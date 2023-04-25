/*
 * Copyright 2023 Juan Fumero
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
import uk.ac.manchester.tornado.api.collections.math.TornadoMath;
import uk.ac.manchester.tornado.api.collections.types.Double2;
import uk.ac.manchester.tornado.api.collections.types.Matrix2DInt;
import uk.ac.manchester.tornado.api.collections.types.VectorDouble2;
import uk.ac.manchester.tornado.api.collections.types.VectorInt;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoRuntimeException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

/**
 * How to run?
 *
 * <p>
 * <code>
 * tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Kmeans <implementation> <numPoints> <numClusters>
 * </code>
 * </p>
 *
 * <p>
 *     Example:
 * <code>
 * tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Kmeans tornado 1048576 3
 * </code>
 * </p>
 */
public class KMeans {

    public static int INIT_VALUE = -1;
    public static int[][] initMatrix;

    private static boolean PRINT_RESULT = false;

    public static Double2 calculateCentroid(VectorInt cluster, VectorDouble2 dataPoints, Double2 centroid) {
        double sumX = 0;
        double sumY = 0;

        int numElements = 0;
        for (int i = 0; i < cluster.getLength(); i++) {
            int pointBelongsToCluster = cluster.get(i);
            if (pointBelongsToCluster != INIT_VALUE) {
                Double2 point = dataPoints.get(pointBelongsToCluster);
                sumX += point.getX();
                sumY += point.getY();
                numElements++;
            }
        }

        if (numElements != 0) {
            double centerX = sumX / numElements;
            double centerY = sumY / numElements;
            return new Double2(centerX, centerY);
        } else {
            // If there are no elements then we return the original centroid
            return centroid;
        }
    }

    public static double distance(Double2 pointA, Double2 pointB) {
        double dx = pointA.getX() - pointB.getX();
        double dy = pointA.getY() - pointB.getY();
        return TornadoMath.sqrt((dx * dx) + (dy * dy));
    }

    public static boolean isEqual(Double2 pointA, Double2 pointB) {
        return ((pointA.getX() - pointB.getX()) == 0) && ((pointA.getY() - pointB.getY()) == 0);
    }

    private static void assignClusters(VectorDouble2 dataPoints, Matrix2DInt clusters, VectorDouble2 centroid) {
        // Assign data points to clusters
        for (@Parallel int pointIndex = 0; pointIndex < dataPoints.getLength(); pointIndex++) {
            Double2 point = dataPoints.get(pointIndex);
            int closetCluster = INIT_VALUE;
            double minDistance = Double.MAX_VALUE;
            for (int clusterIndex = 0; clusterIndex < clusters.getNumRows(); clusterIndex++) {
                double distance = distance(point, centroid.get(clusterIndex));
                if (distance < minDistance) {
                    minDistance = distance;
                    closetCluster = clusterIndex;
                }
            }
            clusters.set(closetCluster, pointIndex, pointIndex);
        }
    }

    private static boolean updateCentroids(VectorDouble2 dataPoints, Matrix2DInt clusters, VectorDouble2 centroid) {
        boolean centroidsChanged = false;
        for (int clusterIndex = 0; clusterIndex < clusters.getNumRows(); clusterIndex++) {
            VectorInt cluster = clusters.row(clusterIndex);
            Double2 oldCentroid =  centroid.get(clusterIndex);
            Double2 newCentroid = calculateCentroid(cluster, dataPoints, oldCentroid);
            if (!isEqual(oldCentroid, newCentroid)) {
                centroid.set(clusterIndex, newCentroid);
                centroidsChanged = true;
            }
        }
        return centroidsChanged;
    }

    public static Matrix2DInt kMeansClusteringWithTornadoVM(VectorDouble2 dataPoints, final int K) {

        initMatrix = new int[K][dataPoints.getLength()];
        for (int clusterIndex = 0; clusterIndex < K; clusterIndex++) {
            Arrays.fill(initMatrix[clusterIndex], INIT_VALUE);
        }

        Matrix2DInt clusters = new Matrix2DInt(initMatrix);

        // Initialize clusters with random centroids
        VectorDouble2 centroid = new VectorDouble2(K);
        int[] rnd = getRandomIndex(dataPoints, K);
        for (int clusterIndex = 0; clusterIndex < K; clusterIndex++) {
            Double2 randomCentroid = dataPoints.get(rnd[clusterIndex]);
            centroid.set(clusterIndex, randomCentroid);
        }

        TaskGraph taskGraph = new TaskGraph("clustering") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, clusters, dataPoints) //
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, centroid) //
                .task("kmeans", KMeans::assignClusters, dataPoints, clusters, centroid) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, centroid, clusters);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph);

        long start = System.nanoTime();
        executionPlan.execute();

        // Recalculate centroids of clusters
        boolean centroidsChanged = true;
        while (centroidsChanged) {
            centroidsChanged = updateCentroids(dataPoints, clusters, centroid);
            if (centroidsChanged) {
                // Reassign data points to clusters
                executionPlan.execute();
            }
        }
        long end = System.nanoTime();

        System.out.println("Total time: " + (end - start) + " (ms)");
        return clusters;
    }

    public static Matrix2DInt kMeansClusteringSequential(VectorDouble2 dataPoints, final int K) {

        initMatrix = new int[K][dataPoints.getLength()];
        for (int clusterIndex = 0; clusterIndex < K; clusterIndex++) {
            Arrays.fill(initMatrix[clusterIndex], INIT_VALUE);
        }

        Matrix2DInt clusters = new Matrix2DInt(initMatrix);

        // Initialize clusters with random centroids
        VectorDouble2 centroid = new VectorDouble2(K);
        int[] rnd = getRandomIndex(dataPoints, K);
        for (int clusterIndex = 0; clusterIndex < K; clusterIndex++) {
            Double2 randomCentroid = dataPoints.get(rnd[clusterIndex]);
            centroid.set(clusterIndex, randomCentroid);
        }

        long start = System.nanoTime();
        assignClusters(dataPoints, clusters, centroid);

        // Recalculate centroids of clusters
        boolean centroidsChanged = true;
        while (centroidsChanged) {
            centroidsChanged = updateCentroids(dataPoints, clusters, centroid);
            if (centroidsChanged) {
                // Reassign data points to clusters
                clusters = new Matrix2DInt(initMatrix);
                assignClusters(dataPoints, clusters, centroid);
            }
        }
        long end = System.nanoTime();

        System.out.println("Total time: " + (end - start) + " (ms)");
        return clusters;
    }

    public static int[] getRandomIndex(VectorDouble2 points, int K) {
        Random r = new Random();
        HashSet<Integer> randomValues = new HashSet<>();
        for (int i = 0; i < K; i++) {
            int valX = r.nextInt(points.getLength());
            while (randomValues.contains(valX)) {
                valX = r.nextInt(points.getLength());
            }
            randomValues.add(valX);
        }

        int[] rnd = new int[K];
        int i = 0;
        for (Integer val : randomValues) {
            rnd[i++] = val;
        }
        return rnd;
    }

    private static void printClusters(VectorDouble2 dataPoints, Matrix2DInt clusters) {
        // Print the clusters
        for (int i = 0; i < clusters.getNumRows(); i++) {
            System.out.println("Cluster " + i + ": ");
            VectorInt row = clusters.row(i);
            for (int j = 0; j < row.getLength(); j++) {
                if (row.get(j) != INIT_VALUE) {
                    int index = row.get(j);
                    Double2 point = dataPoints.get(index);
                    System.out.println("    <" + point.getX() + ", " + point.getY() + "> ");
                }
            }
        }
    }

    private static VectorDouble2 createDataPoints(int numDataPoints) {
        VectorDouble2 dataPoints = new VectorDouble2(numDataPoints);
        Random r = new Random(System.nanoTime());
        for (int i = 0; i < numDataPoints; i++) {
            int pointX = r.nextInt(numDataPoints);
            int pointy = r.nextInt(numDataPoints);
            dataPoints.set(i, new Double2(pointX, pointy));
        }
        return dataPoints;
    }

    private final Options.Implementation implementation;

    // Cluster the data points
    private final int k;
    private int numDataPoints;
    private Matrix2DInt clusters;
    private final VectorDouble2 dataPoints ;

    public KMeans(Options.Implementation implementation, int numDataPoints, int k) {
        this.implementation = implementation;
        this.numDataPoints = numDataPoints;
        this.k = k;
        // Create Data Set: data points
        dataPoints = createDataPoints(numDataPoints);
    }

    public void run() {
        switch (implementation) {
            case SEQUENTIAL:
                clusters = kMeansClusteringSequential(dataPoints, k);
                break;
            case TORNADO_LOOP:
                clusters = kMeansClusteringWithTornadoVM(dataPoints, k);
                break;
            case MT:
                throw  new TornadoRuntimeException("Not implemented yet");
            case TORNADO_KERNEL:
                throw  new TornadoRuntimeException("Not implemented yet");
        }

        if (PRINT_RESULT) {
            printClusters(dataPoints, clusters);
        }
    }

    public static void main(String[] args) {

        String version = "tornado";
        int numDataPoints = 10;
        int k = 2;
        if (args.length == 3) {
            version = args[0];
            if (!Options.isValid(version)) {
                Options.printHelp();
                System.exit(-1);
            }
            try {
                numDataPoints = Integer.parseInt(args[1]);
                k = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                // Using the default ones
            }
        }

        KMeans kmeans = new KMeans(Options.getImplementation(version), numDataPoints, k);
        kmeans.run();
    }
}
