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
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
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
import java.util.stream.IntStream;

/**
 * Example of Multi-Backend with Concurrent Execution on Multiple-Devices. For this example to run, it requires TornadoVM
 * to be configured with multiple backends. For example:
 *
 * <code>
 *     cd <$TORNADOVM_ROOT>
 *     make BACKEND=spirv,opencl,ptx
 * </code>
 *
 * This example takes an input JPEG image and generates two images: one with a blur effect, and another one in black and white.
 * Processing this in TornadoVM can be done with a Task-Graph of 4 tasks. One task to process the Black and White, and another
 * three tasks to process a blur effect of an image colour. The colured image can be decomposed in channels (Red, Blue, Green),
 * and each channel can be processed independently using a different task.
 *
 * Thus, all tasks are fully independent and developers can enable the TornadoVM execution plan to process them concurrently
 * with different devices, even with different backends.
 *
 *
 * Example of how to run:
 *
 * a) Enabling TornadoVM
 * <code>
 *     $ tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.MultiImageProcessor tornado
 * </code>
 *
 * b) Running with the Java Streams version
 *
 * <code>
 *     tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.MultiImageProcessor mt
 * </code>
 *
 */
public class MultiImageProcessor {

    private static final int MAX_ITERATIONS = 10;

    private BufferedImage image;

    IntArray imageRGB;

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

    public MultiImageProcessor(Options.Implementation implementation, int backendIndex, int deviceIndex) {
        this.implementation = implementation;
        loadImage();
        initData();
        if (implementation == Options.Implementation.TORNADO_LOOP) {
            // Tasks using the Loop Parallel API
            parallelFilter = new TaskGraph("imageProcessor") //
                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, imageRGB, redChannel, greenChannel, blueChannel, filter) //
                    .task("blackAndWhite", MultiImageProcessor::blackAndWhiteTransform, imageRGB, w, h) //
                    .task("blurRed", MultiImageProcessor::compute, redChannel, redFilter, w, h, filter, FILTER_WIDTH) //
                    .task("blurGreen", MultiImageProcessor::compute, greenChannel, greenFilter, w, h, filter, FILTER_WIDTH) //
                    .task("blurBlue", MultiImageProcessor::compute, blueChannel, blueFilter, w, h, filter, FILTER_WIDTH) //
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, redFilter, greenFilter, blueFilter, imageRGB);

            ImmutableTaskGraph immutableTaskGraph = parallelFilter.snapshot();
            executionPlan = new TornadoExecutionPlan(immutableTaskGraph);
            if (TornadoRuntime.getTornadoRuntime().getNumDrivers() == 1) {
                TornadoDevice device = TornadoExecutionPlan.getDevice(backendIndex, deviceIndex);
                executionPlan.withDevice(device);
            } else {
                // Extended API - Work for TornadoVM v1.0 API
                // This assumes TornadoVM is installed for all SPIR-V, PTX and OpenCL backends.
                // This call will not work otherwise
                TornadoDevice device0 = TornadoExecutionPlan.getDevice(0, 0);  // spir-v
                TornadoDevice device1 = TornadoExecutionPlan.getDevice(1, 0);  // opencl 0
                TornadoDevice device2 = TornadoExecutionPlan.getDevice(1, 1);  // opencl 1
                TornadoDevice device3 = TornadoExecutionPlan.getDevice(1, 2);  // opencl 2
                TornadoDevice device4 = TornadoExecutionPlan.getDevice(2, 0);  // ptx
                executionPlan.withConcurrentDevices() //
                        .withDevice("imageProcessor.blackAndWhite", device1) //
                        .withDevice("imageProcessor.blurRed", device1) //
                        .withDevice("imageProcessor.blurGreen", device1) //
                        .withDevice("imageProcessor.blurBlue", device1);
            }
        }
    }

    public void loadImage() {
        try {
            image = ImageIO.read(new File(IMAGE_FILE));
            imageRGB = new IntArray(image.getWidth() * image.getHeight());
        } catch (IOException e) {
            throw new RuntimeException(STR."Input file not found: \{IMAGE_FILE}");
        }
    }

    private void initData() {
        w = image.getWidth();
        h = image.getHeight();

        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int rgb = image.getRGB(i, j);
                imageRGB.set(i * h + j, rgb);
            }
        }

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

    private static void blackAndWhiteTransform(IntArray image, final int w, final int s) {
        for (@Parallel int i = 0; i < w; i++) {
            for (@Parallel int j = 0; j < s; j++) {
                int rgb = image.get(i * s + j);
                int alpha = (rgb >> 24) & 0xff;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = (rgb & 0xFF);

                int grayLevel = (red + green + blue) / 3;
                int gray = (alpha << 24) | (grayLevel << 16) | (grayLevel << 8) | grayLevel;
                image.set(i * s + j, gray);
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

    private static void blackAndWhiteTransformStreams(IntArray image, final int w, final int s) {
        IntStream.range(0, w).parallel().forEach(i -> {
            IntStream.range(0, s).parallel().forEach(j -> {
                int rgb = image.get(i * s + j);
                int alpha = (rgb >> 24) & 0xff;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = (rgb & 0xFF);

                int grayLevel = (red + green + blue) / 3;
                int gray = (alpha << 24) | (grayLevel << 16) | (grayLevel << 8) | grayLevel;
                image.set(i * s + j, gray);
            });
        });
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

    private void setImageFromBuffers() {
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                Color c = new Color(redFilter.get(i * h + j), greenFilter.get(i * h + j), blueFilter.get(i * h + j), alphaChannel.get(i * h + j));
                image.setRGB(i, j, c.getRGB());
            }
        }
    }

    private BufferedImage writeBlurImage() {
        setImageFromBuffers();
        try {
            File outputFile = new File( "./blur.jpeg");
            ImageIO.write(image, "JPEG", outputFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return image;
    }

    private BufferedImage writeBlackAndWhiteImage() {
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                image.setRGB(i, j, imageRGB.get(i * h + j));
            }
        }
        try {
            File outputFile = new File( "./bnw.jpeg");
            ImageIO.write(image, "JPEG", outputFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return image;
    }


    private void sequentialComputation() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            blackAndWhiteTransform(imageRGB, w, h);
            channelConvolutionSequential(redChannel, redFilter, w, h, filter, FILTER_WIDTH);
            channelConvolutionSequential(greenChannel, greenFilter, w, h, filter, FILTER_WIDTH);
            channelConvolutionSequential(blueChannel, blueFilter, w, h, filter, FILTER_WIDTH);
            long end = System.nanoTime();
            System.out.println(STR."Sequential Total time (ns) = \{end - start} -- seconds = \{(end - start) * 1e-9}");
        }
    }

    private void parallelStreams() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            blackAndWhiteTransformStreams(imageRGB, w, h);
            computeWithParallelStreams(redChannel, redFilter, w, h, filter, FILTER_WIDTH);
            computeWithParallelStreams(greenChannel, greenFilter, w, h, filter, FILTER_WIDTH);
            computeWithParallelStreams(blueChannel, blueFilter, w, h, filter, FILTER_WIDTH);
            long end = System.nanoTime();
            System.out.println(STR."Streams Total time (ns) = \{end - start} -- seconds = \{(end - start) * 1e-9}");
        }
    }

    private void runTornadoVM() {
        for (int i = 0; i< MAX_ITERATIONS; i++) {
            long start = System.nanoTime();
            executionPlan.execute();
            long end = System.nanoTime();
            System.out.println(STR."Total Time (ns) = \{end - start} -- seconds = \{(end - start) * 1e-9}");
        }
    }

    public void run() {
        switch (implementation) {
            case SEQUENTIAL -> sequentialComputation();
            case MT -> parallelStreams();
            case TORNADO_LOOP -> runTornadoVM();
        }
        writeBlurImage();
        writeBlackAndWhiteImage();
    }

    public static void main(String[] args) {

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

        MultiImageProcessor multiImageProcessor = new MultiImageProcessor(Options.getImplementation(version), backendIndex, deviceIndex);
        multiImageProcessor.run();
    }
}