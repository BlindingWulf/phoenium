package dev.phoenium.chunk;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.core.*;
import dev.phoenium.ownership.*;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

final class FreshPassengerAdmissionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static Entity entity(ServerLevel world, int id) throws Exception {
        Entity entity = stub(Entity.class, List.of());
        set(entity, Entity.class, "level", world); set(entity, Entity.class, "type", EntityTypes.PIG);
        set(entity, Entity.class, "passengers", ImmutableList.of());
        set(entity, Entity.class, "levelCallback", EntityInLevelCallback.NULL);
        set(entity, Entity.class, "chunkPosition", new ChunkPos(id, 0));
        set(entity, Entity.class, "position", new Vec3(id * 16 + 1, 65, 1));
        set(entity, Entity.class, "blockPosition", new net.minecraft.core.BlockPos(id * 16 + 1, 65, 1));
        set(entity, Entity.class, "phoenium$incarnation", FullEntityFacts.nextIncarnation());
        entity.setUUID(UUID.randomUUID()); entity.setId(id); return entity;
    }
    private static void attach(Entity parent, Entity... children) throws Exception {
        set(parent, Entity.class, "passengers", ImmutableList.copyOf(children));
        for (Entity child : children) set(child, Entity.class, "vehicle", parent);
    }
    private static final class Publisher implements FreshPassengerAdmission.Publisher {
        final WorldId world = new WorldId("passenger-test");
        final RegionId owner = new RegionId(world, "owner");
        final RegionDirectory directory = new RegionDirectory();
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        boolean authority = true;
        Publisher() { topology(1); }
        void topology(long generation) {
            var gen = new TopologyGeneration(generation);
            directory.publish(gen, Map.of(), Map.of(world, new RegionRoute(owner, gen, new OwnerEpoch(0))));
        }
        public void requireOwners(Set<ChunkPos> positions) { if (!authority) throw new IllegalStateException("foreign owner"); }
        public ObjectOwnership publish(Entity entity) {
            return objects.admit(entity.getUUID().toString(), OwnedObjectKind.ENTITY,
                new RoutingKey(world, entity.chunkPosition().x(), entity.chunkPosition().z()));
        }
        public ObjectOwnership current(String uuid) { return objects.current(uuid).orElse(null); }
        public void retire(ObjectOwnership identity) { objects.retirePublication(identity); }
    }
    /** Executes Minecraft's original default method and Entity's original nested stream. */
    private static void nativeInsert(Entity root, Consumer<Entity> insertion) {
        var level = (ServerLevelAccessor) Proxy.newProxyInstance(ServerLevelAccessor.class.getClassLoader(),
            new Class<?>[] {ServerLevelAccessor.class}, (proxy, method, args) -> {
                if (method.getName().equals("addFreshEntity")) { insertion.accept((Entity) args[0]); return true; }
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                throw new AssertionError("unexpected native call " + method);
            });
        level.addFreshEntityWithPassengers(root);
    }
    /** Executes the production handler bound to Zombie/Drowned/Husk.finalizeSpawn's direct call. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean directFinalizationInsertion(ServerLevelAccessor level, Entity root,
            BooleanSupplier insertion) throws Exception {
        Class type = Class.forName("dev.phoenium.mixin.NaturalSpawnFinalizationInsertionMixin");
        Object mixin = stub(type, List.of());
        Method handler = Arrays.stream(type.getDeclaredMethods())
            .filter(method -> method.getName().equals("phoenium$directFinalizationInsertion"))
            .findFirst().orElseThrow();
        handler.setAccessible(true);
        Operation<Boolean> original = ignored -> insertion.getAsBoolean();
        try { return (Boolean) handler.invoke(mixin, level, root, original); }
        catch (InvocationTargetException invocation) {
            Throwable failure = invocation.getCause();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw invocation;
        }
    }
    @Test void originalNativeLoopIsRootThenDepthFirstWithSynchronousTracking() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 1); var child = entity(world, 2);
            var nested = entity(world, 3); var sibling = entity(world, 4);
            attach(root, child, sibling); attach(child, nested);
            var events = new ArrayList<String>();
            nativeInsert(root, entity -> { events.add("insert:" + entity.getId()); events.add("track:" + entity.getId()); });
            assertEquals(List.of("insert:1", "track:1", "insert:2", "track:2", "insert:3", "track:3", "insert:4", "track:4"), events);
        }
    }
    @Test void onePassengerResolvesBeforeInsertionWithoutEarlyTrackingOrIndexPublication() throws Exception { successfulGraph(false); }
    @Test void nestedAndSiblingPassengersRetainExactIdentityAndNativeOrder() throws Exception { successfulGraph(true); }
    private void successfulGraph(boolean nestedGraph) throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 11); var child = entity(world, 12);
            attach(root, child); var expected = new ArrayList<>(List.of(root, child));
            if (nestedGraph) {
                var nested = entity(world, 13); var sibling = entity(world, 14);
                attach(child, nested); attach(root, child, sibling); expected.add(nested); expected.add(sibling);
            }
            var publisher = new Publisher(); var lookup = new EntityLookup<Entity>(); Object manager = new Object();
            var interaction = new dev.phoenium.interaction.InteractionTargetIndex();
            var lifecycle = new dev.phoenium.live.EntityIndexLifecycleAdapter(publisher.objects, publisher.directory, interaction);
            var inserted = new ArrayList<Entity>(); var starts = new ArrayList<Entity>();
            try (var turn = f.service.referenceAccess(); var admission = FreshPassengerAdmission.begin(world, root, publisher)) {
                var identities = expected.stream().map(e -> publisher.current(e.getUUID().toString())).toList();
                assertEquals(expected.size(), publisher.objects.liveCount());
                assertTrue(f.service.entityFacts.cut(world).candidates().isEmpty());
                assertEquals(0L, f.service.entityFacts.measurements().get("addressable"));
                assertTrue(interaction.resolve(publisher.world, child.getId()).isEmpty());
                for (Entity entity : expected) assertEquals(FreshPassengerAdmission.Phase.IDENTITY_PUBLISHED_NOT_TRACKED, admission.phase(entity));
                nativeInsert(root, entity -> {
                    inserted.add(entity); FullEntityFacts.stored(entity, manager); lookup.add(entity);
                    FullEntityFacts.passengerTrackingStarted(entity); starts.add(entity);
                    var identity = publisher.current(entity.getUUID().toString());
                    assertEquals(identity, lifecycle.addressable(identity.objectId(), OwnedObjectKind.ENTITY,
                        publisher.world, entity.getId(), identity.routingKey(), false));
                    var candidate = f.service.entityFacts.trackingCandidate(world, entity);
                    var passengers = f.service.entityFacts.trackingPassengers(candidate);
                    assertTrue(f.service.entityFacts.validTrackingPassengers(candidate, passengers));
                    if (entity == root) {
                        assertEquals(expected.subList(1, expected.size()), passengers.stream().map(FullEntityFacts.Candidate::alias).toList());
                        assertNull(lookup.getEntity(child.getUUID()));
                        assertTrue(interaction.resolve(publisher.world, child.getId()).isEmpty());
                        assertFalse(f.service.entityFacts.hasStorage(child));
                        assertEquals(FreshPassengerAdmission.Phase.IDENTITY_PUBLISHED_NOT_TRACKED, admission.phase(child));
                    }
                    FullEntityFacts.passengerTrackingCompleted(entity);
                });
                assertEquals(expected, inserted); assertEquals(expected, starts);
                for (int i = 0; i < expected.size(); i++) {
                    Entity entity = expected.get(i);
                    assertEquals(identities.get(i), publisher.current(entity.getUUID().toString()));
                    assertEquals(((FullEntityFacts.Access) entity).phoenium$entityIncarnation(),
                        f.service.entityFacts.trackingCandidate(world, entity).fact().incarnation());
                    assertEquals(FreshPassengerAdmission.Phase.TRACKING_PUBLISHED, admission.phase(entity));
                }
                assertThrows(ChunkProtocolException.class, () -> FullEntityFacts.passengerTrackingStarted(child));
            }
            assertEquals(expected.size(), publisher.objects.liveCount(), "actual storage owns the inserted prefix");
        }
    }
    @Test void nativeOrderingWithoutAdmissionReproducesMissingPassengerFacts() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 21); var child = entity(world, 22); attach(root, child);
            try (var turn = f.service.referenceAccess()) {
                FullEntityFacts.stored(root, new Object());
                assertThrows(ChunkProtocolException.class, () -> f.service.entityFacts.trackingPassengers(f.service.entityFacts.trackingCandidate(world, root)));
            }
        }
    }
    @Test void staleNativeIncarnationFailsClosed() throws Exception { invalidation((root, child, pub) -> set(child, Entity.class, "phoenium$incarnation", 99999L)); }
    @Test void detachedPassengerFailsClosedEvenWhenRelationPublicationIsUpdated() throws Exception {
        invalidation((root, child, pub) -> { attach(root); set(child, Entity.class, "vehicle", null); FullEntityFacts.passengersChanged(root); });
    }
    @Test void replacementWithSameUuidAndRuntimeIdFailsClosed() throws Exception {
        invalidation((root, child, pub) -> {
            var replacement = entity((ServerLevel) child.level(), child.getId()); replacement.setUUID(child.getUUID());
            attach(root, replacement); FullEntityFacts.passengersChanged(root);
        });
    }
    @Test void foreignWorldFailsClosed() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 31); var child = entity(f.world(), 32); attach(root, child);
            var pub = new Publisher();
            try (var turn = f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class, () -> FreshPassengerAdmission.begin(world, root, pub));
                assertEquals(0, pub.objects.liveCount()); assertFalse(f.service.entityFacts.hasRoute(root));
            }
        }
    }
    @Test void topologyGenerationChangeFailsClosed() throws Exception { invalidation((root, child, pub) -> pub.topology(2)); }
    @Test void ownerLossFailsClosedAndRetiresUnusedIdentities() throws Exception { invalidation((root, child, pub) -> pub.authority = false); }
    @Test void sameUuidOwnershipReplacementIsNotRetiredByOldAdmission() throws Exception {
        invalidation((root, child, pub) -> {
            var old = pub.current(child.getUUID().toString()); pub.objects.remove(old.objectId());
            var replacement = pub.publish(child);
            assertFalse(pub.objects.retirePublication(old)); assertEquals(replacement, pub.current(old.objectId()));
        });
    }
    private interface Mutation { void apply(Entity root, Entity child, Publisher publisher) throws Exception; }
    private void invalidation(Mutation mutation) throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 41); var child = entity(world, 42); attach(root, child);
            var pub = new Publisher();
            try (var turn = f.service.referenceAccess(); var admission = FreshPassengerAdmission.begin(world, root, pub)) {
                mutation.apply(root, child, pub);
                assertThrows(ChunkProtocolException.class, () -> f.service.entityFacts.trackingPassengers(f.service.entityFacts.trackingCandidate(world, root)));
            }
            assertFalse(f.service.entityFacts.hasRoute(child));
        }
    }
    @Test void partialInsertionPreservesNativePrefixAndRetiresOnlyUnusedPassenger() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 51); var child = entity(world, 52); attach(root, child);
            var pub = new Publisher(); var lookup = new EntityLookup<Entity>();
            try (var turn = f.service.referenceAccess()) {
                var expected = new IllegalStateException("native insertion failure");
                assertSame(expected, assertThrows(IllegalStateException.class, () -> {
                    try (var admission = FreshPassengerAdmission.begin(world, root, pub)) {
                        nativeInsert(root, entity -> {
                            if (entity == child) throw expected;
                            FullEntityFacts.stored(entity, lookup); lookup.add(entity);
                            FullEntityFacts.passengerTrackingStarted(entity); FullEntityFacts.passengerTrackingCompleted(entity);
                        });
                    }
                }));
                assertSame(root, lookup.getEntity(root.getUUID())); assertNull(lookup.getEntity(child.getUUID()));
                assertEquals(1, pub.objects.liveCount()); assertNull(pub.current(child.getUUID().toString()));
                assertNull(((FullEntityFacts.Access) child).phoenium$referenceFact());
            }
        }
    }
    @Test void removalBeforeInsertionCancelsGraphAndDoesNotLeakIdentity() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 61); var child = entity(world, 62); attach(root, child);
            var pub = new Publisher();
            try (var turn = f.service.referenceAccess(); var admission = FreshPassengerAdmission.begin(world, root, pub)) {
                FullEntityFacts.passengerRemoved(child);
                assertThrows(ChunkProtocolException.class, admission::validate);
                assertNull(pub.current(child.getUUID().toString()));
            }
            assertEquals(0, pub.objects.liveCount());
        }
    }
    @Test void serviceShutdownRetiresPendingGraphEvenIfNativeScopeHasNotClosed() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 71); var child = entity(world, 72); attach(root, child);
            var pub = new Publisher(); FreshPassengerAdmission admission;
            try (var turn = f.service.referenceAccess()) { admission = FreshPassengerAdmission.begin(world, root, pub); }
            f.service.entityFacts.close(); admission.close();
            assertEquals(0, pub.objects.liveCount()); assertFalse(f.service.entityFacts.hasRoute(root));
            assertThrows(ChunkProtocolException.class, admission::validate);
        }
    }
    @Test void worldCloseRetiresPendingGraphAndRejectsNewAdmission() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 81); var child = entity(world, 82); attach(root, child);
            var pub = new Publisher();
            try (var turn = f.service.referenceAccess(); var admission = FreshPassengerAdmission.begin(world, root, pub)) {
                FullFinishingServices.passengerWorldClosing(world);
                assertEquals(0, pub.objects.liveCount());
                assertThrows(ChunkProtocolException.class, admission::validate);
                assertThrows(ChunkProtocolException.class, () -> FreshPassengerAdmission.begin(world, root, pub));
            }
        }
    }
    @Test void rootRemovalRetiresUnusedGraphAtScopeExit() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 91); var child = entity(world, 92); attach(root, child);
            var pub = new Publisher();
            try (var turn = f.service.referenceAccess(); var admission = FreshPassengerAdmission.begin(world, root, pub)) {
                FullEntityFacts.passengerRemoved(root);
                assertThrows(ChunkProtocolException.class, admission::validate);
            }
            assertEquals(0, pub.objects.liveCount());
            assertNull(((FullEntityFacts.Access) root).phoenium$referenceFact());
        }
    }
    @Test void existingIdentityCollisionDoesNotPublishOrRetireAnUnrelatedObject() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 101); var child = entity(world, 102); attach(root, child);
            var pub = new Publisher(); var existing = pub.publish(child);
            try (var turn = f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class, () -> FreshPassengerAdmission.begin(world, root, pub));
                assertEquals(existing, pub.current(child.getUUID().toString()));
                assertFalse(f.service.entityFacts.hasRoute(root)); assertFalse(f.service.entityFacts.hasRoute(child));
                assertEquals(1, pub.objects.liveCount());
            }
        }
    }
    @Test void realNativeSectionManagerFinishesEachTrackingCallbackBeforeInsertingNextPassenger() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 111); var child = entity(world, 112);
            var nested = entity(world, 113); var sibling = entity(world, 114); attach(root, child, sibling); attach(child, nested);
            var pub = new Publisher(); var events = new ArrayList<String>();
            Object storageToken = new Object();
            var callbacks = new LevelCallback<Entity>() {
                public void onCreated(Entity entity) {
                    events.add("stored:" + entity.getId()); FullEntityFacts.stored(entity, storageToken);
                }
                public void onTrackingStart(Entity entity) {
                    events.add("tracking:" + entity.getId()); FullEntityFacts.passengerTrackingStarted(entity);
                    var source = f.service.entityFacts.trackingCandidate(world, entity);
                    var passengers = f.service.entityFacts.trackingPassengers(source);
                    if (entity == root) {
                        assertEquals(List.of(child, nested, sibling), passengers.stream().map(FullEntityFacts.Candidate::alias).toList());
                        assertFalse(f.service.entityFacts.hasStorage(child));
                        assertFalse(f.service.entityFacts.hasStorage(nested));
                        assertFalse(f.service.entityFacts.hasStorage(sibling));
                    }
                    FullEntityFacts.passengerTrackingCompleted(entity); events.add("tracked:" + entity.getId());
                }
                public void onDestroyed(Entity entity) { }
                public void onTickingStart(Entity entity) { events.add("ticking:" + entity.getId()); }
                public void onTickingEnd(Entity entity) { }
                public void onTrackingEnd(Entity entity) { }
                public void onSectionChange(Entity entity) { }
            };
            var storage = new EntityPersistentStorage<Entity>() {
                public java.util.concurrent.CompletableFuture<ChunkEntities<Entity>> loadEntities(ChunkPos pos) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new ChunkEntities<>(pos, List.of()));
                }
                public void storeEntities(ChunkEntities<Entity> entities) { }
                public void flush(boolean sync) { }
            };
            var manager = new PersistentEntitySectionManager<>(Entity.class, callbacks, storage);
            for (Entity entity : List.of(root, child, nested, sibling)) manager.updateChunkStatus(entity.chunkPosition(), Visibility.TICKING);
            try (var turn = f.service.referenceAccess(); var admission = FreshPassengerAdmission.begin(world, root, pub)) {
                nativeInsert(root, entity -> assertTrue(manager.addNewEntity(entity)));
                assertEquals(List.of("stored:111", "tracking:111", "tracked:111", "ticking:111",
                    "stored:112", "tracking:112", "tracked:112", "ticking:112",
                    "stored:113", "tracking:113", "tracked:113", "ticking:113",
                    "stored:114", "tracking:114", "tracked:114", "ticking:114"), events);
                assertEquals(4, manager.count());
            }
        }
    }
    @Test void directFinalizationInsertionKeepsFactsThroughDelayedOuterInsertion() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var vehicle = entity(world, 161); var candidate = entity(world, 162);
            attach(vehicle, candidate); var pub = new Publisher(); var events = new ArrayList<String>();
            Object storageToken = new Object();
            var callbacks = new LevelCallback<Entity>() {
                public void onCreated(Entity entity) { events.add("stored:" + entity.getId()); FullEntityFacts.stored(entity, storageToken); }
                public void onTrackingStart(Entity entity) {
                    events.add("tracking:" + entity.getId()); FullEntityFacts.passengerTrackingStarted(entity);
                    var source = f.service.entityFacts.trackingCandidate(world, entity);
                    var passengers = f.service.entityFacts.trackingPassengers(source);
                    if (entity == vehicle) {
                        assertEquals(List.of(candidate), passengers.stream().map(FullEntityFacts.Candidate::alias).toList());
                        assertFalse(f.service.entityFacts.hasStorage(candidate), "outer candidate must not be inserted early");
                    }
                    FullEntityFacts.passengerTrackingCompleted(entity); events.add("tracked:" + entity.getId());
                }
                public void onDestroyed(Entity entity) { }
                public void onTickingStart(Entity entity) { events.add("ticking:" + entity.getId()); }
                public void onTickingEnd(Entity entity) { }
                public void onTrackingEnd(Entity entity) { }
                public void onSectionChange(Entity entity) { }
            };
            var storage = new EntityPersistentStorage<Entity>() {
                public java.util.concurrent.CompletableFuture<ChunkEntities<Entity>> loadEntities(ChunkPos pos) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new ChunkEntities<>(pos, List.of()));
                }
                public void storeEntities(ChunkEntities<Entity> entities) { }
                public void flush(boolean sync) { }
            };
            var manager = new PersistentEntitySectionManager<>(Entity.class, callbacks, storage);
            manager.updateChunkStatus(vehicle.chunkPosition(), Visibility.TICKING);
            manager.updateChunkStatus(candidate.chunkPosition(), Visibility.TICKING);
            try (var turn = f.service.referenceAccess(); var scope = FreshPassengerAdmission.naturalSpawnScope(world, pub)) {
                try (var finalization = scope.finalizing(candidate)) {
                    assertTrue(directFinalizationInsertion(world, vehicle, () -> manager.addNewEntity(vehicle)));
                }
                assertEquals(1, manager.count());
                assertNotNull(((FullEntityFacts.Access) candidate).phoenium$referenceFact());
                assertEquals(0L, f.service.entityFacts.measurements().get("directPassengerSettled"));
                scope.bulkInsertion(candidate, () -> nativeInsert(candidate, entity -> assertTrue(manager.addNewEntity(entity))));
                assertEquals(2, manager.count());
            }
            assertEquals(List.of("stored:161", "tracking:161", "tracked:161", "ticking:161",
                "stored:162", "tracking:162", "tracked:162", "ticking:162"), events);
            assertEquals(1L, f.service.entityFacts.measurements().get("directPassengerPrepared"));
            assertEquals(1L, f.service.entityFacts.measurements().get("directPassengerAccepted"));
            assertEquals(1L, f.service.entityFacts.measurements().get("directPassengerSettled"));
            assertEquals(2, pub.objects.liveCount());
        }
    }
    @Test void reentrantScopeReusesTheExactDirectAdmissionWithoutDoubleOwnershipOrTracking() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var vehicle = entity(world, 171); var candidate = entity(world, 172);
            attach(vehicle, candidate); var pub = new Publisher(); var starts = new ArrayList<Entity>(); Object manager = new Object();
            try (var turn = f.service.referenceAccess(); var outer = FreshPassengerAdmission.naturalSpawnScope(world, pub)) {
                try (var finalization = outer.finalizing(candidate)) {
                    assertTrue(outer.directInsertion(vehicle, () -> {
                        FullEntityFacts.stored(vehicle, manager); FullEntityFacts.passengerTrackingStarted(vehicle);
                        starts.add(vehicle); FullEntityFacts.passengerTrackingCompleted(vehicle); return true;
                    }));
                }
                var vehicleOwnership = pub.current(vehicle.getUUID().toString());
                var candidateOwnership = pub.current(candidate.getUUID().toString());
                try (var nested = FreshPassengerAdmission.naturalSpawnScope(world, pub)) {
                    nested.bulkInsertion(candidate, () -> {
                        FullEntityFacts.stored(candidate, manager); FullEntityFacts.passengerTrackingStarted(candidate);
                        starts.add(candidate); FullEntityFacts.passengerTrackingCompleted(candidate);
                    });
                }
                assertEquals(vehicleOwnership, pub.current(vehicle.getUUID().toString()));
                assertEquals(candidateOwnership, pub.current(candidate.getUUID().toString()));
            }
            assertEquals(List.of(vehicle, candidate), starts); assertEquals(2, pub.objects.liveCount());
            assertEquals(1L, f.service.entityFacts.measurements().get("directPassengerPrepared"));
            assertEquals(2L, f.service.entityFacts.measurements().get("naturalPassengerScopes"));
        }
    }
    @Test void finalizationGraphExtensionCoversLaterHelperBulkWithoutEndingOuterCustody() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var vehicle = entity(world, 181); var candidate = entity(world, 182);
            var helper = entity(world, 183); attach(vehicle, candidate); var pub = new Publisher(); Object manager = new Object();
            try (var turn = f.service.referenceAccess(); var scope = FreshPassengerAdmission.naturalSpawnScope(world, pub)) {
                try (var finalization = scope.finalizing(candidate)) {
                    assertTrue(scope.directInsertion(vehicle, () -> {
                        FullEntityFacts.stored(vehicle, manager); FullEntityFacts.passengerTrackingStarted(vehicle);
                        FullEntityFacts.passengerTrackingCompleted(vehicle); return true;
                    }));
                    attach(vehicle, candidate, helper); FullEntityFacts.passengersChanged(vehicle);
                    scope.bulkInsertion(helper, () -> {
                        FullEntityFacts.stored(helper, manager); FullEntityFacts.passengerTrackingStarted(helper);
                        FullEntityFacts.passengerTrackingCompleted(helper);
                    });
                    assertNotNull(pub.current(helper.getUUID().toString()));
                }
                scope.bulkInsertion(candidate, () -> {
                    FullEntityFacts.stored(candidate, manager); FullEntityFacts.passengerTrackingStarted(candidate);
                    FullEntityFacts.passengerTrackingCompleted(candidate);
                });
            }
            assertEquals(3, pub.objects.liveCount());
        }
    }
    @Test void reentrantDirectVehicleReplacementTransfersTheSameCandidatePublication() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var firstVehicle = entity(world, 184); var secondVehicle = entity(world, 185);
            var candidate = entity(world, 186); attach(firstVehicle, candidate); var pub = new Publisher(); Object manager = new Object();
            try (var turn = f.service.referenceAccess(); var scope = FreshPassengerAdmission.naturalSpawnScope(world, pub)) {
                try (var finalization = scope.finalizing(candidate)) {
                    assertTrue(scope.directInsertion(firstVehicle, () -> {
                        FullEntityFacts.stored(firstVehicle, manager); FullEntityFacts.passengerTrackingStarted(firstVehicle);
                        FullEntityFacts.passengerTrackingCompleted(firstVehicle); return true;
                    }));
                    ObjectOwnership candidateOwnership = pub.current(candidate.getUUID().toString());
                    attach(firstVehicle); attach(secondVehicle, candidate); FullEntityFacts.passengersChanged(firstVehicle);
                    assertTrue(scope.directInsertion(secondVehicle, () -> {
                        FullEntityFacts.stored(secondVehicle, manager); FullEntityFacts.passengerTrackingStarted(secondVehicle);
                        FullEntityFacts.passengerTrackingCompleted(secondVehicle); return true;
                    }));
                    assertEquals(candidateOwnership, pub.current(candidate.getUUID().toString()));
                }
                scope.bulkInsertion(candidate, () -> {
                    FullEntityFacts.stored(candidate, manager); FullEntityFacts.passengerTrackingStarted(candidate);
                    FullEntityFacts.passengerTrackingCompleted(candidate);
                });
            }
            assertEquals(3, pub.objects.liveCount());
            assertEquals(1L, f.service.entityFacts.measurements().get("directPassengerPrepared"));
            assertEquals(2L, f.service.entityFacts.measurements().get("directPassengerAccepted"));
        }
    }
    @Test void directNonPassengerInsertionIsBitForBitDelegatedWithoutPublication() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var candidate = entity(world, 191); var independent = entity(world, 192);
            var pub = new Publisher(); var calls = new int[1];
            try (var turn = f.service.referenceAccess(); var scope = FreshPassengerAdmission.naturalSpawnScope(world, pub);
                    var finalization = scope.finalizing(candidate)) {
                assertFalse(scope.directInsertion(independent, () -> { calls[0]++; return false; }));
            }
            assertEquals(1, calls[0]); assertEquals(0, pub.objects.liveCount());
            assertNull(((FullEntityFacts.Access) independent).phoenium$referenceFact());
        }
    }
    @Test void directExceptionPreservesInsertedPrefixAndRetiresUnusedOuterCandidate() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var vehicle = entity(world, 201); var candidate = entity(world, 202);
            attach(vehicle, candidate); var pub = new Publisher(); Object manager = new Object();
            var expected = new IllegalStateException("direct native failure after storage");
            try (var turn = f.service.referenceAccess()) {
                assertSame(expected, assertThrows(IllegalStateException.class, () -> {
                    try (var scope = FreshPassengerAdmission.naturalSpawnScope(world, pub);
                            var finalization = scope.finalizing(candidate)) {
                        scope.directInsertion(vehicle, () -> { FullEntityFacts.stored(vehicle, manager); throw expected; });
                    }
                }));
            }
            assertEquals(1, pub.objects.liveCount()); assertNotNull(pub.current(vehicle.getUUID().toString()));
            assertNull(pub.current(candidate.getUUID().toString()));
            assertNull(((FullEntityFacts.Access) candidate).phoenium$referenceFact());
        }
    }
    @Test void rejectedDirectVehicleRetainsOuterCandidateUntilItsRealInsertion() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var vehicle = entity(world, 203); var candidate = entity(world, 204);
            attach(vehicle, candidate); var pub = new Publisher(); Object manager = new Object();
            try (var turn = f.service.referenceAccess(); var scope = FreshPassengerAdmission.naturalSpawnScope(world, pub)) {
                try (var finalization = scope.finalizing(candidate)) {
                    assertFalse(scope.directInsertion(vehicle, () -> false));
                    assertNotNull(pub.current(vehicle.getUUID().toString()));
                    assertNotNull(pub.current(candidate.getUUID().toString()));
                }
                scope.bulkInsertion(candidate, () -> {
                    FullEntityFacts.stored(candidate, manager); FullEntityFacts.passengerTrackingStarted(candidate);
                    FullEntityFacts.passengerTrackingCompleted(candidate);
                });
            }
            assertNull(pub.current(vehicle.getUUID().toString()));
            assertNotNull(pub.current(candidate.getUUID().toString()));
            assertEquals(1L, f.service.entityFacts.measurements().get("directPassengerRejected"));
            assertEquals(1L, f.service.entityFacts.measurements().get("directPassengerUpgraded"));
        }
    }
    @Test void shutdownDuringOuterScopeRetiresFactsAndLateScopeCloseCannotResurrectThem() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var vehicle = entity(world, 205); var candidate = entity(world, 206);
            attach(vehicle, candidate); var pub = new Publisher();
            try (var turn = f.service.referenceAccess()) {
                var scope = FreshPassengerAdmission.naturalSpawnScope(world, pub);
                try (var finalization = scope.finalizing(candidate)) {
                    assertFalse(scope.directInsertion(vehicle, () -> false));
                }
                f.service.entityFacts.close();
                assertEquals(0, pub.objects.liveCount());
                FullEntityFacts.passengerTrackingStarted(candidate);
                scope.close(); scope.close();
                assertNull(FreshPassengerAdmission.currentNaturalSpawnScope(world));
                assertEquals(0, pub.objects.liveCount());
                assertNull(((FullEntityFacts.Access) candidate).phoenium$referenceFact());
            }
        }
    }
    @Test void cyclicOrDuplicatedGraphIsRejectedBeforeAnyPublication() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 121); var child = entity(world, 122); attach(root, child, child);
            var pub = new Publisher();
            try (var turn = f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class, () -> FreshPassengerAdmission.begin(world, root, pub));
                assertEquals(0, pub.objects.liveCount()); assertFalse(f.service.entityFacts.hasRoute(root));
            }
        }
    }
    @Test void ownershipEpochChangeFailsClosedButUnusedIncarnationStillRetires() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 131); var child = entity(world, 132); attach(root, child);
            var pub = new Publisher();
            try (var turn = f.service.referenceAccess(); var admission = FreshPassengerAdmission.begin(world, root, pub)) {
                var old = pub.current(child.getUUID().toString());
                var gen = new TopologyGeneration(2); var destination = new RegionId(pub.world, "replacement-owner");
                pub.directory.publish(gen, Map.of(), Map.of(pub.world, new RegionRoute(destination, gen, new OwnerEpoch(0))));
                pub.objects.reconcileTopology();
                assertFalse(old.objectEpoch().equals(pub.current(old.objectId()).objectEpoch()));
                assertThrows(ChunkProtocolException.class, admission::validate);
            }
            assertEquals(0, pub.objects.liveCount());
        }
    }
    @Test void firstFailureDiagnosticIsBoundedAndIncludesExactLifecycleContext() throws Exception {
        var messages = new ArrayList<String>();
        var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("Phoenium");
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("passenger-test", null,
                org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), false,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event) {
                if (event.getMessage().getFormattedMessage().contains("PHOENIUM_PASSENGER_IDENTITY_FIRST_FAILURE"))
                    messages.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start(); logger.addAppender(appender);
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 141); var child = entity(world, 142); attach(root, child);
            var pub = new Publisher();
            try (var turn = f.service.referenceAccess(); var admission = FreshPassengerAdmission.begin(world, root, pub)) {
                assertTrue(messages.isEmpty());
                pub.objects.remove(child.getUUID().toString());
                assertThrows(ChunkProtocolException.class, admission::validate);
                assertThrows(ChunkProtocolException.class, admission::validate);
                assertEquals(1, messages.size()); String message = messages.getFirst();
                for (String field : List.of("root=", "passenger=", "type=", "depth=1", "pendingAdmission=true",
                        "entryPath=BULK_INSERTION", "admissionIdentity=", "outerGeneration=0", "nestedDepth=0",
                        "scopeExit=ACTIVE", "IDENTITY_PUBLISHED_NOT_TRACKED", "nativeIncarnation=", "objectEpoch=", "topologyGeneration=",
                        "world=", "owner=", "reason=ownership incarnation, epoch, or topology changed"))
                    assertTrue(message.contains(field), field + " missing: " + message);
            }
        } finally { logger.removeAppender(appender); appender.stop(); }
    }
    @Test void unrelatedParentCannotBorrowPendingPassengerIdentity() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); var root = entity(world, 151); var child = entity(world, 152); attach(root, child);
            var outsider = entity(world, 153); var pub = new Publisher();
            try (var turn = f.service.referenceAccess(); var admission = FreshPassengerAdmission.begin(world, root, pub)) {
                attach(root); FullEntityFacts.passengersChanged(root);
                attach(outsider, child); FullEntityFacts.stored(outsider, new Object());
                var failure = assertThrows(ChunkProtocolException.class, () -> f.service.entityFacts.trackingPassengers(
                    f.service.entityFacts.trackingCandidate(world, outsider)));
                assertTrue(failure.getMessage().contains("unrelated pending graph"));
            }
        }
    }
}
