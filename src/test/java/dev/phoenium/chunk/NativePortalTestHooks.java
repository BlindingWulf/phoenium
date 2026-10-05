package dev.phoenium.chunk;

import dev.phoenium.live.NativePortalTransfer;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;

public final class NativePortalTestHooks {
    static Consumer<Entity> base = entity -> { }, suffix = entity -> { };
    static Consumer<Entity> ticket = entity -> entity.placePortalTicket(BlockPos.containing(entity.position()));
    static Consumer<Entity> projectile = entity -> { }, pearl = entity -> { };
    static Consumer<Entity> hurting = entity -> { };
    static Consumer<Entity> windCharge = entity -> { };
    static java.util.function.BiConsumer<Entity,net.minecraft.world.phys.HitResult> hit = (entity,result) -> { };
    public static void baseSuffix(Entity entity) { base.accept(entity); }
    public static void itemSuffix(ItemEntity entity) { suffix.accept(entity); }
    public static void projectileSuffix(Entity entity) { projectile.accept(entity); }
    public static void hurtingProjectileSuffix(Entity entity,net.minecraft.world.phys.HitResult result) {
        hurting.accept(entity);
    }
    public static void windChargeSuffix(Entity entity) { windCharge.accept(entity); }
    public static void throwableSuffix(Entity entity,net.minecraft.world.phys.HitResult result) { hit.accept(entity,result); }
    public static void pearlSuffix(Entity entity,int x,int z,Entity owner) { pearl.accept(entity); }
    public static void stopRiding(Entity entity) { NativePortalTransfer.sourceEffect(entity,"STOP_RIDING",entity::stopRiding); }
    public static void ejectPassengers(Entity entity) { NativePortalTransfer.sourceEffect(entity,"EJECT",entity::ejectPassengers); }
    public static Entity create(EntityType<?> type, Level level, EntitySpawnReason reason, Entity source) {
        return NativePortalTransfer.create(source, () -> type.create(level,reason));
    }
    public static void restoreFrom(Entity replacement, Entity source) { NativePortalTransfer.restore(replacement,source,() -> replacement.restoreFrom(source)); }
    public static void removeAfterChangingDimensions(Entity source) {
        NativePortalTransfer.removeSource(source, () -> invoke(Entity.class,source,"removeAfterChangingDimensions"));
    }
    public static void teleportSetPosition(Entity entity, PositionMoveRotation from, PositionMoveRotation to, Set<Relative> relatives) {
        NativePortalTransfer.destinationEffect(entity,"POSITION",() -> entity.teleportSetPosition(from,to,relatives));
    }
    public static void addDuringTeleport(ServerLevel level, Entity entity) { NativePortalTransfer.insert(level,entity,() -> level.addDuringTeleport(entity)); }
    public static void resetEmptyTime(ServerLevel level) { NativePortalTransfer.destinationWorldEffect(level,level::resetEmptyTime); }
    public static void onTransition(TeleportTransition.PostTeleportTransition callback, Entity entity) {
        NativePortalTransfer.destinationEffect(entity,"POST_TRANSITION",() -> callback.onTransition(entity));
    }
    public static void mergeWithNeighbours(ItemEntity entity) {
        NativePortalTransfer.destinationEffect(entity,"ITEM_TELEPORT_SUFFIX",() -> invoke(ItemEntity.class,entity,"mergeWithNeighbours"));
    }
    /** Mirrors PortalTicketEntityMixin's head cut; null means the repair is not installed. */
    static dev.phoenium.live.PortalTicketBoundary tickets;
    public static void placePortalTicket(Entity entity, BlockPos pos) {
        if (dev.phoenium.ownership.OwnershipContext.current().isPresent()) {
            var boundary = tickets;
            if (boundary != null && boundary.place(entity, pos)) return;
            ticket.accept(entity); return;
        }
        NativePortalTransfer.destinationEffect(entity,"PEARL_TELEPORT_SUFFIX",() -> ticket.accept(entity));
    }
    private static void invoke(Class<?> owner, Object value, String name) {
        try { var method=owner.getDeclaredMethod(name); method.setAccessible(true); method.invoke(value); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
