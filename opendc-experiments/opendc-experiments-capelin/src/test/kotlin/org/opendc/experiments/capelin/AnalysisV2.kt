/*
 * Copyright (c) 2020 AtLarge Research
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

package org.opendc.experiments.capelin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.opendc.compute.service.ComputeService
import org.opendc.compute.service.scheduler.FilterScheduler
import org.opendc.compute.service.scheduler.filters.ComputeFilter
import org.opendc.compute.service.scheduler.filters.RamFilter
import org.opendc.compute.service.scheduler.filters.VCpuFilter
import org.opendc.compute.service.scheduler.weights.CoreRamWeigher

import org.opendc.experiments.workflow.WorkflowSchedulerSpec
import org.opendc.experiments.workflow.replay
import org.opendc.experiments.workflow.setupWorkflowService
import org.opendc.experiments.workflow.toJobs
import org.opendc.workflow.service.scheduler.job.NullJobAdmissionPolicy
import org.opendc.workflow.service.scheduler.job.SubmissionTimeJobOrderPolicy
import org.opendc.workflow.service.scheduler.job.RandomJobOrderPolicy
import org.opendc.workflow.service.scheduler.job.SizeJobOrderPolicy
import org.opendc.workflow.service.scheduler.task.NullTaskEligibilityPolicy
import org.opendc.workflow.service.scheduler.task.SubmissionTimeTaskOrderPolicy
import org.opendc.workflow.service.scheduler.task.RandomTaskEligibilityPolicy

import org.opendc.experiments.capelin.topology.clusterTopology
import org.opendc.experiments.compute.ComputeWorkloadLoader
import org.opendc.experiments.compute.VirtualMachine
import org.opendc.experiments.compute.grid5000
import org.opendc.experiments.compute.registerComputeMonitor
import org.opendc.experiments.compute.replay
import org.opendc.experiments.compute.sampleByLoad
import org.opendc.experiments.compute.setupComputeService
import org.opendc.experiments.compute.setupHosts
import org.opendc.experiments.compute.telemetry.ComputeMonitor
import org.opendc.experiments.compute.telemetry.table.HostTableReader
import org.opendc.experiments.compute.telemetry.table.ServiceTableReader
import org.opendc.experiments.compute.topology.HostSpec
import org.opendc.experiments.compute.trace
import org.opendc.experiments.provisioner.Provisioner
import org.opendc.simulator.kotlin.runSimulation
import org.opendc.workflow.service.scheduler.job.LimitJobAdmissionPolicy
import org.opendc.workflow.service.scheduler.task.DurationTaskOrderPolicy
import org.opendc.workflow.service.scheduler.task.RandomTaskOrderPolicy
import java.io.File
import java.time.Duration
import java.util.Random

/**
 * An integration test suite for the Capelin experiments.
 */
class AnalysisV2 {
    /**
     * The monitor used to keep track of the metrics.
     */
    private lateinit var monitor: TestComputeMonitor

    /**
     * The [FilterScheduler] to use for all experiments.
     */
    private lateinit var computeScheduler: FilterScheduler

    /**
     * The [ComputeWorkloadLoader] responsible for loading the traces.
     */
    private lateinit var workloadLoader: ComputeWorkloadLoader

    /**
     * Set up the experimental environment.
     */
    @BeforeEach
    fun setUp() {
        monitor = TestComputeMonitor()
        computeScheduler = FilterScheduler(
            filters = listOf(ComputeFilter(), VCpuFilter(16.0), RamFilter(1.0)),
            weighers = listOf(CoreRamWeigher(multiplier = 1.0))
        )
        workloadLoader = ComputeWorkloadLoader(File("src/test/resources/trace"))
    }

    /**
     * Test a large simulation setup.
     */
    @Test
    fun testLarge() = runSimulation {
        val seed = 0L
        val workload = createTestWorkload(1.0, seed)
        val topology = createTopology()
        val monitor = monitor

        Provisioner(coroutineContext, clock, seed).use { provisioner ->
            provisioner.runSteps(
                setupComputeService(serviceDomain = "compute.opendc.org", { computeScheduler }),
                registerComputeMonitor(serviceDomain = "compute.opendc.org", monitor),
                setupHosts(serviceDomain = "compute.opendc.org", topology),
                setupWorkflowService(
                    "workflow.opendc.org",
                    "compute.opendc.org",
                    WorkflowSchedulerSpec(
                        schedulingQuantum = Duration.ofMillis(20000),
                        jobAdmissionPolicy = LimitJobAdmissionPolicy(0),
                        jobOrderPolicy = SizeJobOrderPolicy(),
                        taskEligibilityPolicy = RandomTaskEligibilityPolicy(),
                        taskOrderPolicy = RandomTaskOrderPolicy
                    )
                ),
            )

            val service = provisioner.registry.resolve("compute.opendc.org", ComputeService::class.java)!!
            service.replay(clock, workload, seed)
        }

        println(
            "Scheduler " +
                "Success=${monitor.attemptsSuccess} " +
                "Failure=${monitor.attemptsFailure} " +
                "Error=${monitor.attemptsError} " +
                "Pending=${monitor.serversPending} " +
                "Active=${monitor.serversActive}"
        )
        monitor.show("Leuke naam")

        // Note that these values have been verified beforehand
        assertAll(
            { assertEquals(50, monitor.attemptsSuccess, "The scheduler should schedule 50 VMs") },
            { assertEquals(0, monitor.serversActive, "All VMs should finish after a run") },
            { assertEquals(0, monitor.attemptsFailure, "No VM should be unscheduled") },
            { assertEquals(0, monitor.serversPending, "No VM should not be in the queue") },
            { assertEquals(223394101, monitor.idleTime) { "Incorrect idle time" } },
            { assertEquals(66977086, monitor.activeTime) { "Incorrect active time" } },
            { assertEquals(3160276, monitor.stealTime) { "Incorrect steal time" } },
            { assertEquals(0, monitor.lostTime) { "Incorrect lost time" } },
            { assertEquals(5.84093E9, monitor.energyUsage, 1E4) { "Incorrect power draw" } }
        )
    }

    /**
     * Obtain the trace reader for the test.
     */
    private fun createTestWorkload(fraction: Double, seed: Long): List<VirtualMachine> {
        val source = trace("bitbrains-small").sampleByLoad(fraction)
        return source.resolve(workloadLoader, Random(seed))
    }

    /**
     * Obtain the topology factory for the test.
     */
    private fun createTopology(name: String = "topology"): List<HostSpec> {
        val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/env/$name.txt"))
        return stream.use { clusterTopology(stream) }
    }

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

        fun sec2date(secondsLong: Long): String {
            var seconds = secondsLong.toDouble()
            var minute = 60.0
            var hour = 60.0 * minute
            var day = 24.0 * hour
            var month = 30.0 * day
            var year = 12.0 * month
            var years = seconds / year
            var months = (seconds % year) / month
            var days = ((seconds % year) % month) / day
            var hours = (((seconds % year) % month) % day) / hour
            var minutes = ((((seconds % year) % month) % day) % hour) / minute
            var seconds2 = (((((seconds % year) % month) % day) % hour) % minute) / seconds
            return "${years.toInt()}y${months.toInt()}m${days.toInt()}d ${hours.toInt()}h${minutes.toInt()}m${seconds2.toInt()}s"
        }

        fun show(title: String) {
            println(
                "V===========================V\n" +
                    "| ${title}\n" +
                    "|---------------------------|\n" +
                    "| idleTime = ${idleTime} seconds (${sec2date(idleTime)})\n" +
                    "| activeTime = ${activeTime} seconds (${sec2date(activeTime)})\n" +
                    "| stealTime = ${stealTime} seconds (${sec2date(stealTime)})\n" +
                    "| lostTime = ${lostTime} seconds (${sec2date(lostTime)})\n" +
                    "| energyUsage = ${energyUsage} Joules (${energyUsage / 3600} Wh)\n" +
                    "| uptime = ${uptime} seconds (${sec2date(uptime)})\n" +
                    "| attemptsSuccess = ${attemptsSuccess}\n" +
                    "| attemptsFailure = ${attemptsFailure}\n" +
                    "| attemptsError = ${attemptsError}\n" +
                    "| serversPending = ${serversPending}\n" +
                    "| serversActive = ${serversActive}\n" +
                    "Ʌ===========================Ʌ\n"
            )
        }
    }
}
