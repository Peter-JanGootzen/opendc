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

description = "Workflow orchestration service for OpenDC"

/* Build configuration */
plugins {
    `kotlin-conventions`
    `testing-conventions`
    `jacoco-conventions`
    `benchmark-conventions`
    distribution
}

dependencies {
    api(projects.opendcWorkflow.opendcWorkflowApi)
    api(projects.opendcCompute.opendcComputeApi)
    implementation(projects.opendcCommon)
    implementation(libs.kotlin.logging)

    api(projects.opendcExperiments.opendcExperimentsCompute)

    implementation(projects.opendcSimulator.opendcSimulatorCore)
    implementation(projects.opendcSimulator.opendcSimulatorCompute)
    implementation(projects.opendcCompute.opendcComputeSimulator)

    implementation(libs.clikt)
    implementation(libs.progressbar)
    implementation(libs.kotlin.logging)
    implementation(libs.jackson.dataformat.csv)
    implementation(projects.opendcTrace.opendcTraceGwf)
    implementation(projects.opendcTrace.opendcTraceWtf)


    testImplementation(projects.opendcSimulator.opendcSimulatorCore)
    testImplementation(projects.opendcExperiments.opendcExperimentsCompute)
    testImplementation(projects.opendcExperiments.opendcExperimentsWorkflow)
//    testImplementation(projects.opendcTrace.opendcTraceApi)
//    testRuntimeOnly(projects.opendcTrace.opendcTraceGwf)
    runtimeOnly(projects.opendcTrace.opendcTraceOpendc)
    testRuntimeOnly(libs.log4j.core)
    testRuntimeOnly(libs.log4j.slf4j)
}
