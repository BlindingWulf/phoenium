package dev.phoenium.c2meprobe;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.*;

public final class C2meProbePlugin implements IMixinConfigPlugin {
    private static final String PROBE = "dev/phoenium/c2meprobe/C2mePathProbe";
    public void onLoad(String ignored) { }
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String target, String mixin) { return true; }
    public void acceptTargets(Set<String> own, Set<String> other) { }
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        for (MethodNode method : node.methods) {
            String name = method.name;
            if (!(name.startsWith("phoenium$prepareChunkTick") || name.startsWith("phoenium$finishChunk")
                    || name.equals("runDistanceManagerUpdates") || name.equals("promoteChunkMap")
                    || name.equals("applyStep") || name.equals("apply") || name.equals("upgradeToThis")
                    || name.equals("downgradeFromThis") || name.equals("save") || name.equals("saveAllChunks")
                    || name.equals("lambda$invokeAsyncLoad$0") || name.equals("lambda$invokeAsyncLoad$2")
                    || name.equals("drainQueue")
                    || name.contains("$c2me-notickvd$lambda$upgradeToThis$1$"))) continue;
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            String key = node.name + "." + name + method.desc;
            method.instructions.insert(mark(key + ".enter"));
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN) {
                    if (name.equals("runDistanceManagerUpdates")) {
                        InsnList result = new InsnList(); result.add(new InsnNode(Opcodes.DUP));
                        result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PROBE, "result", "(Z)V", false));
                        method.instructions.insertBefore(instruction, result);
                    }
                    method.instructions.insertBefore(instruction, mark(key + ".return"));
                }
            }
            method.maxStack += 2;
            System.out.println("C2ME_PATH_OBSERVER " + key);
        }
    }
    private static InsnList mark(String key) {
        InsnList code = new InsnList(); code.add(new LdcInsnNode(key));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PROBE, "hit", "(Ljava/lang/String;)V", false)); return code;
    }
}
