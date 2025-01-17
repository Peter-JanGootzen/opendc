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

package org.opendc.experiments.compute

import org.opendc.compute.service.ComputeService
import org.opendc.compute.simulator.SimHost
import org.opendc.experiments.compute.topology.HOSTSPEC_FASTESTFREQ
import org.opendc.experiments.compute.topology.HOSTSPEC_NORMALIZEDSPEED
import org.opendc.experiments.compute.topology.HOSTSPEC_POWEREFFICIENCY
import org.opendc.experiments.compute.topology.HostSpec
import org.opendc.experiments.provisioner.ProvisioningContext
import org.opendc.experiments.provisioner.ProvisioningStep
import org.opendc.simulator.compute.SimBareMetalMachine
import org.opendc.simulator.compute.kernel.SimHypervisor
import org.opendc.simulator.flow2.FlowEngine
import java.util.SplittableRandom

/**
 * A [ProvisioningStep] that provisions a list of hosts for a [ComputeService].
 *
 * @param serviceDomain The domain name under which the compute service is registered.
 * @param specs A list of [HostSpec] objects describing the simulated hosts to provision.
 * @param optimize A flag to indicate that the CPU resources of the host should be merged into a single CPU resource.
 */
public class HostsProvisioningStep internal constructor(
    private val serviceDomain: String,
    private val specs: List<HostSpec>,
    private val optimize: Boolean
) : ProvisioningStep {
    override fun apply(ctx: ProvisioningContext): AutoCloseable {
        val service = requireNotNull(ctx.registry.resolve(serviceDomain, ComputeService::class.java)) { "Compute service $serviceDomain does not exist" }
        val engine = FlowEngine.create(ctx.coroutineContext, ctx.clock)
        val graph = engine.newGraph()
        val hosts = mutableSetOf<SimHost>()

        // By dividing by the number of CPUs we can support multiple CPUs that have differing frequencies
        specs.maxOfOrNull { it -> it.model.cpus.sumOf {it.frequency} / it.model.cpus.size }?.apply {
            for (spec in specs) {
                val freq = spec.model.cpus.sumOf {it.frequency} / spec.model.cpus.size
                val normalizedSpeed = freq / this
                var tdp = spec.model.cpus.sumOf { it.tdp }

                // Set the TDP when it isn't given in the topology.txt
                // - node-A01 is middle of the pack with many cores
                // - node-B01 is half the cores of A and less efficient
                // - node-C01 is half of half the core and more efficient
                if (spec.name.contains("node-A01")) {
                    tdp = 480
                } else if (spec.name.contains("node-B01")) {
                    tdp = 300
                } else if (spec.name.contains("node-C01")) {
                    tdp = 100
                }

                spec.meta[HOSTSPEC_POWEREFFICIENCY] = tdp / spec.model.cpus.size * normalizedSpeed
                spec.meta[HOSTSPEC_NORMALIZEDSPEED] = normalizedSpeed
                spec.meta[HOSTSPEC_FASTESTFREQ] = this
            }
        }

        for (spec in specs) {
            val machine = SimBareMetalMachine.create(graph, spec.model, spec.psuFactory)
            val hypervisor = SimHypervisor.create(spec.multiplexerFactory, SplittableRandom(ctx.seeder.nextLong()))

            val host = SimHost(
                spec.uid,
                spec.name,
                spec.meta,
                ctx.clock,
                machine,
                hypervisor,
                optimize = optimize
            )

            require(hosts.add(host)) { "Host with uid ${spec.uid} already exists" }
            service.addHost(host)
        }

        return AutoCloseable {
            for (host in hosts) {
                host.close()
            }
        }
    }
}
