/*
 * Copyright 2021-2024 Juan Fumero
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

import uk.ac.manchester.tornado.api.DRMode;
import uk.ac.manchester.tornado.api.Policy;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.math.TornadoMath;
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
 * Enable the profiler from the command line:
 * <code>
 *   $ tornado --enableProfiler console --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.HelloTornado
 * </code>
 *
 */
public class HelloTornado {

    /**
     * Set this flag to True if you want to run the sequential code
     */
    private static boolean RUN_SEQUENTIAL = false;

    public static void parallelInitialization(FloatArray data) {
        for (@Parallel int i = 0; i < data.getSize(); i++) {
            data.set(i, i * 2);
        }
    }

    public static void computeSqrt(FloatArray data) {
        for (@Parallel int i = 0; i < data.getSize(); i++) {
            float value = data.get(i);
            data.set(i, TornadoMath.sqrt(value));
        }
    }

    public static void runSequential(FloatArray array) {
        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            parallelInitialization(array);
            computeSqrt(array);
            long end = System.nanoTime();
            System.out.println(STR."Total time (ns): \{end - start}");
        }
    }

    public static void main(String[] args ) {
        // ~512MB of input data
        FloatArray array = new FloatArray(1024 * 1024 * 128);
        TaskGraph taskGraph = new TaskGraph("s0")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, array)
                .task("t0", HelloTornado::parallelInitialization, array)
                .task("t1", HelloTornado::computeSqrt, array)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot());

        // 1. Execute the task-graph
        executionPlan.execute();

        // 2. Enable the Profiler
        TornadoExecutionResult executionResult = executionPlan.withProfiler(ProfilerMode.SILENT)
                .execute();
        // 2.1 Query the profiler
        // Example: Get the GPU Kernel time in nanoseconds:
        System.out.println("Kernel Time in ns: " + executionResult.getProfilerResult().getDeviceKernelTime());

        // 3. Disable the Profiler and run again
        executionPlan.withoutProfiler()
                .execute();

        // 4. Change device (We assume we have at least 2 devices for the driver 0):
        TornadoDevice device = TornadoExecutionPlan.getDevice(0, 1);
        executionPlan.withDevice(device)
                .withProfiler(ProfilerMode.CONSOLE)  // It will dump a JSON entry every time the kernel is executed
                .execute();

        // 5. Enable warmup
        executionPlan.withWarmUp()
                .withoutProfiler()
                .execute();

        // Reset execution
        executionPlan.freeDeviceMemory();
        executionPlan.resetDevice();

        // 6. Enable Dynamic Reconfiguration
        System.out.println("Evaluating Dynamic Reconfiguration");
        executionPlan.withDynamicReconfiguration(Policy.PERFORMANCE, DRMode.PARALLEL)
                .execute();

        // Run again to see which device was selected after the dynamic reconfiguration
        executionPlan.execute();

        if (RUN_SEQUENTIAL) {
            runSequential(array);
        }
    }
}