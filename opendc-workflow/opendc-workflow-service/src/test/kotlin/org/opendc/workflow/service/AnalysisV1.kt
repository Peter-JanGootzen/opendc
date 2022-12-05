/*
 * Copyright (c) 2021 AtLarge Research
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

package org.opendc.workflow.service

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.opendc.compute.service.scheduler.ComputeScheduler
import org.opendc.compute.service.scheduler.FilterScheduler
import org.opendc.compute.service.scheduler.filters.ComputeFilter
import org.opendc.compute.service.scheduler.filters.RamFilter
import org.opendc.compute.service.scheduler.filters.VCpuFilter
import org.opendc.compute.service.scheduler.weights.VCpuWeigher
import org.opendc.experiments.compute.setupComputeService
import org.opendc.experiments.compute.telemetry.ComputeMonitor
import org.opendc.experiments.compute.registerComputeMonitor
import org.opendc.experiments.compute.setupHosts
import org.opendc.experiments.compute.telemetry.table.HostTableReader
import org.opendc.experiments.compute.telemetry.table.ServiceTableReader
import org.opendc.experiments.compute.topology.HostSpec
import org.opendc.experiments.provisioner.Provisioner
import org.opendc.experiments.provisioner.ProvisioningContext
import org.opendc.experiments.workflow.WorkflowSchedulerSpec
import org.opendc.experiments.workflow.replay
import org.opendc.experiments.workflow.setupWorkflowService
import org.opendc.experiments.workflow.toJobs
import org.opendc.simulator.compute.SimPsuFactories
import org.opendc.simulator.compute.model.MachineModel
import org.opendc.simulator.compute.model.MemoryUnit
import org.opendc.simulator.compute.model.ProcessingNode
import org.opendc.simulator.compute.model.ProcessingUnit
import org.opendc.simulator.compute.power.CpuPowerModel
import org.opendc.simulator.compute.power.CpuPowerModels
import org.opendc.simulator.flow2.mux.FlowMultiplexerFactory
import org.opendc.simulator.kotlin.runSimulation
import org.opendc.trace.Trace
import org.opendc.workflow.service.scheduler.job.NullJobAdmissionPolicy
import org.opendc.workflow.service.scheduler.job.SubmissionTimeJobOrderPolicy
import org.opendc.workflow.service.scheduler.task.NullTaskEligibilityPolicy
import org.opendc.workflow.service.scheduler.task.SubmissionTimeTaskOrderPolicy
import java.io.File
import java.nio.file.Paths
import java.time.Duration
import java.util.UUID

class TestComputeMonitor : ComputeMonitor {
    var attemptsSuccess = 0
    var attemptsFailure = 0
    var attemptsError = 0
    var serversPending = 0
    var serversActive = 0

    override fun record(reader: ServiceTableReader) {
        attemptsSuccess = reader.attemptsSuccess
        attemptsFailure = reader.attemptsFailure
        attemptsError = reader.attemptsError
        serversPending = reader.serversPending
        serversActive = reader.serversActive
    }

    var idleTime = 0L
    var activeTime = 0L
    var stealTime = 0L
    var lostTime = 0L
    var energyUsage = 0.0
    var uptime = 0L

    override fun record(reader: HostTableReader) {
        idleTime += reader.cpuIdleTime
        activeTime += reader.cpuActiveTime
        stealTime += reader.cpuStealTime
        lostTime += reader.cpuLostTime
        energyUsage += reader.powerTotal
        uptime += reader.uptime
    }
}

/**
 * Data Analysis Test
 */
@DisplayName("Analysis")
internal class AnalysisV1 {
    @Test
    fun testTrace() = runSimulation {
        val computeService = "compute.opendc.org"
        val workflowService = "workflow.opendc.org"
        val monitor = TestComputeMonitor()

        Provisioner(coroutineContext, clock, seed = 0L).use { provisioner ->
            val scheduler: (ProvisioningContext) -> ComputeScheduler = {
                FilterScheduler(
                    filters = listOf(ComputeFilter(), VCpuFilter(1.0), RamFilter(1.0)),
                    weighers = listOf(VCpuWeigher(1.0, multiplier = 1.0))
                )
            }

            provisioner.runSteps(
                // Configure the ComputeService that is responsible for mapping virtual machines onto physical hosts
                setupComputeService(computeService, scheduler, schedulingQuantum = Duration.ofSeconds(1)),
                setupHosts(computeService, List(4) { createHostSpec(it) }),

                // Configure the WorkflowService that is responsible for scheduling the workflow tasks onto machines
                setupWorkflowService(
                    workflowService,
                    computeService,
                    WorkflowSchedulerSpec(
                        schedulingQuantum = Duration.ofMillis(100),
                        jobAdmissionPolicy = NullJobAdmissionPolicy,
                        jobOrderPolicy = SubmissionTimeJobOrderPolicy(),
                        taskEligibilityPolicy = NullTaskEligibilityPolicy,
                        taskOrderPolicy = SubmissionTimeTaskOrderPolicy()
                    )
                ),
                registerComputeMonitor(computeService, monitor)
            )

            val service = provisioner.registry.resolve(workflowService, WorkflowService::class.java)!!

            val trace = Trace.open(
                Paths.get(checkNotNull(WorkflowServiceTest::class.java.getResource("/trace.gwf")).toURI()),
                format = "gwf"
            )
            service.replay(clock, trace.toJobs())
        }


        println(
            "Scheduler " +
                "Success=${monitor.attemptsSuccess} " +
                "Failure=${monitor.attemptsFailure} " +
                "Error=${monitor.attemptsError} " +
                "Pending=${monitor.serversPending} " +
                "Active=${monitor.serversActive} " +
                "Energy=${monitor.energyUsage} " +
                "Uptime=${monitor.uptime}"
        )
        writeMonitorStats(monitor)
    }

    private fun writeMonitorStats(monitor : TestComputeMonitor) {
        File("results.txt").printWriter().use { out ->
            out.println("EnergyUsage:${monitor.energyUsage}")
            out.println("ActiveTime:${monitor.activeTime}")
            out.println("IdleTime:${monitor.idleTime}")
            out.println("UpTime:${monitor.uptime}")
            out.println("StealTime:${monitor.stealTime}")
            out.println("LostTime:${monitor.lostTime}")
        }
    }

    /**
     * Construct a [HostSpec] for a simulated host.
     */
    private fun createHostSpec(uid: Int): HostSpec {
        // Machine model based on: https://www.spec.org/power_ssj2008/results/res2020q1/power_ssj2008-20191125-01012.html
        val node = ProcessingNode("AMD", "am64", "EPYC 7742", 32)
        val cpus = List(node.coreCount) { ProcessingUnit(node, it, 3400.0) }
        val memory = List(8) { MemoryUnit("Samsung", "Unknown", 2933.0, 16_000) }

        val machineModel = MachineModel(cpus, memory)

        return HostSpec(
            UUID(0, uid.toLong()),
            "host-$uid",
            emptyMap(),
            machineModel,
            SimPsuFactories.simple(CpuPowerModels.linear(100.0, 50.0)), /* TODO: REALISTIC POWER VALUES */
            FlowMultiplexerFactory.forwardingMultiplexer()
        )
    }
}
