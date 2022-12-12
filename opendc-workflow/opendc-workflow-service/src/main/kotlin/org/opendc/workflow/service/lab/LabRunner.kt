/*
 * Copyright (c) 2022 AtLarge Research
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

package org.opendc.workflow.service.lab

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opendc.workflow.service.WorkflowService

import org.opendc.compute.service.ComputeService
import org.opendc.workflow.service.lab.model.Scenario
import org.opendc.workflow.service.lab.topology.clusterTopology
import org.opendc.experiments.compute.ComputeWorkloadLoader
import org.opendc.experiments.compute.createComputeScheduler
import org.opendc.experiments.compute.export.parquet.ParquetComputeMonitor
import org.opendc.experiments.compute.grid5000
import org.opendc.experiments.compute.registerComputeMonitor
import org.opendc.experiments.compute.replay
import org.opendc.experiments.compute.setupComputeService
import org.opendc.experiments.compute.setupHosts
import org.opendc.experiments.compute.telemetry.ComputeMonitor
import org.opendc.experiments.compute.telemetry.table.HostInfo
import org.opendc.experiments.compute.telemetry.table.HostTableReader
import org.opendc.experiments.compute.telemetry.table.ServerInfo
import org.opendc.experiments.compute.telemetry.table.ServerTableReader
import org.opendc.experiments.compute.telemetry.table.ServiceTableReader
import org.opendc.experiments.provisioner.Provisioner
import org.opendc.simulator.kotlin.runSimulation
//import org.opendc.experiments.workflow.toJobs
import org.opendc.simulator.compute.workload.SimWorkloads
import org.opendc.trace.Trace
import org.opendc.trace.conv.TABLE_TASKS
import org.opendc.trace.conv.TASK_ALLOC_NCPUS
import org.opendc.trace.conv.TASK_ID
import org.opendc.trace.conv.TASK_PARENTS
import org.opendc.trace.conv.TASK_REQ_NCPUS
import org.opendc.trace.conv.TASK_RUNTIME
import org.opendc.trace.conv.TASK_SUBMIT_TIME
import org.opendc.trace.conv.TASK_WORKFLOW_ID
import org.opendc.workflow.api.Job
import org.opendc.workflow.api.Task
import org.opendc.workflow.api.WORKFLOW_TASK_CORES
import org.opendc.workflow.api.WORKFLOW_TASK_DEADLINE
import org.opendc.workflow.service.scheduler.job.NullJobAdmissionPolicy
import org.opendc.workflow.service.scheduler.job.SubmissionTimeJobOrderPolicy
import org.opendc.workflow.service.scheduler.task.NullTaskEligibilityPolicy
import org.opendc.workflow.service.scheduler.task.RandomTaskOrderPolicy
//import org.opendc.workflow.service.WorkflowService
import java.io.File
import java.time.Clock
import java.time.Duration
import java.util.Random
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToLong

//import org.opendc.workflow.service.lab


/**
 * Helper class for running the Capelin experiments.
 *
 * @param envPath The path to the directory containing the environments.
 */

public class LabRunner(
    private val envPath: File,
) {
    /**
     * Run a single [scenario] with the specified seed.
     */
    fun runScenario(scenario: Scenario, seed: Long, iteration: Int) = runSimulation {
        val computeDomain = "compute.opendc.org"
        val workflowDomain = "workflow.opendc.org"
        val topology = clusterTopology(File(envPath, "${scenario.topology.name}.txt"))
        val monitor = TestComputeMonitor()

        Provisioner(coroutineContext, clock, seed).use { provisioner ->
            provisioner.runSteps(
                setupComputeService(computeDomain, { createComputeScheduler(scenario.allocationPolicy, Random(it.seeder.nextLong())) }),
                setupHosts(computeDomain, topology),
                registerComputeMonitor(computeDomain, monitor),
                setupWorkflowService(
                    workflowDomain,
                    computeDomain,
                    WorkflowSchedulerSpec(
                        schedulingQuantum = scenario.schedQuantum,
                        jobAdmissionPolicy = scenario.jobAdmissionPolicy,
                        jobOrderPolicy = scenario.jobOrderPolicy,
                        taskEligibilityPolicy = scenario.taskEligibilityPolicy,
                        taskOrderPolicy = scenario.taskOrderPolicy
                    )
                ),
            )

            val operationalPhenomena = scenario.operationalPhenomena
            val failureModel =
                if (operationalPhenomena.failureFrequency > 0) {
                    grid5000(Duration.ofSeconds((operationalPhenomena.failureFrequency * 60).roundToLong()))
                } else {
                    null
                }

            val service = provisioner.registry.resolve(workflowDomain, WorkflowService::class.java)!!

            service.replay(clock, scenario.workload.source.toJobs())
        }
        monitor.show("testTrace", "results_monitor.csv")

        if (iteration == 0) {
            File("results.csv").printWriter().use { out ->
                out.println("energy, idle_t, active_t, up_t, util")
                out.print("${monitor.energyUsage.sum() / 3600}, ${monitor.idleTime}, ${monitor.activeTime}, ${monitor.uptime}, ${monitor.cpuUtilization.average()}")
            }
        } else {
            File("results.csv").appendText("\n${monitor.energyUsage.sum() / 3600}, ${monitor.idleTime}, ${monitor.activeTime}, ${monitor.uptime}, ${monitor.cpuUtilization.average()}")
        }
    }
}

class TestComputeMonitor : ComputeMonitor {
    var attemptsSuccess = 0
    var attemptsFailure = 0
    var attemptsError = 0
    var serversPending = 0
    var serversActive = 0
    var hostsUp = 0
    var hostsDown = 0
    var serversTotal = 0

    override fun record(reader: ServiceTableReader) {
        attemptsSuccess = reader.attemptsSuccess
        attemptsFailure = reader.attemptsFailure
        attemptsError = reader.attemptsError
        serversPending = reader.serversPending
        serversActive = reader.serversActive
        hostsUp = reader.hostsUp
        hostsDown = reader.hostsDown
        serversTotal = reader.serversTotal
    }

    var idleTime = 0L
    var activeTime = 0L
    var stealTime = 0L
    var lostTime = 0L
    var energyUsage: List<Double> = listOf()
    var powerUsage: List<Double> = listOf()
    var uptime = 0L
    var downtime = 0L
    var cpuLimit = 0.0
    var cpuUsage = 0.0
    var cpuDemand = 0.0
    var cpuUtilization: List<Double> = listOf()

    override fun record(reader: HostTableReader) {
        idleTime += reader.cpuIdleTime
        activeTime += reader.cpuActiveTime
        stealTime += reader.cpuStealTime
        lostTime += reader.cpuLostTime
        energyUsage += reader.powerTotal
        powerUsage += reader.powerUsage
        uptime += reader.uptime
        downtime += reader.downtime
        cpuLimit += reader.cpuLimit
        cpuUsage += reader.cpuUsage
        cpuDemand += reader.cpuDemand
        cpuUtilization += reader.cpuUtilization
    }

    var serverCpuLimit = 0.0
    var serverCpuActiveTime = 0L
    var serverCpuIdleTime = 0L
    var serverCpuStealTime = 0L
    var serverCpuLostTime = 0L
    var sysUptime = 0L
    var sysDowntime = 0L
    var serverProvisionTime = ""
    var sysBootTime = ""
    var timestamp = ""
    var serverInfo: List<ServerInfo?> = listOf()
    var hostInfo: List<HostInfo?> = listOf()

    override fun record(reader: ServerTableReader) {
        sysUptime += reader.uptime
        sysDowntime += reader.downtime
        serverCpuLimit += reader.cpuLimit
        serverCpuActiveTime += reader.cpuActiveTime
        serverCpuIdleTime += reader.cpuIdleTime
        serverCpuStealTime += reader.cpuStealTime
        serverCpuLostTime += reader.cpuLostTime
//        serverProvisionTime = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss").format(LocalDateTime.ofInstant(reader.provisionTime, ZoneOffset.UTC))
//        sysBootTime = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss").format(LocalDateTime.ofInstant(reader.bootTime, ZoneOffset.UTC))
//        timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss").format(LocalDateTime.ofInstant(reader.timestamp, ZoneOffset.UTC))
//        serverInfo += reader.server
//        hostInfo += reader.host
    }

    fun show(title: String, fPath: String="") {
        var metrics: String = "monitor; metric; value; unit\n" +
            "host; idleTime; ${idleTime}; seconds\n" +
            "host; activeTime; ${activeTime}; seconds\n" +
            "host; stealTime; ${stealTime}; seconds\n" +
            "host; lostTime; ${lostTime}; seconds\n" +
            "host; energyUsage; ${energyUsage}; J per cycle\n" +
            "host; powerUsage; ${powerUsage}; W per cycle\n" +
            "host; uptime; ${uptime}; ms\n" +
            "host; downtime; ${downtime}; ms\n" +
            "host; cpuLimit; ${cpuLimit}; MHz\n" +
            "host; cpuDemand; ${cpuDemand}; MHz\n" +
            "host; cpuUtilization; ${cpuUtilization}; %\n" +
            "service; attemptsSuccess; ${attemptsSuccess};\n" +
            "service; attemptsFailure; ${attemptsFailure};\n" +
            "service; attemptsError; ${attemptsError};\n" +
            "service; serversPending; ${serversPending};\n" +
            "service; serversActive; ${serversActive};\n" +
            "service; hostsUp; ${hostsUp};\n" +
            "service; hostsDown; ${hostsDown};\n" +
            "service; serversTotal; ${serversTotal};\n" +
            "server; cpuLimit; ${serverCpuLimit}; MHz\n" +
            "server; cpuActiveTime; ${serverCpuActiveTime}; seconds\n" +
            "server; cpuIdleTime; ${serverCpuIdleTime}; seconds\n" +
            "server; cpuStealTime; ${serverCpuStealTime}; seconds\n" +
            "server; cpuLostTime; ${serverCpuLostTime}; seconds\n" +
            "server; sysUptime; ${sysUptime}; ms\n" +
            "server; sysDowntime; ${sysDowntime}; ms\n"

        println(metrics)
        if (fPath != "") { File(fPath).printWriter().use { out -> out.println(metrics) } }
    }
}
