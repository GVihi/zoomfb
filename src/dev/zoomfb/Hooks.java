package dev.zoomfb;

/**
 * The methods that the patched Minecraft code calls. Kept tiny on purpose: all logic lives
 * in Input and Config. These must stay public and static (Minecraft's classes call them).
 */
public final class Hooks {
    private Hooks() {}

    static void log(String s) {
        System.err.println("[zoomfb] " + s);
    }

    /** Called with the FOV Minecraft computed; returns the (possibly zoomed) FOV. */
    public static float fov(float fov) {
        try {
            Input.poll();
        } catch (Throwable ignored) {
            // never break rendering because of input handling
        }
        return fov / Input.zoom();
    }

    /** Wraps every read of LightmapRenderState.brightness. */
    public static float brightness(float v) {
        return Input.fullbright ? Math.max(v, Config.fbBrightness) : v;
    }

    /** Wraps every read of LightmapRenderState.nightVisionEffectIntensity. */
    public static float nightVision(float v) {
        return Input.fullbright ? Math.max(v, Config.fbNightVision) : v;
    }

    /** Called at the end of the in-game GUI render method with Minecraft's graphics object. */
    public static void hud(Object graphics) {
        try {
            Hud.render(graphics);
        } catch (Throwable ignored) {
            // never break rendering because of the overlay
        }
    }
}
