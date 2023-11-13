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

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.collections.VectorFloat4;
import uk.ac.manchester.tornado.api.types.vectors.Float4;

/**
 * tornado --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.HelloVectors
 */
public class HelloVectors {

    public static void parallelInitialization(VectorFloat4 data) {
        for (@Parallel int i = 0; i < data.size(); i++) {
            data.set(i, new Float4(i, i, i, i));
        }
    }

    public static void computeSquare(VectorFloat4 data) {
        for (@Parallel int i = 0; i < data.size(); i++) {
            Float4 item = data.get(i);
            Float4 result = Float4.mult(item, item);
            data.set(i, result);
        }
    }

    public static void main( String[] args ) {
        VectorFloat4 array = new VectorFloat4(128);
        TaskGraph taskGraph = new TaskGraph("s0")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, array)
                .task("t0", HelloVectors::parallelInitialization, array)
                .task("t1", HelloVectors::computeSquare, array)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot());

        // Obtain a device from the list
        TornadoDevice device = TornadoExecutionPlan.getDevice(0, 2);
        executionPlan.withDevice(device);

        // Put in a loop to analyze hotspots with Intel VTune (as a demo)
        for (int i = 0; i < 2000; i++ ) {
            // Execute the application
            executionPlan.execute();
        }

    }
}
