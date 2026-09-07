package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.CommandStorage;

/** Protected command-storage inputs for saved text. Content aliases share one custodian per
 * actual storage service; independent FULL plans retain their own revision and detached data.
 * Original cache loading happens owner-free before regional admission. No write/persistence
 * operation is added, and execution still reaches the original get at its original call.
 */
public final class FullCommandStorageInputs {
    private static final Map<CommandStorage, State> STATES = new IdentityHashMap<>();
    private FullCommandStorageInputs() { }

    static final class Capture {
        final State state;
        final long revision;
        final Identifier id;
        final CompoundTag actual, detached;
        final long bytes;
        Capture(State state, Identifier id, CompoundTag actual, long bytes) {
            this.state = state; this.revision = state.revision; this.id = id; this.actual = actual;
            detached = actual.copy(); this.bytes = bytes;
        }
        boolean valid() { return !state.closed && state.custodian.available() && state.revision == revision; }
        AutoCloseable retain(Runnable changed) {
            var subscription = new Subscription(state, changed);
            state.listeners.add(changed);
            try { if (!valid()) changed.run(); }
            catch (Throwable failure) { subscription.close(); throw failure; }
            return subscription;
        }
    }
    /** Acknowledged aliases retain neither the storage world nor another subscriber. */
    private static final class Subscription implements AutoCloseable {
        private State state;
        private Runnable listener;
        Subscription(State state, Runnable listener) { this.state = state; this.listener = listener; }
        @Override public synchronized void close() {
            if (state == null) return;
            state.listeners.remove(listener); state = null; listener = null;
        }
    }
    private static final class State {
        final MinecraftServer server;
        final CommandStorage storage;
        final FullSavedInput custodian;
        final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile long revision;
        volatile boolean closed;
        State(MinecraftServer server, CommandStorage storage) {
            this.server = server; this.storage = storage;
            custodian = new FullSavedInput(() -> {
                if (!closed) FullFinishingServices.requireServer(server, FullFinishingServices.Kind.COMMAND_STORAGE);
            });
            custodian.activate(); custodian.subscribe(this::changed);
        }
        void changed() {
            if (closed) return;
            revision = Math.incrementExact(revision);
            FullFinishingServices.forServer(server).changed(FullFinishingServices.Kind.COMMAND_STORAGE);
            for (Runnable listener : listeners) listener.run();
        }
    }
    static Capture observe(MinecraftServer server, Identifier id) {
        if (OwnershipContext.current().isPresent() || FullFinishingServices.finishing())
            throw new ChunkProtocolException("command storage input discovered after FULL admission");
        try (var serial = FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.SERVER_TASK)) {
            CommandStorage storage = Objects.requireNonNull(server.getCommandStorage(), "actual command storage");
            State state;
            synchronized (STATES) {
                state = STATES.computeIfAbsent(storage, ignored -> new State(server, storage));
                if (state.server != server || state.closed) throw new ChunkProtocolException("foreign command storage incarnation");
            }
            CompoundTag original = storage.get(id);
            long[] size = {0, 0};
            FullSavedTextInputs.protect(original, state.custodian, 0, size);
            return new Capture(state, id, original, size[1]);
        }
    }
    public static CompoundTag get(CommandStorage storage, Identifier id, Supplier<CompoundTag> original) {
        State state; synchronized (STATES) { state = STATES.get(storage); }
        if (state == null) {
            if (FullFinishingServices.finishing()) throw FullFinishingServices.failure(new ChunkProtocolException("unprepared command storage service"));
            return original.get();
        }
        FullFinishingServices.requireServer(state.server, FullFinishingServices.Kind.COMMAND_STORAGE);
        Capture expected = FullFinishingServices.finishing() ? FullSavedContextInputs.requireStorage(storage, id) : null;
        CompoundTag result = original.get();
        if (expected != null && (!expected.valid() || expected.state != state || !result.equals(expected.detached)))
            throw FullFinishingServices.failure(new ChunkProtocolException("command storage input changed at original text read"));
        return result;
    }
    public static void set(CommandStorage storage, Identifier id, CompoundTag contents, Runnable original) {
        State state; synchronized (STATES) { state = STATES.get(storage); }
        if (state == null) { original.run(); return; }
        FullFinishingServices.requireServer(state.server, FullFinishingServices.Kind.COMMAND_STORAGE);
        try { original.run(); }
        finally { state.changed(); }
    }
    public static void stopped(MinecraftServer server) {
        var stopped = new java.util.ArrayList<State>();
        synchronized (STATES) {
            STATES.entrySet().removeIf(entry -> {
                if (entry.getValue().server != server) return false;
                entry.getValue().closed = true;
                stopped.add(entry.getValue()); return true;
            });
        }
        Throwable failure = null;
        for (State state : stopped) {
            try {
                for (Runnable listener : state.listeners) try { listener.run(); }
                catch (Throwable problem) {
                    if (failure == null) failure = problem;
                    else if (failure != problem) failure.addSuppressed(problem);
                }
            } finally { state.listeners.clear(); }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new ChunkProtocolException("command storage shutdown acknowledgement", failure);
    }
}
