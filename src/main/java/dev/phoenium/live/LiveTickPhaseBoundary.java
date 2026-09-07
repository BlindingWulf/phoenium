package dev.phoenium.live;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Pinned semantic cuts retain both original tick methods and every copied callback in order. */
public final class LiveTickPhaseBoundary {
    public static final String LEVEL = "net/minecraft/server/level/ServerLevel";
    public static final String CACHE = "net/minecraft/server/level/ServerChunkCache";
    public static final String PHASES = "dev/phoenium/live/LiveTickPhases";
    public static final String LEVEL_STATE = PHASES + "$LevelState";
    public static final String CACHE_STATE = PHASES + "$CacheState";
    private static final String BOOL = "Ljava/util/function/BooleanSupplier;";
    private static final String PROFILER = "Lnet/minecraft/util/profiling/ProfilerFiller;";
    private static final String RATE = "Lnet/minecraft/world/TickRateManager;";
    private LiveTickPhaseBoundary() { }

    /** Installed Fabric events remain at the original world-tick ends, once per full tick. */
    public static void requireInstalledLevelCallbacks(ClassNode node) {
        MethodNode method = original(node, "(" + BOOL + ")V");
        int cut = method.instructions.indexOf(unique(method, CACHE, "tick", "(" + BOOL + "Z)V"));
        int starts = 0, ends = 0;
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals(LEVEL) && call.name.startsWith("handler$")) {
            require(call.desc.equals("(" + BOOL + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"), "tick event descriptor changed");
            if (call.name.endsWith("$fabric-lifecycle-events-v1$startLevelTick")) {
                require(method.instructions.indexOf(call) < cut, "start-level event moved after readiness"); starts++;
            } else if (call.name.endsWith("$fabric-lifecycle-events-v1$endLevelTick")) {
                require(method.instructions.indexOf(call) > cut, "end-level event moved before readiness"); ends++;
            } else throw drift("unreviewed tick callback " + call.name);
        }
        require(starts == 1 && ends == 1, "installed level-tick event inventory changed");
    }

    public static void installLevel(ClassNode node) {
        require(node.name.equals(LEVEL), "wrong level target");
        MethodNode original = original(node, "(" + BOOL + ")V");
        MethodInsnNode tick = unique(original, CACHE, "tick", "(" + BOOL + "Z)V");
        AbstractInsnNode one = previous(tick), haveTime = previous(one), getter = previous(haveTime), cut = previous(getter);
        require(one.getOpcode() == Opcodes.ICONST_1 && variable(haveTime, Opcodes.ALOAD, 1)
                && getter instanceof MethodInsnNode get && get.owner.equals(LEVEL) && get.name.equals("getChunkSource")
                && get.desc.equals("()L" + CACHE + ";") && variable(cut, Opcodes.ALOAD, 0), "level chunk-tick invocation changed");
        int boundary = original.instructions.indexOf(haveTime);
        require(original.instructions.indexOf(unique(original, LEVEL, "tickTime", "()V")) < boundary,
                "world-time prefix moved");
        require(original.instructions.indexOf(unique(original, "net/minecraft/world/entity/raid/Raids", "tick", "(L" + LEVEL + ";)V")) < boundary,
                "raid prefix moved");
        require(original.instructions.indexOf(unique(original, LEVEL, "tickBlockEntities", "()V")) > boundary,
                "block-entity callback moved");
        require(original.instructions.indexOf(unique(original, LEVEL, "runBlockEvents", "()V")) > boundary,
                "block-event callback moved");
        checkCut(original, boundary, Set.of(0, 1, 2, 3, 4, 5));
        MethodNode before = portion(original, "phoenium$beforeChunkTick", "(" + BOOL + ")L" + LEVEL_STATE + ";", 0, boundary);
        // The original getChunkSource receiver lookup also belongs to the prefix; retain the
        // one returned cache instead of invoking it again when the continuation resumes.
        before.instructions.add(new VarInsnNode(Opcodes.ASTORE, original.maxLocals));
        before.instructions.add(new TypeInsnNode(Opcodes.NEW, LEVEL_STATE)); before.instructions.add(new InsnNode(Opcodes.DUP));
        before.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        before.instructions.add(new VarInsnNode(Opcodes.ALOAD, original.maxLocals));
        for (int local = 1; local <= 3; local++) before.instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
        before.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4)); before.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        before.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LEVEL_STATE, "<init>",
                "(L" + LEVEL + ";L" + CACHE + ";" + BOOL + PROFILER + RATE + "ZI)V", false)); before.instructions.add(new InsnNode(Opcodes.ARETURN));
        before.maxLocals = original.maxLocals + 1;
        MethodNode after = portion(original, "phoenium$afterChunkTick", "(L" + LEVEL_STATE + ";L" + CACHE_STATE + ";)V",
                boundary, original.instructions.size());
        int levelLocal = original.maxLocals, cacheLocal = levelLocal + 1;
        InsnList locals = new InsnList();
        locals.add(new VarInsnNode(Opcodes.ALOAD, 1)); locals.add(new VarInsnNode(Opcodes.ASTORE, levelLocal));
        locals.add(new VarInsnNode(Opcodes.ALOAD, 2)); locals.add(new VarInsnNode(Opcodes.ASTORE, cacheLocal));
        consume(locals, LEVEL_STATE, LEVEL, levelLocal);
        restore(locals, LEVEL_STATE, levelLocal, "haveTime", BOOL, 1, Opcodes.ASTORE);
        restore(locals, LEVEL_STATE, levelLocal, "profiler", PROFILER, 2, Opcodes.ASTORE);
        restore(locals, LEVEL_STATE, levelLocal, "tickRate", RATE, 3, Opcodes.ASTORE);
        restore(locals, LEVEL_STATE, levelLocal, "runs", "Z", 4, Opcodes.ISTORE);
        restore(locals, LEVEL_STATE, levelLocal, "sleepingPercentage", "I", 5, Opcodes.ISTORE);
        locals.add(new VarInsnNode(Opcodes.ALOAD, levelLocal));
        locals.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL_STATE, "cache", "()L" + CACHE + ";", false));
        after.instructions.insert(locals);
        MethodInsnNode copied = unique(after, CACHE, "tick", "(" + BOOL + "Z)V");
        after.instructions.insertBefore(copied, new VarInsnNode(Opcodes.ALOAD, cacheLocal));
        after.instructions.set(copied, new MethodInsnNode(Opcodes.INVOKESTATIC, PHASES, "finishCache",
                "(L" + CACHE + ";" + BOOL + "ZL" + CACHE_STATE + ";)V", false));
        after.maxLocals = cacheLocal + 1;
        node.interfaces.add(PHASES + "$LevelAccess"); node.methods.add(before); node.methods.add(after);
    }

    public static void installCache(ClassNode node) {
        require(node.name.equals(CACHE), "wrong cache target");
        MethodNode original = original(node, "(" + BOOL + "Z)V");
        MethodInsnNode update = unique(original, CACHE, "runDistanceManagerUpdates", "()Z");
        AbstractInsnNode pop = next(update);
        require(pop.getOpcode() == Opcodes.POP, "distance-update result consumption changed");
        int boundary = original.instructions.indexOf(pop) + 1;
        require(original.instructions.indexOf(unique(original, "net/minecraft/world/level/TicketStorage", "purgeStaleTickets", "(Lnet/minecraft/server/level/ChunkMap;)V")) < boundary,
                "ticket-purge prefix moved");
        require(original.instructions.indexOf(unique(original, CACHE, "tickChunks", "()V")) > boundary,
                "chunk gameplay moved into readiness");
        checkCut(original, boundary, Set.of(0, 1, 2, 3));
        MethodNode before = portion(original, "phoenium$prepareChunkTick", "(" + BOOL + "Z)L" + CACHE_STATE + ";", 0, boundary);
        before.instructions.add(new TypeInsnNode(Opcodes.NEW, CACHE_STATE)); before.instructions.add(new InsnNode(Opcodes.DUP));
        before.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); before.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        before.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2)); before.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        before.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CACHE_STATE, "<init>", "(L" + CACHE + ";" + BOOL + "Z" + PROFILER + ")V", false));
        before.instructions.add(new InsnNode(Opcodes.ARETURN));
        MethodNode after = portion(original, "phoenium$finishChunkTick", "(L" + CACHE_STATE + ";)V", boundary, original.instructions.size());
        int state = original.maxLocals;
        InsnList locals = new InsnList(); locals.add(new VarInsnNode(Opcodes.ALOAD, 1)); locals.add(new VarInsnNode(Opcodes.ASTORE, state));
        consume(locals, CACHE_STATE, CACHE, state);
        restore(locals, CACHE_STATE, state, "haveTime", BOOL, 1, Opcodes.ASTORE);
        restore(locals, CACHE_STATE, state, "tickChunks", "Z", 2, Opcodes.ISTORE);
        restore(locals, CACHE_STATE, state, "profiler", PROFILER, 3, Opcodes.ASTORE);
        after.instructions.insert(locals); after.maxLocals = state + 1;
        node.interfaces.add(PHASES + "$CacheAccess"); node.methods.add(before); node.methods.add(after);
    }
    private static MethodNode original(ClassNode node, String descriptor) {
        require(node.methods.stream().noneMatch(method -> method.name.startsWith("phoenium$beforeChunkTick")
                || method.name.startsWith("phoenium$prepareChunkTick")), "adapter already installed");
        return node.methods.stream().filter(method -> method.name.equals("tick") && method.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> drift("missing original tick"));
    }
    private static void checkCut(MethodNode method, int boundary, Set<Integer> carried) {
        require(method.tryCatchBlocks.isEmpty(), "tick exception boundary needs a new audit");
        Set<Integer> initialized = new HashSet<>(carried);
        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (instruction instanceof JumpInsnNode jump)
                require((i < boundary) == (method.instructions.indexOf(jump.label) < boundary), "control flow crosses tick cut");
            require(!(instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode), "tick switch needs a new audit");
            if (i >= boundary && instruction instanceof VarInsnNode var) {
                if (var.getOpcode() >= Opcodes.ISTORE && var.getOpcode() <= Opcodes.ASTORE) initialized.add(var.var);
                else require(initialized.contains(var.var), "suffix uses an unretained prefix local " + var.var);
            }
        }
    }
    private static MethodNode portion(MethodNode source, String name, String descriptor, int from, int to) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, descriptor, null, null);
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode instruction : source.instructions) if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
        for (int i = from; i < to; i++) {
            AbstractInsnNode instruction = source.instructions.get(i);
            if (!(instruction instanceof LineNumberNode)) method.instructions.add(instruction.clone(labels));
        }
        method.maxLocals = source.maxLocals; method.maxStack = Math.max(source.maxStack, 10);
        return method;
    }
    private static void consume(InsnList out, String state, String target, int local) {
        out.add(new VarInsnNode(Opcodes.ALOAD, local)); out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, state, "consume", "(L" + target + ";)V", false));
    }
    private static void restore(InsnList out, String state, int local, String name, String type, int target, int opcode) {
        out.add(new VarInsnNode(Opcodes.ALOAD, local)); out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, state, name, "()" + type, false));
        out.add(new VarInsnNode(opcode, target));
    }
    private static MethodInsnNode unique(MethodNode method, String owner, String name, String desc) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) {
            require(found == null, "duplicate " + owner + "." + name); found = call;
        }
        require(found != null, "missing " + owner + "." + name); return found;
    }
    private static boolean variable(AbstractInsnNode node, int opcode, int local) { return node instanceof VarInsnNode var && var.getOpcode() == opcode && var.var == local; }
    private static AbstractInsnNode previous(AbstractInsnNode node) { do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0); return node; }
    private static AbstractInsnNode next(AbstractInsnNode node) { do { node = node.getNext(); } while (node != null && node.getOpcode() < 0); return node; }
    private static void require(boolean valid, String detail) { if (!valid) throw drift(detail); }
    private static IllegalStateException drift(String detail) { return new IllegalStateException("Pinned tick phase drift: " + detail); }
}
