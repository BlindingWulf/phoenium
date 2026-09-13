package dev.phoenium.chunk;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Fail-closed reflection adapter for the locked C2ME FULL and chunk-sending provider lineage.
 * Provider classes remain optional and are never linked from Phoenium's production classes.
 */
final class C2meFullAccess {
    static final String CONTEXT = "com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext";
    static final String SERVER_ACCESSIBLE = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessible";
    static final String SERVER_ACCESSIBLE_SENDING =
            "com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessibleChunkSending";
    private static final String STATUS = "com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus";
    private static final String ITEM_STATUS = "com.ishland.flowsched.scheduler.ItemStatus";
    private static final String STATE = "com.ishland.c2me.rewrites.chunksystem.common.ChunkState";
    private static final String CONFIG = "com.ishland.c2me.rewrites.chunksystem.common.Config";

    enum Kind {
        FULL("SERVER_ACCESSIBLE"),
        SEND("SERVER_ACCESSIBLE_CHUNK_SENDING");
        final String field;
        Kind(String field) { this.field = field; }
    }

    record FullState(Object exactState, LevelChunk chunk, ProtoChunk protoChunk, boolean wasFullChunk) {
        FullState {
            Objects.requireNonNull(exactState); Objects.requireNonNull(chunk); Objects.requireNonNull(protoChunk);
        }
    }

    record Observation(Object context, ChunkMap map, Object system, Object backingHolder,
                       Object providerStatus, Object statusSlot, GenerationChunkHolder userDataHolder,
                       ChunkPos position, Kind kind, ContextAccess access) {
        Observation {
            Objects.requireNonNull(context); Objects.requireNonNull(map); Objects.requireNonNull(system);
            Objects.requireNonNull(backingHolder); Objects.requireNonNull(providerStatus); Objects.requireNonNull(statusSlot);
            Objects.requireNonNull(userDataHolder); Objects.requireNonNull(position); Objects.requireNonNull(kind);
            Objects.requireNonNull(access);
        }

        boolean stillCurrent() {
            Parts current = access.read(context, kind);
            return current.map == map && current.system == system && current.backingHolder == backingHolder
                    && current.providerStatus == providerStatus && current.statusSlot == statusSlot
                    && current.userDataHolder == userDataHolder && current.position.equals(position);
        }

        ChunkAccess requireProtoChunk() {
            Parts current = access.read(context, kind);
            requireLineage(current);
            StateParts state = STATE_ACCESS.get(current.itemState.getClass()).read(current.itemState);
            if (!(state.chunk instanceof ProtoChunk proto))
                throw new ChunkProtocolException("C2ME SERVER_ACCESSIBLE input is not a ProtoChunk");
            return proto;
        }

        FullState requireFullState() {
            Parts current = access.read(context, kind);
            requireLineage(current);
            StateParts state = STATE_ACCESS.get(current.itemState.getClass()).read(current.itemState);
            if (!(state.chunk instanceof LevelChunk levelChunk) || state.protoChunk == null || state.reachedStatus != ChunkStatus.FULL)
                throw new ChunkProtocolException("C2ME FULL state was not published into the provider holder");
            return new FullState(current.itemState, levelChunk, state.protoChunk, state.wasFullChunk);
        }

        void requireFullState(FullState expected) {
            Parts current = access.read(context, kind);
            requireLineage(current);
            if (current.itemState != expected.exactState())
                throw new ChunkProtocolException("C2ME FULL state identity changed before acknowledgement");
            StateParts state = STATE_ACCESS.get(current.itemState.getClass()).read(current.itemState);
            if (state.chunk != expected.chunk() || state.protoChunk != expected.protoChunk()
                    || state.reachedStatus != ChunkStatus.FULL || state.wasFullChunk != expected.wasFullChunk())
                throw new ChunkProtocolException("C2ME FULL state contents changed before acknowledgement");
        }

        void requirePublication(FullState expected) {
            requireFullState(expected);
            Object published = PUBLICATION_ACCESS.get(map.getClass()).published(map, position.pack());
            if (published != userDataHolder)
                throw new ChunkProtocolException("C2ME currentChunkHolders publication does not match provider userData");
        }

        boolean delayFullEvents() { return access.delayFullEvents(); }

        boolean sameExactLineage(Observation other) {
            return other != null && other.context == context && other.map == map && other.system == system
                    && other.backingHolder == backingHolder && other.providerStatus == providerStatus
                    && other.statusSlot == statusSlot && other.userDataHolder == userDataHolder
                    && other.position.equals(position) && other.kind == kind;
        }

        boolean sameTarget(Observation other) {
            return other != null && other.map == map && other.userDataHolder == userDataHolder
                    && other.position.equals(position) && other.kind == kind;
        }

        private void requireLineage(Parts current) {
            if (current.map != map || current.system != system || current.backingHolder != backingHolder
                    || current.providerStatus != providerStatus || current.statusSlot != statusSlot
                    || current.userDataHolder != userDataHolder || !current.position.equals(position))
                throw new ChunkProtocolException("C2ME provider FULL lineage changed");
        }
    }

    private record Parts(ChunkMap map, Object system, Object backingHolder, Object providerStatus,
                         Object statusSlot, GenerationChunkHolder userDataHolder, ChunkPos position, Object itemState) { }
    private record StateParts(ChunkAccess chunk, ProtoChunk protoChunk, ChunkStatus reachedStatus, boolean wasFullChunk) { }
    private interface ContextAccess { Parts read(Object context, Kind kind); boolean delayFullEvents(); }
    private interface StateAccess { StateParts read(Object state); }
    private interface PublicationAccess { Object published(ChunkMap map, long packed); }

    private static final ClassValue<ContextAccess> CONTEXT_ACCESS = new ClassValue<>() {
        @Override protected ContextAccess computeValue(Class<?> type) {
            if (!isNamedType(type, CONTEXT)) throw drift(new NoSuchMethodException("unexpected context " + type.getName()));
            try {
                ClassLoader loader = type.getClassLoader();
                Class<?> providerStatus = Class.forName(STATUS, false, loader);
                Class<?> itemStatus = Class.forName(ITEM_STATUS, false, loader);
                Class<?> config = Class.forName(CONFIG, false, loader);
                Method holder = type.getMethod("holder");
                Method tacs = type.getMethod("tacs");
                Method system = type.getMethod("theChunkSystem");
                if (!ChunkMap.class.isAssignableFrom(tacs.getReturnType()))
                    throw new NoSuchMethodException("tacs return type " + tacs.getReturnType().getName());
                Class<?> holderType = holder.getReturnType();
                Method getKey = holderType.getMethod("getKey");
                Method getFuture = holderType.getMethod("getFutureForStatus0", itemStatus);
                Method getUserData = holderType.getMethod("getUserData");
                Method getItem = holderType.getMethod("getItem");
                if (!CompletableFuture.class.isAssignableFrom(getFuture.getReturnType())
                        || !AtomicReference.class.isAssignableFrom(getUserData.getReturnType())
                        || !AtomicReference.class.isAssignableFrom(getItem.getReturnType()))
                    throw new NoSuchMethodException("C2ME ItemHolder accessor shape changed");
                Method getSystemHolder = findSingleArgumentMethod(system.getReturnType(), "getHolder");
                Field accessible = providerStatus.getField(Kind.FULL.field);
                Field sending = providerStatus.getField(Kind.SEND.field);
                Field delayed = config.getField("delayFullChunkEvents");
                if (delayed.getType() != boolean.class) throw new NoSuchFieldException("delayFullChunkEvents type changed");
                return reflective(holder, tacs, system, getKey, getFuture, getUserData, getItem,
                        getSystemHolder, accessible, sending, delayed);
            } catch (ReflectiveOperationException failure) { throw drift(failure); }
        }
    };

    private static final ClassValue<StateAccess> STATE_ACCESS = new ClassValue<>() {
        @Override protected StateAccess computeValue(Class<?> type) {
            if (!isNamedType(type, STATE)) throw drift(new NoSuchMethodException("unexpected state " + type.getName()));
            try {
                Method chunk = type.getMethod("chunk");
                Method proto = type.getMethod("protoChunk");
                Method reached = type.getMethod("reachedStatus");
                Method wasFull = type.getMethod("wasFullChunk");
                return state -> {
                    try {
                        Object c = chunk.invoke(state), p = proto.invoke(state), r = reached.invoke(state), w = wasFull.invoke(state);
                        if (!(c instanceof ChunkAccess access) || !(p instanceof ProtoChunk protoChunk)
                                || !(r instanceof ChunkStatus status) || !(w instanceof Boolean flag))
                            throw new ChunkProtocolException("C2ME ChunkState accessor shape changed");
                        return new StateParts(access, protoChunk, status, flag);
                    } catch (InvocationTargetException failure) {
                        throw drift(failure.getCause() == null ? failure : failure.getCause());
                    } catch (ReflectiveOperationException failure) { throw drift(failure); }
                };
            } catch (ReflectiveOperationException failure) { throw drift(failure); }
        }
    };

    private static final ClassValue<PublicationAccess> PUBLICATION_ACCESS = new ClassValue<>() {
        @Override protected PublicationAccess computeValue(Class<?> type) {
            try {
                Method current = findZeroArgumentMethod(type, "getCurrentChunkHolders");
                return (map, packed) -> {
                    try {
                        Object holders = current.invoke(map);
                        if (holders == null) throw new ChunkProtocolException("C2ME currentChunkHolders is null");
                        Method get = findLongGet(holders.getClass());
                        return get.invoke(holders, packed);
                    } catch (InvocationTargetException failure) {
                        throw drift(failure.getCause() == null ? failure : failure.getCause());
                    } catch (ReflectiveOperationException failure) { throw drift(failure); }
                };
            } catch (ReflectiveOperationException failure) { throw drift(failure); }
        }
    };

    private C2meFullAccess() { }

    static Observation capture(Object context, Kind kind) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(kind, "kind");
        ContextAccess access = CONTEXT_ACCESS.get(context.getClass());
        Parts parts = access.read(context, kind);
        return new Observation(context, parts.map, parts.system, parts.backingHolder, parts.providerStatus,
                parts.statusSlot, parts.userDataHolder, parts.position, kind, access);
    }

    /**
     * Pins the notickvd SEND_READY Rx graph to fromRunnable(...).subscribeOn(exactScheduler) without
     * linking optional RxJava classes into Phoenium. The original provider Runnable is the only node rebound.
     */
    static Object rebindSendReadyGraph(Object providerGraph, java.util.function.BiFunction<Runnable, Object, Object> binder) {
        Objects.requireNonNull(providerGraph, "providerGraph"); Objects.requireNonNull(binder, "binder");
        Class<?> outer = providerGraph.getClass();
        if (!outer.getName().equals("io.reactivex.rxjava3.internal.operators.completable.CompletableSubscribeOn"))
            throw sendDrift(new IllegalStateException("unexpected outer graph " + outer.getName()));
        try {
            Field sourceField = outer.getDeclaredField("source");
            Field schedulerField = outer.getDeclaredField("scheduler");
            sourceField.setAccessible(true); schedulerField.setAccessible(true);
            Object source = sourceField.get(providerGraph);
            Object scheduler = schedulerField.get(providerGraph);
            if (source == null || scheduler == null)
                throw new IllegalStateException("SEND_READY graph has null source or scheduler");
            Class<?> sourceType = source.getClass();
            if (!sourceType.getName().equals("io.reactivex.rxjava3.internal.operators.completable.CompletableFromRunnable"))
                throw new IllegalStateException("unexpected SEND_READY source " + sourceType.getName());
            if (!scheduler.getClass().getName().startsWith("io.reactivex.rxjava3."))
                throw new IllegalStateException("unexpected SEND_READY scheduler " + scheduler.getClass().getName());

            Field runnableField = sourceType.getDeclaredField("runnable");
            runnableField.setAccessible(true);
            Object providerRunnable = runnableField.get(source);
            if (!(providerRunnable instanceof Runnable runnable))
                throw new IllegalStateException("SEND_READY source has no Runnable");
            Object reboundSource = Objects.requireNonNull(binder.apply(runnable, scheduler), "rebound SEND_READY source");

            var constructors = outer.getDeclaredConstructors();
            if (constructors.length != 1 || constructors[0].getParameterCount() != 2
                    || !constructors[0].getParameterTypes()[0].getName().equals("io.reactivex.rxjava3.core.CompletableSource")
                    || !constructors[0].getParameterTypes()[1].getName().equals("io.reactivex.rxjava3.core.Scheduler"))
                throw new NoSuchMethodException("CompletableSubscribeOn(CompletableSource,Scheduler)");
            constructors[0].setAccessible(true);
            return constructors[0].newInstance(reboundSource, scheduler);
        } catch (InvocationTargetException failure) {
            throw sendDrift(failure.getCause() == null ? failure : failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (failure instanceof IllegalStateException state
                    && state.getMessage() != null && state.getMessage().startsWith("pinned C2ME SEND_READY")) throw state;
            throw sendDrift(failure);
        }
    }

    private static ContextAccess reflective(Method holder, Method tacs, Method system, Method getKey,
                                             Method getFuture, Method getUserData, Method getItem,
                                             Method getSystemHolder, Field accessible, Field sending, Field delayed) {
        return new ContextAccess() {
            @Override public Parts read(Object context, Kind kind) {
                try {
                    Object backing = holder.invoke(context);
                    Object providerSystem = system.invoke(context);
                    Object mapValue = tacs.invoke(context);
                    if (backing == null || providerSystem == null || !(mapValue instanceof ChunkMap map))
                        throw new ChunkProtocolException("C2ME FULL context is incomplete");
                    Object key = getKey.invoke(backing);
                    if (!(key instanceof ChunkPos position)) throw new ChunkProtocolException("C2ME FULL holder key is not ChunkPos");
                    Object systemHolder = getSystemHolder.invoke(providerSystem, position);
                    if (systemHolder != backing) throw new ChunkProtocolException("C2ME FULL context/system holder identity differs");
                    Object status = (kind == Kind.FULL ? accessible : sending).get(null);
                    if (status == null) throw new ChunkProtocolException("C2ME provider status singleton is null");
                    Object slot = getFuture.invoke(backing, status);
                    if (!(slot instanceof CompletableFuture<?>)) throw new ChunkProtocolException("C2ME provider status slot is not CompletableFuture");
                    Object userDataRef = getUserData.invoke(backing);
                    Object itemRef = getItem.invoke(backing);
                    if (!(userDataRef instanceof AtomicReference<?> userData) || !(itemRef instanceof AtomicReference<?> item))
                        throw new ChunkProtocolException("C2ME holder references changed type");
                    Object holderValue = userData.get();
                    if (!(holderValue instanceof GenerationChunkHolder generationHolder))
                        throw new ChunkProtocolException("C2ME provider userData is not a GenerationChunkHolder");
                    if (!position.equals(generationHolder.getPos()))
                        throw new ChunkProtocolException("C2ME provider holder position changed");
                    Object state = item.get();
                    if (state == null) throw new ChunkProtocolException("C2ME provider ChunkState is null");
                    return new Parts(map, providerSystem, backing, status, slot, generationHolder, position, state);
                } catch (InvocationTargetException failure) {
                    throw drift(failure.getCause() == null ? failure : failure.getCause());
                } catch (ReflectiveOperationException failure) { throw drift(failure); }
            }

            @Override public boolean delayFullEvents() {
                try { return delayed.getBoolean(null); }
                catch (IllegalAccessException failure) { throw drift(failure); }
            }
        };
    }

    private static Method findSingleArgumentMethod(Class<?> type, String name) throws NoSuchMethodException {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous " + type.getName() + "." + name);
            found = method;
        }
        if (found == null) throw new NoSuchMethodException(type.getName() + "." + name);
        return found;
    }

    private static Method findZeroArgumentMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Method method : type.getMethods()) if (method.getName().equals(name) && method.getParameterCount() == 0) return method;
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Method findLongGet(Class<?> type) throws NoSuchMethodException {
        for (Method method : type.getMethods())
            if (method.getName().equals("get") && method.getParameterCount() == 1 && method.getParameterTypes()[0] == long.class)
                return method;
        throw new NoSuchMethodException(type.getName() + ".get(long)");
    }

    private static boolean isNamedType(Class<?> type, String expected) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            if (current.getName().equals(expected)) return true;
        return false;
    }

    private static IllegalStateException drift(Throwable failure) {
        return new IllegalStateException("pinned C2ME FULL contract drifted", failure);
    }

    private static IllegalStateException sendDrift(Throwable failure) {
        return new IllegalStateException("pinned C2ME SEND_READY Rx graph drifted", failure);
    }
}
