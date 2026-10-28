package dev.zoomfb;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Reads keyboard/mouse state from GLFW (26.2) or SDL3 (26.3+) through method handles,
 * so the project still builds without bundling LWJGL. Called from Hooks.fov() each frame.
 * Input only counts while the game has captured the mouse (no chat/menu/inventory).
 */
final class Input {
    private Input() {}
    private static final int GLFW_PRESS = 1;
    private static final int GLFW_CURSOR = 0x33001;
    private static final int GLFW_CURSOR_DISABLED = 0x34003;
    private static final int KEY_ESCAPE = 256;

    private enum Backend { NONE, GLFW, SDL }
    private static Backend backend = Backend.NONE;
    private static boolean initDone;
    private static long retryInitAt;

    // GLFW handles
    private static MethodHandle hContext, hKey, hMouse, hMode, hSetTitle, hGetTitle;
    // SDL3 handles
    private static MethodHandle hSdlWindow, hSdlKeys, hSdlMouse, hSdlMode, hSdlSetTitle, hSdlGetTitle;
    private static ByteBuffer sdlKeyboardState;
    private static int sdlMouseMask;

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

    static float zoom() { return zoomCurrent; }

    /** Initialize once the game's input library is available. Retry failures instead of caching them forever. */
    private static boolean ensureInit() {
        if (initDone) return true;
        long now = System.nanoTime();
        if (now < retryInitAt) return false;
        retryInitAt = now + 2_000_000_000L;
        MethodHandles.Lookup l = MethodHandles.publicLookup();

        // Minecraft 26.2 and older: GLFW is still the input backend.
        try {
            ClassLoader cl = Input.class.getClassLoader();
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", true, cl);
            hContext = l.findStatic(glfw, "glfwGetCurrentContext", MethodType.methodType(long.class));
            hKey = l.findStatic(glfw, "glfwGetKey", MethodType.methodType(int.class, long.class, int.class));
            hMouse = l.findStatic(glfw, "glfwGetMouseButton", MethodType.methodType(int.class, long.class, int.class));
            hMode = l.findStatic(glfw, "glfwGetInputMode", MethodType.methodType(int.class, long.class, int.class));
            try { hSetTitle = l.findStatic(glfw, "glfwSetWindowTitle", MethodType.methodType(void.class, long.class, CharSequence.class)); }
            catch (Throwable ignored) { hSetTitle = null; }
            try { hGetTitle = l.findStatic(glfw, "glfwGetWindowTitle", MethodType.methodType(String.class, long.class)); }
            catch (Throwable ignored) { hGetTitle = null; }
            backend = Backend.GLFW;
            initDone = true;
            Hooks.log("input backend: GLFW");
            return true;
        } catch (Throwable glfwFailure) {
            // Try SDL3, used by Minecraft 26.3+. Do not require SDL at compile time.
        }

        try {
            ClassLoader cl = Input.class.getClassLoader();
            Class<?> keyboard = Class.forName("org.lwjgl.sdl.SDLKeyboard", true, cl);
            Class<?> mouse = Class.forName("org.lwjgl.sdl.SDLMouse", true, cl);
            Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo", true, cl);
            hSdlWindow = l.findStatic(keyboard, "SDL_GetKeyboardFocus", MethodType.methodType(long.class));
            hSdlKeys = l.findStatic(keyboard, "SDL_GetKeyboardState", MethodType.methodType(ByteBuffer.class));
            hSdlMouse = l.findStatic(mouse, "SDL_GetMouseState", MethodType.methodType(int.class, FloatBuffer.class, FloatBuffer.class));
            hSdlMode = l.findStatic(mouse, "SDL_GetWindowRelativeMouseMode", MethodType.methodType(boolean.class, long.class));
            try { hSdlSetTitle = l.findStatic(video, "SDL_SetWindowTitle", MethodType.methodType(boolean.class, long.class, CharSequence.class)); }
            catch (Throwable ignored) { hSdlSetTitle = null; }
            try { hSdlGetTitle = l.findStatic(video, "SDL_GetWindowTitle", MethodType.methodType(String.class, long.class)); }
            catch (Throwable ignored) { hSdlGetTitle = null; }
            backend = Backend.SDL;
            initDone = true;
            Hooks.log("input backend: SDL3");
            return true;
        } catch (Throwable sdlFailure) {
            backend = Backend.NONE;
            Hooks.log("GLFW and SDL3 input APIs unavailable; will retry: " + sdlFailure);
            return false;
        }
    }

    static void poll() throws Throwable {
        if (!ensureInit()) {
            updateZoom(false, System.nanoTime());
            return;
        }
        long window;
        boolean grabbed;
        if (backend == Backend.GLFW) {
            window = (long) hContext.invokeExact();
            if (window == 0L) return;
            grabbed = ((int) hMode.invokeExact(window, GLFW_CURSOR)) == GLFW_CURSOR_DISABLED;
        } else {
            window = (long) hSdlWindow.invokeExact();
            if (window == 0L) {
                updateZoom(false, System.nanoTime());
                return;
            }
            sdlKeyboardState = (ByteBuffer) hSdlKeys.invokeExact();
            sdlMouseMask = (int) hSdlMouse.invokeExact((FloatBuffer) null, (FloatBuffer) null);
            grabbed = (boolean) hSdlMode.invokeExact(window);
        }

        long now = System.nanoTime();
        Config.maybeReload();
        if (titleRestoreAt != 0 && now >= titleRestoreAt) restoreTitle(window);

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
        if (rz && !rebindZoomWas) startCapture(Target.ZOOM, window, now);
        else if (rf && !rebindFbWas) startCapture(Target.FULLBRIGHT, window, now);
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
        } else zoomCurrent = target;
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
            if (pressed == Keys.NONE) captureArmed = true;
            return;
        }
        if (pressed == Keys.NONE) return;
        if (pressed == KEY_ESCAPE) {
            endCapture(window, now, "zoomfb: rebind cancelled");
            return;
        }
        Target t = capture;
        if (t == Target.ZOOM) Config.zoomKey = pressed;
        else Config.fullbrightKey = pressed;
        Config.save();
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
            int glfwButton = code - Keys.MOUSE_BASE; // 0=left, 1=right, 2=middle
            if (backend == Backend.GLFW) return ((int) hMouse.invokeExact(window, glfwButton)) == GLFW_PRESS;
            int sdlButton = glfwButton == 0 ? 1 : glfwButton == 1 ? 3 : glfwButton == 2 ? 2 : glfwButton + 1;
            return sdlButton >= 1 && sdlButton <= 8 && (sdlMouseMask & (1 << (sdlButton - 1))) != 0;
        }
        if (backend == Backend.GLFW) return ((int) hKey.invokeExact(window, code)) == GLFW_PRESS;
        int scan = sdlScancodeFromGlfw(code);
        return scan > 0 && sdlKeyboardState != null && scan < sdlKeyboardState.limit() && sdlKeyboardState.get(scan) != 0;
    }

    /** First pressed mouse button or supported keyboard key, or Keys.NONE. */
    private static int firstDown(long window) throws Throwable {
        for (int b = 0; b < 8; b++) if (isDown(window, Keys.MOUSE_BASE + b)) return Keys.MOUSE_BASE + b;
        // Keep returning the project's existing GLFW-style codes so zoomfb.properties stays compatible.
        for (int k = 32; k <= 348; k++) if (isDown(window, k)) return k;
        return Keys.NONE;
    }

    /** Convert the GLFW-style codes stored in zoomfb.properties to SDL physical scancodes. */
    private static int sdlScancodeFromGlfw(int k) {
        if (k >= 'A' && k <= 'Z') {
            // SDL scancodes follow physical QWERTY positions, not alphabetical order.
            final int[] letters = {4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29};
            return letters[k - 'A'];
        }
        if (k >= '0' && k <= '9') return k == '0' ? 39 : 29 + (k - '0');
        if (k >= 290 && k <= 301) return 58 + (k - 290); // F1-F12
        if (k >= 302 && k <= 313) return 104 + (k - 302); // F13-F24
        if (k >= 320 && k <= 329) return k == 320 ? 98 : 88 + (k - 320); // keypad 0-9
        return switch (k) {
            case 32 -> 44;  // space
            case 39 -> 52;  // apostrophe
            case 44 -> 54;  // comma
            case 45 -> 45;  // minus
            case 46 -> 55;  // period
            case 47 -> 56;  // slash
            case 59 -> 51;  // semicolon
            case 61 -> 46;  // equal
            case 91 -> 47;  // left bracket
            case 92 -> 49;  // backslash
            case 93 -> 48;  // right bracket
            case 96 -> 53;  // grave accent
            case 256 -> 41; // escape
            case 257 -> 40; // enter
            case 258 -> 43; // tab
            case 259 -> 42; // backspace
            case 260 -> 73; // insert
            case 261 -> 76; // delete
            case 262 -> 79; // right
            case 263 -> 80; // left
            case 264 -> 81; // down
            case 265 -> 82; // up
            case 266 -> 75; // page up
            case 267 -> 78; // page down
            case 268 -> 74; // home
            case 269 -> 77; // end
            case 280 -> 57; // caps lock
            case 281 -> 71; // scroll lock
            case 282 -> 83; // num lock
            case 283 -> 70; // print screen
            case 284 -> 72; // pause
            case 330 -> 99; // keypad decimal
            case 331 -> 84; // keypad divide
            case 332 -> 85; // keypad multiply
            case 333 -> 86; // keypad subtract
            case 334 -> 87; // keypad add
            case 335 -> 88; // keypad enter
            case 340 -> 225; // left shift
            case 341 -> 224; // left control
            case 342 -> 226; // left alt
            case 343 -> 227; // left super
            case 344 -> 229; // right shift
            case 345 -> 228; // right control
            case 346 -> 230; // right alt
            case 347 -> 231; // right super
            case 348 -> 118; // menu
            default -> 0; // unsupported/unmapped GLFW-style key
        };
    }

    private static String readTitle(long window) {
        MethodHandle get = backend == Backend.SDL ? hSdlGetTitle : hGetTitle;
        if (get == null) return "Minecraft";
        try { String s = (String) get.invoke(window); return s != null ? s : "Minecraft"; }
        catch (Throwable t) { return "Minecraft"; }
    }

    private static void setTitle(long window, String text) {
        MethodHandle set = backend == Backend.SDL ? hSdlSetTitle : hSetTitle;
        if (set == null) return;
        try { set.invoke(window, (CharSequence) text); }
        catch (Throwable ignored) { /* cosmetic only */ }
    }

    private static void restoreTitle(long window) {
        setTitle(window, originalTitle != null ? originalTitle : "Minecraft");
        titleRestoreAt = 0;
    }
}
