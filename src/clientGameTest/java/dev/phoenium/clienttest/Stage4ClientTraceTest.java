package dev.phoenium.clienttest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Account-free client lifecycle for the installed Stage 4 ordered trace fixture. */
public final class Stage4ClientTraceTest implements FabricClientGameTest {
    private static final String USER = "PhoeniumTest0";
    private static final int PORT = 25565;
    private static final int START_TIMEOUT_TICKS = 3600;
    private static final int CONNECT_TIMEOUT_TICKS = 1200;
    private static final java.util.UUID ENTITY_A = java.util.UUID.fromString("00000001-0000-0002-0000-000300000004");
    private static final java.util.UUID ENTITY_B = java.util.UUID.fromString("00000005-0000-0006-0000-000700000008");
    private static final java.util.UUID ENTITY_B_FAR = java.util.UUID.fromString("00000009-0000-000a-0000-000b0000000c");

    @Override public void runTest(ClientGameTestContext context) {
        Path directory = Path.of(System.getProperty("phoenium.test.serverDirectory"));
        String runId = System.getProperty("phoenium.stage4.runId",
            Long.toUnsignedString(System.nanoTime()));
        String worldName = "stage4-client-world-" + runId;
        Path trace = Path.of(System.getProperty("phoenium.stage4.tracePath",
            directory.resolve("stage4-trace-" + runId + ".json").toString()));
        Path log = directory.resolve("stage4-client-server-" + runId + ".log");
        Process server = null;
        boolean connected = false;
        boolean candidate = !Boolean.getBoolean("phoenium.stage4.reference");
        try {
            configure(directory, worldName);
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString(),
                "-Xmx2G", "-Dphoenium.test.diagnostics=true",
                "-Dphoenium.stage4.candidate=" + candidate,
                // Both modes use the same disposable Stage 4 probe and support domain.
                // The candidate flag only changes calls that require regional production APIs.
                "-Dphoenium.stage3.explicitTopology=true",
                "-Dphoenium.test.chunkFixtureSupport=true",
                "-Dphoenium.stage3.chunkTrace=true",
                "-Dphoenium.stage4.tracePath=" + trace.toAbsolutePath()));
            command.add("-jar");
            command.add("fabric-server-launch.jar");
            command.add("nogui");
            server = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            waitForReady(context, server, log);
            connectClient(context);
            connected = true;
            // Hold the native server clock before arming the fixture.  Every subsequent
            // simulation phase advances through the same explicit ServerTickRateManager step
            // in both the frozen reference and the regional candidate.
            command(server, "stage4trace freeze");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_FROZEN", START_TIMEOUT_TICKS);
            command(server, "stage4trace run");
            command(server, "stage4trace require-block-recipient");
            if (candidate) command(server, "stage4trace require-tracking-lifecycle");
            awaitLog(context, server, log, "STAGE4_TRACE_ARMED", START_TIMEOUT_TICKS);
            // Fixed UUIDs keep the client packet projection comparable across fresh reference
            // and candidate worlds; entity IDs remain deterministic because the world is fresh.
            command(server, "summon minecraft:armor_stand 8 65 8 {NoGravity:1b,Invulnerable:1b,UUID:[I;1,2,3,4],Tags:[\"stage4_trace_a\"]}");
            command(server, "summon minecraft:armor_stand 120 65 8 {NoGravity:1b,Invulnerable:1b,UUID:[I;5,6,7,8],Tags:[\"stage4_trace_b\"]}");
            command(server, "summon minecraft:armor_stand 520 65 8 {NoGravity:1b,Invulnerable:1b,UUID:[I;9,10,11,12],Tags:[\"stage4_trace_b_far\"]}");
            if (candidate) {
                command(server, "stage4trace lifecycle");
                awaitLog(context, server, log, "STAGE4_TRACE_TOPOLOGY_MERGE", START_TIMEOUT_TICKS);
            }
            command(server, "stage4trace step initial 4");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=initial", START_TIMEOUT_TICKS);
            assertClientEntity(context, ENTITY_A, 8, 8, true);
            command(server, "tp " + USER + " 8.5 65 8.5");
            command(server, "stage4trace barrier setup");
            awaitLog(context, server, log, "STAGE4_TRACE_BARRIER label=setup", START_TIMEOUT_TICKS);
            command(server, "stage4trace step settle 80");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=settle", START_TIMEOUT_TICKS);
            command(server, "tp " + USER + " 120.5 65 8.5");
            command(server, "stage4trace barrier region-b");
            awaitLog(context, server, log, "STAGE4_TRACE_BARRIER label=region-b", START_TIMEOUT_TICKS);
            dev.phoenium.clienttrace.ClientTraceObservations.clear();
            command(server, "stage4trace block-probe-a");
            command(server, "stage4trace step far-block 10");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=far-block", START_TIMEOUT_TICKS);
            assertClientPacket(context, "ClientboundBlockEventPacket", false,
                "b0=4", "b1=21", "pos=BlockPos{x=8, y=65, z=8}");
            command(server, "stage4trace block-probe-check-a");
            dev.phoenium.clienttrace.ClientTraceObservations.clear();
            command(server, "stage4trace block-probe-b");
            command(server, "stage4trace step moved-block 10");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=moved-block", START_TIMEOUT_TICKS);
            assertClientPacket(context, "ClientboundBlockEventPacket", true,
                "b0=5", "b1=22", "pos=BlockPos{x=120, y=65, z=8}");
            command(server, "stage4trace block-probe-check-b");
            command(server, "stage4trace step settle-b 80");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=settle-b", START_TIMEOUT_TICKS);
            waitForClientFull(context, START_TIMEOUT_TICKS);
            assertClientEntity(context, ENTITY_B, 120, 8, true);
            // Check withdrawal while the client is still viewing B; after the next teleport
            // absence would be ambiguous because the entity can simply be out of range.
            command(server, "kill @e[tag=stage4_trace_b,limit=1]");
            command(server, "stage4trace step remove-b 1");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=remove-b", START_TIMEOUT_TICKS);
            assertClientEntity(context, ENTITY_B, 120, 8, false);
            // The split is intentionally requested only after the final distant viewport and
            // its FULL dependencies have settled, so the real admission path cannot merge it
            // again while the split is being proposed.
            command(server, "tp " + USER + " 520.5 65 8.5");
            command(server, "stage4trace barrier region-b-far");
            awaitLog(context, server, log, "STAGE4_TRACE_BARRIER label=region-b-far", START_TIMEOUT_TICKS);
            command(server, "stage4trace step settle-b-far 80");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=settle-b-far", START_TIMEOUT_TICKS);
            waitForClientFull(context, START_TIMEOUT_TICKS);
            assertClientEntity(context, ENTITY_B_FAR, 520, 8, true);
            command(server, "stage4trace await-full-far");
            awaitLog(context, server, log, "STAGE4_TRACE_FULL_READY label=far", START_TIMEOUT_TICKS);
            if (candidate) {
                command(server, "stage4trace split-far");
                awaitLog(context, server, log, "STAGE4_TRACE_TOPOLOGY_SPLIT", START_TIMEOUT_TICKS);
            }
            // Keep the player at the far B cohort while the now+320 obligations execute,
            // then use a separate short step for the deferred event in chunk 32,12.
            command(server, "stage4trace step far-effects 100");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=far-effects", START_TIMEOUT_TICKS);
            command(server, "stage4trace check-far");
            awaitLog(context, server, log, "STAGE4_TRACE_FAR_CHECK pass=true", START_TIMEOUT_TICKS);
            command(server, "tp " + USER + " 520.5 65 200.5");
            command(server, "stage4trace barrier deferred-ready");
            awaitLog(context, server, log, "STAGE4_TRACE_BARRIER label=deferred-ready", START_TIMEOUT_TICKS);
            waitForClientFull(context, START_TIMEOUT_TICKS);
            command(server, "stage4trace await-full-deferred");
            awaitLog(context, server, log, "STAGE4_TRACE_FULL_READY label=deferred", START_TIMEOUT_TICKS);
            // Leave the original now+400 block obligation pending while allowing the migrated
            // far fluid/block (now+320) to execute under B after the split.  This is an exact
            // native frozen-clock step rather than a wall-clock/game-time deadline race.
            dev.phoenium.clienttrace.ClientTraceObservations.clear();
            command(server, "stage4trace step post-split 10");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=post-split", START_TIMEOUT_TICKS);
            assertClientPacket(context, "ClientboundBlockEventPacket", true,
                "b0=3", "b1=11", "pos=BlockPos{x=520, y=65, z=200}");
            command(server, "stage4trace check-deferred");
            awaitLog(context, server, log, "STAGE4_TRACE_DEFERRED_CHECK pass=true", START_TIMEOUT_TICKS);
            command(server, "save-all flush");
            // save-all flush is a synchronous server command; use an immediate receipt because
            // the native clock is intentionally still frozen and cannot satisfy a tick deadline.
            command(server, "stage4trace barrier saved");
            awaitLog(context, server, log, "STAGE4_TRACE_BARRIER label=saved", START_TIMEOUT_TICKS);
            disconnectClient(context);
            connected = false;
            command(server, "stage4trace finish");
            awaitLog(context, server, log, "STAGE4_TRACE_RESULT", START_TIMEOUT_TICKS);
            command(server, "stop");
            waitForExit(context, server);
            writeEvidence(trace, log, worldName, runId, true, server.exitValue());
        } catch (Throwable failure) {
            if (connected) try { disconnectClient(context); } catch (Throwable ignored) { }
            if (server != null && server.isAlive()) {
                try { command(server, "stop"); } catch (Throwable ignored) { server.destroy(); }
            }
            throw new AssertionError("Stage 4 account-free client trace failed", failure);
        } finally {
            if (server != null && server.isAlive()) server.destroyForcibly();
        }
    }

    private static void waitForClientFull(ClientGameTestContext context, int timeoutTicks) {
        int radius = clientSimulationDistance();
        context.waitFor(client -> {
            if (client.level == null || client.player == null) return false;
            var center = client.player.chunkPosition();
            for (int x = center.x() - radius; x <= center.x() + radius; x++)
                for (int z = center.z() - radius; z <= center.z() + radius; z++)
                    if (client.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) return false;
            return true;
        }, timeoutTicks);
    }

    private static int clientSimulationDistance() {
        return Integer.parseInt(System.getProperty("phoenium.stage4.simulationDistance", "8"));
    }

    private static void assertClientEntity(ClientGameTestContext context, java.util.UUID uuid,
                                           double expectedX, double expectedZ, boolean present) {
        context.waitFor(client -> {
            Entity found = null;
            if (client.level != null) for (Entity entity : client.level.entitiesForRendering())
                if (uuid.equals(entity.getUUID())) { found = entity; break; }
            boolean observed = found != null && Math.abs(found.getX() - expectedX) < 2.0
                && Math.abs(found.getZ() - expectedZ) < 2.0;
            return present ? observed : found == null;
        }, 600);
    }

    private static void assertClientPacket(ClientGameTestContext context, String packetName,
                                           boolean present, String... payloadTokens) {
        for (int tick = 0; tick < 20; tick++) context.waitTick();
        boolean[] observed = {false};
        context.runOnClient(client -> observed[0] = dev.phoenium.clienttrace.ClientTraceObservations
            .receivedAll(packetName, payloadTokens));
        if (observed[0] != present)
            throw new AssertionError("client packet observation mismatch packet=" + packetName
                + " tokens=" + java.util.Arrays.toString(payloadTokens)
                + " expected=" + present + " observed=" + observed[0]);
    }

    private static void configure(Path directory, String worldName) throws IOException {
        Path world = directory.resolve(worldName).normalize();
        if (!world.startsWith(directory.normalize())) throw new IOException("fixture world escaped server directory");
        String viewDistance = System.getProperty("phoenium.stage4.viewDistance", "8");
        String simulationDistance = System.getProperty("phoenium.stage4.simulationDistance", "8");
        Files.writeString(directory.resolve("eula.txt"), "eula=true\n",
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(directory.resolve("server.properties"), String.join("\n",
            "level-name=" + worldName, "level-seed=444444", "level-type=minecraft:flat",
            "online-mode=false", "enforce-secure-profile=false", "server-ip=127.0.0.1",
            "server-port=" + PORT, "enable-rcon=false", "max-players=1", "difficulty=peaceful",
            "gamemode=creative", "view-distance=" + viewDistance,
            "simulation-distance=" + simulationDistance, "spawn-protection=0",
            "sync-chunk-writes=true", "pause-when-empty-seconds=-1", "") ,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void waitForReady(ClientGameTestContext context, Process server, Path log) {
        for (int tick = 0; tick < START_TIMEOUT_TICKS; tick++) {
            if (!server.isAlive()) throw new AssertionError("server exited before readiness: " + read(log));
            if (read(log).contains("Done (")) return;
            context.waitTick();
        }
        throw new AssertionError("server did not become ready: " + log);
    }

    private static void waitForExit(ClientGameTestContext context, Process server) {
        for (int tick = 0; tick < 600; tick++) {
            if (!server.isAlive()) return;
            context.waitTick();
        }
        server.destroyForcibly();
        throw new AssertionError("server did not stop normally");
    }

    private static void awaitLog(ClientGameTestContext context, Process server, Path log, String token, int timeoutTicks) {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            if (!server.isAlive()) throw new AssertionError("server exited before barrier " + token + ": " + read(log));
            if (read(log).contains(token)) return;
            context.waitTick();
        }
        throw new AssertionError("server did not reach barrier " + token + ": " + log);
    }

    private static String read(Path path) {
        try { return Files.exists(path) ? Files.readString(path) : ""; }
        catch (IOException failure) { return failure.toString(); }
    }

    private static void command(Process server, String value) throws IOException {
        server.getOutputStream().write((value + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.getOutputStream().flush();
    }

    private static void connectClient(ClientGameTestContext context) {
        // The server's "Done" log line is emitted before the listen event is
        // necessarily visible to a separately scheduled client task.  Wait for
        // the actual TCP endpoint so ConnectScreen does not consume its one
        // connection attempt during that short startup race.
        boolean listening = false;
        for (int tick = 0; tick < START_TIMEOUT_TICKS; tick++) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 250);
                listening = true;
                break;
            } catch (java.io.IOException ignored) {
                context.waitTick();
            }
        }
        if (!listening) throw new AssertionError("server TCP endpoint did not become ready");
        context.runOnClient(client -> {
            ServerData data = new ServerData("Phoenium Stage 4", "127.0.0.1:" + PORT, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(client.gui.screen(), client, ServerAddress.parseString("127.0.0.1:" + PORT), data, false, null);
        });
        context.waitFor(client -> client.level != null && client.player != null, CONNECT_TIMEOUT_TICKS);
    }

    private static void disconnectClient(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ClientPacketListener listener = client.getConnection();
            if (listener != null) listener.getConnection().disconnect(Component.literal("Stage 4 trace disconnect"));
            client.disconnect(new TitleScreen(), false);
        });
        context.waitFor(client -> client.level == null, 600);
    }

    private static void writeEvidence(Path trace, Path log, String worldName, String runId,
                                      boolean connected, int exitCode) throws IOException {
        Path evidence = Path.of(System.getProperty("phoenium.client.gametest.evidence",
            trace.resolveSibling("STAGE4-CLIENT.json").toString()));
        Path parent = evidence.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        String viewDistance = System.getProperty("phoenium.stage4.viewDistance", "8");
        String simulationDistance = System.getProperty("phoenium.stage4.simulationDistance", "8");
        Files.writeString(evidence, "{\n" +
            "  \"kind\": \"stage4-account-free-client-trace\",\n" +
            "  \"username\": \"" + USER + "\",\n" +
            "  \"runId\": \"" + runId + "\",\n" +
            "  \"world\": \"" + worldName + "\",\n" +
            "  \"microsoftAuthentication\": false,\n" +
            "  \"realTcpConnection\": " + connected + ",\n" +
            "  \"viewDistance\": " + viewDistance + ",\n" +
            "  \"simulationDistance\": " + simulationDistance + ",\n" +
            "  \"serverExitCode\": " + exitCode + ",\n" +
            "  \"trace\": \"" + trace.toAbsolutePath().toString().replace("\\", "\\\\") + "\",\n" +
            "  \"log\": \"" + log.toAbsolutePath().toString().replace("\\", "\\\\") + "\"\n}\n",
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
