/*
 * Copyright 2022 Juan Fumero
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

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid2D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.stream.IntStream;

/**
 * Example using TornadoVM. This sample computes a blur filter from an JPEG image using different implementations:
 *
 * --tornado: it runs with TornadoVM using the Loop Parallel API (using a hardware accelerator)
 * --tornadoContext: it runs with TornadoVM using the Parallel Kernel API (using a hardware accelerator)
 * --mt: it runs with JDK 8 Streams (multi-threaded version without TornadoVM)
 * --seq: it runs sequentially (no acceleration)
 *
 * Device Selection from command line:
 *
 * --device=<backendIndex>:<deviceIndex>
 *
 * To obtain the complete list of devices that TornadoVM can see:
 *
 * $ tornado --devices
 *
 */
public class BlurFilter {

    private static final int MAX_ITERATIONS = 10;

    public enum Implementation {
        SEQUENTIAL,
        MT,
        TORNADO_LOOP,
        TORNADO_KERNEL
    }

    private static final HashMap<String, Implementation> VALID_OPTIONS = new HashMap<>();

    static {
        VALID_OPTIONS.put("sequential", Implementation.SEQUENTIAL);
        VALID_OPTIONS.put("seq", Implementation.SEQUENTIAL);
        VALID_OPTIONS.put("mt", Implementation.MT);
        VALID_OPTIONS.put("tornado", Implementation.TORNADO_LOOP);
        VALID_OPTIONS.put("tornadoContext", Implementation.TORNADO_KERNEL);
        VALID_OPTIONS.put("tornadocontext", Implementation.TORNADO_KERNEL);
    }

    private BufferedImage image;
    private Implementation implementation;

    private TaskGraph parallelFilter;
    private TornadoExecutionPlan executionPlan;

    public static final int FILTER_WIDTH = 31;

    private static final String IMAGE_FILE = "./image.jpg";

    int w;
    int h;
    int[] redChannel;
    int[] greenChannel;
    int[] blueChannel;
    int[] alphaChannel;
    int[] redFilter;
    int[] greenFilter;
    int[] blueFilter;
    float[] filter;
    private GridScheduler grid;

    public BlurFilter(Implementation implementation, int backendIndex, int deviceIndex) {
        this.implementation = implementation;
        loadImage();
        initData();
        if (implementation == Implementation.TORNADO_LOOP) {

            // Tasks using the Loop Parallel API
            parallelFilter = new TaskGraph("blur") //
                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, redChannel, greenChannel, blueChannel, filter) //
                    .task("red", BlurFilter::compute, redChannel, redFilter, w, h, filter, FILTER_WIDTH) //
                    .task("green", BlurFilter::compute, greenChannel, greenFilter, w, h, filter, FILTER_WIDTH) //
                    .task("blue", BlurFilter::compute, blueChannel, blueFilter, w, h, filter, FILTER_WIDTH) //
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, redFilter, greenFilter, blueFilter);


            executionPlan = new TornadoExecutionPlan(parallelFilter.snapshot());
            executionPlan.withDevice(TornadoExecutionPlan.getDevice(backendIndex, deviceIndex));

        } else if (implementation == Implementation.TORNADO_KERNEL) {

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
            executionPlan.withDevice(TornadoExecutionPlan.getDevice(backendIndex, deviceIndex));
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

        redChannel = new int[w * h];
        greenChannel = new int[w * h];
        blueChannel = new int[w * h];
        alphaChannel = new int[w * h];

        redFilter = new int[w * h];
        greenFilter = new int[w * h];
        blueFilter = new int[w * h];

        filter = new float[w * h];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                filter[i * h + j] = 1.f / (FILTER_WIDTH * FILTER_WIDTH);
            }
        }
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int rgb = image.getRGB(i, j);
                alphaChannel[i * h + j] = (rgb >> 24) & 0xFF;
                redChannel[i * h + j] = (rgb >> 16) & 0xFF;
                greenChannel[i * h + j] = (rgb >> 8) & 0xFF;
                blueChannel[i * h + j] = (rgb & 0xFF);
            }
        }
    }

    private static void channelConvolutionSequential(int[] channel, int[] channelBlurred, final int numRows, final int numCols, float[] filter, final int filterWidth) {
        assert (filterWidth % 2 == 1);
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                float result = 0.0f;
                for (int filter_r = -filterWidth / 2; filter_r <= filterWidth / 2; filter_r++) {
                    for (int filter_c = -filterWidth / 2; filter_c <= filterWidth / 2; filter_c++) {
                        int image_r = Math.min(Math.max(r + filter_r, 0), (numRows - 1));
                        int image_c = Math.min(Math.max(c + filter_c, 0), (numCols - 1));
                        float image_value = (channel[image_r * numCols + image_c]);
                        float filter_value = filter[(filter_r + filterWidth / 2) * filterWidth + filter_c + filterWidth / 2];
                        result += image_value * filter_value;
                    }
                }
                channelBlurred[r * numCols + c] = result > 255 ? 255 : (int) result;
            }
        }
    }

    private static void compute(int[] channel, int[] channelBlurred, final int numRows, final int numCols, float[] filter, final int filterWidth) {
        assert (filterWidth % 2 == 1);
        for (@Parallel int r = 0; r < numRows; r++) {
            for (@Parallel int c = 0; c < numCols; c++) {
                float result = 0.0f;
                for (int filter_r = -filterWidth / 2; filter_r <= filterWidth / 2; filter_r++) {
                    for (int filter_c = -filterWidth / 2; filter_c <= filterWidth / 2; filter_c++) {
                        int image_r = Math.min(Math.max(r + filter_r, 0), (numRows - 1));
                        int image_c = Math.min(Math.max(c + filter_c, 0), (numCols - 1));
                        float image_value = (channel[image_r * numCols + image_c]);
                        float filter_value = filter[(filter_r + filterWidth / 2) * filterWidth + filter_c + filterWidth / 2];
                        result += image_value * filter_value;
                    }
                }
                channelBlurred[r * numCols + c] = result > 255 ? 255 : (int) result;
            }
        }
    }

    private static void computeWithContext(int[] channel, int[] channelBlurred, final int numRows, final int numCols, float[] filter, final int filterWidth, KernelContext context) {
        assert (filterWidth % 2 == 1);
        int r = context.globalIdx;
        int c = context.globalIdy;
        float result = 0.0f;
        for (int filter_r = -filterWidth / 2; filter_r <= filterWidth / 2; filter_r++) {
            for (int filter_c = -filterWidth / 2; filter_c <= filterWidth / 2; filter_c++) {
                int image_r = Math.min(Math.max(r + filter_r, 0), (numRows - 1));
                int image_c = Math.min(Math.max(c + filter_c, 0), (numCols - 1));
                float image_value = (channel[image_r * numCols + image_c]);
                float filter_value = filter[(filter_r + filterWidth / 2) * filterWidth + filter_c + filterWidth / 2];
                result += image_value * filter_value;
            }
        }
        channelBlurred[r * numCols + c] = result > 255 ? 255 : (int) result;
    }

    private static void computeWithParallelStreams(int[] channel, int[] channelBlurred, final int numRows, final int numCols, float[] filter, final int filterWidth) {
        // For every pixel in the image
        assert (filterWidth % 2 == 1);
        IntStream.range(0, numRows).parallel().forEach(r -> {
            IntStream.range(0, numCols).parallel().forEach(c -> {
                float result = 0.0f;
                for (int filter_r = -filterWidth / 2; filter_r <= filterWidth / 2; filter_r++) {
                    for (int filter_c = -filterWidth / 2; filter_c <= filterWidth / 2; filter_c++) {
                        int image_r = Math.min(Math.max(r + filter_r, 0), (numRows - 1));
                        int image_c = Math.min(Math.max(c + filter_c, 0), (numCols - 1));
                        float image_value = (channel[image_r * numCols + image_c]);
                        float filter_value = filter[(filter_r + filterWidth / 2) * filterWidth + filter_c + filterWidth / 2];
                        result += image_value * filter_value;
                    }
                }
                channelBlurred[r * numCols + c] = result > 255 ? 255 : (int) result;
            });
        });
    }

    private BufferedImage writeFile() {
        setImageFromBuffers();
        try {
            String tmpDirsLocation = System.getProperty("java.io.tmpdir");
            File outputFile = new File(tmpDirsLocation + "/blur.jpeg");
            ImageIO.write(image, "JPEG", outputFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return image;
    }

    private void setImageFromBuffers() {
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                Color c = new Color(redFilter[i * h + j], greenFilter[i * h + j], blueFilter[i * h + j], alphaChannel[i * h + j]);
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
            System.out.println("Sequential Total time (ns) = " + (end - start) + " -- seconds = " + ((end - start) * 1e-9));
        }
    }

    private void parallelStreams() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            computeWithParallelStreams(redChannel, redFilter, w, h, filter, FILTER_WIDTH);
            computeWithParallelStreams(greenChannel, greenFilter, w, h, filter, FILTER_WIDTH);
            computeWithParallelStreams(blueChannel, blueFilter, w, h, filter, FILTER_WIDTH);
            long end = System.nanoTime();
            System.out.println("Streams Total time (ns) = " + (end - start) + " -- seconds = " + ((end - start) * 1e-9));
        }
    }

    private void runTornadoVM() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            executionPlan.execute();
            long end = System.nanoTime();
            System.out.println("Total Time (ns) = " + (end - start) + " -- seconds = " + ((end - start) * 1e-9));
        }
    }

    private void runTornadoVMWithContext() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            executionPlan.withGridScheduler(grid).execute();
            long end = System.nanoTime();
            System.out.println("Total Time (ns) = " + (end - start) + " -- seconds = " + ((end - start) * 1e-9));
        }
    }


    public void run() {
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
        }
        writeFile();
    }

    public static void main(String[] args) {

        String version = "tornado";  // Use acceleration by default
        int backendIndex = 0;
        int deviceIndex = 0;

        if (args.length != 0) {
            for (String arg : args) {
                String option = arg.substring(2);
                if (VALID_OPTIONS.containsKey(option)) {
                    version = option;
                }
                if (option.contains("device=")) {
                    String dev = option.split("=")[1];
                    String[] backendDevice = dev.split(":");
                    backendIndex = Integer.parseInt(backendDevice[0]);
                    deviceIndex = Integer.parseInt(backendDevice[1]);
                }
            }
        }
        BlurFilter blurFilter = new BlurFilter(VALID_OPTIONS.get(version), backendIndex, deviceIndex);
        blurFilter.run();
    }
}