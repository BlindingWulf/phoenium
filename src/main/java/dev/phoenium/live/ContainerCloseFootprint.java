package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import dev.phoenium.mixin.ContainerCloseCompoundAccess;
import dev.phoenium.mixin.ContainerCloseEnderAccess;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyPlanner;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Exact native container recipients, captured before the close prefix, never from a nearby block lookup. */
public record ContainerCloseFootprint(List<Target> targets, Set<RoutingKey> closure) {
    public ContainerCloseFootprint { targets = List.copyOf(targets); closure = Set.copyOf(closure); }
    public record Target(BlockEntity container, ServerLevel level, BlockPos position) { }

    public static ContainerCloseFootprint capture(ServerPlayer player, AbstractContainerMenu menu) {
        var targets = new ArrayList<Target>();
        Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (menu instanceof ChestMenu chest) collect(chest.getContainer(), seen, targets);
        for (var slot : menu.slots) collect(slot.container, seen, targets);
        var world = WorldId.of(player.level().dimension().identifier().toString());
        var seeds = new LinkedHashSet<RoutingKey>();
        seeds.add(new RoutingKey(world, player.chunkPosition().x(), player.chunkPosition().z()));
        for (Target target : targets) {
            // Native player removal closes its old menu before installing another world. A
            // network request cannot acquire a cross-world owner by assuming player locality.
            if (target.level() != player.level()) throw new IllegalStateException("container close recipient is outside current player world");
            seeds.add(new RoutingKey(world, target.position().getX() >> 4, target.position().getZ() >> 4));
        }
        return new ContainerCloseFootprint(targets, new TopologyPlanner().requiredClosure(seeds, 1));
    }
    private static void collect(Container container, Set<Container> seen, List<Target> targets) {
        if (!seen.add(container)) return;
        if (container instanceof CompoundContainer) {
            var access = (ContainerCloseCompoundAccess) container;
            collect(access.phoenium$firstContainer(), seen, targets);
            collect(access.phoenium$secondContainer(), seen, targets);
        } else if (container instanceof PlayerEnderChestContainer) {
            add(((ContainerCloseEnderAccess) container).phoenium$activeChest(), targets);
        } else if (container instanceof BlockEntity block) add(block, targets);
    }
    private static void add(BlockEntity block, List<Target> targets) {
        if (block == null) return;
        if (!(block.getLevel() instanceof ServerLevel level)) throw new IllegalStateException("container close recipient has no server world");
        targets.add(new Target(block, level, block.getBlockPos().immutable()));
    }
}
