package dev.phoenium.chunk;

import dev.phoenium.core.BoundMethod;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Fail-closed reflection adapter for the locked C2ME DISK/ReadFromDisk lineage. */
final class C2meReadAccess {
    static final String CONTEXT = "com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext";
    private static final String STATUS = "com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus";
    private static final String ITEM_STATUS = "com.ishland.flowsched.scheduler.ItemStatus";

    private C2meReadAccess() { }

    /** The disk lifetime binding requires native requester settlement, not just token cancellation. */
    static void requireSettlingProvider() {
        String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("c2me-base")
                .orElseThrow(() -> new IllegalStateException("C2ME base provider is absent"))
                .getMetadata().getVersion().getFriendlyString();
        if (!version.equals("0.4.2-alpha.0.43-phoenium-settlement2+26.2"))
            throw new IllegalStateException("C2ME disk lifetime requires the pinned native settlement provider, found " + version);
    }

    record Observation(Object context, ChunkMap map, Object backingHolder, Object statusSlot,
                       GenerationChunkHolder centerHolder, ChunkPos position, ContextAccess access) {
        Observation {
            Objects.requireNonNull(context); Objects.requireNonNull(map); Objects.requireNonNull(backingHolder);
            Objects.requireNonNull(statusSlot); Objects.requireNonNull(centerHolder); Objects.requireNonNull(position);
            Objects.requireNonNull(access);
        }

        boolean stillCurrent() {
            Parts current = access.read(context, position);
            return current.map == map && current.backingHolder == backingHolder
                    && current.statusSlot == statusSlot && current.centerHolder == centerHolder;
        }
    }

    private record Parts(ChunkMap map, Object backingHolder, Object statusSlot,
                         GenerationChunkHolder centerHolder) { }
    private interface ContextAccess { Parts read(Object context, ChunkPos position); }

    private static final ClassValue<ContextAccess> CONTEXT_ACCESS = new ClassValue<>() {
        @Override protected ContextAccess computeValue(Class<?> type) {
            if (!isNamedType(type, CONTEXT))
                throw new IllegalStateException("unexpected C2ME read context " + type.getName());
            try {
                ClassLoader loader = type.getClassLoader();
                Class<?> providerStatus = Class.forName(STATUS, false, loader);
                Class<?> itemStatus = Class.forName(ITEM_STATUS, false, loader);
                Method holder = type.getMethod("holder");
                Method tacs = type.getMethod("tacs");
                Method system = type.getMethod("theChunkSystem");
                if (!ChunkMap.class.isAssignableFrom(tacs.getReturnType()))
                    throw new NoSuchMethodException("tacs return type " + tacs.getReturnType().getName());

                Class<?> holderType = holder.getReturnType();
                Method getKey = holderType.getMethod("getKey");
                Method getFuture = holderType.getMethod("getFutureForStatus0", itemStatus);
                if (!CompletableFuture.class.isAssignableFrom(getFuture.getReturnType()))
                    throw new NoSuchMethodException("getFutureForStatus0 return type " + getFuture.getReturnType().getName());
                Method fromVanilla = providerStatus.getMethod("fromVanillaStatus", ChunkStatus.class);

                Method getHolder = findSingleArgumentMethod(system.getReturnType(), "getHolder");
                Method getUserData = getHolder.getReturnType().getMethod("getUserData");
                if (!AtomicReference.class.isAssignableFrom(getUserData.getReturnType()))
                    throw new NoSuchMethodException("getUserData return type " + getUserData.getReturnType().getName());
                return reflective(holder, tacs, system, getKey, getFuture, fromVanilla, getHolder, getUserData);
            } catch (ReflectiveOperationException failure) {
                throw drift(failure);
            }
        }
    };

    static Observation capture(Object context) {
        try {
            Object holder = context.getClass().getMethod("holder").invoke(context);
            return capture(context, (ChunkPos) holder.getClass().getMethod("getKey").invoke(holder));
        } catch (ReflectiveOperationException failure) { throw drift(failure); }
    }

    static Observation capture(Object context, ChunkPos position) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(position, "position");
        ContextAccess access = CONTEXT_ACCESS.get(context.getClass());
        Parts parts = access.read(context, position);
        return new Observation(context, parts.map, parts.backingHolder, parts.statusSlot,
                parts.centerHolder, position, access);
    }

    record ProviderPair(Object pair, ProtoChunk chunk, Object queue) { }

    /** Pins the locked Pair<ProtoChunk, ReferenceArrayList<Runnable>> carrier without linking
     * C2ME/fastutil into the production compile classpath. */
    static ProviderPair inspectProviderPair(Object pair) {
        Objects.requireNonNull(pair, "pair");
        try {
            Method first = pair.getClass().getMethod("first");
            Method second = pair.getClass().getMethod("second");
            Object chunk = first.invoke(pair);
            Object queue = second.invoke(pair);
            if (!(chunk instanceof ProtoChunk proto))
                throw new IllegalStateException("C2ME async final pair has no ProtoChunk");
            if (queue == null || !queue.getClass().getName().equals("it.unimi.dsi.fastutil.objects.ReferenceArrayList"))
                throw new IllegalStateException("C2ME async final pair has unexpected queue "
                        + (queue == null ? "null" : queue.getClass().getName()));
            return new ProviderPair(pair, proto, queue);
        } catch (InvocationTargetException failure) {
            throw drift(failure.getCause() == null ? failure : failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw drift(failure);
        }
    }

    /** Number of captured provider main-thread tasks waiting in the locked ReferenceArrayList. */
    static int providerQueueSize(ProviderPair carrier) {
        Objects.requireNonNull(carrier, "carrier");
        if (!(carrier.queue() instanceof List<?> tasks))
            throw new IllegalStateException("C2ME async final queue is no longer a java.util.List");
        for (Object task : tasks) if (!(task instanceof Runnable))
            throw new IllegalStateException("C2ME async final queue contains a non-Runnable task "
                    + (task == null ? "null" : task.getClass().getName()));
        return tasks.size();
    }

    static void requireEmptyProviderQueue(ProviderPair carrier) {
        if (providerQueueSize(carrier) != 0)
            throw new ChunkProtocolException("C2ME async final pair has deferred work without private read lineage");
    }

    /**
     * Executes C2ME's exact audited ChunkIoMainThreadTaskUtils.drainQueue implementation without
     * invoking the provider's final Rx mapper.  The caller supplies the ownership context.  The
     * locked drain owns ordering, continue-after-failure aggregation and queue clearing.
     */
    static void invokeProviderDrain(ProviderPair carrier) {
        Objects.requireNonNull(carrier, "carrier");
        Object queue = carrier.queue();
        try {
            ClassLoader loader = queue.getClass().getClassLoader();
            Class<?> utility = Class.forName(
                    "com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.ChunkIoMainThreadTaskUtils",
                    false, loader);
            Method drain = utility.getMethod("drainQueue", queue.getClass());
            drain.invoke(null, queue);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw drift(cause);
        } catch (ReflectiveOperationException failure) {
            throw drift(failure);
        }
    }

    /** Executes the exact provider Function object; caller chooses the authority under which the
     * locked final mapper (and therefore drainQueue) runs. */
    static Object invokeProviderMapper(Object mapper, Object pair) throws Throwable {
        Objects.requireNonNull(mapper, "mapper"); Objects.requireNonNull(pair, "pair");
        try {
            ClassLoader loader = mapper.getClass().getClassLoader();
            Class<?> function = Class.forName("io.reactivex.rxjava3.functions.Function", false, loader);
            if (!function.isInstance(mapper)) throw new IllegalStateException("C2ME final mapper is not an Rx Function");
            return function.getMethod("apply", Object.class).invoke(mapper, pair);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() == null ? failure : failure.getCause();
        } catch (ReflectiveOperationException failure) {
            throw drift(failure);
        }
    }

    /** Replaces only the locked final Single.map with a flatMap whose result is released by
     * Phoenium after owner execution.  The original provider mapper object itself is retained and
     * invoked later by the owner-routed body. */
    static Object rebindAsyncFinalMap(Object source, Object providerMapper,
                                      Function<Object, CompletionStage<ProtoChunk>> bridge) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(providerMapper, "providerMapper");
        Objects.requireNonNull(bridge, "bridge");
        try {
            ClassLoader loader = source.getClass().getClassLoader();
            Class<?> single = Class.forName("io.reactivex.rxjava3.core.Single", false, loader);
            Class<?> function = Class.forName("io.reactivex.rxjava3.functions.Function", false, loader);
            if (!single.isInstance(source)) throw new IllegalStateException("C2ME final map receiver is not Single");
            if (!function.isInstance(providerMapper)) throw new IllegalStateException("C2ME final mapper is not Function");
            Method fromStage = single.getMethod("fromCompletionStage", CompletionStage.class);
            Object flatMapper = Proxy.newProxyInstance(loader, new Class<?>[] { function }, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (!method.getName().equals("apply") || args == null || args.length != 1)
                    throw new IllegalStateException("unexpected Rx Function invocation " + method);
                CompletionStage<ProtoChunk> stage = Objects.requireNonNull(bridge.apply(args[0]), "C2ME bridge stage");
                return fromStage.invoke(null, stage);
            });
            return single.getMethod("flatMap", function).invoke(source, flatMapper);
        } catch (InvocationTargetException failure) {
            throw drift(failure.getCause() == null ? failure : failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw drift(failure);
        }
    }

    /**
     * The outer upgrade Completable spans C2ME's retries and synchronous fallback.  A real
     * subscriber cancellation can therefore retire whichever private async attempt is current
     * without racing one retry's disposal against the next retry's subscription.
     */
    static Object onOuterDispose(Object completable, Runnable cleanup) {
        Objects.requireNonNull(completable, "completable"); Objects.requireNonNull(cleanup, "cleanup");
        try {
            ClassLoader loader = completable.getClass().getClassLoader();
            Class<?> type = Class.forName("io.reactivex.rxjava3.core.Completable", false, loader);
            Class<?> action = Class.forName("io.reactivex.rxjava3.functions.Action", false, loader);
            if (!type.isInstance(completable)) throw new IllegalStateException("C2ME outer result is not Completable");
            Object disposal = Proxy.newProxyInstance(loader, new Class<?>[] { action }, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (!method.getName().equals("run") || method.getParameterCount() != 0)
                    throw new IllegalStateException("unexpected Rx Action invocation " + method);
                cleanup.run();
                return null;
            });
            return type.getMethod("doOnDispose", action).invoke(completable, disposal);
        } catch (InvocationTargetException failure) {
            throw drift(failure.getCause() == null ? failure : failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw drift(failure);
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "PhoeniumC2meAsyncFinalMapper";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            default -> throw new IllegalStateException("unexpected Object method " + method);
        };
    }

    // S9-D13: the provider accessors are bound once per context class (BoundMethod), not reflected per capture.
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static ContextAccess reflective(Method holderMethod, Method tacsMethod, Method systemMethod, Method getKeyMethod,
                                             Method getFutureMethod, Method fromVanillaMethod, Method getHolderMethod,
                                             Method getUserDataMethod) throws IllegalAccessException {
        BoundMethod holder = BoundMethod.of(LOOKUP, holderMethod), tacs = BoundMethod.of(LOOKUP, tacsMethod),
            system = BoundMethod.of(LOOKUP, systemMethod), getKey = BoundMethod.of(LOOKUP, getKeyMethod),
            getFuture = BoundMethod.of(LOOKUP, getFutureMethod), fromVanilla = BoundMethod.of(LOOKUP, fromVanillaMethod),
            getHolder = BoundMethod.of(LOOKUP, getHolderMethod), getUserData = BoundMethod.of(LOOKUP, getUserDataMethod);
        return (context, position) -> {
            try {
                Object backing = holder.invoke(context);
                if (backing == null) throw new IllegalStateException("C2ME read context has no backing holder");
                Object key = getKey.invoke(backing);
                if (!position.equals(key)) throw new IllegalStateException("C2ME read holder key changed: " + key);
                Object disk = fromVanilla.invoke(null, ChunkStatus.EMPTY);
                if (disk == null) throw new IllegalStateException("C2ME has no DISK status for EMPTY");
                Object slot = getFuture.invoke(backing, disk);
                if (!(slot instanceof CompletableFuture<?>))
                    throw new IllegalStateException("C2ME DISK status slot is not a CompletableFuture");

                Object providerSystem = system.invoke(context);
                Object centerBacking = getHolder.invoke(providerSystem, position);
                if (centerBacking == null) throw new IllegalStateException("C2ME read center holder is absent");
                Object userData = getUserData.invoke(centerBacking);
                if (!(userData instanceof AtomicReference<?> reference))
                    throw new IllegalStateException("C2ME read user data is not an AtomicReference");
                Object center = reference.get();
                if (!(center instanceof GenerationChunkHolder generationHolder))
                    throw new IllegalStateException("C2ME read user data is not a GenerationChunkHolder");
                if (!position.equals(generationHolder.getPos()))
                    throw new IllegalStateException("C2ME read center holder position changed");
                Object map = tacs.invoke(context);
                if (!(map instanceof ChunkMap chunkMap)) throw new IllegalStateException("C2ME context tacs is not ChunkMap");
                return new Parts(chunkMap, backing, slot, generationHolder);
            } catch (InvocationTargetException failure) {
                throw drift(failure.getCause() == null ? failure : failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw drift(failure);
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

    private static boolean isNamedType(Class<?> type, String expected) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            if (current.getName().equals(expected)) return true;
        return false;
    }

    private static IllegalStateException drift(Throwable failure) {
        return new IllegalStateException("pinned C2ME read contract drifted", failure);
    }
}
