package dev.phoenium.clienttest;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;

import java.io.*;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S8-F7 regression and diagnosis: a player riding a pig through native Nether portals must be riding on BOTH sides,
 * promptly. The server-side riding check alone passed while the real client never (or only much later) saw the pig, so
 * every check pairs the server's passenger relation and position with the client's own view: the pig spawned in the
 * client level, LocalPlayer.getVehicle(), the client pig's passenger list, and client/server position agreement.
 *
 * Each check records its latency from the arrival reference (the client-received Respawn packet for a crossing, the
 * Login packet for a reconnect, the command for a remount) and the client-observed packet order (Respawn, teleport and
 * its ack, chunk batches, outbound movement, the pig's AddEntity and SetPassengers), so the same receipt from the vanilla
 * 26.2 control (no Phoenium JAR) is the comparison baseline. The observation window only bounds how long a missing sync
 * is watched.
 *
 * Modes: regression (default) = mount, three mounted round trips, movement input, dismount/remount, reconnect while
 * mounted in both dimensions. diagnose = after a mounted arrival, apply one stimulus at a time while the client is still
 * unsynchronized (idle, movement input, in-section nudge, section change, the pig's own AI wandering, dismount) to
 * identify what restores the client view; the pig's server section is logged against the recovery time.
 *
 * Real 26.2 client over TCP to a separate offline dedicated server.
 */
public final class Stage8MountedPortalClientTest {
    static final String USER = "PhoeniumTest0", OW = "minecraft:overworld", NE = "minecraft:the_nether", TAG = "s8f7_pig";
    static final int SX = 2000, SY = 150, SZ = 2000;
    static final int OBSERVE_TICKS = 200, STABLE_TICKS = 20;
    /**
     * One terrain for every arm. With a random seed the portal arrival sites differed per arm, and in one arm the pig
     * drifted off its arrival frame into ocean, where vanilla's underwater dismount (LivingEntity.baseTick ->
     * dismountsUnderwater -> stopRiding) unseated the rider. This is the seed of the first vanilla control run.
     */
    static final long LEVEL_SEED = -728736150286961811L;
    static final double POSITION_TOLERANCE = 0.5;
    static final Pattern UUID_INTS = Pattern.compile("\\[I;\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)\\]");
    static final Pattern DOUBLES = Pattern.compile("(-?\\d+(?:\\.\\d+)?(?:E-?\\d+)?)d");
    static final Pattern INT_DATA = Pattern.compile("entity data: (-?\\d+)");
    static final Field CHANNEL = field(Connection.class, "channel");
    static final Field PENDING = field(Minecraft.class, "pendingConnection");
    static final Field CONNECTING = field(ConnectScreen.class, "connection");

    final String address = System.getProperty("phoenium.test.serverAddress", "127.0.0.1:25566");
    final int rconPort = Integer.getInteger("phoenium.test.rconPort", 25576);
    final String rconPassword = System.getProperty("phoenium.test.rconPassword", "phoenium-s8f7-client");
    final Path evidence = Path.of(System.getProperty("phoenium.client.gametest.evidence",
        "build/client-gametest/evidence/S8F7-MOUNTED-PORTAL.json"));
    /** Vanilla 26.2 control: the same scenario on the same server base without the Phoenium JAR. */
    final boolean control = Boolean.getBoolean("phoenium.s8f7.control");
    final boolean diagnose = "diagnose".equals(System.getProperty("phoenium.s8f7.mode"));
    // Regression: the rider controls the pig (carrot on a stick), as players do. Diagnose: a passive pig, so the pairing
    // recovery trigger is observed without the rider-controlled vehicle-move path. Overridable with phoenium.s8f7.carrot.
    final boolean carrot = System.getProperty("phoenium.s8f7.carrot", "").isEmpty() ? !diagnose
        : Boolean.parseBoolean(System.getProperty("phoenium.s8f7.carrot"));
    final int roundTrips = Integer.getInteger("phoenium.s8f7.roundTrips", 3);
    final int aiTrials = Integer.getInteger("phoenium.s8f7.aiTrials", 3);
    final long t0 = System.nanoTime();
    final ConcurrentLinkedQueue<Event> trace = new ConcurrentLinkedQueue<>();
    final Map<String, Object> receipt = new LinkedHashMap<>();
    final List<Map<String, Object>> checks = new ArrayList<>();
    final List<String> failures = new ArrayList<>();
    ClientGameTestContext context;
    Process server;
    Path serverLog;

    /** One client-observed packet (in = clientbound, out = serverbound, both at the netty handler) or a test mark. */
    record Event(long nanos, String dir, String kind, int a, int b, String detail) { }

    /** vehicle = LocalPlayer.getVehicle(); hasPig = pig spawned in the client level; carries = client pig's passengers hold the local player. */
    record ClientView(long nanos, String dimension, int playerId, UUID vehicle, boolean hasPig, boolean carries, int pigId,
                      double px, double py, double pz, double pigX, double pigY, double pigZ) { }

    static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public void runTest(ClientGameTestContext ctx) {
        context = ctx;
        receipt.put("test", "S8F7-MOUNTED-PORTAL");
        receipt.put("mode", diagnose ? "diagnose" : "regression");
        receipt.put("riderControlsPig", carrot);
        receipt.put("control", control);
        receipt.put("username", USER);
        receipt.put("microsoftAuthentication", false);
        receipt.put("observeTicks", OBSERVE_TICKS);
        receipt.put("positionTolerance", POSITION_TOLERANCE);
        receipt.put("startUtc", Instant.now().toString());
        try {
            startServer();
            connect();
            context.waitTicks(40);
            scene();
            UUID pig = pigUuid(OW);
            receipt.put("pig", pig.toString());
            long mark = mark("overworldMount");
            rcon("ride " + USER + " mount @e[tag=" + TAG + ",limit=1]");
            observe("overworldMount", OW, pig, mark, null, OBSERVE_TICKS, true);
            if (diagnose) diagnose(pig);
            else regression(pig);
            rcon("ride " + USER + " dismount");
            rcon("kill @e[tag=" + TAG + "]");
        } catch (Throwable failure) {
            failures.add("aborted: " + failure);
            receipt.put("abort", stackTrace(failure));
        } finally {
            try { disconnect(); } catch (Throwable ignored) { }
            stopServer();
            receipt.put("checks", checks);
            receipt.put("failures", failures);
            receipt.put("pass", failures.isEmpty());
            receipt.put("endUtc", Instant.now().toString());
            writeEvidence();
        }
        if (!diagnose && !failures.isEmpty()) throw new AssertionError("S8-F7 mounted portal regression failed: " + failures);
    }

    // ------------------------------------------------------------------------------------------------ scenarios
    void regression(UUID pig) throws IOException {
        String dim = OW;
        for (int i = 1; i <= 2 * roundTrips; i++) {
            String to = OW.equals(dim) ? NE : OW;
            crossing("cross" + i + (NE.equals(to) ? "-nether" : "-overworld"), dim, to, pig, OBSERVE_TICKS, true);
            dim = to;
            if (i == 1) {
                inputHold("cross1-input", dim, pig);
                dismountRemount("netherDismount", "netherRemount", dim, pig);
            }
            if (i == 2) inputHold("cross2-input", dim, pig);
            if (i == 3) reconnect("reconnectNether", dim, pig);
        }
        reconnect("reconnectOverworld", dim, pig);
    }

    void diagnose(UUID pig) throws IOException {
        crossing("diag-entry", OW, NE, pig, 40, false);
        stimulus("D0-idle-60s", NE, pig, () -> { }, 1200, null);
        crossing("diag-return", NE, OW, pig, 40, false);
        stimulus("D1-input-forward-2s", OW, pig, () -> context.getInput().holdKeyFor(o -> o.keyUp, 40), 100, null);
        stimulus("D2-pig-nudge-same-section", OW, pig, () -> nudge(OW, false), 100, null);
        stimulus("D3-pig-section-change", OW, pig, () -> nudge(OW, true), 100, null);
        for (int k = 1; k <= aiTrials; k++) {
            crossing("diag-ai" + k + "-entry", OW, NE, pig, 20, false);
            crossing("diag-ai" + k + "-return", NE, OW, pig, 20, false);
            rcon("execute in " + OW + " run data merge entity @e[tag=" + TAG + ",limit=1] {NoAI:0b}");
            stimulus("D4-ai-wander-" + k, OW, pig, () -> { }, 2400, null);
            rcon("execute in " + OW + " run data merge entity @e[tag=" + TAG + ",limit=1] {NoAI:1b}");
        }
        crossing("diag-dismount-entry", OW, NE, pig, 20, false);
        stimulus("D5-dismount", NE, pig, () -> rcon("ride " + USER + " dismount"), 200, v -> v.hasPig() && v.vehicle() == null);
    }

    void scene() throws IOException {
        rcon("gamerule minecraft:spawn_mobs false");
        rcon("gamerule minecraft:mob_griefing false");
        rcon("execute in " + OW + " run forceload add " + (SX - 32) + " " + (SZ - 32) + " " + (SX + 32) + " " + (SZ + 32));
        context.waitTicks(30);
        rcon(fill(OW, SX - 16, SY - 1, SZ - 16, SX + 16, SY + 8, SZ + 16, "minecraft:air"));
        rcon(fill(OW, SX - 16, SY - 1, SZ - 16, SX + 16, SY - 1, SZ + 16, "minecraft:stone"));
        // Walls keep the AI-wandering pig of the diagnose mode on the platform.
        rcon(fill(OW, SX - 16, SY, SZ - 16, SX - 16, SY + 2, SZ + 16, "minecraft:glass"));
        rcon(fill(OW, SX + 16, SY, SZ - 16, SX + 16, SY + 2, SZ + 16, "minecraft:glass"));
        rcon(fill(OW, SX - 16, SY, SZ - 16, SX + 16, SY + 2, SZ - 16, "minecraft:glass"));
        rcon(fill(OW, SX - 16, SY, SZ + 16, SX + 16, SY + 2, SZ + 16, "minecraft:glass"));
        rcon("gamemode survival " + USER);
        rcon("effect give " + USER + " minecraft:resistance infinite 4 true");
        rcon("effect give " + USER + " minecraft:fire_resistance infinite 0 true");
        // A saddled pig takes its rider's movement input only while the rider holds a carrot on a stick.
        if (carrot) rcon("item replace entity " + USER + " weapon.mainhand with minecraft:carrot_on_a_stick");
        int px = SX + 6, pz = SZ - 4;
        rcon("execute in " + OW + " run summon minecraft:pig " + (px + 1) + " " + SY + " " + (pz + 0.5)
            + " {NoAI:1b,Invulnerable:1b,Tags:[\"" + TAG + "\"],equipment:{saddle:{id:\"minecraft:saddle\",count:1}}}");
        rcon("execute in " + OW + " run tp " + USER + " " + (px + 1) + " " + SY + " " + (pz + 1.5));
        context.waitTicks(20);
    }

    /**
     * Lights a portal around the pig where it stands and observes the arrival. Vanilla refreshes the portal cooldown
     * of an entity standing in a portal while on cooldown, so the portal is lit only once the pig's PortalCooldown has
     * run out (recorded: vanilla gives a player-ridden vehicle the player's 10 ticks). Afterwards the arrival portal is
     * unlit so the next departure is the only active portal under the pig.
     */
    Map<String, Object> crossing(String name, String from, String to, UUID pig, int window, boolean required) throws IOException {
        int cooldown = pigCooldown(from), cooldownWait = 0;
        while (pigCooldown(from) > 0 && cooldownWait < 400) { context.waitTick(); cooldownWait++; }
        double[] p = serverPig(from);
        if (p == null) throw new IllegalStateException(name + ": pig not found in " + from);
        int bx = (int) Math.floor(p[0] - 0.5), by = (int) Math.floor(p[1] + 1.0e-3), bz = (int) Math.floor(p[2]);
        long mark = mark(name);
        portal(from, bx, by, bz);
        int waited = 0;
        while (!to.equals(view(pig).dimension()) && waited < 800) { context.waitTick(); waited++; }
        if (!to.equals(view(pig).dimension())) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("check", name);
            r.put("arrived", false);
            r.put("pigPortalCooldownBefore", cooldown);
            r.put("pigPortalCooldownWaitTicks", cooldownWait);
            checks.add(r);
            failures.add(name + ": client never reached " + to);
            throw new IllegalStateException(name + ": client never reached " + to);
        }
        Map<String, Object> r = observe(name, to, pig, mark, "RESPAWN", window, required);
        r.put("portal", List.of(bx, by, bz));
        r.put("pigPortalCooldownBefore", cooldown);
        r.put("pigPortalCooldownWaitTicks", cooldownWait);
        r.put("arrivalWaitTicks", waited);
        r.put("pigUuidStable", pig.equals(pigUuid(to)));
        double[] q = serverPig(to);
        if (q != null) {
            int x = (int) Math.floor(q[0]), y = (int) Math.floor(q[1] + 1.0e-3), z = (int) Math.floor(q[2]);
            rcon(fill(to, x - 3, y - 2, z - 3, x + 3, y + 4, z + 3, "minecraft:air replace minecraft:nether_portal"));
            // After the observation: solid ground under the arrival, so the next steps never depend on the terrain.
            rcon(fill(to, x - 3, y - 1, z - 3, x + 3, y - 1, z + 3, "minecraft:stone replace #minecraft:replaceable"));
        }
        return r;
    }

    /** Movement input while riding: a vanilla rider stays seated and client and server positions keep agreeing. */
    void inputHold(String name, String dim, UUID pig) throws IOException {
        // A flat pad around the pig (also replacing the arrival portal frame) so the ridden pig can actually move.
        double[] at = serverPig(dim);
        if (at != null) {
            int x = (int) Math.floor(at[0]), y = (int) Math.floor(at[1] + 1e-3), z = (int) Math.floor(at[2]);
            rcon(fill(dim, x - 8, y, z - 8, x + 8, y + 3, z + 8, "minecraft:air"));
            rcon(fill(dim, x - 8, y - 1, z - 8, x + 8, y - 1, z + 8, "minecraft:stone"));
            context.waitTicks(5);
        }
        long mark = mark(name);
        ClientView before = view(pig);
        double[] serverBefore = serverPos(USER);
        context.getInput().holdKeyFor(o -> o.keyUp, 40);
        context.waitTicks(10);
        ClientView after = view(pig);
        double[] serverAfter = serverPos(USER);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("check", name);
        r.put("clientMoved", round(distance(before.px(), before.py(), before.pz(), after.px(), after.py(), after.pz())));
        r.put("serverMoved", serverBefore == null || serverAfter == null ? null
            : round(distance(serverBefore[0], serverBefore[1], serverBefore[2], serverAfter[0], serverAfter[1], serverAfter[2])));
        Double delta = serverAfter == null ? null : round(distance(after, serverAfter));
        r.put("positionDelta", delta);
        r.put("clientVehicleAfter", String.valueOf(after.vehicle()));
        r.put("carriesAfter", after.carries());
        r.put("serverRidingAfter", serverRiding(dim));
        r.put("packets", packets(mark, mark, pig, after.pigId(), after.playerId(), dim));
        checks.add(r);
        if (delta == null || delta > POSITION_TOLERANCE) failures.add(name + ": client/server position disagree by " + delta);
        Object serverMoved = r.get("serverMoved");
        if (!(r.get("clientMoved") instanceof Double cm) || cm < 1.0 || !(serverMoved instanceof Double moved) || moved < 1.0)
            failures.add(name + ": movement input did not move the ridden pig (client " + r.get("clientMoved") + ", server " + serverMoved + ")");
        if (!pig.equals(after.vehicle()) || !after.carries()) failures.add(name + ": client not riding the pig after movement input");
    }

    void dismountRemount(String offName, String onName, String dim, UUID pig) throws IOException {
        long mark = mark(offName);
        rcon("ride " + USER + " dismount");
        Long clientOff = null, serverOff = null, agree = null;
        for (int tick = 0; tick <= OBSERVE_TICKS; tick++) {
            ClientView v = view(pig);
            if (clientOff == null && v.vehicle() == null && !v.carries() && v.hasPig()) clientOff = v.nanos();
            if (tick % 5 == 0) {
                if (serverOff == null && !serverRiding(dim)) serverOff = System.nanoTime();
                double[] sp = serverPos(USER);
                if (agree == null && clientOff != null && sp != null && distance(v, sp) <= POSITION_TOLERANCE) agree = System.nanoTime();
            }
            if (clientOff != null && serverOff != null && agree != null) break;
            context.waitTick();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("check", offName);
        r.put("clientOffWithPigVisibleMs", ms(clientOff, mark));
        r.put("serverOffMs", ms(serverOff, mark));
        r.put("positionAgreeMs", ms(agree, mark));
        ClientView v = view(pig);
        r.put("packets", packets(mark, mark, pig, v.pigId(), v.playerId(), dim));
        checks.add(r);
        if (clientOff == null || serverOff == null || agree == null)
            failures.add(offName + ": dismount not synchronized (clientOff=" + clientOff + " serverOff=" + serverOff + " agree=" + agree + ")");
        context.waitTicks(20);
        long remount = mark(onName);
        rcon("ride " + USER + " mount @e[tag=" + TAG + ",limit=1]");
        observe(onName, dim, pig, remount, null, OBSERVE_TICKS, true);
    }

    /** Vanilla saves the ridden pig with the player on logout and respawns it (same UUID) on login. */
    void reconnect(String name, String dim, UUID pig) throws IOException {
        mark(name + "-disconnect");
        disconnect();
        context.waitTicks(60);
        long connecting = connect();
        UUID after = pigUuid(dim);
        Map<String, Object> r = observe(name, dim, after, connecting, "LOGIN", OBSERVE_TICKS, true);
        r.put("pigUuidStable", pig.equals(after));
        if (!pig.equals(after)) failures.add(name + ": pig UUID changed across the reconnect");
    }

    /** Diagnose: applies one stimulus only while the client view is still unsynchronized, then watches for recovery. */
    void stimulus(String name, String dim, UUID pig, IoAction action, int window, Predicate<ClientView> recovered) throws IOException {
        ClientView v = view(pig);
        if (recovered == null && synced(v, pig)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("check", name);
            r.put("skipped", "client already synchronized: " + sample(-1, v, serverPos(USER), serverPig(dim)));
            checks.add(r);
            return;
        }
        String before = sample(-1, v, serverPos(USER), serverPig(dim));
        long mark = mark(name);
        action.run();
        Map<String, Object> r = recovered == null ? observe(name, dim, pig, mark, null, window, false)
            : observeUntil(name, dim, pig, mark, window, recovered);
        r.put("before", before);
    }

    void nudge(String dim, boolean crossSection) throws IOException {
        double[] p = serverPig(dim);
        double inChunk = p[0] - 16.0 * Math.floor(p[0] / 16.0);
        double dx = crossSection ? (inChunk < 8.0 ? -(inChunk + 1.0) : 16.0 - inChunk + 1.0) : (inChunk < 8.0 ? 0.5 : -0.5);
        rcon("execute in " + dim + " as @e[tag=" + TAG + ",limit=1] at @s run tp @s ~" + dx + " ~ ~");
    }

    // ------------------------------------------------------------------------------------------------ observation
    static boolean synced(ClientView v, UUID pig) { return v.hasPig() && pig.equals(v.vehicle()) && v.carries(); }

    /**
     * Watches until the server relation, the client spawn, the client vehicle, the client passenger list and the
     * client/server position all agree (position counted once the client rides), then requires 1 s of stability.
     */
    Map<String, Object> observe(String name, String dim, UUID pig, long mark, String refKind, int window, boolean required) throws IOException {
        Long entityAt = null, vehicleAt = null, carriesAt = null, serverAt = null, positionAt = null;
        List<String> samples = new ArrayList<>();
        List<String> sectionChanges = new ArrayList<>();
        String lastSection = null;
        boolean done = false;
        int tick;
        ClientView v = null;
        for (tick = 0; tick <= window; tick++) {
            v = view(pig);
            if (entityAt == null && v.hasPig()) entityAt = v.nanos();
            if (vehicleAt == null && pig.equals(v.vehicle())) vehicleAt = v.nanos();
            if (carriesAt == null && v.carries()) carriesAt = v.nanos();
            // The server is polled every tick until riding and position agreement are seen (latency resolution), then every 5 ticks.
            if (tick % 5 == 0 || serverAt == null || vehicleAt != null && positionAt == null) {
                if (serverAt == null && serverRiding(dim)) serverAt = System.nanoTime();
                double[] sp = serverPos(USER);
                double[] pg = serverPig(dim);
                if (positionAt == null && vehicleAt != null && sp != null && distance(v, sp) <= POSITION_TOLERANCE) positionAt = System.nanoTime();
                if (pg != null) {
                    String section = Math.floorDiv((int) Math.floor(pg[0]), 16) + "," + Math.floorDiv((int) Math.floor(pg[1]), 16)
                        + "," + Math.floorDiv((int) Math.floor(pg[2]), 16);
                    if (lastSection != null && !section.equals(lastSection)) sectionChanges.add(ms(System.nanoTime(), mark) + "ms:" + section);
                    lastSection = section;
                }
                if (tick % 20 == 0) samples.add(sample(tick, v, sp, pg));
            }
            if (entityAt != null && vehicleAt != null && carriesAt != null && serverAt != null && positionAt != null) { done = true; break; }
            context.waitTick();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("check", name);
        r.put("dimension", dim);
        Long ref = refKind == null ? null : firstAfter(mark, e -> e.kind().equals(refKind));
        long base = ref != null ? ref : mark;
        r.put("reference", ref != null ? refKind : "mark");
        r.put("referenceAfterMarkMs", ms(base, mark));
        r.put("synchronized", done);
        r.put("observedTicks", tick);
        r.put("serverRidingMs", ms(serverAt, base));
        r.put("clientPigSpawnedMs", ms(entityAt, base));
        r.put("clientVehicleMs", ms(vehicleAt, base));
        r.put("clientPassengerMs", ms(carriesAt, base));
        r.put("positionAgreeMs", ms(positionAt, base));
        r.put("synchronizedMs", done ? ms(Collections.max(List.of(entityAt, vehicleAt, carriesAt, positionAt)), base) : null);
        boolean stable = false;
        if (done) {
            context.waitTicks(STABLE_TICKS);
            ClientView s = view(pig);
            double[] sp = serverPos(USER);
            Double delta = sp == null ? null : round(distance(s, sp));
            r.put("stablePositionDelta", delta);
            stable = synced(s, pig) && serverRiding(dim) && delta != null && delta <= POSITION_TOLERANCE;
            v = s;
        }
        r.put("stable1s", stable);
        r.put("lastView", sample(tick, v, serverPos(USER), serverPig(dim)));
        r.put("pigSectionChanges", sectionChanges);
        r.put("packets", packets(mark, base, pig, v.pigId(), v.playerId(), dim));
        r.put("samples", samples);
        checks.add(r);
        if (required && !done) {
            List<String> missing = new ArrayList<>();
            if (serverAt == null) missing.add("server riding");
            if (entityAt == null) missing.add("client pig spawned");
            if (vehicleAt == null) missing.add("client vehicle");
            if (carriesAt == null) missing.add("client passenger list");
            if (positionAt == null) missing.add("client/server position");
            failures.add(name + ": not synchronized within " + window + " ticks, missing " + missing);
        } else if (required && !stable) {
            failures.add(name + ": synchronized state did not hold for 1 s");
        }
        return r;
    }

    /** Diagnose only: watches for an arbitrary recovery condition (e.g. the pig visible after a dismount). */
    Map<String, Object> observeUntil(String name, String dim, UUID pig, long mark, int window, Predicate<ClientView> recovered) throws IOException {
        Long at = null;
        List<String> samples = new ArrayList<>();
        ClientView v = null;
        int tick;
        for (tick = 0; tick <= window; tick++) {
            v = view(pig);
            if (recovered.test(v)) { at = v.nanos(); break; }
            if (tick % 10 == 0) samples.add(sample(tick, v, serverPos(USER), serverPig(dim)));
            context.waitTick();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("check", name);
        r.put("dimension", dim);
        r.put("recoveredMs", ms(at, mark));
        r.put("observedTicks", tick);
        r.put("lastView", sample(tick, v, serverPos(USER), serverPig(dim)));
        r.put("packets", packets(mark, mark, pig, v.pigId(), v.playerId(), dim));
        r.put("samples", samples);
        checks.add(r);
        return r;
    }

    /** Client-observed packet order after {@code mark}, in milliseconds from {@code base}. */
    Map<String, Object> packets(long mark, long base, UUID pig, int pigIdSeen, int playerIdSeen, String dim) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        Event add = first(mark, e -> e.kind().equals("ADD") && pig.toString().equals(e.detail()));
        int pigId = add != null ? add.a() : pigIdSeen;
        Event login = first(mark, e -> e.kind().equals("LOGIN"));
        int playerId = login != null ? login.a() : playerIdSeen;
        m.put("pigEntityId", pigId);
        m.put("playerEntityId", playerId);
        m.put("respawnMs", at(first(mark, e -> e.kind().equals("RESPAWN")), base));
        m.put("loginMs", at(login, base));
        m.put("teleportMs", at(first(mark, e -> e.kind().equals("TP")), base));
        m.put("teleportAckMs", at(first(mark, e -> e.kind().equals("ACK_TP")), base));
        m.put("firstChunkBatchDoneMs", at(first(mark, e -> e.kind().equals("BATCH_DONE")), base));
        double[] pg = serverPig(dim);
        if (pg != null) {
            int cx = Math.floorDiv((int) Math.floor(pg[0]), 16), cz = Math.floorDiv((int) Math.floor(pg[2]), 16);
            Event chunk = first(mark, e -> e.kind().equals("CHUNK") && e.a() == cx && e.b() == cz);
            m.put("pigChunk", cx + "," + cz);
            m.put("pigChunkMs", at(chunk, base));
            if (chunk != null) {
                m.put("pigChunkBatchDoneMs", at(first(chunk.nanos(), e -> e.kind().equals("BATCH_DONE")), base));
                m.put("pigChunkBatchAckMs", at(first(chunk.nanos(), e -> e.kind().equals("BATCH_ACK")), base));
            }
        }
        m.put("firstMoveOutMs", at(first(Math.max(mark, base), e -> e.kind().equals("MOVE")), base));
        m.put("pigAddEntityMs", at(add, base));
        m.put("pigPassengersWithPlayerMs", at(first(mark, e -> e.kind().equals("PASS") && e.a() == pigId
            && Arrays.asList(e.detail().split(",")).contains(Integer.toString(playerId))), base));
        m.put("pigPassengersAfterAddMs", add == null ? null : at(first(add.nanos(), e -> e.kind().equals("PASS") && e.a() == pigId), base));
        m.put("pigRemoveMs", at(first(mark, e -> e.kind().equals("REMOVE")
            && Arrays.asList(e.detail().split(",")).contains(Integer.toString(pigId))), base));
        long until = add != null ? add.nanos() : System.nanoTime();
        int movesBeforeAdd = 0, inputsBeforeAdd = 0;
        for (Event e : trace) {
            if (e.nanos() <= Math.max(mark, base) || e.nanos() >= until) continue;
            if (e.kind().equals("MOVE")) movesBeforeAdd++;
            if (e.kind().equals("INPUT")) inputsBeforeAdd++;
        }
        m.put("movesOutBeforePigAdd", movesBeforeAdd);
        m.put("inputsOutBeforePigAdd", inputsBeforeAdd);
        return m;
    }

    Event first(long nanos, Predicate<Event> match) {
        for (Event e : trace) if (e.nanos() >= nanos && match.test(e)) return e;
        return null;
    }

    Long firstAfter(long nanos, Predicate<Event> match) {
        Event e = first(nanos, match);
        return e == null ? null : e.nanos();
    }

    static Double at(Event e, long base) { return e == null ? null : ms(e.nanos(), base); }

    long mark(String name) {
        long now = System.nanoTime();
        trace.add(new Event(now, "-", "MARK", 0, 0, name));
        return now;
    }

    ClientView view(UUID pig) {
        return context.computeOnClient(c -> {
            long now = System.nanoTime();
            if (c.level == null || c.player == null) return new ClientView(now, null, -1, null, false, false, -1, 0, 0, 0, 0, 0, 0);
            Entity vehicle = c.player.getVehicle();
            Entity found = null;
            if (pig != null) for (Entity e : c.level.entitiesForRendering()) if (pig.equals(e.getUUID())) { found = e; break; }
            return new ClientView(now, c.level.dimension().identifier().toString(), c.player.getId(),
                vehicle == null ? null : vehicle.getUUID(), found != null, found != null && found.getPassengers().contains(c.player),
                found == null ? -1 : found.getId(), c.player.getX(), c.player.getY(), c.player.getZ(),
                found == null ? Double.NaN : found.getX(), found == null ? Double.NaN : found.getY(), found == null ? Double.NaN : found.getZ());
        });
    }

    String sample(int tick, ClientView v, double[] serverPlayer, double[] serverPig) {
        return tick + "|t=" + ms(v.nanos(), t0) + "|" + v.dimension() + "|veh=" + v.vehicle() + "|pig=" + v.hasPig() + "|carries=" + v.carries()
            + "|c=" + fmt(v.px(), v.py(), v.pz()) + "|s=" + (serverPlayer == null ? "-" : fmt(serverPlayer[0], serverPlayer[1], serverPlayer[2]))
            + "|cpig=" + fmt(v.pigX(), v.pigY(), v.pigZ()) + "|spig=" + (serverPig == null ? "-" : fmt(serverPig[0], serverPig[1], serverPig[2]));
    }

    boolean serverRiding(String dimension) throws IOException {
        return rcon("execute in " + dimension + " positioned 0 100 0 as @e[tag=" + TAG + ",distance=..30000] on passengers if entity @s[name=" + USER + "]")
            .contains("passed");
    }

    double[] serverPos(String who) throws IOException { return doubles(rcon("data get entity " + who + " Pos")); }

    double[] serverPig(String dim) throws IOException {
        return doubles(rcon("execute in " + dim + " positioned 0 100 0 run data get entity @e[tag=" + TAG + ",distance=..30000,limit=1] Pos"));
    }

    int pigCooldown(String dim) throws IOException {
        Matcher m = INT_DATA.matcher(rcon("execute in " + dim + " positioned 0 100 0 run data get entity @e[tag=" + TAG
            + ",distance=..30000,limit=1] PortalCooldown"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static double[] doubles(String reply) {
        Matcher m = DOUBLES.matcher(reply);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) { if (!m.find()) return null; out[i] = Double.parseDouble(m.group(1)); }
        return out;
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
        rcon(fill(d, x - 1, y - 1, z, x + 2, y + 3, z, "minecraft:obsidian"));
        rcon(fill(d, x, y, z, x + 1, y + 2, z, "minecraft:nether_portal[axis=x]"));
    }

    static String fill(String d, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return "execute in " + d + " run fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block;
    }

    static double distance(ClientView v, double[] s) { return distance(v.px(), v.py(), v.pz(), s[0], s[1], s[2]); }

    static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static Double ms(Long nanos, long base) { return nanos == null ? null : Math.round((nanos - base) / 1.0e5) / 10.0; }

    static Double round(double value) { return Double.isFinite(value) ? Math.round(value * 1000.0) / 1000.0 : null; }

    static String fmt(double x, double y, double z) {
        return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.2f,%.2f,%.2f", x, y, z);
    }

    @FunctionalInterface interface IoAction { void run() throws IOException; }

    // ------------------------------------------------------------------------------------------------ packet trace
    /** Records the packets that order a mounted arrival, in netty receive/send order, ahead of the packet handler. */
    final class TraceHandler extends ChannelDuplexHandler {
        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            record("in", msg, System.nanoTime());
            super.channelRead(ctx, msg);
        }

        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            record("out", msg, System.nanoTime());
            super.write(ctx, msg, promise);
        }

        void record(String dir, Object msg, long now) {
            if (msg instanceof BundlePacket<?> bundle) {
                for (Object sub : bundle.subPackets()) record(dir, sub, now);
                return;
            }
            Event e = switch (msg) {
                case ClientboundRespawnPacket p -> new Event(now, dir, "RESPAWN", 0, 0, p.commonPlayerSpawnInfo().dimension().identifier().toString());
                case ClientboundLoginPacket p -> new Event(now, dir, "LOGIN", p.playerId(), 0, p.commonPlayerSpawnInfo().dimension().identifier().toString());
                case ClientboundPlayerPositionPacket p -> new Event(now, dir, "TP", p.id(), 0, p.change().position().toString());
                case ClientboundAddEntityPacket p when p.getType() == EntityTypes.PIG -> new Event(now, dir, "ADD", p.getId(), 0, p.getUUID().toString());
                case ClientboundRemoveEntitiesPacket p -> new Event(now, dir, "REMOVE", 0, 0, join(p.getEntityIds().toIntArray()));
                case ClientboundSetPassengersPacket p -> new Event(now, dir, "PASS", p.getVehicle(), 0, join(p.getPassengers()));
                case ClientboundLevelChunkWithLightPacket p -> new Event(now, dir, "CHUNK", p.getX(), p.getZ(), "");
                case ClientboundChunkBatchStartPacket p -> new Event(now, dir, "BATCH_START", 0, 0, "");
                case ClientboundChunkBatchFinishedPacket p -> new Event(now, dir, "BATCH_DONE", p.batchSize(), 0, "");
                case ServerboundAcceptTeleportationPacket p -> new Event(now, dir, "ACK_TP", p.getId(), 0, "");
                case ServerboundMovePlayerPacket p -> new Event(now, dir, "MOVE", 0, 0, (p.hasPosition() ? "pos" : "") + (p.hasRotation() ? "rot" : ""));
                case ServerboundChunkBatchReceivedPacket p -> new Event(now, dir, "BATCH_ACK", 0, 0, "");
                case ServerboundPlayerInputPacket p -> new Event(now, dir, "INPUT", 0, 0, "");
                case ServerboundMoveVehiclePacket p -> new Event(now, dir, "MOVE_VEHICLE", 0, 0, "");
                default -> null;
            };
            if (e != null) trace.add(e);
        }
    }

    static String join(int[] ids) {
        StringJoiner j = new StringJoiner(",");
        for (int id : ids) j.add(Integer.toString(id));
        return j.toString();
    }

    /** Installs the trace on the connection being established (connect screen) or the joined one; true once installed. */
    boolean installTrace(Minecraft c) {
        try {
            Connection connection = c.getConnection() != null ? c.getConnection().getConnection() : (Connection) PENDING.get(c);
            if (connection == null && c.gui.screen() instanceof ConnectScreen screen) connection = (Connection) CONNECTING.get(screen);
            if (connection == null) return false;
            Channel channel = (Channel) CHANNEL.get(connection);
            if (channel == null || channel.pipeline().get("packet_handler") == null) return false;
            if (channel.pipeline().get("s8f7_trace") == null) channel.pipeline().addBefore("packet_handler", "s8f7_trace", new TraceHandler());
            return true;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------ client
    /** Connects with the trace installed before the game Login packet; returns the pre-connect mark. */
    long connect() {
        long mark = mark("connect");
        context.runOnClient(c -> {
            ServerData data = new ServerData("Phoenium S8-F7", address, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(c.gui.screen(), c, ServerAddress.parseString(address), data, false, null);
        });
        boolean installed = false;
        for (int tick = 0; tick < 1200; tick++) {
            if (!installed) installed = context.computeOnClient(this::installTrace);
            if (context.computeOnClient(c -> c.level != null && c.player != null)) {
                if (!installed) installed = context.computeOnClient(this::installTrace);
                trace.add(new Event(System.nanoTime(), "-", "JOINED", installed ? 1 : 0, 0, ""));
                return mark;
            }
            context.waitTick();
        }
        throw new IllegalStateException("client did not join within 1200 ticks");
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
        receipt.put("levelSeed", LEVEL_SEED);
        Files.writeString(directory.resolve("eula.txt"), "# Disposable S8-F7 evidence server\neula=true\n");
        String port = address.substring(address.lastIndexOf(':') + 1);
        // The prepared (L5) server keeps its own properties; only the test's keys are overridden, identically on every arm.
        Map<String, String> overrides = new LinkedHashMap<>();
        for (String entry : List.of(
            "allow-flight=true", "difficulty=peaceful", "enable-rcon=true", "enforce-secure-profile=false",
            "gamemode=survival", "level-name=" + world, "level-seed=" + LEVEL_SEED, "max-players=2", "online-mode=false",
            "server-ip=127.0.0.1", "server-port=" + port, "query.port=" + port, "rcon.ip=127.0.0.1", "rcon.port=" + rconPort,
            "rcon.password=" + rconPassword, "spawn-protection=0", "view-distance=6", "simulation-distance=6"))
            overrides.put(entry.substring(0, entry.indexOf('=')), entry);
        Path properties = directory.resolve("server.properties");
        List<String> lines = new ArrayList<>();
        if (Files.exists(properties)) {
            for (String line : Files.readAllLines(properties)) {
                int eq = line.indexOf('=');
                String key = line.startsWith("#") || eq < 0 ? null : line.substring(0, eq);
                lines.add(key != null && overrides.containsKey(key) ? overrides.remove(key) : line);
            }
        }
        lines.addAll(overrides.values());
        Files.writeString(properties, String.join(System.lineSeparator(), lines) + System.lineSeparator());
        receipt.put("serverPropertiesSha256", sha256(properties));
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
        boolean geyser = mods.keySet().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("geyser"));
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            String log = Files.exists(serverLog) ? Files.readString(serverLog) : "";
            if (!server.isAlive()) throw new IllegalStateException("server exited during startup");
            // Geyser/Floodgate finish booting (and injecting into the network pipeline) after Minecraft's Done; a client
            // connecting before that can hang in the handshake, so every arm waits for the last startup marker.
            boolean geyserDone = !geyser || log.contains("Run /geyser help");
            if (log.contains("Done (") && geyserDone && (control || log.contains("lifecycle=RUNNING"))) break;
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
            StringBuilder tsv = new StringBuilder("ms\tdir\tkind\ta\tb\tdetail\n");
            for (Event e : trace)
                tsv.append(ms(e.nanos(), t0)).append('\t').append(e.dir()).append('\t').append(e.kind()).append('\t')
                    .append(e.a()).append('\t').append(e.b()).append('\t').append(e.detail()).append('\n');
            Files.writeString(evidence.resolveSibling(evidence.getFileName().toString().replaceFirst("\\.json$", "") + "-packets.tsv"), tsv);
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
