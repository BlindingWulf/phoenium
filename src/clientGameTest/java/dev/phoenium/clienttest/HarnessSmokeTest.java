package dev.phoenium.clienttest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * HARNESS-SMOKE-01: prove the account-free client-to-dedicated-server lifecycle.
 *
 * This class lives in the clientGameTest source set and is never included in the
 * production Phoenium JAR. The server is started and stopped by Fabric's official
 * Client GameTest API; all player input is sent through TestInput.
 */
public final class HarnessSmokeTest implements FabricClientGameTest {
    private static final String TEST_USERNAME = "PhoeniumTest0";

    @Override
    public void runTest(ClientGameTestContext context) {
        if ("fullacceptance".equals(System.getProperty("phoenium.test.mode"))
                || "stage3".equals(System.getProperty("phoenium.test.mode"))
                || "chunktrace".equals(System.getProperty("phoenium.test.mode"))
                || "chunkreadiness".equals(System.getProperty("phoenium.test.mode"))) {
            new Stage3ClientTest().runTest(context);
            return;
        }
        runSmokeTest(context);
    }

    private void runSmokeTest(ClientGameTestContext context) {
        Evidence evidence = new Evidence();
        TestDedicatedServerContext server = null;
        TestDedicatedServerConnection connection = null;

        try {
            evidence.clientProcessStarted = true;
            evidence.testUsername = System.getProperty("phoenium.test.username", TEST_USERNAME);
            evidence.minecraftVersion = System.getProperty("phoenium.test.minecraftVersion", "unknown");
            evidence.javaVersion = System.getProperty("phoenium.test.javaVersion", "unknown");
            evidence.fabricLoaderVersion = System.getProperty("phoenium.test.fabricLoaderVersion", "unknown");
            evidence.fabricApiVersion = System.getProperty("phoenium.test.fabricApiVersion", "unknown");
            evidence.loomVersion = System.getProperty("phoenium.test.loomVersion", "unknown");
            evidence.gradleVersion = System.getProperty("phoenium.test.gradleVersion", "unknown");
            evidence.gradleTask = System.getProperty("phoenium.test.gradleTask", "runClientGameTest");
            evidence.microsoftAuthentication = false;
            evidence.minecraftLauncher = false;
            evidence.microsoftStoreLauncher = false;
            evidence.guiAutomation = false;
            evidence.productionModServerOnly = true;

            Properties serverProperties = new Properties();
            serverProperties.setProperty("level-name", "HARNESS-SMOKE-01-world");
            serverProperties.setProperty("online-mode", "false");
            serverProperties.setProperty("server-port", "25565");
            serverProperties.setProperty("motd", "Phoenium HARNESS-SMOKE-01");
            serverProperties.setProperty("spawn-protection", "0");
            serverProperties.setProperty("max-players", "1");
            serverProperties.setProperty("gamemode", "creative");
            serverProperties.setProperty("force-gamemode", "true");
            serverProperties.setProperty("allow-flight", "true");
            serverProperties.setProperty("spawn-npcs", "false");
            serverProperties.setProperty("spawn-animals", "false");
            serverProperties.setProperty("view-distance", "5");
            serverProperties.setProperty("simulation-distance", "5");

            writeLocalTestEula();
            server = context.worldBuilder().createServer(serverProperties);
            evidence.dedicatedServerStarted = true;

            connection = server.connect();
            evidence.realTcpConnectionStarted = true;
            connection.waitForChunksDownload(600);
            evidence.clientReachedUsableWorld = true;

            final TestDedicatedServerContext activeServer = server;
            final TestDedicatedServerConnection activeConnection = connection;
            LocalPlayer clientPlayer = context.computeOnClient(ignored -> activeConnection.getClientPlayer());
            ServerPlayer serverPlayer = activeServer.computeOnServer(ignored -> activeConnection.getServerPlayer());
            if (clientPlayer == null) {
                throw new AssertionError("Client GameTest connected without a LocalPlayer");
            }
            if (serverPlayer == null) {
                throw new AssertionError("Dedicated server did not expose a ServerPlayer after login");
            }
            evidence.clientPlayerObserved = true;
            evidence.serverPlayerObserved = true;
            evidence.observedClientName = clientPlayer.getName().getString();
            evidence.observedServerName = serverPlayer.getName().getString();

            TestInput input = context.getInput();
            input.holdKey(options -> options.keyUp);
            context.waitTicks(10);
            input.releaseKey(options -> options.keyUp);
            connection.waitForServerboundPackets();
            evidence.programmaticInputExercised = true;

            context.waitTicks(5);
            evidence.clientRemainedConnected = context.computeOnClient(ignored -> activeConnection.getClientPlayer() != null);
        } finally {
            if (connection != null) {
                connection.close();
                evidence.clientDisconnectedCleanly = true;
            }
            if (server != null) {
                server.close();
                evidence.serverStoppedCleanly = true;
            }
            evidence.clientTerminatedCleanly = true;
            writeEvidence(evidence);
        }
    }

    private static void writeEvidence(Evidence evidence) {
        String configuredPath = System.getProperty(
                "phoenium.client.gametest.evidence",
                "build/client-gametest/evidence/HARNESS-SMOKE-01.json");
        Path path = Path.of(configuredPath);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, evidence.toJson());
        } catch (IOException exception) {
            throw new AssertionError("Unable to write client GameTest evidence to " + path, exception);
        }
    }

    private static void writeLocalTestEula() {
        try {
            Files.writeString(Path.of("eula.txt"), "# Disposable automated test server\neula=true\n");
        } catch (IOException exception) {
            throw new AssertionError("Unable to prepare the disposable test-server EULA", exception);
        }
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class Evidence {
        private boolean clientProcessStarted;
        private boolean dedicatedServerStarted;
        private boolean realTcpConnectionStarted;
        private boolean clientReachedUsableWorld;
        private boolean clientPlayerObserved;
        private boolean serverPlayerObserved;
        private boolean programmaticInputExercised;
        private boolean clientRemainedConnected;
        private boolean clientDisconnectedCleanly;
        private boolean serverStoppedCleanly;
        private boolean clientTerminatedCleanly;
        private boolean microsoftAuthentication;
        private boolean minecraftLauncher;
        private boolean microsoftStoreLauncher;
        private boolean guiAutomation;
        private boolean productionModServerOnly;
        private String testUsername = "unknown";
        private String observedClientName = "unknown";
        private String observedServerName = "unknown";
        private String minecraftVersion = "unknown";
        private String javaVersion = "unknown";
        private String fabricLoaderVersion = "unknown";
        private String fabricApiVersion = "unknown";
        private String loomVersion = "unknown";
        private String gradleVersion = "unknown";
        private String gradleTask = "unknown";

        private String toJson() {
            return "{\n"
                    + "  \"test\": \"HARNESS-SMOKE-01\",\n"
                    + "  \"clientProcessStarted\": " + clientProcessStarted + ",\n"
                    + "  \"dedicatedServerStarted\": " + dedicatedServerStarted + ",\n"
                    + "  \"realTcpConnectionStarted\": " + realTcpConnectionStarted + ",\n"
                    + "  \"clientReachedUsableWorld\": " + clientReachedUsableWorld + ",\n"
                    + "  \"clientPlayerObserved\": " + clientPlayerObserved + ",\n"
                    + "  \"serverPlayerObserved\": " + serverPlayerObserved + ",\n"
                    + "  \"programmaticInputExercised\": " + programmaticInputExercised + ",\n"
                    + "  \"clientRemainedConnected\": " + clientRemainedConnected + ",\n"
                    + "  \"clientDisconnectedCleanly\": " + clientDisconnectedCleanly + ",\n"
                    + "  \"serverStoppedCleanly\": " + serverStoppedCleanly + ",\n"
                    + "  \"clientTerminatedCleanly\": " + clientTerminatedCleanly + ",\n"
                    + "  \"testUsername\": " + json(testUsername) + ",\n"
                    + "  \"observedClientName\": " + json(observedClientName) + ",\n"
                    + "  \"observedServerName\": " + json(observedServerName) + ",\n"
                    + "  \"minecraftVersion\": " + json(minecraftVersion) + ",\n"
                    + "  \"javaVersion\": " + json(javaVersion) + ",\n"
                    + "  \"fabricLoaderVersion\": " + json(fabricLoaderVersion) + ",\n"
                    + "  \"fabricApiVersion\": " + json(fabricApiVersion) + ",\n"
                    + "  \"loomVersion\": " + json(loomVersion) + ",\n"
                    + "  \"gradleVersion\": " + json(gradleVersion) + ",\n"
                    + "  \"gradleTask\": " + json(gradleTask) + ",\n"
                    + "  \"microsoftAuthentication\": " + microsoftAuthentication + ",\n"
                    + "  \"minecraftLauncher\": " + minecraftLauncher + ",\n"
                    + "  \"microsoftStoreLauncher\": " + microsoftStoreLauncher + ",\n"
                    + "  \"guiAutomation\": " + guiAutomation + ",\n"
                    + "  \"productionModServerOnly\": " + productionModServerOnly + "\n"
                    + "}\n";
        }
    }
}
