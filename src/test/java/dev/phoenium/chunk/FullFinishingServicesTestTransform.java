package dev.phoenium.chunk;

import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Test-only equivalents of the installed service mixin call sites. Original Minecraft
 * methods remain the callers and writers; all authority and projection logic is production. */
public final class FullFinishingServicesTestTransform {
    private static final String FACTS = "dev/phoenium/chunk/FullEntityFacts", HOOKS = "dev/phoenium/chunk/FullFinishingServicesTestHooks";
    private static final Set<String> TARGETS = Set.of("net/minecraft/world/entity/Entity", "net/minecraft/world/entity/EntityReference",
            "net/minecraft/world/level/entity/EntityLookup", "net/minecraft/world/level/storage/TagValueInput",
            "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Mob", "net/minecraft/server/level/ServerLevel",
            "net/minecraft/world/level/Level");
    public static boolean supports(String name) { return TARGETS.contains(name); }
    public static void install(ClassNode node) {
        if (!supports(node.name)) return;
        if (node.name.equals("net/minecraft/world/level/Level")) {
            node.interfaces.add("dev/phoenium/mixin/FullLevelThreadAccessMixin");
            getter(node, "phoenium$levelThread", "thread", "Ljava/lang/Thread;");
        } else if (node.name.equals("net/minecraft/world/level/storage/TagValueInput")) {
            node.interfaces.add("dev/phoenium/chunk/FullSavedValueInput");
            getter(node, "phoenium$originalSavedTag", "input", "Lnet/minecraft/nbt/CompoundTag;");
        } else if (node.name.equals("net/minecraft/world/entity/EntityReference")) {
            MethodNode reader = method(node, "readWithOldOwnerConversion", "(Lnet/minecraft/world/level/storage/ValueInput;Ljava/lang/String;Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/EntityReference;");
            reader.instructions.clear(); reader.tryCatchBlocks.clear(); if (reader.localVariables != null) reader.localVariables.clear();
            reader.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); reader.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); reader.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            reader.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullLegacyOwners", "readOriginal", reader.desc, false));
            reader.instructions.add(new InsnNode(Opcodes.ARETURN));
        } else if (node.name.equals("net/minecraft/world/entity/Entity")) {
            node.interfaces.add(FACTS + "$Access");
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "phoenium$fact", "L" + FACTS + "$Fact;", null, null));
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "phoenium$incarnation", "J", null, null));
            getter(node, "phoenium$referenceFact", "phoenium$fact", "L" + FACTS + "$Fact;");
            getter(node, "phoenium$entityIncarnation", "phoenium$incarnation", "J"); getter(node, "phoenium$rawEntityId", "id", "I");
            MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$referenceFact", "(L" + FACTS + "$Fact;)V", null, null);
            set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "phoenium$fact", "L" + FACTS + "$Fact;")); set.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(set);
            for (MethodNode body : node.methods.toArray(MethodNode[]::new)) {
                if (body.name.equals("<init>")) {
                    for (AbstractInsnNode at : body.instructions.toArray()) if (at instanceof MethodInsnNode call) {
                        if (call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals("java/lang/Object") && call.name.equals("<init>")) {
                            InsnList identity = new InsnList(); identity.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            identity.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FACTS, "nextIncarnation", "()J", false));
                            identity.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "phoenium$incarnation", "J"));
                            body.instructions.insert(at, identity);
                        }
                        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals(node.name)
                                && call.name.equals("setPos") && call.desc.equals("(DDD)V"))
                            body.instructions.insertBefore(at, entityCall("constructed"));
                    }
                }
                if (body.name.equals("setPosRaw") && body.desc.equals("(DDD)V"))
                    for (AbstractInsnNode at : body.instructions.toArray()) if (at instanceof MethodInsnNode call
                            && call.owner.equals("net/minecraft/world/level/entity/EntityInLevelCallback") && call.name.equals("onMove"))
                        body.instructions.insertBefore(at, entityCall("routed"));
                if (Set.of("getUUID", "getId", "isRemoved", "getScoreboardName", "typeHolder").contains(body.name)) wrap(node, body, body.name);
                if (Set.of("setId", "setUUID", "setRemoved", "unsetRemoved", "setInvulnerable", "setLevel").contains(body.name)) wrap(node, body, body.name);
                if (body.name.equals("phoenium$originalFacts$setRemoved")) for (AbstractInsnNode at : body.instructions.toArray())
                    if (at instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.name.equals("removalReason")) body.instructions.insert(at, entityCall("publish"));
            }
        } else if (node.name.equals("net/minecraft/world/level/entity/EntityLookup")) {
            for (MethodNode body : node.methods.toArray(MethodNode[]::new)) if (body.name.equals("add") || body.name.equals("remove")) {
                String operation = body.name;
                for (AbstractInsnNode at : body.instructions.toArray()) if (at instanceof MethodInsnNode call && call.owner.equals("java/util/Map")
                        && call.name.equals(operation.equals("add") ? "put" : "remove")) {
                    body.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, operation.equals("add") ? "lookupPut" : "lookupRemove",
                            call.desc.replace("(", "(Ljava/util/Map;"), false));
                }
                for (AbstractInsnNode at : body.instructions.toArray()) if (at instanceof MethodInsnNode call
                        && call.owner.equals("it/unimi/dsi/fastutil/ints/Int2ObjectMap")
                        && call.name.equals(operation.equals("add") ? "put" : "remove")) {
                    String hook = operation.equals("add") ? "lookupIntPut" : "lookupIntRemove";
                    body.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook,
                            call.desc.replace("(", "(Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;"), false));
                }
                wrap(node, body, operation.equals("add") ? "lookupAdd" : "lookupRemoveBody");
            }
        } else if (node.name.equals("net/minecraft/server/level/ServerLevel")) {
            wrap(node, method(node, "getEntityInAnyDimension", "(Ljava/util/UUID;)Lnet/minecraft/world/entity/Entity;"), "levelLookup");
            wrap(node, method(node, "getPlayerInAnyDimension", "(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"), "playerLookup");
        } else {
            for (MethodNode body : node.methods) if (body.name.equals("canAttack") || body.name.equals("asValidTarget"))
                for (AbstractInsnNode at : body.instructions.toArray()) if (at instanceof MethodInsnNode call) {
                    if (call.name.equals("canBeSeenAsEnemy")) body.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "enemy", "(Lnet/minecraft/world/entity/LivingEntity;)Z", false));
                    if (call.name.equals("isCreative") || call.name.equals("isSpectator")) body.instructions.set(call,
                            new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, call.name, "(Lnet/minecraft/world/entity/player/Player;)Z", false));
                }
        }
    }
    private static InsnList entityCall(String name) {
        InsnList instructions = new InsnList(); instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FACTS, name, "(Lnet/minecraft/world/entity/Entity;)V", false)); return instructions;
    }
    private static void wrap(ClassNode node, MethodNode original, String hook) {
        String publicName = original.name; original.name = "phoenium$originalFacts$" + publicName;
        original.access = (original.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        MethodNode wrapper = new MethodNode(original.access, publicName, original.desc, original.signature, original.exceptions.toArray(String[]::new));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int local = 1;
        for (Type type : Type.getArgumentTypes(original.desc)) { wrapper.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local)); local += type.getSize(); }
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook, original.desc.replace("(", "(L" + node.name + ";"), false));
        wrapper.instructions.add(new InsnNode(Type.getReturnType(original.desc).getOpcode(Opcodes.IRETURN))); node.methods.add(wrapper);
    }
    private static MethodNode method(ClassNode node, String name, String desc) { return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError("service selector changed: " + node.name + "." + name)); }
    private static void getter(ClassNode node, String name, String field, String desc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()" + desc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, desc));
        method.instructions.add(new InsnNode(Type.getType(desc).getOpcode(Opcodes.IRETURN))); node.methods.add(method);
    }
}
