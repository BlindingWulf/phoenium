package dev.phoenium.live;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.LevelChunk;

/** Exact locked-C2ME adapter for the BLOCK_TICKING producer slot behind its public wrapper future. */
final class C2meReadinessSlot {
    static final String HOLDER = "com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface";
    private static final String STATUS = "com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus";
    private static final String ITEM_STATUS = "com.ishland.flowsched.scheduler.ItemStatus";

    private C2meReadinessSlot() { }

    record Observation(Object slot, CompletableFuture<ChunkResult<LevelChunk>> future) { }
    private record Cached(Object slot, CompletableFuture<ChunkResult<LevelChunk>> future) { }
    interface Access { Object identity(Object holder); }
    private static final Map<ChunkHolder, Cached> WRAPPERS = new WeakHashMap<>();
    private static final ReentrantLock CACHE_GATE = new ReentrantLock();
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            if (!isC2meType(type))
                throw new IllegalStateException("unexpected C2ME readiness holder " + type.getName());
            try {
                ClassLoader loader = type.getClassLoader();
                Class<?> statusType = Class.forName(STATUS, false, loader);
                Class<?> itemStatusType = Class.forName(ITEM_STATUS, false, loader);
                Method backing = type.getMethod("getBackingHolder");
                Method future = backing.getReturnType().getMethod("getFutureForStatus0", itemStatusType);
                Field blockTicking = statusType.getField("BLOCK_TICKING");
                if (!CompletableFuture.class.isAssignableFrom(future.getReturnType()))
                    throw new NoSuchMethodException("getFutureForStatus0 return type " + future.getReturnType().getName());
                return reflective(backing, future, blockTicking.get(null));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("pinned C2ME readiness-slot contract drifted", failure);
            }
        }
    };

    /** S9-D8: the hierarchy name walk once per holder class, not once per holder per readiness walk. */
    private static final ClassValue<Boolean> SUPPORTED = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) { return isC2meType(type); }
    };

    static boolean supports(ChunkHolder holder) {
        return SUPPORTED.get(holder.getClass());
    }

    private static boolean isC2meType(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            if (current.getName().equals(HOLDER)) return true;
        return false;
    }

    static Object identity(ChunkHolder holder) { return ACCESS.get(holder.getClass()).identity(holder); }

    static Observation capture(ChunkHolder holder) {
        for (;;) {
            Object before = identity(holder);
            CACHE_GATE.lock();
            try {
                Cached cached = WRAPPERS.get(holder);
                if (cached != null && cached.slot == before) return new Observation(before, cached.future);
            } finally { CACHE_GATE.unlock(); }
            CompletableFuture<ChunkResult<LevelChunk>> future = holder.getTickingChunkFuture();
            Object after = identity(holder);
            if (before != after) continue;
            CACHE_GATE.lock();
            try { WRAPPERS.put(holder, new Cached(after, future)); }
            finally { CACHE_GATE.unlock(); }
            return new Observation(after, future);
        }
    }

    static Access reflective(Method backing, Method future, Object blockTicking) {
        return holder -> {
            try {
                Object backingHolder = backing.invoke(holder);
                Object slot = future.invoke(backingHolder, blockTicking);
                if (!(slot instanceof CompletableFuture<?>))
                    throw new IllegalStateException("C2ME BLOCK_TICKING slot is not a CompletableFuture");
                return slot;
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("cannot access pinned C2ME readiness slot", failure);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("C2ME readiness-slot lookup failed", cause);
            }
        };
    }
}
