package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.task.TaskCorrelationId;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A vanilla semantic witness, NOT acceptance of an authoritative FULL binding.
 * Executes the original Team restoration callback, ServerScoreboard mutation and
 * PlayerList.broadcastAll. Only environment getters and final network sinks are
 * controlled; no production hook, real network connection or account is used.
 */
final class FullPostLoadScoreboardContractTest {
    @Test void teamRestoreBroadcastsSynchronouslyOutsideTheChunkWorld() throws Throwable {
        var f = new Fixture();
        var world = new dev.phoenium.core.WorldId("minecraft:overworld");
        var owner = new dev.phoenium.region.RegionId(world, "chunk-owner");
        var key = new dev.phoenium.topology.RoutingKey(world, 4, 0);
        var services = new ChunkPrerequisiteServices(world, "full-contract-witness");
        var lifecycle = services.lifecycles().activateHolder(0, key, new Object());
        var work = services.works().beginOrResolveSharedWork(lifecycle,
                new ChunkWorkRegistry.WorkReservation("generation-status", "FULL", 0));
        var generation = new dev.phoenium.core.TopologyGeneration(1);
        var directory = new dev.phoenium.topology.RegionDirectory();
        directory.publish(generation, java.util.Map.of(key,
                new dev.phoenium.topology.RegionRoute(owner, generation, new dev.phoenium.core.OwnerEpoch(0))));
        var leases = new dev.phoenium.ownership.RegionLeaseManager(owner);
        var executor = new ChunkStageExecutor(new ChunkStageAdmission(services.custody(), directory,
                java.util.Map.of(owner, leases), new dev.phoenium.failure.FailureBarrier()));
        var stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.FULL_CONVERT, "FULL", 0);
        var pending = new PendingVanillaStage<Void, Void>(services.custody(), lifecycle,
                work, stage, new TaskCorrelationId(0), (input, claim) -> {
                    claim.require(stage);
                    f.beforeSend = () -> assertTrue(OwnershipContext.current().isPresent());
                    f.restore("saved-team");
                    assertEquals(List.of("overworld:ADD", "nether:ADD"), f.deliveries,
                            "both original broadcasts precede return of the nested callback");
                    return null;
                });
        pending.bindInput(null);
        pending.publishReady();
        assertEquals(ChunkStageAdmission.Result.APPLIED,
                executor.execute(pending, owner, generation));
        assertNotSame(f.overworld, f.nether);
        assertEquals(Level.OVERWORLD, f.overworld.dimension());
        assertEquals(Level.NETHER, f.nether.dimension());
        assertSame(f.scoreboard.getPlayerTeam("saved-team"), f.scoreboard.getPlayersTeam(Fixture.UUID));
        assertTrue(OwnershipContext.current().isEmpty());
        assertFalse(leases.hasActiveLease());
    }

    @Test void teamRestoreCanRemovePriorMembershipBeforeAddingSavedMembership() throws Throwable {
        var f = new Fixture();
        f.scoreboard.addPlayerTeam("prior-team");
        f.scoreboard.addPlayerToTeam(Fixture.UUID, f.scoreboard.getPlayerTeam("prior-team"));
        f.deliveries.clear();
        f.restore("saved-team");
        assertEquals(List.of("overworld:REMOVE", "nether:REMOVE", "overworld:ADD", "nether:ADD"), f.deliveries);
        assertFalse(f.scoreboard.getPlayerTeam("prior-team").getPlayers().contains(Fixture.UUID));
        assertTrue(f.scoreboard.getPlayerTeam("saved-team").getPlayers().contains(Fixture.UUID));
    }

    @Test void rejectingBroadcastAfterEntryCannotUndoTheEarlierScoreboardMutation() throws Throwable {
        var f = new Fixture();
        var exact = new ChunkProtocolException("witness: foreign send rejected");
        f.beforeSend = () -> { throw exact; };
        assertSame(exact, assertThrows(ChunkProtocolException.class, () -> f.restore("saved-team")));
        assertSame(f.scoreboard.getPlayerTeam("saved-team"), f.scoreboard.getPlayersTeam(Fixture.UUID),
                "a late guard is after the original mutation, not an atomic preflight");
        assertTrue(f.deliveries.isEmpty());
    }

    @Test void sameTeamStillRemovesAndAddsAndAbsentSavedTeamLeavesMembershipAlone() throws Throwable {
        var f = new Fixture();
        var team = f.scoreboard.getPlayerTeam("saved-team");
        assertTrue(f.scoreboard.addPlayerToTeam(Fixture.UUID, team));
        f.deliveries.clear();
        assertTrue(f.scoreboard.addPlayerToTeam(Fixture.UUID, team), "vanilla returns the post-removal set insertion result");
        assertEquals(List.of("overworld:REMOVE", "nether:REMOVE", "overworld:ADD", "nether:ADD"), f.deliveries);
        f.deliveries.clear();
        f.restore("absent-team");
        assertSame(team, f.scoreboard.getPlayersTeam(Fixture.UUID));
        assertTrue(f.deliveries.isEmpty());
        assertFalse(f.scoreboard.removePlayerFromTeam("absent-member"));
    }

    @Test void failureDuringRemoveLeavesNoMembershipAndNeverReachesAddOrDirty() throws Throwable {
        var f = new Fixture();
        var prior = f.scoreboard.addPlayerTeam("prior-team");
        f.scoreboard.addPlayerToTeam(Fixture.UUID, prior);
        f.deliveries.clear();
        set(f.scoreboard, ServerScoreboard.class, "dirty", false);
        var exact = new IllegalStateException("controlled REMOVE send failure");
        f.beforeSend = () -> { throw exact; };
        assertSame(exact, assertThrows(IllegalStateException.class, () -> f.restore("saved-team")));
        assertNull(f.scoreboard.getPlayersTeam(Fixture.UUID));
        assertFalse(prior.getPlayers().contains(Fixture.UUID));
        assertFalse(f.scoreboard.getPlayerTeam("saved-team").getPlayers().contains(Fixture.UUID));
        assertTrue(f.deliveries.isEmpty());
        Field dirty = ServerScoreboard.class.getDeclaredField("dirty"); dirty.setAccessible(true);
        assertFalse(dirty.getBoolean(f.scoreboard), "setDirty follows send and waypoint, not the membership write");
    }

    private static final class Fixture {
        static final String UUID = "ce40fa98-3714-4d72-8ad6-055a7d90f29d";
        final List<String> deliveries = new ArrayList<>();
        final ServerScoreboard scoreboard;
        final ServerLevel overworld, nether;
        final LivingEntity entity;
        Runnable beforeSend = () -> { };
        Fixture() throws Exception {
            SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
            PlayerList players = stub(PlayerList.class, List.of(
                    new Answer(PlayerList.class.getMethod("getPlayerByName", String.class), null)));
            MinecraftServer server = stub(MinecraftServer.class, List.of(
                    new Answer(MinecraftServer.class.getMethod("getPlayerList"), players)));
            scoreboard = new ServerScoreboard(server);
            overworld = stub(ServerLevel.class, List.of(
                    new Answer(ServerLevel.class.getMethod("getScoreboard"), scoreboard)));
            nether = stub(ServerLevel.class, List.of(
                    new Answer(ServerLevel.class.getMethod("getScoreboard"), scoreboard)));
            set(overworld, Level.class, "dimension", Level.OVERWORLD);
            set(nether, Level.class, "dimension", Level.NETHER);
            set(players, PlayerList.class, "players", List.of(player(overworld, "overworld"), player(nether, "nether")));
            scoreboard.addPlayerTeam("saved-team");
            deliveries.clear();
            entity = stub(LivingEntity.class, List.of());
            set(entity, Entity.class, "level", overworld);
            set(entity, Entity.class, "stringUUID", UUID);
        }
        ServerPlayer player(ServerLevel level, String label) throws Exception {
            ServerPlayer player = allocate(ServerPlayer.class);
            set(player, Entity.class, "level", level);
            Consumer<Object> sink = packet -> {
                beforeSend.run();
                if (packet instanceof ClientboundSetPlayerTeamPacket team && team.getPlayerAction() != null)
                    deliveries.add(label + ":" + team.getPlayerAction());
            };
            player.connection = stub(ServerGamePacketListenerImpl.class, List.of(
                    new Answer(ServerGamePacketListenerImpl.class.getMethod("send", Packet.class), sink)));
            return player;
        }
        void restore(String name) throws Throwable {
            Method callback = LivingEntity.class.getDeclaredMethod("lambda$readAdditionalSaveData$0", String.class);
            callback.setAccessible(true);
            try { callback.invoke(entity, name); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    private record Answer(Method method, Object value) { }
    private static int sequence;
    /** Constructorless test environment; the three vanilla bodies named above stay unmodified. */
    private static <T> T stub(Class<T> parent, List<Answer> answers) throws Exception {
        String name = "dev/phoenium/chunk/FullContractEnvironment" + ++sequence;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, Type.getInternalName(parent), null);
        writer.visitField(Opcodes.ACC_PUBLIC, "answers", "[Ljava/lang/Object;", null, null).visitEnd();
        for (int i = 0; i < answers.size(); i++) {
            Method method = answers.get(i).method();
            var mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method.getName(), Type.getMethodDescriptor(method), null, null);
            mv.visitCode(); mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, name, "answers", "[Ljava/lang/Object;");
            mv.visitLdcInsn(i); mv.visitInsn(Opcodes.AALOAD);
            if (method.getReturnType() == void.class) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
                mv.visitInsn(Opcodes.RETURN);
            } else {
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(method.getReturnType()));
                mv.visitInsn(Opcodes.ARETURN);
            }
            mv.visitMaxs(0, 0); mv.visitEnd();
        }
        writer.visitEnd();
        Class<?> type = new ClassLoader(parent.getClassLoader()) {
            Class<?> define() { return defineClass(name.replace('/', '.'), writer.toByteArray(), 0, writer.toByteArray().length); }
        }.define();
        T instance = parent.cast(allocate(type));
        type.getField("answers").set(instance, answers.stream().map(Answer::value).toArray());
        return instance;
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    private static void set(Object instance, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
}
