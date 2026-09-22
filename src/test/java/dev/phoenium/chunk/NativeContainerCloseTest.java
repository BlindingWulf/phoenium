package dev.phoenium.chunk;

import dev.phoenium.core.*;
import dev.phoenium.task.*;
import dev.phoenium.live.*;
import dev.phoenium.ownership.*;
import dev.phoenium.region.*;
import dev.phoenium.scheduler.*;
import dev.phoenium.service.*;
import dev.phoenium.topology.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native listener -> ServerPlayer -> ChestMenu -> opener -> ServerLevel block event.
 * Only server/world storage, sound/game-event sinks and network output are controlled. FULL is false. */
class NativeContainerCloseTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() { ContainerCloseTestHooks.live = null; ContainerCloseTestHooks.removal = menu -> { }; ContainerCloseTestHooks.event = level -> { }; }

    @Test void explicitCloseReachesNativeChestBlockEvent() throws Exception {
        var f = new Fixture(); f.open(false);
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        f.drain();
        assertSame(f.player.inventoryMenu, f.player.containerMenu);
        assertEquals(0, f.count(f.first));
        assertEquals(1, f.delivered.size());
        assertEquals(0, f.delivered.getFirst().paramB());
    }

    @Test void duplicateRetainedDeliveryCannotRepeatNativePrefixCursorOrEvent() throws Exception {
        var f = new Fixture(); f.open(false); var menu = f.player.containerMenu;
        menu.setCarried(new ItemStack(Items.SUGAR_CANE, 5));
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        var packets = (Map<TaskCorrelationId, ?>) field(f.live, PhoeniumLiveRuntime.class, "packets");
        var correlation = packets.keySet().iterator().next(); var binding = f.objects.current(f.uuid.toString()).orElseThrow();
        var operation = new TaskPayload.OwnedObjectOperation(binding.objectId(),binding.objectEpoch(),"player-container-close",correlation);
        f.drain();
        try(var turn=FullFinishingServices.forServer(f.server).serial(FullFinishingServices.Phase.SERVER_TICK)) {
            f.dispatcher.executeAndDrain(binding.routingKey(),RegionalServiceKind.CHUNK_GAMEPLAY,ignored->f.live.execute(f.runtimes.get(binding.owner()),operation));
        }
        assertEquals(1,f.removals); assertEquals(0,f.count(f.first)); assertTrue(menu.getCarried().isEmpty());
        assertEquals(5,f.inventory.countItem(Items.SUGAR_CANE)); assertEquals(1,f.delivered.size());
        assertEquals(List.of("sound","game-event"),f.effects);
        assertEquals(1,f.live.packetDiagnostics().blockPacketDuplicate());
        assertTrue(f.turns.stream().allMatch(t->t.startsWith("SERIAL(phase=SERVER_TICK")));
    }

    @Test void nativeServerCloseWithinValidTurnDoesNotCreatePacketWork() throws Exception {
        var f=new Fixture(); f.open(false);
        try(var turn=FullFinishingServices.forServer(f.server).serial(FullFinishingServices.Phase.SERVER_TICK)) { f.player.closeContainer(); }
        f.drain(); assertEquals(1,f.removals); assertEquals(0,f.count(f.first)); assertEquals(1,f.delivered.size());
        assertTrue(f.live.containerCloseDiagnostics().contains("in=0,admit=0,effect=0"));
        assertEquals(1,f.sent.stream().filter(p->p instanceof net.minecraft.network.protocol.game.ClientboundContainerClosePacket).count());
    }

    @Test void delayedRequestCannotCloseReplacementMenuWithReusedNumericId() throws Exception {
        var f=new Fixture(); f.open(false); var old=f.player.containerMenu;
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        try(var turn=FullFinishingServices.forServer(f.server).serial(FullFinishingServices.Phase.SERVER_TICK)) { f.player.closeContainer(); }
        f.open(false); var replacement=f.player.containerMenu; assertEquals(old.containerId,replacement.containerId);
        f.drain(); assertSame(replacement,f.player.containerMenu); assertEquals(1,f.count(f.first)); assertEquals(1,f.removals);
        assertTrue(f.live.containerCloseDiagnostics().contains("retired=1"));
    }

    @Test void reusedConnectionSessionCannotReceiveOldClose() throws Exception {
        var f=new Fixture(); f.open(false);
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        try(var turn=FullFinishingServices.forServer(f.server).serial(FullFinishingServices.Phase.PLAYER_LIFECYCLE)) {
            f.player.doCloseContainer();
            var scoreboard=FullFinishingServices.forServer(f.server).scoreboard;
            scoreboard.remove(f.player,true); scoreboard.registered(f.player); scoreboard.playerUuidPublished(f.uuid,f.player);
        }
        f.open(false); var current=f.player.containerMenu;
        f.drain(); assertSame(current,f.player.containerMenu); assertEquals(1,f.count(f.first)); assertEquals(1,f.removals);
        assertTrue(f.live.containerCloseDiagnostics().contains("retired=1")); f.tracking();
    }

    @Test void replacementPlayerInstanceDoesNotReceiveOldClose() throws Exception {
        var f=new Fixture(); f.open(false); var oldMenu=f.player.containerMenu;
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        var replacement=allocate(ServerPlayer.class); replacement.containerMenu=oldMenu; replacement.connection=f.listener;
        f.listener.player=replacement;
        f.drain(); assertSame(oldMenu,replacement.containerMenu); assertEquals(0,f.removals); assertEquals(1,f.count(f.first));
        assertTrue(f.live.containerCloseDiagnostics().contains("retired=1"));
        // The retired network request does not own or cancel the old player's required cleanup.
        f.listener.player=f.player;
        try(var turn=FullFinishingServices.forServer(f.server).serial(FullFinishingServices.Phase.PLAYER_LIFECYCLE)) { f.player.doCloseContainer(); }
        f.drain(); assertEquals(0,f.count(f.first)); assertEquals(1,f.removals);
    }

    @Test void movementSettlesBeforeDoubleChestSelectionAndTrackingCut() throws Exception {
        var f=new Fixture(); f.open(true);
        var key=new RoutingKey(f.world,0,0);
        // Controlled accepted movement endpoint, delivered by the real FIFO regional dispatcher.
        f.dispatcher.submit(key,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
            set(f.player,Entity.class,"chunkPosition",new ChunkPos(2,0));
            var old=f.objects.current(f.uuid.toString()).orElseThrow();
            f.objects.updateRoutingKey(old.objectId(),new RoutingKey(f.world,2,0),old.objectEpoch());
        });
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        assertEquals(new ChunkPos(2,0),f.player.chunkPosition());
        f.drain(); assertEquals(0,f.count(f.first)); assertEquals(0,f.count(f.second)); assertEquals(1,f.removals);
        assertEquals(List.of(f.first.getBlockPos(),f.second.getBlockPos()),f.delivered.stream().map(BlockEventData::pos).toList());
        f.tracking();
    }

    @Test void doubleChestConvergesActualDistinctOwnersBeforeNativeRemoval() throws Exception {
        var f=new Fixture(true);f.open(true);
        assertNotEquals(f.directory.resolve(new RoutingKey(f.world,0,0)).orElseThrow().region(),
            f.directory.resolve(new RoutingKey(f.world,1,0)).orElseThrow().region());
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        assertEquals(f.directory.resolve(new RoutingKey(f.world,0,0)).orElseThrow().region(),
            f.directory.resolve(new RoutingKey(f.world,1,0)).orElseThrow().region());
        f.drain();assertEquals(1,f.removals);assertEquals(0,f.count(f.first));assertEquals(0,f.count(f.second));assertEquals(2,f.delivered.size());f.tracking();
    }

    @Test void sessionGenerationFenceWorksEvenWithoutAnInterveningNativeClose() throws Exception {
        var f=new Fixture();f.open(false);var oldMenu=f.player.containerMenu;
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        try(var turn=FullFinishingServices.forServer(f.server).serial(FullFinishingServices.Phase.PLAYER_LIFECYCLE)) {
            var scoreboard=FullFinishingServices.forServer(f.server).scoreboard;
            scoreboard.remove(f.player,true);scoreboard.registered(f.player);scoreboard.playerUuidPublished(f.uuid,f.player);
        }
        f.drain();assertSame(oldMenu,f.player.containerMenu);assertEquals(0,f.removals);assertEquals(1,f.count(f.first));
        assertTrue(f.live.containerCloseDiagnostics().contains("retired=1"));
        try(var turn=FullFinishingServices.forServer(f.server).serial(FullFinishingServices.Phase.PLAYER_LIFECYCLE)) { f.player.doCloseContainer(); }
        f.drain();assertEquals(1,f.removals);assertEquals(0,f.count(f.first));f.tracking();
    }

    @Test void nativeIdSemanticsArePreservedAndThreadCheckStillRunsFirst() throws Exception {
        var f=new Fixture(); f.open(false);
        // 26.2 close differs from click: it has no container-id predicate.
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(99)); f.drain(); assertEquals(1,f.removals);
        f.open(false); var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread=new Thread(()->{try { f.listener.handleContainerClose(new ServerboundContainerClosePacket(7)); } catch(Throwable t) {failure.set(t);} });
        thread.start();thread.join();
        assertInstanceOf(net.minecraft.server.RunningOnDifferentThreadException.class,failure.get());
        assertEquals(1,f.removals); assertTrue(f.live.containerCloseDiagnostics().contains("in=1,admit=1"));
    }

    @Test void unrelatedUnadmittedBlockEventStillFails() throws Exception {
        var f=new Fixture();
        var failure=assertThrows(ChunkProtocolException.class,()->f.level.blockEvent(BlockPos.ZERO,Blocks.CHEST,1,0));
        assertTrue(failure.getMessage().contains("no admitted serial or FULL turn: TICK_LIST")); assertTrue(f.delivered.isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=Entity.RemovalReason.class,names={"KILLED","UNLOADED_WITH_PLAYER"})
    void nativeRemovalKeepsCleanupWhileRetiringQueuedNetworkClose(Entity.RemovalReason reason) throws Exception {
        var f=new Fixture();f.open(true);var menu=f.player.containerMenu;
        menu.setCarried(new ItemStack(Items.SUGAR_CANE,4));
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        f.disconnected=reason==Entity.RemovalReason.UNLOADED_WITH_PLAYER;
        try(var turn=FullFinishingServices.forServer(f.server).serial(FullFinishingServices.Phase.PLAYER_LIFECYCLE)) { f.player.remove(reason); }
        f.drain();
        assertTrue(f.player.isRemoved());assertSame(f.player.inventoryMenu,f.player.containerMenu);
        assertEquals(1,f.removals);assertEquals(0,f.count(f.first));assertEquals(0,f.count(f.second));
        assertTrue(menu.getCarried().isEmpty());assertEquals(4,f.drops.stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(0,f.inventory.countItem(Items.SUGAR_CANE));assertEquals(2,f.delivered.size());
        assertTrue(f.live.containerCloseDiagnostics().contains("retired=1"));
    }

    @Test void footprintIncludesActualRemoteChestAndRejectsForeignWorldBeforePrefix() throws Exception {
        var f=new Fixture();f.open(true);
        set(f.player,Entity.class,"chunkPosition",new ChunkPos(3,0));
        var footprint=ContainerCloseFootprint.capture(f.player,f.player.containerMenu);
        assertEquals(List.of(f.first,f.second),footprint.targets().stream().map(ContainerCloseFootprint.Target::container).toList());
        assertTrue(footprint.closure().containsAll(List.of(new RoutingKey(f.world,0,0),new RoutingKey(f.world,1,0),new RoutingKey(f.world,3,0))));
        var other=allocate(ServerLevel.class);f.second.setLevel(other);
        assertThrows(IllegalStateException.class,()->f.listener.handleContainerClose(new ServerboundContainerClosePacket(7)));
        assertEquals(0,f.removals);assertEquals(1,f.count(f.first));assertEquals(1,f.count(f.second));
    }

    @Test void nativeFailureIsOriginalAndConsumedPrefixIsNeverReplayed() throws Exception {
        var f=new Fixture();f.open(false);f.player.containerMenu.setCarried(new ItemStack(Items.SUGAR_CANE,3));
        var first=new IllegalStateException("original native block-event endpoint failure");
        ContainerCloseTestHooks.event=level->{throw first;};
        f.listener.handleContainerClose(new ServerboundContainerClosePacket(7));
        var thrown=assertThrows(RuntimeException.class,f::drain);
        assertSame(first,f.scheduler.failureBarrier().record().orElseThrow().cause());
        assertTrue(thrown==first || thrown.getCause()==first);
        assertEquals(1,f.removals);assertEquals(0,f.count(f.first));assertEquals(3,f.inventory.countItem(Items.SUGAR_CANE));
        assertTrue(((Map<?,?>)field(f.live,PhoeniumLiveRuntime.class,"packets")).isEmpty()); assertTrue(f.delivered.isEmpty());
    }

    static class Fixture {
        final PhoeniumLiveRuntime live = new PhoeniumLiveRuntime(LoggerFactory.getLogger(getClass()));
        final RegionDirectory directory = (RegionDirectory) field(live, PhoeniumLiveRuntime.class, "directory");
        final ObjectOwnershipRegistry objects = (ObjectOwnershipRegistry) field(live, PhoeniumLiveRuntime.class, "objects");
        final Map<RegionId, RegionRuntime> runtimes = (Map<RegionId, RegionRuntime>) field(live, PhoeniumLiveRuntime.class, "runtimes");
        final WorldId world = new WorldId("minecraft:overworld");
        final RegionId owner = new RegionId(world, "close-test");
        final RegionScheduler scheduler;
        final RegionalServiceDispatcher dispatcher;
        final RegionalWorldServices services;
        final RegionalBlockEventService<BlockEventData> events;
        final MinecraftServer server;
        final ServerLevel level;
        final ServerPlayer player;
        final ServerGamePacketListenerImpl listener;
        final Inventory inventory;
        final ChestBlockEntity first, second;
        final List<BlockEventData> delivered = new ArrayList<>();
        final List<Packet<?>> sent = new ArrayList<>();
        final List<String> effects = new ArrayList<>();
        final List<ItemStack> drops = new ArrayList<>();
        final List<String> turns = new ArrayList<>();
        int removals;
        final UUID uuid = UUID.randomUUID();
        final Map<BlockPos, ChestBlockEntity> chests = new HashMap<>();
        boolean disconnected;
        Fixture() throws Exception { this(false); }
        Fixture(boolean split) throws Exception {
            assertFalse((boolean)field(live,PhoeniumLiveRuntime.class,"authoritativeFull"));
            var runtime = new RegionRuntime(owner, new OwnerEpoch(0)); runtime.transitionTo(RegionLifecycle.READY); runtimes.put(owner, runtime);
            var other = new RegionId(world,"other-chest-owner");
            if(split) {var r=new RegionRuntime(other,new OwnerEpoch(0));r.transitionTo(RegionLifecycle.READY);runtimes.put(other,r);}
            var routes = new LinkedHashMap<RoutingKey, RegionRoute>();
            for (int x=-4;x<=5;x++) for(int z=-3;z<=3;z++) routes.put(new RoutingKey(world,x,z),new RegionRoute(split && x>=1 ? other : owner,new TopologyGeneration(1),new OwnerEpoch(0)));
            directory.publish(new TopologyGeneration(1),routes);
            scheduler = new RegionScheduler(runtimes,directory,new dev.phoenium.failure.FailureBarrier(),live,live,GameplayWorkerConfig.fromProperties());
            var gateway = new OwnerGateway(directory,runtimes,scheduler);
            dispatcher = new RegionalServiceDispatcher("close-test",1,directory,runtimes,gateway,scheduler);
            var enclosure=new FullRegionalEnclosure(directory,runtimes,gateway,objects);
            dispatcher.installClosureEncloser(enclosure::enclose);dispatcher.installBoundaryAdvancer(enclosure::advance);
            services = new RegionalWorldServices(directory,dispatcher,objects);
            events = new RegionalBlockEventService<>(world,directory,dispatcher,BlockEventData::pos);
            var processor = new PacketProcessor(Thread.currentThread());
            server = stub(MinecraftServer.class,List.of(new Answer(MinecraftServer.class.getMethod("packetProcessor"),a->processor),
                new Answer(MinecraftServer.class.getMethod("isSameThread"),a->true)));
            level = stub(ServerLevel.class,List.of(new Answer(ServerLevel.class.getMethod("getServer"),a->server),
                new Answer(ServerLevel.class.getMethod("getBlockState",BlockPos.class),a->Blocks.CHEST.defaultBlockState()),
                new Answer(ServerLevel.class.getMethod("getBlockEntity",BlockPos.class),a->chests.get(a[0])),
                new Answer(ServerLevel.class.getMethod("gameEvent",net.minecraft.core.Holder.class,net.minecraft.world.phys.Vec3.class,net.minecraft.world.level.gameevent.GameEvent.Context.class),a->{effects.add("game-event");return null;}),
                new Answer(ServerLevel.class.getMethod("playSound",Entity.class,double.class,double.class,double.class,net.minecraft.sounds.SoundEvent.class,net.minecraft.sounds.SoundSource.class,float.class,float.class),a->{effects.add("sound");return null;})));
            set(level,Level.class,"dimension",Level.OVERWORLD); set(level,Level.class,"thread",Thread.currentThread());
            set(level,Level.class,"random",net.minecraft.util.RandomSource.create());
            player = stub(ServerPlayer.class,List.of(new Answer(ServerPlayer.class.getMethod("isSpectator"),a->false),
                new Answer(ServerPlayer.class.getMethod("onRemoval",Entity.RemovalReason.class),a->null),
                new Answer(ServerPlayer.class.getMethod("drop",ItemStack.class,boolean.class),a->{drops.add(((ItemStack)a[0]).copy());return null;}),
                new Answer(ServerPlayer.class.getMethod("hasDisconnected"),a->disconnected),
                new Answer(ServerPlayer.class.getMethod("canBeSeenAsEnemy"),a->false), new Answer(ServerPlayer.class.getMethod("isCreative"),a->true),
                new Answer(ServerPlayer.class.getMethod("getGameProfile"),a->new com.mojang.authlib.GameProfile(uuid,"CloseTest"))));
            set(player,Player.class,"abilities",new Abilities()); set(player,Entity.class,"type",EntityTypes.PLAYER); set(player,Entity.class,"stringUUID",uuid.toString());
            set(player,Entity.class,"level",level); set(player,Entity.class,"uuid",uuid); set(player,Entity.class,"id",17);
            set(player,Entity.class,"chunkPosition",new ChunkPos(0,0));
            set(player,Entity.class,"position",new net.minecraft.world.phys.Vec3(15,64,1));
            set(player,LivingEntity.class,"brain",new net.minecraft.world.entity.ai.Brain<>());
            set(player,LivingEntity.class,"activeEffects",new HashMap<>());
            set(player,Entity.class,"passengers",com.google.common.collect.ImmutableList.of());
            set(player,Entity.class,"levelCallback",net.minecraft.world.level.entity.EntityInLevelCallback.NULL);
            set(player,ServerPlayer.class,"server",server);
            inventory = new Inventory(player,new EntityEquipment()); set(player,Player.class,"inventory",inventory);
            set(player,Player.class,"inventoryMenu",new InventoryMenu(inventory,true,player)); player.containerMenu=player.inventoryMenu;
            var connection = allocate(Connection.class);
            listener = stub(ServerGamePacketListenerImpl.class,List.of(
                new Answer(ServerGamePacketListenerImpl.class.getMethod("send",Packet.class),a->{sent.add((Packet<?>)a[0]);return null;}),
                new Answer(FullFinishingScoreboard.ConnectionAccess.class.getMethod("phoenium$serviceConnection"),a->connection)),FullFinishingScoreboard.ConnectionAccess.class);
            listener.player=player; player.connection=listener;
            first = new ChestBlockEntity(new BlockPos(15,64,1),Blocks.CHEST.defaultBlockState()); first.setLevel(level);
            second = new ChestBlockEntity(new BlockPos(16,64,1),Blocks.CHEST.defaultBlockState()); second.setLevel(level);
            chests.put(first.getBlockPos(),first); chests.put(second.getBlockPos(),second);
            var blocks = new RegionalScheduledTickService<Block>(world,directory,dispatcher,k->true,true);
            ((Map<ServerLevel,RegionalWorldServices.WorldServices>)field(services,RegionalWorldServices.class,"worlds"))
                .put(level,new RegionalWorldServices.WorldServices(level,blocks,null,events,null,null,null));
            set(live,PhoeniumLiveRuntime.class,"scheduler",scheduler); set(live,PhoeniumLiveRuntime.class,"regionalDispatcher",dispatcher);
            set(live,PhoeniumLiveRuntime.class,"regionalServices",services); set(live,PhoeniumLiveRuntime.class,"serverInstance",server);
            set(live,PhoeniumLiveRuntime.class,"objectGateway",new ObjectOwnerGateway(objects,directory,gateway));
            var state = Class.forName("dev.phoenium.live.PhoeniumLiveRuntime$State");
            set(live,PhoeniumLiveRuntime.class,"state",Enum.valueOf((Class)state,"RUNNING"));
            ((EntityIndexLifecycleAdapter)field(live,PhoeniumLiveRuntime.class,"entityIndexLifecycle"))
                .addressable(uuid.toString(),OwnedObjectKind.PLAYER,world,17,new RoutingKey(world,0,0),false);
            try(var turn=FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.PLAYER_LIFECYCLE)) {
                var scoreboard=FullFinishingServices.forServer(server).scoreboard;
                scoreboard.registered(player); scoreboard.playerUuidPublished(uuid,player);
            }
            ContainerCloseTestHooks.live=live;
            ContainerCloseTestHooks.removal=menu->removals++;
            ContainerCloseTestHooks.event=world->turns.add(FullFinishingServices.currentTurn(server));
        }
        void open(boolean doubleChest) throws Exception {
            // Establish an already-open native menu without scheduling an unrelated native recheck tick.
            set(first,BlockEntity.class,"remove",true); set(second,BlockEntity.class,"remove",true);
            player.containerMenu=doubleChest ? ChestMenu.sixRows(7,inventory,new net.minecraft.world.CompoundContainer(first,second)) : ChestMenu.threeRows(7,inventory,first);
            set(first,BlockEntity.class,"remove",false); set(second,BlockEntity.class,"remove",false);
            set(field(first,ChestBlockEntity.class,"openersCounter"),ContainerOpenersCounter.class,"openCount",1);
            if(doubleChest) set(field(second,ChestBlockEntity.class,"openersCounter"),ContainerOpenersCounter.class,"openCount",1);
        }
        int count(ChestBlockEntity chest) throws Exception { return ((ContainerOpenersCounter)field(chest,ChestBlockEntity.class,"openersCounter")).getOpenerCount(); }
        void tracking() {
            try(var turn=FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.SERVER_TICK)) {
                var scoreboard=FullFinishingServices.forServer(server).scoreboard;
                var cut=scoreboard.trackingCut(List.of(player));scoreboard.requireTrackingSession(cut,cut.sessions().getFirst());
            }
        }
        void drain() {
            try(var turn=FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.SERVER_TICK)) {
                dispatcher.drainBeforeSelection();
                events.runCycle(e->true,e->{delivered.add(e);chests.get(e.pos()).triggerEvent(e.paramA(),e.paramB());});
            }
        }
    }
}
