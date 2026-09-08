package dev.phoenium.chunk;

import java.util.*;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.loot.providers.number.BinomialDistributionGenerator;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.ScoreboardValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import net.minecraft.world.level.storage.loot.predicates.*;

/** Dependency-only inspection of immutable registered selector predicates. Predicate lookup,
 * short circuiting, world reads, serializers and gameplay random remain in EntitySelector's
 * original candidate predicate at the original text-resolution point. */
public final class FullSelectorPredicateInputs {
    public interface CompositeAccess { List<LootItemCondition> phoenium$predicateTerms(); }
    record Result(ReloadableServerRegistries.Holder registries, Set<ChunkPos> biomes,
                  Set<ChunkPos> full, long bytes, boolean capacity, String unmet) { }

    private FullSelectorPredicateInputs() { }

    static Result discover(MinecraftServer server, String selector, Set<ChunkPos> candidateChunks,
                           java.util.function.LongPredicate capacity) {
        ReloadableServerRegistries.Holder holder = server.reloadableRegistries();
        if (holder == null) return new Result(null, Set.of(), Set.of(), 0, false,
                "registered selector predicate registry is unavailable");
        var lookup = holder.lookup().lookup(Registries.PREDICATE).orElse(null);
        if (lookup == null) return new Result(holder, Set.of(), Set.of(), 0, false,
                "registered selector predicate registry is unavailable");
        var visitor = new Visitor(lookup, candidateChunks, capacity);
        for (String value : FullSavedTextInputs.optionValues(selector, "predicate")) {
            String id = value.trim();
            if (id.startsWith("!")) id = id.substring(1).trim();
            Identifier location = Identifier.tryParse(id);
            if (location == null) return new Result(holder, Set.of(), Set.of(), visitor.bytes, visitor.capacity,
                    "registered selector predicate identifier changed after accepted parsing: " + value);
            visitor.reference(ResourceKey.create(Registries.PREDICATE, location));
            if (visitor.unmet != null) break;
        }
        return new Result(holder, Collections.unmodifiableSet(visitor.biomes),
                Collections.unmodifiableSet(visitor.full), visitor.bytes, visitor.capacity, visitor.unmet);
    }

    private static final class Visitor {
        private final net.minecraft.core.HolderLookup.RegistryLookup<LootItemCondition> registry;
        private final Set<ChunkPos> candidates;
        private final Set<ResourceKey<LootItemCondition>> visiting = new HashSet<>();
        private final LinkedHashSet<ChunkPos> biomes = new LinkedHashSet<>(), full = new LinkedHashSet<>();
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
            if (condition instanceof WeatherCheck || condition instanceof TimeCheck
                    || condition instanceof LootItemKilledByPlayerCondition
                    || condition instanceof ExplosionCondition || condition instanceof MatchTool
                    || condition instanceof DamageSourceCondition || condition instanceof BonusLevelTableCondition
                    || condition instanceof LootItemRandomChanceWithEnchantedBonusCondition
                    || condition instanceof EnchantmentActiveCheck) return;
            if (condition instanceof LootItemEntityPropertyCondition)
                unmet = "registered entity-property selector predicate dependency producer pending";
            else if (condition instanceof EnvironmentAttributeCheck<?>)
                unmet = "registered environment-attribute selector predicate dependency producer pending";
            else if (condition instanceof ValueCheckCondition || condition instanceof EntityHasScoreCondition)
                unmet = "registered dynamic numeric selector predicate dependency producer pending";
            else if (condition instanceof LootItemBlockStatePropertyCondition)
                unmet = "registered block-state selector predicate lacks a SELECTOR block-state parameter";
            else unmet = "registered selector predicate dependency producer pending for " + condition.getClass().getName();
        }
        void number(NumberProvider provider) {
            if (provider instanceof ConstantValue || provider instanceof ScoreboardValue) return;
            if (provider instanceof UniformGenerator uniform) {
                number(uniform.min()); number(uniform.max()); return;
            }
            if (provider instanceof BinomialDistributionGenerator binomial) {
                number(binomial.n()); number(binomial.p()); return;
            }
            unmet = "registered random selector predicate number provider pending for " + provider.getClass().getName();
        }
        void location(LocationCheck check) {
            LocationPredicate predicate = check.predicate().orElse(null);
            if (predicate == null) return;
            boolean needsFull = predicate.structures().isPresent() || predicate.smokey().isPresent()
                    || predicate.light().isPresent() || predicate.block().isPresent()
                    || predicate.fluid().isPresent() || predicate.canSeeSky().isPresent();
            boolean needsBiomes = predicate.biomes().isPresent();
            if (!needsFull && !needsBiomes) return;
            for (ChunkPos candidate : candidates) {
                for (ChunkPos affected : affected(candidate, check.offset())) {
                    var target = needsFull ? full : biomes;
                    if (!target.contains(affected)) {
                        long next = Math.addExact(bytes, 64L);
                        if (!capacityCheck.test(next)) { capacity = true; return; }
                        target.add(affected); bytes = next;
                    }
                }
            }
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
