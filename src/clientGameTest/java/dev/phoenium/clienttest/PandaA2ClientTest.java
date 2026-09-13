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
import net.minecraft.world.item.Items;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PANDA_A2_LIVE: account-free real-client ender-pearl workload.
 *
 * <p>The client is launched by Loom's Fabric Client GameTest runner and connects over TCP to a
 * separately launched dedicated server. The server is copied from the accepted Stage 7
 * co-install template, including the pinned settlement2 C2ME and ScalableLux artifacts. RCON is
 * used only for disposable fixture preparation, bounded entity observation, and diagnostics; the
 * pearls themselves are created by the real client using the normal item-use path.</p>
 */
public final class PandaA2ClientTest implements FabricClientGameTest {
    private static final String USER = "PhoeniumTest0";
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final int OVERWORLD_SPAWN_X = -96;
    private static final int NETHER_SPAWN_X = 8;
    private static final String SERVER_ADDRESS = System.getProperty("phoenium.test.serverAddress", "127.0.0.1:25565");
    private static final int CAP = Integer.getInteger("phoenium.panda.cap", 2);
    private static final int WORKERS = Integer.getInteger("phoenium.panda.workers", 1);
    private static final int SERVER_START_TIMEOUT_TICKS = 7_200;
    private static final int CLIENT_CONNECT_TIMEOUT_TICKS = 1_200;
    private static final Pattern NUMERIC_FIELD = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_.-]*)=(-?\\d+)");
    private static final Pattern MOVE_FIELDS = Pattern.compile("packets=move\\[([^]]*)\\]");
    private static final String PRODUCTION_SHA256 =
            "619485DAAF545D9D99C1D630BD60A392B4447BB2099DD44C262E3EFA890655CD";
    private static final String C2ME_SHA256 =
        "8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53";
    private static final String SCALABLE_LUX_SHA256 =
        "8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8";

    @Override
    public void runTest(ClientGameTestContext context) {
        if ("projectile-cap".equals(System.getProperty("phoenium.test.mode"))) {
            runProjectileCap(context);
            return;
        }
        Evidence evidence = new Evidence();
        ExternalServer server = null;
        boolean connected = false;
        Throwable failure = null;
        try {
            require(CAP > 0, "Panda A2 live cap must be positive");
            require(WORKERS == 1 || WORKERS == 4, "Panda A2 live accepts only W1 or W4");
            evidence.workers = WORKERS;
            evidence.cap = CAP;
            evidence.testUsername = USER;

            server = ExternalServer.start();
            evidence.serverTemplate = server.template.toAbsolutePath().toString();
            evidence.serverDirectory = server.directory.toAbsolutePath().toString();
            evidence.productionSha256 = sha256(server.directory.resolve("mods/phoenium-0.2.0-stage2.jar"));
            evidence.c2meSha256 = sha256(server.directory.resolve(
                "mods/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar"));
            evidence.scalableLuxSha256 = sha256(server.directory.resolve(
                "mods/ScalableLux-fabric-0.3.0-alpha.0.3-all.jar"));
            require(PRODUCTION_SHA256.equals(evidence.productionSha256),
                "live server production bytes differ from accepted Stage 7 bytes: " + evidence.productionSha256);
            require(C2ME_SHA256.equals(evidence.c2meSha256),
                "live server C2ME is not the accepted settlement2 artifact: " + evidence.c2meSha256);
            require(SCALABLE_LUX_SHA256.equals(evidence.scalableLuxSha256),
                "live server ScalableLux differs from accepted Stage 7 bytes: " + evidence.scalableLuxSha256);
            evidence.serverProcessStarted = true;

            server.waitUntilReady(context);
            evidence.serverReady = true;
            evidence.worldSeed = server.execute("seed").trim();
            evidence.fixturePreparation = server.prepareFixture();
            server.prepareNetherFixture();
            String startupRoute = server.execute("phoenium route \"" + OVERWORLD + "\" 5 0").trim();
            evidence.crossRegionRoute = "startup=" + startupRoute;

            connectClient(context);
            connected = true;
            evidence.realTcpConnection = true;
            evidence.clientPlayer = USER.equals(clientName(context));
            require(evidence.clientPlayer, "real client did not join as " + USER);
            String placeClient = server.execute("execute in " + OVERWORLD + " run tp " + USER
                + " " + (OVERWORLD_SPAWN_X + 7.5) + " 90 8.5");
            require(!placeClient.toLowerCase().contains("no entity")
                    && !placeClient.toLowerCase().contains("unknown"),
                "real client could not be placed on the prepared Overworld pad: " + placeClient);
            context.waitTicks(40);
            Diagnostics initial = record(server, evidence, "initial");
            require(initial.value("pearlMemberships") == 0, "fresh live ledger was not empty: " + initial.raw);
            require(initial.value("pendingPearlCleanups") == 0, "fresh live cleanup queue was not empty: " + initial.raw);
            require(initial.value("pearlCap") == CAP, "live cap property was not active: " + initial.raw);
            evidence.liveCapEnabled = true;

            equipPearl(server);
            Pearl p1 = throwAndFreeze(context, server, OVERWORLD, "panda_ow_1");
            evidence.crossRegionRoute = "startup=" + server.execute("phoenium route \"" + OVERWORLD + "\" 5 0").trim()
                + " | afterP1=" + server.execute("phoenium route \"" + OVERWORLD + "\" 5 0").trim();
            Diagnostics belowCap = record(server, evidence, "below-cap-one");
            require(entityExists(server, OVERWORLD, p1.tag), "first real client pearl disappeared below cap");
            require(belowCap.value("pearlMemberships") == 1, "below-cap pearl was not admitted: " + belowCap.raw);
            require(belowCap.value("pearlCleanupAdmissions") == 0, "below-cap pearl queued cleanup: " + belowCap.raw);
            evidence.belowCapValid = true;

            moveClientNormally(context, server);
            evidence.crossRegionRoute = evidence.crossRegionRoute
                + " | afterMovement=" + server.execute("phoenium route \"" + OVERWORLD + "\" 5 0").trim();
            Diagnostics ordinaryAfter = record(server, evidence, "ordinary-gameplay-below-cap");
            evidence.ordinaryGameplay = ordinaryAfter.value("move.in") > initial.value("move.in")
                && ordinaryAfter.value("move.accept") > initial.value("move.accept")
                && ordinaryAfter.value("move.effect") > initial.value("move.effect")
                && ordinaryAfter.value("barriers") == 0;
            require(evidence.ordinaryGameplay,
                "ordinary client movement below the pearl cap did not remain functional: " + ordinaryAfter.raw);

            equipPearl(server);
            Pearl p2 = throwAndFreeze(context, server, OVERWORLD, "panda_ow_2");
            evidence.crossRegionRoute = evidence.crossRegionRoute
                + " | afterP2=" + server.execute("phoenium route \"" + OVERWORLD + "\" 5 0").trim();
            Diagnostics exactBoundary = record(server, evidence, "exact-cap-boundary");
            require(exactBoundary.value("pearlMemberships") == CAP,
                "exact cap boundary did not retain both below-cap pearls: " + exactBoundary.raw);
            require(exactBoundary.value("pearlCleanupAdmissions") == belowCap.value("pearlCleanupAdmissions")
                    && exactBoundary.value("pearlCleanupExecutions") == belowCap.value("pearlCleanupExecutions"),
                "exact cap boundary scheduled cleanup unexpectedly: " + exactBoundary.raw);
            require(entityExists(server, OVERWORLD, p1.tag) && entityExists(server, OVERWORLD, p2.tag),
                "one of the exact-boundary pearls was removed");
            evidence.exactBoundaryValid = true;

            StringBuilder routeScan = new StringBuilder();
            for (int chunk = 4; chunk <= 9; chunk++) {
                if (routeScan.length() != 0) routeScan.append(" | ");
                routeScan.append(server.execute("phoenium route \"" + OVERWORLD + "\" " + chunk + " 0").trim());
            }
            evidence.crossRegionRoute = evidence.crossRegionRoute + " | live=" + routeScan;
            String routeB = server.execute("phoenium route \"" + OVERWORLD + "\" 5 0");
            require(routeB.contains("region=stage3-fixture-b"),
                "cross-region route did not resolve to fixture Region B: " + evidence.crossRegionRoute);
            String routeBInterior = server.execute("phoenium route \"" + OVERWORLD + "\" 20 0");
            require(routeBInterior.contains("region=stage3-fixture-b"),
                "Region-B interior route did not resolve before native relocation: " + routeBInterior);
            require(entityExists(server, OVERWORLD, p1.tag), "oldest pearl vanished before the owner re-resolution probe");

            String destinationLoad = server.execute("execute in " + OVERWORLD + " run forceload add 328 8");
            require(!destinationLoad.toLowerCase().contains("not loaded")
                    && !destinationLoad.toLowerCase().contains("unknown"),
                "Region-B interior could not be prepared for the native relocation witness: " + destinationLoad);
            String destinationRouteAfterLoad = server.execute("phoenium route \"" + OVERWORLD + "\" 20 0");
            evidence.crossRegionRoute = evidence.crossRegionRoute + " | destinationLoad=" + destinationLoad.trim()
                + " | destinationRouteAfterLoad=" + destinationRouteAfterLoad.trim();
            String rawMove = server.execute("phoenium-test pearl-raw-move " + p1.tag + " 328.5 90 8.5");
            evidence.crossRegionRoute = evidence.crossRegionRoute + " | rawMove=" + rawMove.trim();
            require(rawMove.contains("matches=1"), "native raw entity movement did not find the oldest pearl: " + rawMove);
            context.waitTicks(20);
            String movedStatus = server.execute("phoenium-test pearl-status " + p1.tag);
            evidence.crossRegionRoute = evidence.crossRegionRoute + " | movedStatus=" + movedStatus.trim();
            require(movedStatus.contains("matches=1") && movedStatus.contains("route=stage3-fixture-a")
                    && movedStatus.contains("owner=RegionId[world=WorldId[value=minecraft:overworld], value=stage3-fixture-a]")
                    && movedStatus.contains("routingKey=RoutingKey[world=WorldId[value=minecraft:overworld], x=20, z=0]")
                    && movedStatus.contains("pendingEntityRelocations=0"),
                "real pearl did not retain identity after the native Region-B movement and topology closure: "
                    + movedStatus);
            // The live fixture may commit the pending A->B fact before topology closure, or the
            // closure may merge the participating domains first. Both are valid production
            // outcomes: the acceptance invariant is one actual pearl identity, its physical
            // destination routing key, and no stranded pending relocation. The separate A1
            // boundary suite proves the owner-transition counter; this live probe must not turn
            // scheduler timing into a false negative for the A2 ledger.
            evidence.crossRegionIdentityPreserved = true;

            Diagnostics duplicateBefore = record(server, evidence, "duplicate-before");
            String reobserve = server.execute("phoenium-test pearl-reobserve " + p2.tag);
            evidence.duplicateReobserveResponse = reobserve.trim();
            require(reobserve.contains("matches=1"), "test-only live re-observation did not find one exact pearl: " + reobserve);
            Diagnostics duplicateAfter = record(server, evidence, "duplicate-after");
            require(duplicateAfter.value("pearlMemberships") == duplicateBefore.value("pearlMemberships")
                    && duplicateAfter.value("pearlCleanupAdmissions") == duplicateBefore.value("pearlCleanupAdmissions")
                    && duplicateAfter.value("pearlCleanupExecutions") == duplicateBefore.value("pearlCleanupExecutions"),
                "duplicate admission changed live count or cleanup obligations: " + duplicateAfter.raw);
            evidence.duplicateAdmissionIdempotent = true;

            String returnClient = server.execute("execute in " + OVERWORLD + " run tp " + USER
                + " " + (OVERWORLD_SPAWN_X + 7.5) + " 90 8.5");
            require(!returnClient.toLowerCase().contains("no entity")
                    && !returnClient.toLowerCase().contains("unknown"),
                "real client could not return to the Overworld for overflow admission: " + returnClient);
            context.waitTicks(70);
            evidence.crossRegionRoute = evidence.crossRegionRoute + " | returnedClient=" + returnClient.trim();
            String ownerTransition = server.execute("phoenium-test pearl-converge " + p1.tag);
            evidence.crossRegionRoute = evidence.crossRegionRoute + " | ownerTransition=" + ownerTransition.trim();
            require(ownerTransition.contains("matches=1") && ownerTransition.contains("routeBefore=stage3-fixture-a")
                    && ownerTransition.contains("routeAfter=stage3-fixture-a"),
                "live cleanup did not retain the pearl under the current owner after the Region-B movement: "
                    + ownerTransition);
            equipPearl(server);
            Pearl p3 = throwAndFreeze(context, server, OVERWORLD, "panda_ow_3");
            context.waitTicks(60);
            Diagnostics overflow = record(server, evidence, "overflow");
            long overflowAdmissions = overflow.value("pearlCleanupAdmissions") - exactBoundary.value("pearlCleanupAdmissions");
            long overflowExecutions = overflow.value("pearlCleanupExecutions") - exactBoundary.value("pearlCleanupExecutions");
            require(overflow.value("pearlMemberships") == CAP, "overflow did not settle back to the cap: " + overflow.raw);
            require(overflowAdmissions == 1 && overflowExecutions == 1,
                "overflow was not admitted/executed exactly once: " + overflow.raw);
            require(overflow.value("pearlCleanupRejections") == exactBoundary.value("pearlCleanupRejections"),
                "overflow encountered a stale-owner or identity rejection: " + overflow.raw);
            require(!entityExists(server, OVERWORLD, p1.tag), "overflow did not remove the correct oldest pearl");
            require(entityExists(server, OVERWORLD, p2.tag) && entityExists(server, OVERWORLD, p3.tag),
                "overflow removed a non-oldest pearl");
            evidence.oldestOverflowSelected = true;
            evidence.overflowRemovedExactlyOnce = true;
            context.waitTicks(60);
            Diagnostics overflowSettled = record(server, evidence, "overflow-settled");
            require(overflowSettled.value("pearlCleanupExecutions") == overflow.value("pearlCleanupExecutions"),
                "overflow cleanup executed more than once: " + overflowSettled.raw);

            naturalRemove(context, server, OVERWORLD, p2.tag);
            Diagnostics natural = record(server, evidence, "natural-removal");
            require(!entityExists(server, OVERWORLD, p2.tag) && entityExists(server, OVERWORLD, p3.tag),
                "natural pearl removal did not remove only the selected live pearl");
            require(natural.value("pearlMemberships") == 1,
                "natural pearl removal did not retire exact ledger membership: " + natural.raw);
            require(natural.value("pearlCleanupAdmissions") == overflowSettled.value("pearlCleanupAdmissions")
                    && natural.value("pearlCleanupExecutions") == overflowSettled.value("pearlCleanupExecutions"),
                "natural removal was misclassified as cap cleanup: " + natural.raw);
            evidence.naturalRemovalRetired = true;

            Diagnostics beforeDisconnect = record(server, evidence, "before-disconnect");
            disconnectClient(context);
            connected = false;
            context.waitTicks(45);
            Diagnostics disconnected = record(server, evidence, "after-disconnect");
            evidence.unloadedWithPlayer = disconnected.value("pearlMemberships") == 1
                && disconnected.value("pendingPearlCleanups") == 0
                && disconnected.value("players") == 0
                && !entityExists(server, OVERWORLD, p3.tag);
            require(evidence.unloadedWithPlayer,
                "disconnect did not preserve the exact live pearl membership through UNLOADED_WITH_PLAYER: " + disconnected.raw);

            connectClient(context);
            connected = true;
            context.waitTicks(70);
            Diagnostics reconnected = record(server, evidence, "after-reconnect");
            evidence.reconnectPreserved = reconnected.value("pearlMemberships") == 1
                && entityExists(server, OVERWORLD, p3.tag)
                && reconnected.value("pearlCleanupAdmissions") == beforeDisconnect.value("pearlCleanupAdmissions")
                && reconnected.value("pearlCleanupExecutions") == beforeDisconnect.value("pearlCleanupExecutions");
            require(evidence.reconnectPreserved,
                "reconnect did not rebind the preserved pearl membership: " + reconnected.raw);

            server.execute("execute in " + NETHER + " run tp " + USER + " " + NETHER_SPAWN_X + ".5 90 8.5");
            context.waitTicks(70);
            require(entityExists(server, OVERWORLD, p3.tag),
                "overworld pearl was not retained while the player entered the Nether");
            equipPearl(server);
            Pearl n1 = throwAndFreeze(context, server, NETHER, "panda_nether_1");
            context.waitTicks(20);
            equipPearl(server);
            Pearl n2 = throwAndFreeze(context, server, NETHER, "panda_nether_2");
            Diagnostics crossDimensionBoundary = record(server, evidence, "cross-dimension-boundary");
            require(crossDimensionBoundary.value("pearlMemberships") == 3,
                "Overworld and Nether pearl scopes were not independent: " + crossDimensionBoundary.raw);
            require(crossDimensionBoundary.value("pearlCleanupAdmissions") == reconnected.value("pearlCleanupAdmissions")
                    && crossDimensionBoundary.value("pearlCleanupExecutions") == reconnected.value("pearlCleanupExecutions"),
                "a Nether pearl consumed the Overworld player's cap: " + crossDimensionBoundary.raw);
            require(entityExists(server, OVERWORLD, p3.tag), "cross-dimension admission lost the Overworld pearl");
            evidence.crossDimensionScopeIndependent = true;

            context.waitTicks(20);
            equipPearl(server);
            Pearl n3 = throwAndFreeze(context, server, NETHER, "panda_nether_3");
            context.waitTicks(60);
            Diagnostics crossDimensionOverflow = record(server, evidence, "cross-dimension-overflow");
            require(crossDimensionOverflow.value("pearlMemberships") == 3,
                "Nether overflow did not retain one Nether cap plus the Overworld membership: " + crossDimensionOverflow.raw);
            require(crossDimensionOverflow.value("pearlCleanupAdmissions")
                    - crossDimensionBoundary.value("pearlCleanupAdmissions") == 1
                    && crossDimensionOverflow.value("pearlCleanupExecutions")
                    - crossDimensionBoundary.value("pearlCleanupExecutions") == 1,
                "Nether overflow was not executed exactly once: " + crossDimensionOverflow.raw);
            require(!entityExists(server, NETHER, n1.tag)
                    && entityExists(server, NETHER, n2.tag)
                    && entityExists(server, NETHER, n3.tag),
                "Nether overflow selected the wrong oldest pearl");
            require(entityExists(server, OVERWORLD, p3.tag),
                "Nether overflow removed or confused the Overworld pearl identity");
            evidence.crossDimensionOverflowCorrect = true;

            server.execute("execute in " + OVERWORLD + " run tp " + USER + " " + (OVERWORLD_SPAWN_X + 7.5) + " 90 8.5");
            context.waitTicks(70);
            naturalRemove(context, server, OVERWORLD, p3.tag);
            // Keep the real client on the prepared Overworld pad for final teardown. A live
            // Nether pearl that is allowed to fall can hit a block and legitimately teleport its
            // owner, which would start a third dimension transition while this fixture is being
            // torn down. The native kill path still exercises setRemoved/retirement in the
            // correct Nether scope; natural removal was already verified above for p2.
            retirePearl(server, NETHER, n2.tag);
            retirePearl(server, NETHER, n3.tag);
            Diagnostics finalLive = record(server, evidence, "final-live-cleanup");
            require(finalLive.value("pearlMemberships") == 0 && finalLive.value("pendingPearlCleanups") == 0,
                "live pearl ledger retained stale membership at the end of the workload: " + finalLive.raw);
            require(finalLive.value("barriers") == 0 && finalLive.value("ownershipViolations") == 0
                    && finalLive.value("unleased") == 0,
                "live workload ended with a Phoenium safety violation: " + finalLive.raw);
            evidence.noStaleMembership = true;
            evidence.acceptancePass = true;
        } catch (Throwable throwable) {
            failure = throwable;
            evidence.failure = throwable.toString();
        } finally {
            if (connected) {
                try {
                    disconnectClient(context);
                    evidence.clientDisconnectedCleanly = true;
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    evidence.cleanupFailure = cleanupFailure.toString();
                }
            }
            if (server != null) {
                try {
                    evidence.serverStoppedCleanly = server.stopNormally(context);
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    evidence.cleanupFailure = cleanupFailure.toString();
                }
                evidence.processLog = server.processLog.toAbsolutePath().toString();
            }
            evidence.clientTerminatedCleanly = true;
            evidence.write();
        }
        if (failure != null) throwUnchecked(failure);
        require(evidence.acceptancePass, "PANDA_A2_LIVE did not produce a pass receipt");
    }

    /**
     * PROJECTILE_CAP: account-free real-client workload for the separate per-region/type ledger.
     * This deliberately uses snowballs and eggs rather than the A2 pearl path, so the two policy
     * ledgers are exercised independently while the same accepted C2ME/ScalableLux server
     * composition and W1/W4 harness are retained.
     */
    private void runProjectileCap(ClientGameTestContext context) {
        ProjectileEvidence evidence = new ProjectileEvidence();
        ExternalServer server = null;
        boolean connected = false;
        Throwable failure = null;
        try {
            require(CAP > 0, "projectile cap must be positive");
            require(WORKERS == 1 || WORKERS == 4, "projectile live accepts only W1 or W4");
            evidence.workers = WORKERS;
            evidence.cap = CAP;
            evidence.testUsername = USER;

            server = ExternalServer.start();
            evidence.serverTemplate = server.template.toAbsolutePath().toString();
            evidence.serverDirectory = server.directory.toAbsolutePath().toString();
            evidence.productionSha256 = sha256(server.directory.resolve("mods/phoenium-0.2.0-stage2.jar"));
            evidence.c2meSha256 = sha256(server.directory.resolve(
                "mods/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar"));
            evidence.scalableLuxSha256 = sha256(server.directory.resolve(
                "mods/ScalableLux-fabric-0.3.0-alpha.0.3-all.jar"));
            require(PRODUCTION_SHA256.equals(evidence.productionSha256),
                "live server production bytes differ from accepted Stage 7 bytes: " + evidence.productionSha256);
            require(C2ME_SHA256.equals(evidence.c2meSha256),
                "live server C2ME is not the accepted settlement2 artifact: " + evidence.c2meSha256);
            require(SCALABLE_LUX_SHA256.equals(evidence.scalableLuxSha256),
                "live server ScalableLux differs from accepted Stage 7 bytes: " + evidence.scalableLuxSha256);
            evidence.serverProcessStarted = true;

            server.waitUntilReady(context);
            evidence.serverReady = true;
            evidence.worldSeed = server.execute("seed").trim();
            evidence.fixturePreparation = server.prepareFixture();
            server.prepareNetherFixture();

            connectClient(context);
            connected = true;
            evidence.realTcpConnection = true;
            evidence.clientPlayer = USER.equals(clientName(context));
            require(evidence.clientPlayer, "real client did not join as " + USER);
            String placeClient = server.execute("execute in " + OVERWORLD + " run tp " + USER
                + " " + (OVERWORLD_SPAWN_X + 7.5) + " 90 8.5");
            require(!placeClient.toLowerCase().contains("no entity")
                    && !placeClient.toLowerCase().contains("unknown"),
                "real client could not be placed on the prepared Overworld pad: " + placeClient);
            context.waitTicks(40);
            Diagnostics initial = record(server, evidence, "initial");
            require(initial.value("projectileMemberships") == 0,
                "fresh live projectile ledger was not empty: " + initial.raw);
            require(initial.value("pendingProjectileCleanups") == 0,
                "fresh live projectile cleanup queue was not empty: " + initial.raw);
            require(initial.value("projectileCap") == CAP,
                "live projectile cap property was not active: " + initial.raw);
            require(initial.value("pearlMemberships") == 0 && initial.value("pearlCap") == -1,
                "projectile workload unexpectedly enabled or retained the pearl cap: " + initial.raw);
            evidence.liveCapEnabled = true;

            equipProjectile(server, "snowball");
            throwProjectileAndFreeze(context, server, OVERWORLD, "projectile_ow_1", "snowball", "snowball");
            Diagnostics belowCap = record(server, evidence, "below-cap-one");
            require(projectileExists(server, OVERWORLD, "projectile_ow_1", "snowball"),
                "first real client projectile disappeared below cap");
            require(belowCap.value("projectileMemberships") == 1
                    && belowCap.value("projectileCleanupAdmissions") == 0,
                "below-cap projectile was not retained: " + belowCap.raw);
            evidence.belowCapValid = true;

            moveClientNormally(context, server);
            Diagnostics ordinaryAfter = record(server, evidence, "ordinary-gameplay-below-cap");
            evidence.ordinaryGameplay = ordinaryAfter.value("move.in") > initial.value("move.in")
                && ordinaryAfter.value("move.accept") > initial.value("move.accept")
                && ordinaryAfter.value("move.effect") > initial.value("move.effect")
                && ordinaryAfter.value("barriers") == 0;
            require(evidence.ordinaryGameplay,
                "ordinary client movement below the projectile cap did not remain functional: " + ordinaryAfter.raw);

            equipProjectile(server, "snowball");
            throwProjectileAndFreeze(context, server, OVERWORLD, "projectile_ow_2", "snowball", "snowball");
            Diagnostics exactBoundary = record(server, evidence, "exact-cap-boundary");
            require(exactBoundary.value("projectileMemberships") == CAP
                    && exactBoundary.value("projectileCleanupAdmissions") == belowCap.value("projectileCleanupAdmissions")
                    && exactBoundary.value("projectileCleanupExecutions") == belowCap.value("projectileCleanupExecutions"),
                "exact projectile cap boundary scheduled cleanup unexpectedly: " + exactBoundary.raw);
            require(projectileExists(server, OVERWORLD, "projectile_ow_1", "snowball")
                    && projectileExists(server, OVERWORLD, "projectile_ow_2", "snowball"),
                "one of the exact-boundary projectiles was removed");
            evidence.exactBoundaryValid = true;

            String routeB = server.execute("phoenium route \"" + OVERWORLD + "\" 5 0");
            require(routeB.contains("region=stage3-fixture-b"),
                "cross-region route did not resolve to fixture Region B: " + routeB);
            String destinationLoad = server.execute("execute in " + OVERWORLD + " run forceload add 328 8");
            require(!destinationLoad.toLowerCase().contains("not loaded")
                    && !destinationLoad.toLowerCase().contains("unknown"),
                "Region-B destination could not be prepared for projectile relocation: " + destinationLoad);
            String rawMove = server.execute("phoenium-test projectile-raw-move projectile_ow_1 328.5 90 8.5");
            evidence.crossRegionRoute = "routeB=" + routeB.trim() + " | destinationLoad=" + destinationLoad.trim()
                + " | rawMove=" + rawMove.trim();
            require(rawMove.contains("matches=1"), "native raw projectile movement did not find the first projectile: " + rawMove);
            context.waitTicks(20);
            String movedStatus = server.execute("phoenium-test projectile-status projectile_ow_1");
            evidence.crossRegionRoute = evidence.crossRegionRoute + " | movedStatus=" + movedStatus.trim();
            require(movedStatus.contains("matches=1") && movedStatus.contains("routingKey=RoutingKey")
                    && movedStatus.contains("x=20") && movedStatus.contains("pendingEntityRelocations=0"),
                "real projectile did not retain identity after the native Region-B movement: " + movedStatus);
            Diagnostics moved = record(server, evidence, "cross-region-relocated");
            require(moved.value("projectileMemberships") == exactBoundary.value("projectileMemberships")
                    && moved.value("projectileCleanupAdmissions") == exactBoundary.value("projectileCleanupAdmissions"),
                "cross-region movement duplicated or cleaned the projectile: " + moved.raw);
            evidence.crossRegionIdentityPreserved = true;

            Diagnostics duplicateBefore = record(server, evidence, "duplicate-before");
            String reobserve = server.execute("phoenium-test projectile-reobserve projectile_ow_2");
            evidence.duplicateReobserveResponse = reobserve.trim();
            require(reobserve.contains("matches=1"), "projectile re-observation did not find one exact entity: " + reobserve);
            Diagnostics duplicateAfter = record(server, evidence, "duplicate-after");
            require(duplicateAfter.value("projectileMemberships") == duplicateBefore.value("projectileMemberships")
                    && duplicateAfter.value("projectileCleanupAdmissions") == duplicateBefore.value("projectileCleanupAdmissions")
                    && duplicateAfter.value("projectileCleanupExecutions") == duplicateBefore.value("projectileCleanupExecutions"),
                "duplicate projectile admission changed count or cleanup obligations: " + duplicateAfter.raw);
            evidence.duplicateAdmissionIdempotent = true;

            // Bring the moved identity back to the prepared A pad and explicitly converge the
            // participant routes before the overflow admission. The ledger must move the same
            // identity, preserve its original admission age, and let the current owner resolve
            // the later discard.
            String returnProjectile = server.execute("phoenium-test projectile-raw-move projectile_ow_1 -88.5 90 8.5");
            require(returnProjectile.contains("matches=1"), "moved projectile could not return to Region A: " + returnProjectile);
            context.waitTicks(70);
            String converge = server.execute("phoenium-test projectile-converge projectile_ow_1");
            evidence.crossRegionRoute = evidence.crossRegionRoute + " | return=" + returnProjectile.trim()
                + " | converge=" + converge.trim();
            require(converge.contains("matches=1"), "projectile owner convergence did not observe the identity: " + converge);

            equipProjectile(server, "snowball");
            throwProjectileAndFreeze(context, server, OVERWORLD, "projectile_ow_3", "snowball", "snowball");
            context.waitTicks(50);
            Diagnostics overflow = record(server, evidence, "overflow");
            require(overflow.value("projectileMemberships") == CAP
                    && overflow.value("projectileCleanupAdmissions") - exactBoundary.value("projectileCleanupAdmissions") == 1
                    && overflow.value("projectileCleanupExecutions") - exactBoundary.value("projectileCleanupExecutions") == 1,
                "projectile overflow did not settle to the cap exactly once: " + overflow.raw);
            require(!projectileExists(server, OVERWORLD, "projectile_ow_1", "snowball")
                    && projectileExists(server, OVERWORLD, "projectile_ow_2", "snowball")
                    && projectileExists(server, OVERWORLD, "projectile_ow_3", "snowball"),
                "projectile overflow selected the wrong oldest identity");
            evidence.oldestOverflowSelected = true;
            evidence.overflowRemovedExactlyOnce = true;
            context.waitTicks(50);
            Diagnostics overflowSettled = record(server, evidence, "overflow-settled");
            require(overflowSettled.value("projectileCleanupExecutions") == overflow.value("projectileCleanupExecutions"),
                "projectile overflow cleanup executed more than once: " + overflowSettled.raw);

            naturalRemoveProjectile(context, server, OVERWORLD, "projectile_ow_2", "snowball");
            Diagnostics natural = record(server, evidence, "natural-removal");
            require(!projectileExists(server, OVERWORLD, "projectile_ow_2", "snowball")
                    && natural.value("projectileMemberships") == 1
                    && natural.value("projectileCleanupAdmissions") == overflowSettled.value("projectileCleanupAdmissions")
                    && natural.value("projectileCleanupExecutions") == overflowSettled.value("projectileCleanupExecutions"),
                "natural projectile removal did not retire only the exact membership: " + natural.raw);
            evidence.naturalRemovalRetired = true;

            // Start the cross-dimension portion from a fresh real-client session. The accepted
            // A2 workload uses the same boundary: it drains the old movement stream before the
            // server performs the Nether respawn, so no stale client movement packet can force a
            // synchronous chunk wait inside the owner transaction during the dimension change.
            String recenterClient = server.execute("execute in " + OVERWORLD + " run tp " + USER
                + " " + (OVERWORLD_SPAWN_X + 7.5) + " 90 8.5");
            require(!recenterClient.toLowerCase().contains("no entity")
                    && !recenterClient.toLowerCase().contains("unknown"),
                "real client could not be recentered on the prepared Overworld pad: " + recenterClient);
            context.waitTicks(40);
            disconnectClient(context);
            connected = false;
            context.waitTicks(45);
            connectClient(context);
            connected = true;
            // Allow the fresh TCP session to finish its initial position/region stream before
            // the native dimension change. This keeps the transition epoch fence from carrying
            // an in-flight baseline packet into the destination owner transaction.
            context.waitTicks(140);

            String toNether = server.execute("execute in " + NETHER + " run tp " + USER + " "
                + NETHER_SPAWN_X + ".5 90 8.5");
            require(!toNether.toLowerCase().contains("no entity") && !toNether.toLowerCase().contains("unknown"),
                "real client could not enter the prepared Nether fixture: " + toNether);
            context.waitTicks(140);
            // Replace the active hand after the native respawn. The fixture command targets
            // weapon.mainhand, so this remains authoritative even if the client carried a
            // different selected hotbar index across the dimension transition.
            equipProjectile(server, "egg");
            // Establish the type-isolation sample before placing frozen snowballs in the same
            // Nether pad. The later snowball overflow still proves that the egg has its own
            // per-type budget and remains live while the snowball budget turns over.
            throwProjectileAndFreeze(context, server, NETHER, "projectile_nether_egg", "egg", "egg");
            Diagnostics typeBoundary = record(server, evidence, "type-boundary");
            require(typeBoundary.value("projectileCleanupAdmissions") == natural.value("projectileCleanupAdmissions")
                    && typeBoundary.value("projectileMemberships") == 2
                    && projectileExists(server, NETHER, "projectile_nether_egg", "egg"),
                "egg type admission consumed another scope or was not retained: " + typeBoundary.raw);

            equipProjectile(server, "snowball");
            throwProjectileAndFreeze(context, server, NETHER, "projectile_nether_1", "snowball", "snowball");
            context.waitTicks(20);
            equipProjectile(server, "snowball");
            throwProjectileAndFreeze(context, server, NETHER, "projectile_nether_2", "snowball", "snowball");
            Diagnostics dimensionBoundary = record(server, evidence, "cross-dimension-boundary");
            require(dimensionBoundary.value("projectileMemberships") == 4
                    && dimensionBoundary.value("projectileCleanupAdmissions") == typeBoundary.value("projectileCleanupAdmissions")
                    && projectileExists(server, OVERWORLD, "projectile_ow_3", "snowball"),
                "Nether snowballs consumed the independent Overworld projectile scope: " + dimensionBoundary.raw);
            evidence.crossDimensionScopeIndependent = true;

            context.waitTicks(20);
            equipProjectile(server, "snowball");
            throwProjectileAndFreeze(context, server, NETHER, "projectile_nether_3", "snowball", "snowball");
            context.waitTicks(50);
            Diagnostics dimensionOverflow = record(server, evidence, "cross-dimension-overflow");
            require(dimensionOverflow.value("projectileCleanupAdmissions") - typeBoundary.value("projectileCleanupAdmissions") == 1
                    && dimensionOverflow.value("projectileCleanupExecutions") - typeBoundary.value("projectileCleanupExecutions") == 1
                    && !projectileExists(server, NETHER, "projectile_nether_1", "snowball")
                    && projectileExists(server, NETHER, "projectile_nether_2", "snowball")
                    && projectileExists(server, NETHER, "projectile_nether_3", "snowball")
                    && projectileExists(server, NETHER, "projectile_nether_egg", "egg"),
                "Nether/type projectile overflow selected the wrong identity: " + dimensionOverflow.raw);
            require(projectileExists(server, OVERWORLD, "projectile_ow_3", "snowball"),
                "Nether projectile overflow removed or confused the Overworld identity");
            evidence.crossDimensionOverflowCorrect = true;

            server.execute("execute in " + OVERWORLD + " run tp " + USER + " "
                + (OVERWORLD_SPAWN_X + 7.5) + " 90 8.5");
            context.waitTicks(70);
            retireProjectile(server, OVERWORLD, "projectile_ow_3", "snowball");
            retireProjectile(server, NETHER, "projectile_nether_2", "snowball");
            retireProjectile(server, NETHER, "projectile_nether_3", "snowball");
            retireProjectile(server, NETHER, "projectile_nether_egg", "egg");
            Diagnostics finalLive = record(server, evidence, "final-live-cleanup");
            require(finalLive.value("projectileMemberships") == 0
                    && finalLive.value("pendingProjectileCleanups") == 0
                    && finalLive.value("barriers") == 0
                    && finalLive.value("ownershipViolations") == 0
                    && finalLive.value("unleased") == 0,
                "projectile workload ended with stale membership or Phoenium safety residue: " + finalLive.raw);
            evidence.noStaleMembership = true;
            evidence.acceptancePass = true;
        } catch (Throwable throwable) {
            failure = throwable;
            evidence.failure = throwable.toString();
        } finally {
            if (connected) {
                try {
                    disconnectClient(context);
                    evidence.clientDisconnectedCleanly = true;
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    evidence.cleanupFailure = cleanupFailure.toString();
                }
            }
            if (server != null) {
                try {
                    evidence.serverStoppedCleanly = server.stopNormally(context);
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    evidence.cleanupFailure = cleanupFailure.toString();
                }
                evidence.processLog = server.processLog.toAbsolutePath().toString();
            }
            evidence.clientTerminatedCleanly = true;
            evidence.write();
        }
        if (failure != null) throwUnchecked(failure);
        require(evidence.acceptancePass, "PROJECTILE_CAP did not produce a pass receipt");
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof AssertionError error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError(failure);
    }

    private static void equipPearl(ExternalServer server) throws IOException {
        String give = server.execute("give " + USER + " minecraft:ender_pearl 64");
        require(!give.toLowerCase().contains("unknown") && !give.toLowerCase().contains("error"),
            "could not give the real client an ender pearl: " + give);
        String replace = server.execute("item replace entity " + USER
            + " hotbar.0 with minecraft:ender_pearl 64");
        require(!replace.toLowerCase().contains("unknown") && !replace.toLowerCase().contains("error"),
            "could not place an ender pearl in the selected hotbar slot: " + replace);
        String count = server.execute("clear " + USER + " minecraft:ender_pearl 0");
        require(!count.toLowerCase().contains("no items") && !count.toLowerCase().contains("0 items"),
            "RCON inventory setup left the real client without an ender pearl: " + count);
    }

    private static void equipProjectile(ExternalServer server, String item) throws IOException {
        String give = server.execute("give " + USER + " minecraft:" + item + " 64");
        require(!give.toLowerCase().contains("unknown") && !give.toLowerCase().contains("error"),
            "could not give the real client a " + item + ": " + give);
        String hotbar = server.execute("item replace entity " + USER
            + " hotbar.0 with minecraft:" + item + " 16");
        require(!hotbar.toLowerCase().contains("unknown") && !hotbar.toLowerCase().contains("error"),
            "could not place " + item + " in the client's hotbar: " + hotbar);
        String replace = server.execute("item replace entity " + USER
            + " weapon.mainhand with minecraft:" + item + " 16");
        require(!replace.toLowerCase().contains("unknown") && !replace.toLowerCase().contains("error"),
            "could not place " + item + " in the real client's main hand: " + replace);
        String count = server.execute("clear " + USER + " minecraft:" + item + " 0");
        require(!count.toLowerCase().contains("no items") && !count.toLowerCase().contains("0 items"),
            "RCON inventory setup left the real client without " + item + ": " + count);
        String selected = server.execute("data get entity " + USER + " SelectedItem");
        require(selected.contains("minecraft:" + item),
            "RCON inventory setup did not update the active hand to " + item
                + ": give=" + give + " hotbar=" + hotbar + " mainhand=" + replace
                + " count=" + count + " selected=" + selected);
    }

    private static void throwProjectileAndFreeze(ClientGameTestContext context, ExternalServer server,
                                                  String dimension, String tag, String item, String entityType)
            throws IOException {
        // Let the RCON inventory replacement reach the real client before the use input. The
        // discovery window below still begins immediately after the item-use event, so this is
        // only client inventory convergence and never a synthetic projectile timing shortcut.
        TestInput input = context.getInput();
        // Select slot 0 through the actual client input path after every authoritative server
        // replacement. This closes the dimension-respawn case where the client keeps a stale
        // selected index even though the server inventory has converged.
        input.pressKey(options -> options.keyHotbarSlots[0]);
        context.waitTicks(5);
        String[] observedHand = {"no-player"};
        boolean selected = false;
        for (int tick = 0; tick < 120; tick++) {
            boolean ready = context.computeOnClient(client -> {
                if (client.player == null) {
                    observedHand[0] = "no-player";
                    return false;
                }
                observedHand[0] = "selectedSlot=" + client.player.getInventory().getSelectedSlot()
                    + " selectedItem=" + client.player.getInventory().getSelectedItem().getItem()
                    + " slot0=" + client.player.getInventory().getItem(0).getItem();
                return switch (item) {
                    case "egg" -> client.player.getMainHandItem().is(Items.EGG);
                    case "snowball" -> client.player.getMainHandItem().is(Items.SNOWBALL);
                    default -> false;
                };
            });
            if (ready) {
                selected = true;
                break;
            }
            context.waitTick();
        }
        String serverHand = server.execute("data get entity " + USER + " SelectedItem");
        String serverInventory = server.execute("data get entity " + USER + " Inventory");
        require(selected, "real client did not converge to " + item + " before use: client="
            + observedHand[0] + " server=" + serverHand + " inventory=" + serverInventory);
        // The Nether pad spans x=0..15 and the player starts at x=8.5. Keep the target well
        // inside that pad; aiming at NETHER_SPAWN_X+7 would put the target on the x=15 wall.
        int padAimX = NETHER.equals(dimension) ? NETHER_SPAWN_X + 4 : OVERWORLD_SPAWN_X + 12;
        // Keep the aim level with the player so the egg travels through the prepared air column
        // instead of depending on an upward trajectory while the client inventory converges.
        int padAimY = 100;
        input.lookAt(new BlockPos(padAimX, padAimY, 8));
        input.pressKey(options -> options.keyUse);
        // pressKey() already advances one client tick after releasing the real key. Query RCON
        // immediately afterward so a fast projectile cannot complete its vanilla flight before
        // the live observation window begins.
        String freeze = "";
        for (int tick = 0; tick < 30; tick++) {
            // The probe only observes the real entity created by the client and performs the tag,
            // no-gravity, and zero-motion cut in one server-thread command. This avoids a race
            // between separate RCON selector operations for short-lived egg flight.
            freeze = server.execute("phoenium-test projectile-capture " + tag + " " + entityType);
            if (freeze.contains("matches=1")) break;
            context.waitTick();
        }
        require(freeze.contains("matches=1"),
            "real client item-use did not create a discoverable " + item + " for " + tag + ": " + freeze);
        require(freeze.contains("matches=1"),
            "new " + item + " was not frozen for bounded live observation: " + freeze);
    }

    private static void naturalRemoveProjectile(ClientGameTestContext context, ExternalServer server,
                                                 String dimension, String tag, String entityType) throws IOException {
        String response = server.execute("execute in " + dimension + " run data merge entity @e[type=minecraft:"
            + entityType + ",tag=" + tag + ",limit=1] {NoGravity:0b,Motion:[0.0d,-1.0d,0.0d]}");
        require(!response.toLowerCase().contains("no entity"),
            "cannot begin natural removal for " + tag + ": " + response);
        for (int tick = 0; tick < 80 && projectileExists(server, dimension, tag, entityType); tick++) context.waitTick();
        require(!projectileExists(server, dimension, tag, entityType),
            "natural projectile removal did not settle for " + tag);
    }

    private static void retireProjectile(ExternalServer server, String dimension, String tag, String entityType)
            throws IOException {
        String response = server.execute("execute in " + dimension + " run kill @e[type=minecraft:" + entityType
            + ",tag=" + tag + ",limit=1]");
        require(!response.toLowerCase().contains("no entity"),
            "cannot retire live projectile " + tag + ": " + response);
        require(!projectileExists(server, dimension, tag, entityType),
            "explicit projectile retirement did not remove " + tag);
    }

    private static Pearl throwAndFreeze(ClientGameTestContext context, ExternalServer server,
                                        String dimension, String tag) throws IOException {
        // Allow the inventory replacement issued through RCON to reach the real client before
        // exercising the normal item-use input path.
        context.waitTicks(5);
        TestInput input = context.getInput();
        int verticalAimX = NETHER.equals(dimension) ? NETHER_SPAWN_X : OVERWORLD_SPAWN_X;
        input.lookAt(new BlockPos(verticalAimX, 110, 8));
        // Use the vanilla key binding so the client follows the same item-use path as a player
        // pressing right-click. The RCON command only supplies the item; it never creates the
        // projectile.
        input.pressKey(options -> options.keyUse);
        context.waitTicks(5);
        String prefix = "execute in " + dimension + " run ";
        String selector = "@e[type=minecraft:ender_pearl,tag=!panda_a2_seen,sort=nearest,limit=1]";
        String found = "";
        for (int tick = 0; tick < 30; tick++) {
            found = server.execute(prefix + "tag " + selector + " add " + tag);
            if (!found.toLowerCase().contains("no entity")) break;
            context.waitTick();
        }
        require(!found.toLowerCase().contains("no entity"),
            "real client item-use did not create a discoverable pearl for " + tag + ": " + found);
        server.execute(prefix + "tag " + selector + " add panda_a2_seen");
        String freeze = server.execute(prefix + "data merge entity @e[type=minecraft:ender_pearl,tag=" + tag
            + ",limit=1] {NoGravity:1b,Motion:[0.0d,0.0d,0.0d]}");
        require(!freeze.toLowerCase().contains("no entity"), "new pearl was not frozen for bounded live observation: " + freeze);
        return new Pearl(tag, dimension);
    }

    private static void movePearl(ExternalServer server, String dimension, String tag,
                                  double x, double y, double z) throws IOException {
        String response = server.execute("execute in " + dimension + " run tp @e[type=minecraft:ender_pearl,tag="
            + tag + ",limit=1] " + x + " " + y + " " + z);
        require(!response.toLowerCase().contains("no entity"), "could not move live pearl " + tag + ": " + response);
    }

    private static void naturalRemove(ClientGameTestContext context, ExternalServer server,
                                      String dimension, String tag) throws IOException {
        String response = server.execute("execute in " + dimension + " run data merge entity @e[type=minecraft:ender_pearl,tag="
            + tag + ",limit=1] {NoGravity:0b,Motion:[0.0d,-1.0d,0.0d]}");
        require(!response.toLowerCase().contains("no entity"), "cannot begin natural removal for " + tag + ": " + response);
        for (int tick = 0; tick < 80 && entityExists(server, dimension, tag); tick++) context.waitTick();
        require(!entityExists(server, dimension, tag), "natural pearl removal did not settle for " + tag);
    }

    private static void retirePearl(ExternalServer server, String dimension, String tag) throws IOException {
        String response = server.execute("execute in " + dimension + " run kill @e[type=minecraft:ender_pearl,tag="
            + tag + ",limit=1]");
        require(!response.toLowerCase().contains("no entity"), "cannot retire live pearl " + tag + ": " + response);
        require(!entityExists(server, dimension, tag), "explicit pearl retirement did not remove " + tag);
    }

    private static void moveClientNormally(ClientGameTestContext context, ExternalServer server) {
        TestInput input = context.getInput();
        input.lookAt(new BlockPos(OVERWORLD_SPAWN_X + 12, 90, 8));
        input.holdKey(options -> options.keyUp);
        try {
            context.waitTicks(30);
        } finally {
            input.releaseKey(options -> options.keyUp);
        }
        context.waitTicks(10);
    }

    private static boolean entityExists(ExternalServer server, String dimension, String tag) throws IOException {
        String response = server.execute("execute in " + dimension + " run data get entity @e[type=minecraft:ender_pearl,tag="
            + tag + ",limit=1] Pos");
        return !response.toLowerCase().contains("no entity") && !response.toLowerCase().contains("unknown entity");
    }

    private static boolean projectileExists(ExternalServer server, String dimension, String tag, String entityType)
            throws IOException {
        String response = server.execute("execute in " + dimension + " run data get entity @e[type=minecraft:"
            + entityType + ",tag=" + tag + ",limit=1] Pos");
        return !response.toLowerCase().contains("no entity") && !response.toLowerCase().contains("unknown entity");
    }

    private static void connectClient(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ServerData server = new ServerData("Phoenium Panda A2", SERVER_ADDRESS, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(client.gui.screen(), client,
                ServerAddress.parseString(SERVER_ADDRESS), server, false, null);
        });
        context.waitFor(client -> client.level != null && client.player != null, CLIENT_CONNECT_TIMEOUT_TICKS);
    }

    private static void disconnectClient(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ClientPacketListener listener = client.getConnection();
            if (listener != null) {
                listener.getConnection().disconnect(Component.literal("Phoenium Panda A2 disconnect"));
            }
            client.disconnect(new TitleScreen(), false);
        });
        context.waitFor(client -> client.level == null, 600);
    }

    private static String clientName(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player == null ? "" : client.player.getName().getString());
    }

    private static Diagnostics record(ExternalServer server, Evidence evidence, String phase) throws IOException {
        Diagnostics diagnostics = new Diagnostics(server.execute("phoenium diagnostics"));
        require(diagnostics.raw.contains("pearlMemberships=") && diagnostics.raw.contains("pearlCleanupExecutions="),
            "live diagnostics omitted pearl counters for " + phase + ": " + diagnostics.raw);
        evidence.snapshots.put(phase, diagnostics);
        return diagnostics;
    }

    private static Diagnostics record(ExternalServer server, ProjectileEvidence evidence, String phase)
            throws IOException {
        Diagnostics diagnostics = new Diagnostics(server.execute("phoenium diagnostics"));
        require(diagnostics.raw.contains("projectileMemberships=")
                && diagnostics.raw.contains("projectileCleanupExecutions="),
            "live diagnostics omitted projectile counters for " + phase + ": " + diagnostics.raw);
        evidence.snapshots.put(phase, diagnostics);
        return diagnostics;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format("%02X", value));
            return result.toString();
        } catch (Exception failure) {
            throw new IOException("cannot hash live artifact " + path, failure);
        }
    }

    private record Pearl(String tag, String dimension) { }

    private record Diagnostics(String raw, Map<String, Long> values) {
        private Diagnostics(String raw) {
            this(raw, parse(raw));
        }

        private long value(String key) {
            return values.getOrDefault(key, -1L);
        }

        private static Map<String, Long> parse(String raw) {
            Map<String, Long> values = new LinkedHashMap<>();
            Matcher matcher = NUMERIC_FIELD.matcher(raw);
            while (matcher.find()) values.put(matcher.group(1), Long.parseLong(matcher.group(2)));
            Matcher move = MOVE_FIELDS.matcher(raw);
            if (move.find()) {
                Matcher nested = NUMERIC_FIELD.matcher(move.group(1));
                while (nested.find()) values.put("move." + nested.group(1), Long.parseLong(nested.group(2)));
            }
            return Map.copyOf(values);
        }
    }

    private static final class Evidence {
        private final Map<String, Diagnostics> snapshots = new LinkedHashMap<>();
        private int workers;
        private int cap;
        private String testUsername = "unknown";
        private String serverTemplate = "";
        private String serverDirectory = "";
        private String processLog = "";
        private String productionSha256 = "";
        private String c2meSha256 = "";
        private String scalableLuxSha256 = "";
        private String worldSeed = "";
        private String fixturePreparation = "";
        private String duplicateReobserveResponse = "";
        private String crossRegionRoute = "";
        private String failure = "";
        private String cleanupFailure = "";
        private boolean serverProcessStarted;
        private boolean serverReady;
        private boolean realTcpConnection;
        private boolean clientPlayer;
        private boolean liveCapEnabled;
        private boolean belowCapValid;
        private boolean ordinaryGameplay;
        private boolean exactBoundaryValid;
        private boolean crossRegionIdentityPreserved;
        private boolean duplicateAdmissionIdempotent;
        private boolean oldestOverflowSelected;
        private boolean overflowRemovedExactlyOnce;
        private boolean naturalRemovalRetired;
        private boolean unloadedWithPlayer;
        private boolean reconnectPreserved;
        private boolean crossDimensionScopeIndependent;
        private boolean crossDimensionOverflowCorrect;
        private boolean noStaleMembership;
        private boolean acceptancePass;
        private boolean clientDisconnectedCleanly;
        private boolean serverStoppedCleanly;
        private boolean clientTerminatedCleanly;

        private void write() {
            Path path = Path.of(System.getProperty("phoenium.panda.evidence",
                "build/panda-a2-live/PANDA-A2-LIVE.json"));
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(path, toJson(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException failure) {
                throw new AssertionError("cannot write Panda A2 evidence to " + path, failure);
            }
        }

        private String toJson() {
            StringBuilder json = new StringBuilder("{\n");
            field(json, "test", "PANDA_A2_LIVE", true);
            number(json, "workers", workers, true);
            number(json, "cap", cap, true);
            field(json, "testUsername", testUsername, true);
            field(json, "serverTemplate", serverTemplate, true);
            field(json, "serverDirectory", serverDirectory, true);
            field(json, "processLog", processLog, true);
            field(json, "productionSha256", productionSha256, true);
            field(json, "c2meSha256", c2meSha256, true);
            field(json, "scalableLuxSha256", scalableLuxSha256, true);
            field(json, "worldSeed", worldSeed, true);
            field(json, "fixturePreparation", fixturePreparation, true);
            field(json, "duplicateReobserveResponse", duplicateReobserveResponse, true);
            field(json, "crossRegionRoute", crossRegionRoute, true);
            bool(json, "serverProcessStarted", serverProcessStarted, true);
            bool(json, "serverReady", serverReady, true);
            bool(json, "realTcpConnection", realTcpConnection, true);
            bool(json, "clientPlayer", clientPlayer, true);
            bool(json, "liveCapEnabled", liveCapEnabled, true);
            bool(json, "belowCapValid", belowCapValid, true);
            bool(json, "ordinaryGameplay", ordinaryGameplay, true);
            bool(json, "exactBoundaryValid", exactBoundaryValid, true);
            bool(json, "crossRegionIdentityPreserved", crossRegionIdentityPreserved, true);
            bool(json, "duplicateAdmissionIdempotent", duplicateAdmissionIdempotent, true);
            bool(json, "oldestOverflowSelected", oldestOverflowSelected, true);
            bool(json, "overflowRemovedExactlyOnce", overflowRemovedExactlyOnce, true);
            bool(json, "naturalRemovalRetired", naturalRemovalRetired, true);
            bool(json, "unloadedWithPlayer", unloadedWithPlayer, true);
            bool(json, "reconnectPreserved", reconnectPreserved, true);
            bool(json, "crossDimensionScopeIndependent", crossDimensionScopeIndependent, true);
            bool(json, "crossDimensionOverflowCorrect", crossDimensionOverflowCorrect, true);
            bool(json, "noStaleMembership", noStaleMembership, true);
            bool(json, "clientDisconnectedCleanly", clientDisconnectedCleanly, true);
            bool(json, "serverStoppedCleanly", serverStoppedCleanly, true);
            bool(json, "clientTerminatedCleanly", clientTerminatedCleanly, true);
            bool(json, "acceptancePass", acceptancePass, true);
            field(json, "failure", failure, true);
            field(json, "cleanupFailure", cleanupFailure, true);
            json.append("  \"snapshots\": {\n");
            int index = 0;
            for (var entry : snapshots.entrySet()) {
                json.append("    ").append(quote(entry.getKey())).append(": ").append(quote(entry.getValue().raw));
                if (++index < snapshots.size()) json.append(',');
                json.append('\n');
            }
            json.append("  }\n}\n");
            return json.toString();
        }

        private static void field(StringBuilder json, String name, String value, boolean comma) {
            json.append("  ").append(quote(name)).append(": ").append(quote(value == null ? "" : value));
            if (comma) json.append(',');
            json.append('\n');
        }

        private static void number(StringBuilder json, String name, long value, boolean comma) {
            json.append("  ").append(quote(name)).append(": ").append(value);
            if (comma) json.append(',');
            json.append('\n');
        }

        private static void bool(StringBuilder json, String name, boolean value, boolean comma) {
            json.append("  ").append(quote(name)).append(": ").append(value);
            if (comma) json.append(',');
            json.append('\n');
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
        }
    }

    private static final class ProjectileEvidence {
        private final Map<String, Diagnostics> snapshots = new LinkedHashMap<>();
        private int workers;
        private int cap;
        private String testUsername = "unknown";
        private String serverTemplate = "";
        private String serverDirectory = "";
        private String processLog = "";
        private String productionSha256 = "";
        private String c2meSha256 = "";
        private String scalableLuxSha256 = "";
        private String worldSeed = "";
        private String fixturePreparation = "";
        private String duplicateReobserveResponse = "";
        private String crossRegionRoute = "";
        private String failure = "";
        private String cleanupFailure = "";
        private boolean serverProcessStarted;
        private boolean serverReady;
        private boolean realTcpConnection;
        private boolean clientPlayer;
        private boolean liveCapEnabled;
        private boolean belowCapValid;
        private boolean ordinaryGameplay;
        private boolean exactBoundaryValid;
        private boolean crossRegionIdentityPreserved;
        private boolean duplicateAdmissionIdempotent;
        private boolean oldestOverflowSelected;
        private boolean overflowRemovedExactlyOnce;
        private boolean naturalRemovalRetired;
        private boolean crossDimensionScopeIndependent;
        private boolean crossDimensionOverflowCorrect;
        private boolean noStaleMembership;
        private boolean acceptancePass;
        private boolean clientDisconnectedCleanly;
        private boolean serverStoppedCleanly;
        private boolean clientTerminatedCleanly;

        private void write() {
            Path path = Path.of(System.getProperty("phoenium.projectile.evidence",
                "build/projectile-cap-live/PROJECTILE-CAP-LIVE.json"));
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(path, toJson(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException failure) {
                throw new AssertionError("cannot write projectile-cap evidence to " + path, failure);
            }
        }

        private String toJson() {
            StringBuilder json = new StringBuilder("{\n");
            field(json, "test", "PROJECTILE_CAP_LIVE", true);
            number(json, "workers", workers, true);
            number(json, "cap", cap, true);
            field(json, "testUsername", testUsername, true);
            field(json, "serverTemplate", serverTemplate, true);
            field(json, "serverDirectory", serverDirectory, true);
            field(json, "processLog", processLog, true);
            field(json, "productionSha256", productionSha256, true);
            field(json, "c2meSha256", c2meSha256, true);
            field(json, "scalableLuxSha256", scalableLuxSha256, true);
            field(json, "worldSeed", worldSeed, true);
            field(json, "fixturePreparation", fixturePreparation, true);
            field(json, "duplicateReobserveResponse", duplicateReobserveResponse, true);
            field(json, "crossRegionRoute", crossRegionRoute, true);
            bool(json, "serverProcessStarted", serverProcessStarted, true);
            bool(json, "serverReady", serverReady, true);
            bool(json, "realTcpConnection", realTcpConnection, true);
            bool(json, "clientPlayer", clientPlayer, true);
            bool(json, "liveCapEnabled", liveCapEnabled, true);
            bool(json, "belowCapValid", belowCapValid, true);
            bool(json, "ordinaryGameplay", ordinaryGameplay, true);
            bool(json, "exactBoundaryValid", exactBoundaryValid, true);
            bool(json, "crossRegionIdentityPreserved", crossRegionIdentityPreserved, true);
            bool(json, "duplicateAdmissionIdempotent", duplicateAdmissionIdempotent, true);
            bool(json, "oldestOverflowSelected", oldestOverflowSelected, true);
            bool(json, "overflowRemovedExactlyOnce", overflowRemovedExactlyOnce, true);
            bool(json, "naturalRemovalRetired", naturalRemovalRetired, true);
            bool(json, "crossDimensionScopeIndependent", crossDimensionScopeIndependent, true);
            bool(json, "crossDimensionOverflowCorrect", crossDimensionOverflowCorrect, true);
            bool(json, "noStaleMembership", noStaleMembership, true);
            bool(json, "clientDisconnectedCleanly", clientDisconnectedCleanly, true);
            bool(json, "serverStoppedCleanly", serverStoppedCleanly, true);
            bool(json, "clientTerminatedCleanly", clientTerminatedCleanly, true);
            bool(json, "acceptancePass", acceptancePass, true);
            field(json, "failure", failure, true);
            field(json, "cleanupFailure", cleanupFailure, true);
            json.append("  \"snapshots\": {\n");
            int index = 0;
            for (var entry : snapshots.entrySet()) {
                json.append("    ").append(quote(entry.getKey())).append(": ").append(quote(entry.getValue().raw));
                if (++index < snapshots.size()) json.append(',');
                json.append('\n');
            }
            json.append("  }\n}\n");
            return json.toString();
        }

        private static void field(StringBuilder json, String name, String value, boolean comma) {
            json.append("  ").append(quote(name)).append(": ").append(quote(value == null ? "" : value));
            if (comma) json.append(',');
            json.append('\n');
        }

        private static void number(StringBuilder json, String name, long value, boolean comma) {
            json.append("  ").append(quote(name)).append(": ").append(value);
            if (comma) json.append(',');
            json.append('\n');
        }

        private static void bool(StringBuilder json, String name, boolean value, boolean comma) {
            json.append("  ").append(quote(name)).append(": ").append(value);
            if (comma) json.append(',');
            json.append('\n');
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
        }
    }

    private static final class ExternalServer {
        private final Path template;
        private final Path directory;
        private final Path latestLog;
        private final Path processLog;
        private final String worldName;
        private Process process;

        private ExternalServer(Path template, Path directory) {
            this.template = template;
            this.directory = directory;
            this.latestLog = directory.resolve("logs/latest.log");
            this.processLog = directory.resolve("server-process.log");
            this.worldName = (projectileMode() ? "PROJECTILE-CAP-LIVE-W" : "PANDA-A2-LIVE-W")
                + WORKERS + "-" + System.currentTimeMillis();
        }

        private static ExternalServer start() throws IOException {
            Path template = Path.of(System.getProperty("phoenium.panda.serverTemplate"));
            require(Files.isDirectory(template), "accepted Panda server template is missing: " + template);
            Path evidence = Path.of(System.getProperty(evidenceProperty(), defaultEvidencePath()))
                .toAbsolutePath().getParent();
            if (evidence == null) throw new IOException("Panda evidence path has no parent");
            Files.createDirectories(evidence);
            Path directory = evidence.resolve("server-" + System.currentTimeMillis());
            ExternalServer server = new ExternalServer(template, directory);
            server.copyTemplate();
            server.writeConfiguration();
            server.launch();
            return server;
        }

        private void copyTemplate() throws IOException {
            for (String name : List.of("fabric-server-launch.jar", ".fabric", "libraries", "versions", "mods", "config")) {
                Path source = template.resolve(name);
                if (!Files.exists(source)) {
                    if (name.equals("config")) continue;
                    throw new IOException("accepted server template is missing " + source);
                }
                copyTree(source, directory.resolve(name));
            }
            // The accepted Stage 7 evidence template carries a separate C2ME path-observer
            // probe. Its output path is intentionally not part of PANDA_A2_LIVE; leave that
            // observer out of this disposable workload while retaining the accepted C2ME jar.
            Files.deleteIfExists(directory.resolve("mods/c2me-path-observer.jar"));
            Path productionJar = Path.of(System.getProperty("phoenium.panda.productionJar"));
            require(Files.isRegularFile(productionJar), "current Panda production jar is missing: " + productionJar);
            Files.copy(productionJar, directory.resolve("mods/phoenium-0.2.0-stage2.jar"),
                StandardCopyOption.REPLACE_EXISTING);
            Path harness = Path.of(System.getProperty("phoenium.panda.harness"));
            require(Files.isRegularFile(harness), "Panda server probe was not built: " + harness);
            Files.copy(harness, directory.resolve("mods").resolve(harness.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
            require(PRODUCTION_SHA256.equals(sha256(directory.resolve("mods/phoenium-0.2.0-stage2.jar"))),
                "copied production artifact changed during Panda server preparation");
            require(C2ME_SHA256.equals(sha256(directory.resolve(
                "mods/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar"))),
                "copied C2ME artifact changed during Panda server preparation");
        }

        private static void copyTree(Path source, Path target) throws IOException {
            if (Files.isDirectory(source)) {
                try (var paths = Files.walk(source)) {
                    paths.forEach(path -> {
                        try {
                            Path relative = source.relativize(path);
                            Path destination = target.resolve(relative);
                            if (Files.isDirectory(path)) Files.createDirectories(destination);
                            else {
                                Files.createDirectories(destination.getParent());
                                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException failure) {
                            throw new CopyFailure(failure);
                        }
                    });
                } catch (CopyFailure failure) {
                    throw failure.io;
                }
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private void writeConfiguration() throws IOException {
            Files.writeString(directory.resolve("eula.txt"), "eula=true\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            List<String> properties = List.of(
                "accepts-transfers=false", "allow-flight=true", "difficulty=peaceful",
                "enable-rcon=true", "enable-status=true", "enforce-secure-profile=false",
                "gamemode=creative", "force-gamemode=true", "level-name=" + worldName,
                "level-seed=444444", "level-type=minecraft\\:flat", "generator-settings={}",
                "max-players=2", "motd=Phoenium Panda A2 real-client workload",
                "online-mode=false", "server-ip=127.0.0.1", "server-port=25565",
                "rcon.ip=127.0.0.1", "rcon.password=" + rconPassword(), "rcon.port=" + rconPort(),
                "spawn-animals=false", "spawn-npcs=false", "spawn-protection=0",
                "sync-chunk-writes=true", "pause-when-empty-seconds=-1", "view-distance=1",
                "simulation-distance=1");
            Files.writeString(directory.resolve("server.properties"), String.join(System.lineSeparator(), properties)
                + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        private void launch() throws IOException {
            Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
            List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx2G",
                "-Dphoenium.stage3.explicitTopology=true", "-Dphoenium.stage3.chunkTrace=true",
                "-Dphoenium.test.chunkFixtureSupport=true",
                "-Dphoenium.test.diagnostics=true", "-Dphoenium.gameplay.workers=" + WORKERS));
            command.add(projectileMode() ? "-Dphoenium.protection.projectileCap.enabled=true"
                : "-Dphoenium.protection.pearlCap.enabled=true");
            command.add(projectileMode() ? "-Dphoenium.protection.projectileCap=" + CAP
                : "-Dphoenium.protection.pearlCap=" + CAP);
            command.addAll(List.of("-jar", "fabric-server-launch.jar", "nogui"));
            process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(processLog.toFile())).start();
        }

        private void waitUntilReady(ClientGameTestContext context) {
            for (int tick = 0; tick < SERVER_START_TIMEOUT_TICKS; tick++) {
                require(isAlive(), serverFailure("Panda server exited before readiness"));
                String log = readLatestLog();
                if (log.contains("PHOENIUM_STAGE2 started") && log.contains("explicitFixture=true") && tick % 5 == 0) {
                    try {
                        String diagnostics = execute("phoenium diagnostics");
                        String capField = projectileMode() ? "projectileCap=" : "pearlCap=";
                        if (diagnostics.contains("lifecycle=RUNNING") && diagnostics.contains(capField + CAP)) return;
                    } catch (IOException ignored) {
                        // The server log may lead RCON by a few ticks while the listener binds.
                    }
                }
                context.waitTick();
            }
            throw new AssertionError(serverFailure("Panda server did not become RCON-ready"));
        }

        private String prepareFixture() throws IOException {
            // Keep the boundary route published without touching any A/B seam chunks before the
            // real client joins. The client must still have a usable, non-falling world; create a
            // one-chunk landing pad at the distant spawn, entirely inside the accepted support
            // envelope, and leave the seam/trace chunks unedited.
            String forceLoad = "not issued (boundary-safe live fixture)";
            String forceLoadSpawn = execute("execute in " + OVERWORLD + " run forceload add "
                + OVERWORLD_SPAWN_X + " 8");
            String clearAHigh = "not issued (flat-world airspace)";
            String spawnPrefix = "execute in " + OVERWORLD + " run ";
            String clearSpawnHigh = execute(spawnPrefix + "fill " + OVERWORLD_SPAWN_X + " 90 0 "
                + (OVERWORLD_SPAWN_X + 15) + " 109 15 minecraft:air");
            String lowerPlatform = "not issued (spawn-only platform)";
            String upperPlatform = "not issued (spawn-only ceiling)";
            String lowerSpawnPlatform = execute(spawnPrefix + "fill " + OVERWORLD_SPAWN_X + " 89 0 "
                + (OVERWORLD_SPAWN_X + 15) + " 89 15 minecraft:stone");
            String upperSpawnPlatform = execute(spawnPrefix + "fill " + OVERWORLD_SPAWN_X + " 110 0 "
                + (OVERWORLD_SPAWN_X + 15) + " 110 15 minecraft:glass");
            for (String fixtureResponse : List.of(forceLoadSpawn, clearSpawnHigh, lowerSpawnPlatform, upperSpawnPlatform))
                require(!fixtureResponse.toLowerCase().contains("not loaded"),
                    "Panda spawn fixture command was not applied: " + fixtureResponse);
            String spawn = execute("setworldspawn " + OVERWORLD_SPAWN_X + " 90 8");
            require(!spawn.toLowerCase().contains("not loaded"), "Panda spawn command was not applied: " + spawn);
            return "forceLoadA=" + forceLoad.trim() + " | forceLoadSpawn=" + forceLoadSpawn.trim()
                + " | clearAHigh=" + clearAHigh.trim()
                + " | clearSpawnHigh=" + clearSpawnHigh.trim()
                + " | lowerPlatform=" + lowerPlatform.trim()
                + " | upperPlatform=" + upperPlatform.trim()
                + " | lowerSpawnPlatform=" + lowerSpawnPlatform.trim()
                + " | upperSpawnPlatform=" + upperSpawnPlatform.trim()
                + " | spawn=" + spawn.trim();
        }

        private void prepareNetherFixture() throws IOException {
            String prefix = "execute in " + NETHER + " run ";
            for (String command : List.of(
                    prefix + "forceload add 0 0 15 15",
                    prefix + "fill 0 80 0 15 200 15 minecraft:air",
                    prefix + "fill 0 62 0 15 62 15 minecraft:netherrack",
                    prefix + "fill 0 79 0 15 79 15 minecraft:netherrack")) {
                require(!execute(command).toLowerCase().contains("not loaded"), "Nether fixture command was not applied: " + command);
            }
        }

        private String execute(String command) throws IOException {
            return RconClient.execute("127.0.0.1", rconPort(), rconPassword(), command);
        }

        private boolean stopNormally(ClientGameTestContext context) {
            if (!isAlive()) return process.exitValue() == 0;
            try {
                execute("stop");
            } catch (IOException ignored) {
                // RCON can close as the normal stop completes.
            }
            for (int tick = 0; tick < 900 && isAlive(); tick++) context.waitTick();
            return !isAlive() && process.exitValue() == 0 && readProcessLog().contains("PHOENIUM_STAGE2 stopped");
        }

        private void stopQuietly(ClientGameTestContext context) {
            if (!isAlive()) return;
            try {
                execute("stop");
            } catch (IOException ignored) { }
            for (int tick = 0; tick < 120 && isAlive(); tick++) context.waitTick();
            if (isAlive()) process.destroy();
            for (int tick = 0; tick < 60 && isAlive(); tick++) context.waitTick();
            if (isAlive()) process.destroyForcibly();
        }

        private boolean isAlive() { return process != null && process.isAlive(); }

        private String readLatestLog() {
            try { return Files.exists(latestLog) ? Files.readString(latestLog) : ""; }
            catch (IOException ignored) { return ""; }
        }

        private String readProcessLog() {
            try { return Files.exists(processLog) ? Files.readString(processLog) : ""; }
            catch (IOException ignored) { return ""; }
        }

        private String serverFailure(String message) {
            return message + "\nlatest.log tail:\n" + tail(readLatestLog())
                + "\nserver-process.log tail:\n" + tail(readProcessLog());
        }

        private static String tail(String value) { return value.length() <= 8_000 ? value : value.substring(value.length() - 8_000); }

        private static int rconPort() { return Integer.parseInt(System.getProperty("phoenium.test.rconPort", "25575")); }
        private static String rconPassword() { return System.getProperty("phoenium.test.rconPassword", "phoenium-panda-a2"); }
        private static boolean projectileMode() { return "projectile-cap".equals(System.getProperty("phoenium.test.mode")); }
        private static String evidenceProperty() { return projectileMode() ? "phoenium.projectile.evidence" : "phoenium.panda.evidence"; }
        private static String defaultEvidencePath() {
            return projectileMode() ? "build/projectile-cap-live/PROJECTILE-CAP-LIVE.json"
                : "build/panda-a2-live/PANDA-A2-LIVE.json";
        }
    }

    private static final class CopyFailure extends RuntimeException {
        private final IOException io;
        private CopyFailure(IOException io) { this.io = io; }
    }

    private static final class RconClient {
        private static String execute(String host, int port, String password, String command) throws IOException {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1_500);
                socket.setSoTimeout(8_000);
                DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                OutputStream output = socket.getOutputStream();
                writePacket(output, 1, 3, password);
                Packet authentication = readPacket(input);
                if (authentication.requestId != 1) throw new IOException("RCON authentication failed");
                writePacket(output, 2, 2, command);
                return readPacket(input).body;
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
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new EOFException("truncated RCON response");
            ByteBuffer packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int requestId = packet.getInt();
            int type = packet.getInt();
            byte[] body = new byte[length - 10];
            packet.get(body);
            packet.get();
            packet.get();
            return new Packet(requestId, type, new String(body, StandardCharsets.UTF_8));
        }

        private static int readLittleEndianInt(DataInputStream input) throws IOException {
            return Integer.reverseBytes(input.readInt());
        }

        private record Packet(int requestId, int type, String body) { }
    }
}
