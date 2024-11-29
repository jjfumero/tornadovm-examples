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
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.matrix.Matrix2DFloat;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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

        private static final boolean DEBUG = false;

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

            if (DEBUG) {
                Arrays.stream(ranges).forEach(r -> {
                    System.out.println(r + " -- " + (r.max - r.min));
                });
            }

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
            executionPlan.withWarmUp().withDevice(TornadoExecutionPlan.getDevice(0, 0));
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

    @State(Scope.Thread)
    public static class Benchmarking {

        MatrixMultiplication matrixMultiplication;

        FloatMatrix matrixA;
        FloatMatrix matrixB;

        // Matrix for results
        FloatMatrix matrixC;
        FloatMatrix matrixD;
        FloatMatrix matrixE;
        FloatMatrix matrixF;
        FloatMatrix matrixG;

        Matrix2DFloat tma;
        Matrix2DFloat tmb;
        Matrix2DFloat resultTornadoVM;
        TornadoExecutionPlan executionPlan;

        @Setup(Level.Trial)
        public void doSetup() {
            // Using Panama Segments
            final int size = 1024;
            matrixA = new FloatMatrix(size, size);
            matrixB = new FloatMatrix(size, size);

            // Matrix for results
            matrixC = new FloatMatrix(size, size);
            matrixD = new FloatMatrix(size, size);
            matrixE = new FloatMatrix(size, size);
            matrixF = new FloatMatrix(size, size);
            matrixG = new FloatMatrix(size, size);

            matrixA.initRamdom();
            matrixB.initRamdom();

            // TornadoVM
            tma = Multiplication.transformMatrixForTornadoVM(matrixA);
            tmb = Multiplication.transformMatrixForTornadoVM(matrixB);
            resultTornadoVM = new Matrix2DFloat(size, size);
            executionPlan = Multiplication.createTornadoVMPlan(tma, tmb, resultTornadoVM);
        }

        @Benchmark
        @BenchmarkMode(Mode.AverageTime)
        @Warmup(iterations = 2, time = 60)
        @Measurement(iterations = 5, time = 30)
        @OutputTimeUnit(TimeUnit.NANOSECONDS)
        @Fork(1)
        public void mxmSequential(Benchmarking state) {
            MatrixMultiplication.Multiplication.mxmSequential(state.matrixA, state.matrixB, state.matrixC);
        }

        @Benchmark
        @BenchmarkMode(Mode.AverageTime)
        @Warmup(iterations = 2, time = 60)
        @Measurement(iterations = 5, time = 30)
        @OutputTimeUnit(TimeUnit.NANOSECONDS)
        @Fork(1)
        public void mxmParallelStreams(Benchmarking state) {
            MatrixMultiplication.Multiplication.mxmParallelStreams(state.matrixA, state.matrixB, state.matrixD);
        }

        @Benchmark
        @BenchmarkMode(Mode.AverageTime)
        @Warmup(iterations = 2, time = 60)
        @Measurement(iterations = 5, time = 30)
        @OutputTimeUnit(TimeUnit.NANOSECONDS)
        @Fork(1)
        public void mxmParallelThreads(Benchmarking state) throws InterruptedException {
            MatrixMultiplication.Multiplication.mxmParallelThreads(state.matrixA, state.matrixB, state.matrixE);
        }

        @Benchmark
        @BenchmarkMode(Mode.AverageTime)
        @Warmup(iterations = 2, time = 60)
        @Measurement(iterations = 5, time = 30)
        @OutputTimeUnit(TimeUnit.NANOSECONDS)
        @Fork(1)
        public void mxmSequentialVectorized(Benchmarking state) {
            MatrixMultiplication.Multiplication.mxmSequentialVectorized(state.matrixA, state.matrixB, state.matrixF);
        }

        @Benchmark
        @BenchmarkMode(Mode.AverageTime)
        @Warmup(iterations = 2, time = 60)
        @Measurement(iterations = 5, time = 30)
        @OutputTimeUnit(TimeUnit.NANOSECONDS)
        @Fork(1)
        public void mxmParallelVectorized(Benchmarking state) {
            MatrixMultiplication.Multiplication.mxmParallelVectorized(state.matrixA, state.matrixB, state.matrixG);
        }

        @Benchmark
        @BenchmarkMode(Mode.AverageTime)
        @Warmup(iterations = 2, time = 60)
        @Measurement(iterations = 5, time = 30)
        @OutputTimeUnit(TimeUnit.NANOSECONDS)
        @Fork(1)
        public void mxmTornadoVM(Benchmarking state) {
            state.executionPlan.execute();
        }
    }

    private static void runWithJMH() throws RunnerException {
        org.openjdk.jmh.runner.options.Options opt = new OptionsBuilder() //
                .include(MatrixMultiplication.class.getName() + ".*") //
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

    private static void runTestAll(final int size) throws InterruptedException {

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

        final int RUNS = 100;

        // 6 implementations to compare
        ArrayList<ArrayList<Long>> timers = IntStream.range(0, 6) //
                .<ArrayList<Long>>mapToObj(i -> new ArrayList<>()) //
                .collect(Collectors.toCollection(ArrayList::new));

        // 1. Sequential
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmSequential(matrixA, matrixB, matrixC);
            long end = System.nanoTime();
            long elapsedTime = (end - start);
            timers.get(0).add(elapsedTime);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.println("Elapsed time: " + (elapsedTime) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
        }

        // 2. Parallel Streams
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmParallelStreams(matrixA, matrixB, matrixD);
            long end = System.nanoTime();
            long elapsedTime = (end - start);
            timers.get(1).add(elapsedTime);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Stream Elapsed time: " + (elapsedTime) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(matrixD, matrixC));
        }

        // 3. Parallel with Java Threads
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmParallelThreads(matrixA, matrixB, matrixE);
            long end = System.nanoTime();
            long elapsedTime = (end - start);
            timers.get(2).add(elapsedTime);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Elapsed time Threads: " + (elapsedTime) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(matrixE, matrixC));
        }

        // 4. Sequential Using the Vector API
        FloatMatrix bTranspose = Multiplication.transposeMatrix(matrixB);
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmSequentialVectorized(matrixA, bTranspose, matrixF);
            long end = System.nanoTime();
            long elapsedTime = (end - start);
            timers.get(3).add(elapsedTime);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Elapsed time Vectorized: " + (elapsedTime) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(matrixF, matrixC));
        }

        // 5. Parallel Streams using the Vector API
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            Multiplication.mxmParallelVectorized(matrixA, bTranspose, matrixG);
            long end = System.nanoTime();
            long elapsedTime = (end - start);
            timers.get(4).add(elapsedTime);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Elapsed time Parallel Vectorized: " + (elapsedTime) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(matrixG, matrixC));
        }

        // TornadoVM
        Matrix2DFloat tma = Multiplication.transformMatrixForTornadoVM(matrixA);
        Matrix2DFloat tmb = Multiplication.transformMatrixForTornadoVM(matrixB);
        Matrix2DFloat resultTornadoVM = new Matrix2DFloat(size, size);
        TornadoExecutionPlan executionPlan = Multiplication.createTornadoVMPlan(tma, tmb, resultTornadoVM);

        // 6. On the GPU using TornadoVM
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            executionPlan.execute();
            long end = System.nanoTime();
            long elapsedTime = (end - start);
            timers.get(5).add(elapsedTime);
            double elapsedTimeMilliseconds = elapsedTime * 1E-6;
            System.out.print("Elapsed time TornadoVM-GPU: " + (elapsedTime) + " (ns)  -- " + elapsedTimeMilliseconds + " (ms)");
            System.out.println(" -- Result Correct? " + Multiplication.verify(resultTornadoVM, matrixC));
        }

        // Print CSV table with RAW elapsed timers
        try (FileWriter fileWriter = new FileWriter("performanceTable.csv")) {
            // Write header
            fileWriter.write("sequential,streams,threads,vectorSingle,vectorParallel,TornadoVM\n");
            // Write data
            for (int i = 0; i < RUNS; i++) {
                StringBuilder builder = new StringBuilder();
                for (int j = 0; j < 6; j++) {
                    builder.append(timers.get(j).get(i) + ",");
                }
                fileWriter.write(builder.substring(0, builder.length() -1));
                fileWriter.write("\n");
            }
        } catch (IOException e) {
            System.err.println("An error occurred: " + e.getMessage());
        }
    }


    public static void main(String[] args) throws InterruptedException, RunnerException {

        System.out.println("Matrix Multiplication");
        final int size = 1024;

        if (args.length > 0) {
            if (args[0].equals("--jmh")) {
                runWithJMH();
                return;
            }
        }
        runTestAll(size);
    }
}
