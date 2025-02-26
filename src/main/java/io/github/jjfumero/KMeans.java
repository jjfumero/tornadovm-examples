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
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoRuntimeException;
import uk.ac.manchester.tornado.api.math.TornadoMath;
import uk.ac.manchester.tornado.api.types.collections.VectorFloat2;
import uk.ac.manchester.tornado.api.types.collections.VectorInt;
import uk.ac.manchester.tornado.api.types.matrix.Matrix2DInt;
import uk.ac.manchester.tornado.api.types.vectors.Float2;

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
 * Example: <code>
 * tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Kmeans tornado 1048576 3
 * </code>
 * </p>
 */
public class KMeans {

    public static int INIT_VALUE = -1;

    // Matrix used for input data initialization only
    public static int[][] initMatrix;

    private static boolean PRINT_RESULT = false;

    /**
     * This method recalculates the centroids. Centroids are calculated using the
     * average distance of all points that are currently classified into the same
     * cluster
     *
     * @param cluster
     *            Input set of clusters.
     * @param dataPoints
     *            Data Set
     * @param centroid
     *            Current set of centroids
     * @return a new Centroid
     */
    public static Float2 calculateCentroid(VectorInt cluster, VectorFloat2 dataPoints, Float2 centroid) {
        float sumX = 0;
        float sumY = 0;

        int numElements = 0;
        for (int i = 0; i < cluster.getLength(); i++) {
            // Obtain the point that belongs to a cluster
            int pointBelongsToCluster = cluster.get(i);
            // If the point is not the Init value, means that the point was assigned to a
            // cluster
            if (pointBelongsToCluster != INIT_VALUE) {
                // In this case, we obtain the point coordinates (x,y) and accumulate their
                // values to compute the average in the next step.
                Float2 point = dataPoints.get(pointBelongsToCluster);
                sumX += point.getX();
                sumY += point.getY();
                numElements++;
            }
        }

        if (numElements != 0) {
            float centerX = sumX / numElements;
            float centerY = sumY / numElements;
            return new Float2(centerX, centerY);
        } else {
            // If there are no elements then we return the original centroid
            return centroid;
        }
    }

    /**
     * It computes the distance between two points. Points are represented using the
     * {@link Float2} object type from TornadoVM.
     *
     * @param pointA
     * @param pointB
     * @return a float number that represents the distance between the two input
     *         points.
     */
    public static float distance(Float2 pointA, Float2 pointB) {
        float dx = pointA.getX() - pointB.getX();
        float dy = pointA.getY() - pointB.getY();
        return TornadoMath.sqrt((dx * dx) + (dy * dy));
    }

    /**
     * Method that compares when two points are equal.
     *
     * @param pointA
     * @param pointB
     * @return returns true if the two input points are equal.
     */
    public static boolean isEqual(Float2 pointA, Float2 pointB) {
        return ((pointA.getX() - pointB.getX()) == 0) && ((pointA.getY() - pointB.getY()) == 0);
    }

    /**
     * Main method in the Kmeans clustering. It assigns a cluster number for each
     * data point.
     *
     * <p>
     * Clusters are represented as a 2D Matrix. The 2D matrix is of size K-Clusters
     * x Size. Each row from the matrix stores the point index (point ID) that
     * belongs to each cluster. Row 0 will control cluster 0, row 1 will control
     * cluster 1, etc.
     * </p>
     *
     * <p>
     * Each point from the input data set can be assigned to a cluster in parallel.
     * Thus, if the TornadoVM runtime is presented, then the code will be
     * automatically parallelized to run with OpenCL, PTX and SPIR-V.
     * </p>
     *
     * @param dataPoints
     * @param clusters
     * @param centroid
     */
    private static void assignClusters(VectorFloat2 dataPoints, Matrix2DInt clusters, VectorFloat2 centroid) {
        // Assign data points to clusters
        for (@Parallel int pointIndex = 0; pointIndex < dataPoints.getLength(); pointIndex++) {
            Float2 point = dataPoints.get(pointIndex);
            int closerCluster = INIT_VALUE;
            float minDistance = Float.MAX_VALUE;
            for (int clusterIndex = 0; clusterIndex < clusters.getNumRows(); clusterIndex++) {
                // Compute the distance between the current point and the centroid that was
                // assigned to the input data point.
                float distance = distance(point, centroid.get(clusterIndex));
                if (distance < minDistance) {
                    minDistance = distance;
                    closerCluster = clusterIndex;
                }
            }
            // If it founds a closer cluster, then we assign that cluster to the data point
            // being observed.
            clusters.set(closerCluster, pointIndex, pointIndex);
        }
    }

    /**
     * Second function for the KMeans algorithm. It updates the centroids after
     * updating each point to a new cluster.
     *
     * @param dataPoints
     * @param clusters
     * @param centroid
     * @return
     */
    private static boolean updateCentroids(VectorFloat2 dataPoints, Matrix2DInt clusters, VectorFloat2 centroid) {
        boolean centroidsChanged = false;
        for (int clusterIndex = 0; clusterIndex < clusters.getNumRows(); clusterIndex++) {
            VectorInt cluster = clusters.row(clusterIndex);
            Float2 oldCentroid = centroid.get(clusterIndex);
            Float2 newCentroid = calculateCentroid(cluster, dataPoints, oldCentroid);
            if (!isEqual(oldCentroid, newCentroid)) {
                centroid.set(clusterIndex, newCentroid);
                centroidsChanged = true;
            }
        }
        return centroidsChanged;
    }

    public static Matrix2DInt kMeansClusteringWithTornadoVM(VectorFloat2 dataPoints, final int K) {

        // 1. Data initialization

        // 1.1 Assign an INIT_VALUE for all columns per cluster
        // [ cluster0 ] [ InitVal, InitVal, ... InitVal ]
        // [ cluster1 ] [ InitVal, InitVal, ... InitVal ]
        initMatrix = new int[K][dataPoints.getLength()];
        for (int clusterIndex = 0; clusterIndex < K; clusterIndex++) {
            Arrays.fill(initMatrix[clusterIndex], INIT_VALUE);
        }

        // 1.2 Create a Matrix2D int type using the initMatrix
        Matrix2DInt clusters = new Matrix2DInt(initMatrix);

        // 1.3 Initialize clusters with random centroids from the input data set.
        VectorFloat2 centroid = new VectorFloat2(K);
        // Create an array with random points from the input data set
        int[] rnd = getRandomIndex(dataPoints, K);
        // Assign different the random point as a centroid for the initialization
        for (int clusterIndex = 0; clusterIndex < K; clusterIndex++) {
            Float2 randomCentroid = dataPoints.get(rnd[clusterIndex]);
            centroid.set(clusterIndex, randomCentroid);
        }

        // 2. Create a Task-Graph for the assign cluster method
        TaskGraph taskGraph = new TaskGraph("clustering") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, clusters, dataPoints) //
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, centroid) //
                .task("kmeans", KMeans::assignClusters, dataPoints, clusters, centroid) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, clusters);

        // 3. Create an execution plan
        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph);

        long start = System.nanoTime();
        executionPlan.execute();

        // 4. KMeans Clustering Algorithm
        // 4.1 Recalculate centroids of clusters while the centroids list change between
        // iterations.
        boolean centroidsChanged = true;
        while (centroidsChanged) {
            // Recalculate centroids. The following method is executed on a CPU (without
            // TornadoVM). s
            centroidsChanged = updateCentroids(dataPoints, clusters, centroid);
            if (centroidsChanged) {
                // If there are new changes, then we reassign clusters
                executionPlan.execute();
            }
        }
        long end = System.nanoTime();

        System.out.println("Total time: " + (end - start) + " (nanoseconds)");
        return clusters;
    }

    public static Matrix2DInt kMeansClusteringSequential(VectorFloat2 dataPoints, final int K) {

        initMatrix = new int[K][dataPoints.getLength()];
        for (int clusterIndex = 0; clusterIndex < K; clusterIndex++) {
            Arrays.fill(initMatrix[clusterIndex], INIT_VALUE);
        }

        Matrix2DInt clusters = new Matrix2DInt(initMatrix);

        // Initialize clusters with random centroids
        VectorFloat2 centroid = new VectorFloat2(K);
        int[] rnd = getRandomIndex(dataPoints, K);
        for (int clusterIndex = 0; clusterIndex < K; clusterIndex++) {
            Float2 randomCentroid = dataPoints.get(rnd[clusterIndex]);
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
                //clusters = new Matrix2DInt(initMatrix);
                assignClusters(dataPoints, clusters, centroid);
            }
        }
        long end = System.nanoTime();

        System.out.println("Total time: " + (end - start) + " (nanoseconds)");
        return clusters;
    }

    public static int[] getRandomIndex(VectorFloat2 points, int K) {
        Random r = new Random(71);
        HashSet<Integer> randomValues = new HashSet<>();
        for (int i = 0; i < K; i++) {
            int valX = r.nextInt(points.getLength());
            while (randomValues.contains(valX)) {
                valX = r.nextInt(points.getLength());
            }
            randomValues.add(valX);
        }

        // Flatten the random values in an array
        int[] rnd = new int[K];
        int i = 0;
        for (Integer val : randomValues) {
            rnd[i++] = val;
        }
        return rnd;
    }

    private static void printClusters(VectorFloat2 dataPoints, Matrix2DInt clusters) {
        // Print the clusters
        for (int i = 0; i < clusters.getNumRows(); i++) {
            System.out.println("Cluster " + i + ": ");
            VectorInt row = clusters.row(i);
            for (int j = 0; j < row.getLength(); j++) {
                if (row.get(j) != INIT_VALUE) {
                    int index = row.get(j);
                    Float2 point = dataPoints.get(index);
                    System.out.println("    <" + point.getX() + ", " + point.getY() + "> ");
                }
            }
        }
    }

    private static VectorFloat2 createDataPoints(int numDataPoints) {
        VectorFloat2 dataPoints = new VectorFloat2(numDataPoints);
        // Use the same seed for all implementation, to facilitate comparisons
        Random r = new Random(7);
        for (int i = 0; i < numDataPoints; i++) {
            int pointX = r.nextInt(numDataPoints);
            int pointy = r.nextInt(numDataPoints);
            dataPoints.set(i, new Float2(pointX, pointy));
        }
        return dataPoints;
    }

    private final Options.Implementation implementation;

    // Cluster the data points
    private final int k;
    private Matrix2DInt clusters;
    private final VectorFloat2 dataPoints;

    public KMeans(Options.Implementation implementation, int numDataPoints, int k) {
        this.implementation = implementation;
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
                throw new TornadoRuntimeException("Not implemented yet");
            case TORNADO_KERNEL:
                throw new TornadoRuntimeException("Not implemented yet");
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
