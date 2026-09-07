package dev.phoenium.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.arguments.coordinates.LocalCoordinates;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.*;
import net.minecraft.network.chat.contents.data.*;
import net.minecraft.util.CompilableString;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Static contexts of the original TextDisplay ComponentUtils.resolve call. Codec parsing and
 * scalar coordinates are permitted discovery; source resolution, entity predicates, NBT reads,
 * scoreboard reads and random selection are performed only by their admitted original body.
 */
final class FullSavedTextInputs {
    enum Source { ENTITY, BLOCK, STORAGE }
    enum Use { NAME, SCORE, NBT }
    record Selector(String source, boolean self, boolean playersOnly, boolean worldLimited, Use use) {
        Selector(String source, boolean self, boolean playersOnly, boolean worldLimited) {
            this(source, self, playersOnly, worldLimited, Use.NAME);
        }
    }
    record Score(String name, String objective) { }
    record NbtRead(Source source, String target, String path, boolean interpreting, int recursionDepth) { }
    record Requirements(List<BlockPos> blocks, List<Selector> selectors, List<Score> scores, List<NbtRead> nbtReads,
                        String unmet) {
        Requirements {
            blocks = List.copyOf(blocks); selectors = List.copyOf(selectors);
            scores = List.copyOf(scores); nbtReads = List.copyOf(nbtReads);
        }
        boolean needsContext() { return !blocks.isEmpty() || !selectors.isEmpty() || !scores.isEmpty() || !nbtReads.isEmpty(); }
    }

    /** Encapsulated copy: consumers cannot mutate saved text through its fact accessor. */
    static final class Saved {
        static final Saved EMPTY = new Saved(null, Vec2.ZERO, 0);
        private final Tag original;
        private final Vec2 rotation;
        private final int payloadBytes;
        Saved(Tag original, Vec2 rotation, int payloadBytes) {
            this.original = original; this.rotation = rotation; this.payloadBytes = payloadBytes;
        }
        int payloadBytes() { return payloadBytes; }
    }

    private FullSavedTextInputs() { }

    static Saved capture(CompoundTag entity, FullSavedInput custodian) {
        Tag text = entity.get("text");
        if (text == null) return Saved.EMPTY;
        int[] budget = {0, 0};
        protect(text, custodian, 0, budget);
        entity.getList("Rotation").ifPresent(rotation -> {
            if (custodian != null) FullSavedCollections.watch(rotation, custodian);
        });
        Vec2 saved = entity.read("Rotation", Vec2.CODEC).orElse(Vec2.ZERO);
        // Original Entity.setYRot ignores nonfinite values; setXRot additionally wraps/clamps.
        float yaw = Float.isFinite(saved.x) ? saved.x % 360.0F : 0.0F;
        float pitch = Float.isFinite(saved.y) ? Math.clamp(saved.y % 360.0F, -90.0F, 90.0F) : 0.0F;
        return new Saved(text.copy(), new Vec2(pitch, yaw), budget[1] + 16);
    }

    static void protect(Tag tag, FullSavedInput source, int depth, int[] budget) {
        if (depth > 128 || ++budget[0] > FullSavedInput.MAX_ENTITIES)
            throw new ChunkProtocolException("saved display component structure exceeds admission bound");
        budget[1] += 16;
        if (tag instanceof CompoundTag compound) {
            if (source != null) FullSavedCollections.watch(compound, source);
            for (var entry : compound.entrySet()) {
                budget[1] += 2 * entry.getKey().length();
                protect(entry.getValue(), source, depth + 1, budget);
            }
        } else if (tag instanceof ListTag list) {
            if (source != null) FullSavedCollections.watch(list, source);
            for (Tag child : list) protect(child, source, depth + 1, budget);
        } else budget[1] += tag.sizeInBytes();
        if (budget[1] > FullSavedInput.MAX_FACT_BYTES)
            throw new ChunkProtocolException("saved display component payload exceeds admission bound");
    }

    static Requirements discover(FullSavedInput.EntityFact fact, HolderLookup.Provider registries) {
        Saved saved = fact.services().text();
        var visitor = new Visitor(fact, saved);
        if (saved.original != null) {
            // Failed decode is the original Optional.empty branch; no resolver executes.
            ComponentSerialization.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), saved.original)
                    .result().ifPresent(component -> visitor.visit(component, 0));
        }
        return visitor.result();
    }

    /** Also used after a protected interpreted-NBT producer supplies the next decoded component. */
    static Requirements discoverInterpreted(FullSavedInput.EntityFact fact, Component component, int recursionDepth) {
        var visitor = new Visitor(fact, fact.services().text());
        visitor.visit(component, recursionDepth);
        return visitor.result();
    }

    private static final class Visitor {
        final FullSavedInput.EntityFact fact;
        final Saved saved;
        final List<BlockPos> blocks = new ArrayList<>();
        final List<Selector> selectors = new ArrayList<>();
        final List<Score> scores = new ArrayList<>();
        final List<NbtRead> nbt = new ArrayList<>();
        String unmet;
        int nodes;
        Visitor(FullSavedInput.EntityFact fact, Saved saved) { this.fact = fact; this.saved = saved; }
        Requirements result() { return new Requirements(blocks, selectors, scores, nbt, unmet); }
        void visit(Component component, int depth) {
            // Original ResolutionContext default copies the remaining subtree beyond 100.
            if (depth > 100 || unmet != null) return;
            if (++nodes > FullSavedInput.MAX_ENTITIES) {
                unmet = fact.path() + ": resolved display component exceeds admission bound"; return;
            }
            var contents = component.getContents();
            if (contents instanceof TranslatableContents translation) {
                for (Object argument : translation.getArgs()) if (argument instanceof Component nested) visit(nested, depth + 1);
            } else if (contents instanceof SelectorContents selected) {
                selector(selected.selector(), Use.NAME); selected.separator().ifPresent(separator -> visit(separator, depth + 1));
            } else if (contents instanceof ScoreContents score) {
                score.name().left().ifPresent(selector -> selector(selector, Use.SCORE));
                scores.add(new Score(score.name().map(CompilableString::source, name -> name), score.objective()));
            } else if (contents instanceof NbtContents read) {
                read.separator().ifPresent(separator -> visit(separator, depth + 1));
                Source source; String target;
                if (read.dataSource() instanceof BlockDataSource block) {
                    source = Source.BLOCK; target = block.coordinates().source();
                    var coordinates = block.coordinates().compiled();
                    Vec3 position;
                    if (coordinates instanceof WorldCoordinates world) {
                        position = new Vec3(world.x().get(fact.x()), world.y().get(fact.y()), world.z().get(fact.z()));
                    } else if (coordinates instanceof LocalCoordinates local) {
                        position = Vec3.applyLocalCoordinatesToRotation(saved.rotation,
                                new Vec3(local.left(), local.up(), local.forwards())).add(fact.x(), fact.y(), fact.z());
                    } else { unmet = fact.path() + ": display block coordinate contributor unbound"; return; }
                    if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
                        unmet = fact.path() + ": display block coordinate is nonfinite"; return;
                    }
                    blocks.add(BlockPos.containing(position));
                } else if (read.dataSource() instanceof EntityDataSource entity) {
                    source = Source.ENTITY; target = entity.selector().source(); selector(entity.selector(), Use.NBT);
                } else if (read.dataSource() instanceof StorageDataSource storage) {
                    source = Source.STORAGE; target = storage.id().toString();
                } else { unmet = fact.path() + ": display NBT source contributor unbound"; return; }
                nbt.add(new NbtRead(source, target, read.nbtPath().source(), read.interpreting(), depth + 1));
            } else if (contents instanceof ObjectContents object) {
                object.fallback().ifPresent(fallback -> visit(fallback, depth + 1));
            } else if (!(contents instanceof PlainTextContents || contents instanceof KeybindContents)) {
                unmet = fact.path() + ": display component contributor unbound for " + contents.getClass().getName();
            }
            for (Component sibling : component.getSiblings()) visit(sibling, depth + 1);
            if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText hover) visit(hover.value(), depth + 1);
        }
        void selector(CompilableString<EntitySelector> parsed, Use use) {
            EntitySelector selector = parsed.compiled();
            selectors.add(new Selector(parsed.source(), selector.isSelfSelector(), !selector.includesEntities(), selector.isWorldLimited(), use));
        }
    }
}
