package dev.phoenium.chunk;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Mirrors only Mixin call-site plumbing; all reservation, generation, registrar and stage bodies are production. */
public final class FullRegistrationTestTransform {
    public static byte[] transform(String name, byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        MobPortalTestTransform.install(node);
        if (name.equals(dev.phoenium.live.MobPortalNativeSuffixes.CUBE)) {
            ClassWriter cubeWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(cubeWriter); return cubeWriter.toByteArray();
        }
        ContainerCloseTestTransform.install(node);
        if (NativePortalTestTransform.supports(name) && !name.equals("net/minecraft/world/entity/Entity")) {
            NativePortalTestTransform.install(node);
            ClassWriter portalWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(portalWriter); return portalWriter.toByteArray();
        }
        ResidentTickerTestTransform.install(node);
        VaultContinuationTestTransform.install(node);
        FullFinishingQueryTestTransform.install(node);
        FullFinishingServicesTestTransform.install(node);
        NativePortalTestTransform.install(node);
        FullSavedContextTestTransform.install(node);
        FullStructureTestTransform.install(node);
        FullEntityPredicateTestTransform.install(node);
        if (name.equals(dev.phoenium.live.LiveTickPhaseBoundary.LEVEL)) dev.phoenium.live.LiveTickPhaseBoundary.installLevel(node);
        if (name.equals(dev.phoenium.live.LiveTickPhaseBoundary.CACHE)) dev.phoenium.live.LiveTickPhaseBoundary.installCache(node);
        if (name.equals(FullStageBoundary.TARGET)) {
            FullStageBoundary.install(node); FullStageBoundary.installRegistration(node);
        } else if (name.equals("net/minecraft/nbt/CompoundTag") || name.equals("net/minecraft/nbt/ListTag")) {
            boolean compound = name.endsWith("CompoundTag");
            String field = compound ? "tags" : "list", method = compound ? "phoenium$savedMap" : "phoenium$savedList";
            String desc = compound ? "Ljava/util/Map;" : "Ljava/util/List;";
            node.interfaces.add("dev/phoenium/chunk/FullSavedCollections$" + (compound ? "CompoundAccess" : "ListAccess"));
            node.fields.stream().filter(f -> f.name.equals(field)).forEach(f -> f.access &= ~Opcodes.ACC_FINAL);
            getter(node, method, field, desc);
            MethodNode setter = new MethodNode(Opcodes.ACC_PUBLIC, method, "(" + desc + ")V", null, null);
            setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            setter.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, name, field, desc)); setter.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(setter);
        } else if (name.equals("net/minecraft/server/level/ServerChunkCache")) {
            node.interfaces.add("dev/phoenium/chunk/FullInputObservationThread");
            getter(node, "phoenium$inputThread", "mainThread", "Ljava/lang/Thread;");
        } else if (name.equals("net/minecraft/server/level/ChunkMap")) {
            node.interfaces.add("dev/phoenium/live/LiveTickReadiness$MapAccess");
            MethodNode ticking = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$tickCandidates", "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;)Ljava/util/stream/Stream;", null, null);
            ticking.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); ticking.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ticking.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, name, "allChunksWithAtLeastStatus", ticking.desc, false));
            ticking.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(ticking);
            node.interfaces.add("dev/phoenium/chunk/FullInputView");
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "phoenium$inputChanges", "Ldev/phoenium/chunk/FullInputNotifications;", null, null));
            getter(node, "phoenium$inputChanges", "phoenium$inputChanges", "Ldev/phoenium/chunk/FullInputNotifications;");
            for (MethodNode method : node.methods) if (method.name.equals("lambda$scheduleUnload$0"))
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("it/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap") && call.name.equals("remove") && call.desc.equals("(JLjava/lang/Object;)Z"))
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationTestHooks", "actualUnload", "(Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;JLjava/lang/Object;)Z", false));
            for (MethodNode method : node.methods) if (method.name.equals("promoteChunkMap")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.IRETURN) {
                    InsnList notify = new InsnList(); notify.add(new InsnNode(Opcodes.DUP)); notify.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    notify.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationTestHooks", "visiblePublished", "(ZLnet/minecraft/server/level/ChunkMap;)V", false));
                    method.instructions.insertBefore(instruction, notify);
                }
            }
            MethodNode lookup = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$visibleInput", "(J)Lnet/minecraft/server/level/ChunkHolder;", null, null);
            lookup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); lookup.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1));
            lookup.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, name, "getVisibleChunkIfPresent", lookup.desc, false));
            lookup.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(lookup);
        } else if (name.equals("net/minecraft/world/level/chunk/ChunkAccess")) {
            node.interfaces.add("dev/phoenium/chunk/FullBlockEntityTagInputs$Access");
            String state = "Ldev/phoenium/chunk/FullBlockEntityTagInputs$State;";
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "phoenium$blockTags", state, null, null));
            getter(node, "phoenium$blockEntityTags", "phoenium$blockTags", state);
            node.fields.stream().filter(field -> field.name.equals("pendingBlockEntities")).forEach(field -> field.access &= ~Opcodes.ACC_FINAL);
            for (MethodNode method : node.methods) if (method.name.equals("<init>"))
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                    InsnList init = new InsnList();
                    init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullBlockEntityTagInputs", "create", "()" + state, false));
                    init.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "phoenium$blockTags", state));
                    init.add(new VarInsnNode(Opcodes.ALOAD, 0)); init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.add(new FieldInsnNode(Opcodes.GETFIELD, name, "phoenium$blockTags", state));
                    init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.add(new FieldInsnNode(Opcodes.GETFIELD, name, "pendingBlockEntities", "Ljava/util/Map;"));
                    init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullBlockEntityTagInputs", "guard",
                            "(" + state + "Ljava/util/Map;)Ljava/util/Map;", false));
                    init.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "pendingBlockEntities", "Ljava/util/Map;"));
                    method.instructions.insertBefore(instruction, init);
                }
        } else if (name.equals("net/minecraft/world/level/chunk/LevelChunk")) {
            node.interfaces.add("dev/phoenium/chunk/FullSavedInput$PostLoadAccess");
            getter(node, "phoenium$postLoad", "postLoad", "Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;");
        } else if (name.equals("net/minecraft/world/level/chunk/storage/SerializableChunkData")) {
            for (MethodNode method : node.methods) if (method.name.equals("postLoadChunk")) {
                InsnList roots = new InsnList(); roots.add(new VarInsnNode(Opcodes.ALOAD, 1));
                roots.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullSavedInput", "reloadRoots", "(Ljava/util/List;)Ljava/util/List;", false));
                roots.add(new VarInsnNode(Opcodes.ASTORE, 1)); roots.add(new VarInsnNode(Opcodes.ALOAD, 2));
                roots.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullBlockEntityTagInputs", "reloadRoots", "(Ljava/util/List;)Ljava/util/List;", false));
                roots.add(new VarInsnNode(Opcodes.ASTORE, 2)); method.instructions.insert(roots);
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.ARETURN) {
                    InsnList wrap = new InsnList(); wrap.add(new VarInsnNode(Opcodes.ALOAD, 1)); wrap.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullSavedInput", "reload",
                            "(Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;Ljava/util/List;Ljava/util/List;)Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;", false));
                    method.instructions.insertBefore(instruction, wrap);
                }
            }
        } else if (name.equals("net/minecraft/world/level/chunk/ProtoChunk")) {
            node.interfaces.add("dev/phoenium/chunk/FullSavedInput$Access");
            String descriptor = "Ldev/phoenium/chunk/FullSavedInput;";
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "phoenium$saved", descriptor, null, null));
            getter(node, "phoenium$savedInput", "phoenium$saved", descriptor);
            for (MethodNode method : node.methods) {
                if (method.name.equals("<init>")) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        InsnList init = new InsnList(); init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        init.add(new TypeInsnNode(Opcodes.NEW, "dev/phoenium/chunk/FullSavedInput")); init.add(new InsnNode(Opcodes.DUP));
                        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "dev/phoenium/chunk/FullSavedInput", "<init>", "()V", false));
                        init.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "phoenium$saved", descriptor)); method.instructions.insertBefore(instruction, init);
                    }
                }
                if (method.name.equals("addEntity") && method.desc.equals("(Lnet/minecraft/nbt/CompoundTag;)V")) method.name = "phoenium$originalAddEntity";
                if (method.name.equals("getEntities")) method.name = "phoenium$originalGetEntities";
            }
            MethodNode entities = new MethodNode(Opcodes.ACC_PUBLIC, "getEntities", "()Ljava/util/List;", null, null);
            entities.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            entities.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationTestHooks", "savedEntities", "(Lnet/minecraft/world/level/chunk/ProtoChunk;)Ljava/util/List;", false));
            entities.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(entities);
            MethodNode add = new MethodNode(Opcodes.ACC_PUBLIC, "addEntity", "(Lnet/minecraft/nbt/CompoundTag;)V", null, null);
            add.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); add.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            add.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationTestHooks", "savedAdd",
                    "(Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/nbt/CompoundTag;)V", false));
            add.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(add);
        } else if (name.equals("net/minecraft/world/level/storage/PrimaryLevelData")) {
            node.interfaces.add("dev/phoenium/chunk/FullPreparationClockAccess");
            String descriptor = "Ldev/phoenium/chunk/FullPreparationClock;";
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "phoenium$clock", descriptor, null, null));
            getter(node, "phoenium$preparationClock", "phoenium$clock", descriptor);
            for (MethodNode method : node.methods) {
                if (method.name.equals("<init>")) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        InsnList init = new InsnList(); init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        init.add(new TypeInsnNode(Opcodes.NEW, "dev/phoenium/chunk/FullPreparationClock"));
                        init.add(new InsnNode(Opcodes.DUP));
                        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "dev/phoenium/chunk/FullPreparationClock", "<init>", "()V", false));
                        init.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "phoenium$clock", descriptor));
                        method.instructions.insertBefore(instruction, init);
                    }
                }
                if (method.name.equals("setGameTime") && method.desc.equals("(J)V")) {
                    method.instructions.insert(clockCall(name, "beforeWrite"));
                    for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN)
                        method.instructions.insertBefore(instruction, clockCall(name, "afterWrite"));
                }
            }
        } else if (FullFinishingQueryTestTransform.supports(name) || FullFinishingServicesTestTransform.supports(name)
                || FullSavedContextTestTransform.supports(name) || name.equals(FullEntityPredicateTestTransform.TARGET)) {
            // Query/service-only classes need no holder/saved-input fixture plumbing.
        } else {
            node.interfaces.add("dev/phoenium/chunk/FullHolderEvidence");
            String lifetimeDesc = "Ldev/phoenium/chunk/ChunkHolderLifetime;";
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "phoenium$lifetime", lifetimeDesc, null, null));
            getter(node, "phoenium$lifetime", "phoenium$lifetime", lifetimeDesc);
            MethodNode lifetimeSetter = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$lifetime", "(" + lifetimeDesc + ")V", null, null);
            lifetimeSetter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); lifetimeSetter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            lifetimeSetter.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "phoenium$lifetime", lifetimeDesc));
            lifetimeSetter.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(lifetimeSetter);
            String publicationDesc = "Ldev/phoenium/chunk/FullPublishedInputs$Entry;";
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "phoenium$publication", publicationDesc, null, null));
            getter(node, "phoenium$publication", "phoenium$publication", publicationDesc);
            MethodNode publicationSetter = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$publication", "(" + publicationDesc + ")V", null, null);
            publicationSetter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); publicationSetter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            publicationSetter.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "phoenium$publication", publicationDesc));
            publicationSetter.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(publicationSetter);
            getter(node, "phoenium$highestAllowed", "highestAllowedStatus", "Lnet/minecraft/world/level/chunk/status/ChunkStatus;");
            for (MethodNode method : node.methods) {
                if (method.name.equals("replaceProtoChunk")) method.instructions.insert(holderCall("replacing"));
                if (method.name.equals("updateHighestAllowedStatus"))
                    for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN)
                        method.instructions.insertBefore(instruction, holderCall("eligibilityChanged"));
                if (method.name.equals("failAndClearPendingFuture")) {
                    InsnList args = new InsnList(); args.add(new VarInsnNode(Opcodes.ALOAD, 0)); args.add(new VarInsnNode(Opcodes.ILOAD, 1)); args.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    args.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationTestHooks", "invalidating", "(Lnet/minecraft/server/level/GenerationChunkHolder;ILjava/util/concurrent/CompletableFuture;)V", false));
                    method.instructions.insert(args);
                }
                if (method.name.equals("completeFuture")) for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode call) {
                    boolean complete = call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("complete");
                    boolean install = call.owner.equals("java/util/concurrent/atomic/AtomicReferenceArray") && call.name.equals("compareAndSet");
                    if (complete || install) {
                        InsnList args = new InsnList(); args.add(new VarInsnNode(Opcodes.ALOAD, 0)); args.add(new VarInsnNode(Opcodes.ALOAD, 1)); method.instructions.insertBefore(call, args);
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationTestHooks", complete ? "completeSlot" : "installSlot",
                                complete ? "(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Object;Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStatus;)Z"
                                : "(Ljava/util/concurrent/atomic/AtomicReferenceArray;ILjava/lang/Object;Ljava/lang/Object;Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStatus;)Z", false));
                    }
                }
            }
            getter(node, "phoenium$startedWork", "startedWork", "Ljava/util/concurrent/atomic/AtomicReference;");
            getter(node, "phoenium$futures", "futures", "Ljava/util/concurrent/atomic/AtomicReferenceArray;");
            getter(node, "phoenium$generationRefs", "generationRefCount", "Ljava/util/concurrent/atomic/AtomicInteger;");
            getter(node, "phoenium$generationSaveSync", "generationSaveSyncFuture", "Ljava/util/concurrent/CompletableFuture;");
            MethodNode clear = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$failAndClear", "(ILjava/util/concurrent/CompletableFuture;)V", null, null);
            clear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); clear.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            clear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            clear.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, name, "failAndClearPendingFuture", clear.desc, false));
            clear.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(clear);
            int winning = 0, adjacent = 0;
            for (MethodNode method : node.methods) if (method.name.equals("applyStep")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals("net/minecraft/server/level/GeneratingChunkMap") && call.name.equals("applyStep")) {
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationTestHooks",
                                "applyWinning", call.desc.replace("(", "(Lnet/minecraft/server/level/GeneratingChunkMap;"), false)); winning++;
                    } else if (call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("handle")) {
                        InsnList args = new InsnList();
                        for (int local : new int[]{0, 1, 2}) args.add(new VarInsnNode(Opcodes.ALOAD, local));
                        method.instructions.insertBefore(call, args);
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationTestHooks", "handle",
                                "(Ljava/util/concurrent/CompletableFuture;Ljava/util/function/BiFunction;Lnet/minecraft/server/level/GenerationChunkHolder;"
                                        + "Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/server/level/GeneratingChunkMap;)Ljava/util/concurrent/CompletableFuture;", false)); adjacent++;
                    }
                }
            }
            if (winning != 1 || adjacent != 1) throw new AssertionError("test hook selectors changed");
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static InsnList clockCall(String owner, String operation) {
        InsnList result = new InsnList(); result.add(new VarInsnNode(Opcodes.ALOAD, 0));
        result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "phoenium$preparationClock", "()Ldev/phoenium/chunk/FullPreparationClock;", false));
        result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "dev/phoenium/chunk/FullPreparationClock", operation, "()V", false));
        return result;
    }
    private static InsnList holderCall(String operation) {
        InsnList result = new InsnList(); result.add(new VarInsnNode(Opcodes.ALOAD, 0));
        result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullPublishedInputs", operation, "(Lnet/minecraft/server/level/GenerationChunkHolder;)V", false)); return result;
    }
    private static void getter(ClassNode node, String methodName, String field, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, methodName, "()" + descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, descriptor));
        method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
    }
}
