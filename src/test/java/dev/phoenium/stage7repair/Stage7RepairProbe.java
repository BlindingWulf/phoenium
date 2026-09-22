package dev.phoenium.stage7repair;

import com.google.gson.*;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

/** Read-only installed observer. No production lifecycle/admission helper is called. */
public final class Stage7RepairProbe implements DedicatedServerModInitializer {
    private static final java.util.concurrent.atomic.AtomicLong caneAdds=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong caneRemoves=new java.util.concurrent.atomic.AtomicLong();
    public static boolean relevant(Entity e) {
        return e instanceof ServerPlayer || e instanceof ThrownEnderpearl || e.entityTags().contains("stage7_mob")
            || e instanceof ItemEntity item && item.getItem().is(Items.SUGAR_CANE);
    }
    public static JsonObject entity(Entity e) {
        JsonObject j=new JsonObject();
        j.addProperty("uuid",e.getUUID().toString());
        j.addProperty("object",Integer.toHexString(System.identityHashCode(e)));
        j.addProperty("nativeId",e.getId());
        j.addProperty("world",e.level().dimension().identifier().toString());
        j.addProperty("worldObject",Integer.toHexString(System.identityHashCode(e.level())));
        j.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        j.addProperty("x",e.getX());j.addProperty("y",e.getY());j.addProperty("z",e.getZ());
        j.addProperty("removed",e.isRemoved());
        j.addProperty("ticks",e.tickCount);
        j.addProperty("reason",String.valueOf(e.getRemovalReason()));
        if(e instanceof ItemEntity item) {j.addProperty("count",item.getItem().getCount());j.addProperty("age",item.getAge());}
        if(e instanceof ThrownEnderpearl pearl) {
            Entity owner=pearl.getOwner();j.addProperty("ownerObject",owner==null?"UNAVAILABLE":Integer.toHexString(System.identityHashCode(owner)));
            j.addProperty("ownerWorld",owner==null?"UNAVAILABLE":owner.level().dimension().identifier().toString());
        }
        if(e instanceof ServerPlayer p) {
            j.addProperty("name",p.getName().getString());j.addProperty("health",p.getHealth());j.addProperty("deathTime",p.deathTime);
            j.addProperty("cane",p.getInventory().countItem(Items.SUGAR_CANE));
            j.addProperty("pearls",p.getInventory().countItem(Items.ENDER_PEARL));
            j.addProperty("connectionObject",Integer.toHexString(System.identityHashCode(p.connection)));
            j.addProperty("listenerPlayerObject",Integer.toHexString(System.identityHashCode(p.connection.player)));
        }
        return j;
    }
    public static void observe(String phase,Entity e) {
        if(!relevant(e))return;
        if(e instanceof ItemEntity item&&item.getItem().is(Items.SUGAR_CANE)){
            if(phase.equals("TRACKING_ADD_RETURN"))caneAdds.incrementAndGet();
            if(phase.equals("TRACKING_REMOVE_RETURN"))caneRemoves.incrementAndGet();
        }
        try {JsonObject j=entity(e);j.addProperty("phase",phase);LoggerFactory.getLogger("Stage7RepairProbe").info("STAGE7_NATIVE {}",j);}
        catch(Throwable failed) {LoggerFactory.getLogger("Stage7RepairProbe").error("STAGE7_OBSERVER_FAILED phase={}",phase,failed);}
    }
    public static JsonObject snapshot(MinecraftServer server) {
        JsonObject j=new JsonObject();JsonArray entities=new JsonArray(),players=new JsonArray();
        for(var level:server.getAllLevels())for(Entity e:level.getAllEntities())if(relevant(e))entities.add(entity(e));
        for(var player:server.getPlayerList().getPlayers())players.add(entity(player));
        j.add("entities",entities);j.add("players",players);j.addProperty("caneAdds",caneAdds.get());j.addProperty("caneRemoves",caneRemoves.get());return j;
    }
    @Override public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher,access,selection)->dispatcher.register(
            Commands.literal("stage7-probe").then(Commands.literal("snapshot").executes(context->{
                String value=snapshot(context.getSource().getServer()).toString();
                context.getSource().sendSuccess(()->Component.literal(value),false);return 1;
            }))));
    }
}
