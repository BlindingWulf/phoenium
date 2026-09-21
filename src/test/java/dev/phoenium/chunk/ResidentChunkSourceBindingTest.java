package dev.phoenium.chunk;

import com.ishland.c2me.rewrites.chunksystem.common.*;
import com.ishland.flowsched.scheduler.ItemHolder;
import dev.phoenium.service.ResidentBlockEntityTickerBinding;
import dev.phoenium.service.VaultBlockEntityTickContinuation;
import dev.phoenium.core.*;
import dev.phoenium.topology.RegionRoute;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResidentChunkSourceBindingTest {
    static final BlockPos POS = VaultContinuationNativeTest.POS;
    @BeforeAll static void bootstrap() { BlockEntityChunkQueryDiagnosticTest.bootstrap(); }

    @Test void vanillaProofRetainsExactFullSlotAndRuntime() throws Exception {
        try (var f = new VaultContinuationNativeTest.Fixture()) {
            var binding = ResidentChunkSourceBinding.capture(f.world, ChunkPos.pack(POS));
            assertNotNull(binding); assertEquals("VANILLA", binding.provider());
            assertTrue(binding.current());
            ((FullHolderEvidence) f.holder(POS)).phoenium$futures().set(ChunkStatus.FULL.getIndex(), null);
            assertFalse(binding.current());
        }
    }

    @Test void settledC2meProofNeedsNoVanillaSlotAndSurvivesOperationAcknowledgement() throws Exception {
        try (var f = new ProviderFixture()) {
            f.publish();
            var binding = f.capture();
            assertNotNull(binding); assertEquals("C2ME", binding.provider());
            assertTrue(binding.current()); assertNull(f.nativeFixture.fullSlot(POS));
            assertTrue(BlockEntityExternalReadPreparation.ready(f.nativeFixture.world, POS));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"backing", "slot", "not-full", "state", "chunk", "holder",
        "lifetime", "unload", "runtime", "world-close", "retired"})
    void exactProviderReplacementInvalidatesReceipt(String replacement) throws Exception {
        try (var f = new ProviderFixture()) {
            f.publish(); var binding = f.capture(); assertNotNull(binding);
            f.invalidate(replacement);
            assertFalse(binding.current(), replacement);
        }
    }

    @Test void providerSlotMustSettleSuccessfullyBeforeReadiness() throws Exception {
        try (var f = new ProviderFixture()) {
            f.publishState();
            assertNull(f.capture());
            f.backing.slot(NewChunkStatus.SERVER_ACCESSIBLE).completeExceptionally(new IllegalStateException("provider failed"));
            assertNull(f.capture());
        }
    }

    @Test void newlyRegisteredVaultBindsResidentProviderBeforeFirstNativeTick() throws Exception {
        try (var f = new ProviderFixture()) {
            f.publish();
            try (var binding = f.tickerBinding(); var tick = f.nativeFixture.capture()) {
                assertTrue(binding.activate());
                tick.sourceBinding(binding.source());
                AtomicInteger entered = new AtomicInteger();
                f.nativeFixture.layout(false);
                // Ordinary resident cache hit; no inherited FULL slot.
                cacheSource(f.nativeFixture);
                f.nativeFixture.owned(() -> tick.original(() -> {
                    entered.incrementAndGet();
                    f.nativeFixture.ticker.tick();
                }));
                assertEquals(1, entered.get());
                assertEquals(VaultBlockEntityTickContinuation.Phase.READ_SUSPENDED, tick.phase());
                assertNotNull(tick.neighbors()); assertNotNull(tick.query());
                assertTrue(tick.current()); assertNull(tick.failure());
            }
        }
    }

    @Test void registrationWaitsForExactPublicationAndActivatesOnceWithoutReplay() throws Exception {
        try (var f = new ProviderFixture(); var binding = f.tickerBinding()) {
            AtomicInteger ticks = new AtomicInteger();
            assertFalse(binding.activate()); assertEquals(0, ticks.get());
            assertEquals(ResidentBlockEntityTickerBinding.State.REGISTERED_WAITING_SOURCE, binding.state());
            f.publishState();
            assertFalse(binding.activate());
            f.backing.slot(NewChunkStatus.SERVER_ACCESSIBLE).complete(null);
            assertTrue(binding.ready());
            assertEquals(ResidentBlockEntityTickerBinding.State.SOURCE_BOUND_READY, binding.state());
            assertTrue(binding.activate()); ticks.incrementAndGet();
            assertEquals(ResidentBlockEntityTickerBinding.State.ACTIVE, binding.state());
            assertTrue(binding.ready()); assertEquals(1, ticks.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"entity", "ticker", "holder", "lifetime", "unload", "runtime", "world-close", "topology", "retired", "slot"})
    void pendingReplacementCannotActivateAfterLatePublication(String replacement) throws Exception {
        try (var f = new ProviderFixture(); var binding = f.tickerBinding()) {
            assertFalse(binding.activate());
            if (replacement.equals("entity")) {
                var other = new VaultBlockEntity(POS, Blocks.VAULT.defaultBlockState());
                other.setLevel(f.nativeFixture.world);
                f.nativeFixture.source.getBlockEntities().put(POS, other);
            } else if (replacement.equals("ticker")) {
                f.nativeFixture.ticker.entity = new VaultBlockEntity(POS, Blocks.VAULT.defaultBlockState());
                f.nativeFixture.ticker.entity.setLevel(f.nativeFixture.world);
            } else if (replacement.equals("topology")) {
                var generation = new TopologyGeneration(2);
                f.nativeFixture.base.directory.publish(generation, Map.of(), Map.of(f.nativeFixture.base.worldId,
                    new RegionRoute(f.nativeFixture.base.ownerId, generation, new OwnerEpoch(0))));
            } else f.invalidate(replacement);
            assertFalse(binding.activate());
            assertEquals(ResidentBlockEntityTickerBinding.State.RETIRED, binding.state());
            // Late success of the original slot must not revive a retired registration.
            f.originalSlot.complete(null);
            assertFalse(binding.activate());
        }
    }

    @Test void providerResidentRemovalReleasesEvidenceWithoutRetiringBackingLifetime() throws Exception {
        try (var f = new ProviderFixture()) {
            f.publish(); var binding = f.capture(); assertNotNull(binding);
            var holder = f.nativeFixture.holder(POS);
            var lifetime = BlockEntityExternalReadPreparation.lifetime(holder);
            ChunkHolderLifetime.residentSourceRetired(holder);
            assertFalse(binding.current()); assertFalse(f.publication.pendingCurrent());
            assertNull(lifetime.services().residentPublication(lifetime));
            assertTrue(BlockEntityExternalReadPreparation.current(holder, lifetime));
        }
    }

    @Test void replacementProviderPublicationCannotReviveAnOlderPendingRegistration() throws Exception {
        try (var f = new ProviderFixture(); var old = f.tickerBinding()) {
            assertFalse(old.activate());
            ChunkHolderLifetime.residentSourceRetired(f.nativeFixture.holder(POS));
            f.backing.replaceSlot(NewChunkStatus.SERVER_ACCESSIBLE, new CompletableFuture<>());
            var replacement = new C2meResidentPublication(C2meFullAccess.capture(f.context, C2meFullAccess.Kind.FULL), () -> true);
            var life = BlockEntityExternalReadPreparation.lifetime(f.nativeFixture.holder(POS));
            life.services().residentPublication(life.lifecycle(), replacement);
            f.backing.setItem(new ChunkState(f.nativeFixture.source, f.proto, ChunkStatus.FULL, false));
            f.nativeFixture.base.providerHolders.put(ChunkPos.pack(POS), f.nativeFixture.holder(POS));
            replacement.published(C2meFullAccess.capture(f.context, C2meFullAccess.Kind.FULL).requireFullState());
            f.backing.slot(NewChunkStatus.SERVER_ACCESSIBLE).complete(null);
            assertFalse(old.activate()); assertEquals(ResidentBlockEntityTickerBinding.State.RETIRED, old.state());
            try (var fresh = f.tickerBinding()) { assertTrue(fresh.activate()); }
            replacement.retire();
        }
    }

    @Test void replacementTickerCanBindIndependentlyAfterOldRegistrationRetires() throws Exception {
        try (var f = new ProviderFixture(); var old = f.tickerBinding()) {
            old.close();
            var replacement = f.nativeFixture.new Ticker();
            replacement.entity = f.nativeFixture.vault;
            try (var next = new ResidentBlockEntityTickerBinding(f.nativeFixture.world, replacement,
                    f.nativeFixture.base.directory, f.nativeFixture.key)) {
                assertFalse(next.activate()); f.publish();
                assertFalse(old.activate()); assertTrue(next.activate());
            }
        }
    }

    @Test void nativeSetBlockCreatesRegistersAndStartsExactResidentC2meVault() throws Exception {
        try (var f = new ProviderFixture()) {
            var n = f.nativeFixture; f.publish();
            n.source.getBlockEntities().clear();
            n.put(POS, Blocks.STONE.defaultBlockState());
            n.source.setLoaded(true);
            n.source.setFullStatus(() -> net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING);
            FullDecisionWitnessSupport.set(n.world, net.minecraft.world.level.Level.class, "blockEntityTickers", new java.util.ArrayList<>());
            FullDecisionWitnessSupport.set(n.world, net.minecraft.world.level.Level.class, "pendingBlockEntityTickers", new java.util.ArrayList<>());
            var light = FullDecisionWitnessSupport.stub(net.minecraft.server.level.ThreadedLevelLightEngine.class,
                java.util.List.of(new FullDecisionWitnessSupport.Answer(net.minecraft.server.level.ThreadedLevelLightEngine.class.getMethod("checkBlock", BlockPos.class), args -> null)));
            FullDecisionWitnessSupport.set(n.base.world.cache, net.minecraft.server.level.ServerChunkCache.class, "lightEngine", light);
            var debug = FullDecisionWitnessSupport.stub(net.minecraft.util.debug.LevelDebugSynchronizers.class,
                java.util.List.of(new FullDecisionWitnessSupport.Answer(net.minecraft.util.debug.LevelDebugSynchronizers.class.getMethod("registerBlockEntity", net.minecraft.world.level.block.entity.BlockEntity.class), args -> null)));
            FullDecisionWitnessSupport.set(n.world, net.minecraft.server.level.ServerLevel.class, "debugSynchronizers", debug);
            cacheSource(n);
            var captured = new java.util.ArrayList<net.minecraft.world.level.block.entity.TickingBlockEntity>();
            var bindings = new java.util.ArrayList<ResidentBlockEntityTickerBinding>();
            ResidentTickerTestHooks.ADDED.set((level, ticker) -> {
                assertSame(n.world, level); captured.add(ticker);
                bindings.add(new ResidentBlockEntityTickerBinding(n.world, ticker, n.base.directory, n.key));
            });
            try {
                // Real four-argument Level.setBlock inherited by ServerLevel, native LevelChunk conversion and registration.
                assertTrue(n.world.setBlock(POS, Blocks.VAULT.defaultBlockState(), 16, 0));
            } finally { ResidentTickerTestHooks.ADDED.remove(); }
            assertEquals(1, captured.size());
            var nativeTicker = captured.getFirst();
            var entity = (VaultBlockEntity) n.source.getBlockEntities().get(POS);
            assertNotSame(n.vault, entity); assertSame(n.world, entity.getLevel());
            assertSame(entity, ((dev.phoenium.service.LocalNativeTickerAccess) nativeTicker).phoenium$localBlockEntity());
            FullDecisionWitnessSupport.set(entity.getServerData(), net.minecraft.world.level.block.entity.vault.VaultServerData.class, "stateUpdatingResumesAt", Long.MAX_VALUE);
            FullDecisionWitnessSupport.set(entity.getServerData(), net.minecraft.world.level.block.entity.vault.VaultServerData.class, "isDirty", true);
            n.layout(false);
            try (var binding = bindings.getFirst(); var tick = new VaultBlockEntityTickContinuation(n.world, nativeTicker,
                    n.base.directory, n.key, 1, () -> true, n::ownerFree)) {
                assertTrue(binding.activate()); tick.sourceBinding(binding.source());
                n.owned(() -> tick.original(nativeTicker::tick));
                assertEquals(VaultBlockEntityTickContinuation.Phase.READ_SUSPENDED, tick.phase());
                assertTrue(tick.current()); assertNotNull(tick.query());
                assertNull(n.fullSlot(POS));
            }
        }
    }
    private static void cacheSource(VaultContinuationNativeTest.Fixture n) throws Exception {
        FullDecisionWitnessSupport.set(n.base.world.cache, net.minecraft.server.level.ServerChunkCache.class,
            "lastChunkPos", new long[] {ChunkPos.pack(POS), Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE});
        FullDecisionWitnessSupport.set(n.base.world.cache, net.minecraft.server.level.ServerChunkCache.class,
            "lastChunkStatus", new ChunkStatus[] {ChunkStatus.FULL, null, null, null});
        FullDecisionWitnessSupport.set(n.base.world.cache, net.minecraft.server.level.ServerChunkCache.class,
            "lastChunk", new ChunkAccess[] {n.source, null, null, null});
    }

    @ParameterizedTest @ValueSource(strings = {"current", "slot", "holder", "lifetime", "chunk", "runtime"})
    void externalReadUsesQueriedProviderReceiptAndRejectsReplacement(String replacement) throws Exception {
        try (var f = new ProviderFixture()) {
            f.publish();
            try (var read = new BlockEntityExternalReadPreparation(f.nativeFixture.world, POS,
                    f.nativeFixture.base.directory.snapshot().generation(), 1, 1, () -> true, f.nativeFixture::ownerFree)) {
                read.prepare(); assertTrue(read.prepared()); assertNull(f.nativeFixture.fullSlot(POS));
                assertFalse(BlockEntityExternalReadPreparation.ready(f.nativeFixture.world, POS.east(2)),
                    "source receipt cannot establish readiness in another chunk");
                if (!replacement.equals("current")) {
                    f.invalidate(replacement); assertFalse(read.prepared()); assertFalse(read.current());
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"publish", "slot", "holder"})
    void ownerFreeExternalReadWaitsForProviderFutureAndExactPublication(String completion) throws Exception {
        try (var f = new ProviderFixture()) {
            var n = f.nativeFixture;
            // This is the provider's native selector result, not an inherited FULL-slot publication.
            var selected = f.originalSlot.thenApply(ignored -> net.minecraft.server.level.ChunkResult.<ChunkAccess>of(n.source));
            var selections = new AtomicInteger();
            var cache = FullDecisionWitnessSupport.stub(net.minecraft.server.level.ServerChunkCache.class,
                java.util.List.of(new FullDecisionWitnessSupport.Answer(dev.phoenium.mixin.RegionalServerChunkCacheAccess.class.getMethod(
                    "phoenium$naturalSpawnFuture", int.class, int.class, ChunkStatus.class, boolean.class), args -> {
                        assertEquals(ChunkPos.containing(POS).x(), args[0]); assertEquals(ChunkPos.containing(POS).z(), args[1]);
                        assertEquals(ChunkStatus.FULL, args[2]); assertEquals(true, args[3]);
                        n.ownerFree(); selections.incrementAndGet(); return selected;
                    })));
            FullDecisionWitnessSupport.set(cache, net.minecraft.server.level.ServerChunkCache.class, "chunkMap", n.base.fixtureMap);
            FullDecisionWitnessSupport.set(cache, net.minecraft.server.level.ServerChunkCache.class, "level", n.world);
            FullDecisionWitnessSupport.set(n.base.world, FullTadpoleDecisionTest.Fixture.class, "cache", cache);
            try (var read = new BlockEntityExternalReadPreparation(n.world, POS,
                    n.base.directory.snapshot().generation(), 1, 1, () -> true, n::ownerFree)) {
                read.prepare(); assertFalse(read.prepared()); assertEquals(1, selections.get());
                if (completion.equals("publish")) {
                    f.publish(); read.prepare(); assertTrue(read.prepared());
                } else {
                    f.invalidate(completion); f.originalSlot.complete(null);
                    read.prepare(); assertFalse(read.current()); assertFalse(read.prepared());
                }
                assertNull(n.fullSlot(POS));
                assertEquals(1, selections.get(), "preparation does not retry or replace native selection");
            }
        }
    }

    @Test void externalReadPreparationStillRejectsOwnedInvocation() throws Exception {
        try (var f = new ProviderFixture()) {
            f.publish();
            try (var read = new BlockEntityExternalReadPreparation(f.nativeFixture.world, POS,
                    f.nativeFixture.base.directory.snapshot().generation(), 1, 1, () -> true, () -> {})) {
                f.nativeFixture.owned(() -> assertThrows(IllegalStateException.class, read::prepare));
            }
        }
    }

    static final class ProviderFixture implements AutoCloseable {
        final VaultContinuationNativeTest.Fixture nativeFixture = new VaultContinuationNativeTest.Fixture();
        final ItemHolder backing;
        final ChunkLoadingContext context;
        final C2meResidentPublication publication;
        final CompletableFuture<Void> originalSlot;
        final ProtoChunk proto;
        boolean open = true;
        ProviderFixture() throws Exception {
            var holder = nativeFixture.holder(POS);
            nativeFixture.base.services.trackHolder(((FullHolderEvidence) holder).phoenium$lifetime().lifecycle(), holder);
            proto = new ProtoChunk(ChunkPos.containing(POS), UpgradeData.EMPTY,
                nativeFixture.world, nativeFixture.world.palettedContainerFactory(), null);
            backing = new ItemHolder(ChunkPos.containing(POS), holder);
            backing.setItem(new ChunkState(proto, proto, ChunkStatus.SPAWN, false));
            context = new ChunkLoadingContext(backing, nativeFixture.base.fixtureMap, new TheChunkSystem(backing));
            publication = new C2meResidentPublication(C2meFullAccess.capture(context, C2meFullAccess.Kind.FULL), () -> open);
            originalSlot = backing.slot(NewChunkStatus.SERVER_ACCESSIBLE);
            nativeFixture.base.services.residentPublication(((FullHolderEvidence) holder).phoenium$lifetime().lifecycle(), publication);
            ((FullHolderEvidence) holder).phoenium$futures().set(ChunkStatus.FULL.getIndex(), null);
        }
        void publishState() {
            backing.setItem(new ChunkState(nativeFixture.source, proto, ChunkStatus.FULL, false));
            nativeFixture.base.providerHolders.put(ChunkPos.pack(POS), nativeFixture.holder(POS));
            publication.published(C2meFullAccess.capture(context, C2meFullAccess.Kind.FULL).requireFullState());
        }
        void publish() { publishState(); originalSlot.complete(null); }
        ResidentChunkSourceBinding capture() { return ResidentChunkSourceBinding.capture(nativeFixture.world, ChunkPos.pack(POS)); }
        ResidentBlockEntityTickerBinding tickerBinding() {
            return new ResidentBlockEntityTickerBinding(nativeFixture.world, nativeFixture.ticker,
                nativeFixture.base.directory, nativeFixture.key);
        }
        void invalidate(String kind) {
            switch (kind) {
                case "backing" -> context.theChunkSystem().replaceHolder(null);
                case "slot" -> backing.replaceSlot(NewChunkStatus.SERVER_ACCESSIBLE, new CompletableFuture<>());
                case "not-full" -> backing.setItem(new ChunkState(nativeFixture.source, proto, ChunkStatus.SPAWN, false));
                case "state" -> backing.setItem(new ChunkState(nativeFixture.source, proto, ChunkStatus.FULL, false));
                case "chunk" -> backing.setItem(new ChunkState(new LevelChunk(nativeFixture.world, proto, null), proto, ChunkStatus.FULL, false));
                case "holder" -> nativeFixture.replaceHolder(POS);
                case "lifetime" -> nativeFixture.lifetime(nativeFixture.holder(POS));
                case "unload" -> ChunkHolderLifetime.retired(nativeFixture.holder(POS));
                case "runtime" -> open = false;
                case "world-close" -> nativeFixture.base.services.stopResidentHolders();
                case "retired" -> publication.retire();
                default -> throw new AssertionError(kind);
            }
        }
        public void close() { publication.retire(); nativeFixture.close(); }
    }
}
