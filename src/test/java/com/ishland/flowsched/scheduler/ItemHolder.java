package com.ishland.flowsched.scheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only provider shape for C2ME generation-lineage reflection. */
public final class ItemHolder {
    private final Object key;
    private final AtomicReference<Object> userData;
    private volatile CompletableFuture<Void> slot = new CompletableFuture<>();

    public ItemHolder(Object key, Object userData) {
        this.key = key; this.userData = new AtomicReference<>(userData);
    }
    public Object getKey() { return key; }
    public CompletableFuture<Void> getFutureForStatus0(ItemStatus ignored) { return slot; }
    public AtomicReference<Object> getUserData() { return userData; }
    public CompletableFuture<Void> slot() { return slot; }
    public void replaceSlot(CompletableFuture<Void> replacement) { slot = replacement; }
}
