package dev.phoenium.service;

import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Small owner-free metadata index used by the live tracker adapter. Values are exact retained
 * tracker records; this class never executes a callback or grants access to their aliases. */
final class RegionalTrackingPartitions<T> {
    record Item<T>(int id, Object identity, T value, RoutingKey key, RegionId owner) {
        Item { Objects.requireNonNull(identity); Objects.requireNonNull(value); Objects.requireNonNull(key); Objects.requireNonNull(owner); }
    }
    record Plan(long generation, Map<Integer, RegionId> owners) {
        Plan { owners = Map.copyOf(owners); }
    }
    record Identity(int id, Object identity) { Identity { Objects.requireNonNull(identity); } }
    private final LinkedHashMap<Integer, Item<T>> ordered = new LinkedHashMap<>();
    private final LinkedHashMap<RegionId, LinkedHashMap<Integer, Item<T>>> partitions = new LinkedHashMap<>();
    /** Exact tracker identity -> retained value, rebuilt with the partitions. The first record in source order wins,
     * which is what a findFirst() scan of values() by identity returned before this index existed. */
    private final IdentityHashMap<Object, T> byIdentity = new IdentityHashMap<>();

    T value(int id) { Item<T> item = ordered.get(id); return item == null ? null : item.value; }
    List<T> values() { return ordered.values().stream().map(Item::value).toList(); }
    /** O(1) equivalent of the first value in source order whose record identity is exactly {@code identity}. */
    T valueByIdentity(Object identity) { return byIdentity.get(identity); }
    int size() { return ordered.size(); }
    int partitionCount() { return partitions.size(); }

    void replaceAll(List<Item<T>> next) {
        var replacement = new LinkedHashMap<Integer, Item<T>>();
        for (Item<T> item : next) {
            Item<T> previous = ordered.get(item.id);
            if (previous != null && previous.identity != item.identity)
                throw new IllegalStateException("tracker identity changed without removal " + item.id);
            if (replacement.put(item.id, item) != null)
                throw new IllegalStateException("duplicate tracker id " + item.id);
        }
        ordered.clear(); ordered.putAll(replacement); rebuild();
    }

    void put(Item<T> item) {
        Item<T> previous = ordered.get(item.id);
        if (previous != null && previous.identity != item.identity)
            throw new IllegalStateException("tracker insertion displaced retained identity " + item.id);
        ordered.put(item.id, item); rebuild();
    }

    void reorder(List<Identity> sourceOrder) {
        if (sourceOrder.size() != ordered.size()) throw new IllegalStateException("tracker source order size changed");
        var replacement = new LinkedHashMap<Integer, Item<T>>();
        for (Identity identity : sourceOrder) {
            Item<T> item = ordered.get(identity.id);
            if (item == null || item.identity != identity.identity)
                throw new IllegalStateException("tracker source order identity changed " + identity.id);
            if (replacement.put(identity.id, item) != null)
                throw new IllegalStateException("duplicate tracker source order id " + identity.id);
        }
        ordered.clear(); ordered.putAll(replacement); rebuild();
    }

    T remove(int id, Object expectedIdentity) {
        Item<T> previous = ordered.get(id);
        if (previous != null && previous.identity != expectedIdentity)
            throw new IllegalStateException("tracker removal names displaced identity " + id);
        if (previous != null) ordered.remove(id);
        rebuild();
        return previous == null ? null : previous.value;
    }

    void update(int id, RoutingKey key, RegionId owner) {
        Item<T> previous = Objects.requireNonNull(ordered.get(id), "missing retained tracker " + id);
        if (previous.key.equals(key) && previous.owner.equals(owner)) return;
        LinkedHashMap<Integer, Item<T>> oldPartition = partitions.get(previous.owner);
        if (oldPartition == null || oldPartition.remove(id) != previous)
            throw new IllegalStateException("tracker owner partition lost " + id);
        if (oldPartition.isEmpty()) partitions.remove(previous.owner);
        ordered.put(id, new Item<>(id, previous.identity, previous.value, key, owner));
        partitions.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(id, ordered.get(id));
    }

    Plan plan(long generation, Function<RoutingKey, RegionId> resolver) {
        var owners = new LinkedHashMap<Integer, RegionId>();
        ordered.forEach((id, item) -> owners.put(id, Objects.requireNonNull(resolver.apply(item.key))));
        return new Plan(generation, owners);
    }

    long install(Plan plan) {
        if (!plan.owners.keySet().equals(ordered.keySet()))
            throw new IllegalStateException("tracker migration plan changed");
        long moved = 0;
        var next = new ArrayList<Item<T>>(ordered.size());
        for (Item<T> item : ordered.values()) {
            RegionId owner = Objects.requireNonNull(plan.owners.get(item.id));
            if (!owner.equals(item.owner)) moved++;
            next.add(new Item<>(item.id, item.identity, item.value, item.key, owner));
        }
        ordered.clear();
        for (Item<T> item : next) ordered.put(item.id, item);
        rebuild();
        return moved;
    }

    private void rebuild() {
        partitions.clear();
        byIdentity.clear();
        ordered.forEach((id, item) -> {
            partitions.computeIfAbsent(item.owner, ignored -> new LinkedHashMap<>()).put(id, item);
            byIdentity.putIfAbsent(item.identity, item.value);
        });
    }
}
