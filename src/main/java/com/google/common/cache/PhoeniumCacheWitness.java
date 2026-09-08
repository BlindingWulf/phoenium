package com.google.common.cache;

/** A non-mutating exact lookup for the pinned Guava LocalCache implementation.
 * It follows the same rehashed bucket and key equivalence as LocalCache.get, but
 * does not record recency, update statistics, drain queues, wait, or invoke a loader. */
public final class PhoeniumCacheWitness {
    public enum State { ABSENT, LOADING, PRESENT }
    public record Result<V>(State state, V value) { }

    private PhoeniumCacheWitness() { }

    public static <K, V> Result<V> peek(LoadingCache<K, V> cache, K key) {
        if (!(cache instanceof LocalCache.LocalLoadingCache<K, V> loading))
            throw new IllegalArgumentException("unsupported Guava loading cache implementation: " + cache.getClass().getName());
        LocalCache<K, V> map = loading.localCache;
        int hash = map.hash(key);
        LocalCache.Segment<K, V> segment = map.segmentFor(hash);
        var table = segment.table;
        for (ReferenceEntry<K, V> entry = table.get(hash & table.length() - 1);
             entry != null; entry = entry.getNext()) {
            if (entry.getHash() != hash) continue;
            K stored = entry.getKey();
            if (stored == null || !map.keyEquivalence.equivalent(key, stored)) continue;
            LocalCache.ValueReference<K, V> reference = entry.getValueReference();
            if (reference.isLoading()) return new Result<>(State.LOADING, null);
            V value = reference.get();
            return value == null ? new Result<>(State.ABSENT, null) : new Result<>(State.PRESENT, value);
        }
        return new Result<>(State.ABSENT, null);
    }
}
