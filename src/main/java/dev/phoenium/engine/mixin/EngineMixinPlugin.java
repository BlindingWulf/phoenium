package dev.phoenium.engine.mixin;

import dev.phoenium.engine.EngineConfig;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * S9-E1: phoenium.engine.mixins.json applies only in engine mode ({@code -Dphoenium.engine.tiles=true}). Three provider
 * methods merged by other mods' mixins cannot be wrapped from here without their types, so after the carrier mixin is
 * applied (priority 2000, after Lithium and C2ME) the class is adjusted directly:
 * <ul>
 * <li>{@code ClassInstanceMultiMap}: vanilla {@code find} and Lithium's lazily created group and raw-type lists become
 *     synchronized methods;</li>
 * <li>{@code ServerLevel}: Lithium's active-navigation registration becomes synchronized;</li>
 * <li>{@code MinecraftServer}: C2ME's {@code executeTasksMidTick} returns at once while a colour runs.</li>
 * <li>S9-E3 {@code Level} and {@code ServerLevel}: every read and write of {@code Level.randValue} (vanilla
 *     getBlockRandomPos, Lithium's chunk-random helpers) goes through TileEngine.randValue / setRandValue, so a tile
 *     keeps its own; each read of Lithium's cached random-tick position is passed through TileEngine.tilePos.</li>
 * </ul>
 * Each count is recorded with {@link EngineConfig#noteTransform} and printed on the engine's start line.
 */
public final class EngineMixinPlugin implements IMixinConfigPlugin {
    private static final String PACKAGE = "dev.phoenium.engine.mixin.";
    private static final Set<String> SECTION_MAP_LAZY = Set.of("find", "lithium$getAllOfGroupType",
        "lithium$getOrCreateAllOfTypeRaw", "lithium$replaceCollectionAndGet");
    private static final Set<String> LITHIUM_NAVIGATION = Set.of("lithium$setNavigationActive", "lithium$setNavigationInactive");
    private static final String MID_TICK_DESC = "(Lnet/minecraft/server/level/ServerLevel;)V";
    private static final String ENGINE = "dev/phoenium/engine/TileEngine";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String MUTABLE_POS = "Lnet/minecraft/core/BlockPos$MutableBlockPos;";

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return EngineConfig.ENABLED; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
        switch (mixinClassName) {
            case PACKAGE + "EngineClassInstanceMultiMapMixin" -> synchronize(targetClass, SECTION_MAP_LAZY, "sectionMapSynchronized");
            case PACKAGE + "EngineServerLevelMixin" -> {
                synchronize(targetClass, LITHIUM_NAVIGATION, "navigationSynchronized");
                routeRandValue(targetClass, "serverLevelRandValue");
                tileRandomPos(targetClass);
            }
            case PACKAGE + "EngineLevelMixin" -> routeRandValue(targetClass, "levelRandValue");
            case PACKAGE + "EngineMinecraftServerMixin" -> guardMidTickTasks(targetClass);
            default -> { }
        }
    }

    /** Marks the named instance methods synchronized on their receiver. */
    static int synchronize(ClassNode target, Set<String> names, String label) {
        int count = 0;
        for (MethodNode method : target.methods) {
            if (!names.contains(method.name) || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) continue;
            method.access |= Opcodes.ACC_SYNCHRONIZED;
            count++;
        }
        EngineConfig.noteTransform(label + "=" + count);
        return count;
    }

    /**
     * S9-E3: replaces each GETFIELD / PUTFIELD of {@code randValue:I} on Level or ServerLevel (outside constructors and
     * Phoenium's own accessors) with TileEngine.randValue(Level) / setRandValue(Level, int): same stack effect.
     */
    static int routeRandValue(ClassNode target, String label) {
        int count = 0;
        for (MethodNode method : target.methods) {
            if (method.name.startsWith("<") || method.name.startsWith("phoenium$")) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof FieldInsnNode field) || !field.name.equals("randValue") || !field.desc.equals("I")
                        || !(field.owner.equals(LEVEL) || field.owner.equals(SERVER_LEVEL)))
                    continue;
                MethodInsnNode call;
                if (insn.getOpcode() == Opcodes.GETFIELD) call = new MethodInsnNode(Opcodes.INVOKESTATIC, ENGINE, "randValue", "(L" + LEVEL + ";)I", false);
                else if (insn.getOpcode() == Opcodes.PUTFIELD) call = new MethodInsnNode(Opcodes.INVOKESTATIC, ENGINE, "setRandValue", "(L" + LEVEL + ";I)V", false);
                else continue;
                method.instructions.set(insn, call);
                insn = call;
                count++;
            }
        }
        EngineConfig.noteTransform(label + "=" + count);
        return count;
    }

    /** S9-E3: after each GETFIELD of Lithium's cached random-tick position, {@code TileEngine.tilePos(pos)}. */
    static int tileRandomPos(ClassNode target) {
        int count = 0;
        for (MethodNode method : target.methods) {
            if (method.name.startsWith("<")) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.GETFIELD || !(insn instanceof FieldInsnNode field)
                        || !field.name.endsWith("randomPosInChunkCachedPos") || !field.desc.equals(MUTABLE_POS))
                    continue;
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, ENGINE, "tilePos", "(" + MUTABLE_POS + ")" + MUTABLE_POS, false);
                method.instructions.insert(insn, call);
                insn = call;
                count++;
            }
        }
        EngineConfig.noteTransform("chunkRandomPos=" + count);
        return count;
    }

    /** Prefixes C2ME's executeTasksMidTick(ServerLevel) with {@code if (TileEngine.inColour()) return;}. */
    static int guardMidTickTasks(ClassNode target) {
        int count = 0;
        for (MethodNode method : target.methods) {
            if (!method.name.equals("executeTasksMidTick") || !method.desc.equals(MID_TICK_DESC)
                    || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) continue;
            InsnList guard = new InsnList();
            LabelNode proceed = new LabelNode();
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/engine/TileEngine", "inColour", "()Z", false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(proceed);
            method.instructions.insert(guard);
            count++;
        }
        EngineConfig.noteTransform("midTickGuard=" + count);
        return count;
    }
}
