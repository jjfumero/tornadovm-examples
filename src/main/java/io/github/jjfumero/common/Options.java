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
package io.github.jjfumero.common;

import java.util.HashMap;
import java.util.Map;

public final class Options {

    public enum Implementation {
        SEQUENTIAL,
        MT,
        TORNADO_LOOP,
        TORNADO_KERNEL
    }

    private static final Map<String, Implementation> VALID_OPTIONS = new HashMap<>();

    static {
        VALID_OPTIONS.put("sequential", Implementation.SEQUENTIAL);
        VALID_OPTIONS.put("seq", Implementation.SEQUENTIAL);
        VALID_OPTIONS.put("mt", Implementation.MT);
        VALID_OPTIONS.put("tornado", Implementation.TORNADO_LOOP);
        VALID_OPTIONS.put("tornadoContext", Implementation.TORNADO_KERNEL);
        VALID_OPTIONS.put("tornadocontext", Implementation.TORNADO_KERNEL);
    }

    public static Implementation getImplementation(String str) {
        return VALID_OPTIONS.get(str);
    }

    public static boolean isValid(String key) {
        return VALID_OPTIONS.containsKey(key);
    }

    public static void printHelp() {
        System.out.println("Option not valid. Use:");
        System.out.println("\ttornado: for accelerated version with TornadoVM");
        System.out.println("\ttornadoContext: for accelerated version with TornadoVM");
        System.out.println("\tseq: for running the sequential version with Java Streams");
        System.out.println("\tmt: for running the CPU multi-thread version with Java Parallel Streams");
    }

}
