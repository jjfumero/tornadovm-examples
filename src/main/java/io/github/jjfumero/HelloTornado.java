/*
 * Copyright 2021 Juan Fumero
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.jjfumero;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * Simple example to show how to program and how to invoke TornadoVM kernels to run on a hardware accelerator.
 *
 * How to run?
 *
 * <code>
 *    $ tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.HelloTornado
 * </code>
 *
 * Print the generated kernel via STDOUT:
 *
 * <code>
 *     $ tornado --printKernel -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.HelloTornado
 * </code>
 *
 *
 * Check in which device the kernel was executed:
 * <code>
 *   $ tornado --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.HelloTornado
 * </code>
 *
 * Sample output:
 * <code>
 *  Task info: s0.t0
 * 	Backend           : OpenCL
 * 	Device            : NVIDIA GeForce RTX 2060 with Max-Q Design CL_DEVICE_TYPE_GPU (available)
 * 	Dims              : 1
 * 	Global work offset: [0]
 * 	Global work size  : [512]
 * 	Local  work size  : [512, 1, 1]
 * 	Number of workgroups  : [1]
 *
 * Task info: s0.t1
 * 	Backend           : OpenCL
 * 	Device            : NVIDIA GeForce RTX 2060 with Max-Q Design CL_DEVICE_TYPE_GPU (available)
 * 	Dims              : 1
 * 	Global work offset: [0]
 * 	Global work size  : [512]
 * 	Local  work size  : [512, 1, 1]
 * 	Number of workgroups  : [1]
 * </code>
 *
 * Run with the profiler:
 * <code>
 *   $ tornado --enableProfiler console --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.HelloTornado
 * </code>
 *
 *
 */
public class HelloTornado {

    public static void parallelInitialization(FloatArray data) {
        for (@Parallel int i = 0; i < data.getSize(); i++) {
            data.set(i, i);
        }
    }

    public static void computeSquare(FloatArray data) {
        for (@Parallel int i = 0; i < data.getSize(); i++) {
            float value = data.get(i);
            data.set(i,  value * value);
        }
    }

    public static void main(String[] args ) {
        FloatArray array = new FloatArray(512);
        TaskGraph taskGraph = new TaskGraph("s0")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, array)
                .task("t0", HelloTornado::parallelInitialization, array)
                .task("t1", HelloTornado::computeSquare, array)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot());
        executionPlan.execute();
    }
}