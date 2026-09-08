package dev.phoenium.clienttest;

import dev.phoenium.clientobs.ClientTraceObservations;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.security.MessageDigest;

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
        Path restartTrace = trace.resolveSibling("STAGE4-CLIENT-reload-trace.json");
        Path restartLog = directory.resolve("stage4-client-server-" + runId + "-restart.log");
        Path mcaSnapshot = trace.resolveSibling("mca-before-restart");
        Process server = null;
        boolean connected = false;
        boolean candidate = !Boolean.getBoolean("phoenium.stage4.reference");
        try {
            Files.createDirectories(trace.toAbsolutePath().getParent());
            Files.createDirectories(log.toAbsolutePath().getParent());
            configure(directory, worldName);
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString(),
                "-Xmx2G", "-Dphoenium.test.diagnostics=true",
                "-Dphoenium.stage4.candidate=" + candidate,
                // Both modes use the same accepted Stage 3 support flags and disposable Stage 4
                // probe.  explicitTopology is the established one-worker fixture; the
                // stage4.candidate flag alone controls calls that require the new Stage 4
                // coordinator/authority APIs.
                "-Dphoenium.full.authoritative=true",
                "-Dphoenium.stage3.explicitTopology=true",
                "-Dphoenium.test.chunkFixtureSupport=true",
                "-Dphoenium.stage3.chunkTrace=true",
                "-Dphoenium.stage4.viewDistance=" + System.getProperty("phoenium.stage4.viewDistance", "8"),
                "-Dphoenium.stage4.simulationDistance=" + System.getProperty("phoenium.stage4.simulationDistance", "8"),
                "-Dphoenium.stage4.tracePath=" + trace.toAbsolutePath()));
            if (Boolean.getBoolean("phoenium.stage4.packetTrace"))
                command.add("-Dphoenium.stage4.packetTracePath="
                    + trace.getParent().resolve("client-packets.log").toAbsolutePath());
            command.add("-jar");
            command.add("fabric-server-launch.jar");
            command.add("nogui");
            verifyInstalledProduction(directory, trace, "first", candidate);
            server = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            waitForReady(context, server, log);
            connectClient(context);
            connected = true;
            // Hold the native server clock before arming the fixture.  Every subsequent
            // simulation phase advances through the same explicit ServerTickRateManager step
            // in both the frozen reference and the regional candidate.
            // Prime the four exact fixture chunks through the real player distance path before
            // freezing.  This avoids a synchronous managed getChunk wait in the server command,
            // while each client-side FULL receipt proves the native holder future completed.
            context.waitFor(client -> client.player != null, CONNECT_TIMEOUT_TICKS);
            topologyTeleport(context, server, log, 8, 65, 8, new int[][] {{0, 0}});
            command(server, "stage4trace prepare-a");
            awaitLog(context, server, log, "STAGE4_TRACE_PREPARED_POINT label=A", START_TIMEOUT_TICKS);
            topologyTeleport(context, server, log, 120, 65, 8, new int[][] {{7, 0}});
            command(server, "stage4trace inspect-demand 7 0");
            awaitLog(context, server, log, "STAGE4_TRACE_DEMAND_DIAGNOSTIC chunk=7,0", START_TIMEOUT_TICKS);
            command(server, "stage4trace prepare-b");
            awaitLog(context, server, log, "STAGE4_TRACE_PREPARED_POINT label=B", START_TIMEOUT_TICKS);
            topologyTeleport(context, server, log, 520, 65, 8, new int[][] {{32, 0}});
            command(server, "stage4trace inspect-far");
            awaitLog(context, server, log, "STAGE4_TRACE_FAR_DEMAND_DONE center=32,0", START_TIMEOUT_TICKS);
            // Let the native distance manager consume the teleport before the second snapshot;
            // the immediate snapshot above records the exact pre-update state as well.
            for (int tick = 0; tick < 120; tick++) context.waitTick();
            command(server, "stage4trace inspect-far-late");
            awaitLog(context, server, log, "STAGE4_TRACE_FAR_DEMAND_DONE_LATE center=32,0", START_TIMEOUT_TICKS);
            command(server, "stage4trace prepare-far");
            awaitLog(context, server, log, "STAGE4_TRACE_PREPARED_POINT label=far", START_TIMEOUT_TICKS);
            topologyTeleport(context, server, log, 520, 65, 200, new int[][] {{32, 12}});
            command(server, "stage4trace prepare-deferred");
            awaitLog(context, server, log, "STAGE4_TRACE_PREPARED_FAR", START_TIMEOUT_TICKS);
            topologyTeleport(context, server, log, 8, 65, 8, new int[][] {{0, 0}});
            command(server, "stage4trace freeze");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_FROZEN", START_TIMEOUT_TICKS);
            command(server, "stage4trace run");
            command(server, "stage4trace require-block-recipient");
            if (candidate) command(server, "stage4trace require-tracking-lifecycle");
            awaitLog(context, server, log, "STAGE4_TRACE_ARMED", START_TIMEOUT_TICKS);
            // The far and deferred chunks were force retained only to obtain authentic FULL
            // receipts before arm. Release those temporary tickets before stepping the clock so
            // the deferred event still exercises native eligibility and later viewport admission.
            command(server, "stage4trace release-temporary");
            awaitLog(context, server, log, "STAGE4_TRACE_TEMPORARY_RELEASED", START_TIMEOUT_TICKS);
            // Prove the deferred event is rejected by the native distance predicate while its
            // far chunk is not in the player's viewport.  The later check is made only after
            // the real deferred viewport FULL receipt, so neither result is inferred from a
            // route label or a scheduled deadline.
            command(server, "stage4trace check-deferred-ineligible");
            awaitLog(context, server, log,
                "STAGE4_TRACE_DEFERRED_ELIGIBILITY phase=before-viewport", START_TIMEOUT_TICKS);
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
            topologyTeleport(context, server, log, 8, 65, 8, new int[][] {{0, 0}});
            command(server, "stage4trace barrier setup");
            awaitLog(context, server, log, "STAGE4_TRACE_BARRIER label=setup", START_TIMEOUT_TICKS);
            command(server, "stage4trace step settle 80");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=settle", START_TIMEOUT_TICKS);
            topologyTeleport(context, server, log, 120, 65, 8, new int[][] {{7, 0}});
            command(server, "stage4trace barrier region-b");
            awaitLog(context, server, log, "STAGE4_TRACE_BARRIER label=region-b", START_TIMEOUT_TICKS);
            command(server, "stage4trace clear-block-probe-support");
            ClientTraceObservations.clearPacketEvents();
            command(server, "stage4trace block-probe-a");
            command(server, "stage4trace step far-block 10");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=far-block", START_TIMEOUT_TICKS);
            assertClientPacket(context, "ClientboundBlockEventPacket", false,
                "b0=4", "b1=21", "pos=BlockPos{x=8, y=65, z=8}");
            command(server, "stage4trace block-probe-check-a");
            ClientTraceObservations.clearPacketEvents();
            command(server, "stage4trace block-probe-b");
            command(server, "stage4trace step moved-block 10");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=moved-block", START_TIMEOUT_TICKS);
            assertClientPacket(context, "ClientboundBlockEventPacket", true,
                "b0=5", "b1=22", "pos=BlockPos{x=120, y=65, z=8}");
            command(server, "stage4trace block-probe-check-b");
            command(server, "stage4trace step settle-b 80");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=settle-b", START_TIMEOUT_TICKS);
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
            topologyTeleport(context, server, log, 520, 65, 8, new int[][] {{32, 0}});
            command(server, "stage4trace barrier region-b-far");
            awaitLog(context, server, log, "STAGE4_TRACE_BARRIER label=region-b-far", START_TIMEOUT_TICKS);
            command(server, "stage4trace step settle-b-far 80");
            awaitLog(context, server, log, "STAGE4_TRACE_CLOCK_STEP_REACHED label=settle-b-far", START_TIMEOUT_TICKS);
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
            topologyTeleport(context, server, log, 520, 65, 200, new int[][] {{32, 12}});
            command(server, "stage4trace barrier deferred-ready");
            awaitLog(context, server, log, "STAGE4_TRACE_BARRIER label=deferred-ready", START_TIMEOUT_TICKS);
            command(server, "stage4trace await-full-deferred");
            awaitLog(context, server, log, "STAGE4_TRACE_FULL_READY label=deferred", START_TIMEOUT_TICKS);
            command(server, "stage4trace check-deferred-eligible");
            awaitLog(context, server, log,
                "STAGE4_TRACE_DEFERRED_ELIGIBILITY phase=after-viewport", START_TIMEOUT_TICKS);
            // Leave the original now+400 block obligation pending while allowing the migrated
            // far fluid/block (now+320) to execute under B after the split.  This is an exact
            // native frozen-clock step rather than a wall-clock/game-time deadline race.
            ClientTraceObservations.clearPacketEvents();
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
            copyMcaSnapshot(directory.resolve(worldName), mcaSnapshot);
            Files.copy(trace, trace.resolveSibling("STAGE4-CLIENT-before-restart-trace.json"));

            // Reopen the same saved client world. The child server starts with the test-only
            // native clock frozen; reconnecting at the saved B cohort supplies real ticking
            // demand before reload observes the persisted target chunk.
            List<String> restartCommand = new ArrayList<>(command);
            int jarIndex = restartCommand.indexOf("-jar");
            restartCommand.add(jarIndex, "-Dphoenium.stage4.restartFreeze=true");
            for (int index = 0; index < restartCommand.size(); index++)
                if (restartCommand.get(index).startsWith("-Dphoenium.stage4.tracePath="))
                    restartCommand.set(index, "-Dphoenium.stage4.tracePath=" + restartTrace.toAbsolutePath());
            verifyInstalledProduction(directory, trace, "restart", candidate);
            server = new ProcessBuilder(restartCommand).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(restartLog.toFile())).start();
            waitForReady(context, server, restartLog);
            awaitLog(context, server, restartLog, "STAGE4_TRACE_STARTUP_CLOCK_FROZEN", START_TIMEOUT_TICKS);
            connectClient(context);
            connected = true;
            context.waitFor(client -> client.player != null, CONNECT_TIMEOUT_TICKS);
            topologyTeleport(context, server, restartLog, 120, 65, 8, new int[][] {{7, 0}});
            long reloadOffset = logSize(restartLog);
            command(server, "stage4trace reload");
            awaitLogAfter(context, server, restartLog, "STAGE4_TRACE_RELOAD_UNPACK", reloadOffset,
                START_TIMEOUT_TICKS, "matches=1");
            awaitLogAfter(context, server, restartLog, "STAGE4_TRACE_RELOAD_CLOCK_FROZEN", reloadOffset,
                START_TIMEOUT_TICKS, null);
            awaitLogAfter(context, server, restartLog, "STAGE4_TRACE_RELOAD_ELIGIBILITY", reloadOffset,
                START_TIMEOUT_TICKS, "eligible=true");
            long restoredStepOffset = logSize(restartLog);
            command(server, "stage4trace step-restored 450");
            awaitLogAfter(context, server, restartLog, "STAGE4_TRACE_RELOAD_STEP_SCHEDULED phase=before",
                restoredStepOffset, 30, null);
            awaitLogAfter(context, server, restartLog,
                "STAGE4_TRACE_CLOCK_STEP_REACHED label=restored-before-deadline", restoredStepOffset, 180, null);
            long beforePhaseOffset = logSize(restartLog);
            command(server, "stage4trace check-restored-before");
            awaitLogAfter(context, server, restartLog, "STAGE4_TRACE_RELOAD_PHASE phase=before",
                beforePhaseOffset, 30, "pass=true");
            long deadlineStepOffset = logSize(restartLog);
            command(server, "stage4trace step restored-deadline 1");
            awaitLogAfter(context, server, restartLog,
                "STAGE4_TRACE_CLOCK_STEP_REACHED label=restored-deadline", deadlineStepOffset, 30, null);
            long deadlinePhaseOffset = logSize(restartLog);
            command(server, "stage4trace check-restored-deadline");
            awaitLogAfter(context, server, restartLog, "STAGE4_TRACE_RELOAD_PHASE phase=deadline",
                deadlinePhaseOffset, 30, "pass=true");
            long afterStepOffset = logSize(restartLog);
            command(server, "stage4trace step restored-after-deadline 1");
            awaitLogAfter(context, server, restartLog,
                "STAGE4_TRACE_CLOCK_STEP_REACHED label=restored-after-deadline", afterStepOffset, 30, null);
            long afterPhaseOffset = logSize(restartLog);
            command(server, "stage4trace check-restored-after");
            awaitLogAfter(context, server, restartLog, "STAGE4_TRACE_RELOAD_PHASE phase=after",
                afterPhaseOffset, 30, "pass=true");
            disconnectClient(context);
            connected = false;
            command(server, "stop");
            waitForExit(context, server);
            writeEvidence(trace, log, restartTrace, restartLog, mcaSnapshot,
                worldName, runId, true, server.exitValue());
        } catch (Throwable failure) {
            Path dumpDirectory = log.toAbsolutePath().getParent() == null
                ? Path.of("run/stage4/diagnostics")
                : log.toAbsolutePath().getParent().resolve("thread-dumps");
            captureThreadDump(server, dumpDirectory.resolve("server-handshake-timeout.txt"));
            captureCurrentThreadDump(dumpDirectory.resolve("client-handshake-timeout.txt"));
            captureNetworkState(server, dumpDirectory.resolve("tcp-state-handshake-timeout.txt"));
            if (connected) try { disconnectClient(context); } catch (Throwable ignored) { }
            if (server != null && server.isAlive()) {
                try { command(server, "stop"); } catch (Throwable ignored) { server.destroy(); }
            }
            throw new AssertionError("Stage 4 account-free client trace failed", failure);
        } finally {
            if (server != null && server.isAlive()) server.destroyForcibly();
        }
    }

    private static void verifyInstalledProduction(Path directory, Path trace, String phase,
                                                  boolean candidate) throws IOException {
        String frozen = "017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534";
        String expected = System.getProperty("phoenium.stage4.expectedProductionSha256", "");
        Path installed = directory.resolve("mods/phoenium-0.2.0-stage2.jar");
        String actual = sha256(installed);
        boolean pass = expected.matches("[0-9A-F]{64}") && expected.equals(actual)
            && (candidate ? !actual.equals(frozen) : actual.equals(frozen));
        Files.writeString(trace.resolveSibling("production-identity-" + phase + ".json"),
            "{\"phase\":" + jsonString(phase) + ",\"candidate\":" + candidate
                + ",\"expectedSha256\":" + jsonString(expected) + ",\"installedSha256\":"
                + jsonString(actual) + ",\"installed\":" + jsonString(installed.toAbsolutePath().toString())
                + ",\"pass\":" + pass + "}\n", StandardOpenOption.CREATE_NEW);
        if (!pass) throw new IllegalStateException("Stage 4 production identity rejected before " + phase
            + " server startup: expected=" + expected + " installed=" + actual + " candidate=" + candidate);
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

    private static void waitForClientChunks(ClientGameTestContext context, Process server, Path log,
                                             int[][] chunks, int timeoutTicks) {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            requireHealthyServer(server, log);
            if (tick == 40 || tick == 200) {
                try {
                    for (int[] chunk : chunks)
                        command(server, "stage4trace inspect-demand " + chunk[0] + " " + chunk[1]);
                    command(server, "phoenium status");
                } catch (IOException failure) { throw new AssertionError("cannot request pending FULL diagnostics", failure); }
            }
            boolean applied = context.computeOnClient(client -> {
                if (client.level == null) return false;
                for (int[] chunk : chunks) {
                    var loaded = client.level.getChunkSource()
                        .getChunk(chunk[0], chunk[1], ChunkStatus.FULL, false);
                    if (loaded == null || loaded.getPos().x() != chunk[0] || loaded.getPos().z() != chunk[1]
                        || !ClientTraceObservations.receivedChunk(chunk[0], chunk[1])) return false;
                }
                return true;
            });
            if (applied) return;
            context.waitTick();
        }
        String clientState = context.computeOnClient(client -> {
            if (client.level == null || client.player == null) return "level=none";
            StringBuilder state = new StringBuilder("player=")
                .append(client.player.getX()).append(',').append(client.player.getY()).append(',')
                .append(client.player.getZ()).append(" chunk=").append(client.player.chunkPosition());
            for (int[] chunk : chunks) {
                var loaded = client.level.getChunkSource()
                    .getChunk(chunk[0], chunk[1], ChunkStatus.FULL, false);
                state.append(" target=").append(chunk[0]).append(',').append(chunk[1])
                    .append(" loaded=").append(loaded != null)
                    .append(" packet=").append(ClientTraceObservations.receivedChunk(chunk[0], chunk[1]));
            }
            return state.toString();
        });
        throw new AssertionError("client did not apply exact FULL chunks "
            + java.util.Arrays.deepToString(chunks) + " within " + timeoutTicks + " ticks; "
            + clientState + " received=" + ClientTraceObservations.receivedChunksSnapshot());
    }

    /**
     * Runs one unchanged native teleport inside the test-only coordinator scope.  Preparation
     * and completion are separate server commands so the helper can verify the real native ACK,
     * section update, player-distance demand and applied FULL payload before restoring routes.
     */
    private static void topologyTeleport(ClientGameTestContext context, Process server, Path log,
                                          int x, int y, int z, int[][] expectedChunks) throws IOException {
        long preparedOffset = logSize(log);
        command(server, "stage4trace prepare-teleport " + x + " " + y + " " + z);
        awaitLogAfter(context, server, log, "STAGE4_TRACE_TELEPORT_TOPOLOGY_PREPARED", preparedOffset,
            START_TIMEOUT_TICKS, null);
        awaitLogAfter(context, server, log, "STAGE4_TRACE_TELEPORT_TERRAIN_READY", preparedOffset,
            START_TIMEOUT_TICKS, "destination=" + (x >> 4) + "," + (z >> 4));
        long teleportOffset = logSize(log);
        command(server, "tp " + USER + " " + x + ".5 " + y + " " + z + ".5");
        awaitLogAfter(context, server, log, "STAGE4_TRACE_TELEPORT_ACCEPT", teleportOffset,
            START_TIMEOUT_TICKS, null);
        nudgeAfterTeleport(context);
        waitForClientChunks(context, server, log, expectedChunks, START_TIMEOUT_TICKS);
        long completedOffset = logSize(log);
        command(server, "stage4trace complete-teleport");
        awaitLogAfter(context, server, log, "STAGE4_TRACE_TELEPORT_TOPOLOGY_COMPLETED", completedOffset,
            START_TIMEOUT_TICKS, null);
    }

    /**
     * A server-side /tp sends a native teleport packet, but vanilla only refreshes the
     * DistanceManager player-simulation ticket when ChunkMap.move is reached.  The regular
     * self-camera player tick does not call that method, so the next real client movement
     * packet is part of this fixture's controlled input.  One short horizontal input keeps
     * the player in the commanded chunk while completing teleport acknowledgement and the
     * native remove/add-player ticket transition in both reference and candidate runs.
     */
    private static void nudgeAfterTeleport(ClientGameTestContext context) {
        TestInput input = context.getInput();
        input.holdKey(options -> options.keyLeft);
        try {
            context.waitTicks(2);
        } finally {
            input.releaseKey(options -> options.keyLeft);
        }
        context.waitTicks(2);
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
        context.runOnClient(client -> observed[0] = ClientTraceObservations
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
            requireHealthyServer(server, log);
            if (!server.isAlive()) throw new AssertionError("server exited before barrier " + token + ": " + read(log));
            if (read(log).contains(token)) return;
            context.waitTick();
        }
        throw new AssertionError("server did not reach barrier " + token + ": " + log);
    }

    private static long logSize(Path log) {
        return read(log).length();
    }

    /** Waits for a marker emitted after {@code offset}; an optional token must be on that same line. */
    private static void awaitLogAfter(ClientGameTestContext context, Process server, Path log,
                                      String token, long offset, int timeoutTicks, String required) {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            requireHealthyServer(server, log);
            if (!server.isAlive()) throw new AssertionError("server exited before barrier " + token + ": " + read(log));
            String contents = read(log);
            int begin = (int) Math.min(offset, contents.length());
            for (String line : contents.substring(begin).split("\\R"))
                if (line.contains(token) && (required == null || line.contains(required))) return;
            context.waitTick();
        }
        throw new AssertionError("server did not reach fresh barrier " + token + " required=" + required + ": " + log);
    }

    private static void requireHealthyServer(Process server, Path log) {
        String contents = read(log);
        if (!server.isAlive() || contents.contains("live tick failure; simulation remains fenced")
                || contents.contains("An unexpected error occurred while trying to execute that command")
                || contents.contains("Encountered an unexpected exception"))
            throw new AssertionError("server failed during Stage 4 workload; evidence=" + log);
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
        // A logged-in client can receive the initial level stream before the local player
        // reference is attached.  The server command channel is already usable at this
        // boundary, and all fixture waits below independently require the level/chunks they
        // observe.  Waiting on player here can strand the server after a valid login.
        context.waitFor(client -> client.level != null && client.getConnection() != null, CONNECT_TIMEOUT_TICKS);
    }

    private static void disconnectClient(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ClientPacketListener listener = client.getConnection();
            if (listener != null) listener.getConnection().disconnect(Component.literal("Stage 4 trace disconnect"));
            client.disconnect(new TitleScreen(), false);
        });
        context.waitFor(client -> client.level == null, 600);
    }

    /** Preserve actionable stacks before the timeout cleanup tears down a stalled child. */
    private static void captureThreadDump(Process process, Path output) {
        if (process == null) return;
        captureThreadDump(process.pid(), output);
    }

    private static void captureCurrentThreadDump(Path output) {
        String runtimeName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int separator = runtimeName.indexOf('@');
        if (separator <= 0) return;
        try { captureThreadDump(Long.parseLong(runtimeName.substring(0, separator)), output); }
        catch (NumberFormatException ignored) { }
    }

    private static void captureThreadDump(long pid, Path output) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path javaHome = Path.of(System.getProperty("java.home"));
            String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "jcmd.exe" : "jcmd";
            Process dump = new ProcessBuilder(javaHome.resolve("bin").resolve(executable).toString(),
                Long.toString(pid), "Thread.print", "-l")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!dump.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) dump.destroyForcibly();
        } catch (Exception ignored) {
            try { Files.writeString(output, "thread dump unavailable: " + ignored); }
            catch (IOException ignoredAgain) { }
        }
    }

    /** Capture the OS TCP table while both sides still retain the stalled connection. */
    private static void captureNetworkState(Process server, Path output) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "netstat.exe" : "netstat";
            Process dump = new ProcessBuilder(executable, "-ano", "-p", "TCP")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!dump.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) dump.destroyForcibly();
            Files.writeString(output, "\nclientPid=" + ProcessHandle.current().pid()
                + "\nserverPid=" + (server == null ? "none" : server.pid())
                + "\nendpoint=127.0.0.1:25565\n", StandardOpenOption.APPEND);
        } catch (Exception failure) {
            try { Files.writeString(output, "network state unavailable: " + failure); }
            catch (IOException ignored) { }
        }
    }

    private static void writeEvidence(Path trace, Path log, Path restartTrace, Path restartLog,
                                      Path mcaSnapshot, String worldName, String runId,
                                      boolean connected, int exitCode) throws IOException {
        Path evidence = Path.of(System.getProperty("phoenium.client.gametest.evidence",
            trace.resolveSibling("STAGE4-CLIENT.json").toString()));
        Path parent = evidence.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        String viewDistance = System.getProperty("phoenium.stage4.viewDistance", "8");
        String simulationDistance = System.getProperty("phoenium.stage4.simulationDistance", "8");
        Path installedMods = restartLog.getParent().resolve("mods");
        String productionSha = sha256(installedMods.resolve("phoenium-0.2.0-stage2.jar"));
        String probeSha = sha256(installedMods.resolve("stage4-acceptance-probe.jar"));
        String unpackReceipt = lineContaining(restartLog, "STAGE4_TRACE_RELOAD_UNPACK");
        String beforeReceipt = lineContaining(restartLog, "STAGE4_TRACE_RELOAD_PHASE phase=before");
        String deadlineReceipt = lineContaining(restartLog, "STAGE4_TRACE_RELOAD_PHASE phase=deadline");
        String afterReceipt = lineContaining(restartLog, "STAGE4_TRACE_RELOAD_PHASE phase=after");
        Files.writeString(evidence, "{\n" +
            "  \"kind\": \"stage4-account-free-client-trace\",\n" +
            "  \"username\": \"" + USER + "\",\n" +
            "  \"runId\": \"" + runId + "\",\n" +
            "  \"world\": \"" + worldName + "\",\n" +
            "  \"microsoftAuthentication\": false,\n" +
            "  \"realTcpConnection\": " + connected + ",\n" +
            "  \"viewDistance\": " + viewDistance + ",\n" +
            "  \"simulationDistance\": " + simulationDistance + ",\n" +
            "  \"productionSha256\": \"" + productionSha + "\",\n" +
            "  \"probeSha256\": \"" + probeSha + "\",\n" +
            "  \"restartUnpackReceipt\": " + jsonString(unpackReceipt) + ",\n" +
            "  \"restartBeforeReceipt\": " + jsonString(beforeReceipt) + ",\n" +
            "  \"restartDeadlineReceipt\": " + jsonString(deadlineReceipt) + ",\n" +
            "  \"restartAfterReceipt\": " + jsonString(afterReceipt) + ",\n" +
            "  \"serverExitCode\": " + exitCode + ",\n" +
            "  \"trace\": \"" + trace.toAbsolutePath().toString().replace("\\", "\\\\") + "\",\n" +
            "  \"log\": \"" + log.toAbsolutePath().toString().replace("\\", "\\\\") + "\",\n" +
            "  \"restartTrace\": \"" + restartTrace.toAbsolutePath().toString().replace("\\", "\\\\") + "\",\n" +
            "  \"restartLog\": \"" + restartLog.toAbsolutePath().toString().replace("\\", "\\\\") + "\",\n" +
            "  \"mcaSnapshot\": \"" + mcaSnapshot.toAbsolutePath().toString().replace("\\", "\\\\") + "\"\n}\n",
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                for (int read; (read = input.read(buffer)) >= 0; )
                    if (read > 0) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format("%02X", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 unavailable", failure);
        }
    }

    private static String lineContaining(Path path, String token) throws IOException {
        if (!Files.exists(path)) return null;
        try (var lines = Files.lines(path)) {
            return lines.filter(line -> line.contains(token)).findFirst().orElse(null);
        }
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void copyMcaSnapshot(Path world, Path snapshot) throws IOException {
        if (!Files.exists(world)) throw new IOException("saved world is missing: " + world);
        if (Files.exists(snapshot)) throw new IOException("refusing to overwrite MCA snapshot: " + snapshot);
        try (var paths = Files.walk(world)) {
            paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".mca"))
                .forEach(path -> {
                    try {
                        Path target = snapshot.resolve(world.relativize(path));
                        Files.createDirectories(target.getParent());
                        Files.copy(path, target);
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                });
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }
}
