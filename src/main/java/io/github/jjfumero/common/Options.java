package io.github.jjfumero.common;

import java.util.HashMap;

public class Options {

    public enum Implementation {
        SEQUENTIAL,
        MT,
        TORNADO_LOOP,
        TORNADO_KERNEL
    }

    public static final HashMap<String, Implementation> VALID_OPTIONS = new HashMap<>();

    static {
        VALID_OPTIONS.put("sequential", Implementation.SEQUENTIAL);
        VALID_OPTIONS.put("seq", Implementation.SEQUENTIAL);
        VALID_OPTIONS.put("mt", Implementation.MT);
        VALID_OPTIONS.put("tornado", Implementation.TORNADO_LOOP);
        VALID_OPTIONS.put("tornadoContext", Implementation.TORNADO_KERNEL);
        VALID_OPTIONS.put("tornadocontext", Implementation.TORNADO_KERNEL);
    }

}
