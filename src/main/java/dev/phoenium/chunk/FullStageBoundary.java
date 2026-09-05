package dev.phoenium.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * One pinned semantic adapter, applied by Mixin after the installed lifecycle injections.
 * It adds two entry points for exactly ChunkStatusTasks' FULL supplier. The baseline supplier
 * stays intact. No external class, arbitrary method, instruction offset or test fragment is used.
 */
public final class FullStageBoundary {
    public static final String TARGET = "net/minecraft/world/level/chunk/status/ChunkStatusTasks";
    public static final String SUPPLIER = "lambda$full$0";
    public static final String PREPARE = "phoenium$prepareFull";
    public static final String FINISH = "phoenium$finishFull";
    public static final String DESCRIPTOR = "(Lnet/minecraft/world/level/chunk/ChunkAccess;"
            + "Lnet/minecraft/world/level/chunk/status/WorldGenContext;"
            + "Lnet/minecraft/server/level/GenerationChunkHolder;)Lnet/minecraft/world/level/chunk/ChunkAccess;";
    public static final String FINISH_DESCRIPTOR = DESCRIPTOR.replace(")Lnet/", "Lnet/minecraft/world/level/chunk/LevelChunk;)Lnet/");
    public static final String CALLBACK_DESCRIPTOR = DESCRIPTOR.substring(0, DESCRIPTOR.indexOf(')'))
            + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
    private static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    private static final String PROTO = "net/minecraft/world/level/chunk/ProtoChunk";
    private static final String CONTEXT = "net/minecraft/world/level/chunk/status/WorldGenContext";
    private static final String HOLDER = "net/minecraft/server/level/GenerationChunkHolder";
    private static final List<String> ORIGINAL_CALLS = List.of(
            CONTEXT + ".level()Lnet/minecraft/server/level/ServerLevel;",
            "net/minecraft/world/level/chunk/ImposterProtoChunk.getWrapped()Lnet/minecraft/world/level/chunk/LevelChunk;",
            LEVEL_CHUNK + ".<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
            "net/minecraft/world/level/chunk/ImposterProtoChunk.<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Z)V",
            HOLDER + ".replaceProtoChunk(Lnet/minecraft/world/level/chunk/ImposterProtoChunk;)V",
            LEVEL_CHUNK + ".setFullStatus(Ljava/util/function/Supplier;)V",
            LEVEL_CHUNK + ".runPostLoad()V",
            LEVEL_CHUNK + ".setLoaded(Z)V",
            LEVEL_CHUNK + ".registerAllBlockEntitiesAfterLevelLoad()V",
            LEVEL_CHUNK + ".registerTickContainerInLevel(Lnet/minecraft/server/level/ServerLevel;)V",
            CONTEXT + ".unsavedListener()Lnet/minecraft/world/level/chunk/LevelChunk$UnsavedListener;",
            LEVEL_CHUNK + ".setUnsavedListener(Lnet/minecraft/world/level/chunk/LevelChunk$UnsavedListener;)V");

    private FullStageBoundary() { }

    /** The production plugin refuses missing, moved, duplicated or unknown supplier injections. */
    public static void requireInstalledCallbacks(ClassNode target) {
        MethodNode supplier = target.methods.stream().filter(m -> m.name.equals(SUPPLIER) && m.desc.equals(DESCRIPTOR))
                .findFirst().orElseThrow(() -> drift("missing transformed supplier"));
        boolean finishing = false;
        int load = 0, enter = 0, returned = 0;
        for (AbstractInsnNode instruction : supplier.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.owner.equals(LEVEL_CHUNK) && call.name.equals("runPostLoad")) finishing = true;
            if (call.owner.equals(TARGET)) {
                require(call.getOpcode() == Opcodes.INVOKESTATIC && call.desc.equals(CALLBACK_DESCRIPTOR),
                        "installed supplier callback signature changed");
                if (call.name.endsWith("$fabric-lifecycle-events-v1$onChunkLoad")) {
                    require(finishing, "Fabric load event moved into preparation"); load++;
                } else if (call.name.endsWith("$phoenium$fullConversionStarted")) {
                    require(!finishing, "conversion-entry observation moved"); enter++;
                } else if (call.name.endsWith("$phoenium$fullConversionReturned")) {
                    require(finishing, "conversion-return observation moved"); returned++;
                } else throw drift("unaudited supplier injection: " + call.name);
            } else if (!call.owner.startsWith("net/minecraft/")
                    && !(call.owner.equals("java/util/Objects") && call.name.equals("requireNonNull"))
                    && !(call.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable")
                         && call.name.equals("<init>"))) throw drift("unaudited supplier call: " + call.owner + "." + call.name);
        }
        require(load == 1 && enter == 1 && returned == 1, "installed FULL callback inventory changed");
    }

    public static void install(ClassNode target) {
        require(TARGET.equals(target.name), "wrong target class");
        require(target.methods.stream().noneMatch(m -> m.name.equals(PREPARE) || m.name.equals(FINISH)),
                "FULL boundary already installed");
        MethodNode supplier = target.methods.stream().filter(m -> m.name.equals(SUPPLIER) && m.desc.equals(DESCRIPTOR))
                .findFirst().orElseThrow(() -> drift("missing exact supplier"));
        require((supplier.access & Opcodes.ACC_STATIC) != 0 && supplier.tryCatchBlocks.isEmpty(),
                "supplier access or exception boundary changed");
        List<String> calls = new ArrayList<>();
        MethodInsnNode runPostLoad = null;
        for (AbstractInsnNode instruction : supplier.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.startsWith("net/minecraft/") && !call.owner.equals(TARGET))
                    calls.add(call.owner + "." + call.name + call.desc);
                if (call.owner.equals(LEVEL_CHUNK) && call.name.equals("runPostLoad")) {
                    require(runPostLoad == null, "multiple post-load calls"); runPostLoad = call;
                }
            }
        }
        require(calls.equals(ORIGINAL_CALLS), "ordered original FULL operations changed: " + calls);
        require(runPostLoad != null, "missing post-load boundary");
        AbstractInsnNode cut = previousOperation(runPostLoad);
        require(cut instanceof VarInsnNode && cut.getOpcode() == Opcodes.ALOAD, "post-load receiver changed");
        int actual = ((VarInsnNode) cut).var;
        // These are semantic-local invariants of this one pinned descriptor, checked rather than
        // inferred from a numeric byte offset or an arbitrary caller's stack.
        require(actual == 4, "actual chunk local changed");
        int boundary = supplier.instructions.indexOf(cut);
        Set<Integer> initialized = new HashSet<>(Set.of(0, 1, 2, 3, 4, 5));
        for (AbstractInsnNode instruction : supplier.instructions) {
            int index = supplier.instructions.indexOf(instruction);
            if (instruction instanceof JumpInsnNode jump) {
                require((index < boundary) == (supplier.instructions.indexOf(jump.label) < boundary),
                        "control flow crosses FULL boundary");
            }
            require(!(instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode),
                    "supplier switch requires a new boundary audit");
            if (index >= boundary && instruction instanceof VarInsnNode var) {
                if (var.getOpcode() >= Opcodes.ISTORE && var.getOpcode() <= Opcodes.ASTORE) initialized.add(var.var);
                else require(initialized.contains(var.var), "suffix uses a preparation-only local");
            }
        }
        // Returning from preparation must not execute any of the installed RETURN callbacks.
        MethodNode prepare = portion(supplier, PREPARE, DESCRIPTOR, 0, boundary);
        prepare.instructions.add(new VarInsnNode(Opcodes.ALOAD, actual));
        prepare.instructions.add(new InsnNode(Opcodes.ARETURN));
        MethodNode finish = portion(supplier, FINISH, FINISH_DESCRIPTOR, boundary, supplier.instructions.size());
        InsnList locals = new InsnList();
        locals.add(new VarInsnNode(Opcodes.ALOAD, 3)); locals.add(new VarInsnNode(Opcodes.ASTORE, actual));
        locals.add(new VarInsnNode(Opcodes.ALOAD, 0)); locals.add(new TypeInsnNode(Opcodes.CHECKCAST, PROTO));
        locals.add(new VarInsnNode(Opcodes.ASTORE, 3));
        locals.add(new VarInsnNode(Opcodes.ALOAD, 1));
        locals.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONTEXT, "level", "()Lnet/minecraft/server/level/ServerLevel;", false));
        locals.add(new VarInsnNode(Opcodes.ASTORE, 5));
        finish.instructions.insert(locals);
        target.methods.add(prepare); target.methods.add(finish);
    }

    /** One exact asynchronous registration edge; the original supplier is the baseline branch. */
    public static void installRegistration(ClassNode target) {
        require(TARGET.equals(target.name), "wrong async target");
        require(target.methods.stream().anyMatch(method -> method.name.equals(PREPARE) && method.desc.equals(DESCRIPTOR)),
                "async registration requires the installed preparation operation");
        String wrapperDescriptor = "(L" + CONTEXT + ";Lnet/minecraft/world/level/chunk/status/ChunkStep;"
                + "Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;";
        MethodNode wrapper = target.methods.stream().filter(m -> m.name.equals("full") && m.desc.equals(wrapperDescriptor))
                .findFirst().orElseThrow(() -> drift("missing exact async FULL wrapper"));
        MethodInsnNode edge = null;
        InvokeDynamicInsnNode supplier = null;
        int holders = 0;
        List<String> operations = new ArrayList<>();
        require(wrapper.tryCatchBlocks.isEmpty(), "async exception boundary changed");
        for (AbstractInsnNode instruction : wrapper.instructions) {
            require(!(instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode), "async control flow changed");
            if (instruction instanceof InvokeDynamicInsnNode indy) {
                require(supplier == null && indy.bsmArgs.length == 3 && indy.bsmArgs[1] instanceof org.objectweb.asm.Handle,
                        "async supplier shape changed");
                supplier = indy;
                var implementation = (org.objectweb.asm.Handle) indy.bsmArgs[1];
                require(implementation.getOwner().equals(TARGET) && implementation.getName().equals(SUPPLIER)
                        && implementation.getDesc().equals(DESCRIPTOR), "async supplier lineage changed");
            }
            if (instruction instanceof MethodInsnNode call) {
                if (!call.owner.equals(TARGET)
                        && !call.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"))
                    operations.add(call.owner + "." + call.name + call.desc);
                if (call.owner.equals("net/minecraft/util/StaticCache2D") && call.name.equals("get")) holders++;
                if (call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("supplyAsync")) {
                    require(edge == null && call.desc.equals("(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
                            "async registration changed"); edge = call;
                }
            }
        }
        require(operations.equals(List.of(
                "net/minecraft/world/level/chunk/ChunkAccess.getPos()Lnet/minecraft/world/level/ChunkPos;",
                "net/minecraft/world/level/ChunkPos.x()I", "net/minecraft/world/level/ChunkPos.z()I",
                "net/minecraft/util/StaticCache2D.get(II)Ljava/lang/Object;",
                CONTEXT + ".mainThreadExecutor()Ljava/util/concurrent/Executor;",
                "java/util/concurrent/CompletableFuture.supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")),
                "ordered async FULL operations changed");
        require(edge != null && supplier != null && holders == 1, "missing exact async registration operations");
        AbstractInsnNode holderStore = previousOperation(supplier);
        require(holderStore instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD && var.var == 5,
                "async captured holder local changed");
        InsnList arguments = new InsnList();
        for (int local : new int[]{0, 1, 2, 3, 5, 3, 0, 5}) arguments.add(new VarInsnNode(Opcodes.ALOAD, local));
        Object[] preparationBootstrap = supplier.bsmArgs.clone();
        preparationBootstrap[1] = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, TARGET, PREPARE, DESCRIPTOR, false);
        arguments.add(new InvokeDynamicInsnNode(supplier.name, supplier.desc, supplier.bsm, preparationBootstrap));
        wrapper.instructions.insertBefore(edge, arguments);
        wrapper.instructions.set(edge, new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/phoenium/chunk/FullRegistrationRuntime", "registerAsync",
                "(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;L" + CONTEXT + ";"
                        + "Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;L" + HOLDER + ";Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", false));
        wrapper.maxStack += 10;
    }

    public static void requireInstalledRegistrationCallbacks(ClassNode target) {
        var wrapper = target.methods.stream().filter(method -> method.name.equals("full")).findFirst().orElseThrow();
        int started = 0, returned = 0;
        String descriptor = wrapper.desc.substring(0, wrapper.desc.indexOf(')'))
                + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
        boolean afterRegistration = false;
        for (AbstractInsnNode instruction : wrapper.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("supplyAsync")) afterRegistration = true;
            if (call.owner.equals(TARGET)) {
                require(call.desc.equals(descriptor) && call.getOpcode() == Opcodes.INVOKESTATIC, "async observation signature changed");
                if (call.name.endsWith("$phoenium$fullStarted")) { require(!afterRegistration, "async entry observation moved"); started++; }
                else if (call.name.endsWith("$phoenium$fullReturned")) { require(afterRegistration, "async return observation moved"); returned++; }
                else throw drift("unknown async FULL injection: " + call.name);
            }
        }
        require(started == 1 && returned == 1, "async FULL observation inventory changed");
    }

    private static AbstractInsnNode previousOperation(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }

    private static MethodNode portion(MethodNode source, String name, String descriptor, int from, int to) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, descriptor, null, null);
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode instruction : source.instructions) if (instruction instanceof LabelNode label)
            labels.put(label, new LabelNode());
        for (int i = from; i < to; i++) {
            AbstractInsnNode instruction = source.instructions.get(i);
            // Source/debug scopes span both portions; they are not executable state or selectors.
            if (!(instruction instanceof LineNumberNode)) method.instructions.add(instruction.clone(labels));
        }
        method.maxLocals = Math.max(source.maxLocals, 6);
        method.maxStack = Math.max(source.maxStack, 4);
        return method;
    }
    private static void require(boolean condition, String detail) { if (!condition) throw drift(detail); }
    private static IllegalStateException drift(String detail) { return new IllegalStateException("Pinned FULL boundary drift: " + detail); }
}
