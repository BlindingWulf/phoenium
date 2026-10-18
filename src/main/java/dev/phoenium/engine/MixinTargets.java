package dev.phoenium.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
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

    private static final String INJECTION = "Lorg/spongepowered/asm/mixin/injection/";
    private static final Set<String> INJECTORS = Set.of("Inject;", "Redirect;", "ModifyArg;", "ModifyArgs;",
        "ModifyVariable;", "ModifyConstant;");
    private static final String MIXINEXTRAS_INJECTOR = "Lcom/llamalad7/mixinextras/injector/";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    /** Annotations that mark a mixin method as neither a hook nor merged into the target. */
    private static final Set<String> NOT_MERGED = Set.of("Lorg/spongepowered/asm/mixin/Shadow;",
        "Lorg/spongepowered/asm/mixin/Unique;");

    /**
     * Stage 11 gate v2: the names of the target methods a mixin class injects into or replaces, or {@code null} when that
     * cannot be read (an injector without a plain {@code method} selector, a wildcard, a regex or a {@code @Desc} target).
     * Counted: every injector ({@code @Inject}, {@code @Redirect}, {@code @Modify*} and MixinExtras' injectors) by its
     * selectors, {@code @Overwrite} by its own name, and every other non-private, non-static, non-synthetic method by its
     * own name, since Mixin merges it into the target where it replaces or overrides a vanilla method of that name.
     * {@code @Shadow} and {@code @Unique} methods are skipped, as are names with a {@code $} (Mixin's prefix for a mod's
     * own duck methods). An interface mixin injects nothing.
     */
    static List<String> injectedMethods(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) return List.of();
        List<String> out = new ArrayList<>();
        boolean[] unknown = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                List<String> annotations = new ArrayList<>();
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        annotations.add(desc);
                        boolean injector = desc.startsWith(MIXINEXTRAS_INJECTOR)
                            || (desc.startsWith(INJECTION) && INJECTORS.contains(desc.substring(INJECTION.length())));
                        if (!injector) return null;
                        List<String> selectors = new ArrayList<>();
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String element) {
                                if (!"method".equals(element)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String unused, Object value) {
                                        if (value instanceof String s) selectors.add(s);
                                    }
                                };
                            }

                            @Override
                            public void visit(String element, Object value) {
                                if ("method".equals(element) && value instanceof String s) selectors.add(s);
                            }

                            @Override
                            public void visitEnd() {
                                if (selectors.isEmpty()) unknown[0] = true;
                                for (String s : selectors) {
                                    String m = selectorName(s);
                                    if (m == null) unknown[0] = true;
                                    else out.add(m);
                                }
                            }
                        };
                    }

                    @Override
                    public void visitEnd() {
                        boolean injector = annotations.stream().anyMatch(d -> d.startsWith(MIXINEXTRAS_INJECTOR)
                            || (d.startsWith(INJECTION) && INJECTORS.contains(d.substring(INJECTION.length()))));
                        if (injector) return;
                        if (annotations.contains(OVERWRITE)) {
                            out.add(name);
                            return;
                        }
                        if (annotations.stream().anyMatch(NOT_MERGED::contains)) return;
                        if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) return;
                        if (name.equals("<init>") || name.equals("<clinit>") || name.indexOf('$') >= 0) return;
                        out.add(name);
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return unknown[0] ? null : out;
    }

    /** The method name in a selector ({@code name}, {@code name(desc)}, {@code Lowner;name(desc)}), or null if not plain. */
    static String selectorName(String selector) {
        String s = selector.trim();
        if (s.isEmpty() || s.startsWith("/") || s.startsWith("@") || s.indexOf('*') >= 0) return null;
        int paren = s.indexOf('(');
        if (paren >= 0) s = s.substring(0, paren);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        int semi = s.lastIndexOf(';');
        if (semi >= 0) s = s.substring(semi + 1);
        return s.isEmpty() ? null : s;
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
