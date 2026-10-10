package dev.phoenium.ownership;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** S9-D12: the fused current-plus-owner check answers as current() followed by requireCurrentOwner(). */
class ObjectOwnershipFusedCheckTest {
    private final WorldId world = new WorldId("minecraft:overworld");
    private final RegionId serial = new RegionId(world, "serial");
    private final RegionId other = new RegionId(world, "other");
    private final TopologyGeneration generation = new TopologyGeneration(1);
    private final RegionDirectory directory = new RegionDirectory();
    private final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
    private final RoutingKey key = new RoutingKey(world, 3, -2);

    ObjectOwnershipFusedCheckTest() {
        directory.publish(generation, Map.of(), Map.of(world, new RegionRoute(serial, generation, new OwnerEpoch(0))));
    }

    @Test
    void anAbsentObjectIsEmptyWithoutAnOwnerContext() {
        assertEquals(Optional.empty(), objects.requireCurrentOwnerIfPresent("absent", serial));
        assertEquals(Optional.empty(), objects.current("absent"));
    }

    @Test
    void aCurrentObjectUnderItsOwnerIsTheCurrentBinding() {
        objects.admit("mob", OwnedObjectKind.ENTITY, key);
        try (var owner = OwnershipContext.enter(serial, new LeaseEpoch(1), OperationCategory.OWNER_TASK)) {
            ObjectOwnership expected = objects.current("mob").orElseThrow();
            assertEquals(expected, objects.requireCurrentOwnerIfPresent("mob", serial).orElseThrow());
            assertEquals(expected, objects.requireCurrentOwner("mob", expected.objectEpoch(), serial));
        }
    }

    @Test
    void aForeignExecutingRegionOrAMissingContextFailsAsRequireCurrentOwnerDoes() {
        objects.admit("mob", OwnedObjectKind.ENTITY, key);
        ObjectOwnership binding = objects.current("mob").orElseThrow();
        var foreign = assertThrows(IllegalStateException.class, () -> objects.requireCurrentOwnerIfPresent("mob", other));
        var foreignOriginal = assertThrows(IllegalStateException.class,
            () -> objects.requireCurrentOwner("mob", binding.objectEpoch(), other));
        assertEquals(foreignOriginal.getMessage(), foreign.getMessage());
        var unowned = assertThrows(IllegalStateException.class, () -> objects.requireCurrentOwnerIfPresent("mob", serial));
        var unownedOriginal = assertThrows(IllegalStateException.class,
            () -> objects.requireCurrentOwner("mob", binding.objectEpoch(), serial));
        assertEquals(unownedOriginal.getMessage(), unowned.getMessage());
    }

    /** S9-D17: a retained answer stands only while no entry was written and the view is the same. */
    @Test
    void aRetainedOwnerAnswerStandsOnlyWhileEntriesAndViewAreUnchanged() {
        String mob = "mob";
        objects.admit(mob, OwnedObjectKind.ENTITY, key);
        try (var owner = OwnershipContext.enter(serial, new LeaseEpoch(1), OperationCategory.OWNER_TASK)) {
            var first = objects.requireCurrentOwner(mob, serial, null);
            assertEquals(objects.current(mob).orElseThrow(), first.binding());
            assertSame(first, objects.requireCurrentOwner(mob, serial, first));
            // the context check still runs on a retained answer
            assertThrows(IllegalStateException.class, () -> objects.requireCurrentOwner(mob, other, first));
            // any entry write retires the answer: another object's admission, then this object's removal
            objects.admit("other-mob", OwnedObjectKind.ENTITY, key);
            var second = objects.requireCurrentOwner(mob, serial, first);
            assertNotSame(first, second);
            assertEquals(first.binding(), second.binding());
            objects.remove(mob);
            assertNull(objects.requireCurrentOwner(mob, serial, second));
        }
        // outside any owner context a retained answer fails as the full check does
        objects.admit("third", OwnedObjectKind.ENTITY, key);
        ObjectOwnershipRegistry.OwnerAnswer[] held = new ObjectOwnershipRegistry.OwnerAnswer[1];
        try (var owner = OwnershipContext.enter(serial, new LeaseEpoch(2), OperationCategory.OWNER_TASK)) {
            held[0] = objects.requireCurrentOwner("third", serial, null);
        }
        assertThrows(IllegalStateException.class, () -> objects.requireCurrentOwner("third", serial, held[0]));
        // a new view whose default names another region is checked in full (the binding is no longer current)
        TopologyGeneration second = new TopologyGeneration(2);
        directory.publish(second, Map.of(), Map.of(world, new RegionRoute(other, second, new OwnerEpoch(1))));
        try (var owner = OwnershipContext.enter(serial, new LeaseEpoch(3), OperationCategory.OWNER_TASK)) {
            assertNull(objects.requireCurrentOwner("third", serial, held[0]));
        }
    }

    @Test
    void defaultResolutionFollowsEveryPublishedView() {
        assertEquals(serial, directory.resolve(key).orElseThrow().region());
        assertSame(directory.resolve(key).orElseThrow(), directory.resolve(new RoutingKey(world, -40, 7)).orElseThrow());
        assertEquals(Optional.empty(), directory.resolve(new RoutingKey(new WorldId("minecraft:the_end"), 0, 0)));
        // An explicit route disables the default for its world; republishing default-only restores it.
        TopologyGeneration second = new TopologyGeneration(2);
        RegionRoute explicit = new RegionRoute(other, second, new OwnerEpoch(1));
        directory.publish(second, Map.of(key, explicit), Map.of(world, new RegionRoute(serial, second, new OwnerEpoch(0))));
        assertEquals(explicit, directory.resolve(key).orElseThrow());
        assertEquals(Optional.empty(), directory.resolve(new RoutingKey(world, 9, 9)));
        TopologyGeneration third = new TopologyGeneration(3);
        RegionRoute restored = new RegionRoute(other, third, new OwnerEpoch(2));
        directory.publish(third, Map.of(), Map.of(world, restored));
        assertEquals(restored, directory.resolve(key).orElseThrow());
        assertEquals(restored, directory.resolve(new RoutingKey(world, 9, 9)).orElseThrow());
    }
}
