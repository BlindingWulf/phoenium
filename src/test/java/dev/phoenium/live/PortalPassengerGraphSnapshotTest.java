package dev.phoenium.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalPassengerGraphSnapshotTest {
    private static final class Node {
        final String name;
        final List<Node> children = new ArrayList<>();
        Node(String name, Node... direct) { this.name = name; children.addAll(List.of(direct)); }
        // Identity, not equality of a displayed name, defines a native graph member.
        @Override public boolean equals(Object other) { return other instanceof Node node && name.equals(node.name); }
        @Override public int hashCode() { return name.hashCode(); }
    }

    @Test void nestedGraphCapturesNativePassengerAndRootOrder() {
        Node grandchild = new Node("grandchild");
        Node first = new Node("first", grandchild);
        Node second = new Node("second");
        Node root = new Node("root", first, second);
        var graph = PortalPassengerGraphSnapshot.Topology.capture(root, node -> node.children);
        assertEquals(List.of(root, first, grandchild, second), graph.preorder());
        assertEquals(List.of(grandchild, first, second, root), graph.postorder());
        assertSame(root, graph.parent(first));
        assertSame(first, graph.parent(grandchild));
        assertNull(graph.parent(root));
        graph.validate(node -> node.children);
    }

    @Test void duplicateAliasCycleAndChangedOrderFailClosed() {
        Node sameNameA = new Node("same");
        Node sameNameB = new Node("same");
        Node root = new Node("root", sameNameA, sameNameB);
        var graph = PortalPassengerGraphSnapshot.Topology.capture(root, node -> node.children);
        assertSame(sameNameA, graph.children(root).get(0));
        assertSame(sameNameB, graph.children(root).get(1));
        root.children.set(0, sameNameB);
        root.children.set(1, sameNameA);
        assertThrows(IllegalStateException.class, () -> graph.validate(node -> node.children));

        Node duplicate = new Node("duplicate");
        assertThrows(IllegalStateException.class,
            () -> PortalPassengerGraphSnapshot.Topology.capture(new Node("root", duplicate, duplicate), node -> node.children));
        Node cycle = new Node("cycle");
        cycle.children.add(cycle);
        assertThrows(IllegalStateException.class,
            () -> PortalPassengerGraphSnapshot.Topology.capture(cycle, node -> node.children));
    }

    @Test void passengerPositionRotationAndFlagsMatchPinnedNativeFormula() {
        var rootTransition = new TeleportTransition((ServerLevel) null, new Vec3(100, 70, -50),
            new Vec3(.1, .2, .3), 90, 15, Set.of(), TeleportTransition.DO_NOTHING);
        var placement = PortalPassengerGraphSnapshot.calculatePassengerPlacement(rootTransition,
            new Vec3(10, 65, 10), 30, -20, new Vec3(12, 66, 9), 60, 5);
        assertEquals(new Vec3(102, 71, -51), placement.position());
        assertEquals(120, placement.yRot());
        assertEquals(40, placement.xRot());

        var relative = new TeleportTransition((ServerLevel) null, new Vec3(100, 70, -50),
            Vec3.ZERO, 90, 15, Set.of(Relative.X, Relative.Y_ROT, Relative.X_ROT),
            TeleportTransition.DO_NOTHING);
        var relativePlacement = PortalPassengerGraphSnapshot.calculatePassengerPlacement(relative,
            new Vec3(10, 65, 10), 30, -20, new Vec3(12, 66, 9), 60, 5);
        assertEquals(new Vec3(100, 71, -51), relativePlacement.position());
        assertEquals(90, relativePlacement.yRot());
        assertEquals(15, relativePlacement.xRot());
    }
}
