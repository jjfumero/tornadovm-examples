/*
 * Copyright 2022-2023 Juan Fumero
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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid2D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Blur-Filter Algorithm taken from CUDA course CS344 from Udacity: {@url https://www.udacity.com/blog/2014/01/update-on-udacity-cs344-intro-to.html}
 * This sample computes a blur filter from an JPEG image using different implementations:
 *
 * `tornado`: it runs with TornadoVM using the Loop Parallel API (using a hardware accelerator)
 * `tornadoContext`: it runs with TornadoVM using the Parallel Kernel API (using a hardware accelerator)
 * `mt`: it runs with JDK 8 Streams (multi-threaded version without TornadoVM)
 * `seq`: it runs sequentially (no acceleration)
 *
 * Device Selection from command line:
 *
 * --device=<backendIndex>:<deviceIndex>
 *
 * To obtain the complete list of devices that TornadoVM can see:
 *
 * $ tornado --devices
 *
 * Example of how to run:
 *
 * a) Enabling TornadoVM
 * <code>
 *     $ tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter tornado
 * </code>
 *
 * b) Running with the Java Streams version
 *
 * <code>
 *     tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter mt
 * </code>
 *
 *
 * To run with JMH:
 *
 * <code>
 *     tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter jmh
 * </code>
 *
 */
public class BlurFilter {

    private static final int MAX_ITERATIONS = 10;

    private BufferedImage image;
    private Options.Implementation implementation;

    private TaskGraph parallelFilter;
    private TornadoExecutionPlan executionPlan;

    public static final int FILTER_WIDTH = 31;

    private static final String IMAGE_FILE = "./images/image.jpg";

    int w;
    int h;
    IntArray redChannel;
    IntArray greenChannel;
    IntArray blueChannel;
    IntArray alphaChannel;
    IntArray redFilter;
    IntArray greenFilter;
    IntArray blueFilter;
    FloatArray filter;
    private GridScheduler grid;

    public BlurFilter(Options.Implementation implementation, int backendIndex, int deviceIndex) {
        this.implementation = implementation;
        loadImage();
        initData();
        if (implementation == Options.Implementation.TORNADO_LOOP) {
            // Tasks using the Loop Parallel API
            parallelFilter = new TaskGraph("blur") //
                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, redChannel, greenChannel, blueChannel, filter) //
                    .task("red", BlurFilter::compute, redChannel, redFilter, w, h, filter, FILTER_WIDTH) //
                    .task("green", BlurFilter::compute, greenChannel, greenFilter, w, h, filter, FILTER_WIDTH) //
                    .task("blue", BlurFilter::compute, blueChannel, blueFilter, w, h, filter, FILTER_WIDTH) //
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, redFilter, greenFilter, blueFilter);

            ImmutableTaskGraph immutableTaskGraph = parallelFilter.snapshot();
            executionPlan = new TornadoExecutionPlan(immutableTaskGraph);

            // Select the device
            TornadoDevice device = TornadoExecutionPlan.getDevice(backendIndex, deviceIndex);
            executionPlan.withDevice(device);

        } else if (implementation == Options.Implementation.TORNADO_KERNEL) {

            // Tasks using the Kernel API
            KernelContext context = new KernelContext();
            grid = new GridScheduler();
            // This version might run slower, since thread block size can influence performance.
            // TornadoVM implements a heuristic for thread block selection (available for loop-parallel API)
            WorkerGrid2D worker = new WorkerGrid2D(w, h);
            grid.setWorkerGrid("blur.red", worker);
            grid.setWorkerGrid("blur.green", worker);
            grid.setWorkerGrid("blur.blue", worker);

            parallelFilter = new TaskGraph("blur") //
                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, redChannel, greenChannel, blueChannel, filter) //
                    .task("red", BlurFilter::computeWithContext, redChannel, redFilter, w, h, filter, FILTER_WIDTH, context) //
                    .task("green", BlurFilter::computeWithContext, greenChannel, greenFilter, w, h, filter, FILTER_WIDTH, context) //
                    .task("blue", BlurFilter::computeWithContext, blueChannel, blueFilter, w, h, filter, FILTER_WIDTH, context) //
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, redFilter, greenFilter, blueFilter);

            executionPlan = new TornadoExecutionPlan(parallelFilter.snapshot());
            executionPlan.withDevice(TornadoExecutionPlan.getDevice(backendIndex, deviceIndex)).withGridScheduler(grid);
        }
    }

    public void loadImage() {
        try {
            image = ImageIO.read(new File(IMAGE_FILE));
        } catch (IOException e) {
            throw new RuntimeException("Input file not found: " + IMAGE_FILE);
        }
    }

    private void initData() {
        w = image.getWidth();
        h = image.getHeight();

        redChannel = new IntArray(w * h);
        greenChannel = new IntArray(w * h);
        blueChannel = new IntArray(w * h);
        alphaChannel = new IntArray(w * h);

        redFilter = new IntArray(w * h);
        greenFilter = new IntArray(w * h);
        blueFilter = new IntArray(w * h);

        filter = new FloatArray(w * h);
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                filter.set(i * h + j,  1.f / (FILTER_WIDTH * FILTER_WIDTH));
            }
        }
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int rgb = image.getRGB(i, j);
                alphaChannel.set(i * h + j,  (rgb >> 24) & 0xFF);
                redChannel.set(i * h + j,  (rgb >> 16) & 0xFF);
                greenChannel.set(i * h + j,  (rgb >> 8) & 0xFF);
                blueChannel.set(i * h + j,  (rgb & 0xFF));
            }
        }
    }

    private static void channelConvolutionSequential(IntArray channel, IntArray channelBlurred, final int numRows, final int numCols, FloatArray filter, final int filterWidth) {
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                float result = 0.0f;
                for (int filter_r = -filterWidth / 2; filter_r <= filterWidth / 2; filter_r++) {
                    for (int filter_c = -filterWidth / 2; filter_c <= filterWidth / 2; filter_c++) {
                        int image_r = Math.min(Math.max(r + filter_r, 0), (numRows - 1));
                        int image_c = Math.min(Math.max(c + filter_c, 0), (numCols - 1));
                        float image_value = channel.get(image_r * numCols + image_c);
                        float filter_value = filter.get((filter_r + filterWidth / 2) * filterWidth + filter_c + filterWidth / 2);
                        result += image_value * filter_value;
                    }
                }
                int finalValue = result > 255 ? 255 : (int) result;
                channelBlurred.set(r * numCols + c, finalValue);
            }
        }
    }

    private static void compute(IntArray channel, IntArray channelBlurred, final int numRows, final int numCols, FloatArray filter, final int filterWidth) {
        for (@Parallel int r = 0; r < numRows; r++) {
            for (@Parallel int c = 0; c < numCols; c++) {
                float result = 0.0f;
                for (int filter_r = -filterWidth / 2; filter_r <= filterWidth / 2; filter_r++) {
                    for (int filter_c = -filterWidth / 2; filter_c <= filterWidth / 2; filter_c++) {
                        int image_r = Math.min(Math.max(r + filter_r, 0), (numRows - 1));
                        int image_c = Math.min(Math.max(c + filter_c, 0), (numCols - 1));
                        float image_value = channel.get(image_r * numCols + image_c);
                        float filter_value = filter.get((filter_r + filterWidth / 2) * filterWidth + filter_c + filterWidth / 2);
                        result += image_value * filter_value;
                    }
                }
                int finalValue = result > 255 ? 255 : (int) result;
                channelBlurred.set(r * numCols + c, finalValue);
            }
        }
    }

    private static void computeWithContext(IntArray channel, IntArray channelBlurred, final int numRows, final int numCols, FloatArray filter, final int filterWidth, KernelContext context) {
        int r = context.globalIdx;
        int c = context.globalIdy;
        float result = 0.0f;
        for (int filter_r = -filterWidth / 2; filter_r <= filterWidth / 2; filter_r++) {
            for (int filter_c = -filterWidth / 2; filter_c <= filterWidth / 2; filter_c++) {
                int image_r = Math.min(Math.max(r + filter_r, 0), (numRows - 1));
                int image_c = Math.min(Math.max(c + filter_c, 0), (numCols - 1));
                float image_value = channel.get(image_r * numCols + image_c);
                float filter_value = filter.get((filter_r + filterWidth / 2) * filterWidth + filter_c + filterWidth / 2);
                result += image_value * filter_value;
            }
        }
        int finalValue = result > 255 ? 255 : (int) result;
        channelBlurred.set(r * numCols + c, finalValue);
    }

    private static void computeWithParallelStreams(IntArray channel, IntArray channelBlurred, final int numRows, final int numCols, FloatArray filter, final int filterWidth) {
        // For every pixel in the image
        assert (filterWidth % 2 == 1);
        IntStream.range(0, numRows).parallel().forEach(r -> {
            IntStream.range(0, numCols).parallel().forEach(c -> {
                float result = 0.0f;
                for (int filter_r = -filterWidth / 2; filter_r <= filterWidth / 2; filter_r++) {
                    for (int filter_c = -filterWidth / 2; filter_c <= filterWidth / 2; filter_c++) {
                        int image_r = Math.min(Math.max(r + filter_r, 0), (numRows - 1));
                        int image_c = Math.min(Math.max(c + filter_c, 0), (numCols - 1));
                        float image_value = channel.get(image_r * numCols + image_c);
                        float filter_value = filter.get((filter_r + filterWidth / 2) * filterWidth + filter_c + filterWidth / 2);
                        result += image_value * filter_value;
                    }
                }
                int finalValue = result > 255 ? 255 : (int) result;
                channelBlurred.set(r * numCols + c, finalValue);
            });
        });
    }

    private BufferedImage writeFile() {
        setImageFromBuffers();
        try {
            File outputFile = new File( "./blur.jpeg");
            ImageIO.write(image, "JPEG", outputFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return image;
    }

    private void setImageFromBuffers() {
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                Color c = new Color(redFilter.get(i * h + j), greenFilter.get(i * h + j), blueFilter.get(i * h + j), alphaChannel.get(i * h + j));
                image.setRGB(i, j, c.getRGB());
            }
        }
    }

    private void sequentialComputation() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            channelConvolutionSequential(redChannel, redFilter, w, h, filter, FILTER_WIDTH);
            channelConvolutionSequential(greenChannel, greenFilter, w, h, filter, FILTER_WIDTH);
            channelConvolutionSequential(blueChannel, blueFilter, w, h, filter, FILTER_WIDTH);
            long end = System.nanoTime();
            System.out.println(STR."Sequential Total time (ns) = \{end - start} -- seconds = \{(end - start) * 1e-9}");
        }
    }

    private void sequentialComputationJHM() {
        channelConvolutionSequential(redChannel, redFilter, w, h, filter, FILTER_WIDTH);
        channelConvolutionSequential(greenChannel, greenFilter, w, h, filter, FILTER_WIDTH);
        channelConvolutionSequential(blueChannel, blueFilter, w, h, filter, FILTER_WIDTH);
    }

    private void parallelStreams() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            computeWithParallelStreams(redChannel, redFilter, w, h, filter, FILTER_WIDTH);
            computeWithParallelStreams(greenChannel, greenFilter, w, h, filter, FILTER_WIDTH);
            computeWithParallelStreams(blueChannel, blueFilter, w, h, filter, FILTER_WIDTH);
            long end = System.nanoTime();
            System.out.println(STR."Streams Total time (ns) = \{end - start} -- seconds = \{(end - start) * 1e-9}");
        }
    }

    private void parallelStreamsJMH() {
        computeWithParallelStreams(redChannel, redFilter, w, h, filter, FILTER_WIDTH);
        computeWithParallelStreams(greenChannel, greenFilter, w, h, filter, FILTER_WIDTH);
        computeWithParallelStreams(blueChannel, blueFilter, w, h, filter, FILTER_WIDTH);
    }

    private void runTornadoVM() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            executionPlan.execute();
            long end = System.nanoTime();
            System.out.println(STR."TornadoVM Total Time (ns) = \{end - start} -- seconds = \{(end - start) * 1e-9}");
        }
    }

    private void runTornadoVMJMH() {
        executionPlan.execute();
    }

    private void runTornadoVMWithContext() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            executionPlan.execute();
            long end = System.nanoTime();
            System.out.println(STR."TornadoVM(kernelAPI) Total Time (ns) = \{end - start} -- seconds = \{(end - start) * 1e-9}");
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
            case TORNADO_KERNEL:
                runTornadoVMWithContext();
                break;
            case JMH:
                runWithJMH();
                break;
        }
        writeFile();
    }

    // Class to run with the JHM Java framework
    @State(Scope.Thread)
    public static class Benchmarking {

        BlurFilter blurFilter;

        @Setup(Level.Trial)
        public void doSetup() {
            // Select here the device to run (backendIndex, deviceIndex)
            blurFilter = new BlurFilter(Options.Implementation.TORNADO_LOOP, 0, 3);
        }

        @Benchmark
        @BenchmarkMode(Mode.AverageTime)
        @Warmup(iterations = 2, time = 60, timeUnit = TimeUnit.SECONDS)
        @Measurement(iterations = 5, time = 30, timeUnit = TimeUnit.SECONDS)
        @OutputTimeUnit(TimeUnit.NANOSECONDS)
        @Fork(1)
        public void jvmSequential(Benchmarking state) {
            state.blurFilter.sequentialComputationJHM();
        }

        @Benchmark
        @BenchmarkMode(Mode.AverageTime)
        @Warmup(iterations = 2, time = 60, timeUnit = TimeUnit.SECONDS)
        @Measurement(iterations = 5, time = 30, timeUnit = TimeUnit.SECONDS)
        @OutputTimeUnit(TimeUnit.NANOSECONDS)
        @Fork(1)
        public void jvmJavaStreams(Benchmarking state) {
            state.blurFilter.parallelStreamsJMH();
        }

        @Benchmark
        @BenchmarkMode(Mode.AverageTime)
        @Warmup(iterations = 2, time = 60, timeUnit = TimeUnit.SECONDS)
        @Measurement(iterations = 5, time = 30, timeUnit = TimeUnit.SECONDS)
        @OutputTimeUnit(TimeUnit.NANOSECONDS)
        @Fork(1)
        public void runTornadoVM(Benchmarking state) {
            state.blurFilter.runTornadoVMJMH();
        }
    }

    private static void runWithJMH() throws RunnerException {
       org.openjdk.jmh.runner.options.Options opt = new OptionsBuilder() //
               .include(BlurFilter.class.getName() + ".*") //
               .mode(Mode.AverageTime) //
               .timeUnit(TimeUnit.NANOSECONDS) //
               .warmupTime(TimeValue.seconds(60)) //
               .warmupIterations(2) //
               .measurementTime(TimeValue.seconds(30)) //
               .measurementIterations(5) //
               .forks(1) //
               .build();
        new Runner(opt).run();
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

        BlurFilter blurFilter = new BlurFilter(Options.getImplementation(version), backendIndex, deviceIndex);
        blurFilter.run();
    }
}