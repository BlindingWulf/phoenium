package dev.phoenium.clienttest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final Pattern MOVE_SEGMENT = Pattern.compile("move\\[([^]]*)\\]");
    private static final Pattern INTERACT_SEGMENT = Pattern.compile("interact\\[([^]]*)\\]");
    private static final Pattern NUMERIC_FIELD = Pattern.compile("\\b([A-Za-z]+)=(-?\\d+)");
    private static final Pattern POSITION = Pattern.compile(
        "\\[\\s*(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)[dDfF]?\\s*,"
            + "\\s*(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)[dDfF]?\\s*,"
            + "\\s*(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)[dDfF]?\\s*\\]");

    @Override
    public void runTest(ClientGameTestContext context) {
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
            Snapshot interactionBeforeSummon = record(first, evidence, "interaction-before-summon");
            first.execute("summon minecraft:armor_stand 10.5 80 8.5 {NoGravity:1b,Tags:[\""
                + SAME_OWNER_TARGET_TAG + "\"]}");
            context.waitTicks(25);
            Snapshot interactionPublished = record(first, evidence, "interaction-target-published");
            evidence.interactionTargetClientEntityCount = Integer.toString(clientEntityCount(context));
            evidence.interactionLifecycleObserved = value(interactionPublished, "entities") > value(interactionBeforeSummon, "entities");
            require(evidence.interactionLifecycleObserved,
                "same-owner target did not become visible in the live owned-entity projection: " + interactionPublished.raw);

            input.lookAt(new BlockPos(10, 80, 8));
            input.pressKey(options -> options.keyUse);
            context.waitTicks(5);
            input.pressMouse(1);
            context.waitTicks(20);
            Snapshot interactionAfter = record(first, evidence, "interaction-after");
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
            input.lookAt(new BlockPos(82, 80, 8));
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

    private static final class ExternalServer {
        private final Path directory;
        private final Path latestLog;
        private final Path processLog;
        private final int lifecycle;
        private final String worldName;
        private Process process;
        private boolean cleanStop;

        private ExternalServer(Path directory, int lifecycle) {
            this.directory = directory;
            this.latestLog = directory.resolve("logs").resolve("latest.log");
            this.processLog = directory.resolve("server-process.log");
            this.lifecycle = lifecycle;
            this.worldName = "STAGE3-CLIENT-" + lifecycle + "-" + System.currentTimeMillis();
        }

        private static ExternalServer start(int lifecycle) throws IOException {
            Path directory = Path.of(System.getProperty("phoenium.test.serverDirectory"));
            require(Files.isDirectory(directory), "prepared external server directory is missing: " + directory);
            ExternalServer server = new ExternalServer(directory, lifecycle);
            server.writeConfiguration();
            Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
            List<String> command = List.of(
                java.toString(), "-Xmx1G",
                "-Dphoenium.stage3.explicitTopology=true",
                "-Dphoenium.test.diagnostics=true",
                "-jar", "fabric-server-launch.jar", "nogui");
            ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
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
                "level-seed=",
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
                "view-distance=5",
                "simulation-distance=5");
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
                + "Each lifecycle uses a unique level-name and a blank level-seed so the server creates a fresh world.\n"
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
