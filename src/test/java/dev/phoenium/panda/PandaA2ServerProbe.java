package dev.phoenium.panda;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import dev.phoenium.Phoenium;
import dev.phoenium.core.WorldId;
import dev.phoenium.live.PhoeniumLiveRuntime;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.topology.RoutingKey;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Test-only commands used by the real-client A2 workload. These commands never create entities or
 * synthetic packets: they repeat production observations for actual tracked pearls and, for the
 * owner-reroute witness, perform one bounded topology convergence after an actual cleanup has
 * been admitted. This class is never included in the production Phoenium artifact.
 */
public final class PandaA2ServerProbe implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, selection) ->
            dispatcher.register(Commands.literal("phoenium-test")
                .then(Commands.literal("pearl-reobserve")
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(context -> {
                            String tag = StringArgumentType.getString(context, "tag");
                            int matches = 0;
                            for (var level : context.getSource().getServer().getAllLevels()) {
                                for (Entity entity : level.getAllEntities()) {
                                    if (entity instanceof ThrownEnderpearl && entity.entityTags().contains(tag)) {
                                        Phoenium.liveRuntime().onEntityAddressable(entity);
                                        matches++;
                                    }
                                }
                            }
                            String response = "pearlReobserve tag=" + tag + " matches=" + matches;
                            context.getSource().sendSuccess(() -> Component.literal(response), false);
                            return matches;
                        })))
                .then(Commands.literal("pearl-raw-move")
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                            .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                    .executes(context -> {
                                        String tag = StringArgumentType.getString(context, "tag");
                                        double x = DoubleArgumentType.getDouble(context, "x");
                                        double y = DoubleArgumentType.getDouble(context, "y");
                                        double z = DoubleArgumentType.getDouble(context, "z");
                                        int matches = 0;
                                        String route = "";
                                        for (var level : context.getSource().getServer().getAllLevels()) {
                                            for (Entity entity : level.getAllEntities()) {
                                                if (!(entity instanceof ThrownEnderpearl)
                                                        || !entity.entityTags().contains(tag)) continue;
                                                entity.setPosRaw(x, y, z);
                                                WorldId world = new WorldId(level.dimension().identifier().toString());
                                                route = Phoenium.liveRuntime().routeDiagnostic(world.value(),
                                                    entity.chunkPosition().x(), entity.chunkPosition().z());
                                                matches++;
                                            }
                                        }
                                        String response = "pearlRawMove tag=" + tag + " matches=" + matches
                                            + " route=" + region(route)
                                            + " pendingEntityRelocations="
                                            + Phoenium.liveRuntime().diagnostics().pendingEntityRelocations()
                                            + " entityRelocations=" + Phoenium.liveRuntime().diagnostics().entityRelocations();
                                        context.getSource().sendSuccess(() -> Component.literal(response), false);
                                        return matches;
                                    }))))))
                .then(Commands.literal("pearl-status")
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(context -> {
                            String tag = StringArgumentType.getString(context, "tag");
                            int matches = 0;
                            String route = "";
                            String owner = "UNBOUND";
                            String routingKey = "UNBOUND";
                            for (var level : context.getSource().getServer().getAllLevels()) {
                                for (Entity entity : level.getAllEntities()) {
                                    if (!(entity instanceof ThrownEnderpearl)
                                            || !entity.entityTags().contains(tag)) continue;
                                    matches++;
                                    WorldId world = new WorldId(level.dimension().identifier().toString());
                                    route = Phoenium.liveRuntime().routeDiagnostic(world.value(),
                                        entity.chunkPosition().x(), entity.chunkPosition().z());
                                    ObjectOwnership binding = currentBinding(entity.getUUID().toString());
                                    if (binding != null) {
                                        owner = binding.owner().toString();
                                        routingKey = binding.routingKey().toString();
                                    }
                                }
                            }
                            String response = "pearlStatus tag=" + tag + " matches=" + matches
                                + " route=" + region(route) + " owner=" + owner + " routingKey=" + routingKey
                                + " pendingEntityRelocations="
                                + Phoenium.liveRuntime().diagnostics().pendingEntityRelocations()
                                + " entityRelocations=" + Phoenium.liveRuntime().diagnostics().entityRelocations();
                            context.getSource().sendSuccess(() -> Component.literal(response), false);
                            return matches;
                        })))
                .then(Commands.literal("pearl-converge")
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(context -> {
                            String tag = StringArgumentType.getString(context, "tag");
                            int matches = 0;
                            String routeBefore = "";
                            String routeAfter = "";
                            int pendingBefore = -1;
                            int pendingAfter = -1;
                            for (var level : context.getSource().getServer().getAllLevels()) {
                                for (Entity entity : level.getAllEntities()) {
                                    if (!(entity instanceof ThrownEnderpearl)
                                            || !entity.entityTags().contains(tag)) continue;
                                    matches++;
                                    WorldId world = new WorldId(level.dimension().identifier().toString());
                                    routeBefore = Phoenium.liveRuntime().routeDiagnostic(world.value(),
                                        entity.chunkPosition().x(), entity.chunkPosition().z());
                                    pendingBefore = Phoenium.liveRuntime().diagnostics().pendingPearlCleanups();
                                    Phoenium.liveRuntime().regionalDispatcher().ensureClosure(Set.of(
                                        new RoutingKey(world, 0, 0),
                                        new RoutingKey(world, 20, 0)));
                                    routeAfter = Phoenium.liveRuntime().routeDiagnostic(world.value(),
                                        entity.chunkPosition().x(), entity.chunkPosition().z());
                                    pendingAfter = Phoenium.liveRuntime().diagnostics().pendingPearlCleanups();
                                }
                            }
                            String response = "pearlConverge tag=" + tag + " matches=" + matches
                                + " routeBefore=" + region(routeBefore) + " routeAfter=" + region(routeAfter)
                                + " pendingBefore=" + pendingBefore + " pendingAfter=" + pendingAfter;
                            context.getSource().sendSuccess(() -> Component.literal(response), false);
                            return matches;
                        })))
                .then(Commands.literal("pearl-admit-converge")
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(context -> {
                            String tag = StringArgumentType.getString(context, "tag");
                            int matches = 0;
                            String routeBefore = "";
                            String routeAfter = "";
                            int pendingBefore = -1;
                            int pendingAfter = -1;
                            for (var level : context.getSource().getServer().getAllLevels()) {
                                for (Entity entity : level.getAllEntities()) {
                                    if (!(entity instanceof ThrownEnderpearl)
                                            || !entity.entityTags().contains(tag)) continue;
                                    matches++;
                                    WorldId world = new WorldId(level.dimension().identifier().toString());
                                    routeBefore = Phoenium.liveRuntime().routeDiagnostic(world.value(),
                                        entity.chunkPosition().x(), entity.chunkPosition().z());
                                    Phoenium.liveRuntime().onEntityAddressable(entity);
                                    pendingBefore = Phoenium.liveRuntime().diagnostics().pendingPearlCleanups();
                                    Phoenium.liveRuntime().regionalDispatcher().ensureClosure(Set.of(
                                        new RoutingKey(world, 0, 0),
                                        new RoutingKey(world, 20, 0)));
                                    routeAfter = Phoenium.liveRuntime().routeDiagnostic(world.value(),
                                        entity.chunkPosition().x(), entity.chunkPosition().z());
                                    pendingAfter = Phoenium.liveRuntime().diagnostics().pendingPearlCleanups();
                                }
                            }
                            String response = "pearlAdmitConverge tag=" + tag + " matches=" + matches
                                + " routeBefore=" + region(routeBefore) + " routeAfter=" + region(routeAfter)
                                + " pendingBefore=" + pendingBefore + " pendingAfter=" + pendingAfter;
                            context.getSource().sendSuccess(() -> Component.literal(response), false);
                            return matches;
                        }))))
        );
    }

    private static String region(String route) {
        int start = route.indexOf("region=");
        if (start < 0) return "UNRESOLVED";
        start += "region=".length();
        int end = route.indexOf(' ', start);
        return end < 0 ? route.substring(start) : route.substring(start, end);
    }

    private static ObjectOwnership currentBinding(String objectId) {
        try {
            Field field = PhoeniumLiveRuntime.class.getDeclaredField("objects");
            field.setAccessible(true);
            ObjectOwnershipRegistry registry = (ObjectOwnershipRegistry) field.get(Phoenium.liveRuntime());
            return registry.current(objectId).orElse(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("test-only ownership status could not inspect the live registry", failure);
        }
    }

}
