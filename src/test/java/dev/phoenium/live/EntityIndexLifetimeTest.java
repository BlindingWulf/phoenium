package dev.phoenium.live;

import dev.phoenium.core.*;
import dev.phoenium.interaction.InteractionTargetIndex;
import dev.phoenium.ownership.*;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityIndexLifetimeTest {
    @Test void endedServerDropsHiddenRecordsAndCannotResurrectRetainedIdentities() {
        var world = new WorldId("ended-server");
        var region = new RegionId(world, "owner");
        var generation = new TopologyGeneration(1);
        var directory = new RegionDirectory();
        directory.publish(generation, Map.of(), Map.of(world, new RegionRoute(region, generation, new OwnerEpoch(0))));
        var objects = new ObjectOwnershipRegistry(directory);
        var index = new InteractionTargetIndex();
        var adapter = new EntityIndexLifecycleAdapter(objects, directory, index);
        var key = new RoutingKey(world, 7, 0);
        var object = adapter.addressable("persisted-hidden", OwnedObjectKind.ENTITY, world, 17, key, false);
        var retained = index.require(world, 17);
        adapter.close(); adapter.close();
        assertEquals(0, objects.liveCount());
        assertTrue(objects.current(object.objectId()).isEmpty());
        assertNull(adapter.entityId(object.objectId()));
        assertFalse(index.isCurrent(retained));
        assertThrows(IllegalStateException.class, () -> adapter.addressable(object.objectId(), OwnedObjectKind.ENTITY, world, 17, key, false));
        assertThrows(IllegalStateException.class, () -> index.publish(world, 17, object));
    }
}
