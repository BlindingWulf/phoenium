package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

/** The recorded crash path begins in the native falling-block tick, before insertion. */
class FallingBlockEndPortalNativeTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() { if(PortalDestinationTestHooks.falling!=null)PortalDestinationTestHooks.falling.close();
        PortalDestinationTestHooks.falling=null;PortalDestinationTestHooks.fallingTail=null;
        NativePortalTestHooks.ticket=entity->entity.placePortalTicket(BlockPos.containing(entity.position())); }

    @Test void unretainedSandReachesEndPlatformReadInsideSourceOwner() throws Exception {
        var f = new NativeItemPortalTransferTest.Fixture();
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var sand = (FallingBlockEntity) EntityTypes.FALLING_BLOCK.create(f.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(sand);
        set(sand, FallingBlockEntity.class, "blockState", Blocks.SAND.defaultBlockState());
        sand.setPos(.5, 65.63, -.5);
        sand.noPhysics = true;
        f.publish(sand, f.from);
        var pending = new CompletableFuture<Void>();
        var platformReads = new AtomicInteger();
        f.blockRead = pos -> {
            if (pos.getX() >= 90) {
                platformReads.incrementAndGet();
                ChunkSynchronousAccessGuard.beforeWait(pending::isDone);
            }
            return Blocks.AIR.defaultBlockState();
        };
        sand.setAsInsidePortal((net.minecraft.world.level.block.Portal) Blocks.END_PORTAL, BlockPos.ZERO);
        var failure = assertThrows(ChunkProtocolException.class, () ->
            f.dispatcher.executeAndDrain(f.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> sand.tick()));
        assertTrue(failure.getMessage().contains("unfinished synchronous"));
        assertTrue(platformReads.get() > 0);
        assertTrue(OwnershipContext.current().isEmpty());
    }

    @Test void retainedSandCreatesItsEndDestinationOutsideSourceOwnerAndKeepsNativeTail() throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(net.minecraft.world.level.Level.END);
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var manager=new dev.phoenium.live.FallingBlockPortalTransitionManager(f.authority,f.objects);
        PortalDestinationTestHooks.falling=manager;
        var sand=(FallingBlockEntity)EntityTypes.FALLING_BLOCK.create(f.overworld,EntitySpawnReason.COMMAND);
        assertNotNull(sand);set(sand,FallingBlockEntity.class,"blockState",Blocks.SAND.defaultBlockState());
        sand.setPos(.5,65.63,-.5);sand.noPhysics=true;f.publish(sand,f.from);
        var reads=new AtomicInteger();var tails=new AtomicInteger();var tickets=new AtomicInteger();
        NativePortalTestHooks.ticket=entity->{f.owner(f.to);tickets.incrementAndGet();};
        f.platformPreparation=()->{assertTrue(OwnershipContext.current().isEmpty());assertEquals(0,reads.get());};
        f.blockRead=pos->{if(pos.getX()>=90){f.owner(f.to);reads.incrementAndGet();}
            return pos.getY()==net.minecraft.server.level.ServerLevel.END_SPAWN_POINT.getY()-2
                ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState();};
        PortalDestinationTestHooks.fallingTail=source->{assertSame(sand,source);f.owner(f.from);tails.incrementAndGet();};
        sand.setAsInsidePortal((net.minecraft.world.level.block.Portal)Blocks.END_PORTAL,BlockPos.ZERO);
        f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->sand.tick());
        assertEquals(0,reads.get());assertEquals(1,manager.pendingCount());
        assertEquals(1,manager.advance());assertEquals(0,manager.pendingCount());
        assertEquals(1,f.platformServices);assertEquals(100,reads.get());
        assertEquals(1,tickets.get());
        assertEquals(1,tails.get());assertTrue(sand.isRemoved());
        assertTrue(sand.forceTickAfterTeleportToDuplicate);
        assertInstanceOf(FallingBlockEntity.class,f.replacement);
        assertSame(f.nether,f.replacement.level());
        assertEquals(Blocks.SAND.defaultBlockState(),((FallingBlockEntity)f.replacement).getBlockState());
        assertEquals(1,java.util.Collections.frequency(f.order,"insert"));
        assertEquals(1,f.objects.liveCount());
    }

    @Test void destinationVisibilityWaitRetainsOnePlatformOperationAndDoesNotReplaySourceTick() throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(net.minecraft.world.level.Level.END);
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var manager=new dev.phoenium.live.FallingBlockPortalTransitionManager(f.authority,f.objects);
        PortalDestinationTestHooks.falling=manager;
        var sand=(FallingBlockEntity)EntityTypes.FALLING_BLOCK.create(f.overworld,EntitySpawnReason.COMMAND);
        assertNotNull(sand);
        set(sand,FallingBlockEntity.class,"blockState",Blocks.SAND.defaultBlockState());
        sand.setPos(.5,65.63,-.5);sand.noPhysics=true;f.publish(sand,f.from);
        NativePortalTestHooks.ticket=entity->f.owner(f.to);
        f.blockRead=pos->{if(pos.getX()>=90)f.owner(f.to);return Blocks.AIR.defaultBlockState();};
        f.entityVisible=false;
        sand.setAsInsidePortal((net.minecraft.world.level.block.Portal)Blocks.END_PORTAL,BlockPos.ZERO);
        f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->sand.tick());
        assertEquals(1,manager.pendingCount());
        int nativeTime=sand.time;
        assertEquals(0,manager.advance());
        assertEquals(1,f.platformServices);
        assertEquals(1,f.visibilityDemands);
        assertEquals(0,java.util.Collections.frequency(f.order,"insert"));
        f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->sand.tick());
        assertEquals(nativeTime,sand.time);
        assertEquals(0,manager.advance());
        assertEquals(1,f.platformServices);
        assertEquals(1,f.visibilityDemands);
        f.entityVisible=true;
        assertEquals(1,manager.advance());
        assertEquals(1,f.platformServices);
        assertEquals(1,f.visibilityDemands);
        assertEquals(1,java.util.Collections.frequency(f.order,"insert"));
        assertEquals(0,manager.pendingCount());
        assertTrue(sand.isRemoved());
    }

    @ParameterizedTest @ValueSource(strings={"sand","gravel","anvil"})
    void nativePlatformWritesAndRepeatsForIndependentFallingBlocks(String material) throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(net.minecraft.world.level.Level.END);
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var manager=new dev.phoenium.live.FallingBlockPortalTransitionManager(f.authority,f.objects);
        PortalDestinationTestHooks.falling=manager;
        var block=switch(material){case "sand"->Blocks.SAND;case "gravel"->Blocks.GRAVEL;default->Blocks.ANVIL;};
        var altered=new BlockPos(net.minecraft.server.level.ServerLevel.END_SPAWN_POINT.getX(),
            net.minecraft.server.level.ServerLevel.END_SPAWN_POINT.getY()-1,
            net.minecraft.server.level.ServerLevel.END_SPAWN_POINT.getZ());
        f.blockStates.put(altered,Blocks.STONE.defaultBlockState());
        var pending=new CompletableFuture<Void>();var prepared=new AtomicInteger();var reads=new AtomicInteger();
        f.platformPreparation=()->{assertTrue(OwnershipContext.current().isEmpty());
            ChunkSynchronousAccessGuard.beforeWait(pending::isDone);pending.complete(null);prepared.incrementAndGet();};
        f.blockRead=pos->{if(pos.getX()>=90){assertTrue(prepared.get()>0);f.owner(f.to);reads.incrementAndGet();}
            return f.blockStates.getOrDefault(pos,Blocks.AIR.defaultBlockState());};
        var tickets=new AtomicInteger();NativePortalTestHooks.ticket=e->{f.owner(f.to);tickets.incrementAndGet();};
        for(int operation=0;operation<2;operation++) {
            if(operation==1)f.blockStates.put(altered,Blocks.STONE.defaultBlockState());
            var entity=(FallingBlockEntity)EntityTypes.FALLING_BLOCK.create(f.overworld,EntitySpawnReason.COMMAND);
            assertNotNull(entity);set(entity,FallingBlockEntity.class,"blockState",block.defaultBlockState());
            entity.setPos(.5,65.63,-.5);entity.noPhysics=true;f.publish(entity,f.from);
            entity.setAsInsidePortal((net.minecraft.world.level.block.Portal)Blocks.END_PORTAL,BlockPos.ZERO);
            int before=reads.get(),writes=f.blockWrites;
            f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->entity.tick());
            assertEquals(before,reads.get());assertEquals(1,manager.pendingCount());
            assertEquals(1,manager.advance());assertEquals(0,manager.pendingCount());
            assertEquals(before+100,reads.get());
            assertEquals(operation==0 ? 26 : 1,f.blockWrites-writes);
            assertTrue(entity.isRemoved());assertTrue(entity.forceTickAfterTeleportToDuplicate);
            assertEquals(block.defaultBlockState(),((FallingBlockEntity)f.replacement).getBlockState());
            assertEquals(Blocks.AIR.defaultBlockState(),f.blockStates.get(altered));
        }
        assertEquals(2,prepared.get());assertEquals(2,f.platformServices);assertEquals(2,tickets.get());
        assertEquals(2,f.objects.liveCount());
        long foundation=f.blockStates.entrySet().stream().filter(e->e.getValue().is(Blocks.OBSIDIAN)).count();
        assertEquals(25,foundation);
    }
}
