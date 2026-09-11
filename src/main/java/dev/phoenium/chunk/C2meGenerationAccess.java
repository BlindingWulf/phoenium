package dev.phoenium.chunk;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Fail-closed reflection adapter for the locked C2ME pre-FULL generation lineage.
 * C2ME remains an optional provider; no provider class is linked into Phoenium's production JAR.
 */
final class C2meGenerationAccess {
    static final String DELEGATE = "com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate";
    static final String CONTEXT = "com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext";
    private static final String STATUS = "com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus";
    private static final String ITEM_STATUS = "com.ishland.flowsched.scheduler.ItemStatus";

    private C2meGenerationAccess() { }

    record Observation(Object delegate, Object context, ChunkMap map, Object backingHolder,
                       Object statusSlot, GenerationChunkHolder centerHolder,
                       ChunkStatus status, ChunkPos position, DelegateAccess delegateAccess,
                       ContextAccess contextAccess) {
        Observation {
            Objects.requireNonNull(delegate); Objects.requireNonNull(context); Objects.requireNonNull(map);
            Objects.requireNonNull(backingHolder); Objects.requireNonNull(statusSlot); Objects.requireNonNull(centerHolder);
            Objects.requireNonNull(status); Objects.requireNonNull(position); Objects.requireNonNull(delegateAccess);
            Objects.requireNonNull(contextAccess);
        }

        boolean stillCurrent() {
            if (delegateAccess.status(delegate) != status) return false;
            Parts current = contextAccess.read(context, status, position);
            return current.map == map && current.backingHolder == backingHolder
                    && current.statusSlot == statusSlot && current.centerHolder == centerHolder;
        }

        boolean sameProviderStatus(Observation other) {
            return other != null && other.map == map && other.backingHolder == backingHolder
                    && other.centerHolder == centerHolder && other.status == status
                    && other.position.equals(position);
        }
    }

    private record Parts(ChunkMap map, Object backingHolder, Object statusSlot,
                         GenerationChunkHolder centerHolder) { }

    private interface DelegateAccess { ChunkStatus status(Object delegate); }
    private interface ContextAccess { Parts read(Object context, ChunkStatus status, ChunkPos position); }

    private static final ClassValue<DelegateAccess> DELEGATE_ACCESS = new ClassValue<>() {
        @Override protected DelegateAccess computeValue(Class<?> type) {
            if (!isNamedType(type, DELEGATE))
                throw new IllegalStateException("unexpected C2ME generation delegate " + type.getName());
            try {
                Field status = findField(type, "status");
                if (!ChunkStatus.class.isAssignableFrom(status.getType()))
                    throw new NoSuchFieldException("status field type " + status.getType().getName());
                status.setAccessible(true);
                return delegate -> {
                    try { return (ChunkStatus) status.get(delegate); }
                    catch (IllegalAccessException failure) { throw drift(failure); }
                };
            } catch (ReflectiveOperationException failure) {
                throw drift(failure);
            }
        }
    };

    private static final ClassValue<ContextAccess> CONTEXT_ACCESS = new ClassValue<>() {
        @Override protected ContextAccess computeValue(Class<?> type) {
            if (!isNamedType(type, CONTEXT))
                throw new IllegalStateException("unexpected C2ME generation context " + type.getName());
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
                Class<?> systemHolderType = getHolder.getReturnType();
                Method getUserData = systemHolderType.getMethod("getUserData");
                if (!AtomicReference.class.isAssignableFrom(getUserData.getReturnType()))
                    throw new NoSuchMethodException("getUserData return type " + getUserData.getReturnType().getName());

                return reflective(holder, tacs, system, getKey, getFuture, fromVanilla, getHolder, getUserData);
            } catch (ReflectiveOperationException failure) {
                throw drift(failure);
            }
        }
    };

    static Observation capture(Object delegate, Object context, ChunkPos position) {
        Objects.requireNonNull(delegate, "delegate"); Objects.requireNonNull(context, "context"); Objects.requireNonNull(position, "position");
        DelegateAccess delegateAccess = DELEGATE_ACCESS.get(delegate.getClass());
        ContextAccess contextAccess = CONTEXT_ACCESS.get(context.getClass());
        ChunkStatus status = Objects.requireNonNull(delegateAccess.status(delegate), "C2ME delegate status");
        Parts parts = contextAccess.read(context, status, position);
        return new Observation(delegate, context, parts.map, parts.backingHolder, parts.statusSlot,
                parts.centerHolder, status, position, delegateAccess, contextAccess);
    }

    private static ContextAccess reflective(Method holder, Method tacs, Method system, Method getKey,
                                             Method getFuture, Method fromVanilla, Method getHolder,
                                             Method getUserData) {
        return (context, status, position) -> {
            try {
                Object backing = holder.invoke(context);
                if (backing == null) throw new IllegalStateException("C2ME generation context has no backing holder");
                Object key = getKey.invoke(backing);
                if (!position.equals(key)) throw new IllegalStateException("C2ME backing holder key changed: " + key);
                Object providerStatus = fromVanilla.invoke(null, status);
                if (providerStatus == null) throw new IllegalStateException("C2ME has no provider status for " + status);
                Object slot = getFuture.invoke(backing, providerStatus);
                if (!(slot instanceof CompletableFuture<?>))
                    throw new IllegalStateException("C2ME generation status slot is not a CompletableFuture");

                Object providerSystem = system.invoke(context);
                Object centerBacking = getHolder.invoke(providerSystem, position);
                if (centerBacking == null) throw new IllegalStateException("C2ME generation center holder is absent");
                Object userData = getUserData.invoke(centerBacking);
                if (!(userData instanceof AtomicReference<?> reference))
                    throw new IllegalStateException("C2ME generation user data is not an AtomicReference");
                Object center = reference.get();
                if (!(center instanceof GenerationChunkHolder generationHolder))
                    throw new IllegalStateException("C2ME generation user data is not a GenerationChunkHolder");
                if (!position.equals(generationHolder.getPos()))
                    throw new IllegalStateException("C2ME center holder position changed");
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

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
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
        return new IllegalStateException("pinned C2ME generation contract drifted", failure);
    }
}
