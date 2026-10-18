package dev.zoomfb;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Patches three things, found without relying on method names:
 *
 *  1. ZOOM: in net/minecraft/client/Camera, the method that computes the field of view.
 *     It is the float-returning method containing the constant 0.85714287F (the
 *     water/effect FOV scale). Every FRETURN in it gets a call to Hooks.fov(float).
 *
 *  3. HUD: in net/minecraft/client/gui/Hud, the public (GuiGraphics*, DeltaTracker) -> void
 *     render method gets a call to Hooks.hud(this, graphics) before each RETURN.
 *
 *  2. FULLBRIGHT: every read of LightmapRenderState.brightness and
 *     LightmapRenderState.nightVisionEffectIntensity (outside the extractor class) is
 *     wrapped in a call to Hooks.brightness / Hooks.nightVision.
 */
final class Patcher implements ClassFileTransformer {

    private static final String CAMERA = "net/minecraft/client/Camera";
    private static final String GUI = "net/minecraft/client/gui/Hud";
    private static final MethodTypeDesc TWO_OBJECTS_TO_VOID =
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object);
    private static final ClassDesc HOOKS = ClassDesc.of("dev.zoomfb.Hooks");
    private static final MethodTypeDesc FLOAT_TO_FLOAT =
            MethodTypeDesc.of(ConstantDescs.CD_float, ConstantDescs.CD_float);
    private static final float FOV_MARKER = 0.85714287F;

    private static final byte[] LIGHTMAP_STATE_ASCII =
            "LightmapRenderState".getBytes(StandardCharsets.US_ASCII);

    private final ClassFile cf = ClassFile.of(
            ClassFile.ClassHierarchyResolverOption.of(
                    ClassHierarchyResolver.defaultResolver()
                            .orElse(ClassHierarchyResolver.ofResourceParsing(ClassLoader.getSystemClassLoader()))));

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                            ProtectionDomain pd, byte[] bytes) {
        if (className == null || !className.startsWith("net/minecraft/")) return null;
        try {
            if (DUMP && looksLikeControlsCode(className)) {
                dump(bytes, className);
            }
            if (className.equals(CAMERA)) {
                return patchCamera(bytes);
            }
            if (className.equals(GUI)) {
                return patchGui(bytes);
            }
            if (!className.endsWith("LightmapRenderState")
                    && !className.endsWith("LightmapRenderStateExtractor")
                    && contains(bytes, LIGHTMAP_STATE_ASCII)) {
                return patchLightmapReads(bytes, className);
            }
        } catch (Throwable t) {
            // Never break the game because of a failed patch: log and leave the class alone.
            Hooks.log("FAILED to patch " + className + ": " + t);
        }
        return null;
    }

    // ---------------------------------------------------------------- zoom

    private byte[] patchCamera(byte[] bytes) {
        ClassModel cm = cf.parse(bytes);
        Set<String> targets = new HashSet<>();

        for (MethodModel mm : cm.methods()) {
            if (!mm.methodTypeSymbol().returnType().equals(ConstantDescs.CD_float)) continue;
            Optional<CodeModel> code = mm.code();
            if (code.isEmpty()) continue;
            for (CodeElement e : code.get()) {
                if (e instanceof ConstantInstruction.LoadConstantInstruction lc
                        && lc.constantValue() instanceof Float f
                        && f.floatValue() == FOV_MARKER) {
                    targets.add(key(mm));
                    break;
                }
            }
        }

        if (targets.isEmpty()) {
            Hooks.log("WARNING: could not find the FOV method in Camera (zoom disabled). "
                    + "Minecraft's code may have changed.");
            return null;
        }
        if (targets.size() > 1) {
            Hooks.log("note: several FOV candidates in Camera, patching all: " + targets);
        }

        byte[] out = cf.transformClass(cm, ClassTransform.transformingMethodBodies(
                mm -> targets.contains(key(mm)),
                (CodeBuilder b, CodeElement e) -> {
                    if (e instanceof ReturnInstruction r && r.opcode() == Opcode.FRETURN) {
                        b.invokestatic(HOOKS, "fov", FLOAT_TO_FLOAT);
                    }
                    b.with(e);
                }));
        Hooks.log("patched zoom hook into Camera " + targets);
        return out;
    }

    // ------------------------------------------------------------------ hud

    /**
     * In net/minecraft/client/gui/Hud (26.2: the class that draws hotbar, crosshair, chat and so
     * on), finds the public instance method of shape (GuiGraphics*, DeltaTracker) returning
     * void whose name contains "render" (extractRenderState). Before every RETURN it inserts
     * Hooks.hud(this, graphics) so our text is drawn on top of the normal HUD.
     */
    private byte[] patchGui(byte[] bytes) {
        ClassModel cm = cf.parse(bytes);

        // Tier 1: public instance void method (GuiGraphics*, DeltaTracker).
        // Tier 2: same shape but any visibility, name must contain "render".
        // Tier 3: any non-static void method whose FIRST parameter is a GuiGraphics*, name
        //         contains "render" (covers a changed second parameter).
        MethodModel chosen = null;
        for (int tier = 1; tier <= 3 && chosen == null; tier++) {
            List<MethodModel> found = new java.util.ArrayList<>();
            for (MethodModel mm : cm.methods()) {
                if (mm.code().isEmpty() || mm.flags().has(AccessFlag.STATIC)) continue;
                MethodTypeDesc d = mm.methodTypeSymbol();
                if (!d.returnType().equals(ConstantDescs.CD_void)) continue;
                if (d.parameterCount() < 1 || !d.parameterType(0).displayName().startsWith("GuiGraphics")) continue;
                boolean twoParams = d.parameterCount() == 2
                        && d.parameterType(1).displayName().endsWith("DeltaTracker");
                boolean render = mm.methodName().stringValue().toLowerCase().contains("render");
                boolean pub = mm.flags().has(AccessFlag.PUBLIC);
                boolean ok = switch (tier) {
                    case 1 -> twoParams && pub;
                    case 2 -> twoParams && render;
                    default -> render;
                };
                if (ok) found.add(mm);
            }
            if (found.isEmpty()) continue;
            chosen = found.get(0);
            for (MethodModel mm : found) {
                if (mm.methodName().stringValue().toLowerCase().contains("render")
                        && mm.flags().has(AccessFlag.PUBLIC)) {
                    chosen = mm;
                    break;
                }
            }
            if (found.size() > 1) {
                StringBuilder sb = new StringBuilder();
                for (MethodModel mm : found) sb.append(key(mm)).append("  ");
                Hooks.log("note: " + found.size() + " GUI render candidates (tier " + tier + "): "
                        + sb + "-> using " + key(chosen));
            }
        }

        if (chosen == null) {
            Hooks.log("WARNING: could not find the render method in Hud (HUD disabled). "
                    + "Methods in Hud that involve a graphics type:");
            for (MethodModel mm : cm.methods()) {
                String desc = mm.methodType().stringValue();
                if (desc.contains("Graphics")) {
                    Hooks.log("    " + mm.flags().flagsMask() + " " + key(mm));
                }
            }
            return null;
        }

        final int graphicsSlot = 1; // instance method: slot 0 is "this", slot 1 the first parameter
        String chosenKey = key(chosen);
        byte[] out = cf.transformClass(cm, ClassTransform.transformingMethodBodies(
                mm -> key(mm).equals(chosenKey),
                (CodeBuilder b, CodeElement e) -> {
                    if (e instanceof ReturnInstruction r && r.opcode() == Opcode.RETURN) {
                        b.aload(0);             // this (the Hud instance)
                        b.aload(graphicsSlot);  // the GuiGraphicsExtractor
                        b.invokestatic(HOOKS, "hud", TWO_OBJECTS_TO_VOID);
                    }
                    b.with(e);
                }));
        Hooks.log("patched HUD hook into Hud " + chosenKey);
        return out;
    }

    // ----------------------------------------------------------- fullbright

    private byte[] patchLightmapReads(byte[] bytes, String className) {
        ClassModel cm = cf.parse(bytes);

        int count = 0;
        for (MethodModel mm : cm.methods()) {
            Optional<CodeModel> code = mm.code();
            if (code.isEmpty()) continue;
            for (CodeElement e : code.get()) {
                if (isTargetRead(e) != null) count++;
            }
        }
        if (count == 0) return null;

        byte[] out = cf.transformClass(cm, ClassTransform.transformingMethodBodies(
                (CodeBuilder b, CodeElement e) -> {
                    b.with(e);
                    String hook = isTargetRead(e);
                    if (hook != null) {
                        b.invokestatic(HOOKS, hook, FLOAT_TO_FLOAT);
                    }
                }));
        Hooks.log("patched " + count + " lightmap read(s) in " + className);
        return out;
    }

    /** Returns the hook name if e is a GETFIELD of one of the lightmap float fields, else null. */
    private static String isTargetRead(CodeElement e) {
        if (e instanceof FieldInstruction fi
                && fi.opcode() == Opcode.GETFIELD
                && fi.owner().asInternalName().endsWith("LightmapRenderState")
                && fi.typeSymbol().equals(ConstantDescs.CD_float)) {
            if (fi.name().equalsString("brightness")) return "brightness";
            if (fi.name().equalsString("nightVisionEffectIntensity")) return "nightVision";
        }
        return null;
    }

    // ----------------------------------------------------- optional dump

    /** Run the game with -Dzoomfb.dump=true to write zoomfb-dump.txt (names only, no code). */
    private static final boolean DUMP = Boolean.getBoolean("zoomfb.dump");

    private static boolean looksLikeControlsCode(String internalName) {
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        return simple.equals("KeyMapping") || simple.equals("Options")
                || simple.startsWith("KeyBind") || simple.startsWith("Controls");
    }

    private void dump(byte[] bytes, String className) throws IOException {
        ClassModel cm = cf.parse(bytes);
        StringBuilder sb = new StringBuilder();
        sb.append("class ").append(className).append('\n');
        cm.fields().forEach(f -> sb.append("  field  ")
                .append(f.fieldName().stringValue()).append(' ')
                .append(f.fieldType().stringValue()).append('\n'));
        cm.methods().forEach(m -> sb.append("  method ")
                .append(m.methodName().stringValue())
                .append(m.methodType().stringValue()).append('\n'));
        sb.append('\n');
        Files.writeString(Path.of("zoomfb-dump.txt"), sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // -------------------------------------------------------------- helpers

    private static String key(MethodModel mm) {
        return mm.methodName().stringValue() + mm.methodType().stringValue();
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}