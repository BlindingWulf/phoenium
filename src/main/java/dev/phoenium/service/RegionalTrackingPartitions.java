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
    /** S9-D8: records per identity, so a put or removal whose identity is unique can update the indexes in place. */
    private final IdentityHashMap<Object, Integer> identityCounts = new IdentityHashMap<>();
    /** S9-D8: values() in source order, rebuilt only after a membership, order or value change (update() keeps values). */
    private List<T> values;
    /** S9-D18: every record's value by the 4x4-chunk cell of its key and by id, kept with each membership and key
     * change (a service's keys all name its one world), so a move can visit only the records near its player. */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<T>> cells =
        new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static long cell(int chunkX, int chunkZ) { return ((long) (chunkX >> 2) << 32) | ((chunkZ >> 2) & 0xFFFFFFFFL); }
    private void cellAdd(Item<T> item) {
        long key = cell(item.key.x(), item.key.z());
        var bucket = cells.get(key);
        if (bucket == null) cells.put(key, bucket = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
        bucket.put(item.id, item.value);
    }
    private void cellRemove(Item<T> item) {
        long key = cell(item.key.x(), item.key.z());
        var bucket = cells.get(key);
        if (bucket == null || bucket.remove(item.id) == null) throw new IllegalStateException("tracker cell index lost " + item.id);
        if (bucket.isEmpty()) cells.remove(key);
    }
    /** S9-D18: every record whose key is within {@code radius} chunks of (x, z) on both axes, and possibly others from the
     * same cells (the caller applies its own exact distance). */
    void forEachNear(int x, int z, int radius, java.util.function.Consumer<T> output) {
        for (int cx = (x - radius) >> 2, maxX = (x + radius) >> 2; cx <= maxX; cx++)
            for (int cz = (z - radius) >> 2, maxZ = (z + radius) >> 2; cz <= maxZ; cz++) {
                var bucket = cells.get(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                if (bucket != null) bucket.values().forEach(output);
            }
    }

    T value(int id) { Item<T> item = ordered.get(id); return item == null ? null : item.value; }
    List<T> values() {
        List<T> current = values;
        if (current == null) {
            var built = new ArrayList<T>(ordered.size());
            for (Item<T> item : ordered.values()) built.add(item.value);
            values = current = java.util.Collections.unmodifiableList(built);
        }
        return current;
    }
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
        ordered.put(item.id, item);
        // S9-D8: a new id is appended last in source order; with a new identity it is also its identity's first
        // record, so rebuild() would add exactly this item to its partition and identity index.
        if (previous == null && !identityCounts.containsKey(item.identity)) {
            partitions.computeIfAbsent(item.owner, ignored -> new LinkedHashMap<>()).put(item.id, item);
            byIdentity.put(item.identity, item.value);
            identityCounts.put(item.identity, 1);
            cellAdd(item);
            values = null;
            return;
        }
        rebuild();
    }

    void reorder(List<Identity> sourceOrder) {
        if (sourceOrder.size() != ordered.size()) throw new IllegalStateException("tracker source order size changed");
        // S9-D13: a source order that is already the retained order changes nothing (each record still validated)
        boolean same = true;
        var retained = ordered.values().iterator();
        for (Identity identity : sourceOrder) {
            Item<T> current = retained.next();
            if (current.id != identity.id) { same = false; break; }
            if (current.identity != identity.identity)
                throw new IllegalStateException("tracker source order identity changed " + identity.id);
        }
        if (same) return;
        var replacement = new LinkedHashMap<Integer, Item<T>>();
        for (Identity identity : sourceOrder) {
            Item<T> item = ordered.get(identity.id);
            if (item == null || item.identity != identity.identity)
                throw new IllegalStateException("tracker source order identity changed " + identity.id);
            if (replacement.put(identity.id, item) != null)
                throw new IllegalStateException("duplicate tracker source order id " + identity.id);
        }
        ordered.clear(); ordered.putAll(replacement);
        values = null;
        // S9-D13: a reorder keeps every record, so the partitions (read only by their count) and the identity counts
        // are unchanged; the identity index depends on order only through an identity that several records share.
        if (identityCounts.size() != ordered.size()) rebuild();
    }

    T remove(int id, Object expectedIdentity) {
        Item<T> previous = ordered.get(id);
        if (previous != null && previous.identity != expectedIdentity)
            throw new IllegalStateException("tracker removal names displaced identity " + id);
        // S9-D8: nothing removed leaves the indexes as rebuild() derives them; the sole record of an identity
        // leaves its partition and the identity index. Shared identities still rebuild.
        if (previous == null) return null;
        ordered.remove(id);
        values = null;
        Integer count = identityCounts.get(previous.identity);
        LinkedHashMap<Integer, Item<T>> partition = partitions.get(previous.owner);
        if (count != null && count == 1 && partition != null && partition.get(id) == previous) {
            partition.remove(id);
            if (partition.isEmpty()) partitions.remove(previous.owner);
            byIdentity.remove(previous.identity);
            identityCounts.remove(previous.identity);
            cellRemove(previous);
            return previous.value;
        }
        rebuild();
        return previous.value;
    }

    void update(int id, RoutingKey key, RegionId owner) {
        Item<T> previous = Objects.requireNonNull(ordered.get(id), "missing retained tracker " + id);
        if (previous.key.equals(key) && previous.owner.equals(owner)) return;
        LinkedHashMap<Integer, Item<T>> oldPartition = partitions.get(previous.owner);
        if (oldPartition == null || oldPartition.remove(id) != previous)
            throw new IllegalStateException("tracker owner partition lost " + id);
        if (oldPartition.isEmpty()) partitions.remove(previous.owner);
        Item<T> next = new Item<>(id, previous.identity, previous.value, key, owner);
        ordered.put(id, next);
        partitions.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(id, next);
        // S9-D18: a record keeps its cell while its key stays in it (the cell holds the unchanged value)
        if (cell(previous.key.x(), previous.key.z()) != cell(key.x(), key.z())) { cellRemove(previous); cellAdd(next); }
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
        identityCounts.clear();
        cells.clear();
        values = null;
        ordered.forEach((id, item) -> {
            partitions.computeIfAbsent(item.owner, ignored -> new LinkedHashMap<>()).put(id, item);
            byIdentity.putIfAbsent(item.identity, item.value);
            identityCounts.merge(item.identity, 1, Integer::sum);
            cellAdd(item);
        });
    }
}
