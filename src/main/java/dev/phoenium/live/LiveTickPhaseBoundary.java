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
        int chunkBoundary = original.instructions.indexOf(haveTime);
        require(original.instructions.indexOf(unique(original, LEVEL, "tickTime", "()V")) < chunkBoundary,
                "world-time prefix moved");
        MethodInsnNode raid = unique(original, "net/minecraft/world/entity/raid/Raids", "tick", "(L" + LEVEL + ";)V");
        require(original.instructions.indexOf(raid) < chunkBoundary,
                "raid prefix moved");
        MethodInsnNode blockEvents = unique(original, LEVEL, "runBlockEvents", "()V");
        require(original.instructions.indexOf(unique(original, LEVEL, "tickBlockEntities", "()V")) > chunkBoundary,
                "block-entity callback moved");
        require(original.instructions.indexOf(blockEvents) > chunkBoundary,
                "block-event callback moved");
        int scheduledStart = boundaryAtConstant(original, "tickPending");
        int scheduledEnd = boundaryAtConstant(original, "raid");
        int cacheEnd = original.instructions.indexOf(tick) + 1;
        int blockEnd = boundaryBeforeFieldWrite(original, LEVEL, "handlingTick", "Z");
        require(scheduledStart < scheduledEnd && scheduledEnd < chunkBoundary && chunkBoundary < cacheEnd
                && cacheEnd < original.instructions.indexOf(blockEvents) && original.instructions.indexOf(blockEvents) < blockEnd,
                "level phase order changed");
        for (int boundary : new int[] { scheduledStart, scheduledEnd, chunkBoundary, cacheEnd, blockEnd })
            checkCut(original, boundary, Set.of(0, 1, 2, 3, 4, 5));

        MethodNode prefix = portion(original, "phoenium$beforeScheduledTicks", "(" + BOOL + ")L" + LEVEL_STATE + ";", 0, scheduledStart);
        prefix.instructions.add(new TypeInsnNode(Opcodes.NEW, LEVEL_STATE)); prefix.instructions.add(new InsnNode(Opcodes.DUP));
        prefix.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int local = 1; local <= 3; local++) prefix.instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
        prefix.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4)); prefix.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        prefix.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LEVEL_STATE, "<init>",
                "(L" + LEVEL + ";" + BOOL + PROFILER + RATE + "ZI)V", false)); prefix.instructions.add(new InsnNode(Opcodes.ARETURN));

        MethodNode scheduled = portion(original, "phoenium$runScheduledTicks", "(L" + LEVEL_STATE + ";)V", scheduledStart, scheduledEnd);
        int scheduledState = original.maxLocals;
        InsnList scheduledLocals = levelLocals(scheduledState, "beginScheduled");
        scheduled.instructions.insert(scheduledLocals);
        phaseEnd(scheduled.instructions, scheduledState, "finishScheduled");
        scheduled.maxLocals = scheduledState + 1;
        retainLocalAcrossFrames(scheduled, scheduledState, LEVEL_STATE);

        MethodNode middle = portion(original, "phoenium$beforeChunkTick", "(L" + LEVEL_STATE + ";)V", scheduledEnd, chunkBoundary);
        int middleState = original.maxLocals, cacheLocal = middleState + 1;
        middle.instructions.insert(levelLocals(middleState, "beginMiddle"));
        middle.instructions.add(new VarInsnNode(Opcodes.ASTORE, cacheLocal));
        middle.instructions.add(new VarInsnNode(Opcodes.ALOAD, middleState));
        middle.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        middle.instructions.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
        middle.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL_STATE, "finishMiddle", "(L" + LEVEL + ";L" + CACHE + ";)V", false));
        middle.instructions.add(new InsnNode(Opcodes.RETURN));
        middle.maxLocals = cacheLocal + 1;
        retainLocalAcrossFrames(middle, middleState, LEVEL_STATE);

        MethodNode chunk = portion(original, "phoenium$afterChunkTick", "(L" + LEVEL_STATE + ";L" + CACHE_STATE + ";)V", chunkBoundary, cacheEnd);
        int chunkState = original.maxLocals, cacheState = chunkState + 1;
        InsnList chunkLocals = new InsnList();
        chunkLocals.add(new VarInsnNode(Opcodes.ALOAD, 2)); chunkLocals.add(new VarInsnNode(Opcodes.ASTORE, cacheState));
        chunkLocals.add(levelLocals(chunkState, "consumeChunk"));
        chunkLocals.add(new VarInsnNode(Opcodes.ALOAD, chunkState));
        chunkLocals.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL_STATE, "cache", "()L" + CACHE + ";", false));
        chunk.instructions.insert(chunkLocals);
        MethodInsnNode copied = unique(chunk, CACHE, "tick", "(" + BOOL + "Z)V");
        chunk.instructions.insertBefore(copied, new VarInsnNode(Opcodes.ALOAD, cacheState));
        chunk.instructions.set(copied, new MethodInsnNode(Opcodes.INVOKESTATIC, PHASES, "finishCache",
                "(L" + CACHE + ";" + BOOL + "ZL" + CACHE_STATE + ";)V", false));
        chunk.instructions.add(new VarInsnNode(Opcodes.ALOAD, chunkState));
        chunk.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        chunk.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL_STATE, "finishChunk", "(L" + LEVEL + ";)V", false));
        chunk.instructions.add(new InsnNode(Opcodes.RETURN));
        chunk.maxLocals = cacheState + 1;

        MethodNode events = portion(original, "phoenium$runBlockEvents", "(L" + LEVEL_STATE + ";)V", cacheEnd, blockEnd);
        int eventState = original.maxLocals;
        events.instructions.insert(levelLocals(eventState, "beginBlockEvents"));
        phaseEnd(events.instructions, eventState, "finishBlockEvents");
        events.maxLocals = eventState + 1;
        retainLocalAcrossFrames(events, eventState, LEVEL_STATE);

        MethodNode finish = portion(original, "phoenium$finishLevelTick", "(L" + LEVEL_STATE + ";)V", blockEnd, original.instructions.size());
        int finishState = original.maxLocals;
        finish.instructions.insert(levelLocals(finishState, "consumeFinal"));
        finish.maxLocals = finishState + 1;

        node.interfaces.add(PHASES + "$LevelAccess");
        node.methods.add(prefix); node.methods.add(scheduled); node.methods.add(middle);
        node.methods.add(chunk); node.methods.add(events); node.methods.add(finish);
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
        MethodInsnNode chunkGameplay = unique(original, CACHE, "tickChunks", "()V");
        MethodInsnNode tracking = unique(original, "net/minecraft/server/level/ChunkMap", "tick", "()V");
        require(original.instructions.indexOf(chunkGameplay) > boundary,
                "chunk gameplay moved into readiness");
        int maintenanceBoundary = boundaryAtConstant(original, "unload");
        require(original.instructions.indexOf(chunkGameplay) < original.instructions.indexOf(tracking)
                && original.instructions.indexOf(tracking) < maintenanceBoundary, "chunk tracking/maintenance order changed");
        checkCut(original, boundary, Set.of(0, 1, 2, 3));
        checkCut(original, maintenanceBoundary, Set.of(0, 1, 2, 3));
        MethodNode before = portion(original, "phoenium$prepareChunkTick", "(" + BOOL + "Z)L" + CACHE_STATE + ";", 0, boundary);
        before.instructions.add(new TypeInsnNode(Opcodes.NEW, CACHE_STATE)); before.instructions.add(new InsnNode(Opcodes.DUP));
        before.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); before.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        before.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2)); before.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        before.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CACHE_STATE, "<init>", "(L" + CACHE + ";" + BOOL + "Z" + PROFILER + ")V", false));
        before.instructions.add(new InsnNode(Opcodes.ARETURN));
        MethodNode after = portion(original, "phoenium$finishChunkTick", "(L" + CACHE_STATE + ";)V", boundary, maintenanceBoundary);
        int state = original.maxLocals;
        after.instructions.insert(cacheLocals(state, "beginGameplay"));
        MethodInsnNode copiedTracking = unique(after, "net/minecraft/server/level/ChunkMap", "tick", "()V");
        AbstractInsnNode trackingField = previous(copiedTracking), trackingReceiver = previous(trackingField);
        require(trackingField instanceof FieldInsnNode field && field.owner.equals(CACHE) && field.name.equals("chunkMap")
                && variable(trackingReceiver, Opcodes.ALOAD, 0), "chunk tracking receiver changed");
        after.instructions.remove(trackingReceiver); after.instructions.remove(trackingField); after.instructions.remove(copiedTracking);
        cachePhaseEnd(after.instructions, state, "finishGameplay");
        after.maxLocals = state + 1;
        retainLocalAcrossFrames(after, state, CACHE_STATE);

        MethodNode maintenance = portion(original, "phoenium$finishChunkMaintenance", "(L" + CACHE_STATE + ";)V",
                maintenanceBoundary, original.instructions.size());
        int maintenanceState = original.maxLocals;
        InsnList maintenanceLocals = cacheLocals(maintenanceState, "beginMaintenance");
        LabelNode noTracking = new LabelNode();
        maintenanceLocals.add(new VarInsnNode(Opcodes.ILOAD, 2));
        maintenanceLocals.add(new JumpInsnNode(Opcodes.IFEQ, noTracking));
        maintenanceLocals.add(new VarInsnNode(Opcodes.ALOAD, 0));
        maintenanceLocals.add(new FieldInsnNode(Opcodes.GETFIELD, CACHE, "chunkMap", "Lnet/minecraft/server/level/ChunkMap;"));
        maintenanceLocals.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/level/ChunkMap", "tick", "()V", false));
        maintenanceLocals.add(noTracking);
        maintenanceLocals.add(new FrameNode(Opcodes.F_NEW, 5,
            new Object[] { CACHE, "java/util/function/BooleanSupplier", Opcodes.INTEGER,
                "net/minecraft/util/profiling/ProfilerFiller", CACHE_STATE }, 0, new Object[] { }));
        maintenance.instructions.insert(maintenanceLocals);
        cachePhaseEnd(maintenance.instructions, maintenanceState, "finishMaintenance");
        maintenance.maxLocals = maintenanceState + 1;
        retainLocalAcrossFrames(maintenance, maintenanceState, CACHE_STATE);
        node.interfaces.add(PHASES + "$CacheAccess"); node.methods.add(before); node.methods.add(after); node.methods.add(maintenance);
    }
    private static MethodNode original(ClassNode node, String descriptor) {
        require(node.methods.stream().noneMatch(method -> method.name.startsWith("phoenium$beforeScheduledTicks")
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
    private static InsnList cacheLocals(int stateLocal, String phaseMethod) {
        InsnList locals = new InsnList();
        locals.add(new VarInsnNode(Opcodes.ALOAD, 1)); locals.add(new VarInsnNode(Opcodes.ASTORE, stateLocal));
        locals.add(new VarInsnNode(Opcodes.ALOAD, stateLocal)); locals.add(new VarInsnNode(Opcodes.ALOAD, 0));
        locals.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CACHE_STATE, phaseMethod, "(L" + CACHE + ";)V", false));
        restore(locals, CACHE_STATE, stateLocal, "haveTime", BOOL, 1, Opcodes.ASTORE);
        restore(locals, CACHE_STATE, stateLocal, "tickChunks", "Z", 2, Opcodes.ISTORE);
        restore(locals, CACHE_STATE, stateLocal, "profiler", PROFILER, 3, Opcodes.ASTORE);
        return locals;
    }
    private static void cachePhaseEnd(InsnList out, int stateLocal, String phaseMethod) {
        removeCopiedReturn(out);
        out.add(new VarInsnNode(Opcodes.ALOAD, stateLocal)); out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CACHE_STATE, phaseMethod, "(L" + CACHE + ";)V", false));
        out.add(new InsnNode(Opcodes.RETURN));
    }
    private static InsnList levelLocals(int stateLocal, String phaseMethod) {
        InsnList locals = new InsnList();
        locals.add(new VarInsnNode(Opcodes.ALOAD, 1)); locals.add(new VarInsnNode(Opcodes.ASTORE, stateLocal));
        locals.add(new VarInsnNode(Opcodes.ALOAD, stateLocal)); locals.add(new VarInsnNode(Opcodes.ALOAD, 0));
        locals.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL_STATE, phaseMethod, "(L" + LEVEL + ";)V", false));
        restore(locals, LEVEL_STATE, stateLocal, "haveTime", BOOL, 1, Opcodes.ASTORE);
        restore(locals, LEVEL_STATE, stateLocal, "profiler", PROFILER, 2, Opcodes.ASTORE);
        restore(locals, LEVEL_STATE, stateLocal, "tickRate", RATE, 3, Opcodes.ASTORE);
        restore(locals, LEVEL_STATE, stateLocal, "runs", "Z", 4, Opcodes.ISTORE);
        restore(locals, LEVEL_STATE, stateLocal, "sleepingPercentage", "I", 5, Opcodes.ISTORE);
        return locals;
    }
    private static void phaseEnd(InsnList out, int stateLocal, String phaseMethod) {
        removeCopiedReturn(out);
        out.add(new VarInsnNode(Opcodes.ALOAD, stateLocal)); out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL_STATE, phaseMethod, "(L" + LEVEL + ";)V", false));
        out.add(new InsnNode(Opcodes.RETURN));
    }
    private static void removeCopiedReturn(InsnList out) {
        AbstractInsnNode tail = out.getLast();
        while (tail != null && tail.getOpcode() < 0) tail = tail.getPrevious();
        if (tail != null && tail.getOpcode() == Opcodes.RETURN) out.remove(tail);
    }
    private static int boundaryAtConstant(MethodNode method, String value) {
        LdcInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof LdcInsnNode ldc && value.equals(ldc.cst)) {
            require(found == null, "duplicate phase marker " + value); found = ldc;
        }
        require(found != null, "missing phase marker " + value);
        return method.instructions.indexOf(previous(found));
    }
    private static int boundaryBeforeFieldWrite(MethodNode method, String owner, String name, String desc) {
        FieldInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.PUTFIELD && field.owner.equals(owner) && field.name.equals(name) && field.desc.equals(desc)
                && previous(field).getOpcode() == Opcodes.ICONST_0) {
            require(found == null, "duplicate field phase marker " + name); found = field;
        }
        require(found != null, "missing field phase marker " + name);
        return method.instructions.indexOf(previous(previous(found)));
    }
    private static void retainLocalAcrossFrames(MethodNode method, int local, String type) {
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof FrameNode frame) {
            if (frame.local == null) frame.local = new ArrayList<>();
            while (frame.local.size() < local) frame.local.add(Opcodes.TOP);
            if (frame.local.size() == local) frame.local.add(type);
            else frame.local.set(local, type);
        }
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
