package dev.phoenium.chunk;

import dev.phoenium.live.CrossDimensionReplacementBoundary;
import dev.phoenium.live.NativePortalGraphTransferPlan;
import dev.phoenium.live.PortalPassengerGraphSnapshot;
import dev.phoenium.service.RegionalServiceKind;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/** Pinned Entity.java:3074-3087 transfers the child before parent EntityType.create can return null. */
class NativePortalGraphParentNullControlTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void clear() { PortalDestinationTestHooks.mobs = null; }

    @Test void parentNullControlRecordsNativePhasesAndSettledBoundary() throws Exception {
        Observation observed = runParentNullControl();
        assertEquals(0, observed.pendingBoundaryCount(), "parent-null graph left a pending retained generation");
        assertEquals(2, observed.livePublicationCount(), "parent-null graph duplicated or lost UUID publication");
        assertEquals(observed.sourceRetirements(), observed.destinationInsertions(),
            "source retirements and destination admissions disagree");
        assertEquals(observed.destinationInsertions(), observed.postCallbacks(),
            "destination admissions and native callbacks disagree");
        System.out.println("STAGE7_GRAPH_PARENT_NULL_CONTROL " + observed);
    }

    /**
     * Required native-parity gate. Pinned 26.2 {@code Entity.teleportCrossDimension} ejects the
     * passengers, runs each child's complete {@code teleport} (source removal, destination
     * insertion, post callback) and only then asks {@code EntityType.create} for the parent, so a
     * null parent leaves the child admitted. Native has no rollback; Phoenium must reproduce that
     * exact per-member outcome and keep every identity, edge and publication consistent.
     */
    @Test void parentNullMatchesPinnedNativePerMemberOutcomeAndStaysSafe() throws Exception {
        Observation o = runParentNullControl();
        // Exact native per-member outcome.
        assertTrue(o.sourceParent(), "Ghast replacement returned null: the source Ghast must remain");
        assertFalse(o.destinationParent(), "no destination Ghast may exist");
        assertFalse(o.sourceChild(), "Chicken's native transfer completed before the parent factory");
        assertTrue(o.destinationChild(), "Chicken was admitted at the destination");
        assertEquals(java.util.List.of("source-retire", "insert", "post"), o.nativeEffects(),
            "native effects are exactly one child retire -> insert -> callback; no parent effects");
        // Native ejects before the child recursion: neither the source nor destination edge survives.
        assertFalse(o.originalEdge()); assertFalse(o.destinationEdge());
        assertTrue(o.parentPassengersEmpty(), "source Ghast kept a dangling passenger pointer");
        assertFalse(o.parentHasVehicle());
        assertFalse(o.destinationChildHasVehicle(), "destination Chicken kept a dangling vehicle pointer");
        assertTrue(o.destinationChildPassengersEmpty());
        // Identity: one addressable incarnation per UUID, never tracked in two worlds.
        assertTrue(o.parentUniquelyInSource() && o.childUniquelyInDestination(),
            "a UUID is addressable in both worlds");
        assertEquals(2, o.livePublicationCount());
        assertTrue(o.parentPublicationUnchanged(), "unchanged source parent must keep its exact publication");
        assertTrue(o.childPublicationReplaced(), "child needs a newer destination publication");
        assertTrue(o.sourceChildRetiredAsChangedDimension());
        assertEquals(0, o.pendingBoundaryCount(), "parent-null graph left a pending retained generation");
        assertFalse(o.cleanupDisturbedState(), "repeated cleanup mutated the settled graph");
    }

    /**
     * Superseded historical contract (preserved red). It demanded an all-or-unchanged graph, but
     * pinned native ordering admits the child before the parent outcome is known and has no rollback
     * (see {@link #parentNullMatchesPinnedNativePerMemberOutcomeAndStaysSafe}). It is not a native
     * refusal and is not part of the acceptance gate; the receipts remain in the audit directory.
     * Run with PHOENIUM_GRAPH_STRICT=true to reproduce the historical red.
     */
    @EnabledIfEnvironmentVariable(named = "PHOENIUM_GRAPH_STRICT", matches = "true")
    @Test void strictGraphAtomicityRejectsParentNullChildAdmission() throws Exception {
        Observation observed = runParentNullControl();
        boolean safeUnchanged = observed.sourceParent() && observed.sourceChild()
            && observed.originalEdge() && !observed.destinationParent() && !observed.destinationChild();
        boolean safeTransferred = !observed.sourceParent() && !observed.sourceChild()
            && observed.destinationParent() && observed.destinationChild() && observed.destinationEdge();
        assertTrue(safeUnchanged || safeTransferred,
            "native parent-null/child-admitted graph has no safe atomic outcome: " + observed);
    }

    private Observation runParentNullControl() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var boundary = new CrossDimensionReplacementBoundary();
        NativePortalDestinationPreparationTest.installNativeLookup(fixture);
        fixture.levelData = data(Difficulty.NORMAL);
        FullDecisionWitnessSupport.set(fixture.overworld, ServerLevel.class, "serverLevelData", fixture.levelData);
        FullDecisionWitnessSupport.set(fixture.nether, ServerLevel.class, "serverLevelData", fixture.levelData);
        var registries = NativePortalDestinationPreparationTest.dynamicRegistries();
        FullDecisionWitnessSupport.set(fixture.overworld, Level.class, "registryAccess", registries);
        FullDecisionWitnessSupport.set(fixture.nether, Level.class, "registryAccess", registries);
        var ghast = EntityTypes.GHAST.create(fixture.overworld, EntitySpawnReason.COMMAND);
        var chicken = EntityTypes.CHICKEN.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(ghast); assertNotNull(chicken);
        ghast.setPos(.44, 65.31, .02); chicken.setPos(.44, 66.1, .02);
        fixture.publish(ghast, fixture.from); fixture.publish(chicken, fixture.from);
        var graphHolder = new PortalPassengerGraphSnapshot[1];
        var tokens = new IdentityHashMap<Entity, CrossDimensionReplacementBoundary.Token>();
        fixture.dispatcher.executeAndDrain(fixture.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            assertTrue(chicken.startRiding(ghast, true, false));
            assertSame(ghast, chicken.getVehicle(), "parent-null control never formed a source graph");
            var graph = PortalPassengerGraphSnapshot.capture(ghast, fixture.objects);
            graphHolder[0] = graph;
            for (Entity source : graph.preorder()) {
                var member = graph.member(source);
                tokens.put(source, boundary.begin(new CrossDimensionReplacementBoundary.SourceIdentity(
                    member.uuid().toString(), member.nativeIncarnation(), member.binding().incarnation())));
            }
        });
        boundary.prepared(tokens.get(ghast));
        var plan = new NativePortalGraphTransferPlan(fixture.authority, fixture.objects, boundary,
            graphHolder[0], tokens, fixture.transition(fixture.nether), () -> true);
        assertTrue(plan.ready());
        // EntityType.canSpawn checks destination difficulty at construction; chicken is allowed,
        // Ghast is not. The native parent factory is reached after child recursion.
        fixture.levelData = data(Difficulty.PEACEFUL);
        FullDecisionWitnessSupport.set(fixture.nether, ServerLevel.class, "serverLevelData", fixture.levelData);
        assertFalse(EntityTypes.GHAST.canSpawn(fixture.nether));
        assertTrue(EntityTypes.CHICKEN.canSpawn(fixture.nether));
        assertNull(plan.invoke(), "Peaceful Ghast factory unexpectedly created the parent");
        var continuation = boundary.authorizeUnchangedSourceContinuation(tokens.get(ghast),
            tokens.get(ghast).source());
        boundary.settle(continuation);
        plan.settleChildren();
        plan.settleRoot(null);
        Entity parentSource = fixture.visible.get(fixture.overworld).get(ghast.getUUID());
        Entity childSource = fixture.visible.get(fixture.overworld).get(chicken.getUUID());
        Entity parentDestination = fixture.visible.get(fixture.nether).get(ghast.getUUID());
        Entity childDestination = fixture.visible.get(fixture.nether).get(chicken.getUUID());
        var graph = graphHolder[0];
        var parentBefore = graph.member(ghast).binding();
        var childBefore = graph.member(chicken).binding();
        var parentNow = fixture.objects.current(ghast.getUUID().toString()).orElse(null);
        var childNow = fixture.objects.current(chicken.getUUID().toString()).orElse(null);
        var effects = fixture.order.stream().filter(NATIVE_EFFECTS::contains).toList();
        var beforeCleanup = shape(fixture);
        // Repeated cleanup after settlement must be inert: no cancellation, no state change.
        plan.cancelUnsettled();
        boolean cancelled = boundary.cancel(tokens.get(chicken)) | boundary.cancel(tokens.get(ghast));
        boolean disturbed = cancelled || !beforeCleanup.equals(shape(fixture));
        return new Observation(parentSource == ghast, childSource == chicken,
            parentDestination != null, childDestination != null,
            childSource != null && childSource.getVehicle() == parentSource,
            childDestination != null && parentDestination != null
                && childDestination.getVehicle() == parentDestination,
            Collections.frequency(fixture.order, "source-retire"),
            Collections.frequency(fixture.order, "insert"),
            Collections.frequency(fixture.order, "post"),
            boundary.pendingCount(), fixture.objects.liveCount(),
            effects, ghast.getPassengers().isEmpty(), ghast.getVehicle() != null,
            childDestination != null && childDestination.getVehicle() != null,
            childDestination == null || childDestination.getPassengers().isEmpty(),
            parentSource == ghast && parentDestination == null,
            childDestination != null && childSource == null,
            parentNow != null && parentNow.equals(parentBefore),
            childNow != null && childNow.incarnation() > childBefore.incarnation()
                && childNow.routingKey().world().equals(fixture.to.world()),
            chicken.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION && !ghast.isRemoved(),
            disturbed);
    }

    private static final java.util.Set<String> NATIVE_EFFECTS =
        java.util.Set.of("source-retire", "insert", "post", "destination-cleanup", "merge");

    private static java.util.List<Object> shape(NativeItemPortalTransferTest.Fixture fixture) {
        return java.util.List.of(fixture.visible.get(fixture.overworld).keySet(),
            fixture.visible.get(fixture.nether).keySet(), fixture.objects.liveCount(),
            java.util.List.copyOf(fixture.order));
    }

    private static ServerLevelData data(Difficulty difficulty) {
        return (ServerLevelData) Proxy.newProxyInstance(
            NativePortalGraphParentNullControlTest.class.getClassLoader(),
            new Class<?>[]{ServerLevelData.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getLevelName" -> "graph-parent-null-control";
                case "getDifficulty" -> difficulty;
                default -> null;
            });
    }

    private record Observation(boolean sourceParent, boolean sourceChild,
            boolean destinationParent, boolean destinationChild,
            boolean originalEdge, boolean destinationEdge,
            int sourceRetirements, int destinationInsertions, int postCallbacks,
            int pendingBoundaryCount, int livePublicationCount,
            java.util.List<String> nativeEffects, boolean parentPassengersEmpty,
            boolean parentHasVehicle, boolean destinationChildHasVehicle,
            boolean destinationChildPassengersEmpty, boolean parentUniquelyInSource,
            boolean childUniquelyInDestination, boolean parentPublicationUnchanged,
            boolean childPublicationReplaced, boolean sourceChildRetiredAsChangedDimension,
            boolean cleanupDisturbedState) { }
}
