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
import org.opendc.compute.service.scheduler.filters.HostFilter
import org.opendc.compute.service.scheduler.weights.HostWeigher
import java.time.Clock
import java.util.Random
import kotlin.math.min

/**
 * A [ComputeScheduler] implementation that uses filtering and weighing passes to select
 * the host to schedule a [Server] on.

 * This implementation is based on the filter scheduler from OpenStack Nova.
 * See: https://docs.openstack.org/nova/latest/user/filter-scheduler.html
 *
 * @param filters The list of filters to apply when searching for an appropriate host.
 * @param weighers The list of weighers to apply when searching for an appropriate host.
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
        val slack: Long = server.meta.getOrDefault(WORKFLOW_TASK_SLACK, 0L) as Long;
        val earliestStartTime: Long = server.meta.getOrDefault(WORKFLOW_TASK_MINIMAL_START_TIME, 0L) as Long;
        val remainingSlack: Long = maxOf(0L, slack - (currentTime - earliestStartTime))

        // These checks were commented out in LookAheadPlacement.kt
        if (earliestStartTime < 0) {
            println("[ERR] A task had negative earliest start time.")
        }

        if (currentTime < earliestStartTime) {
            println("[ERR] A task was possibly scheduled before it was submitted.")
        }

        // The server VM can only be mapped to hosts that can fit it.
        val cpuDemand: Int = server.flavor.cpuCount
        val ramDemand: Long = server.flavor.memorySize

        // We know the amount of FLOPs for the task. This is the total amount of FLOPs, so we can divide by the core count.
        val flopWorkload = server.meta.getOrDefault(TASK_WORKLOAD, 0L) as Long;


        // TODO:
        //   - Filter hosts by cores available -done
        //   - Filter hosts by memory available -done
        //   - Somehow get the performance/watt for every machine -done
        //   - Provision on the most power efficient host that we can manage given the slack
        var filteredHosts: MutableList<HostView> = hosts
        filteredHosts = filteredHosts.filter { host -> (host.host.model.cpuCount - host.provisionedCores) > cpuDemand } as MutableList<HostView>
        filteredHosts = filteredHosts.filter { host -> (host.availableMemory) > ramDemand } as MutableList<HostView>

        val subset = filteredHosts.sortedWith(Comparator<HostView>{ a, b ->
            val eff1: Double = a.host.meta.getOrDefault(HOSTSPEC_POWEREFFICIENCY, 1000) as Double
            val eff2: Double = b.host.meta.getOrDefault(HOSTSPEC_POWEREFFICIENCY, 1000) as Double
            when {
                eff1 > eff2 -> 1
                eff1 < eff2 -> -1
                else -> 0
            }
        }) as MutableList<HostView>
        // Example of how to get data from hostView.
        val host = hosts.get(0)
        val cpuLeft = host.host.model.cpuCount - host.provisionedCores
        val ramLeft = host.availableMemory


        // Voodoo starts here..
        // We can access the baremetal host using Java reflection.
        // returns a SimBareMetalMachine from which we don't seem to be able to do anything.
        // Because it is again defined outside this project.
//        val machine = host.host.javaClass.getDeclaredField("machine")
//        val machineFields: SimBareMetalMachine = machine.javaClass.declaredFields

        return when (val maxSize = min(subsetSize, subset.size)) {
            0 -> null
            1 -> subset[0]
            else -> subset[random.nextInt(maxSize)]
        }
    }
}
