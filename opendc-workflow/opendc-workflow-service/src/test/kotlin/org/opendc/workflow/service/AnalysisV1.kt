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

import org.opendc.workflow.service.lab.LabRunner
import org.opendc.workflow.service.lab.model.Scenario
import org.opendc.workflow.service.lab.model.OperationalPhenomena
import org.opendc.workflow.service.lab.model.Topology
import org.opendc.workflow.service.lab.model.Workload
//import org.opendc.experiments.compute.trace

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
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
import org.opendc.experiments.compute.telemetry.table.HostInfo
import org.opendc.experiments.compute.telemetry.table.HostTableReader
import org.opendc.experiments.compute.telemetry.table.ServerInfo
import org.opendc.experiments.compute.telemetry.table.ServerTableReader
import org.opendc.experiments.compute.telemetry.table.ServiceTableReader
import org.opendc.experiments.compute.topology.HostSpec
//import org.opendc.experiments.compute.trace
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
import org.opendc.trace.TableColumnType
import org.opendc.trace.Trace
import org.opendc.workflow.service.scheduler.job.LimitJobAdmissionPolicy
import org.opendc.workflow.service.scheduler.job.NullJobAdmissionPolicy
import org.opendc.workflow.service.scheduler.job.SizeJobOrderPolicy
import org.opendc.workflow.service.scheduler.job.SubmissionTimeJobOrderPolicy
import org.opendc.workflow.service.scheduler.task.NullTaskEligibilityPolicy
import org.opendc.workflow.service.scheduler.task.RandomTaskEligibilityPolicy
import org.opendc.workflow.service.scheduler.task.RandomTaskOrderPolicy
import org.opendc.workflow.service.scheduler.task.SubmissionTimeTaskOrderPolicy
import org.opendc.workflow.service.scheduler.task.TaskFlowTaskEligibilityPolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.UUID
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random


/**
 * Data Analysis Test
 */
@DisplayName("Analysis")
internal class AnalysisV1 {
//    @Test
    fun testRunner() {
        val envPath = File("src/test/resources/env")
        val outPath = "results_monitor.csv"
        val trace = Trace.open(
            Paths.get(checkNotNull(WorkflowServiceTest::class.java.getResource("/askalon_ee.gwf")).toURI()),
            format = "gwf"
        )

        val runner = LabRunner(envPath, outPath)
        val scenario = Scenario(
            Topology("topology"),
            Workload("test", trace),
            Duration.ofMillis(100),
            NullJobAdmissionPolicy,
            SubmissionTimeJobOrderPolicy(),
            NullTaskEligibilityPolicy, // TaskFlowTaskEligibilityPolicy(clock),
            SubmissionTimeTaskOrderPolicy(), // RandomTaskOrderPolicy
            "taskflow", // From a predefined list of computescheduler policies. Custom can be defined there
            OperationalPhenomena(failureFrequency = 24.0 * 7, hasInterference = true),
        )

        val seed = 0L
        val repeats = 1

        for (i in 0 until repeats) {
            assertDoesNotThrow {runner.runScenario(scenario, seed + i.toLong(), i)}
        }
    }

    @Test
    fun testExperiments() {
        val envPath = File("src/test/resources/env")
        // Define experiment as list [name, file, format, topology, computeScheduler]
        val experiments = listOf(
            listOf("askalon_ee", "/askalon_ee.gwf", "gwf", "heterogeneous", "naive"),
            listOf("askalon_ee", "/askalon_ee.gwf", "gwf", "heterogeneous", "random"),
            listOf("askalon_ee", "/askalon_ee.gwf", "gwf", "heterogeneous", "taskflow"),
            listOf("askalon_ee", "/askalon_ee.gwf", "gwf", "homogeneous", "taskflow"),
            listOf("Pegasus_P1_parquet", "/Pegasus_P1_parquet", "wtf", "heterogeneous", "taskflow"),
            listOf("Pegasus_P7_parquet", "/Pegasus_P7_parquet", "wtf", "heterogeneous", "taskflow"),
        )
        val repeats = 1
        for (experiment in experiments) {
            val experimentName = experiment[0]
            val tracePath = experiment[1]
            val traceFormat = experiment[2]
            val experimentTopology = experiment[3]
            val experimentScheduler = experiment[4]
            val trace = Trace.open(
                Paths.get(checkNotNull(WorkflowServiceTest::class.java.getResource(tracePath)).toURI()),
                format = traceFormat
            )
            val resultPath = "results/${experimentName}/${experimentScheduler}"
            Files.createDirectories(Paths.get(resultPath))
            val runner = LabRunner(envPath, "${resultPath}/${experimentTopology}.csv")

            val scenario = Scenario(
                Topology(experimentTopology),
                Workload(experimentName, trace),
                Duration.ofMillis(100),
                NullJobAdmissionPolicy,
                SubmissionTimeJobOrderPolicy(),
                NullTaskEligibilityPolicy, // TaskFlowTaskEligibilityPolicy(clock),
                SubmissionTimeTaskOrderPolicy(), // RandomTaskOrderPolicy
                experimentScheduler, // From a predefined list of computescheduler policies. Custom can be defined there
                OperationalPhenomena(failureFrequency = 24.0 * 7, hasInterference = true),
            )

            val seed = 0L

            for (i in 0 until repeats) {
                assertDoesNotThrow {runner.runScenario(scenario, seed + i.toLong(), i)}
            }
        }


    }

}
