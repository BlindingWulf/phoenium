package dev.phoenium.chunk;

import dev.phoenium.save.RegionalSaveCoordinator;
import dev.phoenium.topology.RoutingKey;
import java.util.IdentityHashMap;
import java.util.Objects;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Disposable installed-probe bridge. Production never sees this class: the full-registration
 * harness uses it to keep a controlled world's save work on that world's genuine isolated
 * directory, leases, lifetime service, dispatcher and coordinator.
 */
public final class FullRegistrationFixtureSaveBridge {
    private static final Object METADATA = new Object();
    private static final IdentityHashMap<ServerLevel, Binding> BINDINGS = new IdentityHashMap<>();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private FullRegistrationFixtureSaveBridge() { }

    public static Binding register(ServerLevel level, ChunkMap map,
                                   ChunkBindingRuntime.WorldAuthority authority,
                                   RegionalSaveCoordinator coordinator,
                                   FullRegistrationRuntime runtime) {
        Binding binding = new Binding(level, map, authority, coordinator, runtime);
        synchronized (METADATA) {
            if (BINDINGS.putIfAbsent(level, binding) != null)
                throw new IllegalStateException("controlled save world registered twice");
        }
        return binding;
    }

    public static Binding find(ServerLevel level, ChunkMap map) {
        synchronized (METADATA) {
            Binding binding = BINDINGS.get(level);
            return binding != null && binding.map == map ? binding : null;
        }
    }

    public static Scope enter(Binding binding) {
        Objects.requireNonNull(binding);
        synchronized (METADATA) {
            if (BINDINGS.get(binding.level) != binding)
                throw new IllegalStateException("controlled save binding is no longer registered");
        }
        Scope parent = CURRENT.get();
        Scope scope = new Scope(binding, parent);
        CURRENT.set(scope);
        return scope;
    }

    public static Binding current(ServerLevel level) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.binding.level != level)
            throw new IllegalStateException("controlled save substitution escaped its exact world");
        synchronized (METADATA) {
            if (BINDINGS.get(level) != scope.binding)
                throw new IllegalStateException("controlled save substitution outlived its fixture");
        }
        return scope.binding;
    }

    public static Binding currentOrNull() {
        Scope scope = CURRENT.get();
        if (scope == null) return null;
        synchronized (METADATA) {
            if (BINDINGS.get(scope.binding.level) != scope.binding)
                throw new IllegalStateException("controlled save substitution outlived its fixture");
        }
        return scope.binding;
    }

    /**
     * The controlled unload is an owner-free synchronous boundary. Settle only save attempts
     * whose real body/write completion is already published; an asynchronous write remains
     * retained for normal later pumping. A failure from the unchanged unload body stays primary.
     */
    public static void acknowledgeCompleted(Binding binding, Throwable originalFailure) {
        Objects.requireNonNull(binding);
        try {
            while (binding.coordinator.hasAcknowledgement()) {
                if (!binding.coordinator.pumpOne())
                    throw new IllegalStateException("completed controlled save acknowledgement made no progress");
            }
            if (binding.coordinator.pending() == 0) binding.coordinator.requireFlushed();
        } catch (RuntimeException | Error acknowledgementFailure) {
            if (originalFailure != null) {
                if (originalFailure != acknowledgementFailure) originalFailure.addSuppressed(acknowledgementFailure);
                return;
            }
            throw acknowledgementFailure;
        }
    }

    public static final class Binding implements AutoCloseable {
        private final ServerLevel level;
        private final ChunkMap map;
        private final ChunkBindingRuntime.WorldAuthority authority;
        private final RegionalSaveCoordinator coordinator;
        private final FullRegistrationRuntime runtime;
        private FullFinishingServices finishing;

        private Binding(ServerLevel level, ChunkMap map,
                        ChunkBindingRuntime.WorldAuthority authority,
                        RegionalSaveCoordinator coordinator,
                        FullRegistrationRuntime runtime) {
            Objects.requireNonNull(level); Objects.requireNonNull(map);
            Objects.requireNonNull(authority); Objects.requireNonNull(coordinator); Objects.requireNonNull(runtime);
            this.level = level; this.map = map; this.authority = authority;
            this.coordinator = coordinator; this.runtime = runtime;
        }
        public ServerLevel level() { return level; }
        public ChunkMap map() { return map; }
        public ChunkBindingRuntime.WorldAuthority authority() { return authority; }
        public RegionalSaveCoordinator coordinator() { return coordinator; }
        public FullRegistrationRuntime runtime() { return runtime; }
        public RoutingKey key(ChunkPos position) {
            return new RoutingKey(authority.world(), position.x(), position.z());
        }
        public void finishing(FullFinishingServices service) {
            Objects.requireNonNull(service);
            synchronized (METADATA) {
                if (BINDINGS.get(level) != this)
                    throw new IllegalStateException("controlled save binding is no longer registered");
                if (finishing != null && finishing != service)
                    throw new IllegalStateException("controlled save finishing service changed");
                finishing = service;
            }
        }
        public FullFinishingServices finishing() {
            synchronized (METADATA) { return finishing; }
        }
        @Override public void close() {
            synchronized (METADATA) {
                if (!BINDINGS.remove(level, this))
                    throw new IllegalStateException("controlled save world binding changed");
            }
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Binding binding;
        private final Scope parent;
        private boolean closed;
        private Scope(Binding binding, Scope parent) { this.binding = binding; this.parent = parent; }
        @Override public void close() {
            if (closed || CURRENT.get() != this)
                throw new IllegalStateException("controlled save scope closed out of order");
            closed = true;
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
        }
    }
}
