package dev.phoenium.stage7repair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

/** Read-only test witness for the exact native vehicle and ordered passenger UUID edges. */
public final class PortalGraphProbe {
    private PortalGraphProbe() { }

    public static void addEdges(JsonObject row, Entity entity) {
        Entity vehicle = entity.getVehicle();
        row.addProperty("vehicleUuid", vehicle == null ? "UNAVAILABLE" : vehicle.getUUID().toString());
        JsonArray passengers = new JsonArray();
        for (Entity passenger : entity.getPassengers())
            passengers.add(passenger.getUUID().toString());
        row.add("passengerUuids", passengers);
    }

    /** Bounded live-world witness; retain duplicate UUID rows so the caller can reject them. */
    public static JsonObject snapshot(MinecraftServer server, UUID root, UUID child) {
        if (root.equals(child)) throw new IllegalArgumentException("graph UUIDs must differ");
        JsonObject result = new JsonObject();
        result.addProperty("schema", "stage7-graph-snapshot-v1");
        result.addProperty("rootUuid", root.toString());
        result.addProperty("childUuid", child.toString());
        JsonArray entities = new JsonArray();
        for (var level : server.getAllLevels())
            for (Entity entity : level.getAllEntities())
                if (entity.getUUID().equals(root) || entity.getUUID().equals(child))
                    entities.add(Stage7RepairProbe.entity(entity));
        result.add("entities", entities);
        return result;
    }
}
