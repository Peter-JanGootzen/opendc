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

package org.opendc.compute.service.scheduler

import org.opendc.compute.api.Server
import org.opendc.compute.service.internal.HostView
import java.time.Clock
import java.util.Random
import kotlin.math.min

/**
 * A [ComputeScheduler] implementation that uses filtering and weighing passes to select
 * the host to schedule a [Server] on.

 * This implementation is based on the filter scheduler from OpenStack Nova.
 * See: https://docs.openstack.org/nova/latest/user/filter-scheduler.html
 *
 * @param clock The clock from the provisioner
 * @param subsetSize The size of the subset of best hosts from which a target is randomly chosen.
 * @param random A [Random] instance for selecting
 */


public class TaskFlowScheduler(
    private val clock: Clock,
    private val subsetSize: Int = 1,
    private val random: Random = Random(0),
) : ComputeScheduler {
    /**
     * The pool of hosts available to the scheduler.
     */
    private val hosts = mutableListOf<HostView>()

    private val WORKFLOW_TASK_SLACK: String = "workflow:task:slack"
    private val WORKFLOW_TASK_MINIMAL_START_TIME: String = "workflow:task:minimalStartTime"
    private val TASK_WORKLOAD: String = "workload_flops"
    private val HOSTSPEC_NORMALIZEDSPEED: String = "hostspec:normalizedSpeed"
    private val HOSTSPEC_POWEREFFICIENCY: String = "hostspec:powerEfficiency"
    // If a host has a normalized speed of 1, this is its frequency.
    private val HOSTSPEC_FASTESTFREQ: String = "hostspec:fastestFreq"

    init {
        require(subsetSize >= 1) { "Subset size must be one or greater" }
    }

    override fun addHost(host: HostView) {
        hosts.add(host)
    }

    override fun removeHost(host: HostView) {
        hosts.remove(host)
    }

    override fun select(server: Server): HostView? {
        // Get the simulated time. Starts at 0 when the simulator starts.
        val currentTime: Long = clock.millis()

        // Get the slack for the task that will be running on the VM.
        val slack: Long = server.meta[WORKFLOW_TASK_SLACK] as Long
        val earliestStartTime: Long = server.meta[WORKFLOW_TASK_MINIMAL_START_TIME] as Long

        // These checks were commented out in LookAheadPlacement.kt
        if (earliestStartTime < 0) {
            println("[ERR] A task had negative earliest start time in the TaskFlowScheduler.")
        }

        if (currentTime < earliestStartTime) {
            println("[ERR] A task was possibly scheduled before it was submitted in the TaskFlowScheduler.")
        }

        // The server VM can only be mapped to hosts that can fit it.
        val cpuDemand: Int = server.flavor.cpuCount
        val ramDemand: Long = server.flavor.memorySize

        // We know the amount of FLOPs for the task. This is the total amount of FLOPs, so we can divide by the core count.
        val flopWorkload = server.meta[TASK_WORKLOAD] as Long

        // Filter the hosts according to the requirements of the task.
        val filteredHosts1: MutableList<HostView> = hosts
        val filteredHosts2 = filteredHosts1.filter { host -> (host.host.model.cpuCount - host.provisionedCores) > cpuDemand }
        val filteredHosts3 = filteredHosts2.filter { host -> (host.availableMemory) > ramDemand }

        val sorted = filteredHosts3.sortedWith { a, b ->
            val eff1: Double = (a.host.meta[HOSTSPEC_POWEREFFICIENCY] as Double)
            val eff2: Double = (b.host.meta[HOSTSPEC_POWEREFFICIENCY] as Double)
            when {
                eff1 > eff2 -> 1
                eff1 < eff2 -> -1
                else -> 0
            }
        }

        // Choose the host that is the most efficient and capable of executing the task in time.
        for (host in sorted) {
            // Assume one FLOP per cycle. Default value is not used.
            val normalFlopsPerSecond = host.host.meta.getOrDefault(HOSTSPEC_FASTESTFREQ, 1000) as Double
            val normalizedSpeed = host.host.meta.getOrDefault(HOSTSPEC_NORMALIZEDSPEED, 1.0) as Double
            val normalRuntime = flopWorkload / (normalFlopsPerSecond * cpuDemand)
            val expectedRuntime = normalRuntime /  normalizedSpeed

            // Task fits, so we select this host.
            if (expectedRuntime <= normalRuntime + slack) {
                return host
            }
        }

        // If we get here, there is no best host, so return one at random.
        return when (val maxSize = sorted.size) {
            0 -> null
            1 -> sorted[0]
            else -> sorted[random.nextInt(maxSize)]
        }
    }
}
