package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Stage 11 (2026-10-18): the gate must see a mod's entity mixins from the mod's own files. s11gate-graves-D43G-1 ran
 * PARALLEL with universal-graves installed (its LivingEntityMixin was missed) because Mixins.getConfigs() is the pending
 * set, which MixinProcessor.selectConfigs empties before the server starts; the server then crashed in the graves model
 * tick exactly as on plain D43.
 */
class MixinTargetsTest {
    private static byte[] mixinClass(String name, Type[] value, String[] targets) {
        return mixinClass(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, value, targets);
    }

    private static byte[] mixinClass(int access, String name, Type[] value, String[] targets) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, access, name, null, "java/lang/Object", null);
        if (value != null || targets != null) {
            AnnotationVisitor av = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
            if (value != null) {
                AnnotationVisitor arr = av.visitArray("value");
                for (Type t : value) arr.visit(null, t);
                arr.visitEnd();
            }
            if (targets != null) {
                AnnotationVisitor arr = av.visitArray("targets");
                for (String t : targets) arr.visit(null, t);
                arr.visitEnd();
            }
            av.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void readsClassValueTargets() {
        byte[] b = mixinClass("eu/pb4/graves/mixin/LivingEntityMixin",
            new Type[] {Type.getObjectType("net/minecraft/world/entity/LivingEntity")}, null);
        assertEquals(List.of("net.minecraft.world.entity.LivingEntity"), MixinTargets.targets(b));
    }

    @Test
    void readsStringTargetsInEitherForm() {
        byte[] b = mixinClass("x/M", null, new String[] {"net.minecraft.server.level.ServerPlayer", "net/minecraft/world/entity/Mob$1"});
        assertEquals(List.of("net.minecraft.server.level.ServerPlayer", "net.minecraft.world.entity.Mob$1"), MixinTargets.targets(b));
    }

    @Test
    void readsBothValueAndTargets() {
        byte[] b = mixinClass("x/M", new Type[] {Type.getObjectType("net/minecraft/world/entity/Entity")},
            new String[] {"net.minecraft.world.level.Level"});
        assertEquals(List.of("net.minecraft.world.entity.Entity", "net.minecraft.world.level.Level"), MixinTargets.targets(b));
    }

    @Test
    void classWithoutMixinAnnotationHasNoTargets() {
        assertEquals(List.of(), MixinTargets.targets(mixinClass("x/Plain", null, null)));
    }

    @Test
    void accessorInterfaceMixinsAddNoCallbacks() {
        // e.g. graves' LivingEntityAccessor: an interface mixin can only expose fields/methods (@Accessor/@Invoker)
        byte[] accessor = mixinClass(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, "eu/pb4/graves/mixin/LivingEntityAccessor",
            new Type[] {Type.getObjectType("net/minecraft/world/entity/LivingEntity")}, null);
        assertEquals(List.of("net.minecraft.world.entity.LivingEntity"), MixinTargets.targets(accessor));
        assertEquals(List.of(), MixinTargets.hookTargets(accessor));
    }

    @Test
    void classMixinsAreHooks() {
        byte[] b = mixinClass("eu/pb4/graves/mixin/LivingEntityMixin",
            new Type[] {Type.getObjectType("net/minecraft/world/entity/LivingEntity")}, null);
        assertEquals(List.of("net.minecraft.world.entity.LivingEntity"), MixinTargets.hookTargets(b));
    }

    @Test
    void configListsCommonAndServerMixinsButNotClient() {
        String json = "{\"required\":true,\"package\":\"eu.pb4.graves.mixin\",\"mixins\":[\"LivingEntityMixin\",\"sub.PlayerMixin\"],"
            + "\"server\":[\"ServerOnlyMixin\"],\"client\":[\"ClientOnlyMixin\"]}";
        assertEquals(List.of("eu.pb4.graves.mixin.LivingEntityMixin", "eu.pb4.graves.mixin.sub.PlayerMixin",
            "eu.pb4.graves.mixin.ServerOnlyMixin"), MixinTargets.configClasses(json));
    }

    @Test
    void configWithoutListsHasNoClasses() {
        assertEquals(List.of(), MixinTargets.configClasses("{\"package\":\"a.b\"}"));
    }

    @Test
    void configWithoutPackageUsesBareNames() {
        assertEquals(List.of("M"), MixinTargets.configClasses("{\"mixins\":[\"M\"]}"));
    }

    @Test
    void fabricModJsonMixinEntriesSkipClientOnlyConfigs() {
        String fmj = "{\"id\":\"graves\",\"mixins\":[\"universal-graves.mixins.json\","
            + "{\"config\":\"graves.client.mixins.json\",\"environment\":\"client\"},"
            + "{\"config\":\"graves.server.mixins.json\",\"environment\":\"server\"},"
            + "{\"config\":\"graves.any.mixins.json\",\"environment\":\"*\"}]}";
        assertEquals(List.of("universal-graves.mixins.json", "graves.server.mixins.json", "graves.any.mixins.json"),
            MixinTargets.modConfigs(fmj));
    }

    @Test
    void fabricModJsonWithoutMixinsHasNoConfigs() {
        assertEquals(List.of(), MixinTargets.modConfigs("{\"id\":\"polymer-bundled\"}"));
    }
}
