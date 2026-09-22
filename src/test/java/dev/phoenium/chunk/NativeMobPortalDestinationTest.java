package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real native mob tick -> Entity.handlePortal -> NetherPortalBlock -> PortalForcer
 * -> PoiManager; only world/provider endpoints are controlled. */
class NativeMobPortalDestinationTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() { if(PortalDestinationTestHooks.mobs!=null) PortalDestinationTestHooks.mobs.close(); PortalDestinationTestHooks.mobs=null; }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> cases() {
        return java.util.stream.Stream.of("zombified_piglin","chicken","pig","enderman").flatMap(type->
            java.util.stream.Stream.of(true,false).flatMap(forward->java.util.stream.Stream.of(true,false)
                .map(ready->org.junit.jupiter.params.provider.Arguments.of(type,forward,ready))));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("cases")
    void nativeMobTickReleasesOwnerBeforeFirstPendingPoiQuery(String type,boolean forward,boolean ready) throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture();
        var manager=new dev.phoenium.live.MobPortalTransitionManager(f.authority,f.objects);
        PortalDestinationTestHooks.mobs=manager;
        f.levelData=(net.minecraft.world.level.storage.LevelData)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{net.minecraft.world.level.storage.ServerLevelData.class},(proxy,method,args)->
                method.getName().equals("getDifficulty") ? net.minecraft.world.Difficulty.NORMAL : null);
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var registries=NativePortalDestinationPreparationTest.dynamicRegistries();
        set(f.overworld,Level.class,"registryAccess",registries);set(f.nether,Level.class,"registryAccess",registries);
        var source=forward ? f.overworld : f.nether;var destination=forward ? f.nether : f.overworld;
        var route=f.key(source);
        EntityType<?> entityType=switch(type) {
            case "zombified_piglin" -> EntityTypes.ZOMBIFIED_PIGLIN;
            case "chicken" -> EntityTypes.CHICKEN;
            case "enderman" -> EntityTypes.ENDERMAN;
            default -> EntityTypes.PIG;
        };
        var mob=(Mob)entityType.create(source,EntitySpawnReason.COMMAND);
        assertNotNull(mob); mob.setPos(.44,65.31,.02); f.publish(mob,route);
        mob.hurtTime=7;mob.invulnerableTime=9;mob.tickCount=83;
        set(mob,LivingEntity.class,"fallFlyTicks",7);
        set(mob,LivingEntity.class,"currentImpulseContextResetGraceTime",5);
        // Native Nether/POI enumeration remains real. Only the failed create endpoint's
        // final result is controlled so the same reached query can continue into native transfer.
        NativePortalDestinationPreparationTest.setPortal(mob,args->{
            ((Portal)Blocks.NETHER_PORTAL).getPortalDestination(source,mob,BlockPos.ZERO);
            return f.transition(destination);
        });
        var reads=new AtomicInteger();
        f.chunkRead=args->{
            reads.incrementAndGet();
            assertSame(ChunkStatus.EMPTY,args[2]); assertEquals(true,args[3]);
            var dependency=new java.util.concurrent.CompletableFuture<Void>();
            if(ready)dependency.complete(null);
            ChunkSynchronousAccessGuard.beforeWait(dependency::isDone);
            dependency.complete(null);
            assertTrue(OwnershipContext.current().isEmpty());
            assertTrue(f.runtimes.values().stream().noneMatch(r->r.leases().hasActiveLease()));
            return null;
        };
        assertDoesNotThrow(()->f.dispatcher.executeAndDrain(route,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
            manager.begin(mob);
            try { mob.tick(); assertTrue(manager.complete(mob)); } finally { manager.finish(mob); }
        }));
        assertEquals(0,reads.get(),"lookup must wait until the enclosing owner callback has returned");
        assertEquals(1,manager.pendingCount());
        assertEquals(1,manager.advance());
        assertTrue(reads.get()>1,"native POI enumeration must reach each required EMPTY query");
        assertEquals(0,manager.advance());
        assertTrue(mob.isRemoved());assertNotSame(mob,f.replacement);assertSame(destination,f.replacement.level());
        assertEquals(mob.getUUID(),f.replacement.getUUID());
        assertEquals(1,java.util.Collections.frequency(f.order,"insert"));
        assertEquals(1,java.util.Collections.frequency(f.order,"base-suffix"));
        assertEquals(6,mob.hurtTime);assertEquals(8,mob.invulnerableTime);
        assertEquals(0,field(mob,LivingEntity.class,"fallFlyTicks"));
        assertEquals(4,field(mob,LivingEntity.class,"currentImpulseContextResetGraceTime"));
        assertEquals(300,mob.getPortalCooldown());assertEquals(300,f.replacement.getPortalCooldown());
        assertTrue(f.order.indexOf("source-retire")<f.order.indexOf("insert"));
        assertTrue(f.order.indexOf("post")<f.order.indexOf("base-suffix"));
    }

    record Rig(NativeItemPortalTransferTest.Fixture f,dev.phoenium.live.MobPortalTransitionManager manager,Mob mob) {
        void tick() {
            f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
                manager.begin(mob);try{mob.tick();assertTrue(manager.complete(mob));}finally{manager.finish(mob);}
            });
        }
    }
    Rig rig()throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture();
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var manager=new dev.phoenium.live.MobPortalTransitionManager(f.authority,f.objects);PortalDestinationTestHooks.mobs=manager;
        var registries=NativePortalDestinationPreparationTest.dynamicRegistries();
        set(f.overworld,Level.class,"registryAccess",registries);set(f.nether,Level.class,"registryAccess",registries);
        var mob=(Mob)EntityTypes.CHICKEN.create(f.overworld,EntitySpawnReason.COMMAND);mob.setPos(.4,65,.1);f.publish(mob,f.from);
        NativePortalDestinationPreparationTest.setPortal(mob,args->{
            ChunkSynchronousAccessGuard.beforeWait(()->false);assertTrue(OwnershipContext.current().isEmpty());return f.transition(f.nether);
        });return new Rig(f,manager,mob);
    }
    @ParameterizedTest @ValueSource(strings={"removed","processor","entry","world","shutdown","incarnation","route","position","motion","rotation"})
    void invalidatedSourceCannotRunLookupOrTouchANewerGeneration(String cause)throws Exception {
        var r=rig();var f=r.f();r.tick();
        switch(cause) {
            case "removed" -> f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.ENTITY_PORTAL_SOURCE,ignored->{r.manager.onRemoval(r.mob,Entity.RemovalReason.DISCARDED);r.mob.discard();});
            case "processor" -> r.mob.portalProcess=new PortalProcessor((Portal)Blocks.NETHER_PORTAL,BlockPos.ZERO);
            case "entry" -> r.mob.portalProcess.updateEntryPosition(new BlockPos(1,2,3));
            case "world" -> r.manager.worldClosing(f.overworld);
            case "shutdown" -> r.manager.close();
            case "position" -> r.mob.setPos(.6,65,.1);
            case "motion" -> r.mob.setDeltaMovement(.1,0,0);
            case "rotation" -> r.mob.setYRot(67);
            case "incarnation" -> {
                var binding=f.objects.current(r.mob.getUUID().toString()).orElseThrow();assertTrue(f.identities.retirePortalPublication(binding,f.a,r.mob.getId()));
                var newer=EntityTypes.CHICKEN.create(f.overworld,EntitySpawnReason.COMMAND);newer.setUUID(r.mob.getUUID());f.publish(newer,f.from);
            }
            case "route" -> {
                var generation=new dev.phoenium.core.TopologyGeneration(2);
                var changed=new dev.phoenium.topology.RoutingKey(f.a,1,0);
                f.directory.publish(generation,java.util.Map.of(f.from,new dev.phoenium.topology.RegionRoute(f.ar,generation,new dev.phoenium.core.OwnerEpoch(0)),changed,new dev.phoenium.topology.RegionRoute(f.ar,generation,new dev.phoenium.core.OwnerEpoch(0)),f.to,new dev.phoenium.topology.RegionRoute(f.br,generation,new dev.phoenium.core.OwnerEpoch(0))));
                var binding=f.objects.current(r.mob.getUUID().toString()).orElseThrow();f.objects.updateRoutingKey(binding.objectId(),changed,binding.objectEpoch());
            }
        }
        assertEquals(0,r.manager.advance());assertEquals(0,r.manager.pendingCount());assertFalse(f.order.contains("insert"));
    }
    @Test void completedLookupCannotResumeAfterItsExactSourceIsReplaced()throws Exception {
        var r=rig();var calls=new AtomicInteger();
        NativePortalDestinationPreparationTest.setPortal(r.mob,args->{
            assertTrue(OwnershipContext.current().isEmpty());calls.incrementAndGet();
            assertEquals(0,r.manager.advance(),"reentrant coordinator must not replay query");
            r.mob.portalProcess=new PortalProcessor((Portal)Blocks.NETHER_PORTAL,BlockPos.ZERO);
            return r.f.transition(r.f.nether);
        });
        r.tick();assertEquals(0,r.manager.advance());assertEquals(1,calls.get());assertEquals(0,r.manager.advance());assertFalse(r.f.order.contains("insert"));
    }
    @Test void changedReadinessRefusesNativeEffectsAndRetiresOnlyThisOperation()throws Exception {
        var r=rig();r.tick();r.f.preparation=()->r.f.ready=false;
        assertThrows(IllegalStateException.class,r.manager::advance);assertFalse(r.mob.isRemoved());assertEquals(0,r.manager.pendingCount());assertEquals(0,r.manager.advance());
        assertFalse(r.f.order.contains("insert"));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=NativeItemPortalTransferTest.FailurePoint.class,names={"BEFORE_CLAIM","CLAIMED","CALLBACK","INSERTED","POST"})
    void partialMobInsertionFailureCleansExactIncarnationOnce(NativeItemPortalTransferTest.FailurePoint point)throws Exception {
        var r=rig();r.f.failAt=point;r.tick();
        assertSame(r.f.failure,assertThrows(IllegalStateException.class,r.manager::advance));
        assertEquals(0,r.manager.pendingCount());assertEquals(0,r.manager.advance());
        assertTrue(r.mob.isRemoved());assertEquals(0,r.f.objects.liveCount());
        assertEquals(0,r.f.visible.values().stream().mapToInt(java.util.Map::size).sum());
        assertFalse(r.f.order.contains("base-suffix"));assertEquals(1,java.util.Collections.frequency(r.f.order,"insert"));
    }
    @Test void failedMobCannotDeleteNewerSameUuidDestination()throws Exception {
        var r=rig();Entity[] newer=new Entity[1];
        r.f.afterInsertion=()->{
            var old=r.f.objects.current(r.mob.getUUID().toString()).orElseThrow();assertTrue(r.f.identities.retirePortalPublication(old,r.f.b,r.f.replacement.getId()));
            var next=EntityTypes.CHICKEN.create(r.f.nether,EntitySpawnReason.COMMAND);next.setUUID(r.mob.getUUID());newer[0]=next;r.f.publish(next,r.f.to);
        };
        r.tick();assertThrows(IllegalStateException.class,r.manager::advance);
        assertSame(newer[0],r.f.visible.get(r.f.nether).get(r.mob.getUUID()));assertFalse(newer[0].isRemoved());
        assertEquals(1,r.f.objects.liveCount());assertEquals(0,r.manager.advance());
    }
}
