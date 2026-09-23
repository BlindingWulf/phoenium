package dev.phoenium.stage7repair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.phoenium.mixin.PearlPortalProcessorAccess;
import dev.phoenium.live.CrossDimensionReplacementBoundary;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.OwnershipContext;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.portal.TeleportTransition;
import org.slf4j.LoggerFactory;

/**
 * Installed-harness-only observer of the retained portal lifecycle and native admission.
 * It has no route or mutation hooks and never throws into the native operation.
 * A separate status command and log gate turn recorded violations into test failures.
 */
public final class PortalLifecycleObserverBridge implements DedicatedServerModInitializer {
    private static final int TRACE_LIMIT = 4096;
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final AtomicLong EVENTS = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong VIOLATIONS = new AtomicLong();
    private static final Map<UUID, Transaction> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Transaction> LOOKUP = new ThreadLocal<>();
    private static final ThreadLocal<PlatformScope> NATIVE_PLATFORM = new ThreadLocal<>();
    private static final PortalNativeCallCorrelation NATIVE_CALLS = new PortalNativeCallCorrelation();
    private static final ArrayDeque<JsonObject> RECENT = new ArrayDeque<>();
    private static volatile JsonObject firstViolation;
    private static final String PRODUCT_SHA = modHash("phoenium");
    private static final String TEST_SOURCE_SHA = modHash("phoenium-stage7-repair-probe");

    private record Transaction(long generation, long productGeneration, UUID uuid, String type, String route,
            String sourceWorld, String sourceObject, long nativeIncarnation, Set<String> markerPhases) {
        // Mutable only under event callbacks on the server thread. Volatile protects diagnostics.
        private static final String SCHEMA = "stage7-portal-tx-v1";
    }
    private record PlatformScope(Transaction transaction, String destinationWorld) { }

    private static String world(Entity entity) { return entity.level().dimension().identifier().toString(); }
    private static String ownerWorld() {
        return OwnershipContext.current().map(c -> c.region().world().value()).orElse("NONE");
    }
    private static String ownerContext() {
        return OwnershipContext.current().map(Object::toString).orElse("NONE");
    }
    private static String route(Entity entity) {
        try {
            Entity routeSource = entity;
            if (entity.portalProcess == null && entity.isPassenger()) {
                Entity rootVehicle = entity.getRootVehicle();
                if (rootVehicle != entity && rootVehicle.portalProcess != null) routeSource = rootVehicle;
            }
            if (routeSource.portalProcess == null) return "DIRECT_OR_UNKNOWN";
            var portal = ((PearlPortalProcessorAccess) routeSource.portalProcess).phoenium$portal();
            if (portal instanceof NetherPortalBlock)
                return world(routeSource).equals("minecraft:the_nether") ? "NETHER_RETURN" : "NETHER_ENTRY";
            if (portal instanceof EndPortalBlock)
                return world(routeSource).equals("minecraft:the_end") ? "END_EXIT" : "END_ENTRY";
            if (portal instanceof EndGatewayBlock) return "END_GATEWAY";
            return "OTHER_PORTAL:" + portal.getClass().getName();
        } catch (Throwable unavailable) { return "PORTAL_UNKNOWN:" + unavailable.getClass().getSimpleName(); }
    }
    private static String modHash(String id) {
        try {
            var container = FabricLoader.getInstance().getModContainer(id).orElseThrow();
            Path path = container.getOrigin().getPaths().getFirst();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (Throwable unavailable) { return "UNAVAILABLE:" + unavailable.getClass().getSimpleName(); }
    }
    private static JsonObject event(String phase, Entity entity, Transaction transaction, String destinationWorld) {
        JsonObject json = new JsonObject();
        json.addProperty("schema", Transaction.SCHEMA);
        json.addProperty("phase", phase);
        json.addProperty("generation", transaction == null ? -1 : transaction.generation());
        json.addProperty("productGeneration", transaction == null ? -1 : transaction.productGeneration());
        json.addProperty("uuid", entity.getUUID().toString());
        json.addProperty("type", entity.getType().toString());
        json.addProperty("nativeId", entity.getId());
        json.addProperty("object", Integer.toHexString(System.identityHashCode(entity)));
        json.addProperty("sourceObject", transaction == null ? "UNAVAILABLE" : transaction.sourceObject());
        json.addProperty("nativeIncarnation", transaction == null ? -1 : transaction.nativeIncarnation());
        json.addProperty("route", transaction == null ? "UNCLASSIFIED" : transaction.route());
        json.addProperty("sourceWorld", transaction == null ? "UNAVAILABLE" : transaction.sourceWorld());
        json.addProperty("destinationWorld", destinationWorld);
        json.addProperty("ownerWorld", ownerWorld());
        json.addProperty("ownerContext", ownerContext());
        json.addProperty("thread", Thread.currentThread().getName());
        json.addProperty("candidateSha256", PRODUCT_SHA);
        json.addProperty("testSourceSha256", TEST_SOURCE_SHA);
        json.addProperty("nanoTime", System.nanoTime());
        return json;
    }
    private static synchronized void record(JsonObject json) {
        EVENTS.incrementAndGet();
        if (RECENT.size() == TRACE_LIMIT) { RECENT.removeFirst(); DROPPED.incrementAndGet(); }
        RECENT.addLast(json.deepCopy());
        LoggerFactory.getLogger("PortalLifecycleObserverBridge").info("STAGE7_PORTAL_TX {}", json);
    }
    private static synchronized void violation(JsonObject json, String reason) {
        JsonObject failed = json.deepCopy(); failed.addProperty("violation", reason);
        VIOLATIONS.incrementAndGet();
        if (firstViolation == null) firstViolation = failed.deepCopy();
        record(failed);
        LoggerFactory.getLogger("PortalLifecycleObserverBridge").error("STAGE7_PORTAL_TX_VIOLATION {}", failed);
    }
    private static void checkOwner(JsonObject json, String expectedWorld) {
        if (!expectedWorld.equals(ownerWorld()))
            violation(json, "wrong-owner event: expected=" + expectedWorld + " current=" + ownerWorld());
    }

    private static void graphMembers(Entity source, IdentityHashMap<Object, UUID> graph) {
        if (graph.put(source, source.getUUID()) != null) return;
        for (Entity passenger : source.getPassengers()) graphMembers(passenger, graph);
    }

    /** The exact native Entity.handlePortal call site, including its passenger graph. */
    public static Entity nativePortalInvocation(Entity source, TeleportTransition transition,
            Supplier<Entity> original) {
        IdentityHashMap<Object, UUID> graph = new IdentityHashMap<>();
        try {
            graphMembers(source, graph);
            JsonObject json = event("HANDLE_PORTAL_NATIVE_TELEPORT", source, ACTIVE.get(source.getUUID()),
                transition.newLevel().dimension().identifier().toString());
            json.addProperty("portalRoute", route(source));
            json.addProperty("nativeGraphSize", graph.size());
            record(json);
        } catch (Throwable failed) {
            internalFailure("HANDLE_PORTAL_NATIVE_TELEPORT", failed);
            return original.get();
        }
        // The scope ends even when native teleport throws. A later direct /tp cannot inherit it.
        return NATIVE_CALLS.within(source, graph, original);
    }

    /** HEAD of Entity.teleportCrossDimension; direct /tp has no handlePortal call scope. */
    public static void crossDimensionHead(Entity source, ServerLevel oldLevel, ServerLevel newLevel) {
        try {
            Transaction tx = ACTIVE.get(source.getUUID());
            boolean retainedIdentity = tx != null && tx.productGeneration() > 0
                && tx.sourceObject().equals(Integer.toHexString(System.identityHashCode(source)));
            PortalNativeCallCorrelation.Classification classification =
                NATIVE_CALLS.classify(source, source.getUUID(), retainedIdentity);
            if (classification == PortalNativeCallCorrelation.Classification.OUTSIDE_PORTAL) return;
            boolean unretained = classification == PortalNativeCallCorrelation.Classification.UNRETAINED_ROOT
                || classification == PortalNativeCallCorrelation.Classification.UNRETAINED_PASSENGER;
            JsonObject json = event(unretained ? "NATIVE_UNRETAINED" : "NATIVE_RETAINED_WITHIN_HANDLE_PORTAL",
                source, tx, newLevel.dimension().identifier().toString());
            json.addProperty("nativePortalClassification", classification.name());
            json.addProperty("handlePortalCallDepth", NATIVE_CALLS.depth());
            json.addProperty("sourceWorld", oldLevel.dimension().identifier().toString());
            json.addProperty("portalRoute", route(source));
            if (unretained)
                violation(json, "Entity.handlePortal reached native cross-dimension teleport without exact retained source generation");
            else record(json);
        } catch (Throwable failed) { internalFailure("NATIVE_CROSS_DIMENSION_HEAD", failed); }
    }

    /** The product's retained boundary supplies the exact source UUID and generation. */
    public static void retained(Entity entity, ObjectOwnership binding,
            CrossDimensionReplacementBoundary.Token token, ServerLevel destination, String reason) {
        try {
            UUID uuid = entity.getUUID();
            Transaction tx = ACTIVE.get(uuid);
            if (reason.startsWith("CAPTURED")) {
                Transaction captured = new Transaction(GENERATIONS.incrementAndGet(), token.generation(), uuid,
                    entity.getType().toString(), route(entity), world(entity),
                    Integer.toHexString(System.identityHashCode(entity)), token.source().nativeIncarnation(),
                    ConcurrentHashMap.newKeySet());
                Transaction prior = ACTIVE.putIfAbsent(uuid, captured);
                tx = prior == null ? captured : prior;
                if (prior != null) violation(event("CAPTURE_DUPLICATE", entity, tx,
                    destination == null ? "UNAVAILABLE" : destination.dimension().identifier().toString()),
                    "duplicate retained source UUID generation");
            }
            JsonObject json = event(reason, entity, tx,
                destination == null ? "UNAVAILABLE" : destination.dimension().identifier().toString());
            if (tx == null) violation(json, "retained stage lacks capture");
            else if (tx.productGeneration() != token.generation()
                    || !tx.sourceObject().equals(Integer.toHexString(System.identityHashCode(entity))))
                violation(json, "retained stage changed source generation or object");
            else record(json);
            if (reason.startsWith("CAPTURED")) checkOwner(json, world(entity));
            else if (reason.equals("DESTINATION_PREPARING") || reason.equals("DESTINATION_READY"))
                checkOwner(json, "NONE");
            if (reason.equals("SETTLED_NATIVE_NO_TRANSFER") || reason.startsWith("STALE_")
                    || reason.startsWith("UNRETAINED") || reason.equals("WORLD_CLOSED")
                    || reason.equals("SHUTDOWN") || reason.startsWith("FAILED:")) {
                LOOKUP.remove();
                if (tx != null) ACTIVE.remove(uuid, tx);
            }
        } catch (Throwable failed) { internalFailure("RETAINED", failed); }
    }

    /** HEAD/RETURN of the owner-free native destination lookup wrapper. */
    public static void preparation(Entity entity, CrossDimensionReplacementBoundary.Token token,
            TeleportTransition transition, boolean returned) {
        try {
            Transaction tx = ACTIVE.get(entity.getUUID());
            JsonObject json = event(returned ? "DESTINATION_LOOKUP_RETURN" : "DESTINATION_LOOKUP_HEAD",
                entity, tx, transition == null ? "UNAVAILABLE" : transition.newLevel().dimension().identifier().toString());
            if (tx == null || tx.productGeneration() != token.generation())
                violation(json, "destination lookup lacks retained generation");
            else record(json);
            checkOwner(json, "NONE");
            if (returned) LOOKUP.remove();
            else if (tx != null) LOOKUP.set(tx);
        } catch (Throwable failed) { internalFailure("DESTINATION_LOOKUP", failed); }
    }

    public static void destinationQuery(ServerLevel level, int x, int z, ChunkStatus status,
            boolean create, String stage, Boolean ready) {
        try {
            Transaction tx = LOOKUP.get();
            if (tx == null) return; // The product helper is also called for ordinary chunk queries.
            JsonObject json = new JsonObject();
            json.addProperty("schema", Transaction.SCHEMA); json.addProperty("phase", stage);
            json.addProperty("generation", tx == null ? -1 : tx.generation());
            json.addProperty("productGeneration", tx == null ? -1 : tx.productGeneration());
            json.addProperty("uuid", tx == null ? "UNAVAILABLE" : tx.uuid().toString());
            json.addProperty("type", tx == null ? "UNAVAILABLE" : tx.type());
            json.addProperty("route", tx == null ? "UNAVAILABLE" : tx.route());
            json.addProperty("sourceWorld", tx == null ? "UNAVAILABLE" : tx.sourceWorld());
            String destinationWorld = level.dimension().identifier().toString();
            json.addProperty("destinationWorld", destinationWorld);
            json.addProperty("chunkX", x); json.addProperty("chunkZ", z);
            json.addProperty("requestedStatus", status.getName()); json.addProperty("create", create);
            if (ready != null) json.addProperty("futureDone", ready);
            json.addProperty("ownerWorld", ownerWorld()); json.addProperty("ownerContext", ownerContext());
            json.addProperty("thread", Thread.currentThread().getName());
            json.addProperty("candidateSha256", PRODUCT_SHA); json.addProperty("testSourceSha256", TEST_SOURCE_SHA);
            json.addProperty("nanoTime", System.nanoTime());
            PlatformScope nativePlatform = NATIVE_PLATFORM.get();
            json.addProperty("nativePlatformScope", nativePlatform != null);
            record(json);
            if (nativePlatform != null && (nativePlatform.transaction() != tx
                    || !nativePlatform.destinationWorld().equals(destinationWorld)))
                violation(json, "native End platform query escaped its exact transaction or destination world");
            checkOwner(json, nativePlatform == null ? "NONE" : nativePlatform.destinationWorld());
        } catch (Throwable failed) { internalFailure(stage, failed); }
    }

    /** Only the native EndPlatformFeature effect runs under destination authority. */
    public static void nativeEndPlatform(ServerLevel level, Runnable effect) {
        Transaction tx = LOOKUP.get();
        if (tx == null) { effect.run(); return; }
        PlatformScope previous = NATIVE_PLATFORM.get();
        NATIVE_PLATFORM.set(new PlatformScope(tx, level.dimension().identifier().toString()));
        try { effect.run(); }
        finally {
            if (previous == null) NATIVE_PLATFORM.remove();
            else NATIVE_PLATFORM.set(previous);
        }
    }

    public static void platform(Entity entity, ServerLevel level, boolean returned) {
        try {
            Transaction tx = ACTIVE.get(entity.getUUID());
            if (tx == null) return;
            JsonObject json = event(returned ? "END_PLATFORM_WRAPPER_RETURN" : "END_PLATFORM_WRAPPER_HEAD",
                entity, tx, level.dimension().identifier().toString());
            record(json);
            checkOwner(json, "NONE");
        } catch (Throwable failed) { internalFailure("END_PLATFORM", failed); }
    }

    /** Product wrapper edges prove the retained segment and its owner; native hooks prove effects. */
    public static void wrapper(String phase, Entity source, Entity replacement,
            ServerLevel destination, ObjectOwnership binding, boolean returned) {
        try {
            Entity anchor = source == null ? replacement : source;
            if (anchor == null) return;
            Transaction tx = ACTIVE.get(anchor.getUUID());
            if (tx == null) return; // Unrelated native teleport or spawn.
            JsonObject json = event(phase + (returned ? "_RETURN" : "_HEAD"),
                replacement == null ? anchor : replacement, tx,
                destination == null ? "UNAVAILABLE" : destination.dimension().identifier().toString());
            if (binding != null) {
                json.addProperty("registryIncarnation", binding.incarnation());
                json.addProperty("routingKey", binding.routingKey().toString());
            }
            record(json);
            String expected = switch (phase) {
                case "ADDRESSABLE", "CALLBACK_INSTALLED" ->
                    destination == null ? "UNAVAILABLE" : destination.dimension().identifier().toString();
                default -> "NONE";
            };
            if (!expected.equals("UNAVAILABLE")) checkOwner(json, expected);
        } catch (Throwable failed) { internalFailure(phase, failed); }
    }

    /** NativePortalTransfer.marker runs inside the exact owner body for effect stages. */
    public static void marker(String phase, Entity source, ServerLevel destination,
            CrossDimensionReplacementBoundary.Token token) {
        try {
            Transaction tx = ACTIVE.get(source.getUUID());
            JsonObject json = event(phase, source, tx,
                destination.dimension().identifier().toString());
            if (tx == null || tx.productGeneration() != token.generation())
                violation(json, "native stage lacks retained generation");
            else { tx.markerPhases().add(phase); record(json); }
            String expected = switch (phase) {
                case "CREATE", "POSITION", "DESTINATION_INSERTING", "DESTINATION_ADMITTED",
                    "RESTORE_REPORTER", "RESTORE_DESTINATION_LOAD", "RESTORE_DESTINATION_TAIL",
                    "DESTINATION_ADDRESSABLE_TRACKING_PENDING", "POST_TRANSITION", "RESET_EMPTY_TIME",
                    "FAILURE_CLEANUP", "FAILED_DESTINATION_CLEANUP", "ATTACH_PASSENGER",
                    "PASSENGER_ATTACHED", "ITEM_TELEPORT_SUFFIX", "PEARL_TELEPORT_SUFFIX",
                    "TNT_TELEPORT_SUFFIX" ->
                    destination.dimension().identifier().toString();
                case "STOP_RIDING", "EJECT", "RESTORE", "RESTORE_SOURCE_TAIL",
                    "SOURCE_RETIRING", "SOURCE_RETIRED",
                    "STOP_TRADING", "CAPTURE_PASSENGERS", "PASSENGER_TRANSITION",
                    "PASSENGER_TRANSITION_ACCEPT" ->
                    tx == null ? "UNAVAILABLE" : tx.sourceWorld();
                default -> "NONE";
            };
            if (!expected.equals("UNAVAILABLE")) checkOwner(json, expected);
        } catch (Throwable failed) { internalFailure("NATIVE_MARKER", failed); }
    }

    public static void settled(Entity source) {
        try {
            Transaction tx = ACTIVE.get(source.getUUID());
            JsonObject json = event("TRANSFER_SETTLED_AFTER_SUFFIX", source, tx, "UNAVAILABLE");
            if (tx == null) violation(json, "transfer settlement lacks retained generation");
            else {
                Set<String> required = Set.of("RESTORE_REPORTER", "RESTORE",
                    "RESTORE_DESTINATION_LOAD", "RESTORE_SOURCE_TAIL", "RESTORE_DESTINATION_TAIL",
                    "SOURCE_RETIRED", "DESTINATION_ADMITTED", "POST_TRANSITION");
                if (!tx.markerPhases().containsAll(required)) {
                    var missing = new java.util.TreeSet<>(required);
                    missing.removeAll(tx.markerPhases());
                    violation(json, "native transfer skipped required restore/publication phases " + missing);
                } else record(json);
                ACTIVE.remove(source.getUUID(), tx);
            }
            checkOwner(json, "NONE");
        } catch (Throwable failed) { internalFailure("TRANSFER_SETTLED", failed); }
    }

    /** HEAD of Entity.setRemoved(CHANGED_DIMENSION), before native source cleanup. */
    public static void sourceRetiring(Entity entity, Entity.RemovalReason reason) {
        if (reason != Entity.RemovalReason.CHANGED_DIMENSION) return;
        try {
            // Native /tp and other direct dimension changes share CHANGED_DIMENSION.
            // They do not enter the portal transaction whose owner contract this probe checks.
            if (!ACTIVE.containsKey(entity.getUUID()) && route(entity).equals("DIRECT_OR_UNKNOWN")) return;
            String sourceWorld = world(entity);
            Transaction transaction = ACTIVE.get(entity.getUUID());
            if (transaction == null) {
                // Player teleports have their own retained manager and no NativePortalTransfer token.
                transaction = new Transaction(GENERATIONS.incrementAndGet(), -1, entity.getUUID(),
                    entity.getType().toString(), route(entity), sourceWorld,
                    Integer.toHexString(System.identityHashCode(entity)),
                    entity instanceof dev.phoenium.chunk.FullEntityFacts.Access access
                        ? access.phoenium$entityIncarnation() : -1,
                    ConcurrentHashMap.newKeySet());
                Transaction prior = ACTIVE.putIfAbsent(entity.getUUID(), transaction);
                if (prior != null) transaction = prior;
            }
            JsonObject json = event("SOURCE_RETIRING", entity, transaction, "UNAVAILABLE");
            if (!transaction.sourceObject().equals(Integer.toHexString(System.identityHashCode(entity))))
                violation(json, "source retirement changed native object");
            else record(json);
            checkOwner(json, sourceWorld);
        } catch (Throwable failed) { internalFailure("SOURCE_RETIRING", failed); }
    }
    /** HEAD/RETURN of ServerLevel.addDuringTeleport. */
    public static void insertion(ServerLevel level, Entity entity, boolean returned) {
        try {
            Transaction tx = ACTIVE.get(entity.getUUID());
            if (tx == null) return; // Ordinary /tp or spawn; no portal capture preceded it.
            JsonObject json = event(returned ? "ADD_DURING_TELEPORT_RETURN" : "ADD_DURING_TELEPORT_HEAD",
                entity, tx, level.dimension().identifier().toString());
            record(json);
            checkOwner(json, level.dimension().identifier().toString());
            if (returned && tx != null && tx.productGeneration() < 0) ACTIVE.remove(entity.getUUID(), tx);
        } catch (Throwable failed) { internalFailure("ADD_DURING_TELEPORT", failed); }
    }
    /** HEAD/RETURN of ChunkMap.addEntity, constrained to an active relocation generation. */
    public static void tracking(ServerLevel level, Entity entity, boolean returned) {
        try {
            Transaction tx = ACTIVE.get(entity.getUUID());
            if (tx == null) return; // Ordinary spawn tracking is not a relocation transaction.
            JsonObject json = event(returned ? "CHUNK_MAP_TRACKING_RETURN" : "CHUNK_MAP_TRACKING_HEAD",
                entity, tx, level.dimension().identifier().toString());
            json.addProperty("trackingRange", entity.getType().clientTrackingRange());
            record(json);
            checkOwner(json, level.dimension().identifier().toString());
        } catch (Throwable failed) { internalFailure("CHUNK_MAP_TRACKING", failed); }
    }
    private static void internalFailure(String phase, Throwable failure) {
        JsonObject json = new JsonObject(); json.addProperty("schema", Transaction.SCHEMA);
        json.addProperty("phase", phase); json.addProperty("observerFailure", failure.toString());
        violation(json, "observer failed to record native event");
    }
    public static synchronized JsonObject summary() {
        JsonObject json = new JsonObject();
        json.addProperty("schema", Transaction.SCHEMA);
        json.addProperty("candidateSha256", PRODUCT_SHA);
        json.addProperty("testSourceSha256", TEST_SOURCE_SHA);
        json.addProperty("events", EVENTS.get()); json.addProperty("droppedRecentEvents", DROPPED.get());
        json.addProperty("violations", VIOLATIONS.get()); json.addProperty("pending", ACTIVE.size());
        json.add("firstViolation", firstViolation == null ? null : firstViolation.deepCopy());
        JsonArray recent = new JsonArray(); for (JsonObject event : RECENT) recent.add(event.deepCopy());
        json.add("recent", recent); return json;
    }
    /** Fits in an RCON response even after a large campaign; full events remain in the server log. */
    public static synchronized JsonObject status() {
        JsonObject json = new JsonObject();
        json.addProperty("schema", Transaction.SCHEMA);
        json.addProperty("candidateSha256", PRODUCT_SHA);
        json.addProperty("testSourceSha256", TEST_SOURCE_SHA);
        json.addProperty("events", EVENTS.get());
        json.addProperty("droppedRecentEvents", DROPPED.get());
        json.addProperty("violations", VIOLATIONS.get());
        json.addProperty("pending", ACTIVE.size());
        json.add("firstViolation", firstViolation == null ? null : firstViolation.deepCopy());
        return json;
    }
    @Override public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
            Commands.literal("stage7-tx").then(Commands.literal("status").executes(context -> {
                JsonObject summary = status(); String value = summary.toString();
                if (VIOLATIONS.get() > 0 || !ACTIVE.isEmpty()) {
                    context.getSource().sendFailure(Component.literal(value)); return 0;
                }
                context.getSource().sendSuccess(() -> Component.literal(value), false); return 1;
            }))));
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
            LoggerFactory.getLogger("PortalLifecycleObserverBridge").info("STAGE7_PORTAL_TX_SUMMARY {}", summary()));
    }
}
