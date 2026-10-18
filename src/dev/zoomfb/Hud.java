package dev.zoomfb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Draws the FPS and coordinates in the top left corner. Called once per frame from the patched
 * Gui render method (see Patcher.patchGui), with the GUI graphics object Minecraft passes in.
 *
 * Minecraft's internals are reached through reflection and looked up by SHAPE (types and
 * signatures) rather than by name wherever possible, so a rename between versions is less
 * likely to break it. If anything cannot be found, the HUD switches itself off and logs why;
 * it never throws into the render loop.
 */
final class Hud {
    private Hud() {}

    private static final int WHITE = 0xFFFFFFFF;

    private static boolean resolved;
    private static boolean broken;

    // Minecraft access
    private static Field minecraftField, playerField;
    private static Method getInstance;          // fallback only
    private static Method getFont, isHidden;    // on the game's Hud object
    private static Method getX, getY, getZ;

    // graphics text method: (Font, String, int x, int y, int color[, boolean shadow])
    private static Method textMethod;
    private static boolean textHasShadowArg;

    // fps counting
    private static long windowStart;
    private static int frames;
    private static int fps;
    private static long lastFrame;

    static void render(Object gameHud, Object graphics) {
        if (broken || gameHud == null || graphics == null) return;
        if (!Input.hud) return;

        long now = System.nanoTime();
        countFrame(now);

        try {
            if (!resolved) resolve(gameHud, graphics);
            if (broken) return;

            // Respect F1 (hide GUI): Hud.isHidden()
            if (isHidden != null && (boolean) isHidden.invoke(gameHud)) return;

            Object mc = minecraftField != null ? minecraftField.get(gameHud) : getInstance.invoke(null);
            if (mc == null) return;

            Object font = getFont.invoke(gameHud);
            if (font == null) return;

            int x = Config.hudX;
            int y = Config.hudY;
            int lineHeight = 10;

            if (Config.hudShowFps) {
                draw(graphics, font, (fps > 0 ? String.valueOf(fps) : "--") + " FPS", x, y);
                y += lineHeight;
            }

            if (Config.hudShowCoords) {
                Object player = playerField.get(mc);
                String line;
                if (player == null) {
                    line = "XYZ: -";
                } else {
                    String f = "%." + Config.hudDecimals + "f";
                    line = String.format(Locale.ROOT, "XYZ: " + f + " / " + f + " / " + f,
                            ((Number) getX.invoke(player)).doubleValue(),
                            ((Number) getY.invoke(player)).doubleValue(),
                            ((Number) getZ.invoke(player)).doubleValue());
                }
                draw(graphics, font, line, x, y);
            }
        } catch (Throwable t) {
            broken = true;
            Hooks.log("HUD disabled after an error: " + t);
        }
    }

    // ------------------------------------------------------------ fps

    /** Counts frames and refreshes the shown value twice per second. */
    private static void countFrame(long now) {
        // If the HUD was off (or the game stalled) for a while, start a fresh window.
        if (lastFrame == 0 || now - lastFrame > 1_500_000_000L) {
            windowStart = now;
            frames = 0;
        }
        lastFrame = now;
        frames++;
        long elapsed = now - windowStart;
        if (elapsed >= 500_000_000L) {
            fps = Math.round(frames * 1.0e9f / elapsed);
            windowStart = now;
            frames = 0;
        }
    }

    // ----------------------------------------------------------- draw

    private static void draw(Object graphics, Object font, String text, int x, int y) throws Exception {
        if (textHasShadowArg) {
            textMethod.invoke(graphics, font, text, x, y, WHITE, true);
        } else {
            textMethod.invoke(graphics, font, text, x, y, WHITE);
        }
    }

    // -------------------------------------------------------- lookup

    private static void resolve(Object gameHud, Object graphics) {
        resolved = true;
        try {
            Class<?> hudClass = gameHud.getClass();

            // The Hud keeps a reference to the Minecraft instance; use it (found by type).
            minecraftField = fieldBySimpleType(hudClass, "Minecraft");
            Class<?> mcClass;
            if (minecraftField != null) {
                mcClass = minecraftField.getType();
            } else {
                ClassLoader cl = graphics.getClass().getClassLoader();
                mcClass = Class.forName("net.minecraft.client.Minecraft", false, cl);
                for (Method m : mcClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                            && m.getReturnType() == mcClass) {
                        getInstance = m;
                        break;
                    }
                }
                if (getInstance == null) throw new IllegalStateException("Minecraft instance not found");
                getInstance.setAccessible(true);
            }

            playerField = fieldBySimpleType(mcClass, "LocalPlayer");
            if (playerField == null) throw new IllegalStateException("player field not found");

            getFont = hudClass.getMethod("getFont");
            Class<?> fontClass = getFont.getReturnType();

            try {
                isHidden = hudClass.getMethod("isHidden");
                if (isHidden.getReturnType() != boolean.class) isHidden = null;
            } catch (NoSuchMethodException e) {
                isHidden = null; // F1 support is optional
            }

            Class<?> playerClass = playerField.getType();
            getX = playerClass.getMethod("getX");
            getY = playerClass.getMethod("getY");
            getZ = playerClass.getMethod("getZ");

            textMethod = findTextMethod(graphics.getClass(), fontClass);
            if (textMethod == null) throw new IllegalStateException("text drawing method not found");
            textHasShadowArg = textMethod.getParameterCount() == 6;

            Hooks.log("HUD ready (text method " + textMethod.getName() + ")");
        } catch (Throwable t) {
            broken = true;
            Hooks.log("HUD disabled, could not find Minecraft members: " + t);
        }
    }

    private static Field fieldBySimpleType(Class<?> owner, String simpleName) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().getSimpleName().equals(simpleName)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * Finds the "draw a string" method on the graphics class: public, parameters
     * (Font, String, int, int, int) or (Font, String, int, int, int, boolean). The return type
     * is ignored (it is int in older versions, void in newer ones). Prefers the 6-argument form
     * (with drop shadow) and the names "text" / "drawString".
     */
    private static Method findTextMethod(Class<?> graphicsClass, Class<?> fontClass) {
        Method best = null;
        int bestScore = -1;
        for (Method m : graphicsClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean five = p.length == 5 && p[2] == int.class && p[3] == int.class && p[4] == int.class;
            boolean six = p.length == 6 && p[2] == int.class && p[3] == int.class && p[4] == int.class
                    && p[5] == boolean.class;
            if (!(five || six)) continue;
            if (p[0] != fontClass || p[1] != String.class) continue;

            int score = six ? 2 : 1;
            String n = m.getName();
            if (n.equals("text") || n.equals("drawString")) score += 4;
            if (score > bestScore) {
                best = m;
                bestScore = score;
            }
        }
        if (best != null) best.setAccessible(true);
        return best;
    }
}