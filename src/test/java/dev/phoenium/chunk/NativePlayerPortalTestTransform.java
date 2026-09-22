package dev.phoenium.chunk;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Test binding of the real ServerPlayer.teleport prefix and its real native suffix.
 * Networking and world storage remain explicitly controlled environment endpoints. */
final class NativePlayerPortalTestTransform {
    static final String PLAYER="net/minecraft/server/level/ServerPlayer", LEVEL="net/minecraft/server/level/ServerLevel";
    static final String TRANSITION="net/minecraft/world/level/portal/TeleportTransition";
    static final String PEARL="net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl";
    static final String HOOK="dev/phoenium/chunk/NativePlayerPortalTestHooks";
    static void install(ClassNode node) {
        if(node.name.equals(PLAYER)) player(node);
        if(node.name.equals(PEARL)) pearl(node);
    }
    private static void player(ClassNode node) {
        var original=node.methods.stream().filter(m->m.name.equals("teleport") && m.desc.endsWith("ServerPlayer;")).findFirst().orElseThrow();
        MethodInsnNode cut=null;
        for(var insn:original.instructions) if(insn instanceof MethodInsnNode call && call.name.equals("removePlayerImmediately")) cut=call;
        Objects.requireNonNull(cut);
        var suffix=new MethodNode(Opcodes.ACC_PUBLIC,"phoenium$resumePlayerDimensionChange","(L"+TRANSITION+";L"+LEVEL+";)V",null,null);
        // Restore precisely the native locals live at the source-removal return edge.
        suffix.instructions.add(new VarInsnNode(Opcodes.ALOAD,2)); suffix.instructions.add(new VarInsnNode(Opcodes.ASTORE,3));
        suffix.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));
        suffix.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,TRANSITION,"newLevel","()L"+LEVEL+";",false)); suffix.instructions.add(new VarInsnNode(Opcodes.ASTORE,2));
        suffix.instructions.add(new VarInsnNode(Opcodes.ALOAD,3));
        suffix.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LEVEL,"dimension","()Lnet/minecraft/resources/ResourceKey;",false)); suffix.instructions.add(new VarInsnNode(Opcodes.ASTORE,4));
        suffix.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));
        suffix.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LEVEL,"getLevelData","()Lnet/minecraft/world/level/storage/LevelData;",false)); suffix.instructions.add(new VarInsnNode(Opcodes.ASTORE,5));
        suffix.instructions.add(new VarInsnNode(Opcodes.ALOAD,0)); suffix.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,PLAYER,"server","Lnet/minecraft/server/MinecraftServer;"));
        suffix.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/server/MinecraftServer","getPlayerList","()Lnet/minecraft/server/players/PlayerList;",false)); suffix.instructions.add(new VarInsnNode(Opcodes.ASTORE,6));
        Map<LabelNode,LabelNode> labels=new HashMap<>(); for(var insn:original.instructions) if(insn instanceof LabelNode label) labels.put(label,new LabelNode());
        for(var insn=cut.getNext();insn!=null;insn=insn.getNext()) {
            if(insn instanceof LineNumberNode) continue;
            if(insn.getOpcode()==Opcodes.ARETURN) { suffix.instructions.add(new InsnNode(Opcodes.POP)); suffix.instructions.add(new InsnNode(Opcodes.RETURN)); }
            else suffix.instructions.add(insn.clone(labels));
        }
        for(var insn:suffix.instructions.toArray()) if(insn instanceof MethodInsnNode call && call.name.equals("triggerDimensionChangeTriggers"))
            suffix.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"trigger","(L"+PLAYER+";L"+LEVEL+";)V",false));
        node.interfaces.add("dev/phoenium/live/PlayerDimensionTransitionNativeAccess$PlayerSuffix"); node.methods.add(suffix);
        InsnList suspend=new InsnList(); LabelNode next=new LabelNode();
        suspend.add(new VarInsnNode(Opcodes.ALOAD,0)); suspend.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"suspend","(L"+PLAYER+";)Z",false));
        suspend.add(new JumpInsnNode(Opcodes.IFEQ,next)); suspend.add(new VarInsnNode(Opcodes.ALOAD,0)); suspend.add(new InsnNode(Opcodes.ARETURN)); suspend.add(next);
        suspend.add(new FrameNode(Opcodes.F_NEW,7,new Object[]{PLAYER,TRANSITION,LEVEL,LEVEL,"net/minecraft/resources/ResourceKey","net/minecraft/world/level/storage/LevelData","net/minecraft/server/players/PlayerList"},0,null)); suspend.add(new InsnNode(Opcodes.NOP));
        original.instructions.insert(cut,suspend); original.name="phoenium$testNativePlayerTeleport";
        var wrapper=new MethodNode(Opcodes.ACC_PUBLIC,"teleport",original.desc,null,null);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD,0)); wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"teleport","(L"+PLAYER+";L"+TRANSITION+";)L"+PLAYER+";",false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(wrapper);
    }
    private static void pearl(ClassNode node) {
        var hit=node.methods.stream().filter(m->m.name.equals("onHit")).findFirst().orElseThrow();
        for(var insn:hit.instructions.toArray()) if(insn instanceof MethodInsnNode call) {
            if(call.name.equals("getOwner")) hit.instructions.insert(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"owner","(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/entity/Entity;",false));
            if(call.name.equals("isOnPortalCooldown")) {
                var code=new InsnList(); var next=new LabelNode();
                // The receiver already on stack is consumed by capture; restore it only if native work continues.
                code.add(new VarInsnNode(Opcodes.ALOAD,2)); code.add(new VarInsnNode(Opcodes.ALOAD,4)); code.add(new VarInsnNode(Opcodes.ALOAD,5));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"capture","(L"+PEARL+";L"+LEVEL+";Lnet/minecraft/world/phys/Vec3;L"+PLAYER+";)Z",false));
                code.add(new JumpInsnNode(Opcodes.IFEQ,next)); code.add(new InsnNode(Opcodes.RETURN)); code.add(next);
                code.add(new FrameNode(Opcodes.F_NEW,6,new Object[]{PEARL,"net/minecraft/world/phys/HitResult",LEVEL,"net/minecraft/world/entity/Entity","net/minecraft/world/phys/Vec3",PLAYER},0,null));
                code.add(new VarInsnNode(Opcodes.ALOAD,0)); hit.instructions.insertBefore(call,code);
            }
        }
        node.interfaces.add("dev/phoenium/live/PlayerDimensionTransitionNativeAccess$PearlHitSuffix");
        delegate(node,"phoenium$resumePearlPlayerHit","(L"+PLAYER+";L"+LEVEL+";Lnet/minecraft/world/phys/Vec3;)V","hitSuffix");
        delegate(node,"phoenium$resumePearlPlayerDamage","(L"+PLAYER+";)V","damage");
        delegate(node,"phoenium$resumePearlHitEffects","(L"+LEVEL+";Lnet/minecraft/world/phys/Vec3;)V","effects");
    }
    private static void delegate(ClassNode node,String name,String descriptor,String target) {
        var method=new MethodNode(Opcodes.ACC_PUBLIC,name,descriptor,null,null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0)); int slot=1;
        for(var type:Type.getArgumentTypes(descriptor)) method.instructions.add(new VarInsnNode(Opcodes.ALOAD,slot++));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,target,descriptor.replace("(","(L"+PEARL+";"),false));
        method.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(method);
    }
}
