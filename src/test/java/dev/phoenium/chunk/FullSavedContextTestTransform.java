package dev.phoenium.chunk;

import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Exact saved-context Mixin entry points for JVM tests; reader and storage bodies remain original. */
public final class FullSavedContextTestTransform {
    private static final String HOOKS = "dev/phoenium/chunk/FullSavedContextTestHooks";
    private static final Set<String> TARGETS = Set.of("net/minecraft/world/entity/Display$TextDisplay",
            "net/minecraft/world/level/storage/CommandStorage", "net/minecraft/network/chat/contents/data/BlockDataSource",
            "net/minecraft/network/chat/contents/SelectorContents", "net/minecraft/network/chat/contents/ScoreContents",
            "net/minecraft/network/chat/contents/NbtContents",
            "net/minecraft/world/entity/Entity", "net/minecraft/world/level/chunk/ChunkAccess",
            "net/minecraft/world/level/block/entity/BeaconBlockEntity",
            "net/minecraft/world/level/block/entity/BaseContainerBlockEntity",
            "net/minecraft/world/level/block/entity/EnchantingTableBlockEntity",
            "net/minecraft/world/level/block/entity/BannerBlockEntity",
            "net/minecraft/world/level/block/entity/SkullBlockEntity",
            "net/minecraft/world/level/storage/loot/predicates/CompositeLootItemCondition");
    public static boolean supports(String name) { return TARGETS.contains(name); }
    public static void install(ClassNode node) {
        if (!supports(node.name)) return;
        if (node.name.endsWith("/CompositeLootItemCondition")) {
            node.interfaces.add("dev/phoenium/chunk/FullSelectorPredicateInputs$CompositeAccess");
            var method = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$predicateTerms", "()Ljava/util/List;", null, null);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "terms", "Ljava/util/List;"));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(method);
        } else if (node.name.endsWith("/Entity")) {
            node.interfaces.add("dev/phoenium/chunk/FullLiveComponentInputs$EntityAccess");
            var method = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$rawCustomName", "()Lnet/minecraft/network/chat/Component;", null, null);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "entityData", "Lnet/minecraft/network/syncher/SynchedEntityData;"));
            method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, "DATA_CUSTOM_NAME", "Lnet/minecraft/network/syncher/EntityDataAccessor;"));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/syncher/SynchedEntityData", "get",
                    "(Lnet/minecraft/network/syncher/EntityDataAccessor;)Ljava/lang/Object;", false));
            method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Optional"));
            method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/network/chat/Component"));
            method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
        } else if (node.name.endsWith("$TextDisplay")) {
            node.interfaces.add("dev/phoenium/chunk/FullLiveComponentInputs$TextAccess");
            var method = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$rawDisplayText", "()Lnet/minecraft/network/chat/Component;", null, null);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getEntityData", "()Lnet/minecraft/network/syncher/SynchedEntityData;", false));
            method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, "DATA_TEXT_ID", "Lnet/minecraft/network/syncher/EntityDataAccessor;"));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/syncher/SynchedEntityData", "get",
                    "(Lnet/minecraft/network/syncher/EntityDataAccessor;)Ljava/lang/Object;", false));
            method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/network/chat/Component"));
            method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
            wrap(node, "readAdditionalSaveData", "(Lnet/minecraft/world/level/storage/ValueInput;)V", "textReader");
        } else if (node.name.endsWith("/ChunkAccess")) {
            node.interfaces.add("dev/phoenium/chunk/FullLiveComponentInputs$ChunkAccessView");
            fieldGetter(node, "phoenium$rawBlockEntities", "()Ljava/util/Map;", "blockEntities", "Ljava/util/Map;");
        } else if (node.name.endsWith("/SkullBlockEntity")) {
            node.interfaces.add("dev/phoenium/chunk/FullLiveComponentInputs$NamedBlockAccess");
            fieldGetter(node, "phoenium$rawBlockName", "()Lnet/minecraft/network/chat/Component;", "customName", "Lnet/minecraft/network/chat/Component;");
        } else if (node.name.endsWith("BlockEntity")) {
            node.interfaces.add("dev/phoenium/chunk/FullLiveComponentInputs$NamedBlockAccess");
            fieldGetter(node, "phoenium$rawBlockName", "()Lnet/minecraft/network/chat/Component;", "name", "Lnet/minecraft/network/chat/Component;");
        } else if (node.name.endsWith("/CommandStorage")) {
            wrap(node, "get", "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/nbt/CompoundTag;", "get");
            wrap(node, "set", "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/nbt/CompoundTag;)V", "set");
        } else if (node.name.endsWith("/BlockDataSource")) {
            var method = node.methods.stream().filter(body -> body.name.equals("getData")
                    && body.desc.equals("(Lnet/minecraft/commands/CommandSourceStack;)Ljava/util/stream/Stream;")).findFirst()
                    .orElseThrow(() -> new AssertionError("saved-context selector changed: " + node.name + ".getData"));
            for (AbstractInsnNode at : method.instructions.toArray()) if (at instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/server/level/ServerLevel")) {
                if (call.name.equals("isLoaded") && call.desc.equals("(Lnet/minecraft/core/BlockPos;)Z"))
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "blockLoaded",
                            "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Z", false));
                if (call.name.equals("getBlockEntity") && call.desc.equals("(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "blockEntity",
                            "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", false));
            }
        } else {
            String hook = node.name.endsWith("/SelectorContents") ? "selectorResolve"
                    : node.name.endsWith("/ScoreContents") ? "scoreResolve" : "nbtResolve";
            wrap(node, "resolve", "(Lnet/minecraft/network/chat/ResolutionContext;I)Lnet/minecraft/network/chat/MutableComponent;", hook);
        }
    }
    private static void fieldGetter(ClassNode node, String methodName, String methodDesc, String fieldName, String fieldDesc) {
        var method = new MethodNode(Opcodes.ACC_PUBLIC, methodName, methodDesc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, fieldName, fieldDesc));
        method.instructions.add(new InsnNode(Type.getReturnType(methodDesc).getOpcode(Opcodes.IRETURN)));
        node.methods.add(method);
    }
    private static void wrap(ClassNode node, String name, String descriptor, String hook) {
        var matches = node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).toList();
        if (matches.size() != 1) throw new AssertionError("saved-context selector changed: " + node.name + "." + name);
        var original = matches.getFirst();
        original.name = "phoenium$originalContext$" + name;
        original.access = (original.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        var wrapper = new MethodNode(original.access, name, descriptor, original.signature, original.exceptions.toArray(String[]::new));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int local = 1;
        for (Type type : Type.getArgumentTypes(descriptor)) {
            wrapper.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local)); local += type.getSize();
        }
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook,
                descriptor.replace("(", "(L" + node.name + ";"), false));
        wrapper.instructions.add(new InsnNode(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)));
        node.methods.add(wrapper);
    }
}
