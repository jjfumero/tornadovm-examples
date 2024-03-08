/*
 * Copyright 2022, 2024 Juan Fumero
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
package io.github.jjfumero.taskmigration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import java.util.Random;
import java.util.stream.IntStream;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

public class Server extends Thread {

    public static final int PORT_NUMBER = 8081;

    public static final String SERVER_IP = "127.0.0.1";


    protected Socket socket;

    private final TornadoExecutionPlan executionPlan;

    private FloatArray a;
    private FloatArray b;

    private Server(Socket socket) {
        this.socket = socket;
        System.out.println(STR."New client connected from \{socket.getInetAddress().getHostAddress()}");

        a = new FloatArray(256);
        b = new FloatArray(256);

        Random r = new Random();
        IntStream.range(0, a.getSize()).parallel().forEach(idx -> {
            a.set(idx, r.nextFloat());
        });

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, a) //
                .task("t0", Server::vectorAddition, a, b) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, b); //
        executionPlan = new TornadoExecutionPlan(taskGraph.snapshot());
        start();
    }

    private static void vectorAddition(final FloatArray a, final FloatArray b) {
        for (@Parallel int i = 0; i < a.getSize(); i++) {
            float value = a.get(i);
            b.set(i, value + value);
        }
    }

    @Override
    public void run() {
        InputStream in = null;
        OutputStream out = null;

        int maxDrivers = TornadoRuntime.getTornadoRuntime().getNumDrivers();

        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String request;

            int backendIndex;
            int deviceIndex;
            while ((request = br.readLine()) != null) {
                System.out.println(STR."REQUEST: \{request}");
                try {
                    String[] clientDeviceSelection = request.split(":");
                    backendIndex = Integer.parseInt(clientDeviceSelection[0]);
                    deviceIndex = Integer.parseInt(clientDeviceSelection[1]);
                } catch (NumberFormatException e) {
                    backendIndex = 0;
                    deviceIndex = 0;
                }

                // Control for max devices limit
                if (backendIndex >= maxDrivers) {
                    backendIndex = 0;
                    System.out.println(STR."[Warning] max \{maxDrivers} drivers");
                    int maxDevices = TornadoRuntime.getTornadoRuntime().getDriver(backendIndex).getDeviceCount();
                    if (maxDevices >= deviceIndex) {
                        System.out.println(STR."[Warning] max \{maxDevices} devices");
                        deviceIndex = 0;
                    }
                }

                TornadoDevice device = TornadoExecutionPlan.getDevice(backendIndex, deviceIndex);
                executionPlan.withDevice(device);

                System.out.println(STR."Selecting the device: \{device.getPhysicalDevice().getDeviceName()}");
                request += '\n';
                out.write(request.getBytes());

                executionPlan.execute();
            }

        } catch (IOException ex) {
            System.out.println("Unable to get streams from client");
        } finally {
            try {
                in.close();
                out.close();
                socket.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("Server Example");
        ServerSocket server = null;
        try {
            server = new ServerSocket(PORT_NUMBER);
            while (true) {
                new Server(server.accept());
            }
        } catch (IOException ex) {
            System.out.println("Unable to start server.");
        } finally {
            try {
                if (server != null) {
                    server.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
}