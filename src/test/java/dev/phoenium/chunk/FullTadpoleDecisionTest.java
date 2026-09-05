package dev.phoenium.chunk;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.frog.FrogVariant;
import net.minecraft.world.entity.animal.frog.FrogVariants;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.variant.SpawnPrioritySelectors;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTicks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original FULL supplier, EntityType/Entity/Tadpole loading, Mob conversion, frog finalization and collision search.
 * World registration/transport, a minimal variant registry and the proposed getter boundary are controlled.
 * No transformed production FULL binding and no real entity-manager/client acceptance are claimed. */
final class FullTadpoleDecisionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void savedUnderageBranchDoesNotConvertAndCompletesOriginalSupplier() throws Exception {
        var f = new Fixture(Tadpole.ticksToBeFrog - 1);
        LevelChunk result = f.run();
        assertEquals(1, f.inserted.size()); assertInstanceOf(Tadpole.class, f.inserted.getFirst());
        assertEquals(0, f.difficultyQueries); assertEquals(0, f.sounds);
        assertEquals(RandomSource.create(9157L).nextLong(), f.soundRandom.nextLong());
        assertSame(result, f.actual()); assertEquals(true, field(result, LevelChunk.class, "loaded")); assertFalse(f.fullSlot.isDone());
    }

    @Test void savedMatureAgeEvenWhenAgeLockedRunsActualConversionBeforeFullPublication() throws Exception {
        var f = new Fixture(Tadpole.ticksToBeFrog);
        LevelChunk result = f.run();
        assertEquals(1, f.difficultyQueries); assertEquals(1, f.sounds);
        assertEquals(2, f.inserted.size());
        Frog frog = assertInstanceOf(Frog.class, f.inserted.get(0));
        Tadpole source = assertInstanceOf(Tadpole.class, f.inserted.get(1));
        assertTrue(source.isRemoved()); assertTrue(source.isAgeLocked()); assertTrue(frog.isPersistenceRequired());
        assertEquals(List.of("difficulty", "sound", "fresh-frog", "loaded-root"), f.events);
        RandomSource expectedSound = RandomSource.create(9157L); expectedSound.nextLong();
        assertEquals(expectedSound.nextLong(), f.soundRandom.nextLong(), "one original sound-seed consumption");
        RandomSource expectedWorld = RandomSource.create(73031L);
        expectedWorld.nextInt(1); expectedWorld.nextIntBetweenInclusive(100, 140);
        expectedWorld.triangle(0, 0.11485000000000001); expectedWorld.nextFloat();
        assertEquals(expectedWorld.nextLong(), f.random.nextLong(), "variant, jump memory, follow-range bonus, handedness in original order");
        assertTrue(f.localReads > 1, "collision search also uses the exact unpublished object");
        assertSame(result, f.actual()); assertFalse(f.fullSlot.isDone());
        assertNull(f.holder.getChunkIfPresent(ChunkStatus.FULL));
        Method publish = GenerationChunkHolder.class.getDeclaredMethod("completeFuture", ChunkStatus.class, ChunkAccess.class);
        publish.setAccessible(true); publish.invoke(f.holder, ChunkStatus.FULL, result);
        assertSame(result, f.fullSlot.join().orElse(null));
    }

    @Test void nestedOriginalFutureSelectionReturnsTheVeryFullSlotWhoseSupplierIsStillRunning() throws Exception {
        var f = new Fixture(Tadpole.ticksToBeFrog); f.detectCycle = true;
        // The endpoint stops before managedBlock; there is no hanging thread or invented timeout result.
        LevelChunk result = f.run();
        assertEquals(1, f.cycleDetections); assertFalse(f.fullSlot.isDone());
        assertTrue(f.inserted.isEmpty()); assertEquals(0, f.sounds);
        assertSame(result, f.actual(), "EntityType catches the injected diagnostic exception; supplier may still return");
    }

    @Test void exactObjectBoundaryRejectsOtherWorkNeighborAndPublicVisibility() throws Exception {
        var f = new Fixture(Tadpole.ticksToBeFrog); f.run();
        assertThrows(ChunkProtocolException.class, () -> f.borrow(f.work, 0, 0));
        f.admitted = true;
        try {
            assertThrows(ChunkProtocolException.class, () -> f.borrow(new Object(), 0, 0));
            assertThrows(ChunkProtocolException.class, () -> f.borrow(f.work, 1, 0));
            assertFalse(f.fullSlot.isDone()); assertNull(f.holder.getChunkIfPresent(ChunkStatus.FULL));
        } finally { f.admitted = false; }
    }

    @Test void caughtSoundFailureKeepsTeamConversionPrefixAndSupplierCanSucceedWithoutReplay() throws Exception {
        var f = new Fixture(Tadpole.ticksToBeFrog); f.failSound = true;
        LevelChunk result = f.run();
        assertTrue(f.inserted.isEmpty(), "failure is before addFreshEntity and before stream root insertion");
        assertEquals(1, f.sounds); assertEquals(1, f.difficultyQueries);
        assertEquals(1, f.scoreboard.getPlayerTeam("amber").getPlayers().size(), "converted frog membership survives");
        assertSame(result, f.actual()); assertNull(field(result, LevelChunk.class, "postLoad"));
        result.runPostLoad(); assertEquals(1, f.sounds, "normal outer postLoad completion cleared its callback");
        assertFalse(f.fullSlot.isDone(), "a caught nested error does not itself publish FULL");
    }

    @Test void uncaughtPostLoadFailureLeavesCallbackButDoesNotAuthorizeReplayOrPublication() throws Exception {
        var f = new Fixture(Tadpole.ticksToBeFrog); f.failRootInsertion = true;
        assertThrows(java.util.concurrent.CompletionException.class, f::run);
        assertEquals(2, f.inserted.size()); assertTrue(f.inserted.get(1).isRemoved());
        assertEquals(1, f.sounds); assertFalse(f.fullSlot.isDone());
        assertNotNull(field(f.actual(), LevelChunk.class, "postLoad")); assertEquals(false, field(f.actual(), LevelChunk.class, "loaded"));
        assertTrue(f.executions == 1, "retained callback is evidence, not retry permission");
    }

    @Test void originalDifficultyNullBranchIsDifferentAndMustNotBeReplacedByInProgressObject() throws Exception {
        var f = new Fixture(Tadpole.ticksToBeFrog - 1);
        f.nullQuery = true; var absent = f.level.getCurrentDifficultyAt(new BlockPos(8, 8, 8));
        var expected = new net.minecraft.world.DifficultyInstance(Difficulty.HARD, 100000L, 0L, 0F);
        assertEquals(expected.getEffectiveDifficulty(), absent.getEffectiveDifficulty());
        assertEquals(0, f.localReads); assertEquals(0, f.moonReads);
    }

    @Test void duplicateDeliveryOfOriginalSupplierTaskDoesNotRepeatACommittedConversion() throws Exception {
        var f = new Fixture(Tadpole.ticksToBeFrog); f.duplicateDelivery = true;
        f.run(); assertEquals(1, f.sounds); assertEquals(1, f.difficultyQueries); assertEquals(2, f.inserted.size());
        assertFalse(f.fullSlot.isDone());
    }

    @Test void reloadImposterUsesOriginalLegacyPostLoadCallbackAndReusesItsActualChunk() throws Exception {
        var f = new Fixture(Tadpole.ticksToBeFrog);
        Method callback = net.minecraft.world.level.chunk.storage.SerializableChunkData.class.getDeclaredMethod(
                "postLoadChunk", ServerLevel.class, List.class, List.class); callback.setAccessible(true);
        var processor = (LevelChunk.PostLoadProcessor)callback.invoke(null, f.level, f.proto.getEntities(), List.of());
        LevelChunk original = new LevelChunk(f.level, f.proto, processor);
        f.input = new ImposterProtoChunk(original, false);
        @SuppressWarnings("unchecked") var futures = (AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>>)
                field(f.holder, GenerationChunkHolder.class, "futures");
        for (ChunkStatus status : ChunkStatus.getStatusList()) if (status != ChunkStatus.FULL)
            futures.set(status.getIndex(), CompletableFuture.completedFuture(ChunkResult.of(f.input)));
        assertSame(original, f.run()); assertEquals(1, f.difficultyQueries); assertEquals(1, f.sounds);
        assertEquals(List.of("difficulty", "sound", "fresh-frog", "legacy-root"), f.events);
        assertSame(f.input, f.holder.getChunkIfPresentUnchecked(ChunkStatus.FULL.getParent())); assertFalse(f.fullSlot.isDone());
    }

    static final class Fixture {
        final ArrayDeque<Runnable> execution = new ArrayDeque<>();
        final List<Entity> inserted = new ArrayList<>(); final List<String> events = new ArrayList<>();
        final Object work = new Object(); final net.minecraft.server.ServerScoreboard scoreboard;
        final CompletableFuture<ChunkResult<ChunkAccess>> fullSlot = new CompletableFuture<>();
        final ServerLevel level; final ChunkHolder holder; final ServerChunkCache cache; final ProtoChunk proto;
        final java.util.Map<Long, ChunkHolder> visibleHolders = new java.util.HashMap<>();
        final net.minecraft.world.level.TicketStorage tickets = new net.minecraft.world.level.TicketStorage();
        java.util.function.Function<Object[], ChunkAccess> queryEndpoint;
        java.util.function.Function<Object[], Long> gameTimeEndpoint = args -> 100000L;
        ChunkAccess input;
        final RandomSource random = RandomSource.create(73031L);
        final RandomSource soundRandom = RandomSource.create(9157L);
        boolean admitted, detectCycle, failSound, failRootInsertion, nullQuery, duplicateDelivery;
        int difficultyQueries, localReads, sounds, executions, cycleDetections, moonReads, nextId;

        Fixture(int age) throws Exception {
            var base = new MinecraftLightReadFixture(false, true);
            var frogs = new MappedRegistry<FrogVariant>(Registries.FROG_VARIANT, Lifecycle.stable());
            frogs.register(FrogVariants.TEMPERATE, new FrogVariant(new ClientAsset.ResourceTexture(
                    Identifier.withDefaultNamespace("entity/frog/frog_temperate")), SpawnPrioritySelectors.fallback(0)), RegistrationInfo.BUILT_IN);
            List<Registry<?>> registries = new ArrayList<>();
            base.registries.registries().forEach(entry -> registries.add(entry.value())); registries.add(frogs);
            RegistryAccess.Frozen registry = new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
            holder = stub(ChunkHolder.class, List.of(
                    new Answer(ChunkHolder.class.getMethod("getTicketLevel"), args -> 33),
                    new Answer(ChunkHolder.class.getMethod("getFullStatus"), args -> FullChunkStatus.FULL)));
            set(holder, GenerationChunkHolder.class, "pos", ChunkPos.ZERO);
            set(holder, GenerationChunkHolder.class, "highestAllowedStatus", ChunkStatus.FULL);
            set(holder, GenerationChunkHolder.class, "startedWork", new AtomicReference<>(ChunkStatus.FULL));
            var task = allocate(ChunkGenerationTask.class); set(task, ChunkGenerationTask.class, "targetStatus", ChunkStatus.FULL);
            set(holder, GenerationChunkHolder.class, "task", new AtomicReference<>(task));
            visibleHolders.put(ChunkPos.ZERO.pack(), holder);
            ChunkMap map = stub(ChunkMap.class, List.of(new Answer(ChunkMap.class.getDeclaredMethod("getVisibleChunkIfPresent", long.class), args -> visibleHolders.get((long) args[0]))));
            cache = allocate(ServerChunkCache.class); set(cache, ServerChunkCache.class, "chunkMap", map);
            set(cache, ServerChunkCache.class, "ticketStorage", tickets);
            var blockTicks = new LevelTicks<net.minecraft.world.level.block.Block>(pos -> true);
            var fluidTicks = new LevelTicks<net.minecraft.world.level.material.Fluid>(pos -> true);
            var players = stub(net.minecraft.server.players.PlayerList.class, List.of(new Answer(
                    net.minecraft.server.players.PlayerList.class.getMethod("getPlayerByName", String.class), args -> null)));
            set(players, net.minecraft.server.players.PlayerList.class, "players", List.of());
            var server = stub(net.minecraft.server.MinecraftServer.class, List.of(
                    new Answer(net.minecraft.server.MinecraftServer.class.getMethod("getPlayerList"), args -> players), new Answer(
                    net.minecraft.server.MinecraftServer.class.getMethod("debugSubscribers"),
                    args -> new net.minecraft.util.debug.ServerDebugSubscribers(null))));
            scoreboard = new net.minecraft.server.ServerScoreboard(server); scoreboard.addPlayerTeam("amber");
            var waypoints = new net.minecraft.server.waypoints.ServerWaypointManager();
            level = stub(ServerLevel.class, List.of(
                    new Answer(ServerLevel.class.getMethod("getServer"), args -> server),
                    new Answer(ServerLevel.class.getMethod("getWaypointManager"), args -> waypoints),
                    new Answer(ServerLevel.class.getMethod("dimensionType"), args -> base.level.dimensionType()),
                    new Answer(ServerLevel.class.getMethod("registryAccess"), args -> registry),
                    new Answer(ServerLevel.class.getMethod("palettedContainerFactory"), args -> base.factory),
                    new Answer(ServerLevel.class.getMethod("getNextEntityId"), args -> ++nextId),
                    new Answer(ServerLevel.class.getMethod("enabledFeatures"), args -> FeatureFlags.DEFAULT_FLAGS),
                    new Answer(ServerLevel.class.getMethod("getDifficulty"), args -> Difficulty.HARD),
                    new Answer(ServerLevel.class.getMethod("getGameTime"), args -> gameTimeEndpoint.apply(args)),
                    new Answer(ServerLevel.class.getMethod("getOverworldClockTime"), args -> 100000L),
                    new Answer(ServerLevel.class.getMethod("getMoonBrightness", BlockPos.class), args -> { moonReads++; return 0.75F; }),
                    new Answer(ServerLevel.class.getMethod("getRandom"), args -> random),
                    new Answer(ServerLevel.class.getMethod("getGameRules"), args -> new GameRules(FeatureFlags.DEFAULT_FLAGS)),
                    new Answer(ServerLevel.class.getMethod("getBiome", BlockPos.class), args -> registry.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)),
                    new Answer(ServerLevel.class.getMethod("getScoreboard"), args -> scoreboard),
                    new Answer(ServerLevel.class.getMethod("getWorldBorder"), args -> null),
                    new Answer(ServerLevel.class.getMethod("getBlockTicks"), args -> blockTicks),
                    new Answer(ServerLevel.class.getMethod("getFluidTicks"), args -> fluidTicks),
                    new Answer(ServerLevel.class.getMethod("getChunk", int.class, int.class, ChunkStatus.class, boolean.class), args -> {
                        if (queryEndpoint != null) return queryEndpoint.apply(args);
                        assertSame(ChunkStatus.FULL, args[2]); assertEquals(false, args[3]);
                        if (nullQuery) return null;
                        return borrow(work, (int)args[0], (int)args[1]);
                    }),
                    new Answer(ServerLevel.class.getMethod("addWorldGenChunkEntities", Stream.class), args -> {
                        ((Stream<?>)args[0]).forEach(entity -> { inserted.add((Entity)entity); events.add("loaded-root");
                            if (failRootInsertion) throw new IllegalStateException("controlled registration after prefix"); }); return null;
                    }),
                    new Answer(ServerLevel.class.getMethod("addLegacyChunkEntities", Stream.class), args -> {
                        ((Stream<?>)args[0]).forEach(entity -> { inserted.add((Entity)entity); events.add("legacy-root");
                            if (failRootInsertion) throw new IllegalStateException("controlled registration after prefix"); }); return null;
                    }),
                    new Answer(ServerLevel.class.getMethod("addFreshEntity", Entity.class), args -> {
                        inserted.add((Entity)args[0]); events.add("fresh-frog"); return true;
                    }),
                    new Answer(ServerLevel.class.getMethod("playSeededSound", Entity.class, double.class, double.class, double.class,
                            Holder.class, SoundSource.class, float.class, float.class, long.class), args -> {
                        sounds++; events.add("sound"); if (failSound) throw new IllegalStateException("controlled sound failure"); return null;
                    })));
            set(level, Level.class, "random", random);
            set(level, Level.class, "thread", Thread.currentThread());
            set(level, Level.class, "blockEntityTickers", new ArrayList<>());
            set(level, Level.class, "pendingBlockEntityTickers", new ArrayList<>());
            set(level, ServerLevel.class, "debugSynchronizers", new net.minecraft.util.debug.LevelDebugSynchronizers(level));
            set(level, Level.class, "soundSeedGenerator", soundRandom);
            proto = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY, level, base.factory, null);
            input = proto;
            proto.setInhabitedTime(400000L);
            proto.setPersistedStatus(ChunkStatus.FULL.getParent());
            CompoundTag tag = new CompoundTag(); tag.putString("id", "minecraft:tadpole");
            tag.putInt("Age", age); tag.putBoolean("AgeLocked", true); tag.putString("Team", "amber");
            ListTag position = new ListTag(); position.add(DoubleTag.valueOf(8)); position.add(DoubleTag.valueOf(8)); position.add(DoubleTag.valueOf(8));
            tag.put("Pos", position); proto.addEntity(tag);
            var futures = new AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>>(ChunkStatus.getStatusList().size());
            for (ChunkStatus status : ChunkStatus.getStatusList()) futures.set(status.getIndex(), status == ChunkStatus.FULL ? fullSlot : CompletableFuture.completedFuture(ChunkResult.of(proto)));
            set(holder, GenerationChunkHolder.class, "futures", futures);
        }

        LevelChunk run() {
            return run(holder, input);
        }

        LevelChunk run(ChunkHolder target, ChunkAccess source) {
            var context = new WorldGenContext(level, null, null, null, execution::add, ignored -> {});
            ChunkPos pos = target.getPos();
            CompletableFuture<ChunkAccess> result = ChunkStatusTasks.full(context, null, StaticCache2D.create(pos.x(), pos.z(), 0, (x, z) -> target), source);
            assertFalse(result.isDone()); assertEquals(1, execution.size());
            admitted = true; executions++;
            try { Runnable delivery = execution.remove(); delivery.run(); if (duplicateDelivery) delivery.run(); }
            finally { admitted = false; }
            return (LevelChunk)result.join();
        }

        LevelChunk actual() {
            return ((ImposterProtoChunk)holder.getChunkIfPresentUnchecked(ChunkStatus.FULL.getParent())).getWrapped();
        }

        LevelChunk borrow(Object token, int x, int z) {
            if (!admitted || token != work || x != 0 || z != 0) throw new ChunkProtocolException("exact conversion object access denied");
            // First request is original ServerLevel.getCurrentDifficultyAt; subsequent ones are collision reads.
            if (localReads++ == 0) {
                difficultyQueries++; events.add("difficulty");
                assertFalse(fullSlot.isDone());
                assertNull(holder.getChunkIfPresent(ChunkStatus.FULL));
                assertEquals(400000L, actual().getInhabitedTime());
                assertTrue(actual().getBlockState(new BlockPos(8, 8, 8)).is(Blocks.AIR));
                try {
                    assertEquals(false, field(actual(), LevelChunk.class, "loaded"));
                    Method query = ServerChunkCache.class.getDeclaredMethod("getChunkFutureMainThread", int.class, int.class, ChunkStatus.class, boolean.class);
                    query.setAccessible(true);
                    assertSame(fullSlot, query.invoke(cache, x, z, ChunkStatus.FULL, false));
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                if (detectCycle) { cycleDetections++; throw new IllegalStateException("bounded stop before self-dependent managedBlock"); }
            }
            return actual();
        }
    }
}
