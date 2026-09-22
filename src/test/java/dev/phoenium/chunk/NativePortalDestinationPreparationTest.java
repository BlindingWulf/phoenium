package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.junit.jupiter.api.*;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Starts at native ItemEntity.tick, before handlePortal and native PortalProcessor.
 * Controlled portal endpoint models an unfinished chunk dependency using the real
 * synchronous guard. No destination is supplied to the retained manager. */
class NativePortalDestinationPreparationTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() { PortalDestinationTestHooks.items=null; PortalDestinationTestHooks.pearls=null; PortalDestinationTestHooks.controlledHit=null;
        NativePortalTestHooks.ticket=entity->entity.placePortalTicket(BlockPos.containing(entity.position()));
    }
    @Test void redSandNativeTickCapturesBeforeUnfinishedDestinationLookup() throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture();
        var item=new ItemEntity(f.overworld,.44,65.31,.02,new ItemStack(Items.RED_SAND,3)); f.publish(item,f.from);
        PortalDestinationTestHooks.items=f.manager;
        var calls=new AtomicInteger(); var dependency=new CompletableFuture<Void>();
        var portal=stub(NetherPortalBlock.class,List.of(new Answer(NetherPortalBlock.class.getMethod("getPortalDestination",ServerLevel.class,Entity.class,BlockPos.class),args->{
            calls.incrementAndGet();
            ChunkSynchronousAccessGuard.beforeWait(dependency::isDone);
            assertTrue(OwnershipContext.current().isEmpty());
            assertTrue(f.runtimes.values().stream().noneMatch(r->r.leases().hasActiveLease()));
            dependency.complete(null); return null;
        })));
        item.setAsInsidePortal(portal,BlockPos.ZERO);
        f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
            f.manager.begin(item);
            try { item.tick(); } finally { f.manager.finish(item); }
            assertEquals(0,calls.get()); assertEquals(1,f.manager.pendingCount());
        });
        assertEquals(1,f.manager.advance()); assertEquals(1,calls.get());
        assertEquals(0,f.manager.advance()); assertFalse(item.isRemoved());
        assertEquals(List.of("base-suffix","item-suffix"),f.order);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true","true,false","false,true","false,false"})
    void nativeNetherSearchReachesEmptyCreateOutsideOwner(boolean forward,boolean ready) throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(); installNativeLookup(f);
        var source=forward ? f.overworld : f.nether;
        var destination=forward ? f.nether : f.overworld;
        var item=new ItemEntity(source,.44,65.31,.02,new ItemStack(forward ? Items.RED_SAND : Items.COBBLESTONE,3)); f.publish(item,f.key(source));
        PortalDestinationTestHooks.items=f.manager;
        var dependency=new CompletableFuture<Void>(); if(ready) dependency.complete(null);
        var reads=new AtomicInteger();
        f.chunkRead=args->{
            assertSame(net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY,args[2]); assertEquals(true,args[3]);
            ChunkSynchronousAccessGuard.beforeWait(dependency::isDone);
            assertTrue(OwnershipContext.current().isEmpty());
            assertTrue(f.runtimes.values().stream().noneMatch(r->r.leases().hasActiveLease()));
            // Reentrant owner-free coordination cannot replay preparation while its lookup is on stack.
            assertEquals(0,f.manager.advance()); reads.incrementAndGet(); dependency.complete(null); return null;
        };
        item.setAsInsidePortal((net.minecraft.world.level.block.Portal)net.minecraft.world.level.block.Blocks.NETHER_PORTAL,BlockPos.ZERO);
        tickItem(f,item);
        assertEquals(0,reads.get()); assertEquals(1,f.manager.advance()); assertTrue(reads.get()>0);
        int count=reads.get(); assertEquals(0,f.manager.advance()); assertEquals(count,reads.get());
        assertEquals(3,item.getItem().getCount()); assertFalse(item.isRemoved());
        assertEquals(List.of("base-suffix","item-suffix"),f.order);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true","true,false","false,true","false,false"})
    void thrownPearlNativeTickRetainsItsSingleContinuationBeforeLookup(boolean forward,boolean ready) throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(); installNativeLookup(f);
        PortalDestinationTestHooks.pearls=pearlManager(f);
        var source=forward ? f.overworld : f.nether;
        var pearl=new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl(EntityTypes.ENDER_PEARL,source);
        pearl.setPos(.44,65.31,.02); pearl.setDeltaMovement(.1,0,.2); f.publish(pearl,f.key(source));
        set(pearl,Entity.class,"firstTick",false);
        set(pearl,net.minecraft.world.entity.projectile.Projectile.class,"hasBeenShot",true);
        PortalDestinationTestHooks.controlledHit=net.minecraft.world.phys.BlockHitResult.miss(pearl.position(),net.minecraft.core.Direction.UP,BlockPos.ZERO);
        pearl.setAsInsidePortal((net.minecraft.world.level.block.Portal)net.minecraft.world.level.block.Blocks.NETHER_PORTAL,BlockPos.ZERO);
        var dependency=new CompletableFuture<Void>(); if(ready) dependency.complete(null);
        var reads=new AtomicInteger(); f.chunkRead=args->{
            reads.incrementAndGet(); ChunkSynchronousAccessGuard.beforeWait(dependency::isDone);
            assertSame(net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY,args[2]); assertEquals(true,args[3]);
            assertTrue(f.runtimes.values().stream().noneMatch(r->r.leases().hasActiveLease()));
            assertTrue(OwnershipContext.current().isEmpty()); assertEquals(0,PortalDestinationTestHooks.pearlCall("advance"));
            dependency.complete(null); return null;
        };
        f.dispatcher.executeAndDrain(f.key(source),RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
            pearl.tick(); assertEquals(0,reads.get()); assertEquals(1,PortalDestinationTestHooks.pearlCall("pendingCount"));
            assertEquals(0,f.manager.pendingCount(),"a thrown pearl never enters the item continuation");
        });
        assertEquals(1,PortalDestinationTestHooks.pearlCall("advance")); assertTrue(reads.get()>0);
        assertEquals(0,PortalDestinationTestHooks.pearlCall("advance"));
    }
    static void tickItem(NativeItemPortalTransferTest.Fixture f,ItemEntity item) {
        f.dispatcher.executeAndDrain(f.key(item.level()),RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
            f.manager.begin(item); try { item.tick(); } finally { f.manager.finish(item); }
        });
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"failure","processor","world","remove","incarnation"})
    void earlyPearlPreparationRejectsFailureAndStaleCompletion(String cause) throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture();PortalDestinationTestHooks.pearls=pearlManager(f);
        var pearl=new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl(EntityTypes.ENDER_PEARL,f.overworld);
        pearl.setPos(0,65,0);f.publish(pearl,f.from);
        set(pearl,Entity.class,"firstTick",false);set(pearl,net.minecraft.world.entity.projectile.Projectile.class,"hasBeenShot",true);
        PortalDestinationTestHooks.controlledHit=net.minecraft.world.phys.BlockHitResult.miss(pearl.position(),net.minecraft.core.Direction.UP,BlockPos.ZERO);
        var failure=new IllegalArgumentException("original pearl destination failure");var reads=new AtomicInteger();
        setPortal(pearl,args->{
            assertTrue(OwnershipContext.current().isEmpty());reads.incrementAndGet();
            switch(cause) {
                case "failure" -> throw failure;
                case "processor" -> pearl.portalProcess=new PortalProcessor((net.minecraft.world.level.block.Portal)net.minecraft.world.level.block.Blocks.NETHER_PORTAL,BlockPos.ZERO);
                case "world" -> PortalDestinationTestHooks.pearlCall("worldClosing",f.overworld);
                case "remove" -> f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.ENTITY_PORTAL_SOURCE,ignored->pearl.discard());
                case "incarnation" -> {
                    var old=f.objects.current(pearl.getUUID().toString()).orElseThrow();
                    assertTrue(f.identities.retirePortalPublication(old,f.a,pearl.getId()));
                    var newer=new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl(EntityTypes.ENDER_PEARL,f.overworld);
                    newer.setUUID(pearl.getUUID());f.publish(newer,f.from);
                }
            }
            return f.transition(f.nether);
        });
        f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->pearl.tick());
        assertEquals(0,reads.get());
        if(cause.equals("failure")) assertSame(failure,assertThrows(IllegalArgumentException.class,()->PortalDestinationTestHooks.pearlCall("advance")));
        else assertEquals(0,PortalDestinationTestHooks.pearlCall("advance"));
        assertEquals(1,reads.get());assertEquals(0,PortalDestinationTestHooks.pearlCall("pendingCount"));
        assertEquals(0,PortalDestinationTestHooks.pearlCall("advance"));assertFalse(f.order.contains("insert"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true","true,false","false,true","false,false"})
    void earlyItemPreparationThenNativeReplacementBothMaterialsAndDirections(boolean forward,boolean ready) throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(); PortalDestinationTestHooks.items=f.manager;
        var source=forward ? f.overworld : f.nether; var destination=forward ? f.nether : f.overworld;
        var item=new ItemEntity(source,.44,65.31,.02,new ItemStack(forward ? Items.RED_SAND : Items.COBBLESTONE,3)); f.publish(item,f.key(source));
        item.setPickUpDelay(31); set(item,ItemEntity.class,"age",417);
        var calls=new AtomicInteger();
        setPortal(item,args->{
            calls.incrementAndGet(); ChunkSynchronousAccessGuard.beforeWait(()->ready);
            assertTrue(OwnershipContext.current().isEmpty()); assertEquals(0,f.manager.advance());
            return f.transition(destination);
        });
        NativePortalTestHooks.suffix=e->{ f.owner(f.key(e.level())); f.order.add("item-suffix");
            try { set(e,ItemEntity.class,"age",418); set(e,ItemEntity.class,"pickupDelay",30); } catch(Exception failure) {throw new AssertionError(failure);} };
        tickItem(f,item); assertEquals(0,calls.get()); assertEquals(1,f.manager.advance());
        assertEquals(1,calls.get()); assertEquals(0,f.manager.advance());
        var replacement=(ItemEntity)f.replacement; assertEquals(item.getUUID(),replacement.getUUID());
        assertNotSame(item,replacement); assertSame(destination,replacement.level()); assertEquals(3,replacement.getItem().getCount());
        assertEquals(417,replacement.getAge()); assertEquals(31,field(replacement,ItemEntity.class,"pickupDelay"));
        assertEquals(418,item.getAge()); assertEquals(1,Collections.frequency(f.order,"item-suffix"));
        assertEquals(1,Collections.frequency(f.order,"insert")); assertEquals(1,f.objects.liveCount());
    }
    @Test void originalPreparationFailureIsTerminalAndDoesNotReplayPrefix() throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(); PortalDestinationTestHooks.items=f.manager; var item=f.item(f.overworld);
        var failure=new IllegalArgumentException("original portal search failure"); var calls=new AtomicInteger();
        setPortal(item,args->{calls.incrementAndGet(); assertTrue(OwnershipContext.current().isEmpty()); throw failure;});
        tickItem(f,item); assertSame(failure,assertThrows(IllegalArgumentException.class,f.manager::advance));
        assertEquals(1,calls.get()); assertEquals(0,f.manager.advance()); assertEquals(List.of(),f.order); assertFalse(item.isRemoved());
    }
    @Test void earlyRedSandTransferPreservesTotalQuantityThroughNativeMerge() throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture();PortalDestinationTestHooks.items=f.manager;
        var target=new ItemEntity(f.nether,0,65,0,new ItemStack(Items.RED_SAND,60));f.publish(target,f.to);f.mergeTargets.add(target);
        var item=new ItemEntity(f.overworld,0,65,0,new ItemStack(Items.RED_SAND,3));f.publish(item,f.from);
        setPortal(item,args->f.transition(f.nether));tickItem(f,item);
        assertEquals(1,f.manager.advance());
        assertEquals(63,f.visible.values().stream().flatMap(map->map.values().stream()).mapToInt(e->((ItemEntity)e).getItem().getCount()).sum());
        assertTrue(item.isRemoved());assertEquals(63,target.getItem().getCount());
        assertEquals(1,Collections.frequency(f.order,"insert"));assertEquals(1,Collections.frequency(f.order,"item-suffix"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"entry","processor","world","remove","incarnation"})
    void staleEarlyAttemptCannotApplyACompletedSearch(String cause) throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(); PortalDestinationTestHooks.items=f.manager; var item=f.item(f.overworld);
        setPortal(item,args->{
            switch(cause) {
                case "entry" -> item.portalProcess.updateEntryPosition(new BlockPos(1,2,3));
                case "processor" -> item.portalProcess=new PortalProcessor((net.minecraft.world.level.block.Portal)net.minecraft.world.level.block.Blocks.NETHER_PORTAL,BlockPos.ZERO);
                case "world" -> f.manager.worldClosing(f.overworld);
                case "remove" -> f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.ENTITY_PORTAL_SOURCE,ignored->item.remove(Entity.RemovalReason.DISCARDED));
                case "incarnation" -> {
                    var old=f.objects.current(item.getUUID().toString()).orElseThrow();
                    assertTrue(f.identities.retirePortalPublication(old,f.a,item.getId()));
                    var newer=new ItemEntity(f.overworld,0,65,0,new ItemStack(Items.COBBLESTONE,9)); newer.setUUID(item.getUUID()); f.publish(newer,f.from);
                }
            }
            return f.transition(f.nether);
        });
        tickItem(f,item); assertEquals(0,f.manager.advance()); assertEquals(0,f.manager.pendingCount());
        assertFalse(f.order.contains("insert")); assertFalse(f.order.contains("item-suffix"));
        assertEquals(0,f.manager.advance());
    }
    @Test void nestedOwnedDrainCannotPrepareCapturedItem() throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(); PortalDestinationTestHooks.items=f.manager; var item=f.item(f.overworld);
        var calls=new AtomicInteger(); setPortal(item,args->{calls.incrementAndGet(); return null;});
        tickItem(f,item);
        f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
            assertThrows(IllegalStateException.class,f.manager::advance); assertEquals(0,calls.get());
        });
        assertEquals(1,f.manager.advance()); assertEquals(1,calls.get());
    }
    @Test void representativePassengerFreePigConfirmsSameUnretainedLookupHole() throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture(); installNativeLookup(f);
        set(f.overworld,net.minecraft.world.level.Level.class,"registryAccess",dynamicRegistries());
        var pig=EntityTypes.PIG.create(f.overworld,EntitySpawnReason.COMMAND);
        assertNotNull(pig); pig.setPos(.44,65.31,.02); f.publish(pig,f.from);
        pig.setAsInsidePortal((net.minecraft.world.level.block.Portal)net.minecraft.world.level.block.Blocks.NETHER_PORTAL,BlockPos.ZERO);
        var reads=new AtomicInteger(); f.chunkRead=args->{reads.incrementAndGet(); ChunkSynchronousAccessGuard.beforeWait(()->false); return null;};
        var failure=assertThrows(ChunkProtocolException.class,()->f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->pig.tick()));
        assertTrue(failure.getMessage().contains("unfinished synchronous")); assertEquals(1,reads.get());
        assertEquals(0,f.manager.pendingCount());
    }
    static void setPortal(Entity entity,java.util.function.Function<Object[],Object> search) throws Exception {
        var portal=stub(NetherPortalBlock.class,List.of(new Answer(NetherPortalBlock.class.getMethod("getPortalDestination",ServerLevel.class,Entity.class,BlockPos.class),search)));
        entity.setAsInsidePortal(portal,BlockPos.ZERO);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static net.minecraft.core.RegistryAccess dynamicRegistries() {
        var lookup=net.minecraft.data.registries.VanillaRegistries.createLookup();
        List<net.minecraft.core.Registry<?>> registries=new ArrayList<>();
        lookup.listRegistryKeys().forEach(key->{
            var values=lookup.lookupOrThrow((net.minecraft.resources.ResourceKey)key);
            var registry=new net.minecraft.core.MappedRegistry((net.minecraft.resources.ResourceKey)key,com.mojang.serialization.Lifecycle.stable());
            values.listElements().forEach(value->{ var holder=(net.minecraft.core.Holder.Reference)value;
                registry.register(holder.key(),holder.value(),net.minecraft.core.RegistrationInfo.BUILT_IN); });
            registries.add(registry.freeze());
        });
        return new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(registries).freeze();
    }
    static Object pearlManager(NativeItemPortalTransferTest.Fixture f) throws Exception {
        var type=Class.forName("dev.phoenium.live.PearlPortalTransitionManager");
        var authority=Class.forName("dev.phoenium.live.PearlPortalTransitionManager$Authority");
        var proxy=java.lang.reflect.Proxy.newProxyInstance(authority.getClassLoader(),new Class<?>[]{authority},(p,m,args)-> {
            try { return m.invoke(f.authority,args); } catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var constructor=type.getDeclaredConstructors()[0]; constructor.setAccessible(true); return constructor.newInstance(proxy,f.objects);
    }
    static void installNativeLookup(NativeItemPortalTransferTest.Fixture f) throws Exception {
        f.server=stub(net.minecraft.server.MinecraftServer.class,List.of(new Answer(net.minecraft.server.MinecraftServer.class.getMethod("getLevel",net.minecraft.resources.ResourceKey.class),args->args[0]==net.minecraft.world.level.Level.OVERWORLD ? f.overworld : f.nether)));
        for(var level:List.of(f.overworld,f.nether)) {
            var poi=stub(net.minecraft.world.entity.ai.village.poi.PoiManager.class,List.of(
                new Answer(net.minecraft.world.level.chunk.storage.SectionStorage.class.getDeclaredMethod("getOrLoad",long.class),args->Optional.empty()),
                new Answer(net.minecraft.world.entity.ai.village.poi.PoiManager.class.getMethod("getInSquare",java.util.function.Predicate.class,BlockPos.class,int.class,net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.class),args->java.util.stream.Stream.empty())));
            set(poi,net.minecraft.world.level.chunk.storage.SectionStorage.class,"levelHeightAccessor",net.minecraft.world.level.LevelHeightAccessor.create(0,16));
            set(poi,net.minecraft.world.entity.ai.village.poi.PoiManager.class,"loadedChunks",new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
            f.pois.put(level,poi);
            var forcer=stub(net.minecraft.world.level.portal.PortalForcer.class,List.of(
                new Answer(net.minecraft.world.level.portal.PortalForcer.class.getMethod("createPortal",BlockPos.class,net.minecraft.core.Direction.Axis.class),args->Optional.empty())));
            set(forcer,net.minecraft.world.level.portal.PortalForcer.class,"level",level); f.forcers.put(level,forcer);
        }
    }
}
