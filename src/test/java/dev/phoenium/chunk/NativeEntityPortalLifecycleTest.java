package dev.phoenium.chunk;

import dev.phoenium.live.EntityPortalTransitionManager;
import dev.phoenium.live.MobPortalOuterTails;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real native Entity.tick/AbstractMinecart.tick/EndCrystal.tick -> Entity.handlePortal -> NetherPortalBlock ->
 * PortalForcer -> PoiManager for the entity classes owned by EntityPortalTransitionManager. The reviewed tail must
 * not run while the transfer is retained, and must run exactly once, after the destination callback, on the retired
 * source under its source owner. Entity.baseTick's own tail runs first only for classes that reach handlePortal
 * through Entity.tick.
 */
class NativeEntityPortalLifecycleTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() {
        if (PortalDestinationTestHooks.entities != null) PortalDestinationTestHooks.entities.close();
        PortalDestinationTestHooks.entities = null; PortalDestinationTestHooks.outerTail = null; PortalDestinationTestHooks.outerTails = null;
    }

    /** type, whether the reviewed chain reaches handlePortal through Entity.baseTick. */
    static java.util.stream.Stream<Arguments> cases() {
        record Case(String type, boolean viaBaseTick) { }
        return java.util.stream.Stream.of(
                new Case("experience_orb", true), new Case("firework_rocket", true), new Case("end_crystal", false),
                new Case("tnt_minecart", false), new Case("spawner_minecart", false), new Case("command_block_minecart", false),
                new Case("hopper_minecart", false), new Case("furnace_minecart", false))
            .flatMap(c -> java.util.stream.Stream.of(true, false).map(forward -> Arguments.of(c.type(), c.viaBaseTick(), forward)));
    }

    static List<String> expectedTails(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type; c != Entity.class; c = c.getSuperclass()) {
            chain.add(0, c);
            var tick = MobPortalOuterTails.pins().getOrDefault(c.getName().replace('.', '/'), List.of()).stream()
                .filter(p -> p.method().equals("tick")).findFirst().orElse(null);
            if (tick != null && tick.anchor() == MobPortalOuterTails.Anchor.HANDLE_PORTAL) break;
        }
        // chain was built derived-first; the loop above inserted at 0, so index 0 is the class closest to Entity.
        List<String> tails = new ArrayList<>();
        for (Class<?> c : chain) {
            String internal = c.getName().replace('.', '/');
            for (var pin : MobPortalOuterTails.pins().getOrDefault(internal, List.of()))
                if (!pin.emptyTail() && pin.method().equals("tick"))
                    tails.add(internal + "." + MobPortalOuterTails.resumeName(internal, "tick"));
        }
        return tails;
    }

    @ParameterizedTest(name = "{0} forward={2}") @MethodSource("cases")
    void reviewedTailRunsOnceAfterTheTransferOnTheRetiredSource(String type, boolean viaBaseTick, boolean forward) throws Exception {
        var f = new NativeItemPortalTransferTest.Fixture();
        var manager = new EntityPortalTransitionManager(f.authority, f.objects);
        PortalDestinationTestHooks.entities = manager;
        f.levelData = (net.minecraft.world.level.storage.LevelData) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{net.minecraft.world.level.storage.ServerLevelData.class}, (proxy, method, args) ->
                method.getName().equals("getDifficulty") ? net.minecraft.world.Difficulty.NORMAL : null);
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var registries = NativePortalDestinationPreparationTest.dynamicRegistries();
        set(f.overworld, Level.class, "registryAccess", registries); set(f.nether, Level.class, "registryAccess", registries);
        var attributes = net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build();
        set(f.overworld, net.minecraft.server.level.ServerLevel.class, "environmentAttributes", attributes);
        set(f.nether, net.minecraft.server.level.ServerLevel.class, "environmentAttributes", attributes);
        set(f.overworld, Level.class, "random", net.minecraft.util.RandomSource.create(7L));
        set(f.nether, Level.class, "random", net.minecraft.util.RandomSource.create(11L));
        var source = forward ? f.overworld : f.nether; var destination = forward ? f.nether : f.overworld;
        var route = f.key(source);
        EntityType<?> entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
            net.minecraft.resources.Identifier.parse("minecraft:" + type));
        assertNotNull(entityType, type);
        Entity entity = entityType.create(source, EntitySpawnReason.COMMAND);
        assertNotNull(entity, type);
        assertTrue(EntityPortalTransitionManager.supports(entity), "generic manager must own " + entity.getClass());
        var expected = expectedTails(entity.getClass());
        entity.setPos(.44, 65.31, .02); f.publish(entity, route);
        var observed = new ArrayList<Object[]>();
        PortalDestinationTestHooks.outerTails = observed;
        PortalDestinationTestHooks.outerTail = (self, id) -> {
            assertSame(entity, self, "a lifted tail belongs to the retired source");
            f.dispatcher.requireCurrentOwner(route); f.order.add("tail:" + id);
        };
        NativePortalDestinationPreparationTest.setPortal(entity, args -> {
            ((Portal) Blocks.NETHER_PORTAL).getPortalDestination(source, entity, BlockPos.ZERO);
            return f.transition(destination);
        });
        var reads = new AtomicInteger();
        f.chunkRead = args -> {
            reads.incrementAndGet();
            if (!f.order.contains("insert")) {
                assertSame(ChunkStatus.EMPTY, args[2]); assertEquals(true, args[3]);
                var dependency = new java.util.concurrent.CompletableFuture<Void>(); dependency.complete(null);
                ChunkSynchronousAccessGuard.beforeWait(dependency::isDone);
                assertTrue(OwnershipContext.current().isEmpty());
                assertTrue(f.runtimes.values().stream().noneMatch(r -> r.leases().hasActiveLease()));
            } else f.dispatcher.requireCurrentOwner(route);
            return null;
        };
        assertDoesNotThrow(() -> f.dispatcher.executeAndDrain(route, RegionalServiceKind.CHUNK_GAMEPLAY,
            ignored -> manager.tick(entity, entity::tick)));
        assertEquals(0, reads.get(), "lookup must wait until the enclosing owner callback has returned");
        assertEquals(1, manager.pendingCount());
        assertEquals(List.of(), observed.stream().map(o -> o[1]).toList(), "no tail may run while retained: " + type);
        assertFalse(f.order.contains("base-suffix"), "Entity.baseTick's tail must wait for the transfer as well");
        assertEquals(1, manager.advance());
        assertTrue(reads.get() > 1, "native POI enumeration must reach each required EMPTY query");
        assertEquals(0, manager.advance());
        assertTrue(entity.isRemoved()); assertNotSame(entity, f.replacement); assertSame(destination, f.replacement.level());
        assertEquals(entity.getUUID(), f.replacement.getUUID());
        assertEquals(1, java.util.Collections.frequency(f.order, "insert"));
        assertEquals(viaBaseTick ? 1 : 0, java.util.Collections.frequency(f.order, "base-suffix"),
            "Entity.baseTick tail only for classes that reach handlePortal through Entity.tick");
        assertEquals(expected, observed.stream().map(o -> (String) o[1]).toList(), "each reviewed tail exactly once, in order");
        int post = f.order.indexOf("post");
        for (String id : expected) assertTrue(post < f.order.indexOf("tail:" + id), "tail before the destination callback: " + id);
        if (viaBaseTick) for (String id : expected)
            assertTrue(f.order.indexOf("base-suffix") < f.order.indexOf("tail:" + id), "Entity.baseTick tail first: " + id);
        assertEquals(1, f.objects.liveCount());
        assertEquals(Entity.RemovalReason.CHANGED_DIMENSION, entity.getRemovalReason());
    }
}
