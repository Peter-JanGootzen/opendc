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
import org.opendc.experiments.compute.trace

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
import org.opendc.experiments.compute.trace
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
import org.opendc.workflow.service.scheduler.job.LimitJobAdmissionPolicy
import org.opendc.workflow.service.scheduler.job.NullJobAdmissionPolicy
import org.opendc.workflow.service.scheduler.job.SizeJobOrderPolicy
import org.opendc.workflow.service.scheduler.job.SubmissionTimeJobOrderPolicy
import org.opendc.workflow.service.scheduler.task.NullTaskEligibilityPolicy
import org.opendc.workflow.service.scheduler.task.RandomTaskEligibilityPolicy
import org.opendc.workflow.service.scheduler.task.RandomTaskOrderPolicy
import org.opendc.workflow.service.scheduler.task.SubmissionTimeTaskOrderPolicy
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
    @Test
    fun testRunner() {
        val envPath = File("src/test/resources/env")
        val trace = Trace.open(
            Paths.get(checkNotNull(WorkflowServiceTest::class.java.getResource("/trace.gwf")).toURI()),
            format = "gwf"
        )

        val runner = LabRunner(envPath)
        val scenario = Scenario(
            Topology("topology"),
            Workload("test", trace),
            Duration.ofMillis(100),
            NullJobAdmissionPolicy,
            SubmissionTimeJobOrderPolicy(),
            NullTaskEligibilityPolicy,
            RandomTaskOrderPolicy,
            "lab1" // From a predefined list of computescheduler policies. Custom can be defined there
        )

        assertDoesNotThrow { runner.runScenario(scenario, seed = 0L) }
    }


    @Test
    fun testTrace() = runSimulation {
        val computeService = "compute.opendc.org"
        val workflowService = "workflow.opendc.org"
        val monitor = TestComputeMonitor()
//        Random.nextLong()
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
//                        jobAdmissionPolicy = LimitJobAdmissionPolicy(0),
                        jobOrderPolicy = SubmissionTimeJobOrderPolicy(),
//                        jobOrderPolicy = SizeJobOrderPolicy(),
                        taskEligibilityPolicy = NullTaskEligibilityPolicy,
//                        taskEligibilityPolicy = RandomTaskEligibilityPolicy(),
                        taskOrderPolicy = RandomTaskOrderPolicy
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

        monitor.show("testTrace", "results.txt")
//        writeMonitorStats(monitor)
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
            SimPsuFactories.simple(CpuPowerModels.linear(350.0, 200.0)), /* TODO: REALISTIC POWER VALUES */
            FlowMultiplexerFactory.forwardingMultiplexer()
        )
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
    //    var energyUsage = 0.0
//    var powerUsage = 0.0
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
    //    var serverInfo: ServerInfo? = null
//    var hostInfo: HostInfo? = null
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

    fun show(title: String, fPath: String="") {
        var metrics_str: String = "V===================================\n" +
            "| ${title} - ComputeMonitor\n" +
            "|-----------------------------------\n" +
            "| HostTableReader:\n" +
            "| \tidleTime = ${idleTime} seconds (${sec2date(idleTime)})\n" +
            "| \tactiveTime = ${activeTime} seconds (${sec2date(activeTime)})\n" +
            "| \tstealTime = ${stealTime} seconds (${sec2date(stealTime)})\n" +
            "| \tlostTime = ${lostTime} seconds (${sec2date(lostTime)})\n" +
            "| \tenergyUsage (J per cycle):\n" +
            "| \t\tcontent = ${energyUsage}\n" +
            "| \t\tcycles = ${energyUsage.size}\n" +
            "| \t\taverage = ${energyUsage.average()} Joules (${energyUsage.average() / 3600} Wh)\n" +
            "| \t\ttotal = ${energyUsage.sum()} Joules (${energyUsage.sum() / 3600} Wh)\n" +
            "| \tpowerUsage (W per cycle):\n" +
            "| \t\tcontent = ${powerUsage}\n" +
            "| \t\tcycles = ${powerUsage.size}\n" +
            "| \t\taverage = ${powerUsage.average()} W\n" +
            "| \t\ttotal = ${powerUsage.sum()} W\n" +
            "| \tuptime = ${uptime} ms (${sec2date(uptime / 1000)})\n" +
            "| \tdowntime = ${downtime} ms (${sec2date(downtime / 1000)})\n" +
            "| \tcpuLimit = ${cpuLimit} MHz\n" +
            "| \tcpuDemand = ${cpuDemand} MHz\n" +
            "| \tcpuUtilization (% per cycle):\n" +
            "| \t\tcontent = ${cpuUtilization}\n" +
            "| \t\tcycles = ${cpuUtilization.size}\n" +
            "| \t\taverage = ${cpuUtilization.average()}%\n" +
            "| \t\ttotal = ${cpuUtilization.sum()}%\n" +
            "|-----------------------------------\n" +
            "| ServiceTableReader:\n" +
            "| \tattemptsSuccess = ${attemptsSuccess}\n" +
            "| \tattemptsFailure = ${attemptsFailure}\n" +
            "| \tattemptsError = ${attemptsError}\n" +
            "| \tserversPending = ${serversPending}\n" +
            "| \tserversActive = ${serversActive}\n" +
            "| \thostsUp = ${hostsUp}\n" +
            "| \thostsDown = ${hostsDown}\n" +
            "| \tserversTotal = ${serversTotal}\n" +
            "|-----------------------------------\n" +
            "| ServerTableReader:\n" +
            "| \tcpuLimit = ${serverCpuLimit} MHz\n" +
            "| \tcpuActiveTime = ${serverCpuActiveTime} seconds (${sec2date(serverCpuActiveTime)})\n" +
            "| \tcpuIdleTime = ${serverCpuIdleTime} seconds (${sec2date(serverCpuIdleTime)})\n" +
            "| \tcpuStealTime = ${serverCpuStealTime} seconds (${sec2date(serverCpuStealTime)})\n" +
            "| \tcpuLostTime = ${serverCpuLostTime} seconds (${sec2date(serverCpuLostTime)})\n" +
            "| \tsysUptime = ${sysUptime} ms (${sec2date(sysUptime / 1000)})\n" +
            "| \tsysDowntime = ${sysDowntime} ms (${sec2date(sysDowntime / 1000)})\n" +
//            "| \tserverProvisionTime = ${serverProvisionTime}\n" +
//            "| \tsysBootTime = ${sysBootTime}\n" +
//            "| \ttimestamp = ${timestamp}\n" +
//            "| \tserverInfo:\n" +
//            "| \t\tid = ${serverInfo?.id}\n" +
//            "| \t\tname = ${serverInfo?.name}\n" +
//            "| \t\ttype = ${serverInfo?.type}\n" +
//            "| \t\tarch = ${serverInfo?.arch}\n" +
//            "| \t\timageId = ${serverInfo?.imageId}\n" +
//            "| \t\timageName = ${serverInfo?.imageName}\n" +
//            "| \t\tcpuCount = ${serverInfo?.cpuCount}\n" +
//            "| \t\tmemCapacity = ${serverInfo?.memCapacity}\n" +
//            "| \thostInfo:\n" +
//            "| \t\tid = ${hostInfo?.id}\n" +
//            "| \t\tname = ${hostInfo?.name}\n" +
//            "| \t\tarch = ${hostInfo?.arch}\n" +
//            "| \t\tcpuCount = ${hostInfo?.cpuCount}\n" +
//            "| \t\tmemCapacity = ${hostInfo?.memCapacity}\n" +
//            "| \tserverInfo = ${hostInfo}\n" +
//            "| \thostInfo = ${hostInfo}\n" +
            "Ʌ===================================\n"

        println(metrics_str)
        if (fPath != "") { File(fPath).printWriter().use { out -> out.println(metrics_str) } }
    }
}
