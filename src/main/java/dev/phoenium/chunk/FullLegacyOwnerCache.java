package dev.phoenium.chunk;

import com.google.gson.*;
import com.mojang.authlib.GameProfileRepository;
import dev.phoenium.ownership.OwnershipContext;
import java.io.*;
import java.lang.invoke.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import net.minecraft.server.players.*;
import net.minecraft.util.StringUtil;

/**
 * Pinned CachedUserNameToIdResolver state protocol. Its two existing maps remain
 * authoritative. Primitive map/MRU cuts use a short gate; provider calls, JSON and
 * ordered physical writes run without that gate or any C/S/W/region reservation.
 * Accessors bind actual vanilla storage; no shadow profile cache is introduced.
 */
public final class FullLegacyOwnerCache {
    public interface Access { FullLegacyOwnerCache phoenium$ownerCache(); }
    public enum Persistence { NO_ATTEMPT, WRITTEN, IO_CAUGHT, RUNTIME_FAILURE }
    public record SaveAttempt(long sequence, Persistence outcome, Throwable failure) { }
    public record Cut(Object entry, UUID identity, long expiration, long policy, long mutation, long mru) { }
    private record Saved(NameAndId identity, long expires, long mru) { }
    private record Write(long sequence, List<Saved> values, CompletableFuture<SaveAttempt> completion) { }
    private static final MethodHandle NEW_ENTRY, NAME, EXPIRATION, LAST_ACCESS, SET_ACCESS;
    static {
        try {
            Class<?> entry = Class.forName("net.minecraft.server.players.CachedUserNameToIdResolver$GameProfileInfo");
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(entry, MethodHandles.lookup());
            NEW_ENTRY = lookup.findConstructor(entry, MethodType.methodType(void.class, NameAndId.class, Date.class));
            NAME = lookup.findVirtual(entry, "nameAndId", MethodType.methodType(NameAndId.class));
            EXPIRATION = lookup.findVirtual(entry, "expirationDate", MethodType.methodType(Date.class));
            LAST_ACCESS = lookup.findVirtual(entry, "lastAccess", MethodType.methodType(long.class));
            SET_ACCESS = lookup.findVirtual(entry, "setLastAccess", MethodType.methodType(void.class, long.class));
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private final UserNameToIdResolver owner;
    private final Map<String, Object> byName;
    private final Map<UUID, Object> byId;
    private final GameProfileRepository provider;
    private final Gson gson;
    private final File file;
    private final AtomicLong operations;
    private final BooleanSupplier offline;
    private final Consumer<Boolean> setOffline;
    private final ReentrantLock state = new ReentrantLock();
    private final ReentrantLock writesGate = new ReentrantLock();
    private final NavigableMap<Long, Write> writes = new TreeMap<>();
    private final ExecutorService storage = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Phoenium-profile-storage"); thread.setDaemon(true); return thread;
    });
    private final ThreadLocal<SaveAttempt> lastSave = ThreadLocal.withInitial(() -> new SaveAttempt(0, Persistence.NO_ATTEMPT, null));
    private long policy, mutation, saveSequence, nextWrite = 1;
    private boolean draining, stopped;

    public FullLegacyOwnerCache(UserNameToIdResolver owner, Map<String, Object> byName, Map<UUID, Object> byId,
                               GameProfileRepository provider, Gson gson, File file, AtomicLong operations,
                               BooleanSupplier offline, Consumer<Boolean> setOffline) {
        this.owner = owner; this.byName = byName; this.byId = byId; this.provider = provider;
        this.gson = gson; this.file = file; this.operations = operations; this.offline = offline; this.setOffline = setOffline;
    }
    public Optional<NameAndId> get(String originalName) {
        String key = originalName.toLowerCase(Locale.ROOT);
        boolean needsSave = false;
        for (;;) {
            final long observedPolicy;
            final boolean permitOffline;
            state.lock();
            try {
                Object entry = byName.get(key);
                if (entry != null && System.currentTimeMillis() >= expires(entry)) {
                    NameAndId identity = identity(entry);
                    byId.remove(identity.id()); byName.remove(identity.name().toLowerCase(Locale.ROOT));
                    mutation++; needsSave = true; entry = null;
                }
                if (entry != null) { touch(entry); return Optional.of(identity(entry)); }
                observedPolicy = policy; permitOffline = offline.getAsBoolean();
            } finally { state.unlock(); }
            Optional<NameAndId> resolved;
            if (!StringUtil.isValidPlayerName(key)) resolved = permitOffline ? Optional.of(NameAndId.createOffline(key)) : Optional.empty();
            else {
                resolved = provider.findProfileByName(key).map(NameAndId::new);
                if (resolved.isEmpty() && permitOffline) resolved = Optional.of(NameAndId.createOffline(key));
            }
            state.lock();
            try {
                // A late provider cannot overwrite a newer mapping or policy. Re-observe before
                // committing; separate logical calls still perform their own MRU occurrence.
                if (policy != observedPolicy || byName.get(key) != null) continue;
                if (resolved.isPresent()) { addLocked(resolved.get()); needsSave = true; }
            } finally { state.unlock(); }
            if (needsSave) owner.save(); // Dynamic override and original failure position retained.
            return resolved;
        }
    }
    public Optional<NameAndId> get(UUID id) {
        state.lock();
        try { Object entry = byId.get(id); if (entry == null) return Optional.empty(); touch(entry); return Optional.of(identity(entry)); }
        finally { state.unlock(); }
    }
    public void add(NameAndId identity) {
        state.lock(); try { addLocked(identity); } finally { state.unlock(); }
        owner.save();
    }
    public void offline(boolean value) {
        state.lock(); try { if (offline.getAsBoolean() != value) policy++; setOffline.accept(value); }
        finally { state.unlock(); }
    }
    private void addLocked(NameAndId identity) {
        Calendar expiration = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
        expiration.setTime(new Date()); expiration.add(Calendar.MONTH, 1);
        final Object entry;
        try { entry = NEW_ENTRY.invoke(identity, expiration.getTime()); }
        catch (Throwable failure) { throw unchecked(failure); }
        touch(entry); byName.put(identity.name().toLowerCase(Locale.ROOT), entry); byId.put(identity.id(), entry); mutation++;
    }
    private void touch(Object entry) {
        try { SET_ACCESS.invoke(entry, operations.incrementAndGet()); }
        catch (Throwable failure) { throw unchecked(failure); }
    }
    public Cut cut(String name) {
        state.lock();
        try {
            Object entry = byName.get(name.toLowerCase(Locale.ROOT));
            return new Cut(entry, entry == null ? null : identity(entry).id(), entry == null ? Long.MAX_VALUE : expires(entry),
                    policy, mutation, operations.get());
        } finally { state.unlock(); }
    }
    public boolean valid(String name, Cut cut) {
        state.lock();
        try { return cut != null && policy == cut.policy && byName.get(name.toLowerCase(Locale.ROOT)) == cut.entry
                && (cut.entry == null || System.currentTimeMillis() < cut.expiration); }
        finally { state.unlock(); }
    }
    public void beginOccurrence() { lastSave.set(new SaveAttempt(0, Persistence.NO_ATTEMPT, null)); }
    public SaveAttempt lastSave() { return lastSave.get(); }
    public void endOccurrence() { lastSave.remove(); }
    public void save() {
        if (OwnershipContext.current().isPresent() || FullFinishingServices.finishing())
            throw new ChunkProtocolException("profile persistence reached with regional or FULL authority");
        final Write write;
        boolean submit = false;
        RejectedExecutionException stoppedFailure = null;
        state.lock();
        try {
            List<Saved> frozen = byId.values().stream().map(entry -> new Saved(identity(entry), expires(entry), access(entry)))
                    .sorted(Comparator.comparingLong(Saved::mru).reversed()).limit(1000).toList();
            write = new Write(++saveSequence, frozen, new CompletableFuture<>());
            // Publish the ordered receipt in the same snapshot turn. Otherwise a later save
            // can queue first, then shutdown can reject this missing earlier sequence forever.
            // No submission, future callback, serialization or I/O occurs under either gate.
            writesGate.lock(); try {
                if (stopped) stoppedFailure = new RejectedExecutionException("profile storage is stopped");
                else {
                    writes.put(write.sequence, write);
                    if (!draining) { draining = true; submit = true; }
                }
            } finally { writesGate.unlock(); }
        } finally { state.unlock(); }
        if (stoppedFailure != null)
            write.completion.complete(new SaveAttempt(write.sequence, Persistence.RUNTIME_FAILURE, stoppedFailure));
        else if (submit) try { storage.execute(this::drainWrites); }
        catch (RejectedExecutionException rejected) { rejectQueuedWrites(rejected); }
        SaveAttempt result = FullFinishingServices.ownerFreeIo(write.completion::join);
        lastSave.set(result);
        if (result.failure != null) throw unchecked(result.failure);
    }
    private void drainWrites() {
        for (;;) {
            Write write;
            writesGate.lock(); try {
                write = writes.remove(nextWrite);
                if (write == null) { draining = false; return; }
                nextWrite++;
            } finally { writesGate.unlock(); }
            SaveAttempt attempt;
            try {
                JsonArray values = new JsonArray();
                DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
                for (Saved entry : write.values) {
                    JsonObject value = new JsonObject(); entry.identity.appendTo(value);
                    value.addProperty("expiresOn", format.format(new Date(entry.expires))); values.add(value);
                }
                String serialized = gson.toJson(values);
                try (Writer output = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                    output.write(serialized); attempt = new SaveAttempt(write.sequence, Persistence.WRITTEN, null);
                } catch (IOException caught) { attempt = new SaveAttempt(write.sequence, Persistence.IO_CAUGHT, null); }
            } catch (Throwable failure) { attempt = new SaveAttempt(write.sequence, Persistence.RUNTIME_FAILURE, failure); }
            write.completion.complete(attempt);
        }
    }
    private void rejectQueuedWrites(RejectedExecutionException failure) {
        List<Write> rejected;
        writesGate.lock();
        try {
            rejected = List.copyOf(writes.values()); writes.clear(); draining = false;
            if (!rejected.isEmpty()) nextWrite = Math.max(nextWrite, rejected.getLast().sequence + 1);
        } finally { writesGate.unlock(); }
        for (Write write : rejected)
            write.completion.complete(new SaveAttempt(write.sequence, Persistence.RUNTIME_FAILURE, failure));
    }
    public void shutdown() {
        writesGate.lock(); try { stopped = true; } finally { writesGate.unlock(); }
        storage.shutdown();
    }
    public int pendingWrites() { writesGate.lock(); try { return writes.size() + (draining ? 1 : 0); } finally { writesGate.unlock(); } }
    private static NameAndId identity(Object entry) { try { return (NameAndId) NAME.invoke(entry); } catch (Throwable failure) { throw unchecked(failure); } }
    private static long expires(Object entry) { try { return ((Date) EXPIRATION.invoke(entry)).getTime(); } catch (Throwable failure) { throw unchecked(failure); } }
    private static long access(Object entry) { try { return (long) LAST_ACCESS.invoke(entry); } catch (Throwable failure) { throw unchecked(failure); } }
    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof Error error) throw error;
        return failure instanceof RuntimeException runtime ? runtime : new ChunkProtocolException("pinned profile cache adapter", failure);
    }
}
