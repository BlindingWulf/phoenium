package dev.phoenium.closeout;

import com.google.gson.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Stage 7 closeout client agent. It does nothing on its own: a real client connects as PhoeniumTest0 and then answers
 * one command file at a time ({control}/cmd.json -> {control}/resp.json). The scenario, the fixture and every
 * server-side observation belong to scripts/stage7/client_closeout.py; this agent only reports what the REAL client sees
 * (its own dimension, position, vehicle, loaded entities, inventory, open screen) and performs real input.
 */
public final class Stage7CloseoutClient implements FabricClientGameTest {
    static final String USER = "PhoeniumTest0";
    final Path control = Path.of(System.getProperty("phoenium.closeout.control"));
    ClientGameTestContext context;

    @Override public void runTest(ClientGameTestContext ctx) {
        context = ctx;
        try {
            context.runOnClient(c -> {
                c.options.renderDistance().set(2);
                c.options.simulationDistance().set(5);
                c.options.framerateLimit().set(20);
                c.options.enableVsync().set(false);
            });
            connect();
            Files.writeString(control.resolve("ready"), "ready", StandardCharsets.UTF_8);
            int idle = 0;
            while (!Files.exists(control.resolve("stop"))) {
                Path command = control.resolve("cmd.json");
                if (!Files.exists(command)) {
                    context.waitTick();
                    if (++idle > 20 * 3600) throw new IllegalStateException("closeout agent idle for an hour");
                    continue;
                }
                idle = 0;
                JsonObject cmd = JsonParser.parseString(Files.readString(command, StandardCharsets.UTF_8)).getAsJsonObject();
                Files.delete(command);
                JsonObject response = new JsonObject();
                response.addProperty("id", cmd.get("id").getAsInt());
                try {
                    handle(cmd, response);
                    response.addProperty("ok", true);
                } catch (Throwable failure) {
                    response.addProperty("ok", false);
                    response.addProperty("error", String.valueOf(failure));
                }
                Path tmp = control.resolve("resp.tmp");
                Files.writeString(tmp, response.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, control.resolve("resp.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    void connect() {
        String address = System.getProperty("phoenium.closeout.address");
        context.runOnClient(c -> ConnectScreen.startConnecting(c.gui.screen(), c, ServerAddress.parseString(address),
                new ServerData("Stage7 closeout", address, ServerData.Type.OTHER), false, null));
        context.waitFor(c -> c.player != null && c.level != null, 1800);
        context.waitTicks(25);
    }

    void disconnect() {
        context.runOnClient(c -> {
            if (c.getConnection() != null) c.getConnection().getConnection().disconnect(Component.literal("Stage7 closeout reconnect"));
            c.disconnect(new TitleScreen(), false);
        });
        context.waitFor(c -> c.level == null, 600);
    }

    void handle(JsonObject cmd, JsonObject response) {
        switch (cmd.get("op").getAsString()) {
            case "connect" -> connect();
            case "disconnect" -> disconnect();
            case "snapshot" -> response.add("snapshot", snapshot());
            case "wait" -> context.waitTicks(cmd.get("ticks").getAsInt());
            case "hold" -> {
                String key = cmd.get("key").getAsString();
                context.getInput().holdKey(o -> pick(o, key));
                context.waitTicks(cmd.get("ticks").getAsInt());
                context.getInput().releaseKey(o -> pick(o, key));
            }
            case "press" -> {
                String key = cmd.get("key").getAsString();
                context.getInput().pressKey(o -> pick(o, key));
                context.waitTicks(cmd.has("ticks") ? cmd.get("ticks").getAsInt() : 2);
            }
            case "look" -> {
                float yaw = cmd.get("yaw").getAsFloat(), pitch = cmd.get("pitch").getAsFloat();
                context.runOnClient(c -> {
                    c.player.setYRot(yaw);
                    c.player.setXRot(pitch);
                });
                context.waitTicks(2);
            }
            case "closeScreen" -> {
                context.runOnClient(c -> {
                    if (c.gui.screen() != null) c.gui.screen().onClose();
                });
                context.waitTicks(5);
            }
            case "respawn" -> {
                context.runOnClient(c -> c.player.respawn());
                context.waitTicks(10);
            }
            case "waitScreenGone" -> context.waitFor(c -> c.gui.screen() == null && c.player != null && c.level != null,
                    cmd.get("ticks").getAsInt());
            default -> throw new IllegalArgumentException("unknown op " + cmd.get("op"));
        }
    }

    static net.minecraft.client.KeyMapping pick(net.minecraft.client.Options o, String key) {
        return switch (key) {
            case "forward" -> o.keyUp;
            case "back" -> o.keyDown;
            case "left" -> o.keyLeft;
            case "right" -> o.keyRight;
            case "jump" -> o.keyJump;
            case "sneak" -> o.keyShift;
            case "use" -> o.keyUse;
            case "attack" -> o.keyAttack;
            case "slot1" -> o.keyHotbarSlots[0];
            case "slot2" -> o.keyHotbarSlots[1];
            case "slot3" -> o.keyHotbarSlots[2];
            default -> throw new IllegalArgumentException("unknown key " + key);
        };
    }

    JsonObject snapshot() {
        return context.computeOnClient(c -> {
            JsonObject out = new JsonObject();
            out.addProperty("clientTime", System.currentTimeMillis());
            out.addProperty("screen", c.gui.screen() == null ? null : c.gui.screen().getClass().getSimpleName());
            if (c.player == null || c.level == null) {
                out.addProperty("connected", false);
                return out;
            }
            out.addProperty("connected", true);
            out.addProperty("dimension", c.level.dimension().identifier().toString());
            out.addProperty("gameTime", c.level.getGameTime());
            JsonObject player = describe(c.player);
            player.addProperty("health", c.player.getHealth());
            player.addProperty("alive", c.player.isAlive());
            player.addProperty("sleeping", c.player.isSleeping());
            player.addProperty("onGround", c.player.onGround());
            player.addProperty("yRot", c.player.getYRot());
            player.addProperty("xRot", c.player.getXRot());
            out.add("player", player);
            JsonArray entities = new JsonArray();
            for (Entity e : c.level.entitiesForRendering()) {
                if (e == c.player) continue;
                entities.add(describe(e));
            }
            out.add("entities", entities);
            JsonObject inventory = new JsonObject();
            var inv = c.player.getInventory();
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                var stack = inv.getItem(slot);
                if (stack.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                inventory.addProperty(id, (inventory.has(id) ? inventory.get(id).getAsInt() : 0) + stack.getCount());
            }
            out.add("inventory", inventory);
            return out;
        });
    }

    static JsonObject describe(Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("uuid", e.getUUID().toString());
        o.addProperty("id", e.getId());
        o.addProperty("x", e.getX());
        o.addProperty("y", e.getY());
        o.addProperty("z", e.getZ());
        o.addProperty("removed", e.isRemoved());
        Entity vehicle = e.getVehicle();
        if (vehicle == null) o.add("vehicleUuid", JsonNull.INSTANCE);
        else o.addProperty("vehicleUuid", vehicle.getUUID().toString());
        JsonArray passengers = new JsonArray();
        for (Entity p : e.getPassengers()) passengers.add(p.getUUID().toString());
        o.add("passengerUuids", passengers);
        return o;
    }
}
