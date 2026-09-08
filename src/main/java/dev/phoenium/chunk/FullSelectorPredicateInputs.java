package dev.phoenium.chunk;

import java.util.*;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.advancements.predicates.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.level.storage.loot.IntRange;
import net.minecraft.world.level.storage.loot.providers.number.BinomialDistributionGenerator;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.EnchantmentLevelProvider;
import net.minecraft.world.level.storage.loot.providers.number.EnvironmentAttributeValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.ScoreboardValue;
import net.minecraft.world.level.storage.loot.providers.number.StorageValue;
import net.minecraft.world.level.storage.loot.providers.number.Sum;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import net.minecraft.world.level.storage.loot.predicates.*;

/** Dependency-only inspection of immutable registered selector predicates. Predicate lookup,
 * short circuiting, world reads, serializers and gameplay random remain in EntitySelector's
 * original candidate predicate at the original text-resolution point. */
public final class FullSelectorPredicateInputs {
    private static final long STORAGE_ID_BYTES = 64L;
    public interface CompositeAccess { List<LootItemCondition> phoenium$predicateTerms(); }
    /** Read-only access to IntRange's immutable providers; no provider is evaluated here. */
    public interface IntRangeAccess {
        NumberProvider phoenium$min();
        NumberProvider phoenium$max();
    }
    record Result(ReloadableServerRegistries.Holder registries, Set<ChunkPos> biomes,
                  Set<ChunkPos> full, Set<ChunkPos> structures, Set<Identifier> storageIds,
                  Set<EntityPredicate> entityPredicates, long bytes, boolean capacity, String unmet) { }

    private FullSelectorPredicateInputs() { }

    static Result discover(MinecraftServer server, String selector, Set<ChunkPos> candidateChunks,
                           java.util.function.LongPredicate capacity) {
        ReloadableServerRegistries.Holder holder = server.reloadableRegistries();
        if (holder == null) return new Result(null, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), 0, false,
                "registered selector predicate registry is unavailable");
        var lookup = holder.lookup().lookup(Registries.PREDICATE).orElse(null);
        if (lookup == null) return new Result(holder, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), 0, false,
                "registered selector predicate registry is unavailable");
        var visitor = new Visitor(lookup, candidateChunks, capacity);
        for (String value : FullSavedTextInputs.optionValues(selector, "predicate")) {
            String id = value.trim();
            if (id.startsWith("!")) id = id.substring(1).trim();
            Identifier location = Identifier.tryParse(id);
            if (location == null) return new Result(holder, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), visitor.bytes, visitor.capacity,
                    "registered selector predicate identifier changed after accepted parsing: " + value);
            visitor.reference(ResourceKey.create(Registries.PREDICATE, location));
            if (visitor.unmet != null) break;
        }
        return new Result(holder, Collections.unmodifiableSet(visitor.biomes),
                Collections.unmodifiableSet(visitor.full), Collections.unmodifiableSet(visitor.structures), Collections.unmodifiableSet(visitor.storageIds),
                Collections.unmodifiableSet(visitor.entityPredicates), visitor.bytes, visitor.capacity, visitor.unmet);
    }

    private static final class Visitor {
        private final net.minecraft.core.HolderLookup.RegistryLookup<LootItemCondition> registry;
        private final Set<ChunkPos> candidates;
        private final Set<ResourceKey<LootItemCondition>> visiting = new HashSet<>();
        private final LinkedHashSet<ChunkPos> biomes = new LinkedHashSet<>(), full = new LinkedHashSet<>();
        private final LinkedHashSet<ChunkPos> structures = new LinkedHashSet<>();
        private final LinkedHashSet<Identifier> storageIds = new LinkedHashSet<>();
        private final Set<NumberProvider> numberVisiting = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<EntityPredicate> entityPredicates = Collections.newSetFromMap(new IdentityHashMap<>());
        private final java.util.function.LongPredicate capacityCheck;
        private long bytes;
        private boolean capacity;
        private String unmet;

        Visitor(net.minecraft.core.HolderLookup.RegistryLookup<LootItemCondition> registry, Set<ChunkPos> candidates,
                java.util.function.LongPredicate capacityCheck) {
            this.registry = registry; this.candidates = candidates; this.capacityCheck = capacityCheck;
        }
        void reference(ResourceKey<LootItemCondition> key) {
            if (!visiting.add(key)) return; // Original visited-entry guard handles the cycle at execution.
            try { registry.get(key).ifPresent(reference -> visit(reference.value())); }
            finally { visiting.remove(key); }
        }
        void visit(LootItemCondition condition) {
            if (unmet != null || capacity) return;
            if (condition instanceof ConditionReference reference) { reference(reference.name()); return; }
            if (condition instanceof InvertedLootItemCondition inverted) { visit(inverted.term()); return; }
            if (condition instanceof CompositeLootItemCondition composite) {
                if (!(composite instanceof CompositeAccess access)) {
                    unmet = "registered composite selector predicate terms are unavailable"; return;
                }
                for (LootItemCondition term : access.phoenium$predicateTerms()) visit(term);
                return;
            }
            if (condition instanceof LocationCheck location) { location(location); return; }
            // These audited conditions either use the already-granted candidate/scalar services,
            // the same world clock/weather, absent SELECTOR parameters, or the original RNG.
            if (condition instanceof LootItemRandomChanceCondition random) { number(random.chance()); return; }
            if (condition instanceof TimeCheck time) { range(time.value()); return; }
            if (condition instanceof ValueCheckCondition value) {
                number(value.value()); range(value.range()); return;
            }
            if (condition instanceof EntityHasScoreCondition scores) {
                for (IntRange range : scores.scores().values()) range(range);
                return;
            }
            if (condition instanceof WeatherCheck
                    || condition instanceof LootItemKilledByPlayerCondition
                    || condition instanceof ExplosionCondition || condition instanceof MatchTool
                    || condition instanceof DamageSourceCondition || condition instanceof BonusLevelTableCondition
                    || condition instanceof LootItemRandomChanceWithEnchantedBonusCondition
                    || condition instanceof EnchantmentActiveCheck) return;
            if (condition instanceof LootItemEntityPropertyCondition entity) {
                // SELECTOR supplies THIS_ENTITY only. Preserve the original null/empty
                // branches without inspecting a predicate that cannot be reached.
                if (entity.entityTarget() == net.minecraft.world.level.storage.loot.LootContext.EntityTarget.THIS)
                    entity.predicate().ifPresent(this::entity);
            }
            else if (condition instanceof EnvironmentAttributeCheck<?> environment) {
                if (environment.attribute().isPositional()) markBiomeCandidates();
            }
            else if (condition instanceof LootItemBlockStatePropertyCondition)
                return; // No SELECTOR block-state parameter: vanilla's original test returns false.
            else unmet = "registered selector predicate dependency producer pending for " + condition.getClass().getName();
        }
        void entity(EntityPredicate predicate) {
            if (unmet != null || capacity || entityPredicates.contains(predicate)) return;
            long next = Math.addExact(bytes, 64L);
            if (!capacityCheck.test(next)) { capacity = true; return; }
            if (!((Object) predicate instanceof FullEntityPredicateInputs.Access access)
                    || access.phoenium$entityPredicateInputs() == null) {
                unmet = "registered entity predicate lacks its original combination producer"; return;
            }
            entityPredicates.add(predicate); bytes = next;
            for (EntitySubPredicate part : access.phoenium$entityPredicateInputs().parts()) {
                if (part == EntitySubPredicate.ALWAYS_TRUE || part instanceof EntityTypePredicate
                        || part instanceof DistanceToPlayerPredicate || part instanceof MovementPredicate
                        || part instanceof EntityEffectsPredicate || part instanceof EntityNbtPredicate
                        || part instanceof EntityFlagsPredicate || part instanceof EntityEquipmentPredicate
                        || part instanceof PeriodicEntityTickPredicate || part instanceof TeamPredicate
                        || part instanceof EntitySlotsPredicate || part instanceof EntityExactDataComponentsPredicate
                        || part instanceof EntityTagPredicate || part instanceof CubeMobPredicate
                        || part instanceof RaiderPredicate || part instanceof SheepPredicate
                        || part instanceof FishingHookPredicate) continue;
                if (part instanceof EntityLocationPredicate location) {
                    location(new LocationCheck(Optional.of(location.predicate()), BlockPos.ZERO));
                    continue;
                }
                // These branches can follow an entity relationship/supporting position or
                // invoke a player ray/partial component predicate. The current scalar and
                // candidate-location receipt does not grant those additional authorities.
                unmet = "registered entity-property dependency footprint is unsupported for " + part.getClass().getName();
                return;
            }
        }
        void number(NumberProvider provider) {
            if (unmet != null || capacity || provider == null) return;
            if (!numberVisiting.add(provider)) {
                unmet = "registered selector predicate number provider graph is cyclic";
                return;
            }
            try {
                // These branches inspect only immutable provider shape. In particular, they never
                // call getInt/getFloat, consume RNG, query scoreboard, or read command storage.
                if (provider instanceof ConstantValue || provider instanceof ScoreboardValue
                        || provider instanceof EnchantmentLevelProvider) return;
                if (provider instanceof UniformGenerator uniform) {
                    number(uniform.min()); number(uniform.max()); return;
                }
                if (provider instanceof BinomialDistributionGenerator binomial) {
                    number(binomial.n()); number(binomial.p()); return;
                }
                if (provider instanceof Sum sum) {
                    for (NumberProvider summand : sum.summands()) number(summand);
                    return;
                }
                if (provider instanceof StorageValue storage) {
                    addStorageId(storage.storage()); return;
                }
                if (provider instanceof EnvironmentAttributeValue environment) {
                    EnvironmentAttribute<?> attribute = environment.attribute();
                    if (attribute.isPositional()) markBiomeCandidates();
                    return;
                }
                unmet = "registered selector predicate number provider is unsupported: "
                        + provider.getClass().getName();
            } finally { numberVisiting.remove(provider); }
        }
        void range(IntRange range) {
            if (range == null || unmet != null || capacity) return;
            if (!(range instanceof IntRangeAccess access)) {
                unmet = "registered selector predicate IntRange providers are unavailable";
                return;
            }
            number(access.phoenium$min()); number(access.phoenium$max());
        }
        private void markBiomeCandidates() {
            for (ChunkPos candidate : candidates) {
                if (biomes.contains(candidate)) continue;
                long next = Math.addExact(bytes, 64L);
                if (!capacityCheck.test(next)) { capacity = true; return; }
                biomes.add(candidate); bytes = next;
            }
        }
        private void addStorageId(Identifier id) {
            if (storageIds.contains(id)) return;
            long next = Math.addExact(bytes, STORAGE_ID_BYTES);
            if (!capacityCheck.test(next)) { capacity = true; return; }
            storageIds.add(id); bytes = next;
        }
        void location(LocationCheck check) {
            LocationPredicate predicate = check.predicate().orElse(null);
            if (predicate == null) return;
            boolean needsStructures = predicate.structures().isPresent();
            boolean needsFull = predicate.smokey().isPresent()
                    || predicate.light().isPresent() || predicate.block().isPresent()
                    || predicate.fluid().isPresent() || predicate.canSeeSky().isPresent();
            boolean needsBiomes = predicate.biomes().isPresent();
            if (!needsFull && !needsBiomes && !needsStructures) return;
            for (ChunkPos candidate : candidates) {
                for (ChunkPos affected : affected(candidate, check.offset())) {
                    if (needsFull && !add(full, affected)) return;
                    if (needsStructures && !add(structures, affected)) return;
                    if (needsBiomes) {
                        // LocationPredicate.getBiome samples a fiddled position. Its ±2 block
                        // corner can cross one additional chunk in either axis, including when a
                        // separate FULL clause is present.
                        for (int x = affected.x() - 1; x <= affected.x() + 1; x++) {
                            for (int z = affected.z() - 1; z <= affected.z() + 1; z++) {
                                if (!add(biomes, new ChunkPos(x, z))) return;
                            }
                        }
                    }
                }
            }
        }
        private boolean add(Set<ChunkPos> target, ChunkPos position) {
            if (target.contains(position)) return true;
            long next = Math.addExact(bytes, 64L);
            if (!capacityCheck.test(next)) { capacity = true; return false; }
            target.add(position); bytes = next; return true;
        }
    }

    /** A route fact publishes only the candidate chunk. Cover both possible edge chunks after a
     * finite block offset without reading the candidate's mutable position before the original. */
    private static Set<ChunkPos> affected(ChunkPos candidate, BlockPos offset) {
        int lowX = Math.floorDiv(Math.addExact(Math.multiplyExact(candidate.x(), 16), offset.getX()), 16);
        int highX = Math.floorDiv(Math.addExact(Math.addExact(Math.multiplyExact(candidate.x(), 16), 15), offset.getX()), 16);
        int lowZ = Math.floorDiv(Math.addExact(Math.multiplyExact(candidate.z(), 16), offset.getZ()), 16);
        int highZ = Math.floorDiv(Math.addExact(Math.addExact(Math.multiplyExact(candidate.z(), 16), 15), offset.getZ()), 16);
        var result = new LinkedHashSet<ChunkPos>();
        for (int x = lowX; x <= highX; x++) for (int z = lowZ; z <= highZ; z++) result.add(new ChunkPos(x, z));
        return result;
    }
}
