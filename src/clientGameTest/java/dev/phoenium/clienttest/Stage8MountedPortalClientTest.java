package dev.phoenium.clienttest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S8-F7 regression: a player riding a pig through a native Nether portal must be riding on BOTH sides.
 * The server-side riding check alone passed while the real client never saw the pig after arrival, so every
 * check here pairs the server's passenger relation with the client's own LocalPlayer.getVehicle() and the
 * pig's presence in the client level. Covers entry, return, dismount/remount, and reconnect while mounted.
 * Real 26.2 client over TCP to a separate offline dedicated server running the production Phoenium JAR.
 */
public final class Stage8MountedPortalClientTest {
    static final String USER = "PhoeniumTest0", OW = "minecraft:overworld", NE = "minecraft:the_nether", TAG = "s8f7_pig";
    static final int SX = 2000, SY = 150, SZ = 2000, NX = SX / 8 + 40, NY = 100, NZ = SZ / 8;
    /** Vanilla pairs the vehicle within a few ticks of arrival (control: 0.06 s); 5 s is generous, not a delay. */
    static final int MOUNT_DEADLINE_TICKS = 100;
    static final Pattern UUID_INTS = Pattern.compile("\\[I;\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)\\]");

    final String address = System.getProperty("phoenium.test.serverAddress", "127.0.0.1:25566");
    final int rconPort = Integer.getInteger("phoenium.test.rconPort", 25576);
    final String rconPassword = System.getProperty("phoenium.test.rconPassword", "phoenium-s8f7-client");
    final Path evidence = Path.of(System.getProperty("phoenium.client.gametest.evidence",
        "build/client-gametest/evidence/S8F7-MOUNTED-PORTAL.json"));
    final Map<String, Object> receipt = new LinkedHashMap<>();
    final List<String> failures = new ArrayList<>();
    ClientGameTestContext context;
    Process server;
    Path serverLog;

    /** Vanilla 26.2 control: the same scenario on the same server base without the Phoenium JAR. */
    final boolean control = Boolean.getBoolean("phoenium.s8f7.control");

    /** vehicle = LocalPlayer.getVehicle(); hasEntity = pig spawned in the client level; carries = the client pig's passengers hold the local player. */
    record ClientView(String dimension, UUID vehicle, boolean hasEntity, boolean carries) { }

    public void runTest(ClientGameTestContext ctx) {
        context = ctx;
        receipt.put("test", "S8F7-MOUNTED-PORTAL");
        receipt.put("control", control);
        receipt.put("username", USER);
        receipt.put("microsoftAuthentication", false);
        receipt.put("startUtc", Instant.now().toString());
        try {
            startServer();
            connect();
            scene();
            UUID pig = mountAt(OW, "overworldMount");
            pig = crossMounted(pig, OW, SX + 6, SY, SZ - 4, NE, "netherEntry");
            pig = dismountRemount(pig);
            pig = crossMounted(pig, NE, NX - 4, NY, NZ, OW, "overworldReturn");
            reconnectMounted(pig);
            rcon("ride " + USER + " dismount");
            rcon("kill @e[tag=" + TAG + "]");
        } catch (Throwable failure) {
            failures.add("aborted: " + failure);
            receipt.put("abort", stackTrace(failure));
        } finally {
            try { disconnect(); } catch (Throwable ignored) { }
            stopServer();
            receipt.put("failures", failures);
            receipt.put("pass", failures.isEmpty());
            receipt.put("endUtc", Instant.now().toString());
            writeEvidence();
        }
        if (!failures.isEmpty()) throw new AssertionError("S8-F7 mounted portal regression failed: " + failures);
    }

    // ------------------------------------------------------------------------------------------------ scenario
    void scene() throws IOException {
        rcon("gamerule minecraft:spawn_mobs false");
        rcon("gamerule minecraft:mob_griefing false");
        rcon("execute in " + OW + " run forceload add " + (SX - 32) + " " + (SZ - 32) + " " + (SX + 32) + " " + (SZ + 32));
        context.waitTicks(30);
        rcon("execute in " + OW + " run fill " + (SX - 16) + " " + (SY - 1) + " " + (SZ - 16) + " " + (SX + 16) + " " + (SY + 8) + " " + (SZ + 16) + " minecraft:air");
        rcon("execute in " + OW + " run fill " + (SX - 16) + " " + (SY - 1) + " " + (SZ - 16) + " " + (SX + 16) + " " + (SY - 1) + " " + (SZ + 16) + " minecraft:stone");
        rcon("gamemode survival " + USER);
        rcon("effect give " + USER + " minecraft:resistance infinite 4 true");
        rcon("effect give " + USER + " minecraft:fire_resistance infinite 0 true");
        // The native lanes' enclosed Nether platform; vanilla creates the arrival portal, the return portal is built here later.
        rcon("execute in " + NE + " run forceload add " + (NX - 16) + " " + (NZ - 16) + " " + (NX + 16) + " " + (NZ + 16));
        context.waitTicks(30);
        rcon("execute in " + NE + " run fill " + (NX - 9) + " " + (NY - 2) + " " + (NZ - 9) + " " + (NX + 9) + " " + (NY + 7) + " " + (NZ + 9) + " minecraft:obsidian hollow");
        rcon("execute in " + NE + " run fill " + (NX - 8) + " " + (NY - 1) + " " + (NZ - 8) + " " + (NX + 8) + " " + (NY - 1) + " " + (NZ + 8) + " minecraft:obsidian");
        int px = SX + 6, pz = SZ - 4;
        rcon("execute in " + OW + " run summon minecraft:pig " + (px + 1) + " " + SY + " " + (pz + 0.5)
            + " {NoAI:1b,Invulnerable:1b,Tags:[\"" + TAG + "\"],equipment:{saddle:{id:\"minecraft:saddle\",count:1}}}");
        rcon("execute in " + OW + " run tp " + USER + " " + (px + 1) + " " + SY + " " + (pz + 1.5));
        context.waitTicks(20);
    }

    UUID mountAt(String dimension, String check) throws IOException {
        UUID pig = pigUuid(dimension);
        receipt.put(check + "-command", rcon("ride " + USER + " mount @e[tag=" + TAG + ",limit=1]"));
        requireMounted(check, dimension, pig);
        return pig;
    }

    UUID crossMounted(UUID before, String from, int x, int y, int z, String to, String check) throws IOException {
        portal(from, x, y, z);
        int waited = 0;
        while (!to.equals(view(before).dimension()) && waited < 800) { context.waitTick(); waited++; }
        receipt.put(check + "-arrivalTicks", waited);
        if (!to.equals(view(before).dimension())) { failures.add(check + ": client never reached " + to); return before; }
        UUID pig = pigUuid(to);
        receipt.put(check + "-pigUuidStable", before.equals(pig));
        requireMounted(check, to, pig);
        clearPortal(from, x, y, z);
        return pig;
    }

    UUID dismountRemount(UUID pig) throws IOException {
        rcon("ride " + USER + " dismount");
        int t = 0;
        while (t++ < MOUNT_DEADLINE_TICKS && (view(pig).vehicle() != null || serverRiding(NE))) context.waitTick();
        boolean off = view(pig).vehicle() == null && !serverRiding(NE);
        receipt.put("netherDismount-ticks", t);
        receipt.put("netherDismount-clientAndServerOff", off);
        if (!off) failures.add("netherDismount: client or server still riding");
        rcon("execute in " + NE + " run fill " + (NX - 8) + " " + NY + " " + (NZ - 8) + " " + (NX + 8) + " " + (NY + 6) + " " + (NZ + 8) + " minecraft:air");
        rcon("execute in " + NE + " run tp @e[tag=" + TAG + ",limit=1] " + (NX - 3) + " " + NY + " " + (NZ + 0.5));
        rcon("execute in " + NE + " run tp " + USER + " " + (NX - 3) + " " + NY + " " + (NZ + 1.5));
        // The unridden pig carries vanilla's 300-tick non-player portal cooldown from its arrival.
        context.waitTicks(340);
        return mountAt(NE, "netherRemount");
    }

    void reconnectMounted(UUID pig) throws IOException {
        disconnect();
        context.waitTicks(60);
        connect();
        UUID after = pigUuid(OW);
        receipt.put("reconnect-pigUuidStable", pig.equals(after));
        requireMounted("reconnect", OW, after);
    }

    /** Server passenger relation AND client vehicle AND client entity, each within the deadline, then stable for 1 s. */
    void requireMounted(String check, String dimension, UUID pig) throws IOException {
        Integer serverAt = null, clientVehicleAt = null, clientEntityAt = null, clientPassengerAt = null;
        List<String> samples = new ArrayList<>();
        for (int tick = 0; tick <= MOUNT_DEADLINE_TICKS; tick++) {
            ClientView v = view(pig);
            if (clientEntityAt == null && v.hasEntity()) clientEntityAt = tick;
            if (clientVehicleAt == null && pig.equals(v.vehicle())) clientVehicleAt = tick;
            if (clientPassengerAt == null && v.carries()) clientPassengerAt = tick;
            if (serverAt == null && tick % 5 == 0 && serverRiding(dimension)) serverAt = tick;
            if (tick % 10 == 0) samples.add(tick + ":" + v.dimension() + "/" + v.vehicle() + "/" + v.hasEntity() + "/" + v.carries());
            if (serverAt != null && clientVehicleAt != null && clientEntityAt != null && clientPassengerAt != null) break;
            context.waitTick();
        }
        boolean stable = true;
        if (serverAt != null && clientVehicleAt != null && clientEntityAt != null && clientPassengerAt != null) {
            context.waitTicks(20);
            ClientView v = view(pig);
            stable = pig.equals(v.vehicle()) && v.hasEntity() && v.carries() && serverRiding(dimension);
        }
        receipt.put(check + "-pig", pig.toString());
        receipt.put(check + "-serverRidingTick", serverAt);
        receipt.put(check + "-clientVehicleTick", clientVehicleAt);
        receipt.put(check + "-clientEntityTick", clientEntityAt);
        receipt.put(check + "-clientPassengerTick", clientPassengerAt);
        receipt.put(check + "-stable1s", stable);
        receipt.put(check + "-samples", samples);
        if (serverAt == null) failures.add(check + ": server does not report the player riding the pig");
        if (clientEntityAt == null) failures.add(check + ": client level never contained the pig " + pig);
        if (clientVehicleAt == null) failures.add(check + ": client LocalPlayer.getVehicle() never became the pig");
        if (clientPassengerAt == null) failures.add(check + ": client pig never listed the local player as its passenger");
        if (!stable) failures.add(check + ": mount state did not stay consistent for 1 s");
    }

    // ------------------------------------------------------------------------------------------------ observations
    ClientView view(UUID pig) {
        return context.computeOnClient(c -> {
            if (c.level == null || c.player == null) return new ClientView(null, null, false, false);
            Entity vehicle = c.player.getVehicle();
            boolean has = false, carries = false;
            if (pig != null) for (Entity e : c.level.entitiesForRendering()) if (pig.equals(e.getUUID())) {
                has = true;
                carries = e.getPassengers().contains(c.player);
                break;
            }
            return new ClientView(c.level.dimension().identifier().toString(), vehicle == null ? null : vehicle.getUUID(), has, carries);
        });
    }

    boolean serverRiding(String dimension) throws IOException {
        return rcon("execute in " + dimension + " positioned 0 100 0 as @e[tag=" + TAG + ",distance=..30000] on passengers if entity @s[name=" + USER + "]")
            .contains("passed");
    }

    UUID pigUuid(String dimension) throws IOException {
        String reply = rcon("execute in " + dimension + " positioned 0 100 0 run data get entity @e[tag=" + TAG + ",distance=..30000,limit=1] UUID");
        Matcher m = UUID_INTS.matcher(reply);
        if (!m.find()) throw new IllegalStateException("pig UUID not readable in " + dimension + ": " + reply);
        long hi = ((long) Integer.parseInt(m.group(1)) << 32) | (Integer.parseInt(m.group(2)) & 0xFFFFFFFFL);
        long lo = ((long) Integer.parseInt(m.group(3)) << 32) | (Integer.parseInt(m.group(4)) & 0xFFFFFFFFL);
        return new UUID(hi, lo);
    }

    void portal(String d, int x, int y, int z) throws IOException {
        rcon("execute in " + d + " run fill " + (x - 1) + " " + (y - 1) + " " + z + " " + (x + 2) + " " + (y + 3) + " " + z + " minecraft:obsidian");
        rcon("execute in " + d + " run fill " + x + " " + y + " " + z + " " + (x + 1) + " " + (y + 2) + " " + z + " minecraft:nether_portal[axis=x]");
    }

    void clearPortal(String d, int x, int y, int z) throws IOException {
        rcon("execute in " + d + " run fill " + (x - 1) + " " + (y - 1) + " " + z + " " + (x + 2) + " " + (y + 3) + " " + z + " minecraft:air");
    }

    // ------------------------------------------------------------------------------------------------ client
    void connect() {
        context.runOnClient(c -> {
            ServerData data = new ServerData("Phoenium S8-F7", address, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(c.gui.screen(), c, ServerAddress.parseString(address), data, false, null);
        });
        context.waitFor(c -> c.level != null && c.player != null, 1200);
        context.waitTicks(40);
    }

    void disconnect() {
        context.runOnClient(c -> {
            ClientPacketListener listener = c.getConnection();
            if (listener != null) listener.getConnection().disconnect(Component.literal("Phoenium S8-F7 test disconnect"));
            c.disconnect(new TitleScreen(), false);
        });
        context.waitFor(c -> c.level == null, 600);
    }

    // ------------------------------------------------------------------------------------------------ server
    void startServer() throws IOException {
        Path directory = Path.of(System.getProperty("phoenium.test.serverDirectory"));
        if (!Files.isDirectory(directory)) throw new IllegalStateException("prepared server directory missing: " + directory);
        String world = "s8f7-mount-" + System.currentTimeMillis();
        receipt.put("serverDirectory", directory.toAbsolutePath().toString());
        receipt.put("world", world);
        Files.writeString(directory.resolve("eula.txt"), "# Disposable S8-F7 evidence server\neula=true\n");
        String port = address.substring(address.lastIndexOf(':') + 1);
        Files.writeString(directory.resolve("server.properties"), String.join(System.lineSeparator(), List.of(
            "allow-flight=true", "difficulty=peaceful", "enable-rcon=true", "enforce-secure-profile=false",
            "gamemode=survival", "level-name=" + world, "level-seed=", "max-players=2", "online-mode=false",
            "server-ip=127.0.0.1", "server-port=" + port, "rcon.ip=127.0.0.1", "rcon.port=" + rconPort,
            "rcon.password=" + rconPassword, "spawn-protection=0", "view-distance=6", "simulation-distance=6")) + System.lineSeparator());
        Map<String, String> mods = new TreeMap<>();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory.resolve("mods"), "*.jar")) {
            for (Path jar : jars) mods.put(jar.getFileName().toString(), Files.size(jar) + " " + sha256(jar));
        }
        receipt.put("serverMods", mods);
        long phoeniumJars = mods.keySet().stream().filter(name -> name.startsWith("phoenium")).count();
        if (phoeniumJars != (control ? 0 : 1))
            throw new IllegalStateException((control ? "control" : "candidate") + " server has " + phoeniumJars + " Phoenium JARs: " + mods.keySet());
        Files.createDirectories(evidence.toAbsolutePath().getParent());
        serverLog = evidence.toAbsolutePath().getParent().resolve(world + "-server-console.log");
        receipt.put("serverLog", serverLog.toAbsolutePath().toString());
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        server = new ProcessBuilder(java.toString(), "-Xmx2G", "-Dphoenium.test.diagnostics=true",
                "-Dphoenium.gameplay.workers=" + Integer.getInteger("phoenium.s8f7.workers", 1),
                "-Djava.net.useSystemProxies=false", "-Dminecraft.api.session.host=http://127.0.0.1:9",
                "-Dminecraft.api.services.host=http://127.0.0.1:9", "-jar", "fabric-server-launch.jar", "nogui")
            .directory(directory.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(serverLog.toFile())).start();
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            String log = Files.exists(serverLog) ? Files.readString(serverLog) : "";
            if (!server.isAlive()) throw new IllegalStateException("server exited during startup");
            if (log.contains("Done (") && (control || log.contains("lifecycle=RUNNING"))) break;
            context.waitTicks(20);
        }
        String log = Files.readString(serverLog);
        Matcher started = Pattern.compile("PHOENIUM_STAGE2 started lifecycle=RUNNING[^\\r\\n]*").matcher(log);
        receipt.put("phoeniumStarted", started.find() ? started.group().substring(0, Math.min(200, started.group().length())) : null);
        if (!log.contains("Done (") || !control && receipt.get("phoeniumStarted") == null)
            throw new IllegalStateException("server did not reach Done with Phoenium RUNNING");
    }

    void stopServer() {
        if (server == null) return;
        try { rcon("stop"); } catch (IOException ignored) { }
        try {
            if (!server.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) server.destroyForcibly();
            receipt.put("serverExit", server.exitValue());
            String log = Files.readString(serverLog);
            Matcher move = Pattern.compile("packets=move\\[[^\\]]*\\]").matcher(log);
            String last = null;
            while (move.find()) last = move.group();
            receipt.put("phoeniumMoveCounters", last);
            receipt.put("serverCrashed", log.contains("Encountered an unexpected exception") || log.contains("crash-reports"));
        } catch (Exception e) {
            receipt.put("serverStopFailure", e.toString());
        }
    }

    String rcon(String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", rconPort), 2000);
            socket.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            write(out, 1, 3, rconPassword);
            if (read(in)[0] != 1) throw new IOException("RCON authentication failed");
            write(out, 2, 2, command);
            return new String(readBody(in), StandardCharsets.UTF_8);
        }
    }

    static void write(OutputStream out, int id, int type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(10 + payload.length).putInt(id).putInt(type).put(payload).put((byte) 0).put((byte) 0);
        out.write(b.array());
        out.flush();
    }

    static int[] read(DataInputStream in) throws IOException {
        ByteBuffer head = ByteBuffer.wrap(in.readNBytes(12)).order(ByteOrder.LITTLE_ENDIAN);
        int length = head.getInt(), id = head.getInt();
        in.readNBytes(length - 8);
        return new int[] {id};
    }

    static byte[] readBody(DataInputStream in) throws IOException {
        ByteBuffer head = ByteBuffer.wrap(in.readNBytes(12)).order(ByteOrder.LITTLE_ENDIAN);
        int length = head.getInt();
        byte[] rest = in.readNBytes(length - 8);
        return Arrays.copyOf(rest, Math.max(0, rest.length - 2));
    }

    static String sha256(Path file) throws IOException {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return java.util.HexFormat.of().withUpperCase().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------ evidence
    void writeEvidence() {
        try {
            Files.createDirectories(evidence.toAbsolutePath().getParent());
            Files.writeString(evidence, toJson(receipt) + "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot write " + evidence, e);
        }
    }

    static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringJoiner j = new StringJoiner(",\n ", "{\n ", "\n}");
            map.forEach((k, v) -> j.add(toJson(String.valueOf(k)) + ": " + toJson(v)));
            return j.toString();
        }
        if (value instanceof Collection<?> list) {
            StringJoiner j = new StringJoiner(", ", "[", "]");
            list.forEach(v -> j.add(toJson(v)));
            return j.toString();
        }
        String s = value.toString();
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    static String stackTrace(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }
}
