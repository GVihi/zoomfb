# zoomfb: zoom and fullbright for Minecraft Java 26.2, without a mod loader

zoomfb gives you three things that OptiFine users are used to:

- **Zoom**: hold a key and the view zooms in (with smooth easing), like OptiFine's zoom.
- **Fullbright**: press a key to light up everything, like OptiFine/Gamma mods, and press again to turn it off.
- **FPS and coordinates**: a small readout in the top left corner (`60 FPS` and `XYZ: x / y / z`). Press **F8** to hide or show it.

It is a **Java agent**: one small `.jar` that you attach to the game with a launcher setting.
There is no Fabric, Forge, NeoForge, Quilt or OptiFine involved, and nothing is installed into
the `mods` folder. The whole thing is a few hundred lines of plain Java that you can read and
build yourself.

> **Status, please read:** The settings, key handling, rebinding and zoom/fullbright logic were
> compiled and tested here against a stand-in for GLFW (the library Minecraft reads keys through).
> The step that patches Minecraft itself could **not** be run, because there was no
> Java 25 JDK and no 26.2 client jar available where this was written. It finds its targets by
> content rather than by name to reduce breakage, and if a target is missing it switches that
> feature off and writes a warning to the log instead of crashing. See
> [Troubleshooting](#troubleshooting).

---

## Contents

1. [Quick start](#quick-start)
2. [Requirements](#requirements)
3. [Building the jar](#building-the-jar)
4. [Installing it in your launcher](#installing-it-in-your-launcher)
5. [Default controls](#default-controls)
6. [Changing the controls](#changing-the-controls)
7. [Configuration reference](#configuration-reference)
8. [Key names](#key-names)
9. [Checking that it works](#checking-that-it-works)
10. [Troubleshooting](#troubleshooting)
11. [How it works](#how-it-works)
12. [Known limitations](#known-limitations)
13. [Multiplayer and fairness](#multiplayer-and-fairness)
14. [Security note about Java agents](#security-note-about-java-agents)
15. [Uninstalling](#uninstalling)
16. [Project layout](#project-layout)

---

## Quick start

1. Build: `./build.sh` (Linux/macOS) or `build.bat` (Windows). You get `zoomfb.jar`.
2. Put `zoomfb.jar` in a permanent place, for example inside your `.minecraft` folder.
3. In your launcher, add this to the **JVM arguments** of your Minecraft 26.2 profile
   (use the real full path):

       -javaagent:/full/path/to/zoomfb.jar

4. Start the game, load a world. Hold **Z** to zoom. Press **G** to toggle fullbright.
5. Change the keys any time by pressing **F6** (zoom) or **F7** (fullbright) in the world and then
   pressing the new key. See [Changing the controls](#changing-the-controls).

---

## Requirements

- **Minecraft Java Edition 26.2.** It is written for this version. Other versions may work if the
  game code is similar, but 26.3 replaces GLFW with SDL, which breaks the key reading
  (see [Known limitations](#known-limitations)).
- **Java 25 to run** Minecraft 26.2 (the launcher normally provides this itself).
- **A JDK 24 or newer to build** the jar, because the patching uses the `java.lang.classfile`
  API that ships with the JDK. A JDK (not just a JRE) is needed because it contains `javac`
  and `jar`. If you only have the launcher's bundled runtime, install a JDK separately; it is
  only needed for building, not for playing.

---

## Building the jar

Linux and macOS:

    ./build.sh

Windows (Command Prompt, from the project folder):

    build.bat

Both do the same thing: compile the sources in `src/` with `--release 25` into `build/classes`, then
package them with `manifest.mf` into `zoomfb.jar`. If the command `javac` is not found, your JDK is
not on the `PATH`; if it complains about `java.lang.classfile`, your JDK is older than 24.

---

## Installing it in your launcher

The goal is always the same: add one JVM argument. Replace the path with where you put the jar.

| Launcher | Where to put it |
|---|---|
| **Official Minecraft Launcher** | Installations tab, hover your installation, click the three dots, Edit, **More options**, then add `-javaagent:C:\path\to\zoomfb.jar` to **JVM arguments** (keep the existing ones, separated by a space). |
| **Prism Launcher / MultiMC** | Right-click the instance, Edit, **Settings**, **Java** tab, tick **JVM arguments**, add `-javaagent:/path/to/zoomfb.jar`. |
| **CurseForge app / ATLauncher / others** | Look for "JVM arguments", "Java arguments" or "Extra Java options" in the profile or instance settings. |
| **Command line** | `java -javaagent:/path/to/zoomfb.jar <your usual arguments>` |

Notes:

- If the path contains spaces, wrap the whole argument in quotes where the launcher allows it.
- Use an absolute path. A relative path depends on the launcher's working directory.
- The config file `zoomfb.properties` is created in the game's working directory (normally the
  instance's `.minecraft` folder). To put it somewhere else, add
  `-Dzoomfb.config=/some/path/zoomfb.properties` as another JVM argument.

---

## Default controls

| Action | Default | Notes |
|---|---|---|
| Zoom | hold **Z** | 4x by default, eased in and out |
| Make zoom stronger / weaker | **=** / **-** while zooming | each press changes it by 25 percent, saved when you stop zooming |
| Toggle fullbright | **G** | |
| Show / hide the FPS and coordinates | **F8** | shown by default; hidden automatically with F1 |
| Rebind the zoom key | **F6** | then press the new key |
| Rebind the fullbright key | **F7** | then press the new key |

All of these only react while you are playing with the mouse captured. Keys are ignored when chat,
the inventory, the pause menu or any other screen is open, so typing a "z" in chat does not zoom.

---

## Changing the controls

There are three ways. They all end up in the same file, `zoomfb.properties`.

### 1. Rebind in the game (no restart)

1. In the world, press **F6** to rebind zoom, or **F7** to rebind fullbright.
2. The window title changes to
   `zoomfb: press the new ZOOM key or mouse button (Esc = cancel)`.
3. Release everything, then press the key or mouse button you want. Keyboard keys and mouse buttons
   (including side buttons) both work.
4. The title confirms the new key for three seconds, then goes back to normal. The choice is saved to
   `zoomfb.properties` straight away.

Details and caveats:

- Press **Esc** to cancel. The attempt also cancels if you open any menu, or after 15 seconds.
- The prompt is shown in the **window title bar**, so you will not see it in fullscreen. In fullscreen,
  it still works (the change is also written to the log); you just do not get the visual prompt.
  Switch to windowed mode for a moment if you want to see it.
- The key you choose is still seen by Minecraft too, because zoomfb watches the keyboard on the side
  rather than registering a real Minecraft keybind. So pick a key that is not already used by the game.
  Binding zoom to `W` would zoom every time you walk forward. Unused keys such as `Z`, `G`, `V`, `B`,
  `F6` to `F10`, or a mouse side button are good choices. The default `C` is deliberately avoided
  because the game uses it for saving a hotbar in creative mode.

### 2. Edit the file while the game runs

Open `zoomfb.properties` in any text editor, change a value and save. Within about two seconds
zoomfb notices and applies it, with no restart. A line `config file changed, reloading` appears in the
log. Use this for everything that has no in-game shortcut: zoom strength, hold versus toggle,
brightness, and so on.

### 3. Edit the file before starting

The same file is read when the game starts. If it does not exist yet, zoomfb writes it with the
default values (and explanatory comments) the first time you launch.

> **Why not an entry in Minecraft's own Options, Controls screen?**
> A real entry there would mean patching Minecraft's key-mapping code, whose names and structure I could
> not inspect here, and which changes between versions. Getting it wrong would be a crash risk, not just a
> missing feature. The in-game rebinding above covers the practical need with far less risk. If you want
> the real thing, run the game once with `-Dzoomfb.dump=true` (see [Dump mode](#dump-mode)) and send the
> resulting `zoomfb-dump.txt`; that is exactly the information needed to add it correctly.

---

## Configuration reference

All settings live in `zoomfb.properties`. Lines starting with `#` are comments. Names are
case-insensitive for keys; setting names are exact.

| Setting | Default | Meaning |
|---|---|---|
| `zoom.key` | `Z` | Key or mouse button for zoom. |
| `zoom.toggle` | `false` | `false`: zoom only while the key is held. `true`: press once to zoom, again to stop. |
| `zoom.factor` | `4.0` | Zoom strength. The field of view is divided by this. Allowed range 1.25 to 30. |
| `zoom.smooth` | `true` | Ease in and out instead of snapping. |
| `zoom.increaseKey` | `EQUAL` | While zooming, press to zoom in further (x1.25 per press). |
| `zoom.decreaseKey` | `MINUS` | While zooming, press to zoom out a bit (÷1.25 per press). |
| `fullbright.key` | `G` | Key or mouse button that toggles fullbright. |
| `fullbright.startOn` | `false` | Start every game with fullbright already on. |
| `fullbright.brightness` | `1.0` | Gamma-style brightness forced while fullbright is on. 1.0 equals the "Bright" slider maximum. |
| `fullbright.nightVision` | `1.0` | Strength of the night-vision-style ambient light forced on. 1.0 is full. |
| `hud.key` | `F8` | Key that shows/hides the FPS and coordinates readout. |
| `hud.startOn` | `true` | Show the readout when the game starts. |
| `hud.fps` | `true` | Show the FPS line. |
| `hud.coords` | `true` | Show the XYZ line. |
| `hud.decimals` | `1` | Decimal places for the coordinates (0 to 6). |
| `hud.x`, `hud.y` | `4`, `4` | Offset from the top left corner in GUI pixels. The F3 debug screen uses the same corner; raise `hud.y` (for example to `80`) or press F8 to avoid overlap. |
| `rebind.zoomKey` | `F6` | Key that starts rebinding zoom. `NONE` disables it. |
| `rebind.fullbrightKey` | `F7` | Key that starts rebinding fullbright. `NONE` disables it. |
| `config.liveReload` | `true` | Re-read the file automatically when it changes. If you set this to `false`, a restart is needed to turn it back on. |

Setting any key to `NONE` disables that action.

### Tuning fullbright

Since 26.1 the game computes lighting differently, and night vision now adds ambient light instead of
scaling colours. zoomfb forces both the brightness value and the night-vision strength while fullbright
is on. If dark caves still look dim:

1. Raise `fullbright.brightness` to `2`, then `4`, then `8`, saving after each change (it applies live).
2. Stop raising it as soon as it looks right. Other mods that do this report that extremely large values
   can cause black or garbled textures on some graphics cards.

### Tuning zoom

- A bigger `zoom.factor` zooms further. `2` is mild, `4` is similar to OptiFine's default, `10` or more is
  binocular territory.
- You can change it on the fly with `=` and `-` while holding zoom. Your final value is written back to the
  file when you let go.

---

## Key names

Anywhere the config wants a key, you can use:

- Letters and digits: `A` to `Z`, `0` to `9`
- Function keys: `F1` to `F25`
- Navigation: `UP`, `DOWN`, `LEFT`, `RIGHT`, `HOME`, `END`, `PAGE_UP`, `PAGE_DOWN`, `INSERT`, `DELETE`
- Editing and whitespace: `SPACE`, `TAB`, `ENTER`, `BACKSPACE`, `ESCAPE`
- Modifiers: `LEFT_SHIFT`, `RIGHT_SHIFT`, `LEFT_CONTROL`, `RIGHT_CONTROL`, `LEFT_ALT`, `RIGHT_ALT`,
  `LEFT_SUPER`, `RIGHT_SUPER`
- Punctuation: `MINUS`, `EQUAL`, `LEFT_BRACKET`, `RIGHT_BRACKET`, `SEMICOLON`, `APOSTROPHE`, `COMMA`,
  `PERIOD`, `SLASH`, `BACKSLASH`, `GRAVE_ACCENT` (the single characters `- = [ ] ; ' , . / \ \`` also work,
  except that backslash must be written `BACKSLASH` in a properties file)
- Keypad: `KP_0` to `KP_9`, `KP_DECIMAL`, `KP_DIVIDE`, `KP_MULTIPLY`, `KP_SUBTRACT`, `KP_ADD`, `KP_ENTER`
- Mouse: `MOUSE_1` (left), `MOUSE_2` (right), `MOUSE_3` (middle), `MOUSE_4` and `MOUSE_5` (the usual
  side buttons), up to `MOUSE_8`
- `NONE` to disable
- A raw GLFW key code number, if you need a key not listed

The in-game rebinding writes these names for you, so you rarely need to type them.

---

## Checking that it works

After launching, open `logs/latest.log` in the game folder and search for `[zoomfb]`. A healthy start looks
like this (the exact class details will differ):

    [zoomfb] starting (zoom + fullbright agent)
    [zoomfb] loaded config /…/zoomfb.properties
    [zoomfb] patched zoom hook into Camera [...]
    [zoomfb] patched 1 lightmap read(s) in net.minecraft.client...
    [zoomfb] patched HUD hook into Gui ...
    [zoomfb] HUD ready (text method ...)     (appears once you load a world)

What each line means:

- `patched zoom hook into Camera`: the field-of-view code was found and hooked. Zoom should work.
- `patched N lightmap read(s)`: the lightmap code was found and hooked. Fullbright should work.
  Seeing this line once or twice (for different classes) is normal.
- When you toggle fullbright you will also see `fullbright ON` or `fullbright OFF`.

---

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| Game crashes at startup with a message about the agent | The path in `-javaagent:` is wrong, or the jar was built with a JDK newer than the Java running the game. Check the path, and rebuild with `--release 25` (the scripts already do). |
| No `[zoomfb]` lines in the log at all | The JVM argument was not applied. Re-check the launcher setting, and that you edited the profile you actually launch. |
| `WARNING: could not find the FOV method in Camera` | Zoom is disabled. The game's code differs from what zoomfb expects (a different Minecraft version, or a changed `Camera` class). The game still runs normally. |
| No `patched ... lightmap` line | Fullbright is disabled for the same reason. The names `LightmapRenderState`, `brightness` and `nightVisionEffectIntensity` were taken from public changelogs and other mods' notes, not from a decompiled 26.2 jar, so this is the more likely one to need adjusting. |
| `WARNING: could not find the GUI render method in Gui` | The FPS/coordinates readout is disabled; zoom and fullbright are unaffected. The `Gui` class differs from what zoomfb expects. |
| `HUD disabled, could not find Minecraft members: ...` | The overlay found the render hook but not the player, font or text-drawing method (it looks them up by type and signature). The message names what was missing. Send me that line. |
| Readout overlaps the F3 screen | Both use the top left. Press F8 to hide it, or raise `hud.y` in the config. |
| `FAILED to patch <class>: ...` | The class could not be rewritten (usually a class-hierarchy lookup problem). Send me that log line. The class is left unmodified. |
| `GLFW not available, keys disabled` | The agent could not reach LWJGL's GLFW class (for example on 26.3, which uses SDL). Zoom and toggles will not respond. |
| Zoom works but fullbright does nothing | See the `patched ... lightmap` line above. If it is present, raise `fullbright.brightness` (see [Tuning fullbright](#tuning-fullbright)). The lighting updates once per tick, so changes appear within a twentieth of a second, not instantly. |
| Everything turns black or garbled with fullbright on | `fullbright.brightness` is too high for your GPU. Lower it. |
| Zoom key also does something else in the game | You picked a key the game already uses. Rebind to a free key (F6 then a new key). |
| Key presses do nothing | You are in a menu or chat (input is ignored there), or the key is set to `NONE`. |
| Editing the config has no effect | `config.liveReload` is `false`, or the file is not the one the game is using (check the path in the log line `loaded config ...`). |
| No window-title prompt when rebinding | You are in fullscreen. It still works; check the log. |

When asking for help, the most useful things to send are the `[zoomfb]` lines from `logs/latest.log` and,
if you get a build error, the compiler output.

### Dump mode

Run the game once with the extra JVM argument:

    -Dzoomfb.dump=true

A file `zoomfb-dump.txt` appears in the game folder. It lists only names and type signatures of the
fields and methods in Minecraft's key-mapping and options classes. It contains no game code. It exists so
that deeper integration (for example, real entries in the Controls screen) can be written against what
your 26.2 install actually contains. Remove the argument afterwards.

---

## How it works

zoomfb never edits Minecraft's files on disk. When the JVM starts, it calls the agent's `premain`, which
registers a *class transformer*. Whenever Minecraft loads one of its own classes, the transformer may
rewrite the bytecode in memory, using the `java.lang.classfile` API that is part of the JDK.

**Zoom.** In 26.2 the field-of-view calculation is a float-returning method in `net.minecraft.client.Camera`.
The transformer finds it by looking for the constant `0.85714287F` inside it (the water and effect scale),
instead of relying on a method name. Before each `return` in that method it inserts a call to
`Hooks.fov(float)`, which divides the value by the current zoom.

**Fullbright.** Since 26.1 the game builds a small "lightmap" state once per tick. The transformer wraps
every read of `LightmapRenderState.brightness` and `LightmapRenderState.nightVisionEffectIntensity`
(everywhere except the class that fills them in) in a call to `Hooks.brightness` or `Hooks.nightVision`.
While fullbright is off, the hooks return the value unchanged. While it is on, they return at least the
configured value.

**Keys.** Once per frame, from the zoom hook, zoomfb asks GLFW (through the current window) which keys
and mouse buttons are down. Using the *current context* means it does not need any Minecraft class to find
the window. It only counts input while the mouse is captured, which is how it avoids reacting in menus and
chat. GLFW is reached through method handles, so building needs no LWJGL jar.

**HUD.** In `net.minecraft.client.gui.Gui`, the public method that takes a graphics object and a `DeltaTracker` and returns void
draws the whole in-game GUI each frame (`render` before 26.1, `extractRenderState` since). The transformer adds a call to
`Hooks.hud(graphics)` before its `return`, so the text is drawn on top of everything else. `Hud.java` then finds the
player, the font and the "draw a string" method on the graphics object by their types and signatures (not names), reads
`getX/getY/getZ`, and counts frames itself for the FPS value (refreshed twice a second).

**Safety.** Every patch is wrapped so that an error leaves the original class untouched, and every runtime
hook swallows its own exceptions so a bug cannot stop rendering.

---

## Known limitations

- **26.2 only (GLFW).** 26.3 swaps GLFW for SDL, so key reading would need to be rewritten there.
- **Lightmap names are unverified against the real jar.** If the fullbright patch does not apply, the log
  says so.
- **Not a real Minecraft keybind.** The keys do not appear in Options, Controls, and Minecraft does not know
  they are in use, so it will not warn you about conflicts. See
  [Changing the controls](#changing-the-controls).
- **The HUD is also untested against a real 26.2 client.** It was tested against stand-in classes shaped like the 26.x ones. It
  draws plain white text with a shadow, with no background box.
- **No on-screen message** when toggling fullbright; the result is visible in the world and is logged.
- **Mouse sensitivity is not reduced** while zoomed (OptiFine does this).
- **Hand and item view** follows the same field of view change, so held items may look larger when zoomed.
- **Updates.** A Minecraft update can move the patched code. Because targets are found by content, small
  changes should be survivable, but check the log after each update.

---

## Multiplayer and fairness

zoomfb changes only what your own game draws. It does not send anything extra to servers. Even so,
fullbright in particular gives a visibility advantage, and many servers forbid it or any client-side
lighting changes in their rules, and some detect modified clients. Check the rules of any server before
using it. Zoom is generally accepted but can also be restricted.

---

## Security note about Java agents

A Java agent runs with the full permissions of the game. Only attach agents whose source you have read and
built yourself. This one is small enough to review in a few minutes; the files to look at are `Patcher.java`
(what it changes in the game) and `Input.java` (what it reads). It does not use the network.

---

## Uninstalling

Remove the `-javaagent:...` argument from the launcher and delete `zoomfb.jar` and `zoomfb.properties`.
Nothing else was changed: no game files, no world data.

---

## Project layout

    zoomfb/
      README.md            this file
      build.sh             build script for Linux and macOS
      build.bat            build script for Windows
      manifest.mf          tells the JVM which class is the agent entry point
      zoomfb.properties    example/default configuration
      src/dev/zoomfb/
        Agent.java         premain: loads config, registers the transformer
        Patcher.java       finds and patches the FOV and lightmap code (and the optional dump)
        Hooks.java         the few static methods the patched game code calls
        Hud.java           the FPS and coordinates overlay
        Input.java         GLFW polling, zoom, fullbright toggle, in-game rebinding
        Config.java        settings: load, save, live reload
        Keys.java          key and mouse-button names
