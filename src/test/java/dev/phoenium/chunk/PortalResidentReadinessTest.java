package dev.phoenium.chunk;

import dev.phoenium.live.*;
import dev.phoenium.scheduler.RegionScheduler;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual runtime portal Authority.prepare with controlled native public chunk getters.
 * Models publication changes with the existing provider fixture; no installed reachability claim. */
class PortalResidentReadinessTest {
    @BeforeAll static void bootstrap(){NativeItemPortalTransferTest.bootstrap();}
    @ParameterizedTest @ValueSource(strings={"slot","state","lifetime","retired","runtime","world-close"})
    void exactProviderGenerationMustOutliveRetainedNativeChunkIdentity(String change)throws Exception {
        try(var f=new Fixture()){
            BooleanSupplier ready=f.prepare();assertTrue(ready.getAsBoolean());var binding=f.provider.capture();assertNotNull(binding);
            assertNull(f.provider.nativeFixture.fullSlot(ResidentChunkSourceBindingTest.POS),"C2ME has no vanilla FULL slot");
            f.provider.invalidate(change);
            // The controlled native public getters still expose the same holder/chunk. The
            // existing provider receipt independently detects the changed publication/lifetime.
            assertFalse(binding.current());
            assertFalse(ready.getAsBoolean(),"portal retained stale provider publication: "+change);
        }
    }
    @Test void preparationCannotSelectPublicDependencyUnderAnOwner()throws Exception {
        try(var f=new Fixture()){f.provider.nativeFixture.owned(()->assertThrows(IllegalStateException.class,f::prepare));}
    }
    static final class Fixture implements AutoCloseable {
        final ResidentChunkSourceBindingTest.ProviderFixture provider=new ResidentChunkSourceBindingTest.ProviderFixture();
        final PhoeniumLiveRuntime live=new PhoeniumLiveRuntime(org.slf4j.LoggerFactory.getLogger(PortalResidentReadinessTest.class));
        final NativePortalTransfer.Authority authority;
        final ItemEntity item;
        Fixture()throws Exception {
            var n=provider.nativeFixture;provider.publish();
            var cache=stub(ServerChunkCache.class,List.of(
                new Answer(ServerChunkCache.class.getMethod("getChunk",int.class,int.class,ChunkStatus.class,boolean.class),a->n.source),
                new Answer(ServerChunkCache.class.getMethod("getChunkNow",int.class,int.class),a->n.source)));
            set(cache,ServerChunkCache.class,"chunkMap",n.base.fixtureMap);
            set(n.base.world,n.base.world.getClass(),"cache",cache);
            MinecraftServer server=n.world.getServer();set(server,MinecraftServer.class,"levels",Map.of(Level.OVERWORLD,n.world));
            set(live,PhoeniumLiveRuntime.class,"serverInstance",server);
            set(live,PhoeniumLiveRuntime.class,"state",PhoeniumLiveRuntime.State.RUNNING);
            set(live,PhoeniumLiveRuntime.class,"scheduler",new RegionScheduler(Map.of(n.base.ownerId,n.base.owner,n.base.otherId,n.base.other),n.base.directory,n.base.barrier));
            set(live,PhoeniumLiveRuntime.class,"regionalDispatcher",n.dispatcher);
            n.dispatcher.installClosureEncloser(keys->true); // preconverged fixture's real directory is still checked
            Object manager=field(live,PhoeniumLiveRuntime.class,"itemPortalTransitions");
            authority=(NativePortalTransfer.Authority)field(manager,ItemPortalTransitionManager.class,"authority");
            // Only the native entity type's tracking radius is an input to preparation.
            item=stub(ItemEntity.class,List.of(new Answer(net.minecraft.world.entity.Entity.class.getMethod("getType"),a->net.minecraft.world.entity.EntityTypes.ITEM)));
        }
        BooleanSupplier prepare(){var n=provider.nativeFixture;return authority.prepare(item,
            new TeleportTransition(n.world,new Vec3(100,65,160),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING),n.key);}
        public void close(){provider.close();}
    }
}
