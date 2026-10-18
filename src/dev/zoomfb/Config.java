package dev.zoomfb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Properties;

/**
 * Settings, stored in zoomfb.properties (in the game folder). The file is created with
 * defaults on first run, rewritten when you rebind in-game, and re-read automatically
 * when you edit it while the game runs (unless config.liveReload=false).
 */
final class Config {
    private Config() {}

    // zoom
    static volatile int zoomKey = 'Z';
    static volatile boolean zoomToggle = false;
    static volatile float zoomFactor = 4.0f;
    static volatile boolean zoomSmooth = true;
    static volatile int zoomIncreaseKey = 61;   // EQUAL
    static volatile int zoomDecreaseKey = 45;   // MINUS

    // fullbright
    static volatile int fullbrightKey = 'G';
    static volatile boolean fullbrightStartOn = false;
    static volatile float fbBrightness = 1.0f;
    static volatile float fbNightVision = 1.0f;

    // HUD (FPS + coordinates)
    static volatile int hudKey = 297;               // F8
    static volatile boolean hudStartOn = true;
    static volatile boolean hudShowFps = true;
    static volatile boolean hudShowCoords = true;
    static volatile int hudDecimals = 1;
    static volatile int hudX = 4;
    static volatile int hudY = 4;

    // in-game rebinding hotkeys
    static volatile int rebindZoomKey = 295;        // F6
    static volatile int rebindFullbrightKey = 296;  // F7

    // misc
    static volatile boolean liveReload = true;

    private static boolean firstLoad = true;
    private static FileTime lastSeen;
    private static long nextCheck;

    static Path path() {
        return Path.of(System.getProperty("zoomfb.config", "zoomfb.properties")).toAbsolutePath();
    }

    static synchronized void load() {
        Path path = path();
        Properties p = new Properties();
        boolean exists = Files.isRegularFile(path);
        if (exists) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
                Hooks.log("loaded config " + path);
            } catch (IOException e) {
                Hooks.log("could not read " + path + ": " + e);
            }
        }

        zoomKey = Keys.parse(p.getProperty("zoom.key"), 'Z');
        zoomToggle = bool(p, "zoom.toggle", false);
        zoomFactor = clamp(num(p, "zoom.factor", 4.0f), 1.25f, 30f);
        zoomSmooth = bool(p, "zoom.smooth", true);
        zoomIncreaseKey = Keys.parse(p.getProperty("zoom.increaseKey"), 61);
        zoomDecreaseKey = Keys.parse(p.getProperty("zoom.decreaseKey"), 45);

        fullbrightKey = Keys.parse(p.getProperty("fullbright.key"), 'G');
        fullbrightStartOn = bool(p, "fullbright.startOn", false);
        fbBrightness = num(p, "fullbright.brightness", 1.0f);
        fbNightVision = num(p, "fullbright.nightVision", 1.0f);

        hudKey = Keys.parse(p.getProperty("hud.key"), 297);
        hudStartOn = bool(p, "hud.startOn", true);
        hudShowFps = bool(p, "hud.fps", true);
        hudShowCoords = bool(p, "hud.coords", true);
        hudDecimals = (int) clamp(num(p, "hud.decimals", 1), 0, 6);
        hudX = (int) clamp(num(p, "hud.x", 4), 0, 4000);
        hudY = (int) clamp(num(p, "hud.y", 4), 0, 4000);

        rebindZoomKey = Keys.parse(p.getProperty("rebind.zoomKey"), 295);
        rebindFullbrightKey = Keys.parse(p.getProperty("rebind.fullbrightKey"), 296);

        liveReload = bool(p, "config.liveReload", true);

        if (firstLoad) {
            firstLoad = false;
            Input.fullbright = fullbrightStartOn;
            Input.hud = hudStartOn;
        }

        if (exists) {
            remember(path);
        } else {
            Hooks.log("no config found, writing defaults to " + path);
            save();
        }
    }

    /** Re-reads the file if it changed on disk. Checked at most every 2 seconds. */
    static void maybeReload() {
        if (!liveReload) return;
        long now = System.nanoTime();
        if (now < nextCheck) return;
        nextCheck = now + 2_000_000_000L;
        try {
            FileTime t = Files.getLastModifiedTime(path());
            if (lastSeen != null && !t.equals(lastSeen)) {
                Hooks.log("config file changed, reloading");
                load();
            }
        } catch (IOException ignored) {
            // file missing or unreadable: keep current values
        }
    }

    static synchronized void save() {
        Path path = path();
        StringBuilder sb = new StringBuilder();
        sb.append("# zoomfb configuration\n");
        sb.append("# Edit while the game runs: changes are picked up within ~2 seconds.\n");
        sb.append("# Key names: A-Z, 0-9, F1-F25, SPACE, TAB, ENTER, UP, DOWN, LEFT, RIGHT,\n");
        sb.append("#   LEFT_SHIFT, LEFT_CONTROL, LEFT_ALT, KP_0..KP_9, MINUS, EQUAL, LEFT_BRACKET,\n");
        sb.append("#   RIGHT_BRACKET, SEMICOLON, COMMA, PERIOD, SLASH, GRAVE_ACCENT, INSERT, DELETE,\n");
        sb.append("#   HOME, END, PAGE_UP, PAGE_DOWN, MOUSE_1..MOUSE_8 (MOUSE_4/MOUSE_5 = side buttons),\n");
        sb.append("#   or NONE to disable a key. A raw GLFW key code number also works.\n\n");

        sb.append("# ---- Zoom ----\n");
        sb.append("# Key to hold (or press, if zoom.toggle=true) to zoom.\n");
        sb.append("zoom.key=").append(Keys.name(zoomKey)).append('\n');
        sb.append("# false = zoom while the key is held, true = press once to zoom, again to unzoom.\n");
        sb.append("zoom.toggle=").append(zoomToggle).append('\n');
        sb.append("# Zoom strength: the field of view is divided by this (1.25 to 30).\n");
        sb.append("zoom.factor=").append(zoomFactor).append('\n');
        sb.append("# Ease in and out instead of snapping.\n");
        sb.append("zoom.smooth=").append(zoomSmooth).append('\n');
        sb.append("# While zooming, press these to make the zoom stronger/weaker (saved when you stop zooming).\n");
        sb.append("zoom.increaseKey=").append(Keys.name(zoomIncreaseKey)).append('\n');
        sb.append("zoom.decreaseKey=").append(Keys.name(zoomDecreaseKey)).append("\n\n");

        sb.append("# ---- Fullbright ----\n");
        sb.append("fullbright.key=").append(Keys.name(fullbrightKey)).append('\n');
        sb.append("# Start every game with fullbright already on?\n");
        sb.append("fullbright.startOn=").append(fullbrightStartOn).append('\n');
        sb.append("# 1.0 is the \"Bright\" slider maximum. Raise it (2, 4, 8) if it is not bright enough.\n");
        sb.append("# Very large values can break rendering on some GPUs.\n");
        sb.append("fullbright.brightness=").append(fbBrightness).append('\n');
        sb.append("# How much night-vision ambient light is forced on (1.0 = full night vision).\n");
        sb.append("fullbright.nightVision=").append(fbNightVision).append("\n\n");

        sb.append("# ---- HUD (FPS and coordinates, top left) ----\n");
        sb.append("# Key that shows/hides the HUD.\n");
        sb.append("hud.key=").append(Keys.name(hudKey)).append('\n');
        sb.append("# Show the HUD when the game starts?\n");
        sb.append("hud.startOn=").append(hudStartOn).append('\n');
        sb.append("hud.fps=").append(hudShowFps).append('\n');
        sb.append("hud.coords=").append(hudShowCoords).append('\n');
        sb.append("# Decimal places for the coordinates (0 to 6).\n");
        sb.append("hud.decimals=").append(hudDecimals).append('\n');
        sb.append("# Offset from the top left corner, in GUI pixels. Raise hud.y to sit below the F3 screen.\n");
        sb.append("hud.x=").append(hudX).append('\n');
        sb.append("hud.y=").append(hudY).append("\n\n");

        sb.append("# ---- In-game rebinding ----\n");
        sb.append("# Press one of these in the world, then press the new key (Esc cancels).\n");
        sb.append("# The new key is saved to this file. Set to NONE to disable.\n");
        sb.append("rebind.zoomKey=").append(Keys.name(rebindZoomKey)).append('\n');
        sb.append("rebind.fullbrightKey=").append(Keys.name(rebindFullbrightKey)).append("\n\n");

        sb.append("# ---- Misc ----\n");
        sb.append("# Re-read this file automatically when it changes.\n");
        sb.append("config.liveReload=").append(liveReload).append('\n');

        try {
            Files.writeString(path, sb.toString());
            remember(path);
        } catch (IOException e) {
            Hooks.log("could not write " + path + ": " + e);
        }
    }

    private static void remember(Path path) {
        try {
            lastSeen = Files.getLastModifiedTime(path);
        } catch (IOException e) {
            lastSeen = null;
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String s = p.getProperty(key);
        return s == null || s.isBlank() ? def : Boolean.parseBoolean(s.trim());
    }

    private static float num(Properties p, String key, float def) {
        String s = p.getProperty(key);
        if (s == null || s.isBlank()) return def;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            Hooks.log("bad number for " + key + ": '" + s + "', using " + def);
            return def;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
