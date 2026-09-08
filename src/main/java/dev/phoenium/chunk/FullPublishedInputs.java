package dev.phoenium.chunk;

import dev.phoenium.Phoenium;
import dev.phoenium.mixin.ChunkMapLoadAccess;
import dev.phoenium.topology.RoutingKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.level.*;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;

/** Original and separately admitted FULL provenance. The holder owns its exact lineage.
 * The index has one record per real winning FULL holder, retained through acknowledged retirement;
 * it cannot refuse provenance for legitimate resident demand and grants neither ownership nor tickets.
 * Original completion and save-pin callbacks always run outside the custody metadata fence.
 */
public final class FullPublishedInputs {
    private static final ThreadLocal<Entry> ORIGINAL_INVOCATION = new ThreadLocal<>();
    public enum Origin { ORIGINAL_BASELINE_PROTO, ORIGINAL_BASELINE_IMPOSTER, ADMITTED_PROTO, ADMITTED_IMPOSTER }
    public record Evidence(GenerationChunkHolder holder, ChunkCustodyModel.LifecycleId lifecycle,
            ChunkCustodyModel.WorkId work, ChunkStep step, Object reservation, Origin origin,
            CompletableFuture<ChunkResult<ChunkAccess>> slot, ChunkResult<ChunkAccess> result,
            ChunkAccess originalValue, LevelChunk actual,
            CompletableFuture<ChunkResult<ChunkAccess>> lowerSlot, long revision) { }
    public static final class Entry {
        final FullPublishedInputs owner;
        final GenerationChunkHolder holder;
        final ChunkCustodyModel.WorkId work;
        final ChunkStep step;
        final Object reservation = new Object();
        final Origin origin;
        final long index;
        GeneratingChunkMap producerMap;
        StaticCache2D<GenerationChunkHolder> cache;
        CompletableFuture<ChunkAccess> returned;
        ChunkAccess value;
        Evidence evidence;
        long revision;
        int uses;
        boolean attached, settled, invalid, retired, announced, originalAsync;
        Entry(FullPublishedInputs owner, GenerationChunkHolder holder, ChunkCustodyModel.WorkId work,
                ChunkStep step, GeneratingChunkMap map, Origin origin) {
            this.owner = owner; this.holder = holder; this.work = work; this.step = step;
            producerMap = map; this.origin = origin; index = ++owner.nextEntry;
        }
    }
    private final ServerLevel level;
    private final Object server;
    private final ChunkBindingRuntime.WorldAuthority authority;
    private final ChunkPrerequisiteServices services;
    private final ChunkCustodyModel custody;
    private final NavigableMap<Long, Entry> entries = new TreeMap<>();
    private long nextEntry;
    private final FullInputNotifications changes = new FullInputNotifications();
    private final Thread observationThread;
    private boolean open = true;
    private long reservations, successes, acquisitions, acknowledgements, refusals;
    // Resident provenance is charged per existing holder, separately from bounded operation plans.
    // A fixed observer cap would permanently lose real publication identity once a world exceeded it.
    FullPublishedInputs(ServerLevel level, ChunkBindingRuntime.WorldAuthority authority) {
        this.level = level; server = level.getServer(); this.authority = authority;
        services = authority.services(); custody = services.custody();
        observationThread = Objects.requireNonNull(((FullInputObservationThread) level.getChunkSource()).phoenium$inputThread());
    }
    public FullInputNotifications changes() { return changes; }
    /** The already-winning selected work supplies lineage; this does not create another reservation. */
    Entry admitted(GeneratingChunkMap map, GenerationChunkHolder holder, ChunkStep step,
                   StaticCache2D<GenerationChunkHolder> cache, ChunkCustodyModel.WorkId work, ChunkAccess input) {
        custody.gate().lock();
        try {
            var hooks = (FullHolderEvidence) holder;
            if (!open || hooks.phoenium$publication() != null || !custody.metadataEntitledLocked(work)
                    || hooks.phoenium$startedWork().get() != ChunkStatus.FULL
                    || cache.get(holder.getPos().x(), holder.getPos().z()) != holder)
                throw new ChunkProtocolException("admitted publication lost its winning work/holder");
            Entry entry = new Entry(this, holder, work, step, map,
                    input instanceof ImposterProtoChunk ? Origin.ADMITTED_IMPOSTER : Origin.ADMITTED_PROTO);
            entry.cache = cache; entry.originalAsync = true;
            custody.retainLifecycle(work.lifecycle());
            hooks.phoenium$publication(entry); entries.put(entry.index, entry); reservations++;
            return entry;
        } finally { custody.gate().unlock(); }
    }
    void admittedReturned(Entry entry, CompletableFuture<ChunkAccess> returned) {
        custody.gate().lock(); try {
            if (entry.returned != null) throw new ChunkProtocolException("repeated admitted R");
            entry.returned = returned;
        } finally { custody.gate().unlock(); }
    }
    void admittedPublishing(Entry entry, ChunkAccess value, ChunkStageClaim claim) {
        claim.require(new ChunkCustodyModel.StageId(entry.work, ChunkStageKind.STATUS_PUBLISH, "FULL", 0));
        custody.gate().lock(); try {
            if (entry.owner != this || entry.settled || entry.retired)
                throw new ChunkProtocolException("obsolete admitted publication");
            // Ticket demotion can fail and clear a pending FULL subscriber while this
            // exact generation work still owns its original adjacent completion. That
            // invalidates dependency evidence, not the claimed producer's execution.
            // Keep invalid set: proposing/matches must never resurrect that evidence.
            entry.attached = true; entry.value = value;
        } finally { custody.gate().unlock(); }
    }
    /** Graph execution settlement is separate and waits for its own claims and prepared consumers. */
    void admittedSettled(Entry entry) {
        custody.gate().lock(); try {
            if (entry.settled) return;
            entry.settled = true; entry.returned = null; entry.producerMap = null;
            entry.value = null; entry.cache = null;
        } finally { custody.gate().unlock(); }
        notifyAndCleanup(entry);
    }
    private static FullPublishedInputs source(GeneratingChunkMap map) {
        if (map instanceof FullPublicationSource source) return source.phoenium$publishedInputs();
        if (map instanceof ChunkMap real) {
            ServerLevel level = ((ChunkMapLoadAccess) real).phoenium$level();
            var authority = Phoenium.liveRuntime().requireChunkWorld(level);
            return authority.services().publishedInputs(level, authority);
        }
        return null;
    }
    public static CompletableFuture<ChunkAccess> winning(GeneratingChunkMap map, GenerationChunkHolder holder,
            ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, Supplier<CompletableFuture<ChunkAccess>> original) {
        if (step.targetStatus() != ChunkStatus.FULL) return original.get();
        FullPublishedInputs owner = source(map);
        if (owner == null) return original.get();
        ((FullInputView) owner.level.getChunkSource().chunkMap).phoenium$inputChanges().publicationObserver(owner);
        FullHolderEvidence hooks = (FullHolderEvidence) holder;
        if (hooks.phoenium$startedWork().get() != ChunkStatus.FULL || cache.get(holder.getPos().x(), holder.getPos().z()) != holder
                || hooks.phoenium$generationRefs().get() <= 0 || hooks.phoenium$generationSaveSync().isDone())
            throw new ChunkProtocolException("published FULL lost its original winning reservation/borrow");
        Entry entry;
        owner.custody.gate().lock();
        try {
            if (!owner.open) {
                owner.refusals++; entry = null;
            } else {
                if (hooks.phoenium$publication() != null) throw new ChunkProtocolException("duplicate winning FULL publication lineage");
                var lifecycle = owner.services.lifecycles().activateHolder(owner.authority.worldInstanceSequence(),
                        new RoutingKey(owner.authority.world(), holder.getPos().x(), holder.getPos().z()), holder);
                var work = owner.services.works().beginOrResolveSharedWork(lifecycle,
                        new ChunkWorkRegistry.WorkReservation("generation-status", "FULL", 0));
                entry = new Entry(owner, holder, work, step, map,
                        holder.getChunkIfPresentUnchecked(ChunkStatus.SPAWN) instanceof ImposterProtoChunk
                                ? Origin.ORIGINAL_BASELINE_IMPOSTER : Origin.ORIGINAL_BASELINE_PROTO);
                owner.custody.retainLifecycle(lifecycle);
                owner.services.trackHolder(lifecycle, holder);
                hooks.phoenium$publication(entry); owner.entries.put(entry.index, entry); owner.reservations++;
            }
        } finally { owner.custody.gate().unlock(); }
        // Refusing observation never cancels vanilla demand or selects a different execution path.
        if (entry == null) return original.get();
        Entry previous = ORIGINAL_INVOCATION.get(); entry.cache = cache; ORIGINAL_INVOCATION.set(entry);
        try { entry.returned = Objects.requireNonNull(original.get()); return entry.returned; }
        catch (Throwable failure) { owner.settled(entry); throw failure; }
        finally { if (previous == null) ORIGINAL_INVOCATION.remove(); else ORIGINAL_INVOCATION.set(previous); }
    }
    /** Verifies the actual original FULL async call, without selecting the incomplete admitted graph. */
    static CompletableFuture<ChunkAccess> originalAsync(Supplier<ChunkAccess> original, Executor executor,
            WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess input, GenerationChunkHolder holder) {
        Entry entry = ORIGINAL_INVOCATION.get();
        if (entry != null) {
            if (entry.holder != holder || entry.step != step || entry.cache != cache || entry.owner.level != context.level()
                    || entry.originalAsync || (step != ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL)
                    && step != ChunkPyramid.LOADING_PYRAMID.getStepTo(ChunkStatus.FULL))
                    || input != holder.getChunkIfPresentUnchecked(ChunkStatus.SPAWN))
                throw new ChunkProtocolException("baseline publication lost its actual original async FULL producer");
            entry.originalAsync = true;
        }
        return CompletableFuture.supplyAsync(original, executor);
    }
    public static CompletableFuture<ChunkResult<ChunkAccess>> attach(GeneratingChunkMap map,
            CompletableFuture<ChunkAccess> returned, GenerationChunkHolder holder, ChunkStep step,
            BiFunction<ChunkAccess, Throwable, ChunkResult<ChunkAccess>> original) {
        Entry entry = ((FullHolderEvidence) holder).phoenium$publication();
        if (entry == null || step.targetStatus() != ChunkStatus.FULL) return null;
        if (entry.producerMap != map || entry.returned != returned || entry.step != step || entry.attached)
            throw new ChunkProtocolException("published FULL adjacent producer identity changed");
        entry.attached = true;
        // Installed before handle can run inline. The original handler remains the completion winner.
        var adjacent = returned.handle((value, failure) -> {
            entry.value = failure == null ? value : null;
            return original.apply(value, failure);
        });
        adjacent.whenComplete((result, failure) -> entry.owner.settled(entry));
        return adjacent;
    }
    /** Before the original future.complete or null-slot CAS, including its exact real result wrapper. */
    public static void proposing(GenerationChunkHolder holder, ChunkStatus status,
            CompletableFuture<ChunkResult<ChunkAccess>> slot, ChunkResult<ChunkAccess> result) {
        Entry entry = ((FullHolderEvidence) holder).phoenium$publication();
        if (entry == null || status != ChunkStatus.FULL) return;
        var owner = entry.owner;
        owner.custody.gate().lock(); try {
            if (entry.invalid || entry.settled || !entry.originalAsync || !entry.attached || entry.value == null || result.orElse(null) != entry.value) return;
            LevelChunk actual = actual(entry.value);
            if (actual == null || entry.value.getPersistedStatus() != ChunkStatus.FULL) return;
            entry.evidence = new Evidence(holder, entry.work.lifecycle(), entry.work, entry.step, entry.reservation, entry.origin,
                    slot, result, entry.value, actual, ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.SPAWN.getIndex()), entry.revision);
        } finally { owner.custody.gate().unlock(); }
    }
    public static void completed(GenerationChunkHolder holder, ChunkStatus status, boolean won) {
        Entry entry = ((FullHolderEvidence) holder).phoenium$publication();
        if (entry != null && status == ChunkStatus.FULL && won) {
            entry.owner.custody.gate().lock(); try { if (!entry.announced && entry.owner.matches(entry.evidence)) { entry.announced = true; entry.owner.successes++; } }
            finally { entry.owner.custody.gate().unlock(); }
            entry.owner.changes.published();
        }
    }
    private static LevelChunk actual(ChunkAccess value) {
        return value instanceof LevelChunk full ? full : value instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : null;
    }
    private void settled(Entry entry) {
        custody.gate().lock(); try {
            if (entry.settled) return;
            entry.settled = true;
            entry.returned = null; entry.producerMap = null; entry.value = null; entry.cache = null;
            services.settleFinishedExecution(entry.work, entry.holder);
        } finally { custody.gate().unlock(); }
        notifyAndCleanup(entry);
    }
    private void requireThread() {
        if (Thread.currentThread() != observationThread) throw new ChunkProtocolException("published input acquisition outside original holder observation thread");
    }
    private boolean matches(Evidence evidence) {
        if (evidence == null) return false;
        Entry entry = ((FullHolderEvidence) evidence.holder()).phoenium$publication();
        var hooks = (FullHolderEvidence) evidence.holder();
        if (!open || entry == null || entry.owner != this || entry.invalid || entry.retired || entry.evidence != evidence
                || entry.revision != evidence.revision() || level.getServer() != server
                || !services.lifecycles().isCurrent(evidence.lifecycle())
                || hooks.phoenium$startedWork().get() != ChunkStatus.FULL
                || hooks.phoenium$futures().get(ChunkStatus.FULL.getIndex()) != evidence.slot()
                || hooks.phoenium$futures().get(ChunkStatus.SPAWN.getIndex()) != evidence.lowerSlot()
                || evidence.slot().isCompletedExceptionally() || !evidence.slot().isDone()
                || evidence.slot().getNow(null) != evidence.result()
                || evidence.result().orElse(null) != evidence.originalValue() || actual(evidence.originalValue()) != evidence.actual()) return false;
        return true;
    }
    public Evidence observe(GenerationChunkHolder holder) {
        requireThread(); custody.gate().lock(); try {
            var entry = ((FullHolderEvidence) holder).phoenium$publication();
            return entry != null && matches(entry.evidence) && eligible(holder) ? entry.evidence : null;
        } finally { custody.gate().unlock(); }
    }
    private boolean eligible(GenerationChunkHolder holder) {
        return ((FullInputView) level.getChunkSource().chunkMap).phoenium$visibleInput(holder.getPos().pack()) == holder
                && holder.getTicketLevel() <= ChunkLevel.byStatus(ChunkStatus.FULL)
                && ((FullHolderEvidence) holder).phoenium$highestAllowed() == ChunkStatus.FULL;
    }
    public boolean validates(Evidence evidence) {
        requireThread(); custody.gate().lock(); try { return matches(evidence) && eligible(evidence.holder()); }
        finally { custody.gate().unlock(); }
    }
    public AutoCloseable retain(Evidence evidence) {
        requireThread(); Entry entry;
        custody.gate().lock(); try {
            if (!validates(evidence)) return null;
            entry = ((FullHolderEvidence) evidence.holder()).phoenium$publication(); entry.uses++; acquisitions++;
        } finally { custody.gate().unlock(); }
        var use = new AutoCloseable() {
            boolean closed;
            public void close() {
                requireThread();
                custody.gate().lock(); try { if (closed) return; closed = true; entry.uses--; acknowledgements++; }
                finally { custody.gate().unlock(); }
                var failures = new FullInputNotifications.Failures();
                failures.run(entry.holder::decreaseGenerationRefCount);
                failures.run(() -> cleanup(entry));
                failures.run(changes::published);
                failures.rethrow();
            }
        };
        // increaseGenerationRefCount commits the reference before its addSaveDependency callback.
        // Receipt ownership already exists, including when that original callback reenters or fails.
        try { entry.holder.increaseGenerationRefCount(); }
        catch (Throwable failure) {
            try { use.close(); } catch (Throwable cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
            throw failure;
        }
        if (!validates(evidence)) { try { use.close(); } catch (Exception impossible) { throw new AssertionError(impossible); } return null; }
        return use;
    }
    /** Original lower-slot replacement fences old evidence before its first CAS. */
    public static void replacing(GenerationChunkHolder holder) {
        Entry entry = ((FullHolderEvidence) holder).phoenium$publication();
        if (entry == null) return;
        entry.owner.custody.gate().lock(); try { entry.revision++; if (entry.evidence != null) entry.invalid = true; }
        finally { entry.owner.custody.gate().unlock(); }
        entry.owner.changes.published();
    }
    public static void eligibilityChanged(GenerationChunkHolder holder) {
        Entry entry = ((FullHolderEvidence) holder).phoenium$publication();
        if (entry != null) entry.owner.changes.published();
    }
    public static void retired(GenerationChunkHolder holder) {
        Entry entry = ((FullHolderEvidence) holder).phoenium$publication();
        if (entry != null) entry.owner.retire(entry);
    }
    public static void invalidating(GenerationChunkHolder holder) {
        Entry entry = ((FullHolderEvidence) holder).phoenium$publication();
        if (entry == null) return;
        entry.owner.custody.gate().lock(); try { entry.invalid = true; entry.revision++; }
        finally { entry.owner.custody.gate().unlock(); }
        entry.owner.changes.published();
    }
    private void retire(Entry entry) {
        custody.gate().lock(); try {
            if (entry.retired) return;
            entry.retired = true; entry.invalid = true;
            services.lifecycles().retireHolder(entry.work.lifecycle(), entry.holder);
        } finally { custody.gate().unlock(); }
        notifyAndCleanup(entry);
    }
    private void notifyAndCleanup(Entry entry) {
        var failures = new FullInputNotifications.Failures();
        failures.run(changes::published);
        failures.run(() -> cleanup(entry));
        failures.rethrow();
    }
    private void cleanup(Entry entry) {
        custody.gate().lock(); try {
            if (!entry.retired || !entry.settled || entry.uses != 0 || !entries.remove(entry.index, entry)) return;
            if (((FullHolderEvidence) entry.holder).phoenium$publication() == entry)
                ((FullHolderEvidence) entry.holder).phoenium$publication(null);
            entry.evidence = null;
            custody.acknowledgeLifecycle(entry.work.lifecycle());
            services.tryReleaseRetiredLifecycle(entry.work.lifecycle());
        } finally { custody.gate().unlock(); }
    }
    /** Called after original serial visible-map publication; scans only this finite derived index. */
    public void visiblePublished() {
        requireThread();
        var failures = new FullInputNotifications.Failures();
        failures.run(() -> visitCurrentEntries(entry -> {
            var visible = ((FullInputView) level.getChunkSource().chunkMap).phoenium$visibleInput(entry.holder.getPos().pack());
            if (visible != null && visible != entry.holder) retire(entry);
        }));
        failures.run(changes::published);
        failures.rethrow();
    }
    /** Constant transient scan space, including across reentrant removal/replacement.
     * A monotonically ordered index avoids copying every resident holder on publication.
     * New entries beyond the initial boundary belong to their subsequent producer event. */
    private void visitCurrentEntries(Consumer<Entry> visitor) {
        long boundary;
        custody.gate().lock(); try { boundary = nextEntry; } finally { custody.gate().unlock(); }
        long cursor = 0;
        var failures = new FullInputNotifications.Failures();
        for (;;) {
            Entry entry;
            custody.gate().lock();
            try {
                var next = entries.higherEntry(cursor);
                if (next == null || next.getKey() > boundary) break;
                cursor = next.getKey(); entry = next.getValue();
            } finally { custody.gate().unlock(); }
            failures.run(() -> visitor.accept(entry));
        }
        failures.rethrow();
    }
    public void shutdown() {
        open = false;
        try { visitCurrentEntries(this::retire); }
        finally { ((FullInputView) level.getChunkSource().chunkMap).phoenium$inputChanges().publicationObserver(null); }
    }
    public Map<String, Long> measurements() {
        custody.gate().lock(); try {
            return Map.of("records", (long) entries.size(), "transientExecutions", entries.values().stream().filter(e -> !e.settled).count(),
                    "uses", entries.values().stream().mapToLong(e -> e.uses).sum(), "reservations", reservations, "publications", successes,
                    "acquisitions", acquisitions, "acknowledgements", acknowledgements, "capacityRefusals", refusals,
                    "chargedRecordBytes", 256L * entries.size());
        } finally { custody.gate().unlock(); }
    }
}
