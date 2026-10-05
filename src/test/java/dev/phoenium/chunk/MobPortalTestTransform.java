package dev.phoenium.chunk;

import dev.phoenium.live.MobPortalNativeSuffixes;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Install the production native tails and the two exact after-super cuts per class. */
public final class MobPortalTestTransform {
    static {
        // Lifted outer tails call the production guard; tests answer it from the retained-transfer hooks.
        dev.phoenium.live.MobPortalGuard.hook = entity -> entity instanceof net.minecraft.world.entity.Entity e && PortalDestinationTestHooks.suspended(e);
    }
    public static boolean outerTail(String name) { return dev.phoenium.live.MobPortalOuterTails.targets().contains(name); }
    /** Frame-computing writer that resolves common supers from class bytes, never by loading a class mid-transform. */
    public static ClassWriter frameWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                if (a.equals(b)) return a;
                var chain = new java.util.HashSet<String>();
                for (String c = a; c != null; c = superOf(c)) chain.add(c);
                for (String c = b; c != null; c = superOf(c)) if (chain.contains(c)) return c;
                return "java/lang/Object";
            }
            private String superOf(String name) {
                if (name.equals("java/lang/Object")) return null;
                try (var in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                    if (in == null) return "java/lang/Object";
                    var reader = new ClassReader(in);
                    return (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "java/lang/Object" : reader.getSuperName();
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            }
        };
    }
    public static boolean supports(String name) { return outerTail(name)||name.equals(MobPortalNativeSuffixes.LIVING)||name.equals(MobPortalNativeSuffixes.MOB)
        ||name.equals(MobPortalNativeSuffixes.CUBE)||name.equals(MobPortalNativeSuffixes.HORSE)
        ||name.equals(MobPortalNativeSuffixes.VILLAGER); }
    public static byte[] transform(String name,byte[] bytes) {
        var node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);
        install(node);
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
    }
    public static void install(ClassNode node) {
        String name=node.name;if(!supports(name))return;
        if(outerTail(name)) {
            dev.phoenium.live.MobPortalOuterTails.install(node);
            // Observe each lifted tail where it runs: owner, receiver and (for the air-supply tails) the captured int.
            for(var method:node.methods) if(method.name.startsWith("phoenium$resumeTail$")) {
                var marker=new InsnList();marker.add(new VarInsnNode(Opcodes.ALOAD,0));marker.add(new LdcInsnNode(name+"."+method.name));
                if(method.desc.equals("(I)V")) marker.add(new VarInsnNode(Opcodes.ILOAD,1)); else marker.add(new InsnNode(Opcodes.ICONST_M1));
                marker.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks","outerTail",
                    "(Ljava/lang/Object;Ljava/lang/String;I)V",false));
                method.instructions.insert(marker);
            }
            return;
        }
        MobPortalNativeSuffixes.install(node);
        if(name.equals(MobPortalNativeSuffixes.CUBE))
            for(var method:node.methods) if(method.name.equals("phoenium$resumeMobCubeTickSuffix")) {
                var marker=new InsnList();marker.add(new VarInsnNode(Opcodes.ALOAD,0));
                marker.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks","cubeTail",
                    "(Lnet/minecraft/world/entity/monster/cubemob/AbstractCubeMob;)V",false));
                method.instructions.insert(marker);
            }
        if(name.equals(MobPortalNativeSuffixes.HORSE))
            for(var method:node.methods) if(method.name.equals("phoenium$resumeMobHorseTickSuffix")) {
                var marker=new InsnList();marker.add(new VarInsnNode(Opcodes.ALOAD,0));
                marker.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks","horseTail",
                    "(Lnet/minecraft/world/entity/animal/equine/AbstractHorse;)V",false));
                method.instructions.insert(marker);
            }
        if(name.equals(MobPortalNativeSuffixes.LIVING))for(var method:node.methods)if(method.name.equals("tickDeath"))
            for(var i:method.instructions.toArray())if(i instanceof MethodInsnNode call&&call.name.equals("remove")&&call.owner.equals(name))
                method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/NativePlayerDeathTestHooks","remove","(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/Entity$RemovalReason;)V",false));
        for(var method:node.methods) if((method.name.equals("tick")||method.name.equals("baseTick"))&&method.desc.equals("()V"))
            for(var i:method.instructions.toArray()) if(i instanceof MethodInsnNode call && call.getOpcode()==Opcodes.INVOKESPECIAL
                    && call.owner.equals(node.superName)&&call.name.equals(method.name)&&call.desc.equals("()V")) {
                var code=new InsnList();var next=new LabelNode();
                code.add(new VarInsnNode(Opcodes.ALOAD,0));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks","suspended","(Lnet/minecraft/world/entity/Entity;)Z",false));
                code.add(new JumpInsnNode(Opcodes.IFEQ,next));code.add(new InsnNode(Opcodes.RETURN));code.add(next);
                code.add(new FrameNode(Opcodes.F_NEW,1,new Object[]{name},0,null));code.add(new InsnNode(Opcodes.NOP));
                method.instructions.insert(call,code);
            }
        if(name.equals(MobPortalNativeSuffixes.MOB)) for(var method:node.methods)
            if(method.name.equals("tick")&&method.desc.equals("()V")) {
                var entry=new InsnList();entry.add(new VarInsnNode(Opcodes.ALOAD,0));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks","beginMob",
                    "(Lnet/minecraft/world/entity/Mob;)V",false));
                method.instructions.insert(entry);
                for(var at:method.instructions.toArray()) if(at.getOpcode()==Opcodes.RETURN) {
                    var exit=new InsnList();exit.add(new VarInsnNode(Opcodes.ALOAD,0));
                    exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks","completeMob",
                        "(Lnet/minecraft/world/entity/Mob;)V",false));
                    exit.add(new VarInsnNode(Opcodes.ALOAD,0));
                    exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks","finishMob",
                        "(Lnet/minecraft/world/entity/Mob;)V",false));
                    method.instructions.insertBefore(at,exit);
                }
            }
    }
}
