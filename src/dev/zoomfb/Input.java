package dev.zoomfb;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reads the keyboard/mouse straight from GLFW (through method handles, so building needs no
 * LWJGL jar) and runs the zoom, fullbright and rebinding logic. Everything here runs on the
 * render thread, called from Hooks.fov() once per frame.
 *
 * Input only counts while the mouse is grabbed (no chat, menu or inventory open).
 */
final class Input {
    private Input() {}

    private static final int GLFW_PRESS = 1;
    private static final int GLFW_CURSOR = 0x33001;
    private static final int GLFW_CURSOR_DISABLED = 0x34003;
    private static final int KEY_ESCAPE = 256;

    private static boolean initDone;
    private static boolean glfwOk;
    private static MethodHandle hContext, hKey, hMouse, hMode, hSetTitle, hGetTitle;

    /** Current fullbright state (read by the lightmap hooks). */
    static volatile boolean fullbright;

    /** Whether the FPS/coordinates overlay is shown (read by Hud). */
    static volatile boolean hud;

    // zoom state
    private static boolean zoomActive, zoomWasDown, incWas, decWas, zoomDirty;
    private static float zoomCurrent = 1.0f;
    private static long lastNanos;

    // fullbright / rebind edge detection
    private static boolean fbWas, hudWas, rebindZoomWas, rebindFbWas;

    // rebinding
    private enum Target { ZOOM, FULLBRIGHT }
    private static Target capture;
    private static boolean captureArmed;
    private static long captureStart;
    private static String originalTitle;
    private static long titleRestoreAt;

    static float zoom() {
        return zoomCurrent;
    }

    // ------------------------------------------------------------ setup

    /** Looked up lazily on the first frame, after Minecraft has initialised LWJGL. */
    private static void ensureInit() {
        if (initDone) return;
        initDone = true;
        Class<?> glfw;
        MethodHandles.Lookup l = MethodHandles.publicLookup();
        try {
            glfw = Class.forName("org.lwjgl.glfw.GLFW", true, ClassLoader.getSystemClassLoader());
            hContext = l.findStatic(glfw, "glfwGetCurrentContext", MethodType.methodType(long.class));
            hKey = l.findStatic(glfw, "glfwGetKey", MethodType.methodType(int.class, long.class, int.class));
            hMouse = l.findStatic(glfw, "glfwGetMouseButton", MethodType.methodType(int.class, long.class, int.class));
            hMode = l.findStatic(glfw, "glfwGetInputMode", MethodType.methodType(int.class, long.class, int.class));
            glfwOk = true;
        } catch (Throwable t) {
            Hooks.log("GLFW not available, keys disabled: " + t);
            return;
        }
        // Optional: used only for on-screen feedback in the window title while rebinding.
        try {
            hSetTitle = l.findStatic(glfw, "glfwSetWindowTitle",
                    MethodType.methodType(void.class, long.class, CharSequence.class));
        } catch (Throwable ignored) {
            hSetTitle = null;
        }
        try {
            hGetTitle = l.findStatic(glfw, "glfwGetWindowTitle",
                    MethodType.methodType(String.class, long.class));
        } catch (Throwable ignored) {
            hGetTitle = null;
        }
    }

    // ------------------------------------------------------------- poll

    static void poll() throws Throwable {
        ensureInit();
        if (!glfwOk) return;
        long window = (long) hContext.invokeExact();
        if (window == 0L) return;

        long now = System.nanoTime();
        Config.maybeReload();
        if (titleRestoreAt != 0 && now >= titleRestoreAt) {
            restoreTitle(window);
        }

        boolean grabbed = ((int) hMode.invokeExact(window, GLFW_CURSOR)) == GLFW_CURSOR_DISABLED;

        if (capture != null) {
            handleCapture(window, grabbed, now);
            updateZoom(false, now);
            return;
        }

        // ---- zoom
        boolean zoomDown = grabbed && isDown(window, Config.zoomKey);
        if (Config.zoomToggle) {
            if (zoomDown && !zoomWasDown) zoomActive = !zoomActive;
            if (!grabbed) zoomActive = false;
        } else {
            zoomActive = zoomDown;
        }
        zoomWasDown = zoomDown;

        boolean inc = grabbed && zoomActive && isDown(window, Config.zoomIncreaseKey);
        boolean dec = grabbed && zoomActive && isDown(window, Config.zoomDecreaseKey);
        if (inc && !incWas) {
            Config.zoomFactor = Math.min(30f, Config.zoomFactor * 1.25f);
            zoomDirty = true;
        }
        if (dec && !decWas) {
            Config.zoomFactor = Math.max(1.25f, Config.zoomFactor / 1.25f);
            zoomDirty = true;
        }
        incWas = inc;
        decWas = dec;
        if (!zoomActive && zoomDirty) {
            zoomDirty = false;
            Config.save();
        }

        // ---- fullbright toggle
        boolean fbDown = grabbed && isDown(window, Config.fullbrightKey);
        if (fbDown && !fbWas) {
            fullbright = !fullbright;
            Hooks.log("fullbright " + (fullbright ? "ON" : "OFF"));
        }
        fbWas = fbDown;

        // ---- HUD toggle
        boolean hudDown = grabbed && isDown(window, Config.hudKey);
        if (hudDown && !hudWas) {
            hud = !hud;
            Hooks.log("hud " + (hud ? "ON" : "OFF"));
        }
        hudWas = hudDown;

        // ---- rebind hotkeys
        boolean rz = grabbed && isDown(window, Config.rebindZoomKey);
        boolean rf = grabbed && isDown(window, Config.rebindFullbrightKey);
        if (rz && !rebindZoomWas) {
            startCapture(Target.ZOOM, window, now);
        } else if (rf && !rebindFbWas) {
            startCapture(Target.FULLBRIGHT, window, now);
        }
        rebindZoomWas = rz;
        rebindFbWas = rf;

        updateZoom(zoomActive, now);
    }

    private static void updateZoom(boolean active, long now) {
        float target = active ? Config.zoomFactor : 1.0f;
        if (Config.zoomSmooth && lastNanos != 0) {
            double dt = (now - lastNanos) / 1.0e9;
            double alpha = 1.0 - Math.exp(-dt / 0.06);
            zoomCurrent += (float) ((target - zoomCurrent) * alpha);
            if (Math.abs(target - zoomCurrent) < 0.001f) zoomCurrent = target;
        } else {
            zoomCurrent = target;
        }
        lastNanos = now;
    }

    // ----------------------------------------------------------- rebind

    private static void startCapture(Target t, long window, long now) {
        capture = t;
        captureArmed = false;
        captureStart = now;
        titleRestoreAt = 0;
        if (originalTitle == null) originalTitle = readTitle(window);
        String what = t == Target.ZOOM ? "ZOOM" : "FULLBRIGHT";
        setTitle(window, "zoomfb: press the new " + what + " key or mouse button (Esc = cancel)");
        Hooks.log("rebinding " + what + ": press the new key (Esc cancels)");
    }

    private static void handleCapture(long window, boolean grabbed, long now) throws Throwable {
        if (!grabbed) {
            endCapture(window, now, "zoomfb: rebind cancelled");
            return;
        }
        if (now - captureStart > 15_000_000_000L) {
            endCapture(window, now, "zoomfb: rebind timed out");
            return;
        }

        int pressed = firstDown(window);
        if (!captureArmed) {
            // Wait until everything (including the rebind hotkey itself) has been released.
            if (pressed == Keys.NONE) captureArmed = true;
            return;
        }
        if (pressed == Keys.NONE) return;
        if (pressed == KEY_ESCAPE) {
            endCapture(window, now, "zoomfb: rebind cancelled");
            return;
        }

        Target t = capture;
        if (t == Target.ZOOM) {
            Config.zoomKey = pressed;
        } else {
            Config.fullbrightKey = pressed;
        }
        Config.save();
        // Don't let the key we just chose trigger its action until it is released.
        zoomWasDown = true;
        fbWas = true;
        hudWas = true;
        endCapture(window, now, "zoomfb: " + t + " key set to " + Keys.name(pressed));
    }

    private static void endCapture(long window, long now, String message) {
        capture = null;
        Hooks.log(message);
        setTitle(window, message);
        titleRestoreAt = now + 3_000_000_000L;
    }

    // ---------------------------------------------------------- helpers

    private static boolean isDown(long window, int code) throws Throwable {
        if (code < 0) return false;
        if (code >= Keys.MOUSE_BASE) {
            int button = code - Keys.MOUSE_BASE;
            return ((int) hMouse.invokeExact(window, button)) == GLFW_PRESS;
        }
        return ((int) hKey.invokeExact(window, code)) == GLFW_PRESS;
    }

    /** First pressed mouse button or key, or Keys.NONE. */
    private static int firstDown(long window) throws Throwable {
        for (int b = 0; b < 8; b++) {
            if (isDown(window, Keys.MOUSE_BASE + b)) return Keys.MOUSE_BASE + b;
        }
        for (int k = 32; k <= 348; k++) {
            if (isDown(window, k)) return k;
        }
        return Keys.NONE;
    }

    private static String readTitle(long window) {
        if (hGetTitle == null) return "Minecraft";
        try {
            String s = (String) hGetTitle.invoke(window);
            return s != null ? s : "Minecraft";
        } catch (Throwable t) {
            return "Minecraft";
        }
    }

    private static void setTitle(long window, String text) {
        if (hSetTitle == null) return;
        try {
            hSetTitle.invoke(window, (CharSequence) text);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    private static void restoreTitle(long window) {
        setTitle(window, originalTitle != null ? originalTitle : "Minecraft");
        titleRestoreAt = 0;
    }
}
