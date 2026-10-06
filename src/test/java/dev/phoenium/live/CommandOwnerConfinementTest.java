package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.LeaseEpoch;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static dev.phoenium.live.ScheduledFunctionBoundaryTest.annotation;
import static dev.phoenium.live.ScheduledFunctionBoundaryTest.at;
import static dev.phoenium.live.ScheduledFunctionBoundaryTest.calls;
import static dev.phoenium.live.ScheduledFunctionBoundaryTest.method;
import static dev.phoenium.live.ScheduledFunctionBoundaryTest.mixinConfig;
import static dev.phoenium.live.ScheduledFunctionBoundaryTest.node;
import static dev.phoenium.live.ScheduledFunctionBoundaryTest.value;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SF-1: a command run inside a region owner turn (command block, command-block minecart, entity or enchantment
 * function) may only see and act in what that owner owns. Folia's rule: a region cannot modify an entity or block
 * outside its owning region. Owner-free sources (console, RCON, player chat, scheduled functions) are unconfined.
 */
class CommandOwnerConfinementTest {
    private static final WorldId OW = new WorldId("minecraft:overworld");
    private static final WorldId NE = new WorldId("minecraft:the_nether");
    private static final RegionId OW_REGION = new RegionId(OW, "stage2-live-world");
    private static final RegionId OW_EAST = new RegionId(OW, "east");
    private static final OwnershipContext.Active OW_OWNER =
        new OwnershipContext.Active(OW_REGION, new LeaseEpoch(1), OperationCategory.TICK, 1);
    private static final CommandOwnerConfinement.Routes ROUTES =
        key -> Optional.of(key.x() >= 100 ? OW_EAST : new RegionId(key.world(), "stage2-live-world"));

    @AfterEach void uninstall() { CommandOwnerConfinement.uninstall(); }

    @Test void ownerFreeSourcesAreUnconfined() {
        assertTrue(CommandOwnerConfinement.admitsWorld(Optional.empty(), NE));
        assertTrue(CommandOwnerConfinement.admitsChunk(Optional.empty(), null, NE, 4400, 4400));
    }

    @Test void anOwnerAdmitsOnlyItsOwnWorld() {
        assertTrue(CommandOwnerConfinement.admitsWorld(Optional.of(OW_OWNER), OW));
        assertFalse(CommandOwnerConfinement.admitsWorld(Optional.of(OW_OWNER), NE));
    }

    @Test void anOwnerAdmitsOnlyChunksRoutedToItself() {
        assertTrue(CommandOwnerConfinement.admitsChunk(Optional.of(OW_OWNER), ROUTES, OW, 5, 5));
        assertFalse(CommandOwnerConfinement.admitsChunk(Optional.of(OW_OWNER), ROUTES, OW, 150, 5), "same world, other region");
        assertFalse(CommandOwnerConfinement.admitsChunk(Optional.of(OW_OWNER), ROUTES, NE, 5, 5), "other world");
        assertFalse(CommandOwnerConfinement.admitsChunk(Optional.of(OW_OWNER), key -> Optional.empty(), OW, 5, 5), "unrouted");
    }

    @Test void missingRoutesFailClosedOnlyUnderAnOwner() {
        assertFalse(CommandOwnerConfinement.admitsChunk(Optional.of(OW_OWNER), null, OW, 5, 5));
        assertTrue(CommandOwnerConfinement.admitsChunk(Optional.empty(), null, OW, 5, 5));
    }

    @Test void liveConfinementFollowsTheCurrentOwnershipContext() {
        CommandOwnerConfinement.install(ROUTES);
        assertFalse(CommandOwnerConfinement.confining());
        assertTrue(CommandOwnerConfinement.admitsWorld(NE));
        try (var owner = OwnershipContext.enter(OW_REGION, new LeaseEpoch(7), OperationCategory.TICK)) {
            assertTrue(CommandOwnerConfinement.confining());
            assertFalse(CommandOwnerConfinement.admitsWorld(NE));
            assertTrue(CommandOwnerConfinement.admitsWorld(OW));
            assertTrue(CommandOwnerConfinement.admitsChunk(OW, 0, 0));
            assertFalse(CommandOwnerConfinement.admitsChunk(OW, 100, 0));
        }
        assertTrue(CommandOwnerConfinement.admitsChunk(NE, 100, 0));
    }

    @Test void nativeSelectorsApplyThePredicateBeforeLimitsAndBypassItOnlyForNamesAndUuids() throws Exception {
        String selector = "net/minecraft/commands/arguments/selector/EntitySelector";
        assertEquals("(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/flag/FeatureFlagSet;)Ljava/util/function/Predicate;",
            method(selector, "getPredicate").desc);
        for (String find : List.of("findEntities", "findPlayers")) {
            MethodNode m = method(selector, find);
            assertEquals("(Lnet/minecraft/commands/CommandSourceStack;)Ljava/util/List;", m.desc);
            List<String> calls = calls(m);
            int predicate = calls.indexOf("getPredicate"), limit = calls.lastIndexOf("sortAndLimit");
            assertTrue(predicate >= 0 && limit > predicate, find + " filters before sortAndLimit: " + calls);
        }
        assertTrue(calls(method(selector, "findSingleEntity")).contains("findEntities"));
        assertTrue(calls(method(selector, "findSinglePlayer")).contains("findPlayers"));
    }

    @Test void nativeCommandEntryPointsHaveThePinnedShapes() throws Exception {
        MethodNode dimension = method("net/minecraft/commands/arguments/DimensionArgument", "getDimension");
        assertEquals("(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)Lnet/minecraft/server/level/ServerLevel;", dimension.desc);
        assertTrue((dimension.access & Opcodes.ACC_STATIC) != 0);
        assertEquals("(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/commands/CommandSourceStack;",
            method("net/minecraft/commands/CommandSourceStack", "withLevel").desc);
        assertEquals("(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/commands/CommandSourceStack;",
            method("net/minecraft/commands/CommandSourceStack", "withEntity").desc);
    }

    @Test void nativeForceLoadDiscardsItsSynchronousChunkLoad() throws Exception {
        MethodNode forced = method("net/minecraft/server/level/ServerLevel", "setChunkForced");
        MethodInsnNode load = null;
        int loads = 0;
        for (var i : forced.instructions)
            if (i instanceof MethodInsnNode c && c.name.equals("getChunk")) { load = c; loads++; }
        assertEquals(1, loads);
        assertEquals("(II)Lnet/minecraft/world/level/chunk/LevelChunk;", load.desc);
        assertEquals(Opcodes.POP, load.getNext().getOpcode(), "the loaded chunk is unused; the ticket already holds it");
        List<String> calls = calls(forced);
        assertTrue(calls.indexOf("updateChunkForced") < calls.indexOf("getChunk"), "ticket precedes the load: " + calls);
    }

    @Test void productionMixinsBindTheExactNativeSelectorsAndAreRegistered() throws Exception {
        String config = mixinConfig();
        AnnotationNode dimension = annotation("dev/phoenium/mixin/CommandDimensionConfinementMixin", "phoenium$confineDimension", "/ModifyReturnValue;");
        assertEquals(List.of("getDimension"), value(dimension, "method"));
        String selector = "dev/phoenium/mixin/CommandSelectorConfinementMixin";
        assertEquals(List.of("getPredicate"), value(annotation(selector, "phoenium$confinePredicate", "/ModifyReturnValue;"), "method"));
        assertEquals(List.of("findEntities"), value(annotation(selector, "phoenium$confineEntities", "/ModifyReturnValue;"), "method"));
        assertEquals(List.of("findPlayers"), value(annotation(selector, "phoenium$confinePlayers", "/ModifyReturnValue;"), "method"));
        String source = "dev/phoenium/mixin/CommandSourceConfinementMixin";
        assertEquals(List.of("withLevel"), value(annotation(source, "phoenium$confineLevel", "/Inject;"), "method"));
        assertEquals(List.of("withEntity"), value(annotation(source, "phoenium$confineEntity", "/Inject;"), "method"));
        AnnotationNode forced = annotation("dev/phoenium/mixin/ForcedChunkOwnerLoadMixin", "phoenium$ownerForceLoad", "/WrapOperation;");
        assertEquals(List.of("setChunkForced"), value(forced, "method"));
        assertTrue(at(forced).values.contains("Lnet/minecraft/server/level/ServerLevel;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"));
        for (String mixin : List.of("CommandDimensionConfinementMixin", "CommandSelectorConfinementMixin",
                "CommandSourceConfinementMixin", "ForcedChunkOwnerLoadMixin"))
            assertTrue(config.contains("\"" + mixin + "\""), mixin);
        assertTrue(calls(method("dev/phoenium/live/PhoeniumLiveRuntime", "start")).contains("install"),
            "the live runtime installs its region routes");
        assertNotNull(node("dev/phoenium/live/CommandOwnerConfinement"));
    }
}
