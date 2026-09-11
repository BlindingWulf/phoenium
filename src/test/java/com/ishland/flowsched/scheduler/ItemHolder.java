package com.ishland.flowsched.scheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.IdentityHashMap;
import java.util.Map;

/** Test-only provider shape for C2ME generation-lineage reflection. */
public final class ItemHolder {
    private final Object key;
    private final AtomicReference<Object> userData;
    private final AtomicReference<Object> item = new AtomicReference<>();
    private final Map<ItemStatus, CompletableFuture<Void>> slots = new IdentityHashMap<>();

    public ItemHolder(Object key, Object userData) {
        this.key = key; this.userData = new AtomicReference<>(userData);
    }
    public Object getKey() { return key; }
    public synchronized CompletableFuture<Void> getFutureForStatus0(ItemStatus status) {
        return slots.computeIfAbsent(status, ignored -> new CompletableFuture<>());
    }
    public AtomicReference<Object> getUserData() { return userData; }
    public AtomicReference<Object> getItem() { return item; }
    public CompletableFuture<Void> slot() {
        return getFutureForStatus0(com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus.GENERATION);
    }
    public synchronized void replaceSlot(CompletableFuture<Void> replacement) {
        slots.put(com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus.GENERATION, replacement);
    }
    public synchronized CompletableFuture<Void> slot(ItemStatus status) { return getFutureForStatus0(status); }
    public synchronized void replaceSlot(ItemStatus status, CompletableFuture<Void> replacement) { slots.put(status, replacement); }
    public void setItem(Object value) { item.set(value); }
}
