package dev.phoenium.clienttest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.InflaterInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P3-CLIENT: real packet evidence against a separately launched production server.
 *
 * The client is still started by Loom and the official Fabric Client GameTest runner.  The
 * dedicated server in this test is a separate Java process containing only the production
 * Phoenium JAR and the minimum Fabric server libraries.  This is intentionally not a mock,
 * direct packet constructor, or in-process handler call.
 */
public final class Stage3ClientTest implements FabricClientGameTest {
    private static final String TEST_USERNAME = "PhoeniumTest0";
    private static final String SAME_OWNER_TARGET_TAG = "phoenium_stage3_same_owner";
    private static final String CROSS_OWNER_TARGET_TAG = "phoenium_stage3_cross_owner";
    private static final String SERVER_ADDRESS = System.getProperty("phoenium.test.serverAddress", "127.0.0.1:25565");
    private static final int SERVER_START_TIMEOUT_TICKS = 1200;
    private static final int CLIENT_CONNECT_TIMEOUT_TICKS = 1200;
    private static final int CHUNK_TRACE_X = 32;
    private static final int CHUNK_TRACE_Z = 4;
    /** Same-owner target at the edge of fixture A, outside the readiness server's spawn view. */
    private static final int CHUNK_READINESS_X = 4;
    private static final int CHUNK_READINESS_Z = -4;
    private static final int CHUNK_TRACE_TIMEOUT_TICKS = 1200;
    /** Fresh client-demand world generation can legitimately outlive a three-minute probe. */
    private static final int CHUNK_READINESS_TIMEOUT_TICKS = 7200;
    /** Keep post-leave teardown bounded without making a missing unload look like a hang. */
    private static final int CHUNK_READINESS_LEAVE_TIMEOUT_TICKS = 300;
    private static final Pattern MOVE_SEGMENT = Pattern.compile("move\\[([^]]*)\\]");
    private static final Pattern INTERACT_SEGMENT = Pattern.compile("interact\\[([^]]*)\\]");
    private static final Pattern NUMERIC_FIELD = Pattern.compile("\\b([A-Za-z]+)=(-?\\d+)");
    private static final Pattern TRACE_STAGE = Pattern.compile("\\\"stage\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern TRACE_EXCEPTION = Pattern.compile("\\\"exception\\\"\\s*:\\s*(null|\\\"([^\\\"]*)\\\")");
    private static final Pattern POSITION = Pattern.compile(
        "\\[\\s*(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)[dDfF]?\\s*,"
            + "\\s*(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)[dDfF]?\\s*,"
            + "\\s*(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)[dDfF]?\\s*\\]");

    @Override
    public void runTest(ClientGameTestContext context) {
        if ("fullacceptance".equals(System.getProperty("phoenium.test.mode"))) {
            runFullAcceptance(context);
            return;
        }
        if ("chunkreadiness".equals(System.getProperty("phoenium.test.mode"))) {
            runChunkReadinessTrace(context);
            return;
        }
        if ("chunktrace".equals(System.getProperty("phoenium.test.mode"))) {
            runChunkTrace(context);
            return;
        }
        Evidence evidence = Evidence.create();
        ExternalServer first = null;
        ExternalServer second = null;
        boolean clientConnected = false;

        try {
            first = ExternalServer.start(1);
            evidence.firstServerProcessStarted = true;
            evidence.serverDirectory = first.directory.toAbsolutePath().toString();
            first.waitUntilReady(context);
            evidence.firstServerReady = true;
            evidence.firstWorldName = first.worldName;
            evidence.firstWorldSeed = first.worldSeed();
            evidence.firstFixturePreparation = first.prepareFixture();
            evidence.productionServerModLoaded = true;

            connectClient(context);
            clientConnected = true;
            evidence.realTcpConnectionStarted = true;
            evidence.clientReachedUsableWorld = true;
            evidence.clientPlayerObserved = clientName(context).equals(evidence.testUsername);
            require(evidence.clientPlayerObserved, "client connected with an unexpected player name");
            context.waitTicks(20);
            first.execute("tp " + evidence.testUsername + " 8.5 80 8.5");
            context.waitTicks(30);
            Snapshot login = record(first, evidence, "login");
            evidence.serverPlayerObserved = value(login, "players") > 0;
            require(evidence.serverPlayerObserved, "server diagnostics did not observe the test player");
            evidence.serverPlayerName = evidence.testUsername;
            evidence.loginServerPosition = first.serverPosition();

            Snapshot sameOwnerBefore = record(first, evidence, "same-owner-before");
            TestInput input = context.getInput();
            input.lookAt(new BlockPos(24, 80, 8));
            input.holdKey(options -> options.keyUp);
            double maxSameOwnerX = clientPosition(context)[0];
            try {
                for (int tick = 0; tick < 240; tick++) {
                    context.waitTick();
                    maxSameOwnerX = Math.max(maxSameOwnerX, clientPosition(context)[0]);
                    if (maxSameOwnerX >= 23.0D) break;
                }
            } finally {
                input.releaseKey(options -> options.keyUp);
            }
            context.waitTicks(10);
            Snapshot sameOwnerAfter = record(first, evidence, "same-owner-after");
            evidence.sameOwnerMovementObserved = delta(sameOwnerBefore, sameOwnerAfter, "move.in") > 0
                && delta(sameOwnerBefore, sameOwnerAfter, "move.accept") > 0
                && delta(sameOwnerBefore, sameOwnerAfter, "move.owner") > 0
                && delta(sameOwnerBefore, sameOwnerAfter, "move.replay") > 0
                && delta(sameOwnerBefore, sameOwnerAfter, "move.effect") > 0
                && delta(sameOwnerBefore, sameOwnerAfter, "move.reconcile") > 0
                && delta(sameOwnerBefore, sameOwnerAfter, "move.sameEpoch") > 0;
            require(evidence.sameOwnerMovementObserved,
                "same-owner movement did not produce the complete ingress/admission/replay/effect path: " + sameOwnerAfter.raw);
            evidence.sameOwnerClientMaxX = Double.toString(maxSameOwnerX);
            evidence.sameOwnerServerPosition = first.serverPosition();
            double sameOwnerServerX = parsePosition(evidence.sameOwnerServerPosition)[0];
            require(sameOwnerServerX >= 20.0D && sameOwnerServerX < 80.0D,
                "same-owner movement did not settle inside owner A: " + evidence.sameOwnerServerPosition);

            first.execute("tp " + evidence.testUsername + " 72.5 80 8.5");
            context.waitTicks(30);
            Snapshot boundaryBefore = record(first, evidence, "boundary-before");
            input.lookAt(new BlockPos(82, 80, 8));
            input.holdKey(options -> options.keyUp);
            try {
                context.waitTicks(150);
            } finally {
                input.releaseKey(options -> options.keyUp);
            }
            context.waitTicks(15);
            Snapshot boundaryAfter = record(first, evidence, "boundary-after");
            evidence.boundaryClientPosition = clientPositionJson(context);
            evidence.boundaryServerPosition = first.serverPosition();
            evidence.boundaryRejectionObserved = delta(boundaryBefore, boundaryAfter, "move.reject") > 0;
            require(evidence.boundaryRejectionObserved,
                "boundary movement produced no footprint rejection: " + boundaryAfter.raw);
            require(value(boundaryAfter, "players") > 0, "boundary rejection disconnected the client");
            double boundaryServerX = parsePosition(evidence.boundaryServerPosition)[0];
            require(boundaryServerX < 80.0D,
                "boundary movement crossed into owner B instead of remaining fail-closed in owner A: "
                    + evidence.boundaryServerPosition);
            evidence.boundaryMovementStayedInOwnerA = true;

            first.execute("tp " + evidence.testUsername + " 8.5 80 8.5");
            first.execute("kill @e[type=minecraft:armor_stand]");
            context.waitTicks(30);
            record(first, evidence, "interaction-before-summon");
            first.execute("summon minecraft:armor_stand 10.5 80 8.5 {NoGravity:1b,Tags:[\""
                + SAME_OWNER_TARGET_TAG + "\"]}");
            context.waitTicks(25);
            Snapshot interactionPublished = record(first, evidence, "interaction-target-published");
            evidence.interactionTargetClientEntityCount = Integer.toString(clientEntityCount(context));
            double[] sameTarget = parsePosition(first.targetPosition(SAME_OWNER_TARGET_TAG));
            require(Math.abs(sameTarget[0] - 10.5D) < 0.01D && Math.abs(sameTarget[2] - 8.5D) < 0.01D,
                "the tagged same-owner target was not present at the interaction position");

            input.lookAt(new BlockPos(10, 80, 8));
            input.pressKey(options -> options.keyUse);
            context.waitTicks(5);
            input.pressMouse(1);
            context.waitTicks(20);
            Snapshot interactionAfter = record(first, evidence, "interaction-after");
            // A hit on this actual interaction proves index publication. Aggregate entity counts
            // can fall concurrently as unrelated naturally generated mobs unload or despawn.
            evidence.interactionLifecycleObserved = delta(interactionPublished, interactionAfter, "interact.hit") > 0;
            evidence.sameRegionInteractionObserved = delta(interactionPublished, interactionAfter, "interact.in") > 0
                && delta(interactionPublished, interactionAfter, "interact.hit") > 0
                && delta(interactionPublished, interactionAfter, "interact.ownerAdmission") > 0
                && delta(interactionPublished, interactionAfter, "interact.ownerExecution") > 0
                && delta(interactionPublished, interactionAfter, "interact.replay") > 0
                && delta(interactionPublished, interactionAfter, "interact.lookup") > 0
                && delta(interactionPublished, interactionAfter, "interact.effect") > 0;
            require(evidence.sameRegionInteractionObserved,
                "same-region interaction did not produce the complete indexed admission/replay path: " + interactionAfter.raw);

            first.execute("summon minecraft:armor_stand 80.5 80 8.5 {NoGravity:1b,Tags:[\""
                + CROSS_OWNER_TARGET_TAG + "\"]}");
            context.waitTicks(25);
            first.execute("tp " + evidence.testUsername + " 78.5 80 8.5");
            context.waitTicks(30);
            evidence.crossOwnerPlayerPosition = first.serverPosition();
            evidence.crossOwnerTargetPosition = first.targetPosition(CROSS_OWNER_TARGET_TAG);
            double[] crossPlayer = parsePosition(evidence.crossOwnerPlayerPosition);
            double[] crossTarget = parsePosition(evidence.crossOwnerTargetPosition);
            int crossPlayerChunkX = chunkCoordinate(crossPlayer[0]);
            int crossTargetChunkX = chunkCoordinate(crossTarget[0]);
            evidence.crossOwnerPlayerChunk = "(" + crossPlayerChunkX + "," + chunkCoordinate(crossPlayer[2]) + ")";
            evidence.crossOwnerTargetChunk = "(" + crossTargetChunkX + "," + chunkCoordinate(crossTarget[2]) + ")";
            evidence.crossOwnerPlayerRoute = first.execute("phoenium route \"minecraft:overworld\" "
                + crossPlayerChunkX + " " + chunkCoordinate(crossPlayer[2])).trim();
            evidence.crossOwnerTargetRoute = first.execute("phoenium route \"minecraft:overworld\" "
                + crossTargetChunkX + " " + chunkCoordinate(crossTarget[2])).trim();
            require(evidence.crossOwnerPlayerRoute.contains("route=RESOLVED")
                    && evidence.crossOwnerPlayerRoute.contains("region=stage3-fixture-a"),
                "cross-owner player did not resolve to fixture Region A: " + evidence.crossOwnerPlayerRoute);
            require(evidence.crossOwnerTargetRoute.contains("route=RESOLVED")
                    && evidence.crossOwnerTargetRoute.contains("region=stage3-fixture-b"),
                "cross-owner target did not resolve to fixture Region B: " + evidence.crossOwnerTargetRoute);
            require(crossPlayerChunkX == 4 && crossTargetChunkX == 5,
                "cross-owner fixture did not straddle the intended chunk boundary: "
                    + evidence.crossOwnerPlayerChunk + " -> " + evidence.crossOwnerTargetChunk);
            double crossDistance = distance(crossPlayer, crossTarget);
            evidence.crossOwnerDistance = Double.toString(crossDistance);
            require(crossDistance > 0.0D && crossDistance <= 3.0D,
                "cross-owner fixture is outside normal vanilla interaction reach: " + crossDistance);
            Snapshot crossOwnerBefore = record(first, evidence, "cross-owner-before");
            input.lookAt(new BlockPos(80, 80, 8));
            input.pressKey(options -> options.keyUse);
            context.waitTicks(5);
            input.pressMouse(1);
            context.waitTicks(20);
            Snapshot crossOwnerAfter = record(first, evidence, "cross-owner-after");
            long crossIngress = delta(crossOwnerBefore, crossOwnerAfter, "interact.in");
            long crossReject = delta(crossOwnerBefore, crossOwnerAfter, "interact.crossReject");
            evidence.crossOwnerInteractionAttempted = true;
            evidence.crossOwnerInteractionPacketObserved = crossIngress > 0;
            evidence.crossOwnerInteractionRejected = crossReject > 0;
            evidence.crossOwnerInteractionReason = crossIngress == 0
                ? "NOT OBSERVED AT SERVER INGRESS: normal client input emitted no packet for the legal near-boundary attempt; no synthetic packet was introduced."
                : "REJECTED BEFORE VANILLA LOOKUP: target owner B differed from player owner A.";
            require(delta(crossOwnerBefore, crossOwnerAfter, "interact.ownerExecution") == 0
                    && delta(crossOwnerBefore, crossOwnerAfter, "interact.replay") == 0
                    && delta(crossOwnerBefore, crossOwnerAfter, "interact.lookup") == 0,
                "cross-owner interaction reached foreign/vanilla execution: " + crossOwnerAfter.raw);
            if (crossIngress > 0) {
                require(crossReject > 0, "a cross-owner interaction packet was admitted without a cross-owner rejection");
            }
            evidence.crossOwnerInteractionNoForeignMutation = true;

            first.execute("kill @e[type=minecraft:armor_stand]");
            context.waitTicks(25);
            Snapshot afterTargetRemoval = record(first, evidence, "interaction-after-removal");
            evidence.interactionTargetRemovalResponse = first.execute(
                "data get entity @e[type=minecraft:armor_stand,tag=" + SAME_OWNER_TARGET_TAG + ",limit=1] Pos");
            evidence.interactionRemovalObserved = evidence.interactionTargetRemovalResponse.contains("No entity was found");
            require(evidence.interactionRemovalObserved,
                "removed same-owner target was still addressable: " + evidence.interactionTargetRemovalResponse
                    + " diagnostics=" + afterTargetRemoval.raw);

            disconnectClient(context);
            clientConnected = false;
            evidence.clientDisconnectedCleanly = true;
            context.waitTicks(20);
            Snapshot afterDisconnect = record(first, evidence, "after-disconnect");
            evidence.disconnectProjectionCleared = value(afterDisconnect, "players") == 0
                && evidence.interactionTargetRemovalResponse.contains("No entity was found");
            require(evidence.disconnectProjectionCleared,
                "disconnect left a player or controlled target binding behind: " + afterDisconnect.raw);

            evidence.firstServerStoppedCleanly = first.stopNormally(context);
            require(evidence.firstServerStoppedCleanly, "first external server lifecycle did not stop normally");

            second = ExternalServer.start(2);
            evidence.secondServerProcessStarted = true;
            second.waitUntilReady(context);
            evidence.secondServerReady = true;
            evidence.secondWorldName = second.worldName;
            evidence.secondWorldSeed = second.worldSeed();
            evidence.secondFixturePreparation = second.prepareFixture();
            connectClient(context);
            clientConnected = true;
            context.waitTicks(25);
            Snapshot secondLogin = record(second, evidence, "second-lifecycle-login");
            evidence.secondLifecycleReinitialized = value(secondLogin, "players") > 0
                && value(secondLogin, "lifecycleStarts") == 1
                && value(secondLogin, "ownershipViolations") == 0
                && value(secondLogin, "unleased") == 0;
            require(evidence.secondLifecycleReinitialized,
                "second external lifecycle did not initialize cleanly: " + secondLogin.raw);
            disconnectClient(context);
            clientConnected = false;
            evidence.secondClientDisconnectedCleanly = true;
            context.waitTicks(15);
            Snapshot secondAfterDisconnect = record(second, evidence, "second-lifecycle-after-disconnect");
            evidence.secondServerStoppedCleanly = second.stopNormally(context);
            require(evidence.secondServerStoppedCleanly, "second external server lifecycle did not stop normally");
            evidence.secondDisconnectProjectionCleared = value(secondAfterDisconnect, "players") == 0;
            require(evidence.secondDisconnectProjectionCleared,
                "second lifecycle left a player binding behind: " + secondAfterDisconnect.raw);
            evidence.clientTerminatedCleanly = true;
        } catch (Throwable failure) {
            evidence.failure = failure.toString();
            if (failure instanceof Error error) throw error;
            throw new AssertionError("Stage 3 real-client evidence failed", failure);
        } finally {
            if (clientConnected) {
                try {
                    disconnectClient(context);
                    evidence.clientDisconnectedCleanly = true;
                } catch (Throwable cleanupFailure) {
                    evidence.cleanupFailure = cleanupFailure.toString();
                }
            }
            if (second != null) second.stopQuietly(context);
            if (first != null) first.stopQuietly(context);
            if (first != null) evidence.firstServerLog = first.latestLog.toAbsolutePath().toString();
            if (second != null) evidence.secondServerLog = second.latestLog.toAbsolutePath().toString();
            writeEvidence(evidence);
        }
    }

    /**
     * CHUNK-RUNTIME: drive the real production server's ordinary chunk request path without
     * changing the path or waiting on its future.  The client GameTest process supplies the
     * bounded clock; the request itself is issued through the test-only RCON command.
     */
    private static void runChunkTrace(ClientGameTestContext context) {
        ChunkTraceEvidence evidence = ChunkTraceEvidence.create();
        ExternalServer server = null;
        try {
            String firstWorld = "STAGE3-CHUNK-GEN-1-" + System.currentTimeMillis();
            server = ExternalServer.startChunkTrace(1, firstWorld, tracePath("generation-1"));
            evidence.firstServerProcessStarted = true;
            evidence.productionServerModLoaded = true;
            evidence.serverDirectory = server.directory.toAbsolutePath().toString();
            server.waitUntilReady(context);
            evidence.firstServerReady = true;
            evidence.firstWorldName = firstWorld;
            evidence.firstWorldSeed = server.worldSeed();
            PendingChunkTrace firstPending = requestChunkTrace(server, context, "fresh-generation-1");
            require(server.stopNormally(context), "first fresh-generation server did not stop normally");
            evidence.firstServerStoppedCleanly = true;
            evidence.runs.add(TraceRun.read(firstPending));
            server = null;
            context.waitTicks(10);

            String secondWorld = "STAGE3-CHUNK-GEN-2-" + System.currentTimeMillis();
            server = ExternalServer.startChunkTrace(2, secondWorld, tracePath("generation-2"));
            evidence.secondServerProcessStarted = true;
            server.waitUntilReady(context);
            evidence.secondServerReady = true;
            evidence.secondWorldName = secondWorld;
            evidence.secondWorldSeed = server.worldSeed();
            PendingChunkTrace secondPending = requestChunkTrace(server, context, "fresh-generation-2");
            require(server.stopNormally(context), "second fresh-generation server did not stop normally");
            evidence.secondServerStoppedCleanly = true;
            evidence.runs.add(TraceRun.read(secondPending));
            server = null;
            context.waitTicks(10);

            // This is the deliberately persisted evidence-world exception described in the
            // Stage 3 handoff: the first fresh world is restarted, not copied or fabricated.
            server = ExternalServer.startChunkTrace(3, firstWorld, tracePath("persisted-reload"));
            evidence.reloadServerProcessStarted = true;
            server.waitUntilReady(context);
            evidence.reloadServerReady = true;
            evidence.reloadWorldName = firstWorld;
            evidence.reloadWorldSeed = server.worldSeed();
            PendingChunkTrace reloadPending = requestChunkTrace(server, context, "existing-chunk-reload");
            require(server.stopNormally(context), "persisted reload server did not stop normally");
            evidence.reloadServerStoppedCleanly = true;
            evidence.runs.add(TraceRun.read(reloadPending));
            server = null;

            assertTraceOrdering(evidence.runs);
            evidence.semanticOrderingStable = true;
            evidence.success = true;
        } catch (Throwable failure) {
            evidence.failure = failure.toString();
            if (failure instanceof Error error) throw error;
            throw new AssertionError("Stage 3 chunk runtime trace failed", failure);
        } finally {
            if (server != null) server.stopQuietly(context);
            evidence.firstServerLog = evidence.runs.stream().findFirst()
                .map(run -> run.serverLog).orElse("unknown");
            evidence.secondServerLog = evidence.runs.stream().skip(1).findFirst()
                .map(run -> run.serverLog).orElse("unknown");
            evidence.reloadServerLog = evidence.runs.stream().skip(2).findFirst()
                .map(run -> run.serverLog).orElse("unknown");
            writeChunkTraceEvidence(evidence);
        }
    }

    private static PendingChunkTrace requestChunkTrace(ExternalServer server,
                                                       ClientGameTestContext context,
                                                       String label) throws IOException {
        String initial = server.execute("phoenium chunktrace status " + CHUNK_TRACE_X + " " + CHUNK_TRACE_Z);
        require(initial.contains("selected=true") && initial.contains("futureDone=false"),
            "target chunk was not initially absent/unloaded for " + label + ": " + initial);
        String request = server.execute("phoenium chunktrace load " + CHUNK_TRACE_X + " " + CHUNK_TRACE_Z);
        require(request.contains("request=QUEUED"), "chunk trace request was not accepted: " + request);
        String status = "";
        boolean complete = false;
        for (int tick = 0; tick < CHUNK_TRACE_TIMEOUT_TICKS; tick++) {
            require(server.isAlive(), server.serverFailure("external server exited during " + label));
            if (tick % 5 == 0) {
                status = server.execute("phoenium chunktrace status " + CHUNK_TRACE_X + " " + CHUNK_TRACE_Z);
                if (status.contains("futureDone=true") && status.contains("futureSuccess=true")
                        && status.contains("vanillaPath=observed-future-and-trace-only")) {
                    complete = true;
                    break;
                }
            }
            context.waitTick();
        }
        require(complete, "chunk trace did not reach a successful vanilla future result for " + label + ": " + status);
        context.waitTicks(10);
        return new PendingChunkTrace(label, server.worldName, server.worldSeed(), server.traceOutput,
            server.latestLog, request, status);
    }

    /**
     * Stage 3's dedicated real-client readiness trace.  The external server receives no synthetic
     * chunk request: player tracking caused by the real TCP client is the demand source, and the
     * client-side cache is sampled only through its non-forcing lookup/update-set APIs.
     */
    private static void runChunkReadinessTrace(ClientGameTestContext context) {
        ReadinessEvidence evidence = ReadinessEvidence.create();
        ExternalServer server = null;
        boolean clientConnected = false;
        try {
            for (int lifecycle = 1; lifecycle <= 2; lifecycle++) {
                String worldName = "STAGE3-CHUNK-READINESS-" + lifecycle + "-" + System.currentTimeMillis();
                String label = "fresh-generation-" + lifecycle;
                Path traceOutput = readinessTracePath(label);
                server = ExternalServer.startChunkReadinessTrace(lifecycle, worldName, traceOutput);
                evidence.serverDirectory = server.directory.toAbsolutePath().toString();
                evidence.productionServerModLoaded = true;
                if (lifecycle == 1) evidence.firstServerProcessStarted = true;
                else evidence.secondServerProcessStarted = true;

                server.waitUntilReady(context);
                if (lifecycle == 1) evidence.firstServerReady = true;
                else evidence.secondServerReady = true;
                String seed = server.worldSeed();
                String setup = server.prepareReadinessSetup();
                String route = server.execute("phoenium route \"minecraft:overworld\" "
                    + CHUNK_READINESS_X + " " + CHUNK_READINESS_Z);
                require(route.contains("route=RESOLVED") && route.contains("region=stage3-fixture-a"),
                    "readiness target did not resolve to same-owner fixture A: " + route);
                String initialInspect = server.execute("phoenium chunktrace inspect "
                    + CHUNK_READINESS_X + " " + CHUNK_READINESS_Z);
                require(initialInspect.contains("getChunkNow=null")
                        && initialInspect.contains("hasChunk=false"),
                    "readiness target already had a loaded server chunk before client demand: " + initialInspect);

                connectClient(context);
                clientConnected = true;
                evidence.realTcpConnectionStarted = true;
                context.waitTicks(20);
                ClientChunkObservation initialClient = clientChunkObservation(context);
                require(!initialClient.targetLoaded,
                    "readiness target was already present in the client before teleport: " + initialClient);

                String gamemode = server.execute("gamemode spectator " + TEST_USERNAME);
                require(gamemode != null && !gamemode.isBlank() && !gamemode.contains("Unknown"),
                    "could not stabilize the real client in spectator mode: " + gamemode);
                setup += " | gamemode=" + gamemode.trim();
                double targetBlockX = CHUNK_READINESS_X * 16.0D + 8.5D;
                double targetBlockZ = CHUNK_READINESS_Z * 16.0D + 8.5D;
                String targetTeleport = server.execute("tp " + TEST_USERNAME + " " + targetBlockX
                    + " 320 " + targetBlockZ);
                waitForClientInChunk(context, CHUNK_READINESS_X, CHUNK_READINESS_Z,
                    "target teleport " + label);
                ReadinessDemandObservation demand = observeDemandUntilReceipt(server, context);
                require(demand.clientTargetLoaded,
                    "client did not receive exact target chunk through ClientChunkCache: " + demand);
                require(demand.lastServerStatus.contains("sendReadyObserved=true"),
                    "server did not observe send-ready before client receipt: " + demand.lastServerStatus);

                String leaveTeleport = server.execute("tp " + TEST_USERNAME + " 8.5 80 8.5");
                waitForClientInChunk(context, 0, 0, "leave teleport " + label);
                ReadinessLeaveObservation leave = observeLeaveUntilServerRemoval(server, context);
                String afterLeaveInspect = server.execute("phoenium chunktrace inspect "
                    + CHUNK_READINESS_X + " " + CHUNK_READINESS_Z);
                require(leave.clientTargetRemoved,
                    "client did not remove the target chunk after leaving its tracking view: " + leave);
                require(leave.serverDemandRemoved,
                    "server did not observe tracking-demand removal: " + leave.lastServerStatus);

                ReadinessPendingTrace pending = new ReadinessPendingTrace(label, worldName, seed,
                    traceOutput, server.latestLog, route, setup, initialInspect, initialClient,
                    targetTeleport, demand, leaveTeleport, leave, afterLeaveInspect);
                disconnectClient(context);
                clientConnected = false;
                if (lifecycle == 1) evidence.firstClientDisconnectedCleanly = true;
                else evidence.secondClientDisconnectedCleanly = true;
                require(server.stopNormally(context), label + " server did not stop normally");
                if (lifecycle == 1) evidence.firstServerStoppedCleanly = true;
                else evidence.secondServerStoppedCleanly = true;
                evidence.runs.add(ReadinessRun.read(pending));
                server = null;
                context.waitTicks(10);
            }

            assertReadinessOrdering(evidence.runs);
            evidence.semanticOrderingStable = true;
            evidence.success = true;
        } catch (Throwable failure) {
            evidence.failure = failure.toString();
            if (failure instanceof Error error) throw error;
            throw new AssertionError("Stage 3 chunk readiness trace failed", failure);
        } finally {
            if (clientConnected) {
                try {
                    disconnectClient(context);
                } catch (Throwable cleanupFailure) {
                    evidence.cleanupFailure = cleanupFailure.toString();
                }
            }
            if (server != null) server.stopQuietly(context);
            writeChunkReadinessEvidence(evidence);
        }
    }

    private static ReadinessDemandObservation observeDemandUntilReceipt(ExternalServer server,
                                                                          ClientGameTestContext context)
            throws IOException {
        boolean clientLoaded = false;
        boolean clientAdded = false;
        int loadedTick = -1;
        int serverReadyTick = -1;
        String status = "";
        ClientChunkObservation latestClient = clientChunkObservation(context);
        for (int tick = 0; tick < CHUNK_READINESS_TIMEOUT_TICKS; tick++) {
            require(server.isAlive(), server.serverFailure("external server exited during readiness demand"));
            latestClient = clientChunkObservation(context);
            clientLoaded |= latestClient.targetLoaded;
            clientAdded |= latestClient.targetAddedInUpdateSet;
            if (clientLoaded && loadedTick < 0) loadedTick = tick;
            if (tick % 5 == 0) {
                status = server.execute("phoenium chunktrace status " + CHUNK_READINESS_X + " " + CHUNK_READINESS_Z);
                if (serverReadyTick < 0 && status.contains("sendReadyObserved=true")) serverReadyTick = tick;
                if (clientLoaded && status.contains("sendReadyObserved=true")
                        && status.contains("startTickingObserved=true")) {
                    return new ReadinessDemandObservation(true, clientAdded, loadedTick, serverReadyTick,
                        status, latestClient);
                }
            }
            context.waitTick();
        }
        return new ReadinessDemandObservation(clientLoaded, clientAdded, loadedTick, serverReadyTick,
            status, latestClient);
    }

    private static ReadinessLeaveObservation observeLeaveUntilServerRemoval(ExternalServer server,
                                                                             ClientGameTestContext context)
            throws IOException {
        boolean clientRemoved = false;
        boolean clientRemovedSet = false;
        boolean demandRemoved = false;
        boolean demoted = false;
        boolean unloaded = false;
        int removedTick = -1;
        int demandRemovedTick = -1;
        int demotedTick = -1;
        int unloadedTick = -1;
        String status = "";
        ClientChunkObservation latestClient = clientChunkObservation(context);
        for (int tick = 0; tick < CHUNK_READINESS_LEAVE_TIMEOUT_TICKS; tick++) {
            require(server.isAlive(), server.serverFailure("external server exited during readiness leave"));
            latestClient = clientChunkObservation(context);
            clientRemoved |= !latestClient.targetLoaded;
            clientRemovedSet |= latestClient.targetRemovedInUpdateSet;
            if (clientRemoved && removedTick < 0) removedTick = tick;
            if (tick % 5 == 0) {
                status = server.execute("phoenium chunktrace status " + CHUNK_READINESS_X + " " + CHUNK_READINESS_Z);
                demandRemoved = status.contains("demandRemovedObserved=true");
                demoted = status.contains("demotionObserved=true");
                unloaded = status.contains("unloadObserved=true");
                if (demandRemoved && demandRemovedTick < 0) demandRemovedTick = tick;
                if (demoted && demotedTick < 0) demotedTick = tick;
                if (unloaded && unloadedTick < 0) unloadedTick = tick;
                if (clientRemoved && demandRemoved && (demoted || unloaded)) {
                    return new ReadinessLeaveObservation(true, clientRemovedSet, demandRemoved, demoted,
                        unloaded, removedTick, demandRemovedTick, demotedTick, unloadedTick, status, latestClient);
                }
            }
            context.waitTick();
        }
        return new ReadinessLeaveObservation(clientRemoved, clientRemovedSet, demandRemoved, demoted, unloaded,
            removedTick, demandRemovedTick, demotedTick, unloadedTick, status, latestClient);
    }

    private static void assertReadinessOrdering(List<ReadinessRun> runs) {
        require(runs.size() == 2, "expected two fresh-world readiness traces");
        for (ReadinessRun run : runs) {
            TraceRun trace = run.trace;
            require(trace.eventCount > 0, "readiness trace has no selected events: " + run.label);
            require(trace.stageCounts.containsKey("CHUNK_MAP_UPDATE_CHUNK_SCHEDULING_ENTER")
                    || trace.stageCounts.containsKey("CHUNK_MAP_APPLY_TRACKING_VIEW_ADD"),
                "readiness trace missed the client-demand boundary: " + run.label);
            require(trace.stageCounts.containsKey("CHUNK_MAP_PREPARE_ACCESSIBLE_CHUNK_ENTER"),
                "readiness trace missed FULL accessibility preparation: " + run.label);
            require(trace.stageCounts.containsKey("CHUNK_MAP_PREPARE_TICKING_CHUNK_ENTER"),
                "readiness trace missed ticking preparation: " + run.label);
            require(trace.stageCounts.containsKey("SERVER_LEVEL_START_TICKING_CHUNK_ENTER"),
                "readiness trace missed ServerLevel.startTickingChunk: " + run.label);
            require(trace.stageCounts.containsKey("CHUNK_MAP_ON_CHUNK_READY_TO_SEND"),
                "readiness trace missed ChunkMap.onChunkReadyToSend: " + run.label);
            require(run.clientTargetLoaded,
                "readiness trace missed exact client cache receipt evidence: " + run.label);
            require(run.clientTargetRemoved,
                "readiness trace missed exact client cache removal evidence: " + run.label);
            require(run.serverDemandObserved, "readiness trace missed tracking demand removal: " + run.label);
            assertPair(trace, "CHUNK_MAP_PREPARE_ACCESSIBLE_CHUNK_ENTER",
                "CHUNK_MAP_PREPARE_TICKING_CHUNK_ENTER");
            assertPair(trace, "CHUNK_MAP_PREPARE_TICKING_CHUNK_ENTER",
                "SERVER_LEVEL_START_TICKING_CHUNK_ENTER");
            assertPair(trace, "SERVER_LEVEL_START_TICKING_CHUNK_ENTER",
                "CHUNK_MAP_ON_CHUNK_READY_TO_SEND");
            assertPair(trace, "CHUNK_MAP_ON_CHUNK_READY_TO_SEND",
                "CHUNK_MAP_DROP_CHUNK_TO_CLIENT");
        }
        String[][] stablePairs = {
            {"CHUNK_MAP_PREPARE_ACCESSIBLE_CHUNK_ENTER", "CHUNK_MAP_PREPARE_TICKING_CHUNK_ENTER"},
            {"CHUNK_MAP_PREPARE_TICKING_CHUNK_ENTER", "SERVER_LEVEL_START_TICKING_CHUNK_ENTER"},
            {"SERVER_LEVEL_START_TICKING_CHUNK_ENTER", "CHUNK_MAP_ON_CHUNK_READY_TO_SEND"},
            {"CHUNK_MAP_ON_CHUNK_READY_TO_SEND", "CHUNK_MAP_DROP_CHUNK_TO_CLIENT"}
        };
        for (String[] pair : stablePairs) {
            int first = runs.get(0).trace.indexOf(pair[0]);
            int second = runs.get(0).trace.indexOf(pair[1]);
            int firstAgain = runs.get(1).trace.indexOf(pair[0]);
            int secondAgain = runs.get(1).trace.indexOf(pair[1]);
            require(first >= 0 && second >= 0 && firstAgain >= 0 && secondAgain >= 0,
                "readiness stable-order pair was absent: " + pair[0] + " -> " + pair[1]);
            require((first < second) == (firstAgain < secondAgain),
                "readiness semantic order was unstable for " + pair[0] + " -> " + pair[1]);
        }
    }

    private static Path readinessTracePath(String label) {
        Path summary = Path.of(System.getProperty("phoenium.client.gametest.chunkReadinessEvidence",
            "build/client-gametest/evidence/STAGE3-CHUNK-READINESS.json"));
        return summary.resolveSibling("STAGE3-CHUNK-READINESS-" + label + ".json").toAbsolutePath();
    }

    private static void writeChunkReadinessEvidence(ReadinessEvidence evidence) {
        Path path = Path.of(System.getProperty("phoenium.client.gametest.evidence",
            "build/client-gametest/evidence/STAGE3-CHUNK-READINESS.json"));
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, evidence.toJson(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(path.resolveSibling("STAGE3-CHUNK-READINESS-world-seeds.txt"),
                evidence.worldSeedText(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new AssertionError("Unable to write chunk readiness evidence to " + path, exception);
        }
    }

    private static final class ReadinessPendingTrace {
        private final String label;
        private final String worldName;
        private final String worldSeed;
        private final Path traceOutput;
        private final Path latestLog;
        private final String routeResponse;
        private final String setupResponse;
        private final String initialInspect;
        private final ClientChunkObservation initialClient;
        private final String targetTeleport;
        private final ReadinessDemandObservation demand;
        private final String leaveTeleport;
        private final ReadinessLeaveObservation leave;
        private final String afterLeaveInspect;

        private ReadinessPendingTrace(String label, String worldName, String worldSeed, Path traceOutput,
                                      Path latestLog, String routeResponse, String setupResponse,
                                      String initialInspect, ClientChunkObservation initialClient,
                                      String targetTeleport, ReadinessDemandObservation demand,
                                      String leaveTeleport, ReadinessLeaveObservation leave,
                                      String afterLeaveInspect) {
            this.label = label;
            this.worldName = worldName;
            this.worldSeed = worldSeed;
            this.traceOutput = traceOutput;
            this.latestLog = latestLog;
            this.routeResponse = routeResponse;
            this.setupResponse = setupResponse;
            this.initialInspect = initialInspect;
            this.initialClient = initialClient;
            this.targetTeleport = targetTeleport;
            this.demand = demand;
            this.leaveTeleport = leaveTeleport;
            this.leave = leave;
            this.afterLeaveInspect = afterLeaveInspect;
        }
    }

    private record ClientChunkObservation(boolean clientLevelPresent, boolean targetLoaded,
                                          boolean targetAddedInUpdateSet, boolean targetRemovedInUpdateSet,
                                          int loadedChunks, String chunkIdentity) { }

    private record ReadinessDemandObservation(boolean clientTargetLoaded, boolean clientAddedInUpdateSet,
                                              int clientLoadedTick, int serverReadyTick,
                                              String lastServerStatus, ClientChunkObservation latestClient) { }

    private record ReadinessLeaveObservation(boolean clientTargetRemoved, boolean clientRemovedInUpdateSet,
                                             boolean serverDemandRemoved, boolean serverDemoted,
                                             boolean serverUnloaded, int removedTick, int demandRemovedTick,
                                             int demotedTick, int unloadedTick, String lastServerStatus,
                                             ClientChunkObservation latestClient) { }

    private static final class ReadinessRun {
        private final String label;
        private final String worldName;
        private final String worldSeed;
        private final TraceRun trace;
        private final String routeResponse;
        private final String setupResponse;
        private final String initialInspect;
        private final String initialClientObservation;
        private final String targetTeleport;
        private final String receiveStatus;
        private final String receiveClientObservation;
        private final String leaveTeleport;
        private final String leaveStatus;
        private final String leaveClientObservation;
        private final String afterLeaveInspect;
        private final boolean clientTargetLoaded;
        private final boolean clientAddedInUpdateSet;
        private final boolean clientTargetRemoved;
        private final boolean clientRemovedInUpdateSet;
        private final boolean serverDemandObserved;
        private final boolean serverSendReady;
        private final boolean serverDemoted;
        private final boolean serverUnloaded;
        private final int clientReceiveTick;
        private final int serverSendReadyTick;
        private final int clientRemovedTick;
        private final int demandRemovedTick;
        private final int demotedTick;
        private final int unloadedTick;
        private final boolean immediateSendBranchObserved;
        private final boolean delayedSendBranchObserved;

        private ReadinessRun(String label, String worldName, String worldSeed, TraceRun trace,
                             String routeResponse, String setupResponse, String initialInspect,
                             String initialClientObservation, String targetTeleport, String receiveStatus,
                             String receiveClientObservation, String leaveTeleport, String leaveStatus,
                             String leaveClientObservation, String afterLeaveInspect,
                             boolean clientTargetLoaded, boolean clientAddedInUpdateSet,
                             boolean clientTargetRemoved, boolean clientRemovedInUpdateSet,
                             boolean serverDemandObserved, boolean serverSendReady, boolean serverDemoted,
                             boolean serverUnloaded, int clientReceiveTick, int serverSendReadyTick,
                             int clientRemovedTick, int demandRemovedTick, int demotedTick, int unloadedTick,
                             boolean immediateSendBranchObserved, boolean delayedSendBranchObserved) {
            this.label = label;
            this.worldName = worldName;
            this.worldSeed = worldSeed;
            this.trace = trace;
            this.routeResponse = routeResponse;
            this.setupResponse = setupResponse;
            this.initialInspect = initialInspect;
            this.initialClientObservation = initialClientObservation;
            this.targetTeleport = targetTeleport;
            this.receiveStatus = receiveStatus;
            this.receiveClientObservation = receiveClientObservation;
            this.leaveTeleport = leaveTeleport;
            this.leaveStatus = leaveStatus;
            this.leaveClientObservation = leaveClientObservation;
            this.afterLeaveInspect = afterLeaveInspect;
            this.clientTargetLoaded = clientTargetLoaded;
            this.clientAddedInUpdateSet = clientAddedInUpdateSet;
            this.clientTargetRemoved = clientTargetRemoved;
            this.clientRemovedInUpdateSet = clientRemovedInUpdateSet;
            this.serverDemandObserved = serverDemandObserved;
            this.serverSendReady = serverSendReady;
            this.serverDemoted = serverDemoted;
            this.serverUnloaded = serverUnloaded;
            this.clientReceiveTick = clientReceiveTick;
            this.serverSendReadyTick = serverSendReadyTick;
            this.clientRemovedTick = clientRemovedTick;
            this.demandRemovedTick = demandRemovedTick;
            this.demotedTick = demotedTick;
            this.unloadedTick = unloadedTick;
            this.immediateSendBranchObserved = immediateSendBranchObserved;
            this.delayedSendBranchObserved = delayedSendBranchObserved;
        }

        private static ReadinessRun read(ReadinessPendingTrace pending) throws IOException {
            String raw = Files.readString(pending.traceOutput);
            TraceRun trace = TraceRun.read(new PendingChunkTrace(pending.label, pending.worldName,
                pending.worldSeed, pending.traceOutput, pending.latestLog, pending.targetTeleport,
                pending.demand.lastServerStatus));
            return new ReadinessRun(
                pending.label, pending.worldName, pending.worldSeed, trace,
                pending.routeResponse, pending.setupResponse, pending.initialInspect,
                pending.initialClient.toString(), pending.targetTeleport, pending.demand.lastServerStatus,
                pending.demand.latestClient.toString(), pending.leaveTeleport, pending.leave.lastServerStatus,
                pending.leave.latestClient.toString(), pending.afterLeaveInspect,
                pending.demand.clientTargetLoaded, pending.demand.clientAddedInUpdateSet,
                pending.leave.clientTargetRemoved, pending.leave.clientRemovedInUpdateSet,
                pending.leave.serverDemandRemoved, pending.demand.lastServerStatus.contains("sendReadyObserved=true"),
                pending.leave.serverDemoted, pending.leave.serverUnloaded,
                pending.demand.clientLoadedTick, pending.demand.serverReadyTick,
                pending.leave.removedTick, pending.leave.demandRemovedTick,
                pending.leave.demotedTick, pending.leave.unloadedTick,
                raw.contains("sendBranch=IMMEDIATE"), raw.contains("sendBranch=DELAYED"));
        }

        private void appendJson(StringBuilder json) {
            json.append("\n    {");
            objectField(json, "label", label, true);
            objectField(json, "worldName", worldName, true);
            objectField(json, "worldSeed", worldSeed, true);
            objectField(json, "tracePath", trace.tracePath, true);
            objectField(json, "serverLog", trace.serverLog, true);
            objectField(json, "routeResponse", routeResponse, true);
            objectField(json, "setupResponse", setupResponse, true);
            objectField(json, "initialInspect", initialInspect, true);
            objectField(json, "initialClientObservation", initialClientObservation, true);
            objectField(json, "targetTeleport", targetTeleport, true);
            objectField(json, "receiveStatus", receiveStatus, true);
            objectField(json, "receiveClientObservation", receiveClientObservation, true);
            objectField(json, "leaveTeleport", leaveTeleport, true);
            objectField(json, "leaveStatus", leaveStatus, true);
            objectField(json, "leaveClientObservation", leaveClientObservation, true);
            objectField(json, "afterLeaveInspect", afterLeaveInspect, true);
            objectField(json, "clientTargetLoaded", clientTargetLoaded, true);
            objectField(json, "clientAddedInUpdateSet", clientAddedInUpdateSet, true);
            objectField(json, "clientTargetRemoved", clientTargetRemoved, true);
            objectField(json, "clientRemovedInUpdateSet", clientRemovedInUpdateSet, true);
            objectField(json, "serverDemandObserved", serverDemandObserved, true);
            objectField(json, "serverSendReady", serverSendReady, true);
            objectField(json, "serverDemoted", serverDemoted, true);
            objectField(json, "serverUnloaded", serverUnloaded, true);
            objectField(json, "clientReceiveTick", clientReceiveTick, true);
            objectField(json, "serverSendReadyTick", serverSendReadyTick, true);
            objectField(json, "clientRemovedTick", clientRemovedTick, true);
            objectField(json, "demandRemovedTick", demandRemovedTick, true);
            objectField(json, "demotedTick", demotedTick, true);
            objectField(json, "unloadedTick", unloadedTick, true);
            objectField(json, "immediateSendBranchObserved", immediateSendBranchObserved, true);
            objectField(json, "delayedSendBranchObserved", delayedSendBranchObserved, true);
            objectField(json, "eventCount", trace.eventCount, true);
            objectField(json, "ownershipContextObserved", trace.ownershipContextObserved, true);
            objectField(json, "regionLeaseObserved", trace.regionLeaseObserved, true);
            objectField(json, "futureSuccessObserved", trace.futureSuccessObserved, true);
            objectField(json, "exceptionCount", trace.exceptionCount, true);
            objectField(json, "requestResponse", trace.requestResponse, true);
            objectField(json, "completionResponse", trace.completionResponse, true);
            objectArray(json, "stageOrder", trace.stageOrder, true);
            objectMap(json, "stageCounts", trace.stageCounts, true);
            objectArray(json, "correlationIds", trace.correlationIds, true);
            objectArray(json, "regions", trace.regions, true);
            objectArray(json, "threadClasses", trace.threadClasses, false);
            json.append("\n    }");
        }

        private static void objectField(StringBuilder json, String name, String value, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": ")
                .append(Stage3ClientTest.json(value));
            if (comma) json.append(",");
        }

        private static void objectField(StringBuilder json, String name, boolean value, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": ").append(value);
            if (comma) json.append(",");
        }

        private static void objectField(StringBuilder json, String name, int value, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": ").append(value);
            if (comma) json.append(",");
        }

        private static void objectArray(StringBuilder json, String name, List<String> values, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": [");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) json.append(", ");
                json.append(Stage3ClientTest.json(values.get(i)));
            }
            json.append("]");
            if (comma) json.append(",");
        }

        private static void objectMap(StringBuilder json, String name, Map<String, Integer> values, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": {");
            int index = 0;
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                if (index++ > 0) json.append(",");
                json.append("\n        ").append(Stage3ClientTest.json(entry.getKey()))
                    .append(": ").append(entry.getValue());
            }
            if (!values.isEmpty()) json.append("\n      ");
            json.append("}");
            if (comma) json.append(",");
        }
    }

    private static final class ReadinessEvidence {
        private final String minecraftVersion;
        private final String javaVersion;
        private final String fabricLoaderVersion;
        private final String fabricApiVersion;
        private final String loomVersion;
        private final String gradleVersion;
        private final String gradleTask;
        private final List<ReadinessRun> runs = new ArrayList<>();
        private boolean firstServerProcessStarted;
        private boolean firstServerReady;
        private boolean firstServerStoppedCleanly;
        private boolean firstClientDisconnectedCleanly;
        private boolean secondServerProcessStarted;
        private boolean secondServerReady;
        private boolean secondServerStoppedCleanly;
        private boolean secondClientDisconnectedCleanly;
        private boolean productionServerModLoaded;
        private boolean realTcpConnectionStarted;
        private boolean semanticOrderingStable;
        private boolean success;
        private String serverDirectory = "unknown";
        private String failure = "";
        private String cleanupFailure = "";

        private ReadinessEvidence(String minecraftVersion, String javaVersion, String fabricLoaderVersion,
                                  String fabricApiVersion, String loomVersion, String gradleVersion,
                                  String gradleTask) {
            this.minecraftVersion = minecraftVersion;
            this.javaVersion = javaVersion;
            this.fabricLoaderVersion = fabricLoaderVersion;
            this.fabricApiVersion = fabricApiVersion;
            this.loomVersion = loomVersion;
            this.gradleVersion = gradleVersion;
            this.gradleTask = gradleTask;
        }

        private static ReadinessEvidence create() {
            return new ReadinessEvidence(
                System.getProperty("phoenium.test.minecraftVersion", "unknown"),
                System.getProperty("phoenium.test.javaVersion", "unknown"),
                System.getProperty("phoenium.test.fabricLoaderVersion", "unknown"),
                System.getProperty("phoenium.test.fabricApiVersion", "unknown"),
                System.getProperty("phoenium.test.loomVersion", "unknown"),
                System.getProperty("phoenium.test.gradleVersion", "unknown"),
                System.getProperty("phoenium.test.gradleTask", "runStage3ChunkReadinessTrace"));
        }

        private String toJson() {
            StringBuilder json = new StringBuilder("{\n");
            field(json, "test", "CHUNK-READINESS");
            field(json, "success", success);
            field(json, "generatedAtUtc", Instant.now().toString());
            field(json, "minecraftVersion", minecraftVersion);
            field(json, "javaVersion", javaVersion);
            field(json, "fabricLoaderVersion", fabricLoaderVersion);
            field(json, "fabricApiVersion", fabricApiVersion);
            field(json, "loomVersion", loomVersion);
            field(json, "gradleVersion", gradleVersion);
            field(json, "gradleTask", gradleTask);
            field(json, "serverDirectory", serverDirectory);
            field(json, "targetWorld", "minecraft:overworld");
            field(json, "targetChunk", CHUNK_READINESS_X + "," + CHUNK_READINESS_Z);
            field(json, "targetOutsideSpawnArea", true);
            field(json, "targetRoute", "stage3-fixture-a");
            field(json, "requestedStatus", "FULL");
            field(json, "demandSource", "real TCP client player tracking after teleport");
            field(json, "clientReceiptMethod",
                "ClientChunkCache.getChunk(4,-4,FULL,false) exact-position match; addedLoadedChunks() sampled as transient auxiliary evidence");
            field(json, "clientRemovalMethod",
                "ClientChunkCache.getChunk(4,-4,FULL,false) null; removedLoadedChunks() sampled as transient auxiliary evidence");
            field(json, "requestIncarnationAvailable", false);
            field(json, "holderIdentityObserved", true);
            field(json, "holderIncarnationAvailable", false);
            field(json, "chunkPosAvailable", true);
            field(json, "topologyGenerationObserved", true);
            field(json, "regionRouteObserved", true);
            field(json, "originalFutureObserved", false);
            field(json, "realDedicatedServer", true);
            field(json, "productionServerModLoaded", productionServerModLoaded);
            field(json, "clientHarnessUsed", true);
            field(json, "realTcpConnectionStarted", realTcpConnectionStarted);
            field(json, "clientNetworkConnectionRequired", true);
            field(json, "microsoftAuthentication", false);
            field(json, "minecraftLauncher", false);
            field(json, "microsoftStoreLauncher", false);
            field(json, "guiAutomation", false);
            field(json, "authoritativeChunkOwnershipImplemented", false);
            field(json, "vanillaFutureReplaced", false);
            field(json, "futuresJoinedOrWrapped", false);
            field(json, "secondWorkerIntroduced", false);
            field(json, "parallelGameplay", false);
            field(json, "workers", 1);
            field(json, "rChunk01", "PARTIAL");
            field(json, "stage3", "PARTIAL");
            field(json, "stage4", "NOT_STARTED");
            field(json, "freshGenerationRuns", 2);
            field(json, "persistedReloadRuns", 0);
            field(json, "firstServerProcessStarted", firstServerProcessStarted);
            field(json, "firstServerReady", firstServerReady);
            field(json, "firstServerStoppedCleanly", firstServerStoppedCleanly);
            field(json, "firstClientDisconnectedCleanly", firstClientDisconnectedCleanly);
            field(json, "secondServerProcessStarted", secondServerProcessStarted);
            field(json, "secondServerReady", secondServerReady);
            field(json, "secondServerStoppedCleanly", secondServerStoppedCleanly);
            field(json, "secondClientDisconnectedCleanly", secondClientDisconnectedCleanly);
            field(json, "semanticOrderingStable", semanticOrderingStable);
            field(json, "allRunsClientReceiptObserved", allRunsHave(runs));
            field(json, "allRunsDemandRemovalObserved", allRunsDemandRemoved(runs));
            field(json, "allRunsDemotionOrUnloadObserved", allRunsDemotionOrUnload(runs));
            field(json, "immediateSendBranchObserved", runs.stream().anyMatch(run -> run.immediateSendBranchObserved));
            field(json, "delayedSendBranchObserved", runs.stream().anyMatch(run -> run.delayedSendBranchObserved));
            field(json, "sendBranch2", runs.stream().anyMatch(run -> run.delayedSendBranchObserved)
                ? "OBSERVED: delayed send-sync completion callback"
                : "NOT OBSERVED: ordinary fresh-world demand completed through the immediate send-sync branch");
            field(json, "distinctFreshWorldSeeds", distinctSeeds());
            field(json, "failure", failure);
            field(json, "cleanupFailure", cleanupFailure);
            fieldPrefix(json, "runs");
            json.append("[");
            for (int i = 0; i < runs.size(); i++) {
                if (i > 0) json.append(",");
                runs.get(i).appendJson(json);
            }
            if (!runs.isEmpty()) json.append("\n  ");
            json.append("]\n}\n");
            return json.toString();
        }

        private String worldSeedText() {
            StringBuilder text = new StringBuilder("Phoenium Stage 3 chunk readiness trace world record\n")
                .append("targetWorld=minecraft:overworld\n")
                .append("targetChunk=").append(CHUNK_READINESS_X).append(",").append(CHUNK_READINESS_Z).append("\n")
                .append("freshGenerationRuns=2\n")
                .append("persistedReloadRuns=0\n")
                .append("targetOutsideSpawnArea=true\n\n");
            for (ReadinessRun run : runs) {
                text.append("[").append(run.label).append("]\n")
                    .append("worldName=").append(run.worldName).append("\n")
                    .append("worldSeed=").append(run.worldSeed).append("\n")
                    .append("routeResponse=").append(run.routeResponse).append("\n")
                    .append("tracePath=").append(run.trace.tracePath).append("\n")
                    .append("stageOrder=").append(String.join(" -> ", run.trace.stageOrder)).append("\n")
                    .append("clientReceipt=").append(run.clientTargetLoaded).append("\n")
                    .append("clientRemoval=").append(run.clientTargetRemoved).append("\n")
                    .append("serverDemoted=").append(run.serverDemoted).append("\n")
                    .append("serverUnloaded=").append(run.serverUnloaded).append("\n\n");
            }
            return text.toString();
        }

        private boolean distinctSeeds() {
            return runs.size() == 2 && !runs.get(0).worldSeed.equals(runs.get(1).worldSeed);
        }

        private static boolean allRunsHave(List<ReadinessRun> runs) {
            return runs.size() == 2 && runs.stream().allMatch(run -> run.clientTargetLoaded);
        }

        private static boolean allRunsDemandRemoved(List<ReadinessRun> runs) {
            return runs.size() == 2 && runs.stream().allMatch(run -> run.serverDemandObserved);
        }

        private static boolean allRunsDemotionOrUnload(List<ReadinessRun> runs) {
            return runs.size() == 2 && runs.stream().allMatch(run -> run.serverDemoted || run.serverUnloaded);
        }

        private static void field(StringBuilder json, String name, String value) {
            fieldPrefix(json, name);
            json.append(Stage3ClientTest.json(value));
        }

        private static void field(StringBuilder json, String name, boolean value) {
            fieldPrefix(json, name);
            json.append(value);
        }

        private static void field(StringBuilder json, String name, int value) {
            fieldPrefix(json, name);
            json.append(value);
        }

        private static void fieldPrefix(StringBuilder json, String name) {
            if (json.length() > 2) json.append(",\n");
            json.append("  ").append(Stage3ClientTest.json(name)).append(": ");
        }
    }

    private static void assertTraceOrdering(List<TraceRun> runs) {
        require(runs.size() == 3, "expected two fresh-generation traces and one reload trace");
        for (TraceRun run : runs) {
            require(run.eventCount > 0, "trace has no selected events: " + run.label);
            require(run.stageCounts.containsKey("CHUNK_FUTURE_REQUEST"),
                "trace missed the future request stage: " + run.label);
            require(run.stageCounts.containsKey("CHUNK_FUTURE_RETURN"),
                "trace missed the future return stage: " + run.label);
            require(run.stageCounts.containsKey("CHUNK_FUTURE_COMPLETION"),
                "trace missed successful future completion: " + run.label);
            assertPair(run, "CHUNK_FUTURE_REQUEST", "CHUNK_FUTURE_RETURN");
            assertPair(run, "CHUNK_FUTURE_RETURN", "CHUNK_FUTURE_COMPLETION");
            assertPair(run, "CHUNK_MAP_SCHEDULE_CHUNK_LOAD_ENTER", "CHUNK_MAP_APPLICATION_ENTER");
            assertPair(run, "CHUNK_MAP_APPLICATION_ENTER", "CHUNK_MAP_APPLICATION_RETURN");
            assertPair(run, "GENERATION_HOLDER_APPLY_STEP_ENTER", "CHUNK_MAP_APPLICATION_ENTER");
            assertPair(run, "GENERATION_HOLDER_APPLY_STEP_ENTER", "GENERATION_HOLDER_APPLY_STEP_RETURN");
            assertPair(run, "CHUNK_STATUS_FULL_CONVERSION_ENTER", "CHUNK_STATUS_FULL_CONVERSION_RETURN");
            assertPair(run, "CHUNK_MAP_TICKING_CONVERSION_ENTER", "CHUNK_MAP_TICKING_CONVERSION_RETURN");
        }
        require(!runs.get(0).parsedChunkDataPresent && !runs.get(1).parsedChunkDataPresent,
            "fresh-generation traces unexpectedly read persisted chunk data");
        TraceRun reload = runs.get(2);
        require(reload.parsedChunkDataPresent, "persisted reload did not observe saved chunk data at application");
        require(reload.stageCounts.containsKey("SERIALIZABLE_DATA_READ_ENTER"),
            "persisted reload did not observe SerializableChunkData.read");
        require(reload.stageCounts.containsKey("SERIALIZABLE_DATA_READ_RETURN"),
            "persisted reload did not return from SerializableChunkData.read");
        assertPair(reload, "CHUNK_MAP_APPLICATION_ENTER", "SERIALIZABLE_DATA_READ_ENTER");
        assertPair(reload, "SERIALIZABLE_DATA_READ_ENTER", "SERIALIZABLE_DATA_READ_RETURN");
        assertPair(reload, "SERIALIZABLE_DATA_READ_RETURN", "CHUNK_MAP_APPLICATION_RETURN");

        String[][] stablePairs = {
            {"CHUNK_FUTURE_REQUEST", "CHUNK_FUTURE_RETURN"},
            {"CHUNK_MAP_SCHEDULE_CHUNK_LOAD_ENTER", "CHUNK_MAP_APPLICATION_ENTER"},
            {"GENERATION_HOLDER_APPLY_STEP_ENTER", "CHUNK_MAP_APPLICATION_ENTER"},
            {"GENERATION_HOLDER_COMPLETE_FUTURE_ENTER", "CHUNK_FUTURE_COMPLETION"}
        };
        for (String[] pair : stablePairs) {
            int first = runs.get(0).indexOf(pair[0]);
            int second = runs.get(0).indexOf(pair[1]);
            int firstAgain = runs.get(1).indexOf(pair[0]);
            int secondAgain = runs.get(1).indexOf(pair[1]);
            if (first >= 0 && second >= 0 && firstAgain >= 0 && secondAgain >= 0) {
                require((first < second) == (firstAgain < secondAgain),
                    "semantic order was unstable for " + pair[0] + " -> " + pair[1]);
            }
        }
    }

    private static void assertPair(TraceRun run, String before, String after) {
        int beforeIndex = run.indexOf(before);
        int afterIndex = run.indexOf(after);
        if (beforeIndex >= 0 && afterIndex >= 0) {
            require(beforeIndex < afterIndex,
                "unexpected stage order in " + run.label + ": " + before + " was not before " + after
                    + " in " + run.stageOrder);
        }
    }

    private static Path tracePath(String label) {
        Path summary = Path.of(System.getProperty("phoenium.client.gametest.chunkTraceEvidence",
            "build/client-gametest/evidence/STAGE3-CHUNK-RUNTIME.json"));
        return summary.resolveSibling("STAGE3-CHUNK-RUNTIME-" + label + ".json").toAbsolutePath();
    }

    private static void writeChunkTraceEvidence(ChunkTraceEvidence evidence) {
        Path path = Path.of(System.getProperty("phoenium.client.gametest.evidence",
            "build/client-gametest/evidence/STAGE3-CHUNK-RUNTIME.json"));
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, evidence.toJson(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(path.resolveSibling("STAGE3-CHUNK-RUNTIME-world-seeds.txt"),
                evidence.worldSeedText(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new AssertionError("Unable to write chunk runtime evidence to " + path, exception);
        }
    }

    private static final class PendingChunkTrace {
        private final String label;
        private final String worldName;
        private final String worldSeed;
        private final Path traceOutput;
        private final Path latestLog;
        private final String requestResponse;
        private final String completionResponse;

        private PendingChunkTrace(String label, String worldName, String worldSeed, Path traceOutput,
                                  Path latestLog, String requestResponse, String completionResponse) {
            this.label = label;
            this.worldName = worldName;
            this.worldSeed = worldSeed;
            this.traceOutput = traceOutput;
            this.latestLog = latestLog;
            this.requestResponse = requestResponse;
            this.completionResponse = completionResponse;
        }
    }

    private static final class TraceRun {
        private final String label;
        private final String worldName;
        private final String worldSeed;
        private final String tracePath;
        private final String serverLog;
        private final int eventCount;
        private final List<String> stageOrder;
        private final Map<String, Integer> stageCounts;
        private final List<String> correlationIds;
        private final List<String> regions;
        private final List<String> threadClasses;
        private final boolean ownershipContextObserved;
        private final boolean regionLeaseObserved;
        private final boolean futureSuccessObserved;
        private final boolean parsedChunkDataPresent;
        private final int exceptionCount;
        private final String requestResponse;
        private final String completionResponse;

        private TraceRun(String label, String worldName, String worldSeed, String tracePath, String serverLog,
                          int eventCount, List<String> stageOrder, Map<String, Integer> stageCounts,
                          List<String> correlationIds, List<String> regions, List<String> threadClasses,
                          boolean ownershipContextObserved, boolean regionLeaseObserved,
                          boolean futureSuccessObserved, boolean parsedChunkDataPresent, int exceptionCount,
                          String requestResponse, String completionResponse) {
            this.label = label;
            this.worldName = worldName;
            this.worldSeed = worldSeed;
            this.tracePath = tracePath;
            this.serverLog = serverLog;
            this.eventCount = eventCount;
            this.stageOrder = stageOrder;
            this.stageCounts = stageCounts;
            this.correlationIds = correlationIds;
            this.regions = regions;
            this.threadClasses = threadClasses;
            this.ownershipContextObserved = ownershipContextObserved;
            this.regionLeaseObserved = regionLeaseObserved;
            this.futureSuccessObserved = futureSuccessObserved;
            this.parsedChunkDataPresent = parsedChunkDataPresent;
            this.exceptionCount = exceptionCount;
            this.requestResponse = requestResponse;
            this.completionResponse = completionResponse;
        }

        private static TraceRun read(PendingChunkTrace pending) throws IOException {
            require(Files.exists(pending.traceOutput),
                "chunk runtime trace was not written for " + pending.label + ": " + pending.traceOutput);
            String raw = Files.readString(pending.traceOutput);
            require(raw.contains("STAGE3-CHUNK-RUNTIME") && raw.contains("\"events\""),
                "chunk runtime trace is not a recognized JSON artifact for " + pending.label);

            Map<String, Integer> stageCounts = new LinkedHashMap<>();
            LinkedHashSet<String> uniqueStages = new LinkedHashSet<>();
            Matcher stages = TRACE_STAGE.matcher(raw);
            while (stages.find()) {
                String stage = stages.group(1);
                uniqueStages.add(stage);
                stageCounts.merge(stage, 1, Integer::sum);
            }
            String serverLog = snapshotServerLog(pending);
            return new TraceRun(
                pending.label,
                pending.worldName,
                pending.worldSeed,
                pending.traceOutput.toAbsolutePath().toString(),
                serverLog,
                stageCountEvents(raw),
                List.copyOf(uniqueStages),
                new LinkedHashMap<>(stageCounts),
                distinctNumericValues(raw, "correlationId"),
                distinctValues(raw, "region"),
                distinctValues(raw, "threadClass"),
                raw.contains("\"ownershipContext\":true"),
                raw.contains("\"regionLease\":true"),
                raw.contains("\"futureSuccess\":true"),
                raw.contains("parsedDataPresent=true"),
                exceptionCount(raw),
                pending.requestResponse,
                pending.completionResponse);
        }

        private static int stageCountEvents(String raw) {
            int count = 0;
            Matcher stages = TRACE_STAGE.matcher(raw);
            while (stages.find()) count++;
            return count;
        }

        private static int exceptionCount(String raw) {
            int count = 0;
            Matcher exceptions = TRACE_EXCEPTION.matcher(raw);
            while (exceptions.find()) {
                if (!"null".equals(exceptions.group(1))) count++;
            }
            return count;
        }

        private static List<String> distinctValues(String raw, String field) {
            Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
            LinkedHashSet<String> values = new LinkedHashSet<>();
            Matcher matches = pattern.matcher(raw);
            while (matches.find()) values.add(matches.group(1));
            return List.copyOf(values);
        }

        private static List<String> distinctNumericValues(String raw, String field) {
            Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*(-?\\d+)");
            LinkedHashSet<String> values = new LinkedHashSet<>();
            Matcher matches = pattern.matcher(raw);
            while (matches.find()) values.add(matches.group(1));
            return List.copyOf(values);
        }

        private static String snapshotServerLog(PendingChunkTrace pending) {
            Path snapshot = pending.traceOutput.resolveSibling(
                "STAGE3-CHUNK-RUNTIME-" + pending.label + "-latest.log");
            try {
                if (Files.exists(pending.latestLog)) {
                    Files.copy(pending.latestLog, snapshot, StandardCopyOption.REPLACE_EXISTING);
                    return snapshot.toAbsolutePath().toString();
                }
            } catch (IOException ignored) {
                // The trace remains the authoritative machine-readable artifact for this run.
            }
            return pending.latestLog.toAbsolutePath().toString();
        }

        private int indexOf(String stage) { return stageOrder.indexOf(stage); }
    }

    private static final class ChunkTraceEvidence {
        private final String minecraftVersion;
        private final String javaVersion;
        private final String fabricLoaderVersion;
        private final String fabricApiVersion;
        private final String loomVersion;
        private final String gradleVersion;
        private final String gradleTask;
        private final List<TraceRun> runs = new ArrayList<>();
        private boolean firstServerProcessStarted;
        private boolean firstServerReady;
        private boolean firstServerStoppedCleanly;
        private boolean secondServerProcessStarted;
        private boolean secondServerReady;
        private boolean secondServerStoppedCleanly;
        private boolean reloadServerProcessStarted;
        private boolean reloadServerReady;
        private boolean reloadServerStoppedCleanly;
        private boolean productionServerModLoaded;
        private boolean semanticOrderingStable;
        private boolean success;
        private String serverDirectory = "unknown";
        private String firstWorldName = "unknown";
        private String firstWorldSeed = "unknown";
        private String secondWorldName = "unknown";
        private String secondWorldSeed = "unknown";
        private String reloadWorldName = "unknown";
        private String reloadWorldSeed = "unknown";
        private String firstServerLog = "unknown";
        private String secondServerLog = "unknown";
        private String reloadServerLog = "unknown";
        private String failure = "";

        private ChunkTraceEvidence(String minecraftVersion, String javaVersion, String fabricLoaderVersion,
                                   String fabricApiVersion, String loomVersion, String gradleVersion,
                                   String gradleTask) {
            this.minecraftVersion = minecraftVersion;
            this.javaVersion = javaVersion;
            this.fabricLoaderVersion = fabricLoaderVersion;
            this.fabricApiVersion = fabricApiVersion;
            this.loomVersion = loomVersion;
            this.gradleVersion = gradleVersion;
            this.gradleTask = gradleTask;
        }

        private static ChunkTraceEvidence create() {
            return new ChunkTraceEvidence(
                System.getProperty("phoenium.test.minecraftVersion", "unknown"),
                System.getProperty("phoenium.test.javaVersion", "unknown"),
                System.getProperty("phoenium.test.fabricLoaderVersion", "unknown"),
                System.getProperty("phoenium.test.fabricApiVersion", "unknown"),
                System.getProperty("phoenium.test.loomVersion", "unknown"),
                System.getProperty("phoenium.test.gradleVersion", "unknown"),
                System.getProperty("phoenium.test.gradleTask", "runStage3ChunkTrace"));
        }

        private String toJson() {
            StringBuilder json = new StringBuilder("{\n");
            field(json, "test", "CHUNK-RUNTIME");
            field(json, "success", success);
            field(json, "generatedAtUtc", Instant.now().toString());
            field(json, "minecraftVersion", minecraftVersion);
            field(json, "javaVersion", javaVersion);
            field(json, "fabricLoaderVersion", fabricLoaderVersion);
            field(json, "fabricApiVersion", fabricApiVersion);
            field(json, "loomVersion", loomVersion);
            field(json, "gradleVersion", gradleVersion);
            field(json, "gradleTask", gradleTask);
            field(json, "serverDirectory", serverDirectory);
            field(json, "targetWorld", "minecraft:overworld");
            field(json, "targetChunk", CHUNK_TRACE_X + "," + CHUNK_TRACE_Z);
            field(json, "requestedStatus", "FULL");
            field(json, "requestPath", "test-only RCON command -> ServerLevel.getChunkSource().getChunkFuture");
            field(json, "requestIncarnationAvailable", false);
            field(json, "holderIdentityObserved", true);
            field(json, "holderIncarnationAvailable", false);
            field(json, "chunkPosAvailable", true);
            field(json, "topologyGenerationObserved", true);
            field(json, "regionRouteObserved", true);
            field(json, "originalFutureObserved", true);
            field(json, "exceptionalLoadPathObserved", false);
            field(json, "realSupersessionObserved", false);
            field(json, "realDedicatedServer", true);
            field(json, "productionServerModLoaded", productionServerModLoaded);
            field(json, "clientHarnessUsed", true);
            field(json, "realTcpConnectionStarted", false);
            field(json, "clientNetworkConnectionRequired", false);
            field(json, "microsoftAuthentication", false);
            field(json, "minecraftLauncher", false);
            field(json, "microsoftStoreLauncher", false);
            field(json, "guiAutomation", false);
            field(json, "authoritativeChunkOwnershipImplemented", false);
            field(json, "vanillaFutureReplaced", false);
            field(json, "futuresJoinedOrWrapped", false);
            field(json, "secondWorkerIntroduced", false);
            field(json, "parallelGameplay", false);
            field(json, "workers", 1);
            field(json, "rChunk01", "PARTIAL");
            field(json, "stage3", "PARTIAL");
            field(json, "stage4", "NOT_STARTED");
            field(json, "freshGenerationRuns", 2);
            field(json, "persistedReloadRuns", 1);
            field(json, "firstServerProcessStarted", firstServerProcessStarted);
            field(json, "firstServerReady", firstServerReady);
            field(json, "firstServerStoppedCleanly", firstServerStoppedCleanly);
            field(json, "secondServerProcessStarted", secondServerProcessStarted);
            field(json, "secondServerReady", secondServerReady);
            field(json, "secondServerStoppedCleanly", secondServerStoppedCleanly);
            field(json, "reloadServerProcessStarted", reloadServerProcessStarted);
            field(json, "reloadServerReady", reloadServerReady);
            field(json, "reloadServerStoppedCleanly", reloadServerStoppedCleanly);
            field(json, "firstWorldName", firstWorldName);
            field(json, "firstWorldSeed", firstWorldSeed);
            field(json, "secondWorldName", secondWorldName);
            field(json, "secondWorldSeed", secondWorldSeed);
            field(json, "reloadWorldName", reloadWorldName);
            field(json, "reloadWorldSeed", reloadWorldSeed);
            field(json, "firstServerLog", firstServerLog);
            field(json, "secondServerLog", secondServerLog);
            field(json, "reloadServerLog", reloadServerLog);
            field(json, "semanticOrderingStable", semanticOrderingStable);
            field(json, "failure", failure);
            fieldPrefix(json, "runs");
            json.append("[");
            for (int i = 0; i < runs.size(); i++) {
                if (i > 0) json.append(",");
                appendRun(json, runs.get(i));
            }
            if (!runs.isEmpty()) json.append("\n  ");
            json.append("]\n}\n");
            return json.toString();
        }

        private static void appendRun(StringBuilder json, TraceRun run) {
            json.append("\n    {");
            objectField(json, "label", run.label, true);
            objectField(json, "worldName", run.worldName, true);
            objectField(json, "worldSeed", run.worldSeed, true);
            objectField(json, "tracePath", run.tracePath, true);
            objectField(json, "serverLog", run.serverLog, true);
            objectField(json, "eventCount", run.eventCount, true);
            objectField(json, "ownershipContextObserved", run.ownershipContextObserved, true);
            objectField(json, "regionLeaseObserved", run.regionLeaseObserved, true);
            objectField(json, "futureSuccessObserved", run.futureSuccessObserved, true);
            objectField(json, "parsedChunkDataPresent", run.parsedChunkDataPresent, true);
            objectField(json, "exceptionCount", run.exceptionCount, true);
            objectField(json, "requestResponse", run.requestResponse, true);
            objectField(json, "completionResponse", run.completionResponse, true);
            objectArray(json, "stageOrder", run.stageOrder, true);
            objectMap(json, "stageCounts", run.stageCounts, true);
            objectArray(json, "correlationIds", run.correlationIds, true);
            objectArray(json, "regions", run.regions, true);
            objectArray(json, "threadClasses", run.threadClasses, false);
            json.append("\n    }");
        }

        private String worldSeedText() {
            StringBuilder text = new StringBuilder("Phoenium Stage 3 chunk runtime trace world record\n")
                .append("targetWorld=minecraft:overworld\n")
                .append("targetChunk=").append(CHUNK_TRACE_X).append(",").append(CHUNK_TRACE_Z).append("\n")
                .append("freshGenerationRuns=2\n")
                .append("persistedReloadRuns=1\n\n")
                .append("firstWorldName=").append(firstWorldName).append("\n")
                .append("firstWorldSeed=").append(firstWorldSeed).append("\n")
                .append("secondWorldName=").append(secondWorldName).append("\n")
                .append("secondWorldSeed=").append(secondWorldSeed).append("\n")
                .append("reloadWorldName=").append(reloadWorldName).append("\n")
                .append("reloadWorldSeed=").append(reloadWorldSeed).append("\n");
            for (TraceRun run : runs) {
                text.append("\n[").append(run.label).append("]\n")
                    .append("tracePath=").append(run.tracePath).append("\n")
                    .append("serverLog=").append(run.serverLog).append("\n")
                    .append("stageOrder=").append(String.join(" -> ", run.stageOrder)).append("\n");
            }
            return text.toString();
        }

        private static void field(StringBuilder json, String name, String value) {
            fieldPrefix(json, name);
            json.append(Stage3ClientTest.json(value));
        }

        private static void field(StringBuilder json, String name, boolean value) {
            fieldPrefix(json, name);
            json.append(value);
        }

        private static void field(StringBuilder json, String name, int value) {
            fieldPrefix(json, name);
            json.append(value);
        }

        private static void fieldPrefix(StringBuilder json, String name) {
            if (json.length() > 2) json.append(",\n");
            json.append("  ").append(Stage3ClientTest.json(name)).append(": ");
        }

        private static void objectField(StringBuilder json, String name, String value, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": ")
                .append(Stage3ClientTest.json(value));
            if (comma) json.append(",");
        }

        private static void objectField(StringBuilder json, String name, boolean value, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": ").append(value);
            if (comma) json.append(",");
        }

        private static void objectField(StringBuilder json, String name, int value, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": ").append(value);
            if (comma) json.append(",");
        }

        private static void objectArray(StringBuilder json, String name, List<String> values, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": [");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) json.append(", ");
                json.append(Stage3ClientTest.json(values.get(i)));
            }
            json.append("]");
            if (comma) json.append(",");
        }

        private static void objectMap(StringBuilder json, String name, Map<String, Integer> values, boolean comma) {
            json.append("\n      ").append(Stage3ClientTest.json(name)).append(": {");
            int index = 0;
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                if (index++ > 0) json.append(",");
                json.append("\n        ").append(Stage3ClientTest.json(entry.getKey()))
                    .append(": ").append(entry.getValue());
            }
            if (!values.isEmpty()) json.append("\n      ");
            json.append("}");
            if (comma) json.append(",");
        }
    }

    private static void connectClient(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ServerData server = new ServerData("Phoenium Stage 3", SERVER_ADDRESS, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(client.gui.screen(), client, ServerAddress.parseString(SERVER_ADDRESS), server, false, null);
        });
        context.waitFor(client -> client.level != null && client.player != null, CLIENT_CONNECT_TIMEOUT_TICKS);
    }

    private static void disconnectClient(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ClientPacketListener listener = client.getConnection();
            if (listener != null) {
                // Minecraft.disconnectWithSavingScreen() tears down the local level, but 26.2's
                // listener close path does not synchronously close the underlying TCP channel.
                // Close that normal client connection explicitly so the external server observes
                // the same clean network disconnect that a user leaving the server would cause.
                listener.getConnection().disconnect(Component.literal("Phoenium automated test disconnect"));
            }
            client.disconnect(new TitleScreen(), false);
        });
        context.waitFor(client -> client.level == null, 600);
    }

    private static String clientName(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player == null ? "" : client.player.getName().getString());
    }

    private static double[] clientPosition(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player == null
            ? new double[] {Double.NaN, Double.NaN, Double.NaN}
            : new double[] {client.player.getX(), client.player.getY(), client.player.getZ()});
    }

    private static int clientEntityCount(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.level == null ? 0 : client.level.getEntityCount());
    }

    private static void waitForClientInChunk(ClientGameTestContext context, int chunkX, int chunkZ,
                                             String phase) {
        for (int tick = 0; tick < CLIENT_CONNECT_TIMEOUT_TICKS; tick++) {
            double[] position = clientPosition(context);
            if (position.length == 3 && Double.isFinite(position[0]) && Double.isFinite(position[2])
                    && (((int) Math.floor(position[0])) >> 4) == chunkX
                    && (((int) Math.floor(position[2])) >> 4) == chunkZ) {
                return;
            }
            context.waitTick();
        }
        double[] position = clientPosition(context);
        throw new AssertionError("real client did not reach " + phase + " chunk=" + chunkX + "," + chunkZ
            + " position=[" + position[0] + "," + position[1] + "," + position[2] + "]");
    }

    private static ClientChunkObservation clientChunkObservation(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            if (client.level == null) {
                return new ClientChunkObservation(false, false, false, false, 0, null);
            }
            ClientChunkCache cache = client.level.getChunkSource();
            long targetKey = ChunkPos.pack(CHUNK_READINESS_X, CHUNK_READINESS_Z);
            LevelChunk chunk = cache.getChunk(CHUNK_READINESS_X, CHUNK_READINESS_Z, ChunkStatus.FULL, false);
            boolean exactChunkLoaded = chunk != null
                && chunk.getPos().x() == CHUNK_READINESS_X
                && chunk.getPos().z() == CHUNK_READINESS_Z;
            return new ClientChunkObservation(true, exactChunkLoaded,
                cache.addedLoadedChunks().contains(targetKey),
                cache.removedLoadedChunks().contains(targetKey),
                cache.getLoadedChunksCount(), exactChunkLoaded ? clientIdentity(chunk) : null);
        });
    }

    private static String clientIdentity(Object value) {
        if (value == null) return null;
        return value.getClass().getName() + "#" + Integer.toUnsignedString(System.identityHashCode(value));
    }

    private static String clientPositionJson(ClientGameTestContext context) {
        double[] position = clientPosition(context);
        return "[" + position[0] + ", " + position[1] + ", " + position[2] + "]";
    }

    private static Snapshot record(ExternalServer server, Evidence evidence, String phase) throws IOException {
        Snapshot snapshot = new Snapshot(server.execute("phoenium diagnostics"));
        require(snapshot.raw.contains("move[") && snapshot.raw.contains("interact["),
            "diagnostics command returned no packet counters for " + phase + ": " + snapshot.raw);
        evidence.snapshots.put(phase, snapshot);
        return snapshot;
    }

    private static long value(Snapshot snapshot, String key) {
        return snapshot.values.getOrDefault(key, -1L);
    }

    private static long delta(Snapshot before, Snapshot after, String key) {
        long beforeValue = value(before, key);
        long afterValue = value(after, key);
        require(beforeValue >= 0 && afterValue >= 0, "diagnostics field is missing: " + key);
        return afterValue - beforeValue;
    }

    private static double[] parsePosition(String raw) {
        Matcher matcher = POSITION.matcher(raw);
        require(matcher.find(), "server position response was not parseable: " + raw);
        return new double[] {
            Double.parseDouble(matcher.group(1)),
            Double.parseDouble(matcher.group(2)),
            Double.parseDouble(matcher.group(3))
        };
    }

    private static int chunkCoordinate(double coordinate) {
        return (int) Math.floor(coordinate / 16.0D);
    }

    private static double distance(double[] first, double[] second) {
        double dx = first[0] - second[0];
        double dy = first[1] - second[1];
        double dz = first[2] - second[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void writeEvidence(Evidence evidence) {
        Path path = Path.of(System.getProperty(
            "phoenium.client.gametest.evidence", "build/client-gametest/evidence/STAGE3-CLIENT.json"));
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path seedPath = path.resolveSibling("STAGE3-CLIENT-world-seeds.txt").toAbsolutePath();
            evidence.worldSeedFile = seedPath.toString();
            Files.writeString(seedPath, evidence.worldSeedText(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(path, evidence.toJson(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new AssertionError("Unable to write Stage 3 client evidence to " + path, exception);
        }
    }

    private static String json(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private static String diagnosticSeed() {
        String seed = System.getenv("PHOENIUM_DIAGNOSTIC_SEED");
        return seed == null ? "" : Long.toString(Long.parseLong(seed));
    }

    private void runFullAcceptance(ClientGameTestContext context) {
        ExternalServer active = null;
        boolean connected = false;
        Map<String, Object> evidence = new LinkedHashMap<>();
        Path output = null;
        try {
            Path prepared = Path.of(System.getProperty("phoenium.test.serverDirectory"));
            Path directory = prepared.getParent().getParent().resolve("run/full-authoritative/live-" + System.currentTimeMillis());
            Files.createDirectories(directory);
            try (var files = Files.walk(prepared)) {
                for (Path source : files.toList()) {
                    Path relative = prepared.relativize(source);
                    if (relative.getNameCount() > 0 && !Set.of("mods", "libraries", "versions", ".fabric", "fabric-server-launch.jar")
                            .contains(relative.getName(0).toString())) continue;
                    Path target = directory.resolve(relative);
                    if (Files.isDirectory(source)) Files.createDirectories(target);
                    else Files.copy(source, target);
                }
            }
            output = directory.resolve("full-acceptance.json");
            evidence.put("directory", directory.toString());
            evidence.put("minecraft", "26.2"); evidence.put("workers", 1); evidence.put("parallelGameplay", false);
            evidence.put("testUsername", TEST_USERNAME); evidence.put("personalAccount", false);
            evidence.put("viewDistance", 5); evidence.put("simulationDistance", 5);
            var modules = new LinkedHashMap<String, String>();
            try (var jars = Files.list(directory.resolve("mods"))) {
                for (Path jar : jars.toList()) modules.put(jar.getFileName().toString(), java.util.HexFormat.of().withUpperCase()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
            }
            evidence.put("modules", modules);
            String world = "FULL-LIVE-" + System.currentTimeMillis();
            evidence.put("world", world);
            Path worldRoot = directory.resolve(world);
            for (int lifecycle = 1; lifecycle <= 2; lifecycle++) {
                String phase = lifecycle == 1 ? "fresh" : "reload";
                active = ExternalServer.start(new ExternalServer(directory, lifecycle, world, null), false, false);
                active.waitUntilReady(context);
                evidence.put(phase + "Seed", active.worldSeed());
                String bedSupport = active.execute(fullBedSupportCommand());
                String bedSupportQuery = active.execute(fullBedSupportQueryCommand());
                require(fullBedSupportSucceeded(phase, bedSupport, bedSupportQuery), active.serverFailure(
                        "FULL saved-bed support was not force-loaded: add=" + bedSupport
                                + " query=" + bedSupportQuery));
                evidence.put(phase + "SavedBedSupport", bedSupport);
                evidence.put(phase + "SavedBedSupportQuery", bedSupportQuery);
                String bedReady = "";
                for (int tick = 0; tick < 1200; tick++) {
                    require(active.isAlive(), active.serverFailure("FULL saved-bed support stopped during " + phase));
                    bedReady = active.execute("fullbedready");
                    if (bedReady.contains("FULL_BED_READY chunk=[127, -69]")) break;
                    context.waitTick();
                }
                require(bedReady.contains("FULL_BED_READY chunk=[127, -69]"), active.serverFailure(
                        "FULL saved-bed support did not reach FULL before fixture demand: " + bedReady));
                evidence.put(phase + "SavedBedReady", bedReady);
                connectClient(context); connected = true;
                require(clientName(context).equals(TEST_USERNAME), "FULL client identity mismatch");
                if (lifecycle == 1) {
                    active.execute("team add full_probe");
                    active.execute("team join full_probe " + TEST_USERNAME);
                    active.execute("forceload add 512 64 575 79");
                }
                String state = "";
                for (int tick = 0; tick < 7200; tick++) {
                    require(active.isAlive(), active.serverFailure("FULL server stopped during " + phase));
                    if (tick % 10 == 0) {
                        state = active.execute("fullprobe");
                        if (state.contains("FULL_LIVE_OK phase=" + phase)) break;
                        require(state.contains("FULL_LIVE_PENDING"), active.serverFailure("FULL fixture failed: " + state));
                    }
                    context.waitTick();
                }
                require(state.contains("FULL_LIVE_OK phase=" + phase), active.serverFailure("FULL demand timed out: " + state));
                requireFullAcceptanceState(phase, state);
                evidence.put(phase + "ServerAssertions", state);
                active.execute("tp " + TEST_USERNAME + " 536 207 72");
                boolean received = false;
                for (int tick = 0; tick < 2400; tick++) {
                    received = context.computeOnClient(client -> {
                        if (client.level == null) return false;
                        for (int x = 32; x <= 35; x++) {
                            LevelChunk chunk = client.level.getChunkSource().getChunk(x, 4, ChunkStatus.FULL, false);
                            if (chunk == null || !chunk.getPos().equals(new ChunkPos(x, 4))) return false;
                        }
                        return client.level.getBlockState(new BlockPos(528, 200, 72)).is(net.minecraft.world.level.block.Blocks.SHULKER_BOX);
                    });
                    if (received) break;
                    context.waitTick();
                }
                require(received, "client never received exact target chunks/nonair fixture during " + phase);
                evidence.put(phase + "RealTcpChunkReceipt", true);
                evidence.put(phase + "Diagnostics", active.execute("phoenium diagnostics"));
                active.execute("save-all flush");
                if (phase.equals("fresh"))
                    evidence.put("freshPersistedFullPayload", persistedFullFixturePayload(worldRoot));
                disconnectClient(context); connected = false;
                require(active.stopNormally(context), active.serverFailure("FULL normal stop timed out"));
                String log = active.readLatestLog();
                String processLog = active.readProcessLog();
                String lifecycleProcessLog = currentLifecycleProcessLog(processLog);
                long levelDatBytes = persistedLevelBytes(worldRoot);
                require(levelDatBytes > 0, active.serverFailure(
                        "FULL lifecycle did not leave a readable on-disk level.dat: " + worldRoot));
                List<String> suffixLines = lifecycleProcessLog.lines()
                        .filter(line -> line.contains("PHOENIUM_FULL_LIVE_SUFFIX phase=" + phase))
                        .toList();
                require(suffixLines.size() == 4 && suffixLines.stream().allMatch(line ->
                                line.contains("publicPending=true") && line.contains("admitted=true")),
                        active.serverFailure("FULL suffix admission evidence was incomplete: " + suffixLines));
                long generatedFixtures = lifecycleProcessLog.lines()
                        .filter(line -> line.contains("PHOENIUM_FULL_LIVE_GENERATED chunk=")).count();
                require(generatedFixtures == (phase.equals("fresh") ? 4 : 0),
                        active.serverFailure("FULL serializer/generation route was not phase-correct: generated="
                                + generatedFixtures + " phase=" + phase));
                require(active.process.exitValue() == 0 && healthyFullAcceptanceLog(log)
                        && healthyFullAcceptanceLog(lifecycleProcessLog),
                        active.serverFailure("FULL server health check failed"));
                evidence.put(phase + "FullStopped", requireFullStoppedMetrics(lifecycleProcessLog));
                evidence.put(phase + "ChunkBindingStopped", requireChunkBindingStopped(lifecycleProcessLog));
                evidence.put(phase + "Stage2Stopped", requireStage2Stopped(lifecycleProcessLog));
                evidence.put(phase + "ServicesStopped", requireServicesStopped(lifecycleProcessLog));
                Path savedLatestLog = directory.resolve(phase + "-latest.log");
                Path savedProcessLog = directory.resolve(phase + "-server-process.log");
                Files.copy(active.latestLog, savedLatestLog, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(active.processLog, savedProcessLog, StandardCopyOption.REPLACE_EXISTING);
                evidence.put(phase + "Exit", active.process.exitValue());
                evidence.put(phase + "LatestLog", savedLatestLog.toString());
                evidence.put(phase + "ProcessLog", savedProcessLog.toString());
                evidence.put(phase + "WorldRoot", worldRoot.toString());
                evidence.put(phase + "LevelDatBytes", levelDatBytes);
                evidence.put(phase + "SuffixAdmissions", suffixLines.size());
                active = null;
            }
            require(Objects.equals(evidence.get("freshSeed"), evidence.get("reloadSeed")), "reload did not use the actual saved world");
            evidence.put("passed", true);
        } catch (Throwable failure) {
            evidence.put("passed", false); evidence.put("failure", failure.toString());
            throw new AssertionError("authoritative FULL real-world/client acceptance failed", failure);
        } finally {
            if (connected) try { disconnectClient(context); } catch (Throwable ignored) { }
            if (active != null) {
                active.stopQuietly(context);
                preserveFullAcceptanceLogs(active, evidence);
            }
            if (output != null) try { Files.writeString(output, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(evidence)); }
            catch (IOException failure) { throw new IllegalStateException("could not preserve FULL acceptance evidence", failure); }
        }
    }

    private static void preserveFullAcceptanceLogs(ExternalServer server, Map<String, Object> evidence) {
        String phase = server.lifecycle == 1 ? "fresh" : "reload";
        Path directory = server.directory;
        try {
            Path savedLatestLog = directory.resolve(phase + "-latest.log");
            Path savedProcessLog = directory.resolve(phase + "-server-process.log");
            if (Files.exists(server.latestLog)) {
                Files.copy(server.latestLog, savedLatestLog, StandardCopyOption.REPLACE_EXISTING);
                evidence.put(phase + "LatestLog", savedLatestLog.toString());
            }
            if (Files.exists(server.processLog)) {
                Files.copy(server.processLog, savedProcessLog, StandardCopyOption.REPLACE_EXISTING);
                evidence.put(phase + "ProcessLog", savedProcessLog.toString());
            }
            if (!server.isAlive()) evidence.put(phase + "Exit", server.process.exitValue());
        } catch (IOException | IllegalThreadStateException ignored) {
            evidence.put(phase + "LogPreservationFailure", "unable to preserve lifecycle logs or exit code");
        }
    }

    private static void requireFullAcceptanceState(String phase, String state) {
        String origin = phase.equals("fresh") ? "ADMITTED_PROTO" : "ADMITTED_IMPOSTER";
        String[] required = {
            "FULL_LIVE_OK phase=" + phase,
            "sources=4", "savedReaders=8", "richVillagers=4", "frogs=4",
            "teams=true", "separatePublication=true", "bedChecks=4", "origin=" + origin,
        };
        for (String field : required)
            require(state.contains(field), "FULL " + phase + " assertion omitted " + field + ": " + state);
        Matcher neighbors = Pattern.compile("(?:^|\\s)privateNeighbors=(\\d+)(?:\\s|$)").matcher(state);
        require(neighbors.find() && Integer.parseInt(neighbors.group(1)) > 0,
            "FULL " + phase + " did not report a positive private-neighbor count: " + state);
        Matcher collision = Pattern.compile("(?:^|\\s)collisionQueries=(\\d+)(?:\\s|$)").matcher(state);
        require(collision.find() && Integer.parseInt(collision.group(1)) > 0,
            "FULL " + phase + " did not report an original collision query: " + state);
        Matcher immediate = Pattern.compile("(?:^|\\s)immediateShulkerReads=(\\d+)(?:\\s|$)").matcher(state);
        require(immediate.find() && Integer.parseInt(immediate.group(1)) > 0,
            "FULL " + phase + " did not report an original ShulkerBox IMMEDIATE read: " + state);
    }

    private static String fullBedSupportCommand() {
        return "forceload add 2032 -1104 2047 -1089";
    }

    private static String fullBedSupportQueryCommand() {
        return "forceload query 2032 -1104";
    }

    private static boolean fullBedSupportSucceeded(String phase, String addResponse, String queryResponse) {
        boolean added = addResponse.contains(
                "Marked chunk [127, -69] in minecraft:overworld to be force loaded");
        boolean alreadyPresent = phase.equals("reload")
                && addResponse.contains("No chunks were marked for force loading");
        boolean queryConfirms = queryResponse.contains(
                "Chunk at [127, -69] in minecraft:overworld is marked for force loading");
        return (added || alreadyPresent) && queryConfirms;
    }

    private static boolean healthyFullAcceptanceLog(String log) {
        return !log.contains("Encountered an unexpected exception")
            && !log.contains("Exception stopping the server")
            && !log.contains("FULL_LIVE_FAIL")
            && !log.contains("FULL fixture failed");
    }

    /**
     * The authoritative lifecycle must publish one complete FULL cleanup snapshot.  A
     * generic shutdown marker or a missing field is insufficient evidence: absent metrics
     * must fail the acceptance instead of being interpreted as zero.
     */
    private static String requireFullStoppedMetrics(String lifecycleProcessLog) {
        List<String> lines = lifecycleProcessLog.lines()
                .filter(line -> line.contains("PHOENIUM_FULL STOPPED"))
                .toList();
        require(lines.size() == 1,
                "authoritative FULL teardown must emit exactly one scoped STOPPED snapshot: " + lines);
        String line = lines.get(0);
        String[] fields = {
                "structureHolders", "structureReaders", "structureWriters", "structureWaitingWriters",
                "structureDemandTickets", "generationHolders", "graphs", "returnedIndex", "deliveries",
                "plans", "requestedPreparations", "consumers", "factBytes", "pendingNotifications",
                "expandedOperations", "edges", "records", "uses", "transientExecutions", "chargedRecordBytes"
        };
        for (String field : fields) {
            Matcher metric = Pattern.compile("(?:^|[\\s,{])" + Pattern.quote(field)
                            + "=(-?\\d+)(?:\\s|[,}])")
                    .matcher(line);
            int occurrences = 0;
            while (metric.find()) {
                occurrences++;
                require(Long.parseLong(metric.group(1)) == 0L,
                        "authoritative FULL teardown left " + field + " nonzero: " + line);
            }
            require(occurrences > 0, "authoritative FULL teardown omitted cleanup metric " + field + ": " + line);
        }
        return line;
    }

    private static String requireChunkBindingStopped(String lifecycleProcessLog) {
        List<String> lines = lifecycleProcessLog.lines()
                .filter(line -> line.contains("PHOENIUM_CHUNK_BINDING STOPPED"))
                .toList();
        require(lines.size() == 1,
                "authoritative FULL teardown must emit exactly one scoped CHUNK_BINDING snapshot: " + lines);
        String line = lines.get(0);
        String[] fields = {
                "barriers", "retainedGraphs", "returnedIndex", "poiIndex", "synchronousInvocations",
                "lightBatches", "lightOccurrences", "lightReceipts", "poiEntries", "metadataEntries",
                "lifecycleEntries", "workEntries", "custodyEntries", "duplicateApplications"
        };
        for (String field : fields)
            requireZeroMetric(line, field, "CHUNK_BINDING");
        return line;
    }

    private static String requireStage2Stopped(String lifecycleProcessLog) {
        List<String> lines = lifecycleProcessLog.lines()
                .filter(line -> line.contains("PHOENIUM_STAGE2 stopped"))
                .toList();
        require(lines.size() == 1,
                "authoritative FULL teardown must emit exactly one scoped STAGE2 snapshot: " + lines);
        String line = lines.get(0);
        for (String field : new String[] {
                "lifecycle=STOPPED", "scheduler=STOPPING", "workers=1", "lifecycleStarts=1",
                "lifecycleStops=1", "parallel=OFF", "leases=0", "fallback=0",
                "ownershipViolations=0", "unleased=0", "barriers=0", "duplicate=0", "bypass=0"
        })
            require(line.contains(field), "STAGE2 teardown omitted or changed " + field + ": " + line);
        return line;
    }

    private static String requireServicesStopped(String lifecycleProcessLog) {
        List<String> lines = lifecycleProcessLog.lines()
                .filter(line -> line.contains("PHOENIUM_FULL_SERVICES STOPPED PASS"))
                .toList();
        require(lines.size() == 1,
                "authoritative FULL teardown must emit exactly one finishing-service snapshot: " + lines);
        String line = lines.get(0);
        for (String field : new String[] {
                "stopEvent=true", "serviceReservations=0", "serviceActive=0", "serviceWorlds=0",
                "serviceEnvironmentRequests=0", "entityFactsAddressable=0", "scoreboardSessions=0",
                "scoreboardCatchup=0", "legacyPlans=0", "legacyQueued=0", "legacyRunning=0",
                "legacyProducerTerminated=1", "legacyCacheRoots=0"
        })
            require(line.contains(field), "finishing-service teardown omitted or changed " + field + ": " + line);
        return line;
    }

    private static void requireZeroMetric(String line, String field, String subsystem) {
        Matcher metric = Pattern.compile("(?:^|[\\s,{])" + Pattern.quote(field)
                        + "=(-?\\d+)(?:\\s|[,}])")
                .matcher(line);
        int occurrences = 0;
        while (metric.find()) {
            occurrences++;
            require(Long.parseLong(metric.group(1)) == 0L,
                    subsystem + " teardown left " + field + " nonzero: " + line);
        }
        require(occurrences > 0, subsystem + " teardown omitted cleanup metric " + field + ": " + line);
    }

    /** The two acceptance lifecycles append to one process log; scope route evidence to this boot. */
    private static String currentLifecycleProcessLog(String processLog) {
        int start = processLog.lastIndexOf("Starting minecraft server version");
        return start < 0 ? processLog : processLog.substring(start);
    }

    private static long persistedLevelBytes(Path worldRoot) {
        try {
            Path levelData = worldRoot.resolve("level.dat");
            return Files.isRegularFile(levelData) ? Files.size(levelData) : 0L;
        } catch (IOException ignored) {
            return 0L;
        }
    }

    /** Read the actual region record after the fresh save, before the reload lifecycle starts. */
    private static String persistedFullFixturePayload(Path worldRoot) {
        Path region = worldRoot.resolve("dimensions/minecraft/overworld/region/r.1.0.mca");
        try {
            require(Files.isRegularFile(region), "fresh FULL region payload is missing: " + region);
            byte[] bytes = Files.readAllBytes(region);
            require(bytes.length >= 8192, "fresh FULL region header is truncated: " + region);
            List<String> chunks = new ArrayList<>();
            for (int x = 32; x <= 35; x++) {
                int index = (x & 31) + 32 * 4;
                int location = ((bytes[index * 4] & 0xff) << 16)
                        | ((bytes[index * 4 + 1] & 0xff) << 8) | (bytes[index * 4 + 2] & 0xff);
                int sectors = bytes[index * 4 + 3] & 0xff;
                require(location > 1 && sectors > 0, "fresh FULL region record missing for chunk " + x + ",4");
                int start = location * 4096;
                require(start + 5 <= bytes.length, "fresh FULL region record is truncated for chunk " + x + ",4");
                int length = ((bytes[start] & 0xff) << 24) | ((bytes[start + 1] & 0xff) << 16)
                        | ((bytes[start + 2] & 0xff) << 8) | (bytes[start + 3] & 0xff);
                require(length > 1 && length <= sectors * 4096 - 4 && start + 4 + length <= bytes.length,
                        "fresh FULL region record length is invalid for chunk " + x + ",4: " + length);
                require((bytes[start + 4] & 0xff) == 2,
                        "fresh FULL region record is not zlib compressed for chunk " + x + ",4");
                CompoundTag payload;
                try (DataInputStream input = new DataInputStream(new InflaterInputStream(
                        new ByteArrayInputStream(bytes, start + 5, length - 1)))) {
                    payload = NbtIo.read(input, NbtAccounter.unlimitedHeap());
                }
                String status = payload.getStringOr("Status", "");
                require(status.equals("full") || status.equals("minecraft:full"),
                        "fresh FULL region record status was not full for chunk " + x + ",4: " + status);
                require(payload.getIntOr("xPos", Integer.MIN_VALUE) == x
                                && payload.getIntOr("zPos", Integer.MIN_VALUE) == 4,
                        "fresh FULL region record coordinates were wrong for chunk " + x + ",4: xPos="
                                + payload.getIntOr("xPos", Integer.MIN_VALUE) + " zPos="
                                + payload.getIntOr("zPos", Integer.MIN_VALUE));
                ListTag entities = payload.getListOrEmpty("entities");
                require(entities.size() == 2,
                        "fresh FULL region record entity list size was not two for chunk " + x + ",4: "
                                + entities.size());
                List<String> entityPayloads = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    require(entities.get(i) instanceof CompoundTag,
                            "fresh FULL region record entity " + i + " was not a compound for chunk " + x + ",4");
                    CompoundTag entity = (CompoundTag) entities.get(i);
                    String expectedTag = "phoenium_full_reload_" + x + "_" + i;
                    ListTag tags = entity.getListOrEmpty("Tags");
                    require(tags.size() == 2 && tags.get(0) instanceof StringTag && tags.get(1) instanceof StringTag
                                    && ((((StringTag) tags.get(0)).value().equals("phoenium_full_reload"))
                                    || ((StringTag) tags.get(1)).value().equals("phoenium_full_reload"))
                                    && ((((StringTag) tags.get(0)).value().equals(expectedTag))
                                    || ((StringTag) tags.get(1)).value().equals(expectedTag)),
                            "fresh FULL region record entity tags were wrong for chunk " + x + ",4 entity " + i
                                    + ": " + tags);
                    UUID expectedUuid = UUID.nameUUIDFromBytes(("reload:" + new ChunkPos(x, 4) + ":" + i)
                            .getBytes(StandardCharsets.UTF_8));
                    UUID actualUuid = entity.read("UUID", UUIDUtil.CODEC).orElse(null);
                    require(expectedUuid.equals(actualUuid), "fresh FULL region record entity UUID was wrong for chunk "
                            + x + ",4 entity " + i + ": " + actualUuid + " expected=" + expectedUuid);
                    entityPayloads.add(entity.toString());
                }
                String recordHash = java.util.HexFormat.of().withUpperCase().formatHex(
                        sha256(bytes, start, 4 + length));
                List<String> uuids = new ArrayList<>();
                for (int i = 0; i < entities.size(); i++) {
                    CompoundTag entity = (CompoundTag) entities.get(i);
                    UUID actualUuid = entity.read("UUID", UUIDUtil.CODEC).orElse(null);
                    uuids.add(String.valueOf(actualUuid));
                }
                chunks.add("[" + x + ",4]:status=" + status + ",offset=" + location
                        + ",sectors=" + sectors + ",recordBytes=" + (4 + length)
                        + ",recordSha256=" + recordHash + ",legacyEntities=" + entities.size()
                        + ",uuids=" + uuids + ",entityPayloads=" + entityPayloads);
            }
            return "path=" + region + " chunks=" + chunks + " roots=8";
        } catch (IOException failure) {
            throw new AssertionError("Unable to inspect the fresh FULL region payload: " + region, failure);
        }
    }

    private static byte[] sha256(byte[] bytes, int offset, int length) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(java.util.Arrays.copyOfRange(bytes, offset, offset + length));
        } catch (java.security.GeneralSecurityException failure) {
            throw new AssertionError("SHA-256 is unavailable for the persisted FULL proof", failure);
        }
    }

    private static final class ExternalServer {
        private final Path directory;
        private final Path latestLog;
        private final Path processLog;
        private final int lifecycle;
        private final String worldName;
        private final Path traceOutput;
        private final boolean readinessTrace;
        private Process process;
        private boolean cleanStop;

        private ExternalServer(Path directory, int lifecycle) {
            this(directory, lifecycle, "STAGE3-CLIENT-" + lifecycle + "-" + System.currentTimeMillis(), null, false);
        }

        private ExternalServer(Path directory, int lifecycle, String worldName, Path traceOutput) {
            this(directory, lifecycle, worldName, traceOutput, false);
        }

        private ExternalServer(Path directory, int lifecycle, String worldName, Path traceOutput,
                               boolean readinessTrace) {
            this.directory = directory;
            this.latestLog = directory.resolve("logs").resolve("latest.log");
            this.processLog = directory.resolve("server-process.log");
            this.lifecycle = lifecycle;
            this.worldName = worldName;
            this.traceOutput = traceOutput;
            this.readinessTrace = readinessTrace;
        }

        private static ExternalServer start(int lifecycle) throws IOException {
            // Explicit diagnostic replay only; the ordinary acceptance ladder still uses fresh seeds.
            String diagnosticDirectory = System.getenv("PHOENIUM_DIAGNOSTIC_SERVER_DIR");
            Path directory = Path.of(diagnosticDirectory == null
                ? System.getProperty("phoenium.test.serverDirectory") : diagnosticDirectory);
            require(Files.isDirectory(directory), "prepared external server directory is missing: " + directory);
            ExternalServer server = new ExternalServer(directory, lifecycle);
            return start(server, false, false);
        }

        private static ExternalServer startChunkTrace(int lifecycle, String worldName, Path traceOutput)
                throws IOException {
            Path directory = Path.of(System.getProperty("phoenium.test.serverDirectory"));
            require(Files.isDirectory(directory), "prepared external server directory is missing: " + directory);
            ExternalServer server = new ExternalServer(directory, lifecycle, worldName, traceOutput);
            return start(server, true, false);
        }

        private static ExternalServer startChunkReadinessTrace(int lifecycle, String worldName, Path traceOutput)
                throws IOException {
            Path directory = Path.of(System.getProperty("phoenium.test.serverDirectory"));
            require(Files.isDirectory(directory), "prepared external server directory is missing: " + directory);
            ExternalServer server = new ExternalServer(directory, lifecycle, worldName, traceOutput, true);
            return start(server, true, true);
        }

        private static ExternalServer start(ExternalServer server, boolean chunkTrace, boolean lazyDemand)
                throws IOException {
            server.writeConfiguration();
            Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
            List<String> command = new ArrayList<>(List.of(
                java.toString(), "-Xmx2G",
                "-Dphoenium.stage3.explicitTopology=true",
                "-Dphoenium.test.chunkFixtureSupport=true",
                "-Dphoenium.test.chunkBindingTrace=true",
                "-Dphoenium.test.diagnostics=true"));
            if ("fullacceptance".equals(System.getProperty("phoenium.test.mode"))) {
                command.add("-Dphoenium.full.authoritative=true");
                command.add("-Dphoenium.test.fullBedSupport=true");
                command.add("-Dphoenium.test.fullFixturePhase=" + (server.lifecycle == 1 ? "fresh" : "reload"));
                command.add("-Dphoenium.test.fullFixtureSeedDisk=" + (server.lifecycle == 1));
            }
            if (chunkTrace) {
                command.add("-Dphoenium.stage3.chunkTrace=true");
                command.add("-Dphoenium.stage3.chunkTrace.world=minecraft:overworld");
                int targetX = server.readinessTrace ? CHUNK_READINESS_X : CHUNK_TRACE_X;
                int targetZ = server.readinessTrace ? CHUNK_READINESS_Z : CHUNK_TRACE_Z;
                command.add("-Dphoenium.stage3.chunkTrace.targets=" + targetX + "," + targetZ);
                command.add("-Dphoenium.stage3.chunkTrace.output=" + server.traceOutput.toAbsolutePath());
                command.add("-Dphoenium.stage3.chunkTrace.lazyDemand=" + lazyDemand);
            }
            command.add("-jar");
            command.add("fabric-server-launch.jar");
            command.add("nogui");
            ProcessBuilder builder = new ProcessBuilder(command)
                .directory(server.directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(server.processLog.toFile()));
            server.process = builder.start();
            return server;
        }

        private void writeConfiguration() throws IOException {
            Files.writeString(directory.resolve("eula.txt"), "# Disposable Stage 3 evidence server\neula=true\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            List<String> properties = List.of(
                "accepts-transfers=false",
                "allow-flight=true",
                "difficulty=peaceful",
                "enable-rcon=true",
                "enable-status=true",
                "enforce-secure-profile=false",
                "gamemode=creative",
                "force-gamemode=true",
                "level-name=" + worldName,
                "level-seed=" + diagnosticSeed(),
                "max-players=1",
                "motd=Phoenium Stage 3 real-client evidence",
                "online-mode=false",
                "server-ip=127.0.0.1",
                "server-port=25565",
                "rcon.ip=127.0.0.1",
                "rcon.password=" + rconPassword(),
                "rcon.port=" + rconPort(),
                "spawn-animals=false",
                "spawn-npcs=false",
                "spawn-protection=0",
                "sync-chunk-writes=true",
                "view-distance=" + (readinessTrace ? "2" : "5"),
                "simulation-distance=" + (readinessTrace ? "2" : "5"));
            Files.writeString(directory.resolve("server.properties"), String.join(System.lineSeparator(), properties)
                    + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        private void waitUntilReady(ClientGameTestContext context) {
            for (int tick = 0; tick < SERVER_START_TIMEOUT_TICKS; tick++) {
                require(isAlive(), serverFailure("external server exited before readiness"));
                String log = readLatestLog();
                if (log.contains("PHOENIUM_STAGE2 started") && log.contains("explicitFixture=true") && tick % 5 == 0) {
                    try {
                        String diagnostics = execute("phoenium diagnostics");
                        if (diagnostics.contains("lifecycle=RUNNING")) return;
                    } catch (IOException ignored) {
                        // The server log can lead RCON by a few ticks while the listener binds.
                    }
                }
                context.waitTick();
            }
            throw new AssertionError(serverFailure("external server did not become RCON-ready"));
        }

        private String worldSeed() throws IOException {
            String response = execute("seed");
            require(response != null && !response.isBlank(), "new Stage 3 world did not return a seed");
            return response.trim();
        }

        private String prepareFixture() throws IOException {
            String forceLoad = execute("forceload add 0 0 143 15");
            String clearAHigh = execute("fill 0 80 0 79 95 15 minecraft:air");
            String clearAUpper = execute("fill 0 96 0 79 110 15 minecraft:air");
            String clearBHigh = execute("fill 80 80 0 143 95 15 minecraft:air");
            String clearBUpper = execute("fill 80 96 0 143 110 15 minecraft:air");
            String lowerPlatform = execute("fill 0 62 0 143 62 15 minecraft:stone");
            String upperPlatform = execute("fill 0 79 0 143 79 15 minecraft:stone");
            String spawn = execute("setworldspawn 8 80 8");
            requireFixtureApplied("clearAHigh", clearAHigh);
            requireFixtureApplied("clearAUpper", clearAUpper);
            requireFixtureApplied("clearBHigh", clearBHigh);
            requireFixtureApplied("clearBUpper", clearBUpper);
            requireFixtureApplied("lowerPlatform", lowerPlatform);
            requireFixtureApplied("upperPlatform", upperPlatform);
            return "forceLoad=" + forceLoad.trim()
                + " | clearAHigh=" + clearAHigh.trim()
                + " | clearAUpper=" + clearAUpper.trim()
                + " | clearBHigh=" + clearBHigh.trim()
                + " | clearBUpper=" + clearBUpper.trim()
                + " | lowerPlatform=" + lowerPlatform.trim()
                + " | upperPlatform=" + upperPlatform.trim()
                + " | spawn=" + spawn.trim();
        }

        private String prepareReadinessSetup() throws IOException {
            String spawn = execute("setworldspawn 8 80 8");
            requireFixtureApplied("spawn", spawn);
            return "spawn=" + spawn.trim() + " targetChunkOutsideSpawnArea=true";
        }

        private static void requireFixtureApplied(String name, String response) {
            require(response != null && !response.isBlank() && !response.contains("not loaded"),
                "fresh-world fixture command " + name + " was not applied: " + response);
        }

        private String execute(String command) throws IOException {
            return RconClient.execute("127.0.0.1", rconPort(), rconPassword(), command);
        }

        private String serverPosition() throws IOException {
            return execute("data get entity " + TEST_USERNAME + " Pos");
        }

        private String targetPosition(String tag) throws IOException {
            return execute("data get entity @e[type=minecraft:armor_stand,tag=" + tag + ",limit=1] Pos");
        }

        private boolean stopNormally(ClientGameTestContext context) {
            if (!isAlive()) {
                cleanStop = true;
                return true;
            }
            try {
                execute("stop");
            } catch (IOException ignored) {
                // RCON commonly closes its socket while the normal stop command is completing.
            }
            for (int tick = 0; tick < 600; tick++) {
                if (!isAlive()) {
                    cleanStop = true;
                    return true;
                }
                context.waitTick();
            }
            return false;
        }

        private void stopQuietly(ClientGameTestContext context) {
            if (!isAlive()) return;
            try {
                execute("stop");
            } catch (IOException ignored) {
                // Cleanup continues to the process wait below.
            }
            for (int tick = 0; tick < 120 && isAlive(); tick++) context.waitTick();
            if (isAlive()) process.destroy();
            for (int tick = 0; tick < 60 && isAlive(); tick++) context.waitTick();
            if (isAlive()) process.destroyForcibly();
        }

        private boolean isAlive() { return process != null && process.isAlive(); }

        private String readLatestLog() {
            try {
                return Files.exists(latestLog) ? Files.readString(latestLog) : "";
            } catch (IOException ignored) {
                return "";
            }
        }

        private String readProcessLog() {
            try {
                return Files.exists(processLog) ? Files.readString(processLog) : "";
            } catch (IOException ignored) {
                return "";
            }
        }

        private String serverFailure(String message) {
            return message + "\nlatest.log tail:\n" + tail(readLatestLog())
                + "\nserver-process.log tail:\n" + tail(readFile(processLog));
        }

        private static String readFile(Path path) {
            try {
                return Files.exists(path) ? Files.readString(path) : "";
            } catch (IOException ignored) {
                return "";
            }
        }

        private static String tail(String text) {
            if (text.length() <= 5000) return text;
            return text.substring(text.length() - 5000);
        }

        private static int rconPort() { return Integer.parseInt(System.getProperty("phoenium.test.rconPort", "25575")); }
        private static String rconPassword() { return System.getProperty("phoenium.test.rconPassword", "phoenium-stage3-client"); }
    }

    private static final class RconClient {
        private RconClient() { }

        private static String execute(String host, int port, String password, String command) throws IOException {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1500);
                socket.setSoTimeout(4000);
                DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                OutputStream output = socket.getOutputStream();
                writePacket(output, 1, 3, password);
                Packet authentication = readPacket(input);
                if (authentication.requestId != 1) {
                    throw new IOException("RCON authentication failed with request id " + authentication.requestId);
                }
                writePacket(output, 2, 2, command);
                Packet response = readPacket(input);
                return response.body;
            }
        }

        private static void writePacket(OutputStream output, int requestId, int type, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            ByteBuffer packet = ByteBuffer.allocate(4 + 4 + 4 + payload.length + 2).order(ByteOrder.LITTLE_ENDIAN);
            packet.putInt(4 + 4 + payload.length + 2);
            packet.putInt(requestId);
            packet.putInt(type);
            packet.put(payload);
            packet.put((byte) 0);
            packet.put((byte) 0);
            output.write(packet.array());
            output.flush();
        }

        private static Packet readPacket(DataInputStream input) throws IOException {
            int length = readLittleEndianInt(input);
            if (length < 10 || length > 1_000_000) throw new IOException("invalid RCON packet length " + length);
            byte[] packet = input.readNBytes(length);
            if (packet.length != length) throw new EOFException("truncated RCON response");
            ByteBuffer body = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            int requestId = body.getInt();
            int type = body.getInt();
            byte[] text = new byte[length - 10];
            body.get(text);
            return new Packet(requestId, type, new String(text, StandardCharsets.UTF_8));
        }

        private static int readLittleEndianInt(DataInputStream input) throws IOException {
            int b0 = input.readUnsignedByte();
            int b1 = input.readUnsignedByte();
            int b2 = input.readUnsignedByte();
            int b3 = input.readUnsignedByte();
            return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }

        private record Packet(int requestId, int type, String body) { }
    }

    private static final class Snapshot {
        private final String raw;
        private final Map<String, Long> values = new LinkedHashMap<>();

        private Snapshot(String raw) {
            this.raw = raw == null ? "" : raw.replace('\0', ' ').trim();
            parseSegment(MOVE_SEGMENT, "move");
            parseSegment(INTERACT_SEGMENT, "interact");
            Matcher fields = NUMERIC_FIELD.matcher(this.raw);
            while (fields.find()) values.putIfAbsent(fields.group(1), Long.parseLong(fields.group(2)));
        }

        private void parseSegment(Pattern pattern, String prefix) {
            Matcher segment = pattern.matcher(raw);
            if (!segment.find()) return;
            for (String field : segment.group(1).split(",")) {
                String[] pair = field.split("=", 2);
                if (pair.length == 2) values.put(prefix + "." + pair[0].trim(), Long.parseLong(pair[1].trim()));
            }
        }
    }

    private static final class Evidence {
        private final String testUsername;
        private final String minecraftVersion;
        private final String javaVersion;
        private final String fabricLoaderVersion;
        private final String fabricApiVersion;
        private final String loomVersion;
        private final String gradleVersion;
        private final String gradleTask;
        private boolean firstServerProcessStarted;
        private boolean firstServerReady;
        private boolean secondServerProcessStarted;
        private boolean secondServerReady;
        private boolean productionServerModLoaded;
        private boolean realTcpConnectionStarted;
        private boolean clientReachedUsableWorld;
        private boolean clientPlayerObserved;
        private boolean serverPlayerObserved;
        private boolean sameOwnerMovementObserved;
        private boolean boundaryRejectionObserved;
        private boolean boundaryMovementStayedInOwnerA;
        private boolean sameRegionInteractionObserved;
        private boolean interactionLifecycleObserved;
        private boolean interactionRemovalObserved;
        private boolean crossOwnerInteractionAttempted;
        private boolean crossOwnerInteractionPacketObserved;
        private boolean crossOwnerInteractionRejected;
        private boolean crossOwnerInteractionNoForeignMutation;
        private boolean disconnectProjectionCleared;
        private boolean secondLifecycleReinitialized;
        private boolean clientDisconnectedCleanly;
        private boolean secondClientDisconnectedCleanly;
        private boolean firstServerStoppedCleanly;
        private boolean secondServerStoppedCleanly;
        private boolean secondDisconnectProjectionCleared;
        private boolean clientTerminatedCleanly;
        private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
        private String serverDirectory = "unknown";
        private String worldSeedFile = "unknown";
        private String firstWorldName = "unknown";
        private String firstWorldSeed = "unknown";
        private String firstFixturePreparation = "unknown";
        private String secondWorldName = "unknown";
        private String secondWorldSeed = "unknown";
        private String secondFixturePreparation = "unknown";
        private String firstServerLog = "unknown";
        private String secondServerLog = "unknown";
        private String serverPlayerName = "unknown";
        private String loginServerPosition = "unknown";
        private String sameOwnerClientMaxX = "unknown";
        private String sameOwnerServerPosition = "unknown";
        private String boundaryClientPosition = "unknown";
        private String boundaryServerPosition = "unknown";
        private String crossOwnerPlayerPosition = "unknown";
        private String crossOwnerTargetPosition = "unknown";
        private String crossOwnerPlayerChunk = "unknown";
        private String crossOwnerTargetChunk = "unknown";
        private String crossOwnerPlayerRoute = "unknown";
        private String crossOwnerTargetRoute = "unknown";
        private String crossOwnerDistance = "unknown";
        private String crossOwnerInteractionReason = "unknown";
        private String interactionTargetClientEntityCount = "unknown";
        private String interactionTargetRemovalResponse = "unknown";
        private String failure = "";
        private String cleanupFailure = "";

        private Evidence(String testUsername, String minecraftVersion, String javaVersion, String fabricLoaderVersion,
                          String fabricApiVersion, String loomVersion, String gradleVersion, String gradleTask) {
            this.testUsername = testUsername;
            this.minecraftVersion = minecraftVersion;
            this.javaVersion = javaVersion;
            this.fabricLoaderVersion = fabricLoaderVersion;
            this.fabricApiVersion = fabricApiVersion;
            this.loomVersion = loomVersion;
            this.gradleVersion = gradleVersion;
            this.gradleTask = gradleTask;
        }

        private static Evidence create() {
            return new Evidence(
                System.getProperty("phoenium.test.username", TEST_USERNAME),
                System.getProperty("phoenium.test.minecraftVersion", "unknown"),
                System.getProperty("phoenium.test.javaVersion", "unknown"),
                System.getProperty("phoenium.test.fabricLoaderVersion", "unknown"),
                System.getProperty("phoenium.test.fabricApiVersion", "unknown"),
                System.getProperty("phoenium.test.loomVersion", "unknown"),
                System.getProperty("phoenium.test.gradleVersion", "unknown"),
                System.getProperty("phoenium.test.gradleTask", "runStage3ClientGameTest"));
        }

        private String toJson() {
            StringBuilder json = new StringBuilder("{\n");
            field(json, "test", "P3-CLIENT");
            field(json, "clientProcessStarted", true);
            field(json, "firstServerProcessStarted", firstServerProcessStarted);
            field(json, "firstServerReady", firstServerReady);
            field(json, "secondServerProcessStarted", secondServerProcessStarted);
            field(json, "secondServerReady", secondServerReady);
            field(json, "productionServerModLoaded", productionServerModLoaded);
            field(json, "realTcpConnectionStarted", realTcpConnectionStarted);
            field(json, "clientReachedUsableWorld", clientReachedUsableWorld);
            field(json, "clientPlayerObserved", clientPlayerObserved);
            field(json, "serverPlayerObserved", serverPlayerObserved);
            field(json, "sameOwnerMovementObserved", sameOwnerMovementObserved);
            field(json, "boundaryRejectionObserved", boundaryRejectionObserved);
            field(json, "boundaryMovementStayedInOwnerA", boundaryMovementStayedInOwnerA);
            field(json, "sameRegionInteractionObserved", sameRegionInteractionObserved);
            field(json, "interactionLifecycleObserved", interactionLifecycleObserved);
            field(json, "interactionRemovalObserved", interactionRemovalObserved);
            field(json, "crossOwnerInteractionAttempted", crossOwnerInteractionAttempted);
            field(json, "crossOwnerInteractionPacketObserved", crossOwnerInteractionPacketObserved);
            field(json, "crossOwnerInteractionRejected", crossOwnerInteractionRejected);
            field(json, "crossOwnerInteractionNoForeignMutation", crossOwnerInteractionNoForeignMutation);
            field(json, "disconnectProjectionCleared", disconnectProjectionCleared);
            field(json, "secondLifecycleReinitialized", secondLifecycleReinitialized);
            field(json, "clientDisconnectedCleanly", clientDisconnectedCleanly);
            field(json, "secondClientDisconnectedCleanly", secondClientDisconnectedCleanly);
            field(json, "firstServerStoppedCleanly", firstServerStoppedCleanly);
            field(json, "secondServerStoppedCleanly", secondServerStoppedCleanly);
            field(json, "secondDisconnectProjectionCleared", secondDisconnectProjectionCleared);
            field(json, "clientTerminatedCleanly", clientTerminatedCleanly);
            field(json, "testUsername", testUsername);
            field(json, "serverPlayerName", serverPlayerName);
            field(json, "minecraftVersion", minecraftVersion);
            field(json, "javaVersion", javaVersion);
            field(json, "fabricLoaderVersion", fabricLoaderVersion);
            field(json, "fabricApiVersion", fabricApiVersion);
            field(json, "loomVersion", loomVersion);
            field(json, "gradleVersion", gradleVersion);
            field(json, "gradleTask", gradleTask);
            field(json, "serverDirectory", serverDirectory);
            field(json, "worldSeedFile", worldSeedFile);
            field(json, "firstWorldName", firstWorldName);
            field(json, "firstWorldSeed", firstWorldSeed);
            field(json, "firstFixturePreparation", firstFixturePreparation);
            field(json, "secondWorldName", secondWorldName);
            field(json, "secondWorldSeed", secondWorldSeed);
            field(json, "secondFixturePreparation", secondFixturePreparation);
            field(json, "firstServerLog", firstServerLog);
            field(json, "secondServerLog", secondServerLog);
            field(json, "loginServerPosition", loginServerPosition);
            field(json, "sameOwnerClientMaxX", sameOwnerClientMaxX);
            field(json, "sameOwnerServerPosition", sameOwnerServerPosition);
            field(json, "boundaryClientPosition", boundaryClientPosition);
            field(json, "boundaryServerPosition", boundaryServerPosition);
            field(json, "crossOwnerPlayerPosition", crossOwnerPlayerPosition);
            field(json, "crossOwnerTargetPosition", crossOwnerTargetPosition);
            field(json, "crossOwnerPlayerChunk", crossOwnerPlayerChunk);
            field(json, "crossOwnerTargetChunk", crossOwnerTargetChunk);
            field(json, "crossOwnerPlayerRoute", crossOwnerPlayerRoute);
            field(json, "crossOwnerTargetRoute", crossOwnerTargetRoute);
            field(json, "crossOwnerDistance", crossOwnerDistance);
            field(json, "interactionTargetClientEntityCount", interactionTargetClientEntityCount);
            field(json, "interactionTargetRemovalResponse", interactionTargetRemovalResponse);
            field(json, "crossOwnerInteractionReason", crossOwnerInteractionReason);
            field(json, "microsoftAuthentication", false);
            field(json, "minecraftLauncher", false);
            field(json, "microsoftStoreLauncher", false);
            field(json, "guiAutomation", false);
            field(json, "productionModServerOnly", true);
            field(json, "failure", failure);
            field(json, "cleanupFailure", cleanupFailure);
            json.append(",\n");
            json.append("  \"diagnostics\": {");
            boolean firstSnapshot = true;
            for (Map.Entry<String, Snapshot> entry : snapshots.entrySet()) {
                if (!firstSnapshot) json.append(",");
                json.append("\n    ").append(json(entry.getKey())).append(": {");
                json.append("\n      \"raw\": ").append(json(entry.getValue().raw)).append(",\n      \"fields\": {");
                boolean firstValue = true;
                for (Map.Entry<String, Long> value : entry.getValue().values.entrySet()) {
                    if (!firstValue) json.append(",");
                    json.append("\n        ").append(json(value.getKey())).append(": ").append(value.getValue());
                    firstValue = false;
                }
                if (!entry.getValue().values.isEmpty()) json.append("\n      ");
                json.append("}\n    }");
                firstSnapshot = false;
            }
            if (!snapshots.isEmpty()) json.append("\n  ");
            json.append("}\n}\n");
            return json.toString();
        }

        private String worldSeedText() {
            return "Phoenium Stage 3 disposable world record\n"
                + "Each lifecycle uses a unique level-name; configured diagnostic seed=" + diagnosticSeed() + ".\n"
                + "Blank means ordinary fresh-seed acceptance; nonblank means diagnostic reproduction only.\n"
                + "The /seed response is retained here and in STAGE3-CLIENT.json.\n\n"
                + "firstWorldName=" + firstWorldName + "\n"
                + "firstWorldSeed=" + firstWorldSeed + "\n"
                + "firstFixturePreparation=" + firstFixturePreparation + "\n\n"
                + "secondWorldName=" + secondWorldName + "\n"
                + "secondWorldSeed=" + secondWorldSeed + "\n"
                + "secondFixturePreparation=" + secondFixturePreparation + "\n";
        }

        private static void field(StringBuilder json, String name, boolean value) {
            fieldPrefix(json, name);
            json.append(value);
        }

        private static void field(StringBuilder json, String name, String value) {
            fieldPrefix(json, name);
            json.append(Stage3ClientTest.json(value));
        }

        private static void fieldPrefix(StringBuilder json, String name) {
            if (json.length() > 2) json.append(",\n");
            json.append("  ").append(Stage3ClientTest.json(name)).append(": ");
        }
    }
}
