package dev.phoenium.live;

import dev.phoenium.service.VaultContinuationPin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class MobPortalNativeContractTest {
    @Test void silverfishPrefixMatchesThePinnedEndermiteContinuationBoundary() throws Exception {
        Class<?> type = net.minecraft.world.entity.monster.Silverfish.class;
        assertEquals(type, type.getMethod("tick").getDeclaringClass());
        assertEquals(net.minecraft.world.entity.Mob.class,
            type.getMethod("baseTick").getDeclaringClass());
        assertEquals(net.minecraft.world.entity.LivingEntity.class,
            type.getMethod("canUsePortal", boolean.class).getDeclaringClass());
        assertEquals(net.minecraft.world.entity.Entity.class,
            type.getMethod("teleport", net.minecraft.world.level.portal.TeleportTransition.class).getDeclaringClass());
        var node = new ClassNode();
        try (var in = getClass().getResourceAsStream("/net/minecraft/world/entity/monster/Silverfish.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, ClassReader.EXPAND_FRAMES);
        }
        var tick = node.methods.stream().filter(method -> method.name.equals("tick")
            && method.desc.equals("()V")).findFirst().orElseThrow();
        var instructions = new java.util.ArrayList<AbstractInsnNode>();
        for (var instruction = tick.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext())
            if (instruction.getOpcode() >= 0) instructions.add(instruction);
        int superTick = -1;
        int bodyRotation = -1;
        for (int i = 0; i < instructions.size(); i++) {
            var instruction = instructions.get(i);
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("yBodyRot")) bodyRotation = i;
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("tick") && call.desc.equals("()V")) superTick = i;
        }
        assertTrue(bodyRotation >= 0 && bodyRotation < superTick,
            "native Silverfish body-rotation prefix must precede super.tick");
        assertEquals(Opcodes.RETURN, instructions.get(superTick + 1).getOpcode(),
            "native Silverfish must have no work after its retained super.tick call");
    }
    @Test void endermitePrefixHasNoOuterTickSuffixAfterTheRetainedMobCaller() throws Exception {
        Class<?> type = net.minecraft.world.entity.monster.Endermite.class;
        assertEquals(type, type.getMethod("tick").getDeclaringClass());
        assertEquals(net.minecraft.world.entity.Mob.class,
            type.getMethod("baseTick").getDeclaringClass());
        assertEquals(net.minecraft.world.entity.Entity.class,
            type.getMethod("teleport", net.minecraft.world.level.portal.TeleportTransition.class).getDeclaringClass());
        var node = new ClassNode();
        try (var in = getClass().getResourceAsStream("/net/minecraft/world/entity/monster/Endermite.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, ClassReader.EXPAND_FRAMES);
        }
        var tick = node.methods.stream().filter(method -> method.name.equals("tick")
            && method.desc.equals("()V")).findFirst().orElseThrow();
        var instructions = new java.util.ArrayList<AbstractInsnNode>();
        for (var instruction = tick.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext())
            if (instruction.getOpcode() >= 0) instructions.add(instruction);
        int superTick = -1;
        int bodyRotation = -1;
        for (int i = 0; i < instructions.size(); i++) {
            var instruction = instructions.get(i);
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("yBodyRot")) bodyRotation = i;
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("tick") && call.desc.equals("()V")) superTick = i;
        }
        assertTrue(bodyRotation >= 0 && bodyRotation < superTick,
            "native Endermite body-rotation prefix must precede super.tick");
        assertEquals(Opcodes.RETURN, instructions.get(superTick + 1).getOpcode(),
            "native Endermite must have no work after its retained super.tick call");
    }
    @Test void implicatedEndermanUsesTheSameReviewedMobAndEntityCallers()throws Exception {
        Class<?> type=net.minecraft.world.entity.monster.EnderMan.class;
        assertEquals(net.minecraft.world.entity.Mob.class,type.getMethod("tick").getDeclaringClass());
        assertEquals(net.minecraft.world.entity.Mob.class,type.getMethod("baseTick").getDeclaringClass());
        assertEquals(net.minecraft.world.entity.Entity.class,type.getMethod("teleport",net.minecraft.world.level.portal.TeleportTransition.class).getDeclaringClass());
        assertEquals(type,type.getMethod("aiStep").getDeclaringClass(),"Enderman's native AI remains dynamically dispatched by the retained living suffix");
    }
    @Test void fourNativeSuffixesBindWithoutRewritingTheirInstructions()throws Exception {
        for(String name:java.util.List.of(MobPortalNativeSuffixes.LIVING,MobPortalNativeSuffixes.MOB)) {
            var node=new ClassNode();try(var in=getClass().getResourceAsStream("/"+name+".class")){
                assertNotNull(in);new ClassReader(in).accept(node,ClassReader.EXPAND_FRAMES);
            }
            MobPortalNativeSuffixes.install(node);
            assertEquals(2,node.methods.stream().filter(m->m.name.startsWith("phoenium$resumeMob")).count());
            for(var method:node.methods) if(method.name.startsWith("phoenium$resumeMob"))
                System.out.println("MOB_SUFFIX_PIN "+method.name+" "+VaultContinuationPin.fingerprint(method));
        }
    }
    @Test void cubeFamilyDeclaresItsOwnTickTailAndUsesTheSharedPortalCaller() throws Exception {
        assertTrue(MobPortalNativeAccess.CubeSuffix.class.isAssignableFrom(
            net.minecraft.world.entity.monster.cubemob.AbstractCubeMob.class));
        for (Class<?> type : java.util.List.of(
                net.minecraft.world.entity.monster.cubemob.MagmaCube.class,
                net.minecraft.world.entity.monster.cubemob.Slime.class)) {
            assertEquals(net.minecraft.world.entity.monster.cubemob.AbstractCubeMob.class,type.getMethod("tick").getDeclaringClass());
            assertEquals(net.minecraft.world.entity.Mob.class,type.getMethod("baseTick").getDeclaringClass());
            assertEquals(net.minecraft.world.entity.Entity.class,type.getMethod("teleport",
                net.minecraft.world.level.portal.TeleportTransition.class).getDeclaringClass());
        }
        var node=new ClassNode();
        try(var in=getClass().getResourceAsStream("/"+MobPortalNativeSuffixes.CUBE+".class")) {
            assertNotNull(in);new ClassReader(in).accept(node,ClassReader.EXPAND_FRAMES);
        }
        MobPortalNativeSuffixes.install(node);
        assertEquals(1,node.methods.stream().filter(m->m.name.equals("phoenium$resumeMobCubeTickSuffix")).count());
    }
}
