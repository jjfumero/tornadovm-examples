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

import io.github.jjfumero.common.Options;
import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;

import static io.github.jjfumero.common.Options.VALID_OPTIONS;

/**
 * Mandelbrot version taken from the examples available in the TornadoVM Framework: tornadovm.org
 *
 * This sample generates an image representing the Mandelbrot fractal. 
 *
 */
public class Mandelbrot {

    private static final int MAX = 100;

    public static void mandelbrotFractal(int size, short[] output) {
        final int iterations = 10000;
        float space = 2.0f / size;

        for (@Parallel int i = 0; i < size; i++) {
            for (@Parallel int j = 0; j < size; j++) {
                float Zr = 0.0f;
                float Zi = 0.0f;
                float Cr = (1 * j * space - 1.5f);
                float Ci = (1 * i * space - 1.0f);
                float ZrN = 0;
                float ZiN = 0;
                int y = 0;
                for (int ii = 0; ii < iterations; ii++) {
                    if (ZiN + ZrN <= 4.0f) {
                        Zi = 2.0f * Zr * Zi + Ci;
                        Zr = 1 * ZrN - ZiN + Cr;
                        ZiN = Zi * Zi;
                        ZrN = Zr * Zr;
                        y++;
                    } else {
                        ii = iterations;
                    }
                }
                short r = (short) ((y * 255) / iterations);
                output[i * size + j] = r;
            }
        }
    }

    public static class Benchmark {
        int size = Integer.parseInt(System.getProperty("x", "512"));
        TaskSchedule ts;
        short[] mandelbrotImage;

        private Options.Implementation implementation;


        private  void doSetup() {
            mandelbrotImage = new short[size * size];
            if (implementation == Options.Implementation.TORNADO_LOOP) {
                ts = new TaskSchedule("s0") //
                        .task("t0", Mandelbrot::mandelbrotFractal, size, mandelbrotImage) //
                        .streamOut(mandelbrotImage);
                ts.warmup();
            }
            else if (implementation == Options.Implementation.TORNADO_KERNEL) {
                // Not implemented yet.
            }
        }

        public Benchmark(Options.Implementation implementation) {
            this.implementation = implementation;
            doSetup();
        }

        public void sequentialComputation() {
            for (int i = 0; i < MAX; i++) {
                long start = System.nanoTime();
                mandelbrotFractal(size, mandelbrotImage);
                long end = System.nanoTime();
                System.out.println("Sequential Total time (ns) = " + (end - start) + " -- seconds = " + ((end - start) * 1e-9));
            }
        }

        private void runTornadoVM() {
            for (int i = 0; i< MAX; i++) {
                long start = System.nanoTime();
                ts.execute();
                long end = System.nanoTime();
                System.out.println("Total Time (ns) = " + (end - start) + " -- seconds = " + ((end - start) * 1e-9));
            }
        }

        private void writeFile() {
            try {
                BufferedImage imageFractal = new BufferedImage(size, size, BufferedImage.TYPE_INT_BGR);
                WritableRaster write = imageFractal.getRaster();
                File outputFile = new File("/tmp/mandelbrot.png");
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < size; j++) {
                        int colour = mandelbrotImage[(i * size + j)];
                        write.setSample(i, j, 0, colour);
                    }
                }
                ImageIO.write(imageFractal, "PNG", outputFile);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void run() {
            switch (implementation) {
                case SEQUENTIAL:
                    sequentialComputation();
                    break;
                case TORNADO_LOOP:
                    runTornadoVM();
                    break;
            }
            writeFile();
        }

    }

    public static void main(String[] args) {
        String version = "tornado";
        if (args.length != 0) {
            version = args[0].substring(2);
            if (!VALID_OPTIONS.containsKey(version)) {
                System.out.println("Option not valid. Use:");
                System.out.println("\t--tornado: for accelerated version with TornadoVM");
                System.out.println("\t--tornadoContext: for accelerated version with TornadoVM");
                System.out.println("\t--seq: for running the sequential version with Java Streams");
                System.out.println("\t--mt: for running the CPU multi-thread version with Java Parallel Streams");
                System.exit(-1);
            }
        }
        Benchmark mandelbrot = new Benchmark(VALID_OPTIONS.get(version));
        mandelbrot.run();
    }
}
