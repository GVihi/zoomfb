package dev.zoomfb;

import java.lang.instrument.Instrumentation;

/**
 * Entry point. Add  -javaagent:/path/to/zoomfb.jar  to the JVM arguments.
 * No mod loader involved: the JVM calls premain before Minecraft starts,
 * and we register a transformer that patches two spots in the client.
 */
public final class Agent {
    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        Hooks.log("starting (zoom + fullbright agent)");
        Config.load();
        inst.addTransformer(new Patcher(), false);
    }
}
