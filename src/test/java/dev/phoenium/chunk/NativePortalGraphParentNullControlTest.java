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

    /** Run with PHOENIUM_GRAPH_STRICT=true for the known red no-orphan campaign gate. */
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
        return new Observation(parentSource == ghast, childSource == chicken,
            parentDestination != null, childDestination != null,
            childSource != null && childSource.getVehicle() == parentSource,
            childDestination != null && parentDestination != null
                && childDestination.getVehicle() == parentDestination,
            Collections.frequency(fixture.order, "source-retire"),
            Collections.frequency(fixture.order, "insert"),
            Collections.frequency(fixture.order, "post"),
            boundary.pendingCount(), fixture.objects.liveCount());
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
            int pendingBoundaryCount, int livePublicationCount) { }
}
