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

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.matrix.Matrix2DFloat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.stream.IntStream;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

public class MatrixMultiplication {

    /**
     * Float MxN Matrix
     */
    private static class FloatMatrix {

        private static final int FLOAT_SIZE = 4;

        private final int m;
        private final int n;
        private final MemorySegment segment;

        public FloatMatrix(int m, int n) {
            this.m = m;
            this.n = n;
            final long segmentByteSize = n * m * FLOAT_SIZE;
            segment = Arena.ofAuto().allocate(segmentByteSize, 64);
        }

        public void set(int i, int j, float value) {
            final int index = i * m + j;
            segment.set(JAVA_FLOAT, index * FLOAT_SIZE, value);
        }

        public float get(int i, int j) {
            final int index = i * m + j;
            return segment.get(JAVA_FLOAT, index * FLOAT_SIZE);
        }

        public void initRamdom() {
            Random r = new Random(71);
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < n; j++) {
                    set(i, j, r.nextFloat());
                }
            }
        }

        public int M() {
            return m;
        }

        public int N() {
            return n;
        }
    }

    private static class Multiplication {

        /**
         * Matrix Multiplication using Panama Segments Sequentially
         *
         * @param a
         * @param b
         * @param c
         */
        public static void mxmSequential(FloatMatrix a, FloatMatrix b, FloatMatrix c) {
            for (int i = 0; i < a.M(); i++) {
                for (int j = 0; j < b.N(); j++) {
                    float acc = 0;
                    for (int k = 0; k < c.M(); k++) {
                        acc += a.get(i, k) * b.get(k, j);
                    }
                    c.set(i, j, acc);
                }
            }
        }

        public static void mxmParallelStreams(FloatMatrix a, FloatMatrix b, FloatMatrix c) {
            IntStream.range(0, a.M()).parallel().forEach(i -> IntStream.range(0, b.N()).parallel().forEach(j -> {
                float acc = 0;
                for (int k = 0; k < c.M(); k++) {
                    acc += a.get(i, k) * b.get(k, j);
                }
                c.set(i, j, acc);
            }));
        }

        private record Range(int min, int max) {
        }

        public static void mxmParallelThreads(FloatMatrix a, FloatMatrix b, FloatMatrix c) throws InterruptedException {

            int maxProcessors = Runtime.getRuntime().availableProcessors();
            // Assuming square-matrices
            int size = a.M();
            int chunk = size / maxProcessors;
            int rest = size % maxProcessors;

            Range[] ranges = new Range[maxProcessors];
            for (int i = 0; i < ranges.length; i++) {
                int min = i * chunk;
                int max = i * chunk + chunk;

                // Adjust load
                if (rest > i) {
                    max += i + 1;
                    min += i;
                } else if (rest != 0) {
                    min += rest;
                    max += rest;
                }

                ranges[i] = new Range(min, max);
            }

//            Arrays.stream(ranges).forEach(r -> {
//                System.out.println(r + " -- " + (r.max - r.min));
//            });

            Thread[] threads = new Thread[maxProcessors];
            IntStream.range(0, threads.length).forEach(t -> {
                threads[t] = new Thread(() -> {
                    for (int i = ranges[t].min; i < ranges[t].max; i++) {
                        for (int j = 0; j < b.N(); j++) {
                            float acc = 0;
                            for (int k = 0; k < c.M(); k++) {
                                acc += a.get(i, k) * b.get(k, j);
                            }
                            c.set(i, j, acc);
                        }
                    }
                });
            });

            for (Thread t : threads) {
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }
        }

        public static FloatMatrix transposeMatrix(FloatMatrix matrix) {
            FloatMatrix matrixTranspose = new FloatMatrix(matrix.M(), matrix.N());
            for (int i = 0; i < matrix.M(); i++) {
                for (int j = 0; j < matrix.N(); j++) {
                    matrixTranspose.set(i, j, matrix.get(j, i));
                }
            }
            return matrixTranspose;
        }

        static final int FLOAT_BYTES = 4;
        public static void mxmSequentialVectorized(FloatMatrix a, FloatMatrix b, FloatMatrix c) {
            VectorSpecies<Float> species = FloatVector.SPECIES_PREFERRED;
            for (int i = 0; i < a.M(); i++) {
                for (int j = 0; j < a.N(); j++) {
                    float acc = 0;
                    for (int k = 0; k < c.M(); k += species.length()) {
                        FloatVector vector1 = FloatVector.fromMemorySegment(species, a.segment, (i * a.M() + k) * FLOAT_BYTES, ByteOrder.nativeOrder());
                        FloatVector vector2 = FloatVector.fromMemorySegment(species, b.segment, (j * b.N() + k) * FLOAT_BYTES, ByteOrder.nativeOrder());
                        acc += vector1.mul(vector2).reduceLanes(VectorOperators.ADD);
                    }
                    c.set(i, j, acc);
                }
            }
        }

        public static void mxmParallelVectorized(FloatMatrix a, FloatMatrix b, FloatMatrix c) {
            VectorSpecies<Float> species = FloatVector.SPECIES_PREFERRED;
            IntStream.range(0, a.M()).parallel().forEach(i -> IntStream.range(0, b.N()).parallel().forEach(j -> {
                    float acc = 0;
                    for (int k = 0; k < c.M(); k += species.length()) {
                        FloatVector vector1 = FloatVector.fromMemorySegment(species, a.segment, (i * a.M() + k) * FLOAT_BYTES, ByteOrder.nativeOrder());
                        FloatVector vector2 = FloatVector.fromMemorySegment(species, b.segment, (j * b.N() + k) * FLOAT_BYTES, ByteOrder.nativeOrder());
                        acc += vector1.mul(vector2).reduceLanes(VectorOperators.ADD);
                    }
                    c.set(i, j, acc);
                }));
        }

        private static void mxmTornadoVM(Matrix2DFloat a, Matrix2DFloat b, Matrix2DFloat c, final int size) {
            for (@Parallel int i = 0; i < size; i++) {
                for (@Parallel int j = 0; j < size; j++) {
                    float sum = 0.0f;
                    for (int k = 0; k < size; k++) {
                        sum += a.get(i, k) * b.get(k, j);
                    }
                    c.set(i, j, sum);
                }
            }
        }

        public static Matrix2DFloat transformMatrixForTornadoVM(FloatMatrix a) {
            int m = a.M();
            int n = a.N();
            Matrix2DFloat matrix2DFloat = new Matrix2DFloat(m, n);
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < n; j++) {
                    matrix2DFloat.set(i, j, a.get(i, j));
                }
            }
            return matrix2DFloat;
        }

        private static TornadoExecutionPlan createTornadoVMPlan(Matrix2DFloat a, Matrix2DFloat b, Matrix2DFloat c) {
            TaskGraph taskGraph = new TaskGraph("mxm");
            taskGraph.transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b) //
                    .task("mxm", Multiplication::mxmTornadoVM, a, b, c, a.getNumRows()) //
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, c);
            TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot());
            executionPlan.withWarmUp();
            return executionPlan;
        }

        private static boolean verify(FloatMatrix matrix, FloatMatrix referenceMatrix) {
            boolean check = true;
            for (int i = 0; i < matrix.M(); i++) {
                for (int j = 0; j < matrix.N(); j++) {
                    if (Math.abs(matrix.get(i, j) - referenceMatrix.get(i, j)) > 0.1f) {
                        System.out.println(matrix.get(i, j) + " vs " + referenceMatrix.get(i, j));
                        check = false;
                        break;
                    }
                }
                if (!check) {
                    return false;
                }
            }
            return check;
        }

        private static boolean verify(Matrix2DFloat matrix, FloatMatrix referenceMatrix) {
            boolean check = true;
            for (int i = 0; i < matrix.getNumRows(); i++) {
                for (int j = 0; j < matrix.getNumColumns(); j++) {
                    if (Math.abs(matrix.get(i, j) - referenceMatrix.get(i, j)) > 0.1f) {
                        System.out.println(matrix.get(i, j) + " vs " + referenceMatrix.get(i, j));
                        check = false;
                        break;
                    }
                }
                if (!check) {
                    return false;
                }
            }
            return check;
        }
    }

    public static void main(String[] args) throws InterruptedException {

        System.out.println("Matrix Multiplication");

        final int size = 1024;

        // Using Panama Segments
        FloatMatrix matrixA = new FloatMatrix(size, size);
        FloatMatrix matrixB = new FloatMatrix(size, size);

        // Matrix for results
        FloatMatrix matrixC = new FloatMatrix(size, size);
        FloatMatrix matrixD = new FloatMatrix(size, size);
        FloatMatrix matrixE = new FloatMatrix(size, size);
        FloatMatrix matrixF = new FloatMatrix(size, size);
        FloatMatrix matrixG = new FloatMatrix(size, size);

        matrixA.initRamdom();
        matrixB.initRamdom();

        final int RUNS = 10;

        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmSequential(matrixA, matrixB, matrixC);
            long end = System.nanoTime();
            double elapsedTime = (end - start);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.println("Elapsed time: " + (end - start) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
        }

        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmParallelStreams(matrixA, matrixB, matrixD);
            long end = System.nanoTime();
            double elapsedTime = (end - start);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Stream Elapsed time: " + (end - start) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(matrixD, matrixC));
        }

        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmParallelThreads(matrixA, matrixB, matrixE);
            long end = System.nanoTime();
            double elapsedTime = (end - start);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Elapsed time Threads: " + (end - start) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(matrixE, matrixC));
        }

        FloatMatrix bTranspose = Multiplication.transposeMatrix(matrixB);
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmSequentialVectorized(matrixA, bTranspose, matrixF);
            long end = System.nanoTime();
            double elapsedTime = (end - start);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Elapsed time Vectorized: " + (end - start) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(matrixF, matrixC));
        }

        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmParallelVectorized(matrixA, bTranspose, matrixG);
            long end = System.nanoTime();
            double elapsedTime = (end - start);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Elapsed time Parallel Vectorized: " + (end - start) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(matrixG, matrixC));
        }

        // TornadoVM
        Matrix2DFloat tma = Multiplication.transformMatrixForTornadoVM(matrixA);
        Matrix2DFloat tmb = Multiplication.transformMatrixForTornadoVM(matrixB);
        Matrix2DFloat resultTornadoVM = new Matrix2DFloat(size, size);
        TornadoExecutionPlan executionPlan = Multiplication.createTornadoVMPlan(tma, tmb, resultTornadoVM);

        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            executionPlan.execute();
            long end = System.nanoTime();
            double elapsedTime = (end - start);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Elapsed time TornadoVM-GPU: " + (end - start) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(resultTornadoVM, matrixC));
        }

    }
}
