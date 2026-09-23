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
    @AfterEach void reset() { if(PortalDestinationTestHooks.mobs!=null) PortalDestinationTestHooks.mobs.close(); PortalDestinationTestHooks.mobs=null; PortalDestinationTestHooks.cubeTail=null; PortalDestinationTestHooks.horseTail=null; }

    @Test void distinctVanillaMerchantCallersAreBoundWithTheirNativeOverrides() throws Exception {
        assertTrue(dev.phoenium.live.MobPortalTransitionManager.supports(
            allocate(net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader.class)));
        assertTrue(dev.phoenium.live.MobPortalTransitionManager.supports(
            allocate(net.minecraft.world.entity.npc.villager.Villager.class)));
        assertTrue(dev.phoenium.live.MobPortalTransitionManager.supports(
            allocate(net.minecraft.world.entity.monster.skeleton.Skeleton.class)));
        assertEquals(net.minecraft.world.entity.npc.villager.AbstractVillager.class,
            net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader.class
                .getMethod("teleport",net.minecraft.world.level.portal.TeleportTransition.class).getDeclaringClass());
        assertEquals(net.minecraft.world.entity.monster.skeleton.Skeleton.class,
            net.minecraft.world.entity.monster.skeleton.Skeleton.class.getMethod("tick").getDeclaringClass());
    }


    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> cases() {
        var established=java.util.stream.Stream.of("zombified_piglin","chicken","pig","enderman","magma_cube","slime").flatMap(type->
            java.util.stream.IntStream.of(type.equals("magma_cube")||type.equals("slime") ? new int[]{1,2,4} : new int[]{1})
                .boxed().flatMap(size->java.util.stream.Stream.of(true,false).flatMap(forward->java.util.stream.Stream.of(true,false)
                    .map(ready->org.junit.jupiter.params.provider.Arguments.of(type,forward,ready,size)))));
        var directSiblings=java.util.stream.Stream.of("blaze","bogged","cow","elder_guardian","endermite","silverfish","ghast","goat",
            "guardian","hoglin","iron_golem","mooshroom","ocelot","parched","piglin","piglin_brute",
            "pillager","ravager","sheep","snow_golem","stray","turtle","vindicator",
            "witch","wither_skeleton","zoglin","zombie","drowned","husk","skeleton",
            "horse","donkey","mule","skeleton_horse","zombie_horse","llama","trader_llama",
            "wandering_trader","villager")
            .flatMap(type->java.util.stream.Stream.of(true,false).flatMap(forward->java.util.stream.Stream.of(true,false)
                .map(ready->org.junit.jupiter.params.provider.Arguments.of(type,forward,ready,1))));
        return java.util.stream.Stream.concat(established,directSiblings);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("cases")
    void nativeMobTickReleasesOwnerBeforeFirstPendingPoiQuery(String type,boolean forward,boolean ready,int size) throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture();
        var manager=new dev.phoenium.live.MobPortalTransitionManager(f.authority,f.objects);
        PortalDestinationTestHooks.mobs=manager;
        f.levelData=(net.minecraft.world.level.storage.LevelData)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{net.minecraft.world.level.storage.ServerLevelData.class},(proxy,method,args)->
                method.getName().equals("getDifficulty") ? net.minecraft.world.Difficulty.NORMAL : null);
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var registries=NativePortalDestinationPreparationTest.dynamicRegistries();
        set(f.overworld,Level.class,"registryAccess",registries);set(f.nether,Level.class,"registryAccess",registries);
        set(f.overworld,net.minecraft.server.level.ServerLevel.class,"environmentAttributes",
            net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build());
        set(f.nether,net.minecraft.server.level.ServerLevel.class,"environmentAttributes",
            net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build());
        var source=forward ? f.overworld : f.nether;var destination=forward ? f.nether : f.overworld;
        var route=f.key(source);
        EntityType<?> entityType=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
            net.minecraft.resources.Identifier.parse("minecraft:"+type));
        assertNotNull(entityType,"reviewed native type must remain registered: "+type);
        var mob=(Mob)entityType.create(source,EntitySpawnReason.COMMAND);
        assertTrue(dev.phoenium.live.MobPortalTransitionManager.supports(mob),"actual concrete class was not selected: "+type);
        if(!(mob instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob)) {
            Class<?> tick=type.equals("endermite") ? net.minecraft.world.entity.monster.Endermite.class
                : type.equals("silverfish") ? net.minecraft.world.entity.monster.Silverfish.class
                : type.equals("skeleton") ? net.minecraft.world.entity.monster.skeleton.Skeleton.class
                : type.equals("villager") ? net.minecraft.world.entity.npc.villager.Villager.class
                : java.util.Set.of("horse","donkey","mule","skeleton_horse","zombie_horse","llama","trader_llama").contains(type)
                    ? net.minecraft.world.entity.animal.equine.AbstractHorse.class
                : java.util.Set.of("zombified_piglin","zombie","drowned","husk").contains(type)
                    ? net.minecraft.world.entity.monster.zombie.Zombie.class : Mob.class;
            assertEquals(tick,mob.getClass().getMethod("tick").getDeclaringClass());
            assertEquals(Mob.class,mob.getClass().getMethod("baseTick").getDeclaringClass());
        }
        assertEquals(mob instanceof net.minecraft.world.entity.npc.villager.AbstractVillager
                ? net.minecraft.world.entity.npc.villager.AbstractVillager.class : Entity.class,
            mob.getClass().getMethod("teleport",
            net.minecraft.world.level.portal.TeleportTransition.class).getDeclaringClass());
        if(mob instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob cube) {
            cube.setSize(size,true);cube.targetSquish=.8F;cube.squish=.4F;cube.oSquish=.1F;
            PortalDestinationTestHooks.cubeTail=seen->{
                assertSame(mob,seen,"the outer native tail belongs to the retired source");
                f.dispatcher.requireCurrentOwner(route);f.order.add("cube-suffix");
            };
        }
        if(mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse) {
            set(mob,net.minecraft.world.entity.animal.equine.AbstractHorse.class,"mouthCounter",1);
            PortalDestinationTestHooks.horseTail=seen->{assertSame(mob,seen);f.dispatcher.requireCurrentOwner(route);f.order.add("horse-suffix");};
        }
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
            mob.tick();
        }));
        assertEquals(0,reads.get(),"lookup must wait until the enclosing owner callback has returned");
        assertEquals(1,manager.pendingCount());
        if(mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse) {
            assertEquals(1,field(mob,net.minecraft.world.entity.animal.equine.AbstractHorse.class,"mouthCounter"));
            assertFalse(f.order.contains("horse-suffix"));
        }
        if(mob instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob cube) {
            assertEquals(.4F,cube.oSquish);assertEquals(.6F,cube.squish);
            assertEquals(.8F,cube.targetSquish,"outer cube suffix must wait for the native transfer");
            assertFalse(f.order.contains("cube-suffix"));
        }
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
        if(mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse) {
            assertEquals(2,field(mob,net.minecraft.world.entity.animal.equine.AbstractHorse.class,"mouthCounter"));
            assertEquals(1,java.util.Collections.frequency(f.order,"horse-suffix"));
            assertTrue(f.order.indexOf("post")<f.order.indexOf("horse-suffix"));
        }
        if(mob instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob cube) {
            var replacement=(net.minecraft.world.entity.monster.cubemob.AbstractCubeMob)f.replacement;
            assertEquals(size,replacement.getSize());assertEquals(cube.getHealth(),replacement.getHealth());
            assertEquals(type.equals("magma_cube") ? .72F : .48F,cube.targetSquish,.00001F);
            assertEquals(1,java.util.Collections.frequency(f.order,"cube-suffix"));
            assertTrue(f.order.indexOf("post")<f.order.indexOf("cube-suffix"));
            assertEquals(Entity.RemovalReason.CHANGED_DIMENSION,cube.getRemovalReason());
            assertEquals(1,f.objects.liveCount(),"dimension retirement must not split a living cube");
            assertEquals(1,f.visible.values().stream().mapToInt(java.util.Map::size).sum());
        }
    }

    record Rig(NativeItemPortalTransferTest.Fixture f,dev.phoenium.live.MobPortalTransitionManager manager,Mob mob) {
        void tick() {
            f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
                mob.tick();
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
