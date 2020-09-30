/*
 * MIT License
 *
 * Copyright (c) 2020 atlarge-research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.atlarge.opendc.experiments.sc20.experiment

import com.atlarge.opendc.compute.core.Flavor
import com.atlarge.opendc.compute.core.ServerEvent
import com.atlarge.opendc.compute.core.workload.PerformanceInterferenceModel
import com.atlarge.opendc.compute.core.workload.VmWorkload
import com.atlarge.opendc.compute.metal.NODE_CLUSTER
import com.atlarge.opendc.compute.metal.driver.BareMetalDriver
import com.atlarge.opendc.compute.metal.service.ProvisioningService
import com.atlarge.opendc.compute.virt.HypervisorEvent
import com.atlarge.opendc.compute.virt.driver.SimpleVirtDriver
import com.atlarge.opendc.compute.virt.service.SimpleVirtProvisioningService
import com.atlarge.opendc.compute.virt.service.VirtProvisioningEvent
import com.atlarge.opendc.compute.virt.service.allocation.AllocationPolicy
import com.atlarge.opendc.core.failure.CorrelatedFaultInjector
import com.atlarge.opendc.core.failure.FailureDomain
import com.atlarge.opendc.core.failure.FaultInjector
import com.atlarge.opendc.experiments.sc20.experiment.monitor.ExperimentMonitor
import com.atlarge.opendc.experiments.sc20.trace.Sc20StreamingParquetTraceReader
import com.atlarge.opendc.format.environment.EnvironmentReader
import com.atlarge.opendc.format.trace.TraceReader
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import mu.KotlinLogging
import java.io.File
import java.time.Clock
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * The logger for this experiment.
 */
private val logger = KotlinLogging.logger {}

/**
 * Construct the failure domain for the experiments.
 */
suspend fun createFailureDomain(
    coroutineScope: CoroutineScope,
    clock: Clock,
    seed: Int,
    failureInterval: Double,
    bareMetalProvisioner: ProvisioningService,
    chan: Channel<Unit>
): CoroutineScope {
    val job = coroutineScope.launch {
        chan.receive()
        val random = Random(seed)
        val injectors = mutableMapOf<String, FaultInjector>()
        for (node in bareMetalProvisioner.nodes()) {
            val cluster = node.metadata[NODE_CLUSTER] as String
            val injector =
                injectors.getOrPut(cluster) {
                    createFaultInjector(
                        this,
                        clock,
                        random,
                        failureInterval
                    )
                }
            injector.enqueue(node.metadata["driver"] as FailureDomain)
        }
    }
    return CoroutineScope(coroutineScope.coroutineContext + job)
}

/**
 * Obtain the [FaultInjector] to use for the experiments.
 */
fun createFaultInjector(coroutineScope: CoroutineScope, clock: Clock, random: Random, failureInterval: Double): FaultInjector {
    // Parameters from A. Iosup, A Framework for the Study of Grid Inter-Operation Mechanisms, 2009
    // GRID'5000
    return CorrelatedFaultInjector(
        coroutineScope,
        clock,
        iatScale = ln(failureInterval), iatShape = 1.03, // Hours
        sizeScale = ln(2.0), sizeShape = ln(1.0), // Expect 2 machines, with variation of 1
        dScale = ln(60.0), dShape = ln(60.0 * 8), // Minutes
        random = random
    )
}

/**
 * Create the trace reader from which the VM workloads are read.
 */
fun createTraceReader(path: File, performanceInterferenceModel: PerformanceInterferenceModel, vms: List<String>, seed: Int): Sc20StreamingParquetTraceReader {
    return Sc20StreamingParquetTraceReader(
        path,
        performanceInterferenceModel,
        vms,
        Random(seed)
    )
}

/**
 * Construct the environment for a VM provisioner and return the provisioner instance.
 */
suspend fun createProvisioner(
    coroutineScope: CoroutineScope,
    clock: Clock,
    environmentReader: EnvironmentReader,
    allocationPolicy: AllocationPolicy
): Pair<ProvisioningService, SimpleVirtProvisioningService> {
    val environment = environmentReader.use { it.construct(coroutineScope, clock) }
    val bareMetalProvisioner = environment.platforms[0].zones[0].services[ProvisioningService]

    // Wait for the bare metal nodes to be spawned
    delay(10)

    val scheduler = SimpleVirtProvisioningService(coroutineScope, clock, bareMetalProvisioner, allocationPolicy)

    // Wait for the hypervisors to be spawned
    delay(10)

    return bareMetalProvisioner to scheduler
}

/**
 * Attach the specified monitor to the VM provisioner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun attachMonitor(coroutineScope: CoroutineScope, clock: Clock, scheduler: SimpleVirtProvisioningService, monitor: ExperimentMonitor) {
    val hypervisors = scheduler.drivers()

    // Monitor hypervisor events
    for (hypervisor in hypervisors) {
        // TODO Do not expose VirtDriver directly but use Hypervisor class.
        monitor.reportHostStateChange(clock.millis(), hypervisor, (hypervisor as SimpleVirtDriver).server)
        hypervisor.server.events
            .onEach { event ->
                val time = clock.millis()
                when (event) {
                    is ServerEvent.StateChanged -> {
                        monitor.reportHostStateChange(time, hypervisor, event.server)
                    }
                }
            }
            .launchIn(coroutineScope)
        hypervisor.events
            .onEach { event ->
                when (event) {
                    is HypervisorEvent.SliceFinished -> monitor.reportHostSlice(
                        clock.millis(),
                        event.requestedBurst,
                        event.grantedBurst,
                        event.overcommissionedBurst,
                        event.interferedBurst,
                        event.cpuUsage,
                        event.cpuDemand,
                        event.numberOfDeployedImages,
                        event.hostServer
                    )
                }
            }
            .launchIn(coroutineScope)

        val driver = hypervisor.server.services[BareMetalDriver.Key]
        driver.powerDraw
            .onEach { monitor.reportPowerConsumption(hypervisor.server, it) }
            .launchIn(coroutineScope)
    }

    scheduler.events
        .onEach { event ->
            when (event) {
                is VirtProvisioningEvent.MetricsAvailable ->
                    monitor.reportProvisionerMetrics(clock.millis(), event)
            }
        }
        .launchIn(coroutineScope)
}

/**
 * Process the trace.
 */
suspend fun processTrace(coroutineScope: CoroutineScope, clock: Clock, reader: TraceReader<VmWorkload>, scheduler: SimpleVirtProvisioningService, chan: Channel<Unit>, monitor: ExperimentMonitor, vmPlacements: Map<String, String> = emptyMap()) {
    try {
        var submitted = 0

        while (reader.hasNext()) {
            val (time, workload) = reader.next()

            submitted++
            delay(max(0, time - clock.millis()))
            coroutineScope.launch {
                chan.send(Unit)
                val server = scheduler.deploy(
                    workload.image.name,
                    workload.image,
                    Flavor(workload.image.maxCores, workload.image.requiredMemory)
                )
                // Monitor server events
                server.events
                    .onEach {
                        if (it is ServerEvent.StateChanged) {
                            monitor.reportVmStateChange(clock.millis(), it.server)
                        }
                    }
                    .collect()
            }
        }

        scheduler.events
            .takeWhile {
                when (it) {
                    is VirtProvisioningEvent.MetricsAvailable ->
                        it.inactiveVmCount + it.failedVmCount != submitted
                }
            }
            .collect()
        delay(1)
    } finally {
        reader.close()
    }
}
