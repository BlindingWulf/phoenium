package dev.phoenium.live;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RoutingKey;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * A command executed inside a region owner turn (command block, command-block minecart, entity or enchantment
 * function) may only see and act in what that owner owns, as in Folia: a region cannot modify an entity or a block
 * outside its owning region. A foreign dimension is a command syntax failure before any mutation, selectors skip
 * entities owned elsewhere, and a source can never be moved into a foreign world. Owner-free sources (console,
 * RCON, player chat, scheduled functions) are not confined.
 */
public final class CommandOwnerConfinement {
    @FunctionalInterface
    public interface Routes { Optional<RegionId> owner(RoutingKey key); }

    private static volatile Routes routes;
    private static volatile SimpleCommandExceptionType foreignDimension;
    private static final AtomicLong REFUSALS = new AtomicLong();

    private CommandOwnerConfinement() {}

    public static void install(Routes installed) { routes = installed; }
    public static void uninstall() { routes = null; }
    public static long refusals() { return REFUSALS.get(); }

    static boolean admitsWorld(Optional<OwnershipContext.Active> owner, WorldId world) {
        return owner.isEmpty() || owner.get().region().world().equals(world);
    }

    static boolean admitsChunk(Optional<OwnershipContext.Active> owner, Routes routes, WorldId world, int chunkX, int chunkZ) {
        if (owner.isEmpty()) return true;
        if (!admitsWorld(owner, world) || routes == null) return false;
        return routes.owner(new RoutingKey(world, chunkX, chunkZ)).map(owner.get().region()::equals).orElse(false);
    }

    public static boolean confining() { return OwnershipContext.current().isPresent(); }
    public static boolean admitsWorld(WorldId world) { return admitsWorld(OwnershipContext.current(), world); }
    public static boolean admitsChunk(WorldId world, int chunkX, int chunkZ) {
        return admitsChunk(OwnershipContext.current(), routes, world, chunkX, chunkZ);
    }

    public static boolean admitsEntity(Entity entity) {
        if (!confining()) return true;
        Level level = entity.level();
        return admitsChunk(worldOf(level), entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
    }

    public static <T extends Entity> List<T> filter(List<T> entities) {
        if (!confining() || entities.stream().allMatch(CommandOwnerConfinement::admitsEntity)) return entities;
        REFUSALS.incrementAndGet();
        return entities.stream().filter(CommandOwnerConfinement::admitsEntity).toList();
    }

    /** {@code execute in <dimension>}: a foreign dimension fails as a command error before the command runs. */
    public static void requireDimension(ServerLevel level) throws CommandSyntaxException {
        if (admitsWorld(worldOf(level))) return;
        REFUSALS.incrementAndGet();
        SimpleCommandExceptionType type = foreignDimension;
        if (type == null) foreignDimension = type = new SimpleCommandExceptionType(
            Component.literal("That dimension is owned by another region; commands run inside a region cannot reach it"));
        throw type.create();
    }

    /** Backstop for source moves that do not pass a selector or a dimension argument (for example {@code execute on}). */
    public static void requireLevel(ServerLevel level) {
        if (admitsWorld(worldOf(level))) return;
        REFUSALS.incrementAndGet();
        throw new IllegalStateException("command source cannot move into " + worldOf(level).value() + " from another region's owner turn");
    }

    public static void requireEntity(Entity entity) {
        if (entity == null || admitsEntity(entity)) return;
        REFUSALS.incrementAndGet();
        throw new IllegalStateException("command source cannot act as an entity owned by another region");
    }

    static WorldId worldOf(Level level) { return new WorldId(level.dimension().identifier().toString()); }
}
