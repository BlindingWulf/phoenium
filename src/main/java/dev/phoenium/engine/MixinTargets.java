package dev.phoenium.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Stage 11 (2026-10-18): a mod's mixin targets read from the mod's own files (fabric.mod.json, its mixin configs and the
 * {@code @Mixin} annotation of each listed class). Mixin's own registry cannot be used at server start:
 * {@code Mixins.getConfigs()} is the pending set, which {@code MixinProcessor.selectConfigs} empties as it consumes it.
 */
final class MixinTargets {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private MixinTargets() { }

    /** The classes a mixin class targets ({@code value} types, then {@code targets} names), dotted; empty if not a mixin. */
    static List<String> targets(byte[] classBytes) {
        List<String> out = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN.equals(descriptor)) return null;
                List<String> values = new ArrayList<>(), names = new ArrayList<>();
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        List<String> into = "value".equals(name) ? values : "targets".equals(name) ? names : null;
                        if (into == null) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String unused, Object value) {
                                if (value instanceof Type t) into.add(t.getClassName());
                                else if (value instanceof String s) into.add(s.replace('/', '.'));
                            }
                        };
                    }

                    @Override
                    public void visitEnd() {
                        out.addAll(values);
                        out.addAll(names);
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    /**
     * The targets a mixin class can add behaviour to: none for an interface mixin, which can only expose fields and
     * methods ({@code @Accessor}/{@code @Invoker}) and so adds no callbacks.
     */
    static List<String> hookTargets(byte[] classBytes) {
        return (new ClassReader(classBytes).getAccess() & Opcodes.ACC_INTERFACE) != 0 ? List.of() : targets(classBytes);
    }

    /** The mixin classes a mixin config applies on a dedicated server ({@code mixins} and {@code server}, not {@code client}). */
    static List<String> configClasses(String mixinConfigJson) {
        JsonObject root = JsonParser.parseString(mixinConfigJson).getAsJsonObject();
        String pkg = root.has("package") ? root.get("package").getAsString() : "";
        List<String> out = new ArrayList<>();
        for (String list : new String[] {"mixins", "server"}) {
            if (!root.has(list) || !root.get(list).isJsonArray()) continue;
            for (JsonElement e : root.getAsJsonArray(list)) out.add(pkg.isEmpty() ? e.getAsString() : pkg + "." + e.getAsString());
        }
        return out;
    }

    /** The mixin config files a fabric.mod.json declares, without client-only ones. */
    static List<String> modConfigs(String fabricModJson) {
        JsonObject root = JsonParser.parseString(fabricModJson).getAsJsonObject();
        List<String> out = new ArrayList<>();
        if (!root.has("mixins") || !root.get("mixins").isJsonArray()) return out;
        for (JsonElement e : root.getAsJsonArray("mixins")) {
            if (e.isJsonPrimitive()) {
                out.add(e.getAsString());
            } else if (e.isJsonObject() && e.getAsJsonObject().has("config")) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("environment") && "client".equals(o.get("environment").getAsString())) continue;
                out.add(o.get("config").getAsString());
            }
        }
        return out;
    }
}
