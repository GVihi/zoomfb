package dev.zoomfb;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Names for GLFW key codes and mouse buttons, so the config file can say "F6" or "MOUSE_4"
 * instead of numbers. Mouse buttons are encoded as MOUSE_BASE + buttonIndex.
 */
final class Keys {
    private Keys() {}

    static final int NONE = -1;
    static final int MOUSE_BASE = 1000;

    private static final Map<String, Integer> BY_NAME = new LinkedHashMap<>();
    private static final Map<Integer, String> BY_CODE = new HashMap<>();
    private static final Map<String, Integer> ALIASES = new HashMap<>();

    static {
        for (char c = 'A'; c <= 'Z'; c++) add(String.valueOf(c), c);
        for (char c = '0'; c <= '9'; c++) add(String.valueOf(c), c);
        for (int i = 1; i <= 25; i++) add("F" + i, 289 + i);
        for (int i = 0; i <= 9; i++) add("KP_" + i, 320 + i);

        add("SPACE", 32);
        add("APOSTROPHE", 39);
        add("COMMA", 44);
        add("MINUS", 45);
        add("PERIOD", 46);
        add("SLASH", 47);
        add("SEMICOLON", 59);
        add("EQUAL", 61);
        add("LEFT_BRACKET", 91);
        add("BACKSLASH", 92);
        add("RIGHT_BRACKET", 93);
        add("GRAVE_ACCENT", 96);

        add("ESCAPE", 256);
        add("ENTER", 257);
        add("TAB", 258);
        add("BACKSPACE", 259);
        add("INSERT", 260);
        add("DELETE", 261);
        add("RIGHT", 262);
        add("LEFT", 263);
        add("DOWN", 264);
        add("UP", 265);
        add("PAGE_UP", 266);
        add("PAGE_DOWN", 267);
        add("HOME", 268);
        add("END", 269);
        add("CAPS_LOCK", 280);
        add("SCROLL_LOCK", 281);
        add("NUM_LOCK", 282);
        add("PRINT_SCREEN", 283);
        add("PAUSE", 284);

        add("KP_DECIMAL", 330);
        add("KP_DIVIDE", 331);
        add("KP_MULTIPLY", 332);
        add("KP_SUBTRACT", 333);
        add("KP_ADD", 334);
        add("KP_ENTER", 335);

        add("LEFT_SHIFT", 340);
        add("LEFT_CONTROL", 341);
        add("LEFT_ALT", 342);
        add("LEFT_SUPER", 343);
        add("RIGHT_SHIFT", 344);
        add("RIGHT_CONTROL", 345);
        add("RIGHT_ALT", 346);
        add("RIGHT_SUPER", 347);
        add("MENU", 348);

        // MOUSE_1 = left, MOUSE_2 = right, MOUSE_3 = middle, MOUSE_4/MOUSE_5 = side buttons
        for (int i = 1; i <= 8; i++) add("MOUSE_" + i, MOUSE_BASE + i - 1);

        ALIASES.put("-", 45);
        ALIASES.put("=", 61);
        ALIASES.put("[", 91);
        ALIASES.put("]", 93);
        ALIASES.put(";", 59);
        ALIASES.put("'", 39);
        ALIASES.put(",", 44);
        ALIASES.put(".", 46);
        ALIASES.put("/", 47);
        ALIASES.put("\\", 92);
        ALIASES.put("`", 96);
    }

    private static void add(String name, int code) {
        BY_NAME.put(name, code);
        BY_CODE.put(code, name);
    }

    /** Parses a key name, a single punctuation character, a GLFW code, or NONE. */
    static int parse(String s, int def) {
        if (s == null) return def;
        s = s.trim();
        if (s.isEmpty()) return def;

        String u = s.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (u.equals("NONE") || u.equals("OFF") || u.equals("UNBOUND")) return NONE;

        Integer c = BY_NAME.get(u);
        if (c != null) return c;
        Integer a = ALIASES.get(s);
        if (a != null) return a;

        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            Hooks.log("unknown key '" + s + "', using " + name(def));
            return def;
        }
    }

    static String name(int code) {
        if (code == NONE) return "NONE";
        String n = BY_CODE.get(code);
        return n != null ? n : Integer.toString(code);
    }
}
