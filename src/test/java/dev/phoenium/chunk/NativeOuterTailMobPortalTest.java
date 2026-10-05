package dev.phoenium.chunk;

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
 * Real native Mob.tick -> Entity.handlePortal -> NetherPortalBlock -> PortalForcer -> PoiManager for every Mob
 * caller profile that the legacy class sets do not cover. The reviewed lifted tails (MobPortalOuterTails) must not
 * run while the transfer is retained, and must each run exactly once on the retired source, under its source
 * owner, after the destination callback and in native unwinding order.
 */
class NativeOuterTailMobPortalTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() {
        if (PortalDestinationTestHooks.mobs != null) PortalDestinationTestHooks.mobs.close();
        PortalDestinationTestHooks.mobs = null; PortalDestinationTestHooks.outerTail = null; PortalDestinationTestHooks.outerTails = null;
        PortalDestinationTestHooks.horseTail = null; PortalDestinationTestHooks.cubeTail = null;
    }

    /** Every unbound Nether-capable Mob profile of the pinned census (bosses with no portal entry excluded). */
    static final List<String> TYPES = List.of(
        "allay", "armadillo", "axolotl", "bat", "bee", "breeze", "camel", "camel_husk", "cat", "cave_spider", "cod",
        "copper_golem", "creaking", "creeper", "dolphin", "evoker", "fox", "frog", "giant", "glow_squid", "happy_ghast",
        "illusioner", "nautilus", "panda", "parrot", "phantom", "polar_bear", "pufferfish", "rabbit", "salmon", "shulker",
        "sniffer", "spider", "squid", "strider", "sulfur_cube", "tadpole", "tropical_fish", "vex", "warden", "wolf",
        "zombie_nautilus", "zombie_villager");

    static java.util.stream.Stream<Arguments> cases() {
        return TYPES.stream().flatMap(type -> java.util.stream.Stream.of(true, false).map(forward -> Arguments.of(type, forward)));
    }

    /** Independent re-derivation of the expected tails from the pin file: base tails first, then tick tails. */
    static List<String> expectedTails(Class<?> mobClass) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = mobClass; c != Mob.class; c = c.getSuperclass()) chain.add(0, c);
        List<String> base = new ArrayList<>(), tick = new ArrayList<>();
        for (Class<?> c : chain) {
            String internal = c.getName().replace('.', '/');
            for (var pin : MobPortalOuterTails.pins().getOrDefault(internal, List.of())) {
                if (pin.emptyTail()) continue;
                (pin.method().equals("baseTick") ? base : tick).add(internal + "." + MobPortalOuterTails.resumeName(internal, pin.method()));
            }
        }
        base.addAll(tick);
        return base;
    }

    @ParameterizedTest(name = "{0} forward={1}") @MethodSource("cases")
    void reviewedOuterTailsRunOnceAfterTheTransferOnTheRetiredSource(String type, boolean forward) throws Exception {
        var f = new NativeItemPortalTransferTest.Fixture();
        var manager = new dev.phoenium.live.MobPortalTransitionManager(f.authority, f.objects);
        PortalDestinationTestHooks.mobs = manager;
        f.levelData = (net.minecraft.world.level.storage.LevelData) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{net.minecraft.world.level.storage.ServerLevelData.class}, (proxy, method, args) ->
                method.getName().equals("getDifficulty") ? net.minecraft.world.Difficulty.NORMAL : null);
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var registries = NativePortalDestinationPreparationTest.dynamicRegistries();
        set(f.overworld, Level.class, "registryAccess", registries); set(f.nether, Level.class, "registryAccess", registries);
        var attributes = net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build();
        set(f.overworld, net.minecraft.server.level.ServerLevel.class, "environmentAttributes", attributes);
        set(f.nether, net.minecraft.server.level.ServerLevel.class, "environmentAttributes", attributes);
        // Real native tails use the level's RandomSource (for example CopperGolem weathering).
        set(f.overworld, Level.class, "random", net.minecraft.util.RandomSource.create(7L));
        set(f.nether, Level.class, "random", net.minecraft.util.RandomSource.create(11L));
        var source = forward ? f.overworld : f.nether; var destination = forward ? f.nether : f.overworld;
        var route = f.key(source);
        EntityType<?> entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
            net.minecraft.resources.Identifier.parse("minecraft:" + type));
        assertNotNull(entityType, "reviewed native type must remain registered: " + type);
        var mob = (Mob) entityType.create(source, EntitySpawnReason.COMMAND);
        assertNotNull(mob, type);
        assertTrue(dev.phoenium.live.MobPortalTransitionManager.supports(mob), "profile not selected for " + mob.getClass());
        var expected = expectedTails(mob.getClass());
        mob.setPos(.44, 65.31, .02); f.publish(mob, route);
        mob.setAirSupply(137);
        mob.hurtTime = 7; mob.invulnerableTime = 9; mob.tickCount = 83;
        var observed = new ArrayList<Object[]>();
        PortalDestinationTestHooks.outerTails = observed;
        PortalDestinationTestHooks.horseTail = seen -> { assertSame(mob, seen); f.dispatcher.requireCurrentOwner(route); f.order.add("legacy-horse"); };
        PortalDestinationTestHooks.cubeTail = seen -> { assertSame(mob, seen); f.dispatcher.requireCurrentOwner(route); f.order.add("legacy-cube"); };
        PortalDestinationTestHooks.outerTail = (self, id) -> {
            assertSame(mob, self, "a lifted tail belongs to the retired source");
            f.dispatcher.requireCurrentOwner(route); f.order.add("tail:" + id);
        };
        NativePortalDestinationPreparationTest.setPortal(mob, args -> {
            ((Portal) Blocks.NETHER_PORTAL).getPortalDestination(source, mob, BlockPos.ZERO);
            return f.transition(destination);
        });
        var reads = new AtomicInteger();
        f.chunkRead = args -> {
            reads.incrementAndGet();
            // Only the pre-transfer destination lookup is the EMPTY/create POI query. A real native tail may
            // read the retired source's own chunk afterwards (for example Shulker.canStayAt).
            if (!f.order.contains("insert")) { assertSame(ChunkStatus.EMPTY, args[2]); assertEquals(true, args[3]); }
            var dependency = new java.util.concurrent.CompletableFuture<Void>(); dependency.complete(null);
            ChunkSynchronousAccessGuard.beforeWait(dependency::isDone);
            if (!f.order.contains("insert")) {
                assertTrue(OwnershipContext.current().isEmpty());
                assertTrue(f.runtimes.values().stream().noneMatch(r -> r.leases().hasActiveLease()));
            } else f.dispatcher.requireCurrentOwner(route);   // a real native tail reads the retired source under its owner
            return null;
        };
        assertDoesNotThrow(() -> f.dispatcher.executeAndDrain(route, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> mob.tick()));
        assertEquals(0, reads.get(), "lookup must wait until the enclosing owner callback has returned");
        assertEquals(1, manager.pendingCount());
        assertEquals(List.of(), observed.stream().map(o -> o[1]).toList(),
            "no outer tail may run while the transfer is retained: " + type);
        assertEquals(1, manager.advance());
        assertTrue(reads.get() > 1, "native POI enumeration must reach each required EMPTY query");
        assertEquals(0, manager.advance());
        assertTrue(mob.isRemoved()); assertNotSame(mob, f.replacement); assertSame(destination, f.replacement.level());
        assertEquals(mob.getUUID(), f.replacement.getUUID());
        assertEquals(1, java.util.Collections.frequency(f.order, "insert"));
        assertEquals(expected, observed.stream().map(o -> (String) o[1]).toList(),
            "every reviewed tail runs exactly once, base tails before tick tails, closest to Mob first");
        for (Object[] tail : observed)
            if (((String) tail[1]).contains("$baseTick$") && MobPortalOuterTails.pins().values().stream().flatMap(List::stream)
                    .anyMatch(p -> p.capture() == MobPortalOuterTails.Capture.AIR_SUPPLY && ((String) tail[1]).contains(
                        "phoenium$resumeTail$baseTick$" + p.owner().substring(p.owner().lastIndexOf('/') + 1))))
                assertEquals(137, tail[2], "the captured air supply is the pre-super value: " + tail[1]);
        int post = f.order.indexOf("post");
        for (String id : expected) assertTrue(post < f.order.indexOf("tail:" + id), "tail before the destination callback: " + id);
        if (mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse) {
            assertEquals(1, java.util.Collections.frequency(f.order, "legacy-horse"));
            assertTrue(post < f.order.indexOf("legacy-horse"));
            for (String id : expected) assertTrue(f.order.indexOf("legacy-horse") < f.order.indexOf("tail:" + id),
                "the AbstractHorse tail is closer to Mob than " + id);
        }
        if (mob instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob) {
            assertEquals(1, java.util.Collections.frequency(f.order, "legacy-cube"));
            for (String id : expected) assertTrue(f.order.indexOf("legacy-cube") < f.order.indexOf("tail:" + id),
                "the AbstractCubeMob tail is closer to Mob than " + id);
        }
        assertEquals(1, f.objects.liveCount(), "dimension retirement must not split the mob");
        assertEquals(Entity.RemovalReason.CHANGED_DIMENSION, mob.getRemovalReason());
    }
}
