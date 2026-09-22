package dev.phoenium.chunk;

import dev.phoenium.live.*;
import dev.phoenium.ownership.*;
import dev.phoenium.service.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native thrown-pearl flight/collision/onHit -> real player teleport prefix and suffix.
 * World storage, packet sends, advancement, damage/sound, and base physics are controlled. */
class NativePearlPlayerHitTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() {
        NativePlayerPortalTestHooks.dimensions=null; NativePlayerPortalTestHooks.handoff=null;
        PortalDestinationTestHooks.controlledHit=null; PortalDestinationTestHooks.controlledBase=null; PortalDestinationTestHooks.pearls=null;
        NativePortalTestHooks.ticket=entity->entity.placePortalTicket(BlockPos.containing(entity.position()));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void nativeDestinationFlightAndHitTeleportThePlayerUnderItsOwnOwners(boolean forward) throws Exception {
        var h=new Fixture(forward); var pearl=h.pearl(); h.hit(pearl);
        assertSame(h.source,h.player.level()); assertFalse(pearl.isRemoved()); assertEquals(0,h.player.getPortalCooldown());
        assertEquals(1,PortalDestinationTestHooks.invoke(h.handoff,"advance"));
        assertTrue(h.player.isRemoved()); assertSame(h.source,h.player.level());
        assertEquals(1,PortalDestinationTestHooks.invoke(h.dimensions,"advance"));
        assertSame(h.destination,h.player.level()); assertFalse(h.player.isRemoved()); assertTrue(pearl.isRemoved());
        assertEquals(1,Collections.frequency(h.order,"player-remove")); assertEquals(1,Collections.frequency(h.order,"player-insert"));
        assertEquals(1,Collections.frequency(h.order,"damage")); assertEquals(1,Collections.frequency(h.order,"sound"));
        assertEquals(300,h.player.getPortalCooldown());
        assertEquals(0,PortalDestinationTestHooks.invoke(h.handoff,"advance")); assertEquals(0,PortalDestinationTestHooks.invoke(h.dimensions,"advance"));
    }
    @Test void staleSessionAfterNativeHitCannotMutatePlayer() throws Exception {
        var h=new Fixture(true); var pearl=h.pearl(); h.hit(pearl); h.session++;
        assertEquals(0,PortalDestinationTestHooks.invoke(h.handoff,"advance")); assertTrue(pearl.isRemoved());
        assertEquals(0,h.player.getPortalCooldown()); assertSame(h.source,h.player.level()); assertFalse(h.order.contains("player-remove"));
    }
    @Test void sessionLossAfterCommittedRemovalFailsWithoutReplayingOrMutatingReplacement() throws Exception {
        var h=new Fixture(true); var pearl=h.pearl(); h.hit(pearl);
        assertEquals(1,PortalDestinationTestHooks.invoke(h.handoff,"advance"));
        assertTrue(h.player.isRemoved()); h.session++;
        var failure=assertThrows(IllegalStateException.class,()->PortalDestinationTestHooks.invoke(h.dimensions,"advance"));
        assertTrue(failure.getMessage().contains("committed source retirement"));
        assertSame(h.source,h.player.level());assertTrue(h.player.isRemoved());
        assertFalse(h.order.contains("player-insert"));assertFalse(h.order.contains("damage"));
        assertEquals(0,PortalDestinationTestHooks.invoke(h.dimensions,"advance"));
    }
    @Test void worldClosingRetiresQueuedHitWithoutMutatingPlayer() throws Exception {
        var h=new Fixture(true);var pearl=h.pearl();h.hit(pearl);
        PortalDestinationTestHooks.invoke(h.handoff,"worldClosing",h.destination);
        assertEquals(0,PortalDestinationTestHooks.invoke(h.handoff,"advance"));
        assertFalse((boolean)PortalDestinationTestHooks.invoke(h.handoff,"pending",pearl));
        assertFalse(h.player.isRemoved());assertFalse(h.order.contains("player-remove"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void ordinaryPlayerPortalPreparesOwnerFreeAndUsesExistingDimensionSuffix(boolean forward) throws Exception {
        var h=new Fixture(forward);h.ownerFreeSource=true;
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var portal=stub(net.minecraft.world.level.block.NetherPortalBlock.class,List.of(
            new Answer(net.minecraft.world.level.block.NetherPortalBlock.class.getMethod("getPortalTransitionTime",ServerLevel.class,Entity.class),args->0),
            new Answer(net.minecraft.world.level.block.NetherPortalBlock.class.getMethod("getPortalDestination",ServerLevel.class,Entity.class,BlockPos.class),args->{
                assertTrue(OwnershipContext.current().isEmpty());
                assertTrue(h.f.runtimes.values().stream().noneMatch(r->r.leases().hasActiveLease()));
                ChunkSynchronousAccessGuard.beforeWait(()->false);reads.incrementAndGet();return h.f.transition(h.destination);
            })));
        h.player.setAsInsidePortal(portal,BlockPos.ZERO);
        var method=Entity.class.getDeclaredMethod("handlePortal");method.setAccessible(true);method.invoke(h.player);
        assertEquals(1,reads.get());assertTrue(h.player.isRemoved());assertEquals(0,h.f.manager.pendingCount());
        assertEquals(1,PortalDestinationTestHooks.invoke(h.dimensions,"advance"));
        assertSame(h.destination,h.player.level());assertFalse(h.player.isRemoved());
        assertEquals(1,Collections.frequency(h.order,"player-remove"));assertEquals(1,Collections.frequency(h.order,"player-insert"));
    }
    @Test void baselineForeignPlayerBeginRejectsThePearlOwner() throws Exception {
        var h=new Fixture(true);
        h.f.dispatcher.executeAndDrain(h.f.key(h.destination),RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
            assertThrows(IllegalStateException.class,()->PortalDestinationTestHooks.invoke(h.dimensions,"begin",h.player,h.f.transition(h.destination)));
        });
        assertSame(h.source,h.player.level()); assertFalse(h.player.isRemoved());
    }
    @Test void ordinaryLocalThrownPearlKeepsNativeHitAndPlayerTeleport() throws Exception {
        var h=new Fixture(true);h.destination=h.source;
        h.f.damageSources=new net.minecraft.world.damagesource.DamageSources(NativePortalDestinationPreparationTest.dynamicRegistries());
        h.f.sound=()->{h.owner(h.destination);h.order.add("sound");};
        var pearl=h.pearl();h.hit(pearl,false);
        assertTrue(pearl.isRemoved());assertSame(h.source,h.player.level());assertEquals(1,h.positions.size());
        assertEquals(1,Collections.frequency(h.order,"native-damage"));assertEquals(1,Collections.frequency(h.order,"sound"));
        assertEquals(0,PortalDestinationTestHooks.invoke(h.handoff,"advance"));assertEquals(0,PortalDestinationTestHooks.invoke(h.dimensions,"advance"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void nativePortalProjectileReplacementComposesWithFlightHitAndPlayerTravel(boolean forward) throws Exception {
        var h=new Fixture(forward);
        portalAndHit(h);
        assertSame(h.destination,h.player.level());assertEquals(1,h.positions.size());
        // A second independent throw, after settlement, crosses back with a new operation.
        var previous=h.source;h.source=h.destination;h.destination=previous;
        h.hitPosition=new Vec3(3,65,3); portalAndHit(h);
        assertEquals(2,h.positions.size());assertNotEquals(h.positions.get(0),h.positions.get(1));
        assertSame(h.destination,h.player.level()); assertEquals(2,Collections.frequency(h.order,"damage"));
    }
    static void portalAndHit(Fixture h) throws Exception {
        PortalDestinationTestHooks.pearls=NativePortalDestinationPreparationTest.pearlManager(h.f);
        NativePortalTestHooks.ticket=entity->{h.owner(h.destination);h.order.add("portal-ticket");};
        var projectile=h.pearl(h.source);
        projectile.setPortalCooldown(0);
        PortalDestinationTestHooks.controlledHit=BlockHitResult.miss(projectile.position(),net.minecraft.core.Direction.UP,BlockPos.ZERO);
        NativePortalDestinationPreparationTest.setPortal(projectile,args->{
            ChunkSynchronousAccessGuard.beforeWait(()->false); assertTrue(OwnershipContext.current().isEmpty());
            return h.f.transition(h.destination);
        });
        h.f.dispatcher.executeAndDrain(h.f.key(h.source),RegionalServiceKind.CHUNK_GAMEPLAY,ignored->projectile.tick());
        assertEquals(1,PortalDestinationTestHooks.pearlCall("advance"));
        var replacement=(ThrownEnderpearl)h.f.replacement; assertNotSame(projectile,replacement); assertEquals(projectile.getUUID(),replacement.getUUID());
        assertSame(h.source,h.player.level());h.hit(replacement);
        assertEquals(1,PortalDestinationTestHooks.invoke(h.handoff,"advance"));
        assertEquals(1,PortalDestinationTestHooks.invoke(h.dimensions,"advance"));
        assertTrue(replacement.isRemoved()); assertEquals(0,PortalDestinationTestHooks.pearlCall("advance"));
    }
    @Test void replacingPlayerWithSameUuidAfterHitCannotUseRetainedOldPointer() throws Exception {
        var h=new Fixture(true);var pearl=h.pearl();h.hit(pearl);var old=h.player;
        var binding=h.f.objects.current(old.getUUID().toString()).orElseThrow();
        assertTrue(h.f.identities.retirePortalPublication(binding,h.f.authority.worldId(h.source),old.getId()));
        h.player=h.makePlayer();h.player.setUUID(old.getUUID());h.f.publish(h.player,h.f.key(h.source));
        assertEquals(0,PortalDestinationTestHooks.invoke(h.handoff,"advance"));
        assertSame(h.source,h.player.level());assertFalse(h.player.isRemoved());assertEquals(0,h.player.getPortalCooldown());
        assertFalse(old.isRemoved());assertFalse(h.order.contains("damage"));
    }
    static final class Fixture {
        final NativeItemPortalTransferTest.Fixture f=new NativeItemPortalTransferTest.Fixture();
        final List<String> order=new ArrayList<>(); ServerLevel source,destination;
        final List<Vec3> positions=new ArrayList<>(); Vec3 hitPosition=new Vec3(.1,64.9,.2);
        final Object dimensions,handoff;
        ServerPlayer player; long session=7; boolean ownerFreeSource;
        Fixture(boolean forward) throws Exception {
            source=forward?f.overworld:f.nether; destination=forward?f.nether:f.overworld;
            f.levelData=(net.minecraft.world.level.storage.LevelData)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{net.minecraft.world.level.storage.LevelData.class},(p,m,args)->switch(m.getName()) {
                    case "getDifficulty"->net.minecraft.world.Difficulty.NORMAL;
                    case "isDifficultyLocked"->false;
                    default->null;
                });
            List<Answer> listAnswers=new ArrayList<>();
            for(String name:List.of("sendPlayerPermissionLevel","sendAllPlayerInfo","sendActivePlayerEffects"))
                listAnswers.add(new Answer(PlayerList.class.getMethod(name,ServerPlayer.class),args->{owner(player.level());order.add(name);return null;}));
            listAnswers.add(new Answer(PlayerList.class.getMethod("sendLevelInfo",ServerPlayer.class,ServerLevel.class),args->{owner(player.level());return null;}));
            listAnswers.add(new Answer(PlayerList.class.getMethod("getPlayer",UUID.class),args->player));
            var playerList=stub(PlayerList.class,listAnswers);
            f.server=stub(MinecraftServer.class,List.of(new Answer(MinecraftServer.class.getMethod("getPlayerList"),args->playerList)));
            var dimType=Class.forName("dev.phoenium.live.PlayerDimensionTransitionManager");
            var dimAuthority=Class.forName("dev.phoenium.live.PlayerDimensionTransitionManager$Authority");
            Object dimProxy=java.lang.reflect.Proxy.newProxyInstance(dimAuthority.getClassLoader(),new Class<?>[]{dimAuthority},(p,m,args)->switch(m.getName()) {
                case "running"->true;
                case "worldActive"->args[0]==f.overworld || args[0]==f.nether;
                case "worldId"->f.authority.worldId((ServerLevel)args[0]);
                case "currentSession"->args[0]==player ? session : -1L;
                case "execute"->{f.authority.execute((dev.phoenium.topology.RoutingKey)args[0],(RegionalServiceKind)args[1],(Runnable)args[2]);yield null;}
                case "retireSourceIndex"->{f.identities.retireTransitionSource(player.getUUID().toString(),f.authority.worldId(source),player.getId(),(long)args[2]);yield null;}
                default->throw new AssertionError(m.getName());
            });
            var ctor=dimType.getDeclaredConstructors()[0];ctor.setAccessible(true); dimensions=ctor.newInstance(dimProxy,f.directory,f.objects);
            var handoffType=Class.forName("dev.phoenium.live.PearlPlayerHitHandoff"); var sessionsType=Class.forName("dev.phoenium.live.PearlPlayerHitHandoff$Sessions");
            var sessions=java.lang.reflect.Proxy.newProxyInstance(sessionsType.getClassLoader(),new Class<?>[]{sessionsType},(p,m,args)->m.getName().equals("current")?player:args[0]==player?session:-1L);
            ctor=handoffType.getDeclaredConstructors()[0];ctor.setAccessible(true);handoff=ctor.newInstance(f.authority,f.objects,dimensions,sessions);
            NativePlayerPortalTestHooks.dimensions=dimensions; NativePlayerPortalTestHooks.handoff=handoff;
            player=makePlayer(); f.publish(player,f.key(source));
            player.setLevelCallback(new net.minecraft.world.level.entity.EntityInLevelCallback() {
                public void onMove() {}
                public void onRemove(Entity.RemovalReason reason) { owner(source); f.visible.get(source).remove(player.getUUID(),player); }
            });
            f.playerRemove=(level,value)->{owner(level); assertTrue((boolean)PortalDestinationTestHooks.invoke(dimensions,"onRemoval",value,Entity.RemovalReason.CHANGED_DIMENSION)); value.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);order.add("player-remove");};
            f.playerInsert=(level,value)->{owner(level);f.visible.get(level).put(value.getUUID(),value);order.add("player-insert");};
            NativePlayerPortalTestHooks.damage=p->{owner(destination);assertSame(player,p);order.add("damage");};
            NativePlayerPortalTestHooks.effects=p->{owner(destination);order.add("sound");};
            NativePlayerPortalTestHooks.trigger=p->{owner(destination);order.add("advancement");};
        }
        void owner(ServerLevel level) {
            if(ownerFreeSource && level==source && OwnershipContext.current().isEmpty()) return;
            f.owner(f.key(level));
        }
        ServerPlayer makePlayer() throws Exception {
            var player=stub(ServerPlayer.class,List.of(
                new Answer(ServerPlayer.class.getMethod("isAlive"),args->true),
                new Answer(ServerPlayer.class.getMethod("isSleeping"),args->false),
                new Answer(ServerPlayer.class.getMethod("isSpectator"),args->false),
                new Answer(ServerPlayer.class.getMethod("onRemoval",Entity.RemovalReason.class),args->null),
                new Answer(ServerPlayer.class.getMethod("resetFallDistance"),args->{owner(this.player.level());order.add("reset-fall");return null;}),
                new Answer(ServerPlayer.class.getMethod("resetCurrentImpulseContext"),args->{owner(this.player.level());return null;}),
                new Answer(ServerPlayer.class.getMethod("hurtServer",ServerLevel.class,net.minecraft.world.damagesource.DamageSource.class,float.class),args->{owner(this.player.level());order.add("native-damage");return true;}),
                new Answer(ServerPlayer.class.getMethod("removeVehicle"),args->null),
                new Answer(ServerPlayer.class.getMethod("getDimensionChangingDelay"),args->300),
                new Answer(ServerPlayer.class.getMethod("createCommonSpawnInfo",ServerLevel.class),args->null),
                new Answer(ServerPlayer.class.getMethod("setServerLevel",ServerLevel.class),args->{try{set(this.player,Entity.class,"level",args[0]);}catch(Exception e){throw new AssertionError(e);}return null;}),
                new Answer(ServerPlayer.class.getMethod("stopUsingItem"),args->null),
                new Answer(ServerPlayer.class.getMethod("getAbilities"),args->new net.minecraft.world.entity.player.Abilities()),
                new Answer(ServerPlayer.class.getMethod("registerEnderPearl",ThrownEnderpearl.class),args->null),
                new Answer(ServerPlayer.class.getMethod("deregisterEnderPearl",ThrownEnderpearl.class),args->null),
                new Answer(Entity.class.getDeclaredMethod("teleportSpectators",net.minecraft.world.level.portal.TeleportTransition.class,ServerLevel.class),args->null)));
            var seed=new net.minecraft.world.entity.item.ItemEntity(source,0,65,0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RED_SAND,1));
            for(var field:Entity.class.getDeclaredFields()) if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {field.setAccessible(true);field.set(player,field.get(seed));}
            set(player,Entity.class,"type",EntityTypes.PLAYER);set(player,ServerPlayer.class,"server",f.server);
            player.connection=stub(ServerGamePacketListenerImpl.class,List.of(
                new Answer(ServerGamePacketListenerImpl.class.getMethod("isAcceptingMessages"),args->true),
                new Answer(ServerGamePacketListenerImpl.class.getMethod("send",net.minecraft.network.protocol.Packet.class),args->{owner(this.player.level());order.add("packet");return null;}),
                new Answer(ServerGamePacketListenerImpl.class.getMethod("teleport",PositionMoveRotation.class,Set.class),args->{owner(this.player.level());order.add("connection-teleport");positions.add(((PositionMoveRotation)args[0]).position());return null;}),
                new Answer(ServerGamePacketListenerImpl.class.getMethod("resetPosition"),args->{owner(this.player.level());return null;})));
            player.connection.player=player; return player;
        }
        ThrownEnderpearl pearl() throws Exception {
            return pearl(destination);
        }
        ThrownEnderpearl pearl(ServerLevel level) throws Exception {
            var pearl=new ThrownEnderpearl(EntityTypes.ENDER_PEARL,level); pearl.setPos(0,65,0);pearl.setDeltaMovement(.1,-.1,.2);pearl.setPortalCooldown();
            set(pearl,net.minecraft.world.entity.projectile.Projectile.class,"owner",EntityReference.of((Entity)player));
            set(pearl,net.minecraft.world.entity.projectile.Projectile.class,"hasBeenShot",true);set(pearl,Entity.class,"firstTick",false);
            f.publish(pearl,f.key(level));return pearl;
        }
        void hit(ThrownEnderpearl pearl) {
            hit(pearl,true);
        }
        void hit(ThrownEnderpearl pearl,boolean pending) {
            PortalDestinationTestHooks.controlledBase=pearl;
            pearl.setPos(hitPosition);pearl.setOldPosAndRot();
            PortalDestinationTestHooks.controlledHit=new BlockHitResult(hitPosition,net.minecraft.core.Direction.UP,BlockPos.ZERO,false);
            f.dispatcher.executeAndDrain(f.key(destination),RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
                try {pearl.tick();} finally {if(PortalDestinationTestHooks.pearls!=null) PortalDestinationTestHooks.pearlCall("finishTick",pearl);}
            });
            assertEquals(pending,PortalDestinationTestHooks.invoke(handoff,"pending",pearl));
        }
    }
}
