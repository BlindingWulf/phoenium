package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.*;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RoutingKey;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
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
        default BooleanSupplier demandDestinationEntityVisibility(Entity source, TeleportTransition transition) {
            return () -> true;
        }
        default void withDestinationPlatform(ServerLevel destination, BlockPos origin,
                BooleanSupplier sourceCurrent, Runnable nativePlatform) {
            throw new IllegalStateException("destination platform service is not bound");
        }
        void retireExpectedSource(Entity source, Entity.RemovalReason reason, ObjectOwnership expected);
    }
    private static final ThreadLocal<NativePortalTransfer> ACTIVE = new ThreadLocal<>();
    private final Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary boundary;
    private final CrossDimensionReplacementBoundary.Token token;
    private final Entity source;
    private final int sourceRuntimeId;
    private final ServerLevel sourceLevel, destinationLevel;
    private TeleportTransition transition;
    private final ObjectOwnership capturedSource;
    private ObjectOwnership effectiveSource;
    private final RoutingKey destinationKey;
    private final BooleanSupplier live;
    private BooleanSupplier readiness;
    private CrossDimensionReplacementBoundary.SourceRemoval removal;
    private Entity replacement;
    private ObjectOwnership publishedReplacement;
    private Runnable unpublishedCleanup;
    private PositionMoveRotation retiredSourcePosition;
    private IdentityHashMap<Entity, NativePortalTransfer> graphChildren;
    private final IdentityHashMap<Entity, Entity> transferredChildren = new IdentityHashMap<>();
    private boolean callbackInstalled, inserting, cleaning, sourceRetired, inserted, returned;
    private String phase = "CAPTURED";

    public NativePortalTransfer(Authority authority, ObjectOwnershipRegistry objects,
            CrossDimensionReplacementBoundary boundary, CrossDimensionReplacementBoundary.Token token,
            Entity source, ObjectOwnership capturedSource, TeleportTransition transition, BooleanSupplier live) {
        this.authority = Objects.requireNonNull(authority); this.objects = Objects.requireNonNull(objects);
        this.boundary = Objects.requireNonNull(boundary); this.token = Objects.requireNonNull(token);
        this.source = Objects.requireNonNull(source); this.sourceRuntimeId = source.getId();
        this.capturedSource = Objects.requireNonNull(capturedSource); this.effectiveSource = capturedSource;
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
        if (readiness == null) readiness = authority.prepare(source, transition, destinationKey);
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

    /** Prepares a graph member's destination before native recursion can mutate any source edge. */
    public boolean prepareReadiness() {
        ownerFree();
        checkSourceIdentity();
        if (readiness == null) readiness = authority.prepare(source, transition, destinationKey);
        checkSourceIdentity();
        return readiness.getAsBoolean();
    }

    /** Bound only from a source-owned, identity-validated graph snapshot. */
    public void bindGraphChildren(Map<Entity, NativePortalTransfer> children) {
        ownerFree();
        if (graphChildren != null || children == null || children.isEmpty())
            throw new IllegalStateException("native portal graph children already bound or absent");
        graphChildren = new IdentityHashMap<>();
        for (var child : children.entrySet()) {
            if (child.getKey() == source || child.getValue() == this
                    || child.getValue().source != child.getKey()
                    || child.getValue().sourceLevel != sourceLevel
                    || child.getValue().destinationLevel != destinationLevel)
                throw new IllegalStateException("native portal graph child identity changed");
            graphChildren.put(child.getKey(), child.getValue());
        }
    }

    /** Exact native child invocation at Entity.teleportCrossDimension's passenger loop. */
    public static Entity passenger(Entity child, TeleportTransition nativeTransition, Supplier<Entity> original) {
        NativePortalTransfer parent = ACTIVE.get();
        if (parent == null) return original.get();
        NativePortalTransfer transfer = parent.graphChildren == null ? null : parent.graphChildren.get(child);
        if (transfer == null || parent.transferredChildren.containsKey(child))
            throw new IllegalStateException("native portal passenger escaped retained graph: child="
                + child.getUUID() + " bound=" + (transfer != null)
                + " repeated=" + parent.transferredChildren.containsKey(child));
        parent.source("PASSENGER_TRANSITION_ACCEPT", () -> {
            transfer.acceptNativePassengerDismount(parent);
            if (child.getVehicle() != null || transfer.sourceLevel != parent.sourceLevel)
                throw new IllegalStateException("native portal passenger was not ejected in source world");
            var expected = PortalPassengerGraphSnapshot.calculatePassengerPlacement(
                parent.transition, parent.source.position(), parent.source.getYRot(), parent.source.getXRot(),
                child.position(), child.getYRot(), child.getXRot());
            TeleportTransition exact = parent.transition.withPosition(expected.position())
                .withRotation(expected.yRot(), expected.xRot()).transitionAsPassenger();
            if (!exact.equals(nativeTransition))
                throw new IllegalStateException("native passenger transition differs from source-owned value");
            if (nativeTransition.relatives().contains(Relative.X)
                    || nativeTransition.relatives().contains(Relative.Y)
                    || nativeTransition.relatives().contains(Relative.Z))
                throw new IllegalStateException("relative-position passenger graph was not prepared");
            ChunkPos arrival = ChunkPos.containing(BlockPos.containing(nativeTransition.position()));
            if (arrival.x() != transfer.destinationKey.x() || arrival.z() != transfer.destinationKey.z())
                throw new IllegalStateException("native passenger dismount crossed prepared destination owner");
            transfer.transition = nativeTransition;
            return null;
        });
        ACTIVE.remove();
        try {
            Entity result = transfer.run();
            parent.transferredChildren.put(child, result);
            return result;
        } finally {
            if (ACTIVE.get() != null) throw new IllegalStateException("passenger transfer retained nested context");
            ACTIVE.set(parent);
        }
    }

    /** Exact native reattachment, after each parent replacement's destination admission. */
    public static boolean attachPassenger(Entity child, Entity parentEntity, Supplier<Boolean> original) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null) return original.get();
        if (transfer.replacement != parentEntity || transfer.graphChildren == null
                || !transfer.transferredChildren.containsValue(child))
            throw new IllegalStateException("native portal reattachment escaped retained graph");
        return transfer.destination("ATTACH_PASSENGER", () -> {
            if (!transfer.inserted || child.level() != transfer.destinationLevel
                    || child.isRemoved() || child.getVehicle() != null)
                throw new IllegalStateException("native portal passenger not ready to reattach");
            var binding = transfer.objects.current(child.getUUID().toString()).orElseThrow();
            if (!binding.routingKey().equals(transfer.destinationKey))
                throw new IllegalStateException("native portal passenger destination spans owners");
            transfer.objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(),
                OwnershipContext.current().orElseThrow().region());
            boolean attached = original.get();
            if (!attached || child.getVehicle() != parentEntity)
                throw new IllegalStateException("native portal passenger reattachment failed");
            transfer.marker("PASSENGER_ATTACHED");
            return true;
        }, true);
    }

    public Entity graphChildResult(Entity child) {
        if (graphChildren == null || !graphChildren.containsKey(child)
                || !transferredChildren.containsKey(child))
            throw new IllegalStateException("native portal graph child has no result");
        return transferredChildren.get(child);
    }
    public RoutingKey destinationKey() { return destinationKey; }

    /** Native source-only stopRiding/ejectPassengers/restore operations. */
    public static void sourceEffect(Entity entity, String phase, Runnable body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.source != entity) { body.run(); return; }
        transfer.source(phase, () -> { body.run(); return null; });
    }
    public static <T> T sourceRead(Entity entity, String phase, Supplier<T> body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.source != entity) return body.get();
        return transfer.source(phase, body);
    }
    public static PositionMoveRotation sourcePosition(Entity entity, Supplier<PositionMoveRotation> body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.source != entity) return body.get();
        if (transfer.retiredSourcePosition == null)
            throw new IllegalStateException("native source position was not captured before retirement");
        return transfer.retiredSourcePosition;
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
        // Entity.restoreFrom first saves the source, then loads the new object.
        // In 26.2 the load can read the replacement's destination world (living
        // team scoreboard, villager brain, minecart spawner, text display). Keep
        // that exact native order while giving each half its owning region.
        var reporter = transfer.destination("RESTORE_REPORTER", () ->
            new ProblemReporter.ScopedCollector(replacement.problemPath(),
                LoggerFactory.getLogger(Entity.class)), true);
        try (reporter) {
            CompoundTag data = transfer.source("RESTORE", () -> {
                TagValueOutput output = TagValueOutput.createWithContext(reporter, source.registryAccess());
                source.saveWithoutId(output);
                return output.buildResult();
            });
            transfer.destination("RESTORE_DESTINATION_LOAD", () -> {
                replacement.load(TagValueInput.create(reporter, replacement.registryAccess(), data));
                return null;
            }, true);
            RestoreTail tail = transfer.source("RESTORE_SOURCE_TAIL", () -> new RestoreTail(
                source.getUUID(), source.getPortalCooldown(), source.portalProcess,
                source instanceof NativePortalRestoreAccess access,
                source instanceof NativePortalRestoreAccess access
                    ? access.phoenium$capturePortalRestoreReference() : null));
            transfer.destination("RESTORE_DESTINATION_TAIL", () -> {
                if (!tail.uuid().equals(replacement.getUUID()))
                    throw new IllegalStateException("native restore changed UUID");
                replacement.setPortalCooldown(tail.cooldown());
                replacement.portalProcess = tail.portalProcess();
                if (tail.hasReferenceOverride()) {
                    if (!(replacement instanceof NativePortalRestoreAccess access))
                        throw new IllegalStateException("native restore reference override changed class");
                    access.phoenium$installPortalRestoreReference(tail.reference());
                } else if (replacement instanceof NativePortalRestoreAccess)
                    throw new IllegalStateException("native restore reference override appeared on replacement");
                return null;
            }, true);
        }
    }
    private record RestoreTail(java.util.UUID uuid, int cooldown, PortalProcessor portalProcess,
                               boolean hasReferenceOverride, EntityReference<?> reference) { }
    public static void removeSource(Entity source, Runnable body) {
        NativePortalTransfer transfer = ACTIVE.get();
        if (transfer == null || transfer.source != source) { body.run(); return; }
        transfer.source("SOURCE_RETIRING", () -> {
            transfer.retiredSourcePosition = PositionMoveRotation.of(source);
            transfer.removal = transfer.boundary.authorizeSourceRemoval(transfer.token, transfer.token.source());
            body.run();
            if (source.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                throw new IllegalStateException("native source did not retire with CHANGED_DIMENSION");
            transfer.authority.retireExpectedSource(source, Entity.RemovalReason.CHANGED_DIMENSION, transfer.effectiveSource);
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
        return execute(effectiveSource.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
            checkSource(); var binding = objects.current(effectiveSource.objectId()).orElseThrow();
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
        checkSourceIdentity();
        if (source.isPassenger() || source.isVehicle() && graphChildren == null)
            throw new IllegalStateException("native portal source graph is not bound");
    }
    private void checkSourceIdentity() {
        checkLive();
        var current = objects.current(effectiveSource.objectId()).orElse(null);
        if (source.isRemoved() || current == null || current.incarnation() != effectiveSource.incarnation()
                || !current.objectEpoch().equals(effectiveSource.objectEpoch())
                || !current.routingKey().equals(effectiveSource.routingKey()))
            throw new IllegalStateException("native portal source incarnation or route changed: captured="
                + capturedSource + " current=" + current + " removed=" + source.isRemoved()
                + " chunk=" + source.chunkPosition() + " vehicle=" + source.getVehicle());
    }

    /** Native stopRiding may move this exact graph leaf to another chunk in the same source owner. */
    private void acceptNativePassengerDismount(NativePortalTransfer parent) {
        if (OwnershipContext.current().isEmpty() || parent.graphChildren == null
                || parent.graphChildren.get(source) != this || parent.source == source
                || graphChildren != null || !source.getPassengers().isEmpty()
                || source.getVehicle() != null || parent.source.getPassengers().contains(source)
                || source.getId() != sourceRuntimeId || effectiveSource != capturedSource)
            throw new IllegalStateException("native portal passenger ejection changed graph identity");
        parent.checkSourceIdentity();
        checkLive();
        ObjectOwnership current = objects.current(capturedSource.objectId()).orElse(null);
        var chunk = source.chunkPosition();
        RoutingKey physical = new RoutingKey(capturedSource.routingKey().world(), chunk.x(), chunk.z());
        if (source.isRemoved() || current == null || !current.objectId().equals(capturedSource.objectId())
                || current.kind() != capturedSource.kind()
                || current.incarnation() != capturedSource.incarnation()
                || !current.objectEpoch().equals(capturedSource.objectEpoch())
                || !current.owner().equals(capturedSource.owner())
                || !current.topologyGeneration().equals(capturedSource.topologyGeneration())
                || !current.regionOwnerEpoch().equals(capturedSource.regionOwnerEpoch())
                || !current.routingKey().equals(physical)
                || !current.routingKey().world().equals(capturedSource.routingKey().world())
                || !OwnershipContext.current().orElseThrow().region().equals(capturedSource.owner()))
            throw new IllegalStateException("native portal passenger ejection crossed source ownership: captured="
                + capturedSource + " current=" + current + " physical=" + physical);
        objects.requireCurrentOwner(current.objectId(), current.objectEpoch(), capturedSource.owner());
        effectiveSource = current;
        checkSource();
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
    ObjectOwnership effectiveSourceBinding() { return effectiveSource; }
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
