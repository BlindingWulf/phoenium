package dev.phoenium.chunk;

import com.google.common.collect.ImmutableList;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.entity.*;
import org.junit.jupiter.api.*;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original reference and caller bodies consume owner-published facts through installed call sites. */
final class FullEntityFactsTest {
    private static final ThreadLocal<AtomicBoolean> CONSTRUCTOR_ROUTING = new ThreadLocal<>();
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void constructorOwnsItsAliasBeforeTheFirstSetPosRoutingCallbackWithoutPublishingFacts() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world(); AtomicBoolean routed = new AtomicBoolean();
            var chunks = stub(net.minecraft.server.level.ServerChunkCache.class, List.of(new Answer(
                    net.minecraft.server.level.ServerChunkCache.class.getMethod("hasEntityWithId", int.class), args -> false)));
            set(world, ServerLevel.class, "chunkSource", chunks);
            f.full(claim -> {
                CONSTRUCTOR_ROUTING.set(routed);
                try {
                    Entity entity = new ConstructorRoutingProbe(world);
                    assertTrue(routed.get());
                    assertNull(((FullEntityFacts.Access) entity).phoenium$referenceFact(),
                            "construction identity must not manufacture initialized owner-published facts");
                } finally { CONSTRUCTOR_ROUTING.remove(); }
            });
        }
    }
    private static LivingEntity target(ServerLevel level, UUID id, int number, AtomicInteger predicates) throws Exception {
        LivingEntity entity = stub(LivingEntity.class, List.of(new Answer(LivingEntity.class.getMethod("getHealth"), args -> {
            predicates.incrementAndGet(); return 20F;
        }), new Answer(Entity.class.getMethod("onRemoval", Entity.RemovalReason.class), args -> null)));
        initialize(entity, level, id, number, EntityTypes.PIG); return entity;
    }
    private static final class ConstructorRoutingProbe extends Entity {
        ConstructorRoutingProbe(ServerLevel level) { super(EntityTypes.ARROW, level); }
        @Override public void setPos(double x, double y, double z) {
            AtomicBoolean routed = CONSTRUCTOR_ROUTING.get();
            if (routed != null) {
                assertFalse(FullEntityFacts.foreign(this));
                assertFalse(isRemoved());
                routed.set(true);
            }
            super.setPos(x, y, z);
        }
        @Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) { }
        @Override public boolean hurtServer(ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount) { return false; }
        @Override protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) { }
        @Override protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) { }
    }
    private static void initialize(Entity entity, ServerLevel level, UUID id, int number, EntityType<?> type) throws Exception {
        set(entity, Entity.class, "level", level); set(entity, Entity.class, "type", type);
        set(entity, Entity.class, "passengers", ImmutableList.of()); set(entity, Entity.class, "levelCallback", EntityInLevelCallback.NULL);
        entity.setUUID(id); entity.setId(number);
    }
    @Test void firstNonnullWorldWinsBeforeOriginalClassFilteringAndUsesSuppliedOrder() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel origin = f.world(), second = f.world(), third = f.world(); UUID id = UUID.randomUUID();
            var local = new EntityLookup<Entity>(); var two = new EntityLookup<Entity>(); var three = new EntityLookup<Entity>();
            Entity wrong = stub(Entity.class, List.of()); initialize(wrong, origin, id, 51, EntityTypes.ARROW); local.add(wrong);
            LivingEntity expected = target(second, id, 52, new AtomicInteger()); two.add(expected); three.add(target(third, id, 53, new AtomicInteger()));
            f.full(claim -> assertNull(EntityReference.getLivingEntity(EntityReference.of(id), origin)));
            local.remove(wrong);
            f.full(claim -> assertSame(expected, EntityReference.getLivingEntity(EntityReference.of(id), origin)));
            Collections.swap(f.worlds, 1, 2);
            f.full(claim -> assertSame(three.getEntity(id), EntityReference.getLivingEntity(EntityReference.of(id), origin)));
        }
    }
    @Test void liveCachedAliasSurvivesDirectoryReplacementUntilOriginalRemovalFallback() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel origin = f.world(); UUID id = UUID.randomUUID(); var lookup = new EntityLookup<Entity>();
            LivingEntity old = target(origin, id, 61, new AtomicInteger()); lookup.add(old);
            var reference = EntityReference.<LivingEntity>of(id);
            f.full(claim -> assertSame(old, reference.getEntity(origin, LivingEntity.class)));
            lookup.remove(old); LivingEntity replacement = target(origin, id, 62, new AtomicInteger()); lookup.add(replacement);
            f.full(claim -> assertSame(old, reference.getEntity(origin, LivingEntity.class), "live cached alias wins over a same-UUID directory replacement"));
            old.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            f.full(claim -> assertSame(replacement, reference.getEntity(origin, LivingEntity.class)));
            assertEquals(1L, f.service.entityFacts.measurements().get("addressable"));
        }
    }
    @Test void originalMobValidationUsesFactsAtEveryReuseWithoutForeignPredicateCalls() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel origin = f.world(), foreign = f.world(); var calls = new AtomicInteger();
            LivingEntity target = target(foreign, UUID.randomUUID(), 71, calls); new EntityLookup<Entity>().add(target);
            int publications = calls.get(); Mob caller = stub(Mob.class, List.of()); set(caller, Entity.class, "level", origin);
            f.full(claim -> {
                FullEntityFacts.constructed(caller); caller.setTarget(target);
                assertSame(target, caller.getTargetUnchecked()); assertSame(target, caller.getTarget());
                assertEquals(publications, calls.get(), "original target virtual predicate is invoked only by its publisher");
            });
            target.setInvulnerable(true); int afterWriter = calls.get();
            f.full(claim -> { FullEntityFacts.constructed(caller); assertNull(caller.getTarget()); assertEquals(afterWriter, calls.get()); });
        }
    }
    @Test void committedRemovalFactPrecedesOriginalCallbackFailureAndCannotBeRolledBack() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world(); var failure = new IllegalStateException("original callback after removal");
            AtomicReference<Entity> exact = new AtomicReference<>();
            Entity entity = stub(Entity.class, List.of(new Answer(Entity.class.getMethod("onRemoval", Entity.RemovalReason.class), args -> {
                assertTrue(((FullEntityFacts.Access) exact.get()).phoenium$referenceFact().removed()); throw failure;
            })));
            exact.set(entity); initialize(entity, world, UUID.randomUUID(), 81, EntityTypes.ARROW); new EntityLookup<Entity>().add(entity);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> entity.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK)));
            f.full(claim -> assertTrue(entity.isRemoved()));
        }
    }
    @Test void foreignSetterCannotBorrowTheScalarReadTurnAndFailureSurvivesCatch() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world(); LivingEntity entity = target(world, UUID.randomUUID(), 91, new AtomicInteger());
            new EntityLookup<Entity>().add(entity);
            assertThrows(ChunkProtocolException.class, () -> f.full(claim -> {
                assertThrows(ChunkProtocolException.class, () -> entity.setInvulnerable(true));
                assertFalse(entity.isInvulnerable());
            }));
            assertEquals(0L, f.service.measurements().get("reservations"));
        }
    }
    @Test void identityWriterInvalidatesUnenteredCutAndOldAliasRetainsItsOwnIdentity() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world(); LivingEntity entity = target(world, UUID.randomUUID(), 101, new AtomicInteger());
            new EntityLookup<Entity>().add(entity); UUID next = UUID.randomUUID();
            try (var serial = f.service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
                var reservation = f.service.tryReserve(f.stage, f.holder, f.worlds, 1);
                entity.setUUID(next); assertFalse(reservation.valid(f.stage, f.holder)); reservation.close();
            }
            f.full(claim -> { assertEquals(next, entity.getUUID()); assertEquals(next.toString(), entity.getScoreboardName()); assertEquals(101, entity.getId()); });
        }
    }
    @Test void retainedDirectPassengerGraphExpandsInVanillaIndirectOrderWithoutAliasReads() {
        Object vehicle = new Object(), first = new Object(), grandchild = new Object(), second = new Object();
        Map<Object, List<Object>> direct = new IdentityHashMap<>();
        direct.put(vehicle, List.of(first, second)); direct.put(first, List.of(grandchild));
        assertEquals(List.of(first, grandchild, second),
            FullEntityFacts.orderedDescendants(vehicle, node -> direct.getOrDefault(node, List.of())));
    }

    @Test void committedPreciseMovementIsPublishedBeforeOriginalCallbackAndSurvivesItsFailure() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world();
            LivingEntity entity = target(world, UUID.randomUUID(), 111, new AtomicInteger());
            set(entity, Entity.class, "position", net.minecraft.world.phys.Vec3.ZERO);
            set(entity, Entity.class, "blockPosition", net.minecraft.core.BlockPos.ZERO);
            set(entity, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
            set(entity, Entity.class, "firstTick", true);
            var lookup = new EntityLookup<Entity>(); lookup.add(entity);
            try (var turn = f.service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
                var before = f.service.entityFacts.publishedPosition(entity);
                AtomicInteger callbacks = new AtomicInteger();
                var failure = new IllegalStateException("original movement observer failed");
                var destination = new net.minecraft.world.phys.Vec3(33.125, 65.75, -17.5);
                set(entity, Entity.class, "levelCallback", new EntityInLevelCallback() {
                    public void onMove() {
                        callbacks.incrementAndGet();
                        var reached = f.service.entityFacts.publishedPosition(entity);
                        assertEquals(destination, reached.position());
                        assertEquals(new net.minecraft.world.level.ChunkPos(2, -2), reached.candidate().position());
                        assertFalse(f.service.entityFacts.validTrackingCandidate(before.candidate()));
                        throw failure;
                    }
                    public void onRemove(Entity.RemovalReason reason) { }
                });
                assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> entity.setPosRaw(destination.x(), destination.y(), destination.z())));
                assertEquals(1, callbacks.get());
                assertEquals(destination, f.service.entityFacts.publishedPosition(entity).position());
                lookup.remove(entity);
                assertThrows(ChunkProtocolException.class, () -> f.service.entityFacts.publishedPosition(entity));
            }
        }
    }
    @Test void trackerRefreshUsesCommittedMovementButCannotAdoptReplacementEntity() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world();
            LivingEntity entity = target(world, UUID.randomUUID(), 112, new AtomicInteger());
            set(entity, Entity.class, "position", net.minecraft.world.phys.Vec3.ZERO);
            set(entity, Entity.class, "blockPosition", net.minecraft.core.BlockPos.ZERO);
            set(entity, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
            set(entity, Entity.class, "firstTick", true);
            var lookup = new EntityLookup<Entity>(); lookup.add(entity);
            try (var turn = f.service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
                var retained = f.service.entityFacts.trackingCandidate(world, entity);
                entity.setPosRaw(33.125, 65.75, -17.5);
                assertThrows(ChunkProtocolException.class, () -> f.service.entityFacts.trackingPassengers(retained));
                var current = f.service.entityFacts.refreshTrackingCandidate(retained);
                assertEquals(new net.minecraft.world.level.ChunkPos(2, -2), current.position());
                assertEquals(retained.fact().incarnation(), current.fact().incarnation());
                assertTrue(f.service.entityFacts.validTrackingCandidate(current));
                assertTrue(f.service.entityFacts.trackingPassengers(current).isEmpty());
                lookup.remove(entity);
                assertThrows(ChunkProtocolException.class, () -> f.service.entityFacts.refreshTrackingCandidate(current));
                LivingEntity replacement = target(world, entity.getUUID(), 112, new AtomicInteger());
                lookup.add(replacement);
                assertThrows(ChunkProtocolException.class, () -> f.service.entityFacts.refreshTrackingCandidate(current));
            }
        }
    }

    @Test void nativeHiddenStorageRetainsRoutesWithoutAddingVisibleCandidates() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world(); Object manager = new Object();
            LivingEntity entity = target(world, UUID.randomUUID(), 113, new AtomicInteger());
            set(entity, Entity.class, "position", net.minecraft.world.phys.Vec3.ZERO);
            set(entity, Entity.class, "blockPosition", net.minecraft.core.BlockPos.ZERO);
            set(entity, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
            set(entity, Entity.class, "firstTick", true);
            try (var turn = f.service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
                FullEntityFacts.stored(entity, manager);
                assertTrue(f.service.entityFacts.cut(world).candidates().isEmpty());
                assertEquals(0L, f.service.entityFacts.measurements().get("addressable"));
                var lookup = new EntityLookup<Entity>(); lookup.add(entity); lookup.remove(entity);
                assertTrue(f.service.entityFacts.cut(world).candidates().isEmpty());
                entity.setPosRaw(520.25, 64, 8.5);
                var receipt = f.service.entityFacts.trackingCandidate(world, entity);
                assertEquals(new net.minecraft.world.level.ChunkPos(32, 0), receipt.position());
                assertTrue(f.service.entityFacts.validTrackingCandidate(receipt));
                assertThrows(ChunkProtocolException.class, () -> FullEntityFacts.unstored(entity, new Object()));
                assertTrue(f.service.entityFacts.validTrackingCandidate(receipt));
                FullEntityFacts.unstored(entity, manager);
                assertFalse(f.service.entityFacts.validTrackingCandidate(receipt));
                assertThrows(ChunkProtocolException.class, () -> f.service.entityFacts.trackingCandidate(world, entity));
            }
        }
    }

    @Test void malformedRetainedPassengerGraphFailsBeforeTrackingCallback() {
        Object vehicle = new Object(), passenger = new Object();
        Map<Object, List<Object>> direct = new IdentityHashMap<>();
        direct.put(vehicle, List.of(passenger)); direct.put(passenger, List.of(vehicle));
        assertThrows(ChunkProtocolException.class,
            () -> FullEntityFacts.orderedDescendants(vehicle, node -> direct.getOrDefault(node, List.of())));
    }
}
