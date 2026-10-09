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
    @Test void removedSavedReaderStillAcquiresOwnershipAtLaterNativeLookupInsertion() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world();
            Entity entity = stub(Entity.class, List.of(new Answer(Entity.class.getMethod("onRemoval", Entity.RemovalReason.class), args -> null)));
            UUID id = UUID.randomUUID(); initialize(entity, world, id, 85, EntityTypes.TADPOLE);
            set(entity, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(32, 4));
            set(entity, Entity.class, "position", new net.minecraft.world.phys.Vec3(520, 65, 72));
            entity.setRemoved(Entity.RemovalReason.DISCARDED);
            assertTrue(entity.isRemoved());
            var lookup = new EntityLookup<Entity>(); lookup.add(entity);
            assertSame(entity, lookup.getEntity(id), "native lookup admits a reader discarded before insertion");

            var runtime = new dev.phoenium.live.PhoeniumLiveRuntime(org.slf4j.LoggerFactory.getLogger("removed-saved-reader"));
            var directoryField = runtime.getClass().getDeclaredField("directory"); directoryField.setAccessible(true);
            var directory = (dev.phoenium.topology.RegionDirectory) directoryField.get(runtime);
            var worldId = new dev.phoenium.core.WorldId("minecraft:overworld");
            var key = new dev.phoenium.topology.RoutingKey(worldId, 32, 4);
            var region = new dev.phoenium.region.RegionId(worldId, "B");
            var generation = new dev.phoenium.core.TopologyGeneration(1);
            directory.publish(generation, Map.of(key, new dev.phoenium.topology.RegionRoute(region, generation,
                new dev.phoenium.core.OwnerEpoch(0))));
            var stateField = runtime.getClass().getDeclaredField("state"); stateField.setAccessible(true);
            stateField.set(runtime, Arrays.stream(stateField.getType().getEnumConstants())
                .filter(value -> value.toString().equals("RUNNING")).findFirst().orElseThrow());
            set(runtime, runtime.getClass(), "scheduler", new dev.phoenium.scheduler.RegionScheduler(
                Map.of(), directory, new dev.phoenium.failure.FailureBarrier()));
            runtime.onEntityAddressable(entity);
            var objectsField = runtime.getClass().getDeclaredField("objects"); objectsField.setAccessible(true);
            var objects = (dev.phoenium.ownership.ObjectOwnershipRegistry) objectsField.get(runtime);
            assertEquals(key, objects.current(id.toString()).orElseThrow().routingKey());
            assertEquals(region, objects.current(id.toString()).orElseThrow().owner());
            assertTrue(entity.isRemoved(), "ownership publication must not resurrect or mutate the reader");
            lookup.remove(entity); runtime.onEntityRemoved(entity);
            assertTrue(objects.current(id.toString()).isEmpty());
        }
    }
    /** S9-D5: the one-lock batch refresh returns what per-entry refreshes return, keeps an unchanged retained
     * record, and on a failure leaves the results before it written and the rest untouched. */
    @Test void batchTrackerRefreshMatchesSingleRefreshAndStopsAtFirstFailure() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world();
            LivingEntity first = target(world, UUID.randomUUID(), 211, new AtomicInteger());
            LivingEntity second = target(world, UUID.randomUUID(), 212, new AtomicInteger());
            for (LivingEntity entity : List.of(first, second)) {
                set(entity, Entity.class, "position", net.minecraft.world.phys.Vec3.ZERO);
                set(entity, Entity.class, "blockPosition", net.minecraft.core.BlockPos.ZERO);
                set(entity, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
                set(entity, Entity.class, "firstTick", true);
            }
            var lookup = new EntityLookup<Entity>(); lookup.add(first); lookup.add(second);
            try (var turn = f.service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
                var retainedFirst = f.service.entityFacts.trackingCandidate(world, first);
                var retainedSecond = f.service.entityFacts.trackingCandidate(world, second);
                second.setPosRaw(33.125, 65.75, -17.5);
                var out = new FullEntityFacts.Candidate[2];
                f.service.entityFacts.refreshTrackingCandidates(List.of(retainedFirst, retainedSecond), out);
                assertSame(retainedFirst, out[0]);
                assertEquals(f.service.entityFacts.refreshTrackingCandidate(retainedSecond), out[1]);
                assertEquals(new net.minecraft.world.level.ChunkPos(2, -2), out[1].position());
                lookup.remove(second);
                var partial = new FullEntityFacts.Candidate[2];
                var refreshedSecond = out[1];
                assertThrows(ChunkProtocolException.class,
                    () -> f.service.entityFacts.refreshTrackingCandidates(List.of(retainedFirst, refreshedSecond), partial));
                assertSame(retainedFirst, partial[0]);
                assertNull(partial[1]);
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

    /** Stage 7A first red: native pairing (sendPairingData -> updateDataBeforeSync) rewrites a converted
     * mob's effect-particle data inside ChunkMap.addEntity. A data write that leaves every published
     * scalar unchanged must keep the source relation; a scalar change must still retire it. */
    @Test void ownerDataWriteThatKeepsEveryScalarKeepsTrackingIdentity() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            ServerLevel world = f.world(); Object manager = new Object();
            LivingEntity entity = target(world, UUID.randomUUID(), 114, new AtomicInteger());
            set(entity, Entity.class, "position", net.minecraft.world.phys.Vec3.ZERO);
            set(entity, Entity.class, "blockPosition", net.minecraft.core.BlockPos.ZERO);
            set(entity, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
            set(entity, Entity.class, "firstTick", true);
            try (var turn = f.service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
                FullEntityFacts.stored(entity, manager);
                var access = (FullEntityFacts.Access) entity;
                FullEntityFacts.Fact fact = access.phoenium$referenceFact();
                assertTrue(fact.enemy());
                var receipt = f.service.entityFacts.trackingCandidate(world, entity);
                FullEntityFacts.publish(entity); FullEntityFacts.publish(entity);
                assertSame(fact, access.phoenium$referenceFact(), "an unchanged data write must not mint a new identity");
                assertTrue(f.service.entityFacts.validTrackingCandidate(receipt));
                set(entity, Entity.class, "invulnerable", true);
                FullEntityFacts.publish(entity);
                FullEntityFacts.Fact changed = access.phoenium$referenceFact();
                assertNotSame(fact, changed);
                assertFalse(changed.enemy());
                assertEquals(fact.revision() + 1, changed.revision());
                assertFalse(f.service.entityFacts.validTrackingCandidate(receipt));
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

    private static Entity reader(ServerLevel world, int id, int chunkX) throws Exception {
        Entity entity=stub(Entity.class,List.of()); initialize(entity,world,UUID.randomUUID(),id,EntityTypes.PIG);
        set(entity,Entity.class,"chunkPosition",new net.minecraft.world.level.ChunkPos(chunkX,0));
        set(entity,Entity.class,"position",new net.minecraft.world.phys.Vec3(chunkX*16+1,65,1));
        return entity;
    }
    @Test void parentFirstNativeInsertionCanProjectNestedPassengersFromExactLoadedBatch() throws Exception {
        try(var f=new FullServiceOperationFixture()) {
            var world=f.world(); var parent=reader(world,201,0); var child=reader(world,202,1); var grandchild=reader(world,203,2);
            set(parent,Entity.class,"passengers",ImmutableList.of(child)); set(child,Entity.class,"passengers",ImmutableList.of(grandchild));
            Object manager=new Object(); var lookup=new EntityLookup<Entity>();
            try(var turn=f.service.referenceAccess()) {
                try(var batch=FullEntityFacts.loadedBatch(world,List.of(parent,child,grandchild))) {
                    assertEquals(0,f.service.entityFacts.cut(world).candidates().size());
                    assertEquals(0L,f.service.entityFacts.measurements().get("addressable"));
                    FullEntityFacts.stored(parent,manager); lookup.add(parent);
                    var source=f.service.entityFacts.trackingCandidate(world,parent);
                    var passengers=f.service.entityFacts.trackingPassengers(source);
                    assertEquals(List.of(child,grandchild),passengers.stream().map(FullEntityFacts.Candidate::alias).toList());
                    assertEquals(List.of(1,2),passengers.stream().map(p->p.position().x()).toList());
                    assertTrue(f.service.entityFacts.validTrackingPassengers(source,passengers));
                    assertNull(lookup.getEntity(child.getUUID()),"staged input must not appear in native lookup");
                    FullEntityFacts.stored(child,manager); lookup.add(child);
                    FullEntityFacts.stored(grandchild,manager); lookup.add(grandchild);
                }
                assertEquals(3,f.service.entityFacts.cut(world).candidates().size());
                assertEquals(2,f.service.entityFacts.trackingPassengers(f.service.entityFacts.trackingCandidate(world,parent)).size());
            }
        }
    }
    @Test void failedLoadedBatchKeepsActualInsertionPrefixAndRetiresUnconsumedInputs() throws Exception {
        try(var f=new FullServiceOperationFixture()) {
            var world=f.world(); var inserted=reader(world,211,0); var unused=reader(world,212,1);
            var lookup=new EntityLookup<Entity>(); RuntimeException expected=new RuntimeException("native failure");
            try(var turn=f.service.referenceAccess()) {
                assertSame(expected,assertThrows(RuntimeException.class,()->{
                    try(var batch=FullEntityFacts.loadedBatch(world,List.of(inserted,unused))) {
                        FullEntityFacts.stored(inserted,new Object()); lookup.add(inserted); throw expected;
                    }
                }));
                assertSame(inserted,lookup.getEntity(inserted.getUUID()));
                assertNotNull(f.service.entityFacts.trackingCandidate(world,inserted));
                assertThrows(ChunkProtocolException.class,()->f.service.entityFacts.trackingCandidate(world,unused));
                assertNull(((FullEntityFacts.Access)unused).phoenium$referenceFact());
            }
        }
    }
    @Test void foreignLoadedInputRollsBackOnlyItsUnpublishedPrefix() throws Exception {
        try(var f=new FullServiceOperationFixture()) {
            var world=f.world(); var other=f.world(); var local=reader(world,221,0); var foreign=reader(other,222,0);
            try(var turn=f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class,()->FullEntityFacts.loadedBatch(world,List.of(local,foreign)));
                assertNull(((FullEntityFacts.Access)local).phoenium$referenceFact());
                assertNull(((FullEntityFacts.Access)foreign).phoenium$referenceFact());
                assertThrows(ChunkProtocolException.class,()->f.service.entityFacts.trackingCandidate(world,local));
            }
        }
    }
    @Test void loadedBatchDoesNotReplaceExistingPublishedIdentityOrVisibleOrder() throws Exception {
        try(var f=new FullServiceOperationFixture()) {
            var world=f.world(); var existing=reader(world,231,0); var lookup=new EntityLookup<Entity>(); lookup.add(existing);
            try(var turn=f.service.referenceAccess()) {
                var before=f.service.entityFacts.trackingCandidate(world,existing);
                try(var batch=FullEntityFacts.loadedBatch(world,List.of(existing,existing))) {
                    assertEquals(before,f.service.entityFacts.trackingCandidate(world,existing));
                }
                assertEquals(before,f.service.entityFacts.trackingCandidate(world,existing));
                assertEquals(List.of(existing),f.service.entityFacts.cut(world).candidates().stream().map(FullEntityFacts.Candidate::alias).toList());
            }
        }
    }
    @Test void unconsumedReloadInputRestoresPreviouslyPublishedReferenceFact() throws Exception {
        try(var f=new FullServiceOperationFixture()) {
            var world=f.world(); var entity=reader(world,241,0); var lookup=new EntityLookup<Entity>();
            lookup.add(entity); lookup.remove(entity);
            var before=((FullEntityFacts.Access)entity).phoenium$referenceFact();
            try(var turn=f.service.referenceAccess()) {
                try(var batch=FullEntityFacts.loadedBatch(world,List.of(entity))) {
                    assertNotNull(f.service.entityFacts.trackingCandidate(world,entity));
                }
                assertSame(before,((FullEntityFacts.Access)entity).phoenium$referenceFact());
                assertThrows(ChunkProtocolException.class,()->f.service.entityFacts.trackingCandidate(world,entity));
            }
        }
    }
    @Test void unconsumedSameUuidReaderCannotRetireCurrentNativeDirectoryEntry() throws Exception {
        try(var f=new FullServiceOperationFixture()) {
            var world=f.world(); var current=reader(world,251,0); var rejected=reader(world,252,1);
            rejected.setUUID(current.getUUID()); var lookup=new EntityLookup<Entity>();lookup.add(current);
            try(var turn=f.service.referenceAccess()) {
                var before=f.service.entityFacts.trackingCandidate(world,current);
                try(var batch=FullEntityFacts.loadedBatch(world,List.of(rejected))) {
                    assertSame(current,lookup.getEntity(current.getUUID()));
                }
                assertEquals(before,f.service.entityFacts.trackingCandidate(world,current));
                assertSame(current,lookup.getEntity(current.getUUID()));
                assertNull(((FullEntityFacts.Access)rejected).phoenium$referenceFact());
            }
        }
    }
}
