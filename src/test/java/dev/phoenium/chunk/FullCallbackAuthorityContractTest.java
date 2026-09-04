package dev.phoenium.chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.util.debug.LevelDebugSynchronizers;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import static org.junit.jupiter.api.Assertions.*;

/** Real-method semantic witnesses with controlled environment edges, NOT a production FULL binding. */
final class FullCallbackAuthorityContractTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void waypointReceiverIsReadEvenWhenFirstTickPreventsConnectionCreation() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        ServerLevel level = stub(ServerLevel.class, List.of(new Answer(ServerLevel.class.getMethod("getGameRules"),
                args -> new GameRules(FeatureFlags.DEFAULT_FLAGS))));
        ServerPlayer player = stub(ServerPlayer.class, List.of(new Answer(ServerPlayer.class.getMethod("level"),
                args -> { reads.incrementAndGet(); return level; })));
        LivingEntity transmitter = stub(LivingEntity.class, List.of());
        transmitter.setId(101); player.setId(102);
        set(transmitter, Entity.class, "firstTick", true);
        ServerWaypointManager manager = new ServerWaypointManager();
        set(manager, ServerWaypointManager.class, "players", Set.of(player));
        manager.trackWaypoint(transmitter);
        assertEquals(1, reads.get());
        assertTrue(manager.transmitters().contains(transmitter));
        assertTrue(transmitter.makeWaypointConnectionWith(player).isEmpty());
    }

    @Test void pairingBundlePrecedesConcreteWitherBossCallbackAndFailedSendKeepsMembership() throws Exception {
        List<Packet<?>> sent = new ArrayList<>();
        ServerPlayer player = allocate(ServerPlayer.class);
        player.setId(103);
        var failure = new IllegalStateException("boss send after pairing");
        player.connection = stub(ServerGamePacketListenerImpl.class, List.of(new Answer(
                ServerGamePacketListenerImpl.class.getMethod("send", Packet.class), args -> {
                    Packet<?> packet = (Packet<?>) args[0]; sent.add(packet);
                    if (packet instanceof ClientboundBossEventPacket) throw failure;
                    return null;
                })));
        WitherBoss wither = allocate(WitherBoss.class);
        ServerBossEvent boss = new ServerBossEvent(UUID.randomUUID(), Component.literal("witness"),
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        set(wither, WitherBoss.class, "bossEvent", boss);
        // Pairing-data collection is the controlled boundary. The original addPairing,
        // WitherBoss.startSeenByPlayer and ServerBossEvent.addPlayer run unchanged.
        ServerEntity tracker = stub(ServerEntity.class, List.of(new Answer(
                ServerEntity.class.getMethod("sendPairingData", ServerPlayer.class, Consumer.class), args -> null)));
        set(tracker, ServerEntity.class, "entity", wither);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> tracker.addPairing(player)));
        assertEquals(2, sent.size());
        assertInstanceOf(ClientboundBundlePacket.class, sent.get(0));
        assertInstanceOf(ClientboundBossEventPacket.class, sent.get(1));
        assertTrue(boss.getPlayers().contains(player));
    }

    @Test void debugListenerRegistrationMutatesBeforeLiveEntityLookupFailure() throws Exception {
        var failure = new IllegalStateException("unreserved live entity lookup");
        AtomicInteger lookups = new AtomicInteger();
        LevelDebugSynchronizers debug = new LevelDebugSynchronizers(null);
        set(debug, LevelDebugSynchronizers.class, "enabledSubscriptions", Set.of(DebugSubscriptions.GAME_EVENT_LISTENERS));
        ServerLevel level = stub(ServerLevel.class, List.of(
                new Answer(ServerLevel.class.getMethod("debugSynchronizers"), args -> debug),
                new Answer(ServerLevel.class.getMethod("getEntity", UUID.class), args -> { lookups.incrementAndGet(); throw failure; })));
        Entity entity = stub(Entity.class, List.of()); set(entity, Entity.class, "uuid", UUID.randomUUID());
        GameEventListener listener = new GameEventListener() {
            public PositionSource getListenerSource() { return new EntityPositionSource(entity, 0); }
            public int getListenerRadius() { return 16; }
            public boolean handleGameEvent(ServerLevel ignored, Holder<GameEvent> event, GameEvent.Context context, Vec3 pos) {
                throw new AssertionError("registration does not dispatch a gameplay event");
            }
        };
        var registry = new EuclideanGameEventListenerRegistry(level, 0, ignored -> { });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> registry.register(listener)));
        assertEquals(1, lookups.get()); assertFalse(registry.isEmpty());
    }

    @Test void concreteJukeboxRemovalDispatchesSynchronouslyAndPreservesFailedPrefix() throws Exception {
        List<String> events = new ArrayList<>();
        var failure = new IllegalStateException("listener failed");
        ServerLevel level = stub(ServerLevel.class, List.of(
                new Answer(ServerLevel.class.getMethod("gameEvent", Holder.class, Vec3.class, GameEvent.Context.class),
                        args -> { events.add("game-event"); throw failure; }),
                new Answer(Level.class.getMethod("levelEvent", int.class, BlockPos.class, int.class),
                        args -> { events.add("level-event"); return null; })));
        JukeboxBlockEntity jukebox = new JukeboxBlockEntity(BlockPos.ZERO, Blocks.JUKEBOX.defaultBlockState());
        jukebox.setLevel(level);
        assertSame(failure, assertThrows(IllegalStateException.class, jukebox::setRemoved));
        assertTrue(jukebox.isRemoved());
        assertEquals(List.of("game-event"), events);
    }

    @Test void entityReferenceResolutionReturnsAnActualForeignDimensionEntitySynchronously() throws Exception {
        UUID id = UUID.randomUUID(); AtomicInteger lookups = new AtomicInteger();
        ServerLevel foreign = stub(ServerLevel.class, List.of()); set(foreign, Level.class, "dimension", Level.NETHER);
        LivingEntity target = stub(LivingEntity.class, List.of());
        set(target, Entity.class, "uuid", id); set(target, Entity.class, "level", foreign);
        ServerLevel origin = stub(ServerLevel.class, List.of(new Answer(
                ServerLevel.class.getMethod("getEntityInAnyDimension", UUID.class), args -> { lookups.incrementAndGet(); return target; })));
        set(origin, Level.class, "dimension", Level.OVERWORLD);
        var reference = EntityReference.<LivingEntity>of(id);
        assertSame(target, EntityReference.getLivingEntity(reference, origin));
        assertSame(target, EntityReference.getLivingEntity(reference, origin));
        assertEquals(1, lookups.get(), "the live reference is cached after a liveness read");
        assertEquals(Level.NETHER, target.level().dimension());
    }

    @Test void legacyOwnerConversionCallsMutableCacheBeforeOfflineFallback() throws Exception {
        List<String> calls = new ArrayList<>();
        UUID id = UUID.randomUUID();
        UserNameToIdResolver cache = new UserNameToIdResolver() {
            public Optional<NameAndId> get(String name) { calls.add(name); return Optional.of(new NameAndId(id, name)); }
            public Optional<NameAndId> get(UUID uuid) { throw new AssertionError(); }
            public void add(NameAndId profile) { throw new AssertionError(); }
            public void resolveOfflineUsers(boolean value) { throw new AssertionError(); }
            public void save() { throw new AssertionError(); }
        };
        Services services = new Services(null, null, null, cache, null);
        MinecraftServer server = stub(MinecraftServer.class, List.of(
                new Answer(MinecraftServer.class.getMethod("services"), args -> services),
                new Answer(MinecraftServer.class.getMethod("usesAuthentication"), args -> { throw new AssertionError("after cache hit"); })));
        assertEquals(id, OldUsersConverter.convertMobOwnerIfNecessary(server, "OldOwner"));
        assertEquals(List.of("OldOwner"), calls);
        assertEquals(id, OldUsersConverter.convertMobOwnerIfNecessary(server, id.toString()));
        assertEquals(1, calls.size(), "UUID form bypasses the name cache");
    }

    @Test void entityLoaderCatchesNestedPartialEffectsWhileOuterSupplierSucceeds() throws Exception {
        ServerPlayer player = allocate(ServerPlayer.class); player.setId(104);
        AtomicInteger sends = new AtomicInteger();
        player.connection = stub(ServerGamePacketListenerImpl.class, List.of(new Answer(
                ServerGamePacketListenerImpl.class.getMethod("send", Packet.class), args -> {
                    sends.incrementAndGet(); throw new IllegalStateException("nested vanilla send failed");
                })));
        var boss = new ServerBossEvent(UUID.randomUUID(), Component.literal("partial"),
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        var type = stub(net.minecraft.world.entity.EntityType.class, List.of(new Answer(
                net.minecraft.world.entity.EntityType.class.getMethod("create", Level.class, net.minecraft.world.entity.EntitySpawnReason.class),
                args -> { boss.addPlayer(player); return null; })));
        Object originalResult = new Object();
        java.util.function.Supplier<Object> supplier = () -> {
            // Uses the actual EntityType.loadStaticEntity RuntimeException catch.
            // The factory is controlled; this is not a complete Entity.load/FULL witness.
            assertNull(net.minecraft.world.entity.EntityType.loadEntityRecursive(type,
                    (net.minecraft.world.level.storage.ValueInput) null, null,
                    net.minecraft.world.entity.EntitySpawnReason.LOAD, entity -> entity));
            return originalResult;
        };
        assertSame(originalResult, supplier.get());
        assertEquals(1, sends.get()); assertTrue(boss.getPlayers().contains(player));
    }

    @Test void realLegacyCacheMissPerformsLookupAndPersistenceBeforeReturn(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        UUID id = UUID.randomUUID(); AtomicInteger lookups = new AtomicInteger();
        var repository = (com.mojang.authlib.GameProfileRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.mojang.authlib.GameProfileRepository.class},
                (proxy, method, args) -> {
                    assertEquals("findProfileByName", method.getName());
                    lookups.incrementAndGet();
                    return Optional.of(new com.mojang.authlib.yggdrasil.response.NameAndId(id, (String) args[0]));
                });
        var path = directory.resolve("usercache.json");
        var cache = new net.minecraft.server.players.CachedUserNameToIdResolver(repository, path.toFile());
        Services services = new Services(null, null, repository, cache, null);
        MinecraftServer server = stub(MinecraftServer.class, List.of(new Answer(
                MinecraftServer.class.getMethod("services"), args -> services)));
        assertEquals(id, OldUsersConverter.convertMobOwnerIfNecessary(server, "OldOwner"));
        assertEquals(1, lookups.get());
        assertTrue(java.nio.file.Files.readString(path).contains(id.toString()),
                "the original nested lookup writes usercache.json before returning");
    }

    @Test void matureSavedTadpoleAgeInvokesConversionAndSynchronousDifficultyQuery() throws Exception {
        var failure = new ChunkProtocolException("FULL required by nested difficulty query is not ready");
        AtomicInteger conversions = new AtomicInteger(), queries = new AtomicInteger();
        ServerLevel level = stub(ServerLevel.class, List.of(new Answer(
                ServerLevel.class.getMethod("getCurrentDifficultyAt", BlockPos.class), args -> {
                    queries.incrementAndGet(); throw failure;
                })));
        var frog = stub(net.minecraft.world.entity.animal.frog.Frog.class, List.of());
        set(frog, Entity.class, "blockPosition", BlockPos.ZERO);
        var tadpole = stub(net.minecraft.world.entity.animal.frog.Tadpole.class, List.of(
                new Answer(Entity.class.getMethod("getTeam"), args -> null),
                new Answer(net.minecraft.world.entity.Mob.class.getMethod("convertTo",
                        net.minecraft.world.entity.EntityType.class, net.minecraft.world.entity.ConversionParams.class,
                        net.minecraft.world.entity.ConversionParams.AfterConversion.class), args -> {
                    conversions.incrementAndGet();
                    @SuppressWarnings("unchecked")
                    var callback = (net.minecraft.world.entity.ConversionParams.AfterConversion<net.minecraft.world.entity.animal.frog.Frog>) args[2];
                    callback.finalizeConversion(frog); return frog;
                })));
        set(tadpole, Entity.class, "level", level);
        Method setAge = net.minecraft.world.entity.animal.frog.Tadpole.class.getDeclaredMethod("setAge", int.class);
        setAge.setAccessible(true);
        var observed = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> setAge.invoke(tadpole, net.minecraft.world.entity.animal.frog.Tadpole.ticksToBeFrog));
        assertSame(failure, observed.getCause());
        assertEquals(1, conversions.get()); assertEquals(1, queries.get());
    }

    private record Answer(Method method, Function<Object[], Object> function) { }
    private static int sequence;
    private static <T> T stub(Class<T> parent, List<Answer> answers) throws Exception {
        String name = "dev/phoenium/chunk/CallbackWitnessEnvironment" + ++sequence;
        Type generated = Type.getObjectType(name), functions = Type.getType(Function[].class);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, Type.getInternalName(parent), null);
        writer.visitField(Opcodes.ACC_PUBLIC, "functions", functions.getDescriptor(), null, null).visitEnd();
        for (int i = 0; i < answers.size(); i++) {
            Method method = answers.get(i).method();
            var generator = new GeneratorAdapter(Opcodes.ACC_PUBLIC,
                    new org.objectweb.asm.commons.Method(method.getName(), Type.getMethodDescriptor(method)), null, null, writer);
            generator.loadThis(); generator.getField(generated, "functions", functions);
            generator.push(i); generator.arrayLoad(Type.getType(Function.class)); generator.loadArgArray();
            generator.invokeInterface(Type.getType(Function.class), new org.objectweb.asm.commons.Method("apply", "(Ljava/lang/Object;)Ljava/lang/Object;"));
            if (method.getReturnType() == void.class) generator.pop();
            else generator.unbox(Type.getType(method.getReturnType()));
            generator.returnValue(); generator.endMethod();
        }
        writer.visitEnd();
        Class<?> type = new ClassLoader(parent.getClassLoader()) {
            Class<?> define() { return defineClass(name.replace('/', '.'), writer.toByteArray(), 0, writer.toByteArray().length); }
        }.define();
        T value = parent.cast(allocate(type));
        type.getField("functions").set(value, answers.stream().map(Answer::function).toArray(Function[]::new));
        return value;
    }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(MinecraftLightReadFixture.allocate(type)); }
    private static void set(Object instance, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
}
