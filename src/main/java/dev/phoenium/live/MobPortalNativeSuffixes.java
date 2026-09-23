package dev.phoenium.live;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Bounded extraction of precisely seven native void suffixes. Keeping the original
 * instructions in their declaring class preserves private calls, dispatch, RNG and
 * every native branch. This is not an arbitrary stack/local continuation engine.
 * The reviewed cuts have only `this` live; a changed native shape fails binding. */
public final class MobPortalNativeSuffixes {
    public static final String LIVING="net/minecraft/world/entity/LivingEntity";
    public static final String MOB="net/minecraft/world/entity/Mob";
    public static final String CUBE="net/minecraft/world/entity/monster/cubemob/AbstractCubeMob";
    public static final String HORSE="net/minecraft/world/entity/animal/equine/AbstractHorse";
    public static final String VILLAGER="net/minecraft/world/entity/npc/villager/Villager";
    private MobPortalNativeSuffixes() { }
    public static void install(ClassNode node) {
        if (!node.name.equals(LIVING) && !node.name.equals(MOB) && !node.name.equals(CUBE)
                && !node.name.equals(HORSE) && !node.name.equals(VILLAGER)) throw new IllegalArgumentException(node.name);
        if (node.name.equals(CUBE)) {
            node.interfaces.add("dev/phoenium/live/MobPortalNativeAccess$CubeSuffix");
            copy(node,"tick","phoenium$resumeMobCubeTickSuffix");
            return;
        }
        if (node.name.equals(HORSE)) {
            node.interfaces.add("dev/phoenium/live/MobPortalNativeAccess$HorseSuffix");
            copy(node,"tick","phoenium$resumeMobHorseTickSuffix");
            return;
        }
        if (node.name.equals(VILLAGER)) {
            node.interfaces.add("dev/phoenium/live/MobPortalNativeAccess$VillagerSuffix");
            copy(node,"tick","phoenium$resumeMobVillagerTickSuffix");
            return;
        }
        boolean living=node.name.equals(LIVING);
        node.interfaces.add("dev/phoenium/live/MobPortalNativeAccess$"+(living ? "LivingSuffix" : "MobSuffix"));
        copy(node,"baseTick",living ? "phoenium$resumeMobLivingBaseSuffix" : "phoenium$resumeMobBaseSuffix");
        copy(node,"tick",living ? "phoenium$resumeMobLivingTickSuffix" : "phoenium$resumeMobTickSuffix");
    }
    private static void copy(ClassNode node,String name,String resume) {
        var methods=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals("()V")).toList();
        if(methods.size()!=1 || node.methods.stream().anyMatch(m->m.name.equals(resume)))
            throw new IllegalStateException("changed mob suffix method: "+node.name+"."+name);
        var nativeMethod=methods.getFirst();
        var cuts=new ArrayList<MethodInsnNode>();
        for(var instruction:nativeMethod.instructions)
            if(instruction instanceof MethodInsnNode call && call.getOpcode()==Opcodes.INVOKESPECIAL
                    && call.owner.equals(node.superName)&&call.name.equals(name)&&call.desc.equals("()V")) cuts.add(call);
        if(cuts.size()!=1 || !nativeMethod.tryCatchBlocks.isEmpty())
            throw new IllegalStateException("changed mob super boundary: "+node.name+"."+name);
        var cut=cuts.getFirst();
        var retained=Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode,Boolean>());
        for(var i=cut.getNext();i!=null;i=i.getNext()) retained.add(i);
        // No jumps/handlers may connect the extracted suffix to its native prefix.
        for(var i:nativeMethod.instructions) {
            if(i instanceof JumpInsnNode jump && retained.contains(i)!=retained.contains(jump.label))
                throw new IllegalStateException("mob boundary gained a crossing branch");
            if(i instanceof TableSwitchInsnNode || i instanceof LookupSwitchInsnNode)
                throw new IllegalStateException("unreviewed mob suffix switch");
        }
        var labels=new IdentityHashMap<LabelNode,LabelNode>();
        for(var i:nativeMethod.instructions) if(i instanceof LabelNode l) labels.put(l,new LabelNode());
        var suffix=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNTHETIC,resume,"()V",null,null);
        for(var i=cut.getNext();i!=null;i=i.getNext()) {
            if(i instanceof LineNumberNode) continue;
            suffix.instructions.add(i.clone(labels));
        }
        suffix.maxLocals=nativeMethod.maxLocals;suffix.maxStack=nativeMethod.maxStack;
        String expected=switch(resume) {
            case "phoenium$resumeMobLivingBaseSuffix" -> "d11277f0f75fb44ff915d8b42c1f9427cf5fa3bac6397dd3fa37a77c3e6622f9";
            case "phoenium$resumeMobLivingTickSuffix" -> "6903ac04c2ccf5c9ecf257279a07dd9340698e8c2f36cc08e753b322f88baae4";
            case "phoenium$resumeMobBaseSuffix" -> "c432d27117ab21139b6327c78d1a2ffbe3644314723890743de6fd9d6484be2a";
            case "phoenium$resumeMobTickSuffix" -> "d67927a767f718868dbaea5c231974b4d85cf2f53b3939b28d1ebb9d47c41b13";
            case "phoenium$resumeMobCubeTickSuffix" -> "b3c50458cfec594f75422b7e48cd83c09461e1f533d975b7675e6e2bd4fd2c3d";
            case "phoenium$resumeMobHorseTickSuffix" -> "286c6749f259c5eae4808b373d8e9de2e2fdb44eca0f7806ff75d0b1cbe2dd57";
            case "phoenium$resumeMobVillagerTickSuffix" -> "94a0ab11728eefa7a7ad02d49776dd2c83cf23993e06337df411f7839ab107da";
            default -> throw new IllegalStateException("unreviewed mob suffix");
        };
        String actual=dev.phoenium.service.VaultContinuationPin.fingerprint(suffix);
        if(!expected.equals(actual))throw new IllegalStateException("Minecraft 26.2 mob suffix changed: "+resume+" "+actual);
        node.methods.add(suffix);
    }
}
