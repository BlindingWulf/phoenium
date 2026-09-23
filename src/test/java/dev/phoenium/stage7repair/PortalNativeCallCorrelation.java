package dev.phoenium.stage7repair;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Test-only scope for the exact native teleport call made by Entity.handlePortal.
 * An identity snapshot is taken before native teleport ejects its passengers.
 */
final class PortalNativeCallCorrelation {
    enum Classification {
        OUTSIDE_PORTAL, RETAINED_ROOT, RETAINED_PASSENGER,
        UNRETAINED_ROOT, UNRETAINED_PASSENGER
    }

    private record Frame(Object root, IdentityHashMap<Object, UUID> graph) { }
    private final ThreadLocal<ArrayDeque<Frame>> calls = ThreadLocal.withInitial(ArrayDeque::new);

    <T> T within(Object root, Map<Object, UUID> graph, Supplier<T> original) {
        IdentityHashMap<Object, UUID> exact = new IdentityHashMap<>();
        exact.putAll(graph);
        if (!exact.containsKey(root)) throw new IllegalArgumentException("portal root missing from graph");
        ArrayDeque<Frame> stack = calls.get();
        stack.push(new Frame(root, exact));
        try {
            return original.get();
        } finally {
            stack.pop();
            if (stack.isEmpty()) calls.remove();
        }
    }

    Classification classify(Object entity, UUID uuid, boolean retainedIdentity) {
        ArrayDeque<Frame> stack = calls.get();
        if (stack.isEmpty()) {
            calls.remove();
            return Classification.OUTSIDE_PORTAL;
        }
        for (Frame frame : stack) {
            if (!uuid.equals(frame.graph().get(entity))) continue;
            if (frame.root() == entity)
                return retainedIdentity ? Classification.RETAINED_ROOT : Classification.UNRETAINED_ROOT;
            return retainedIdentity ? Classification.RETAINED_PASSENGER : Classification.UNRETAINED_PASSENGER;
        }
        return Classification.OUTSIDE_PORTAL;
    }

    int depth() {
        return calls.get().size();
    }
}
