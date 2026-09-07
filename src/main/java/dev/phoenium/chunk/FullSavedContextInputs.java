package dev.phoenium.chunk;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.phoenium.ownership.OwnershipContext;
import java.util.*;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.ValueInput;

/** Protected context discovery and exact saved-reader consumption. Discovery may read named
 * saved-data services before R; it never constructs an entity or evaluates its selector,
 * predicate, reader, brain, or gameplay random source. Original resolution runs once later.
 */
public final class FullSavedContextInputs {
    private static final ThreadLocal<Plan> ACTIVE = new ThreadLocal<>();
    private FullSavedContextInputs() { }

    public static Plan prepare(ServerLevel world, ChunkCustodyModel.StageId stage, FullSavedInput.Snapshot saved, Runnable changed) {
        if (OwnershipContext.current().isPresent() || FullFinishingServices.finishing())
            throw new ChunkProtocolException("saved context preparation requires owner-free discovery");
        Plan plan = new Plan(world, stage, saved, changed);
        try {
            for (var fact : saved.entities()) if (fact.type().equals("minecraft:text_display")) plan.discover(fact);
            return plan;
        } catch (RuntimeException | Error failure) { plan.close(); throw failure; }
    }
    private record Pending(FullSavedInput.EntityFact fact, FullSavedTextInputs.NbtRead read) { }

    public static final class Plan implements AutoCloseable {
        private final ServerLevel world;
        private final ChunkCustodyModel.StageId stage;
        private final FullSavedInput.Snapshot saved;
        private final net.minecraft.core.RegistryAccess registry;
        private final Map<String, List<FullSavedTextInputs.Requirements>> texts = new LinkedHashMap<>();
        private final Map<Identifier, FullCommandStorageInputs.Capture> storage = new LinkedHashMap<>();
        private final List<AutoCloseable> consumers = new ArrayList<>();
        private final Runnable changed;
        private String unmet;
        private int bytes;
        private ChunkStageClaim claim;
        private String currentText;
        private boolean closed;
        private Plan(ServerLevel world, ChunkCustodyModel.StageId stage, FullSavedInput.Snapshot saved, Runnable changed) {
            this.world = world; this.stage = stage; this.saved = saved; this.changed = changed;
            registry = world.registryAccess();
        }
        public String unmet() { return unmet; }
        int payloadBytes() { return bytes; }
        public boolean valid(FullSavedInput.Snapshot current) {
            return !closed && current == saved && world.registryAccess() == registry
                    && storage.values().stream().allMatch(FullCommandStorageInputs.Capture::valid);
        }
        List<FullSavedTextInputs.Requirements> requirements(String path) { return List.copyOf(texts.getOrDefault(path, List.of())); }
        private void discover(FullSavedInput.EntityFact fact) {
            var queue = new ArrayDeque<Pending>();
            add(fact, FullSavedTextInputs.discover(fact, world.registryAccess()), queue);
            while (!queue.isEmpty() && unmet == null) {
                Pending next = queue.remove(); var read = next.read;
                if (read.source() != FullSavedTextInputs.Source.STORAGE) {
                    if (read.source() == FullSavedTextInputs.Source.ENTITY && read.target().equals("@s") && !read.interpreting()) continue;
                    unmet = fact.path() + ": protected display " + read.source() + " context producer pending"; break;
                }
                Identifier id = Identifier.parse(read.target());
                var capture = storage.get(id);
                if (capture == null) {
                    capture = FullCommandStorageInputs.observe(world.getServer(), id);
                    bytes += capture.bytes;
                    storage.put(id, capture);
                    consumers.add(capture.retain(changed));
                }
                if (!read.interpreting() || read.recursionDepth() > 100) continue;
                try {
                    var path = new NbtPathArgument().parse(new StringReader(read.path()));
                    for (var value : path.get(capture.detached)) {
                        var decoded = ComponentSerialization.CODEC.parse(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), value).result();
                        if (decoded.isPresent()) add(fact, FullSavedTextInputs.discoverInterpreted(fact, decoded.get(), read.recursionDepth()), queue);
                    }
                } catch (CommandSyntaxException absent) {
                    // Original NbtContents treats a missing path as an empty stream.
                }
            }
        }
        private void add(FullSavedInput.EntityFact fact, FullSavedTextInputs.Requirements requirements, ArrayDeque<Pending> queue) {
            texts.computeIfAbsent(fact.path(), ignored -> new ArrayList<>()).add(requirements);
            if (requirements.unmet() != null) { unmet = requirements.unmet(); return; }
            for (var selector : requirements.selectors()) {
                boolean ownedSelf = selector.source().equals("@s");
                boolean identityScore = selector.use() == FullSavedTextInputs.Use.SCORE && !selector.source().startsWith("@");
                if (!ownedSelf && !identityScore) {
                    unmet = fact.path() + ": protected display selector context producer pending for " + selector.source(); return;
                }
            }
            for (var read : requirements.nbtReads()) queue.add(new Pending(fact, read));
        }
        public Scope enter(ChunkStageClaim claim) {
            claim.require(stage);
            if (ACTIVE.get() != null || this.claim != null || unmet != null || !valid(saved))
                throw FullFinishingServices.failure(new ChunkProtocolException("saved text contexts are stale or not admitted"));
            var service = FullFinishingServices.currentReservation(world.getServer());
            if (service == null || !service.stage().equals(stage))
                throw FullFinishingServices.failure(new ChunkProtocolException("saved text context lacks the matching named service turn"));
            service.requireActive(claim);
            this.claim = claim; ACTIVE.set(this); return new Scope(this);
        }
        @Override public void close() {
            if (closed) return;
            if (claim != null) throw new ChunkProtocolException("saved contexts released before original reader exit");
            closed = true; texts.clear(); storage.clear();
            Throwable failure = null;
            for (AutoCloseable consumer : consumers) try { consumer.close(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            consumers.clear();
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure != null) throw new ChunkProtocolException("saved context acknowledgement failed", failure);
        }
    }
    public static final class Scope implements AutoCloseable {
        private Plan plan;
        private Scope(Plan plan) { this.plan = plan; }
        @Override public void close() {
            if (plan == null) return;
            if (ACTIVE.get() != plan || plan.currentText != null) throw new ChunkProtocolException("saved text context scope escaped");
            ACTIVE.remove(); plan.claim = null; plan = null;
        }
    }
    /** Installed on the exact concrete reader, with its real ValueInput source alias. */
    public static void textReader(Display.TextDisplay entity, ValueInput input, Runnable original) {
        if (!FullFinishingServices.finishing()) { original.run(); return; }
        Plan plan = ACTIVE.get();
        try {
            if (plan == null || plan.currentText != null || entity.level() != plan.world || !(input instanceof FullSavedValueInput value))
                throw new ChunkProtocolException("display reader has no exact saved context occurrence");
            plan.claim.require(plan.stage);
            var reservation = FullFinishingServices.currentReservation();
            if (reservation == null || !reservation.owns(entity)) throw new ChunkProtocolException("display reader is not its admitted constructed entity");
            var tag = value.phoenium$originalSavedTag(); var source = FullSavedCollections.source(tag);
            String path = source == null ? null : source.pathOf(tag);
            if (source == null || source.snapshot() != plan.saved || !plan.texts.containsKey(path))
                throw new ChunkProtocolException("display reader lost its protected saved input path");
            plan.currentText = path;
            try { original.run(); } finally { plan.currentText = null; }
        } catch (ChunkProtocolException failure) { throw FullFinishingServices.failure(failure); }
    }
    static FullCommandStorageInputs.Capture requireStorage(CommandStorage storage, Identifier id) {
        Plan plan = ACTIVE.get();
        if (plan == null || plan.currentText == null || plan.world.getServer().getCommandStorage() != storage)
            throw FullFinishingServices.failure(new ChunkProtocolException("command storage read outside its exact saved text occurrence"));
        plan.claim.require(plan.stage);
        boolean declared = plan.texts.get(plan.currentText).stream().flatMap(requirements -> requirements.nbtReads().stream())
                .anyMatch(read -> read.source() == FullSavedTextInputs.Source.STORAGE && read.target().equals(id.toString()));
        var capture = declared ? plan.storage.get(id) : null;
        if (capture == null || !capture.valid()) throw FullFinishingServices.failure(new ChunkProtocolException("unbound or stale saved text storage input"));
        return capture;
    }
}
