package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.*;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RoutingKey;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import org.slf4j.LoggerFactory;

/** One native teleport invocation, entered only after the producing owner callback returned.
 * The wrappers below partition native effects; they never supply a replacement return value. */
public final class NativePortalTransfer {
    public interface Authority {
        boolean running();
        boolean worldActive(ServerLevel level);
        WorldId worldId(ServerLevel level);
        void execute(RoutingKey key, RegionalServiceKind kind, Runnable body);
        BooleanSupplier prepare(Entity source, TeleportTransition transition, RoutingKey destination);
        void retireExpectedSource(Entity source, Entity.RemovalReason reason, ObjectOwnership expected);
    }
    private static final ThreadLocal<NativePortalTransfer> ACTIVE = new ThreadLocal<>();
    private final Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary boundary;
    private final CrossDimensionReplacementBoundary.Token token;
    private final Entity source;
    private final ServerLevel sourceLevel, destinationLevel;
    private final TeleportTransition transition;
    private final ObjectOwnership capturedSource;
    private final RoutingKey destinationKey;
    private final BooleanSupplier live;
    private BooleanSupplier readiness;
    private CrossDimensionReplacementBoundary.SourceRemoval removal;
    private Entity replacement;
    private ObjectOwnership publishedReplacement;
    private Runnable unpublishedCleanup;
    private boolean callbackInstalled, inserting, cleaning, sourceRetired, inserted, returned;
    private String phase = "CAPTURED";

    public NativePortalTransfer(Authority authority, ObjectOwnershipRegistry objects,
            CrossDimensionReplacementBoundary boundary, CrossDimensionReplacementBoundary.Token token,
            Entity source, ObjectOwnership capturedSource, TeleportTransition transition, BooleanSupplier live) {
        this.authority = Objects.requireNonNull(authority); this.objects = Objects.requireNonNull(objects);
        this.boundary = Objects.requireNonNull(boundary); this.token = Objects.requireNonNull(token);
        this.source = Objects.requireNonNull(source); this.capturedSource = Objects.requireNonNull(capturedSource);
        this.sourceLevel = (ServerLevel) source.level(); this.transition = Objects.requireNonNull(transition);
        this.destinationLevel = transition.newLevel(); this.live = Objects.requireNonNull(live);
        var absolute = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(source),
            PositionMoveRotation.of(transition), transition.relatives());
        ChunkPos chunk = ChunkPos.containing(BlockPos.containing(absolute.position()));
        destinationKey = new RoutingKey(authority.worldId(destinationLevel), chunk.x(), chunk.z());
    }

    public Entity run() {
        ownerFree();
        if (ACTIVE.get() != null) throw new IllegalStateException("nested native portal transfer");
        checkSource();
        marker("BEFORE_DESTINATION_PREPARATION");
        readiness = authority.prepare(source, transition, destinationKey);
        checkSource(); checkReady();
        ACTIVE.set(this);
        try {
            marker("BEFORE_NATIVE_TRANSFER");
            Entity result = source.teleport(transition);
            if (result == null) {
                if (sourceRetired || replacement != null) throw new IllegalStateException("native portal returned null after committed effects");
            } else if (result != replacement || !inserted || !sourceRetired)
                throw new IllegalStateException("native portal result did not complete its exact publication");
            returned = true; marker("NATIVE_RETURN");
            return result;
        } catch (RuntimeException | Error failure) {
            marker("FAILED:" + failure.getClass().getSimpleName());
            // A destination failure is caught within its owner operation below, before the
            // dispatcher failure barrier can prevent cleanup. Never retry the native call.
            if (replacement != null && !cleaning && (inserted || unpublishedCleanup != null)) {
                try { destination("FAILURE_CLEANUP", () -> { cleanup(); return null; }, false); }
                catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        } finally { ACTIVE.remove(); }
    }

    /** Native source-only stopRiding/ejectPassengers/restore operations. */
    public static void sourceEffect(Entity entity, String phase, Runnable body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.source != entity) { body.run(); return; }
        transfer.source(phase, () -> { body.run(); return null; });
    }
    public static <T extends Entity> T create(Entity source, Supplier<T> body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.source != source) return body.get();
        return transfer.destination("CREATE", () -> {
            if (transfer.replacement != null) throw new IllegalStateException("duplicate native replacement construction");
            T created = body.get(); transfer.replacement = created;
            if (created != null && (created == source || created.level() != transfer.destinationLevel
                    || incarnation(created) == transfer.token.source().nativeIncarnation()))
                throw new IllegalStateException("native replacement construction changed identity");
            return created;
        }, true);
    }
    public static void restore(Entity replacement, Entity source, Runnable body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.source != source) { body.run(); return; }
        if (transfer.replacement != replacement) throw new IllegalStateException("foreign native replacement restore");
        transfer.source("RESTORE", () -> {
            body.run();
            if (!source.getUUID().equals(replacement.getUUID())) throw new IllegalStateException("native restore changed UUID");
            return null;
        });
    }
    public static void removeSource(Entity source, Runnable body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.source != source) { body.run(); return; }
        transfer.source("SOURCE_RETIRING", () -> {
            transfer.removal = transfer.boundary.authorizeSourceRemoval(transfer.token, transfer.token.source());
            body.run();
            if (source.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                throw new IllegalStateException("native source did not retire with CHANGED_DIMENSION");
            transfer.authority.retireExpectedSource(source, Entity.RemovalReason.CHANGED_DIMENSION, transfer.capturedSource);
            transfer.boundary.sourceRetired(transfer.removal); transfer.sourceRetired = true;
            transfer.marker("SOURCE_RETIRED");
            return null;
        });
    }
    public static void destinationEffect(Entity entity, String phase, Runnable body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.replacement != entity) { body.run(); return; }
        transfer.destination(phase, () -> { body.run(); return null; }, true);
    }
    public static void destinationWorldEffect(ServerLevel level, Runnable body) {
        var transfer = ACTIVE.get();
        if (transfer == null || transfer.destinationLevel != level) { body.run(); return; }
        transfer.destination("RESET_EMPTY_TIME", () -> { body.run(); return null; }, true);
    }
    public static void insert(ServerLevel level, Entity entity, Runnable body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.replacement != entity) { body.run(); return; }
        if (level != transfer.destinationLevel) throw new IllegalStateException("native portal insertion changed world");
        transfer.destination("DESTINATION_INSERTING", () -> {
            var admission = transfer.boundary.authorizeDestinationAdmission(transfer.token);
            transfer.inserting = true;
            try {
                body.run();
                ObjectOwnership binding = transfer.objects.current(entity.getUUID().toString()).orElseThrow(
                    () -> new IllegalStateException("native destination has not published ownership at tracking start"));
                if (transfer.publishedReplacement == null || binding.incarnation() != transfer.publishedReplacement.incarnation())
                    throw new IllegalStateException("native destination lost its exact addressability receipt");
                transfer.checkReplacement();
                transfer.boundary.destinationAdmitted(admission, new CrossDimensionReplacementBoundary.DestinationIdentity(
                    binding.objectId(), incarnation(entity), binding.incarnation()));
                transfer.inserted = true;
                transfer.marker("DESTINATION_ADMITTED");
            } catch (RuntimeException | Error failure) {
                try { transfer.cleanup(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            } finally { transfer.inserting = false; }
            return null;
        }, true);
    }
    public static boolean inserting(Entity entity) {
        var transfer = ACTIVE.get(); return transfer != null && transfer.inserting && transfer.replacement == entity;
    }
    /** Called only at the existing successful addressability publication, before tracking. */
    public static void addressable(Entity entity, ObjectOwnership binding) {
        var transfer = ACTIVE.get();
        if (!inserting(entity)) return;
        if (!binding.objectId().equals(transfer.token.source().objectId())
                || binding.incarnation() <= transfer.token.source().registryIncarnation()
                || !binding.routingKey().equals(transfer.destinationKey)
                || transfer.publishedReplacement != null && transfer.publishedReplacement.incarnation() != binding.incarnation())
            throw new IllegalStateException("portal addressability published another incarnation");
        transfer.publishedReplacement = binding;
        transfer.checkReplacement();
        transfer.marker("DESTINATION_ADDRESSABLE_TRACKING_PENDING");
    }
    public static void storageClaimed(Entity entity, Runnable cleanup) {
        var transfer = ACTIVE.get();
        if (!inserting(entity)) return;
        if (transfer.unpublishedCleanup != null) throw new IllegalStateException("duplicate portal storage claim");
        transfer.unpublishedCleanup = Objects.requireNonNull(cleanup);
    }
    public static void callbackInstalled(Entity entity) {
        var transfer = ACTIVE.get(); if (inserting(entity)) transfer.callbackInstalled = true;
    }
    public static boolean expectedRemoval(Entity entity, Entity.RemovalReason reason) {
        var transfer = ACTIVE.get();
        return transfer != null && (transfer.cleaning && entity == transfer.replacement
            || entity == transfer.source && reason == Entity.RemovalReason.CHANGED_DIMENSION
                && transfer.boundary.recognizesExpectedRemoval(transfer.removal, transfer.token.source()));
    }

    private <T> T source(String next, Supplier<T> body) {
        ownerFree(); checkSource();
        return execute(capturedSource.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
            checkSource(); var binding = objects.current(capturedSource.objectId()).orElseThrow();
            objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
            marker(next); return body.get();
        });
    }
    private <T> T destination(String next, Supplier<T> body, boolean validate) {
        ownerFree();
        if (validate) { checkLive(); checkReady(); }
        return execute(destinationKey, RegionalServiceKind.ENTITY_PORTAL_DESTINATION, () -> {
            if (validate) { checkLive(); checkReady(); if (inserted) checkReplacement(); }
            marker(next); return body.get();
        });
    }
    private <T> T execute(RoutingKey key, RegionalServiceKind kind, Supplier<T> body) {
        Object[] result = new Object[1]; Throwable[] failure = new Throwable[1];
        authority.execute(key, kind, () -> {
            try { result[0] = body.get(); } catch (RuntimeException | Error thrown) { failure[0] = thrown; }
        });
        if (failure[0] instanceof RuntimeException runtime) throw runtime;
        if (failure[0] instanceof Error error) throw error;
        @SuppressWarnings("unchecked") T value = (T) result[0]; return value;
    }
    private void checkLive() {
        if (!authority.running() || !authority.worldActive(sourceLevel) || !authority.worldActive(destinationLevel)
                || !live.getAsBoolean() || !boundary.isCurrent(token)
                || source.level() != sourceLevel || incarnation(source) != token.source().nativeIncarnation()
                || !source.getUUID().toString().equals(token.source().objectId())
                || sourceRetired && source.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
            throw new IllegalStateException("stale native portal transfer generation " + token.generation());
    }
    private void checkSource() {
        checkLive();
        var current = objects.current(capturedSource.objectId()).orElse(null);
        if (source.isRemoved() || current == null || current.incarnation() != capturedSource.incarnation()
                || !current.objectEpoch().equals(capturedSource.objectEpoch())
                || !current.routingKey().equals(capturedSource.routingKey()) || source.isPassenger() || source.isVehicle())
            throw new IllegalStateException("native portal source incarnation or route changed");
    }
    private void checkReady() {
        if (readiness == null) throw new IllegalStateException("native portal readiness absent");
        if (!readiness.getAsBoolean()) {
            String observation = "UNAVAILABLE";
            try { observation = readiness.toString(); } catch (Throwable unavailable) { /* Preserve admission failure. */ }
            throw new IllegalStateException("native portal readiness changed: " + observation);
        }
    }
    private void checkReplacement() {
        ObjectOwnership current = objects.current(token.source().objectId()).orElse(null);
        if (replacement == null || replacement.isRemoved() || replacement.level() != destinationLevel
                || current == null || current.incarnation() <= token.source().registryIncarnation()
                || publishedReplacement != null && current.incarnation() != publishedReplacement.incarnation()
                || !current.routingKey().equals(destinationKey)
                || destinationLevel.getEntity(replacement.getUUID()) != replacement
                || destinationLevel.getEntity(replacement.getId()) != replacement)
            throw new IllegalStateException("native portal replacement incarnation or route changed");
        objects.requireCurrentOwner(current.objectId(), current.objectEpoch(), OwnershipContext.current().orElseThrow().region());
    }
    private void cleanup() {
        if (cleaning || replacement == null || unpublishedCleanup == null && !inserted) return;
        cleaning = true;
        Entity byUuid = destinationLevel.getEntity(replacement.getUUID());
        Entity byId = destinationLevel.getEntity(replacement.getId());
        if (byUuid != null && byUuid != replacement || byId != null && byId != replacement)
            throw new IllegalStateException("portal cleanup refused a newer native incarnation");
        var binding = objects.current(replacement.getUUID().toString()).orElse(null);
        if (publishedReplacement != null && binding != null && binding.incarnation() != publishedReplacement.incarnation())
            throw new IllegalStateException("portal cleanup refused a newer ownership incarnation");
        marker("FAILED_DESTINATION_CLEANUP");
        if (!callbackInstalled) unpublishedCleanup.run();
        else replacement.remove(Entity.RemovalReason.DISCARDED);
        if (publishedReplacement != null)
            authority.retireExpectedSource(replacement, Entity.RemovalReason.DISCARDED, publishedReplacement);
        inserted = false; unpublishedCleanup = null;
    }
    private void marker(String next) {
        phase = next;
        if (!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try {
            LoggerFactory.getLogger(NativePortalTransfer.class).info(
                "PHOENIUM_NON_PLAYER_PORTAL generation={} type={} uuid={} source={}@{} native={} registry={} epoch={} replacement={} sourceWorld={} destinationWorld={} capturedRoute={} currentRoute={} destinationRoute={} owner={} phase={} returned={}",
                token.generation(), source.getType(), token.source().objectId(), source.getId(), System.identityHashCode(source),
                token.source().nativeIncarnation(), capturedSource.incarnation(), capturedSource.objectEpoch(),
                replacement == null ? "UNAVAILABLE" : replacement.getId() + "@" + System.identityHashCode(replacement) + ":native=" + incarnation(replacement),
                capturedSource.routingKey().world(), destinationKey.world(), capturedSource,
                objects.current(token.source().objectId()).map(Object::toString).orElse("UNAVAILABLE"), destinationKey,
                OwnershipContext.current().map(Object::toString).orElse("NONE"), phase, returned);
        } catch (Throwable ignored) { /* Diagnostics cannot replace the operation failure. */ }
    }
    public void settled() { marker("SETTLED"); }
    static void retained(Entity entity, ObjectOwnership binding, CrossDimensionReplacementBoundary.Token token,
            ServerLevel destination, String reason) {
        if (!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try {
            LoggerFactory.getLogger(NativePortalTransfer.class).info(
                "PHOENIUM_NON_PLAYER_PORTAL generation={} type={} uuid={} source={}@{} native={} registry={} epoch={} sourceWorld={} destinationWorld={} capturedRoute={} currentRoute=UNAVAILABLE replacement=UNAVAILABLE owner={} phase={}",
                token.generation(), entity.getType(), token.source().objectId(), entity.getId(), System.identityHashCode(entity),
                token.source().nativeIncarnation(), token.source().registryIncarnation(), binding == null ? "UNAVAILABLE" : binding.objectEpoch(),
                binding == null ? "UNAVAILABLE" : binding.routingKey().world(), destination == null ? "UNAVAILABLE" : destination.dimension().identifier(),
                binding == null ? "UNAVAILABLE" : binding, OwnershipContext.current().map(Object::toString).orElse("NONE"), reason);
        } catch (Throwable ignored) { }
    }
    private static long incarnation(Entity entity) { return ((FullEntityFacts.Access) entity).phoenium$entityIncarnation(); }
    private static void ownerFree() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("native portal coordinator retained source owner");
    }
}
