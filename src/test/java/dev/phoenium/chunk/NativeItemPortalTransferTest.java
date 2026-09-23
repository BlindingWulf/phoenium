package dev.phoenium.chunk;

import dev.phoenium.core.*;
import dev.phoenium.live.*;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.*;
import dev.phoenium.region.*;
import dev.phoenium.scheduler.*;
import dev.phoenium.service.*;
import dev.phoenium.topology.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native ItemEntity.teleport / Entity.teleportCrossDimension with controlled world endpoints. */
class NativeItemPortalTransferTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup())
            .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
    }

    @Test void sugarCaneDestinationTrackingRunsUnderDestinationOwner() throws Exception {
        var f = new Fixture();
        ItemEntity item = new ItemEntity(f.overworld, .42, 65.13, .14, new ItemStack(Items.SUGAR_CANE, 3));
        var transition = new TeleportTransition(f.nether, new Vec3(0,65,0), new Vec3(.1,.2,.3), 25, 10,
            entity -> { f.dispatcher.requireCurrentOwner(f.to); f.order.add("post"); });
        f.publish(item, f.from);
        f.transfer(item, transition);
        assertEquals(List.of("source-retire", "insert", "post", "merge", "base-suffix", "item-suffix"), f.order);
        assertEquals(1,f.objects.liveCount()); assertTrue(item.isRemoved());
        assertSame(f.replacement,f.visible.get(f.nether).get(item.getUUID()));
        assertFalse(f.visible.get(f.overworld).containsKey(item.getUUID()));
    }

    @Test void reverseAndTwoSequentialTransfersKeepOneLiveRepresentation() throws Exception {
        var f = new Fixture(); var first = f.item(f.nether); var uuid=first.getUUID();
        f.transfer(first,f.transition(f.overworld));
        var second = (ItemEntity)f.replacement;
        assertEquals(1,f.objects.current(uuid.toString()).orElseThrow().incarnation());
        assertEquals(0,f.manager.advance()); f.order.clear();
        f.transfer(second,f.transition(f.nether));
        var third = (ItemEntity)f.replacement;
        assertNotSame(first,third); assertNotSame(second,third); assertEquals(uuid,third.getUUID());
        assertEquals(2,f.objects.current(uuid.toString()).orElseThrow().incarnation());
        assertEquals(1,f.objects.liveCount());
        assertEquals(1,f.visible.values().stream().mapToInt(Map::size).sum());
        assertEquals(1,Collections.frequency(f.order,"source-retire"));
        assertEquals(1,Collections.frequency(f.order,"item-suffix"));
    }

    @Test void sameDimensionRunsTheOriginalItemTeleportWithoutRetention() throws Exception {
        var f=new Fixture(); var item=f.item(f.overworld); var transition=f.transition(f.overworld);
        f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            f.manager.begin(item); assertFalse(f.manager.capture(item,transition)); f.manager.finish(item);
            assertSame(item,item.teleport(transition));
        });
        assertEquals(List.of("post","merge"),f.order); assertFalse(item.isRemoved());
        assertEquals(0,f.manager.pendingCount()); assertEquals(0,f.objects.current(item.getUUID().toString()).orElseThrow().incarnation());
    }

    @Test void nativeSerializedStateAndNativePositionResetMatchReference() throws Exception {
        var reference=new Fixture(); reference.enforceOwners=false;
        var source=reference.item(reference.overworld);
        source.getItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("portal cane"));
        source.setPickUpDelay(31); set(source,ItemEntity.class,"age",417); source.tickCount=83;
        source.setDeltaMovement(.4,-.2,.1); source.setPortalCooldown();
        var transition=reference.transition(reference.nether);
        var expected=(ItemEntity)source.teleport(transition);
        var f=new Fixture(); var actualSource=f.item(f.overworld);
        actualSource.getItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("portal cane"));
        actualSource.setPickUpDelay(31); set(actualSource,ItemEntity.class,"age",417); actualSource.tickCount=83;
        actualSource.setDeltaMovement(.4,-.2,.1); actualSource.setPortalCooldown();
        f.transfer(actualSource,f.transition(f.nether)); var actual=(ItemEntity)f.replacement;
        assertTrue(ItemStack.matches(expected.getItem(),actual.getItem()));
        assertEquals(expected.getAge(),actual.getAge()); assertEquals(417,actual.getAge());
        assertEquals(field(expected,ItemEntity.class,"pickupDelay"),field(actual,ItemEntity.class,"pickupDelay"));
        assertEquals(expected.position(),actual.position()); assertEquals(expected.getDeltaMovement(),actual.getDeltaMovement());
        assertEquals(expected.getYRot(),actual.getYRot()); assertEquals(expected.getXRot(),actual.getXRot());
        assertEquals(expected.tickCount,actual.tickCount); assertEquals(0,actual.tickCount);
        assertEquals(field(expected,Entity.class,"portalCooldown"),field(actual,Entity.class,"portalCooldown"));
    }

    @Test void sourceRemovalCancelsAndLateAdvanceDoesNotTouchNewerUuid() throws Exception {
        var f=new Fixture(); var old=f.item(f.overworld); f.capture(old,f.transition(f.nether));
        f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.ENTITY_PORTAL_SOURCE, ignored -> old.remove(Entity.RemovalReason.DISCARDED));
        var newer=new ItemEntity(f.overworld,0,65,0,new ItemStack(Items.SUGAR_CANE,8)); newer.setUUID(old.getUUID()); f.publish(newer,f.from);
        assertEquals(0,f.manager.advance()); assertSame(newer,f.visible.get(f.overworld).get(old.getUUID()));
        assertEquals(8,newer.getItem().getCount()); assertEquals(1,f.objects.liveCount());
    }
    @Test void newerOwnershipIncarnationWithoutSourceRemovalRetiresOldWork() throws Exception {
        var f=new Fixture(); var item=f.item(f.overworld); f.capture(item,f.transition(f.nether));
        var old=f.objects.current(item.getUUID().toString()).orElseThrow();
        assertTrue(f.identities.retirePortalPublication(old,f.a,item.getId()));
        var newer=new ItemEntity(f.overworld,0,65,0,new ItemStack(Items.SUGAR_CANE,8)); newer.setUUID(item.getUUID()); f.publish(newer,f.from);
        assertEquals(0,f.manager.advance()); assertFalse(item.isRemoved()); assertFalse(newer.isRemoved());
        assertEquals(List.of(),f.order); assertEquals(1,f.objects.liveCount());
    }
    @Test void topologyOwnerChangeInvalidatesCapturedSourceEpoch() throws Exception {
        var f=new Fixture(); var item=f.item(f.overworld); f.capture(item,f.transition(f.nether));
        var generation=f.directory.snapshot().generation().next(); var replacementOwner=new RegionId(f.a,"new-owner");
        f.directory.publish(generation,Map.of(f.from,new RegionRoute(replacementOwner,generation,new OwnerEpoch(0)),f.to,new RegionRoute(f.br,generation,new OwnerEpoch(0))));
        f.objects.reconcileTopology();
        assertEquals(0,f.manager.advance()); assertEquals(List.of(),f.order); assertFalse(item.isRemoved());
    }
    @Test void physicalRouteChangeBeforeAdmissionRetiresOldContinuation() throws Exception {
        var f=new Fixture(); var item=f.item(f.overworld); f.capture(item,f.transition(f.nether));
        var changed=new RoutingKey(f.a,1,0); var generation=f.directory.snapshot().generation().next();
        f.directory.publish(generation,Map.of(f.from,new RegionRoute(f.ar,generation,new OwnerEpoch(0)),changed,new RegionRoute(f.ar,generation,new OwnerEpoch(0)),f.to,new RegionRoute(f.br,generation,new OwnerEpoch(0))));
        var binding=f.objects.current(item.getUUID().toString()).orElseThrow(); f.objects.updateRoutingKey(binding.objectId(),changed,binding.objectEpoch());
        assertEquals(0,f.manager.advance()); assertEquals(List.of(),f.order);
    }
    @Test void holderReplacementDuringPreparationCannotRunNativeEffects() throws Exception {
        var f=new Fixture(); var item=f.item(f.overworld); f.capture(item,f.transition(f.nether));
        f.ready=false;
        assertThrows(IllegalStateException.class,f.manager::advance); assertFalse(item.isRemoved()); assertEquals(List.of(),f.order);
        f.ready=true; assertEquals(0,f.manager.advance()); assertEquals(1,f.objects.liveCount());
    }
    @Test void shutdownAndWorldCloseRetirePendingGeneration() throws Exception {
        var f=new Fixture(); var item=f.item(f.overworld); f.capture(item,f.transition(f.nether)); f.manager.close();
        assertEquals(0,f.manager.advance()); assertFalse(item.isRemoved()); assertEquals(0,f.manager.pendingCount());
        var second=new Fixture(); var another=second.item(second.overworld); second.capture(another,second.transition(second.nether));
        second.manager.worldClosing(second.nether); assertEquals(0,second.manager.advance()); assertEquals(0,second.manager.pendingCount());
    }
    @Test void shutdownDuringOwnerFreePreparationDoesNotReplayOrRemoveSource() throws Exception {
        var f=new Fixture(); var item=f.item(f.overworld); f.capture(item,f.transition(f.nether));
        f.preparation=() -> f.running=false;
        assertThrows(IllegalStateException.class,f.manager::advance); assertFalse(item.isRemoved()); assertEquals(List.of(),f.order);
        assertEquals(0,f.manager.pendingCount());
    }
    enum FailurePoint { BEFORE_CLAIM, CLAIMED, CALLBACK, INSERTED, POST, MERGE }
    @ParameterizedTest @EnumSource(FailurePoint.class)
    void partialPublicationFailureCleansOnlyItsOwnIncarnationAndNeverRetries(FailurePoint point) throws Exception {
        var f=new Fixture(); var item=f.item(f.overworld); f.failAt=point; f.capture(item,f.transition(f.nether));
        assertSame(f.failure,assertThrows(IllegalStateException.class,f.manager::advance));
        assertEquals(0,f.manager.pendingCount()); assertEquals(0,f.manager.advance());
        assertEquals(0,f.visible.values().stream().mapToInt(Map::size).sum()); assertEquals(0,f.objects.liveCount());
        assertTrue(item.isRemoved()); assertFalse(f.order.contains("item-suffix"));
        assertEquals(1,Collections.frequency(f.order,"insert"));
    }

    @Test void cleanupFailureIsSecondaryAndOriginalExceptionSurvives() throws Exception {
        var f=new Fixture(); var item=f.item(f.overworld); f.failAt=FailurePoint.CLAIMED; f.cleanupFailure=true; f.capture(item,f.transition(f.nether));
        assertSame(f.failure,assertThrows(IllegalStateException.class,f.manager::advance));
        assertEquals(1,f.failure.getSuppressed().length); assertEquals("cleanup endpoint",f.failure.getSuppressed()[0].getMessage());
        assertEquals(0,f.manager.advance());
    }
    @Test void failedStaleDestinationCannotDeleteANewerSameUuidObject() throws Exception {
        var f=new Fixture(); var source=f.item(f.overworld); Entity[] newer=new Entity[1];
        f.afterInsertion=() -> {
            var old=f.objects.current(source.getUUID().toString()).orElseThrow();
            assertTrue(f.identities.retirePortalPublication(old,f.b,f.replacement.getId()));
            var next=new ItemEntity(f.nether,0,65,0,new ItemStack(Items.SUGAR_CANE,9)); next.setUUID(source.getUUID());
            newer[0]=next; f.publish(next,f.to);
        };
        f.capture(source,f.transition(f.nether));
        assertThrows(IllegalStateException.class,f.manager::advance);
        assertSame(newer[0],f.visible.get(f.nether).get(source.getUUID()));
        assertFalse(newer[0].isRemoved()); assertEquals(1,f.objects.liveCount()); assertEquals(0,f.manager.advance());
    }
    @Test void staleRetirementReceiptCannotRemoveEvenAReusedRuntimeId() throws Exception {
        var f=new Fixture(); var source=f.item(f.overworld);
        var old=f.objects.current(source.getUUID().toString()).orElseThrow();
        assertTrue(f.identities.retirePortalPublication(old,f.a,source.getId()));
        var newer=new ItemEntity(f.overworld,0,65,0,new ItemStack(Items.SUGAR_CANE,9));
        newer.setUUID(source.getUUID()); newer.setId(source.getId()); f.publish(newer,f.from);
        assertFalse(f.identities.retirePortalPublication(old,f.a,source.getId()));
        assertEquals(old.incarnation()+1,f.objects.current(source.getUUID().toString()).orElseThrow().incarnation());
        assertEquals(1,f.objects.liveCount());
    }

    static final class Fixture {
        final WorldId a = new WorldId("minecraft:overworld"), b;
        final RoutingKey from = new RoutingKey(a,0,0), to;
        final RegionId ar = new RegionId(a,"stage2-live-world"), br;
        final RegionDirectory directory = new RegionDirectory();
        final Map<RegionId,RegionRuntime> runtimes = new LinkedHashMap<>();
        final FailureBarrier barrier = new FailureBarrier();
        final RegionScheduler scheduler;
        final OwnerGateway gateway;
        final RegionalServiceDispatcher dispatcher;
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        final EntityIndexLifecycleAdapter identities = new EntityIndexLifecycleAdapter(objects,directory,new dev.phoenium.interaction.InteractionTargetIndex());
        final Map<ServerLevel,Map<UUID,Entity>> visible = new IdentityHashMap<>();
        final ItemPortalTransitionManager manager;
        final NativePortalTransfer.Authority authority;
        Entity replacement;
        boolean enforceOwners=true, running=true, ready=true, entityVisible=true, cleanupFailure;
        int visibilityDemands;
        FailurePoint failAt;
        final IllegalStateException failure=new IllegalStateException("controlled native endpoint");
        Runnable preparation=() -> { };
        Runnable platformPreparation=() -> { };
        int platformServices;
        Runnable afterInsertion=() -> { };
        final List<String> order = new ArrayList<>();
        int nextId;
        final ServerLevel overworld, nether;
        net.minecraft.server.MinecraftServer server;
        final Map<ServerLevel,net.minecraft.world.level.portal.PortalForcer> forcers = new IdentityHashMap<>();
        final Map<ServerLevel,net.minecraft.world.entity.ai.village.poi.PoiManager> pois = new IdentityHashMap<>();
        java.util.function.Function<Object[],Object> chunkRead = args -> null;
        java.util.function.Function<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState> blockRead =
            pos -> net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        final Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState> blockStates=new HashMap<>();
        final Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.entity.BlockEntity> blockEntities=new HashMap<>();
        int blockWrites,blockDestroys;
        java.util.function.BiConsumer<ServerLevel,net.minecraft.server.level.ServerPlayer> playerRemove = (level,player)->{};
        java.util.function.BiConsumer<ServerLevel,net.minecraft.server.level.ServerPlayer> playerInsert = (level,player)->{};
        net.minecraft.world.level.storage.LevelData levelData;
        net.minecraft.world.damagesource.DamageSources damageSources;
        Runnable sound=()->{};
        final List<ItemEntity> mergeTargets=new ArrayList<>();
        Fixture() throws Exception { this(Level.NETHER); }
        Fixture(net.minecraft.resources.ResourceKey<Level> destinationDimension) throws Exception {
            b = new WorldId(destinationDimension.identifier().toString());
            to = new RoutingKey(b,destinationDimension == Level.END ? 6 : 0,0);
            br = new RegionId(b,"stage2-live-world");
            for (var id : List.of(ar,br)) {
                var runtime = new RegionRuntime(id,new OwnerEpoch(0)); runtime.transitionTo(RegionLifecycle.READY); runtimes.put(id,runtime);
            }
            var generation = new TopologyGeneration(1);
            directory.publish(generation,Map.of(from,new RegionRoute(ar,generation,new OwnerEpoch(0)),to,new RegionRoute(br,generation,new OwnerEpoch(0))));
            scheduler = new RegionScheduler(runtimes,directory,barrier);
            gateway = new OwnerGateway(directory,runtimes,scheduler);
            dispatcher = new RegionalServiceDispatcher("native-item-portal",1,directory,runtimes,gateway,scheduler);
            overworld = world(Level.OVERWORLD,from); nether = world(destinationDimension,to);
            authority = new NativePortalTransfer.Authority() {
                public boolean running() { return running; }
                public boolean worldActive(ServerLevel level) { return level == overworld || level == nether; }
                public WorldId worldId(ServerLevel level) { return level == overworld ? a : b; }
                public void execute(RoutingKey key, RegionalServiceKind kind, Runnable body) {
                    assertTrue(OwnershipContext.current().isEmpty());
                    assertTrue(runtimes.values().stream().noneMatch(r -> r.leases().hasActiveLease()));
                    dispatcher.executeAndDrain(key,kind, ignored -> body.run());
                }
                public java.util.function.BooleanSupplier prepare(Entity source, TeleportTransition transition, RoutingKey key) {
                    assertTrue(OwnershipContext.current().isEmpty());
                    assertTrue(runtimes.values().stream().noneMatch(r -> r.leases().hasActiveLease()));
                    preparation.run(); return () -> ready;
                }
                public java.util.function.BooleanSupplier demandDestinationEntityVisibility(Entity source,TeleportTransition transition) {
                    visibilityDemands++;return () -> entityVisible;
                }
                public void withDestinationPlatform(ServerLevel destination,net.minecraft.core.BlockPos origin,
                        java.util.function.BooleanSupplier sourceCurrent,Runnable nativePlatform) {
                    assertTrue(OwnershipContext.current().isEmpty());
                    assertTrue(sourceCurrent.getAsBoolean());
                    platformPreparation.run();
                    assertTrue(sourceCurrent.getAsBoolean());
                    execute(key(destination),RegionalServiceKind.ENTITY_PORTAL_DESTINATION,()->{
                        assertTrue(sourceCurrent.getAsBoolean());
                        platformServices++; nativePlatform.run();
                    });
                }
                public void retireExpectedSource(Entity source, Entity.RemovalReason reason, ObjectOwnership expected) {
                    assertTrue(identities.retirePortalPublication(expected, worldId((ServerLevel)source.level()),source.getId()));
                }
            };
            manager = new ItemPortalTransitionManager(authority,objects);
            NativePortalTestHooks.base = entity -> { owner(key(entity.level())); order.add("base-suffix"); };
            NativePortalTestHooks.suffix = entity -> { owner(key(entity.level())); order.add("item-suffix"); };
        }
        RoutingKey key(Level level) { return level == overworld ? from : to; }
        void owner(RoutingKey key) { if (enforceOwners) dispatcher.requireCurrentOwner(key); }
        void fail(FailurePoint point) { if (point == failAt) throw failure; }
        ItemEntity item(ServerLevel level) { var item=new ItemEntity(level,.42,65.13,.14,new ItemStack(Items.SUGAR_CANE,3)); publish(item,key(level)); return item; }
        TeleportTransition transition(ServerLevel destination) {
            return new TeleportTransition(destination,new Vec3(0,65,0),new Vec3(.1,.2,.3),25,10,
                entity -> { owner(key(destination)); order.add("post"); fail(FailurePoint.POST); });
        }
        void transfer(ItemEntity item, TeleportTransition transition) {
            capture(item,transition);
            assertEquals(1,manager.advance());
            assertEquals(0,manager.pendingCount());
        }
        void capture(ItemEntity item, TeleportTransition transition) {
            int previous=order.size();
            dispatcher.executeAndDrain(key(item.level()),RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
                manager.begin(item); assertTrue(manager.capture(item,transition)); assertTrue(manager.complete(item)); manager.finish(item);
                assertEquals(previous,order.size());
            });
        }
        void publish(Entity entity, RoutingKey key) {
            visible.get((ServerLevel)entity.level()).put(entity.getUUID(),entity);
            var binding = identities.addressable(entity.getUUID().toString(),entity instanceof net.minecraft.server.level.ServerPlayer ? OwnedObjectKind.PLAYER : OwnedObjectKind.ENTITY,key.world(),entity.getId(),key,false);
            NativePortalTransfer.addressable(entity,binding);
            callback(entity,key);
        }
        void callback(Entity entity,RoutingKey key) {
            entity.setLevelCallback(new net.minecraft.world.level.entity.EntityInLevelCallback() {
                public void onMove() { }
                public void onRemove(Entity.RemovalReason reason) {
                    owner(key); order.add(reason == Entity.RemovalReason.CHANGED_DIMENSION ? "source-retire" : "destination-cleanup");
                    visible.get((ServerLevel)entity.level()).remove(entity.getUUID(),entity);
                    if (!NativePortalTransfer.expectedRemoval(entity,reason)) {
                        manager.onRemoval(entity,reason);
                        identities.removed(entity.getUUID().toString(),key.world(),entity.getId());
                    }
                }
            });
        }
        ServerLevel world(net.minecraft.resources.ResourceKey<Level> dimension, RoutingKey key) throws Exception {
            Map<UUID,Entity> entities = new HashMap<>();
            ServerLevel level = stub(ServerLevel.class,List.of(
                new Answer(ServerLevel.class.getMethod("isAllowedToEnterPortal",Level.class), args -> true),
                new Answer(ServerLevel.class.getMethod("getServer"), args -> server),
                new Answer(ServerLevel.class.getMethod("getWaypointManager"),args->new net.minecraft.server.waypoints.ServerWaypointManager()),
                new Answer(ServerLevel.class.getMethod("getEntityInAnyDimension",UUID.class),args->visible.values().stream().map(map->map.get(args[0])).filter(Objects::nonNull).findFirst().orElse(null)),
                new Answer(ServerLevel.class.getMethod("getLevelData"),args->levelData),
                new Answer(ServerLevel.class.getMethod("getRespawnData"),args->net.minecraft.world.level.storage.LevelData.RespawnData.DEFAULT),
                new Answer(ServerLevel.class.getMethod("getSeaLevel"),args->63),
                new Answer(ServerLevel.class.getMethod("isSpawningMonsters"),args->false),
                new Answer(Level.class.getMethod("damageSources"),args->damageSources),
                new Answer(Level.class.getMethod("playSound",Entity.class,double.class,double.class,double.class,net.minecraft.sounds.SoundEvent.class,net.minecraft.sounds.SoundSource.class),args->{sound.run();return null;}),
                new Answer(ServerLevel.class.getMethod("gameEvent",net.minecraft.core.Holder.class,Vec3.class,net.minecraft.world.level.gameevent.GameEvent.Context.class),args->null),
                new Answer(ServerLevel.class.getMethod("removePlayerImmediately",net.minecraft.server.level.ServerPlayer.class,Entity.RemovalReason.class),args->{
                    playerRemove.accept(dimension == Level.OVERWORLD ? overworld : nether,(net.minecraft.server.level.ServerPlayer)args[0]); return null;
                }),
                new Answer(ServerLevel.class.getMethod("getPortalForcer"), args -> forcers.get(dimension == Level.OVERWORLD ? overworld : nether)),
                new Answer(ServerLevel.class.getMethod("getPoiManager"), args -> pois.get(dimension == Level.OVERWORLD ? overworld : nether)),
                new Answer(Level.class.getMethod("getWorldBorder"), args -> new net.minecraft.world.level.border.WorldBorder()),
                new Answer(Level.class.getMethod("dimensionType"), args -> net.minecraft.data.registries.VanillaRegistries.createLookup()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                    .getOrThrow(dimension == Level.OVERWORLD ? net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD
                        : dimension == Level.END ? net.minecraft.world.level.dimension.BuiltinDimensionTypes.END
                        : net.minecraft.world.level.dimension.BuiltinDimensionTypes.NETHER).value()),
                new Answer(Level.class.getMethod("getBlockState",net.minecraft.core.BlockPos.class), args -> blockRead.apply((net.minecraft.core.BlockPos)args[0])),
                new Answer(Level.class.getMethod("getBlockEntity",net.minecraft.core.BlockPos.class), args -> blockEntities.get(args[0])),
                new Answer(Level.class.getMethod("setBlock",net.minecraft.core.BlockPos.class,net.minecraft.world.level.block.state.BlockState.class,int.class), args -> {
                    owner(key);blockWrites++;blockStates.put(((net.minecraft.core.BlockPos)args[0]).immutable(),
                        (net.minecraft.world.level.block.state.BlockState)args[1]);return true;
                }),
                new Answer(Level.class.getMethod("destroyBlock",net.minecraft.core.BlockPos.class,boolean.class,Entity.class,int.class), args -> {
                    owner(key);blockDestroys++;blockStates.remove((net.minecraft.core.BlockPos)args[0]);return true;
                }),
                new Answer(Level.class.getMethod("getChunk",int.class,int.class,net.minecraft.world.level.chunk.status.ChunkStatus.class,boolean.class), args -> chunkRead.apply(args)),
                new Answer(ServerLevel.class.getMethod("getEntity",UUID.class), args -> entities.get(args[0])),
                new Answer(ServerLevel.class.getMethod("getEntity",int.class), args -> entities.values().stream().filter(e -> e.getId() == (int)args[0]).findFirst().orElse(null)),
                new Answer(Level.class.getMethod("getEntitiesOfClass",Class.class,net.minecraft.world.phys.AABB.class,java.util.function.Predicate.class), args -> {
                    owner(key); order.add("merge"); fail(FailurePoint.MERGE);
                    @SuppressWarnings("unchecked") var predicate=(java.util.function.Predicate<ItemEntity>)args[2];
                    return mergeTargets.stream().filter(predicate).toList();
                }),
                new Answer(Level.class.getMethod("getNextEntityId"), args -> ++nextId),
                new Answer(Level.class.getMethod("enabledFeatures"), args -> FeatureFlags.REGISTRY.allFlags()),
                new Answer(Level.class.getMethod("getGameTime"), args -> 100L),
                new Answer(ServerLevel.class.getMethod("players"), args -> List.of()),
                new Answer(ServerLevel.class.getMethod("resetEmptyTime"), args -> null),
                new Answer(ServerLevel.class.getMethod("addDuringTeleport",Entity.class), args -> {
                    if(args[0] instanceof net.minecraft.server.level.ServerPlayer player) {playerInsert.accept(dimension == Level.OVERWORLD ? overworld : nether,player);return null;}
                    owner(key); order.add("insert"); replacement=(Entity)args[0]; fail(FailurePoint.BEFORE_CLAIM);
                    Entity exact=replacement;
                    NativePortalTransfer.storageClaimed(exact, () -> {
                        if (cleanupFailure) throw new IllegalStateException("cleanup endpoint");
                        entities.remove(exact.getUUID(),exact);
                    });
                    fail(FailurePoint.CLAIMED);
                    callback(exact,key); NativePortalTransfer.callbackInstalled(exact); fail(FailurePoint.CALLBACK);
                    publish(exact,key);
                    if (enforceOwners) objects.requireCurrentOwner(exact.getUUID().toString(),objects.current(exact.getUUID().toString()).orElseThrow().objectEpoch(),OwnershipContext.current().orElseThrow().region());
                    fail(FailurePoint.INSERTED);
                    afterInsertion.run();
                    return null;
                })));
            set(level,Level.class,"dimension",dimension);
            set(level,Level.class,"registryAccess",RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            visible.put(level,entities); return level;
        }
    }
}
