package dev.phoenium.chunk;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pins the real 26.2 call order and mixin invocation descriptors without provider inspection. */
final class PlayerRespawnHookContractTest {
    private static ClassNode type(String name) throws Exception {
        try (var input = Objects.requireNonNull(PlayerRespawnHookContractTest.class.getResourceAsStream("/" + name + ".class"))) {
            var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
    }
    private static int call(MethodNode method, String owner, String name) {
        var instructions = method.instructions.toArray();
        for (int i = 0; i < instructions.length; i++) if (instructions[i] instanceof MethodInsnNode at
            && at.owner.equals(owner) && at.name.equals(name)) return i;
        throw new AssertionError("missing " + owner + "." + name);
    }
    private static int field(MethodNode method, String name) {
        var instructions = method.instructions.toArray();
        for (int i = 0; i < instructions.length; i++) if (instructions[i] instanceof FieldInsnNode at
            && at.name.equals(name) && at.getOpcode() == Opcodes.PUTFIELD) return i;
        throw new AssertionError("missing assignment " + name);
    }
    private static void ordered(int... indices) {
        for (int i = 1; i < indices.length; i++) assertTrue(indices[i - 1] < indices[i], Arrays.toString(indices));
    }
    @Test void nativeRespawnHooksFollowTheRealPublicationOrder() throws Exception {
        var body = method(type("net/minecraft/server/players/PlayerList"), "respawn");
        String player = "net/minecraft/server/level/ServerPlayer", level = "net/minecraft/server/level/ServerLevel";
        ordered(call(body, "java/util/List", "remove"), call(body, level, "removePlayerImmediately"),
            call(body, player, "<init>"), field(body, "connection"), call(body, player, "restoreFrom"),
            call(body, level, "addRespawnedPlayer"), call(body, "java/util/List", "add"), call(body, "java/util/Map", "put"));
    }
    @Test void storageCallbackExistsBeforeTheTrackingFailureWindow() throws Exception {
        String manager = "net/minecraft/world/level/entity/PersistentEntitySectionManager";
        var body = method(type(manager), "addEntity");
        ordered(call(body, manager, "addEntityUuid"), call(body, "net/minecraft/world/level/entity/EntitySection", "add"),
            call(body, "net/minecraft/world/level/entity/EntityAccess", "setLevelCallback"),
            call(body, manager, "startTracking"));
        var start = method(type(manager), "startTracking");
        ordered(call(start, "net/minecraft/world/level/entity/EntityLookup", "add"),
            call(start, "net/minecraft/world/level/entity/LevelCallback", "onTrackingStart"));
        var stop = method(type(manager), "stopTracking");
        ordered(call(stop, "net/minecraft/world/level/entity/LevelCallback", "onTrackingEnd"),
            call(stop, "net/minecraft/world/level/entity/EntityLookup", "remove"));
    }
    @Test void nativeDeathTickCompletesRemovalCallbackBeforeTheReceiptHookReturns() throws Exception {
        var death = method(type("net/minecraft/world/entity/LivingEntity"), "tickDeath");
        ordered(call(death, "net/minecraft/world/level/Level", "broadcastEntityEvent"),
            call(death, "net/minecraft/world/entity/LivingEntity", "remove"));
        var removal = method(type("net/minecraft/world/entity/Entity"), "setRemoved");
        ordered(call(removal, "net/minecraft/world/level/entity/EntityInLevelCallback", "onRemove"),
            call(removal, "net/minecraft/world/entity/Entity", "onRemoval"));
        var callback = method(type("net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback"), "onRemove");
        ordered(call(callback, "net/minecraft/world/level/entity/PersistentEntitySectionManager", "stopTracking"),
            call(callback, "java/util/Set", "remove"),
            call(callback, "net/minecraft/world/level/entity/EntityAccess", "setLevelCallback"));
        var hook = method(type("dev/phoenium/mixin/PlayerDeathRetirementMixin"), "phoenium$deathRetirementCompleted");
        assertNotNull(hook);
    }
    @Test void bothVanillaRespawnCallsAssignTheListenerOnlyAfterReturn() throws Exception {
        var body = method(type("net/minecraft/server/network/ServerGamePacketListenerImpl"), "handleClientCommand");
        int calls = 0;
        for (var instruction : body.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("respawn")) {
            AbstractInsnNode next = instruction.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            assertInstanceOf(FieldInsnNode.class, next);
            assertEquals("player", ((FieldInsnNode) next).name); assertEquals(Opcodes.PUTFIELD, next.getOpcode()); calls++;
        }
        assertEquals(2, calls);
    }
    @Test void everyNewNarrowInvocationHookMatchesExactlyOneNativeSite() throws Exception {
        verifyHooks("FullFinishingPlayerListMixin", "net/minecraft/server/players/PlayerList", "phoenium$respawn");
        verifyHooks("PlayerRespawnStorageMixin", "net/minecraft/world/level/entity/PersistentEntitySectionManager", "phoenium$");
    }
    private static void verifyHooks(String mixin, String target, String prefix) throws Exception {
        int inspected = 0; var nativeType = type(target);
        for (var handler : type("dev/phoenium/mixin/" + mixin).methods) {
            if (!handler.name.startsWith(prefix)) continue;
            var annotations = new ArrayList<AnnotationNode>();
            if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
            if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
            for (var annotation : annotations) if (annotation.desc.endsWith("/WrapOperation;")) {
                var values = values(annotation);
                Object selector = values.get("at");
                var at = selector instanceof List<?> list ? (AnnotationNode) list.getFirst() : (AnnotationNode) selector;
                String invocation = (String) values(at).get("target");
                int separator = invocation.indexOf(';'), descriptor = invocation.indexOf('(');
                String owner = invocation.substring(1, separator), name = invocation.substring(separator + 1, descriptor), desc = invocation.substring(descriptor);
                int matches = 0;
                for (String selected : (List<String>) values.get("method")) {
                    String selectedName = selected.contains("(") ? selected.substring(0, selected.indexOf('(')) : selected;
                    for (var candidate : nativeType.methods) if (candidate.name.equals(selectedName))
                        for (var instruction : candidate.instructions) if (instruction instanceof MethodInsnNode call
                            && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) matches++;
                }
                assertEquals(1, matches, mixin + "." + handler.name); inspected++;
            }
        }
        assertTrue(inspected >= 2);
    }
    private static Map<String, Object> values(AnnotationNode annotation) {
        var result = new HashMap<String, Object>();
        for (int i = 0; i < annotation.values.size(); i += 2) result.put((String) annotation.values.get(i), annotation.values.get(i + 1));
        return result;
    }
    @Test void respawnMixinsAreInstalledInTheProductionConfiguration() throws Exception {
        String config;
        try (var input = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            config = new String(Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String mixin : List.of("PlayerRespawnIdentityMixin", "PlayerRespawnListenerMixin", "PlayerRespawnStorageMixin",
                "PlayerDeathRetirementMixin"))
            assertTrue(config.contains('"' + mixin + '"'));
    }
}
