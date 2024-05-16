/*
 * Copyright 2024 Juan Fumero
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

import io.github.jjfumero.common.Options;
import org.openjdk.jmh.runner.RunnerException;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.math.TornadoMath;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

import java.util.stream.IntStream;

/**
 * <code>
 *     $ tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Montecarlo
 * </code>
 */
public class Montecarlo {

    private Options.Implementation implementation;
    private TornadoExecutionPlan executionPlan;

    private static final int SIZE = 8192 * 2;

    FloatArray outputFractal;

    public Montecarlo(Options.Implementation implementation, int backendIndex, int deviceIndex) {
        this.implementation = implementation;
        outputFractal = new FloatArray(SIZE);
        if (implementation == Options.Implementation.TORNADO_LOOP) {
            TaskGraph graph = new TaskGraph("task") //
                    .task("compute", Montecarlo::monteCarlo, outputFractal, SIZE) //
                    .transferToHost(DataTransferMode.UNDER_DEMAND, outputFractal);

            ImmutableTaskGraph immutableTaskGraph = graph.snapshot();
            executionPlan = new TornadoExecutionPlan(immutableTaskGraph);

            // Select the device
            TornadoDevice device = TornadoExecutionPlan.getDevice(backendIndex, deviceIndex);
            executionPlan.withDevice(device);
        }
    }

    private static float compute(int idx, int iter) {
        long seed = idx;
        float sum = 0.0f;
        for (int j = 0; j < iter; ++j) {
            seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            float x = (seed & 0x0FFFFFFF) / 268435455f;
            seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            float y = (seed & 0x0FFFFFFF) / 268435455f;
            float dist = TornadoMath.sqrt(x * x + y * y);
            if (dist <= 1.0f) {
                sum += 1.0f;
            }
        }
        return sum;
    }

    public static void monteCarlo(FloatArray result, int size) {
        final int iter = 25000;
        for (@Parallel int idx = 0; idx < size; idx++) {
            long seed = idx;
            float sum = 0.0f;
            for (int j = 0; j < iter; ++j) {
                seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                float x = (seed & 0x0FFFFFFF) / 268435455f;
                seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                float y = (seed & 0x0FFFFFFF) / 268435455f;
                float dist = TornadoMath.sqrt(x * x + y * y);
                if (dist <= 1.0f) {
                    sum += 1.0f;
                }
            }
            sum = sum * 4;
            result.set(idx, sum / iter);
        }
    }



    public static void monteCarloStreams(FloatArray result, int size) {
        final int iter = 25000;
        IntStream.range(0, size).parallel().forEach(idx -> {;
            float sum = compute(idx, iter);
            sum = sum * 4;
            result.set(idx, sum / iter);
        });
    }

    private void sequentialComputation() {
        for (int i = 0; i< 100; i++) {
            long start = System.nanoTime();
            monteCarlo(outputFractal, SIZE);
            long end = System.nanoTime();
            System.out.println("Sequential Total Time (ns) = " + (end - start) + " -- seconds = " + ((end - start) * 1e-9));
        }
    }

    private void parallelStreams() {
        for (int i = 0; i< 100; i++) {
            long start = System.nanoTime();
            monteCarloStreams(outputFractal, SIZE);
            long end = System.nanoTime();
            System.out.println("Streams Total Time (ns) = " + (end - start) + " -- seconds = " + ((end - start) * 1e-9));
        }
    }

    public void runTornadoVM() {
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            executionPlan.execute();
            long end = System.nanoTime();
            System.out.println("TornadoVM Total Time (ns) = " + (end - start) + " -- seconds = " + ((end - start) * 1e-9));
        }
    }

    public void run() throws RunnerException {
        switch (implementation) {
            case SEQUENTIAL:
                sequentialComputation();
                break;
            case MT:
                parallelStreams();
                break;
            case TORNADO_LOOP:
                runTornadoVM();
                break;
        }
    }

    public static void main(String[] args) throws RunnerException {

        String version = "tornado";  // Use acceleration by default
        int backendIndex = 0;
        int deviceIndex = 0;

        for (String arg : args) {
            if (Options.isValid(arg)) {
                version = arg;
            }
            if (arg.contains("device=")) {
                String dev = arg.split("=")[1];
                String[] backendDevice = dev.split(":");
                backendIndex = Integer.parseInt(backendDevice[0]);
                deviceIndex = Integer.parseInt(backendDevice[1]);
            }
        }

        Montecarlo montecarlo = new Montecarlo(Options.getImplementation(version), backendIndex, deviceIndex);
        montecarlo.run();
    }

}
