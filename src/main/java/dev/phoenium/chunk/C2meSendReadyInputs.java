package dev.phoenium.chunk;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import dev.phoenium.ownership.OwnershipContext;

/** Finite native demand pins for the exact notickvd removal callback. No requester is completed here. */
final class C2meSendReadyInputs implements AutoCloseable {
    private record Pin(Object holder, Object ticket, CompletableFuture<?> future) { }
    private final Object system;
    private final Object status;
    private final Object token;
    private final Method getHolder, ordinal, isOpen, target, add, remove, future, dirty, cancelled, cancel, complete;
    private final Constructor<?> ticketConstructor;
    private final Object external;
    private final LinkedHashMap<ChunkPos, Pin> pins = new LinkedHashMap<>();
    private final Set<ChunkPos> absent = new HashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<ChunkPos> footprint;

    C2meSendReadyInputs(C2meFullAccess.Observation observation, Object token, Runnable original) {
        this(observation.system(), accessibleStatus(observation.system()), token,
                footprint(observation.position(), removalPositions(original)));
    }
    C2meSendReadyInputs(Object system, Object status, Object token, Set<ChunkPos> footprint) {
        if (OwnershipContext.current().isPresent()) throw new ChunkProtocolException("SEND_READY discovery under owner claim");
        this.system = Objects.requireNonNull(system); this.status = Objects.requireNonNull(status);
        this.token = Objects.requireNonNull(token); this.footprint = Set.copyOf(footprint);
        try {
            ClassLoader loader = system.getClass().getClassLoader();
            Class<?> scheduler = Class.forName("com.ishland.flowsched.scheduler.StatusAdvancingScheduler", false, loader);
            Class<?> holder = Class.forName("com.ishland.flowsched.scheduler.ItemHolder", false, loader);
            Class<?> itemStatus = Class.forName("com.ishland.flowsched.scheduler.ItemStatus", false, loader);
            Class<?> ticket = Class.forName("com.ishland.flowsched.scheduler.ItemTicket", false, loader);
            Class<?> ticketType = Class.forName("com.ishland.flowsched.scheduler.ItemTicket$TicketType", false, loader);
            external = ticketType.getField("EXTERNAL").get(null);
            ticketConstructor = ticket.getConstructor(ticketType, Object.class, Runnable.class);
            getHolder = scheduler.getMethod("getHolder", Object.class);
            ordinal = itemStatus.getMethod("ordinal"); isOpen = holder.getMethod("isOpen");
            target = holder.getMethod("getTargetStatus");
            add = holder.getMethod("addTicket", itemStatus, ticket);
            remove = holder.getMethod("removeTicket", itemStatus, ticket);
            future = holder.getMethod("getFutureForStatus0", itemStatus);
            dirty = holder.getMethod("markDirty", scheduler);
            cancelled = token.getClass().getMethod("isCancelled"); cancel = token.getClass().getMethod("cancel");
            complete = token.getClass().getMethod("complete");
            check();
            for (ChunkPos position : footprint) capture(position);
        } catch (Throwable failure) {
            // Fields may be incomplete only before any native ticket is acquired.
            for (Pin pin : pins.values()) release(pin);
            throw unchecked(failure);
        }
    }
    private static Object accessibleStatus(Object system) {
        try { return Class.forName("com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus", false,
                system.getClass().getClassLoader()).getField("SERVER_ACCESSIBLE").get(null); }
        catch (ReflectiveOperationException failure) { throw unchecked(failure); }
    }

    static List<BlockPos> removalPositions(Runnable original) {
        ArrayList<?> captured = null;
        try {
            for (Field field : original.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != ArrayList.class) continue;
                if (captured != null) throw new ChunkProtocolException("ambiguous native SEND_READY removal capture");
                field.setAccessible(true); captured = (ArrayList<?>) field.get(original);
            }
        } catch (ReflectiveOperationException failure) { throw new ChunkProtocolException("unreadable native SEND_READY removal capture", failure); }
        if (captured == null) throw new ChunkProtocolException("native SEND_READY removal capture drift");
        var positions = new ArrayList<BlockPos>();
        for (Object value : captured) {
            if (!(value instanceof BlockPos position)) throw new ChunkProtocolException("non-position SEND_READY removal capture");
            positions.add(position.immutable());
        }
        return List.copyOf(positions);
    }

    static Set<ChunkPos> footprint(ChunkPos source, List<BlockPos> removals) {
        var result = new LinkedHashSet<ChunkPos>(); result.add(source);
        for (BlockPos position : removals) {
            if (!chunk(position).equals(source)) throw new ChunkProtocolException("SEND_READY removal escaped its source chunk");
            for (Direction direction : Direction.values()) result.add(chunk(position.relative(direction)));
        }
        return Collections.unmodifiableSet(result);
    }
    private static ChunkPos chunk(BlockPos position) { return new ChunkPos(position.getX() >> 4, position.getZ() >> 4); }

    private void capture(ChunkPos position) throws ReflectiveOperationException {
        Object holder = invoke(getHolder, system, position);
        if (holder == null) { absent.add(position); return; }
        Object ticket = ticketConstructor.newInstance(external, new Object(), null);
        boolean acquired = false;
        // Native removal closes the holder under this same monitor. The added ticket has
        // no callback; no CompletableFuture or Rx terminal is signalled in this section.
        synchronized (holder) {
            if (eligible(holder)) {
                invoke(add, holder, status, ticket);
                pins.put(position, new Pin(holder, ticket, (CompletableFuture<?>) invoke(future, holder, status)));
                acquired = true;
            }
        }
        if (acquired) invoke(dirty, holder, system);
        else absent.add(position);
    }

    private boolean eligible(Object holder) {
        return Boolean.TRUE.equals(invoke(isOpen, holder))
                && (int) invoke(ordinal, invoke(target, holder)) >= (int) invoke(ordinal, status);
    }

    CompletableFuture<Void> ready() {
        return CompletableFuture.allOf(pins.values().stream().map(Pin::future).toArray(CompletableFuture[]::new));
    }
    Set<ChunkPos> footprint() { return footprint; }
    int pinCount() { return pins.size(); }
    boolean awaited() { return pins.values().stream().anyMatch(pin -> !pin.future().isDone()); }

    /** Called before the body, with the complete finite footprint protected by its current owner. */
    void validate() {
        check();
        for (var entry : pins.entrySet()) {
            Pin pin = entry.getValue();
            if (invoke(getHolder, system, entry.getKey()) != pin.holder()
                    || invoke(future, pin.holder(), status) != pin.future()
                    || pin.future().state() != java.util.concurrent.Future.State.SUCCESS)
                retire("stale SEND_READY dependency");
        }
        for (ChunkPos position : absent) {
            Object holder = invoke(getHolder, system, position);
            if (holder != null && eligible(holder)) retire("SEND_READY absent dependency gained demand");
        }
    }
    void commit() {
        validate();
        if (!Boolean.TRUE.equals(invoke(complete, token))) {
            if (Boolean.TRUE.equals(invoke(cancelled, token))) throw new java.util.concurrent.CancellationException("SEND_READY token lost commit");
            throw new ChunkProtocolException("SEND_READY token completed outside its original body");
        }
    }
    void check() {
        if (closed.get() || Boolean.TRUE.equals(invoke(cancelled, token)))
            throw new java.util.concurrent.CancellationException("C2ME SEND_READY retired before effect");
    }
    void retire(String reason) {
        invoke(cancel, token);
        throw new java.util.concurrent.CancellationException(reason);
    }
    void shutdown() {
        // A committed body retains its exact input pins until its physical exit.
        if (Boolean.TRUE.equals(invoke(cancel, token)) || Boolean.TRUE.equals(invoke(cancelled, token))) close();
    }
    static void commitToken(Object token) {
        if (!Boolean.TRUE.equals(tokenCall(token, "complete"))) {
            if (Boolean.TRUE.equals(tokenCall(token, "isCancelled")))
                throw new java.util.concurrent.CancellationException("C2ME SEND_READY cancelled before effect");
            throw new ChunkProtocolException("SEND_READY token completed outside its original body");
        }
    }
    static java.util.concurrent.CancellationException control(Object token, C2meProviderControlException control) {
        tokenCall(token, "cancel");
        if (!Boolean.TRUE.equals(tokenCall(token, "isCancelled")))
            throw new ChunkProtocolException("SEND_READY control attempted after commit", control);
        var cancellation = new java.util.concurrent.CancellationException(control.getMessage());
        cancellation.initCause(control);
        return cancellation;
    }
    private static Object tokenCall(Object token, String method) {
        try { return invoke(token.getClass().getMethod(method), token); }
        catch (ReflectiveOperationException failure) { throw unchecked(failure); }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Throwable failure = null;
        for (Pin pin : pins.values()) {
            try { release(pin); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw unchecked(failure);
    }
    private void release(Pin pin) {
        invoke(remove, pin.holder(), status, pin.ticket());
        invoke(dirty, pin.holder(), system);
    }
    private static Object invoke(Method method, Object receiver, Object... args) {
        try { return method.invoke(receiver, args); }
        catch (Throwable failure) { throw unchecked(failure); }
    }
    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof InvocationTargetException invocation) failure = invocation.getCause();
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("native SEND_READY contract drift", failure);
    }
}
