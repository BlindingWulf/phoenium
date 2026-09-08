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

import java.io.IOException;
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

    @Override public void runTest(ClientGameTestContext context) {
        Path directory = Path.of(System.getProperty("phoenium.test.serverDirectory"));
        Path trace = Path.of(System.getProperty("phoenium.stage4.tracePath",
            directory.resolve("stage4-trace.json").toString()));
        Process server = null;
        boolean connected = false;
        try {
            configure(directory);
            List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString(),
                "-Xmx2G", "-Dphoenium.test.diagnostics=true",
                "-Dphoenium.stage4.tracePath=" + trace.toAbsolutePath(), "-jar",
                "fabric-server-launch.jar", "nogui");
            Path log = directory.resolve("stage4-client-server.log");
            server = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            waitForReady(context, server, log);
            connectClient(context);
            connected = true;
            command(server, "stage4trace run");
            command(server, "summon minecraft:armor_stand 72 65 8 {NoGravity:1b,Invulnerable:1b,Tags:[\"stage4_trace_a\"]}");
            command(server, "summon minecraft:armor_stand 84 65 8 {NoGravity:1b,Invulnerable:1b,Tags:[\"stage4_trace_b\"]}");
            command(server, "tp " + USER + " 72.5 65 8.5");
            context.waitTicks(80);
            command(server, "save-all flush");
            context.waitTicks(30);
            disconnectClient(context);
            connected = false;
            context.waitTicks(30);
            command(server, "stage4trace finish");
            context.waitTicks(10);
            command(server, "stop");
            waitForExit(context, server);
            writeEvidence(trace, log, true, server.exitValue());
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

    private static void configure(Path directory) throws IOException {
        String viewDistance = System.getProperty("phoenium.stage4.viewDistance", "8");
        String simulationDistance = System.getProperty("phoenium.stage4.simulationDistance", "8");
        Files.writeString(directory.resolve("eula.txt"), "eula=true\n",
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(directory.resolve("server.properties"), String.join("\n",
            "level-name=stage4-client-world", "level-seed=444444", "level-type=minecraft:flat",
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

    private static String read(Path path) {
        try { return Files.exists(path) ? Files.readString(path) : ""; }
        catch (IOException failure) { return failure.toString(); }
    }

    private static void command(Process server, String value) throws IOException {
        server.getOutputStream().write((value + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.getOutputStream().flush();
    }

    private static void connectClient(ClientGameTestContext context) {
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

    private static void writeEvidence(Path trace, Path log, boolean connected, int exitCode) throws IOException {
        Path evidence = Path.of(System.getProperty("phoenium.client.gametest.evidence",
            trace.resolveSibling("STAGE4-CLIENT.json").toString()));
        Path parent = evidence.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        String viewDistance = System.getProperty("phoenium.stage4.viewDistance", "8");
        String simulationDistance = System.getProperty("phoenium.stage4.simulationDistance", "8");
        Files.writeString(evidence, "{\n" +
            "  \"kind\": \"stage4-account-free-client-trace\",\n" +
            "  \"username\": \"" + USER + "\",\n" +
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
