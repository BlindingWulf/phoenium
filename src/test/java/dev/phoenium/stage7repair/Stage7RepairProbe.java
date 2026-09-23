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
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

/** Read-only installed observer. No production lifecycle/admission helper is called. */
public final class Stage7RepairProbe implements DedicatedServerModInitializer {
    private static final java.util.concurrent.atomic.AtomicLong caneAdds=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong caneRemoves=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.ArrayDeque<JsonObject> fallingEvents=new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<JsonObject> projectileEvents=new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<JsonObject> projectileImpactEvents=new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<JsonObject> noEntryContactEvents=new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<JsonObject> gatewayContactEvents=new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<JsonObject> gatewayTicketEvents=new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<JsonObject> gatewayTrackingEvents=new java.util.ArrayDeque<>();
    public static boolean relevant(Entity e) {
        return e instanceof ServerPlayer || e instanceof ThrownEnderpearl || e.entityTags().contains("stage7_mob")
            || e.entityTags().stream().anyMatch(tag->tag.startsWith("stage7_projectile_"))
            || e.entityTags().stream().anyMatch(tag->tag.startsWith("stage7_decorative_"))
            || e.entityTags().stream().anyMatch(tag->tag.startsWith("stage7_noentry_"))
            || e.entityTags().stream().anyMatch(tag->tag.startsWith("stage7_vehicle_"))
            || e instanceof net.minecraft.world.entity.projectile.FishingHook hook
                && hook.getPlayerOwner()!=null && hook.getPlayerOwner().getName().getString().equals("PhoeniumTest0")
            || e instanceof FallingBlockEntity
            || e instanceof net.minecraft.world.entity.monster.EnderMan
            || e instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin
            || e instanceof net.minecraft.world.entity.animal.chicken.Chicken
            || e instanceof net.minecraft.world.entity.animal.pig.Pig
            || e instanceof ItemEntity item && (item.getItem().is(Items.SUGAR_CANE)
                || item.getItem().is(Items.IRON_INGOT) || item.getItem().is(Items.DIAMOND));
    }
    public static JsonObject entity(Entity e) {
        JsonObject j=new JsonObject();
        j.addProperty("uuid",e.getUUID().toString());
        j.addProperty("object",Integer.toHexString(System.identityHashCode(e)));
        j.addProperty("nativeId",e.getId());
        j.addProperty("world",e.level().dimension().identifier().toString());
        j.addProperty("worldObject",Integer.toHexString(System.identityHashCode(e.level())));
        j.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        j.addProperty("tags",e.entityTags().toString());
        j.addProperty("x",e.getX());j.addProperty("y",e.getY());j.addProperty("z",e.getZ());
        j.addProperty("removed",e.isRemoved());
        j.addProperty("portalProcessPresent",e.portalProcess!=null);
        j.addProperty("portalCooldown",e.getPortalCooldown());
        j.addProperty("chunkX",e.chunkPosition().x());j.addProperty("chunkZ",e.chunkPosition().z());
        j.addProperty("ticks",e.tickCount);
        j.addProperty("reason",String.valueOf(e.getRemovalReason()));
        PortalGraphProbe.addEdges(j,e);
        if(e.entityTags().contains("stage7_gateway_pig"))
            j.addProperty("ownershipDiagnostic",
                dev.phoenium.Phoenium.liveRuntime().passengerOwnershipDiagnostic(e.getUUID().toString()));
        if(e instanceof net.minecraft.world.entity.LivingEntity living) {
            j.addProperty("health",living.getHealth());
            j.addProperty("poisoned",living.hasEffect(net.minecraft.world.effect.MobEffects.POISON));
        }
        if(e instanceof ItemEntity item) {j.addProperty("count",item.getItem().getCount());j.addProperty("age",item.getAge());j.addProperty("item",BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());}
        if(e instanceof net.minecraft.world.entity.vehicle.boat.AbstractChestBoat chest) {
            var table=chest.getContainerLootTable();
            j.addProperty("vehicleLootTable",table==null?"UNAVAILABLE":table.identifier().toString());
            j.addProperty("vehicleLootSeed",chest.getContainerLootTableSeed());
            j.addProperty("vehicleSlotCount",chest.getItemStacks().size());
            JsonArray stored=new JsonArray();
            for(int slot=0;slot<chest.getItemStacks().size();slot++) {
                var stack=chest.getItemStacks().get(slot);
                if(stack.isEmpty())continue;
                JsonObject item=new JsonObject();item.addProperty("slot",slot);
                item.addProperty("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                item.addProperty("count",stack.getCount());stored.add(item);
            }
            j.add("vehicleItems",stored);
        }
        if(e instanceof net.minecraft.world.entity.vehicle.minecart.MinecartChest chest) {
            var table=chest.getContainerLootTable();
            j.addProperty("vehicleLootTable",table==null?"UNAVAILABLE":table.identifier().toString());
            j.addProperty("vehicleLootSeed",chest.getContainerLootTableSeed());
            j.addProperty("vehicleSlotCount",chest.getItemStacks().size());
            JsonArray stored=new JsonArray();
            for(int slot=0;slot<chest.getItemStacks().size();slot++) {
                var stack=chest.getItemStacks().get(slot);
                if(stack.isEmpty())continue;
                JsonObject item=new JsonObject();item.addProperty("slot",slot);
                item.addProperty("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                item.addProperty("count",stack.getCount());stored.add(item);
            }
            j.add("vehicleItems",stored);
        }
        if(e instanceof FallingBlockEntity falling) {
            j.addProperty("fallingState",falling.getBlockState().toString());
            j.addProperty("fallingTime",falling.time);
            j.addProperty("forceTickAfterTeleportToDuplicate",falling.forceTickAfterTeleportToDuplicate);
            j.addProperty("hasBlockData",falling.blockData!=null);
        }
        if(e instanceof net.minecraft.world.entity.decoration.ArmorStand stand) {
            j.addProperty("small",stand.isSmall());j.addProperty("marker",stand.isMarker());
        }
        if(e instanceof net.minecraft.world.entity.decoration.ArmorStand
                || e instanceof net.minecraft.world.entity.decoration.Mannequin) {
            var living=(net.minecraft.world.entity.LivingEntity)e;
            j.addProperty("head",BuiltInRegistries.ITEM.getKey(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem()).toString());
            j.addProperty("mainhand",BuiltInRegistries.ITEM.getKey(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).getItem()).toString());
        }
        if(e instanceof net.minecraft.world.entity.animal.chicken.Chicken chicken) {j.addProperty("flap",chicken.flap);j.addProperty("eggTime",chicken.eggTime);}
        if(e instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob cube) {
            j.addProperty("size",cube.getSize());j.addProperty("health",cube.getHealth());
            j.addProperty("targetSquish",cube.targetSquish);j.addProperty("squish",cube.squish);
            j.addProperty("oldSquish",cube.oSquish);
        }
        if(e instanceof ThrownEnderpearl pearl) {
            Entity owner=pearl.getOwner();j.addProperty("ownerObject",owner==null?"UNAVAILABLE":Integer.toHexString(System.identityHashCode(owner)));
            j.addProperty("ownerWorld",owner==null?"UNAVAILABLE":owner.level().dimension().identifier().toString());
        }
        if(e instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            Entity owner=projectile.getOwner();
            j.addProperty("projectileOwnerUuid",owner==null?"UNAVAILABLE":owner.getUUID().toString());
            j.addProperty("motionX",e.getDeltaMovement().x());
            j.addProperty("motionY",e.getDeltaMovement().y());
            j.addProperty("motionZ",e.getDeltaMovement().z());
        }
        if(e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow)
            j.addProperty("noPhysics",arrow.isNoPhysics());
        if(e instanceof ServerPlayer p) {
            j.addProperty("name",p.getName().getString());j.addProperty("health",p.getHealth());j.addProperty("deathTime",p.deathTime);
            j.addProperty("cane",p.getInventory().countItem(Items.SUGAR_CANE));
            j.addProperty("pearls",p.getInventory().countItem(Items.ENDER_PEARL));
            j.addProperty("glowstone",p.getInventory().countItem(Items.GLOWSTONE));
            j.addProperty("iron",p.getInventory().countItem(Items.IRON_INGOT));
            j.addProperty("menuId",p.containerMenu.containerId);j.addProperty("menuOpen",p.containerMenu!=p.inventoryMenu);
            j.addProperty("menuSlots",p.containerMenu.slots.size());
            j.addProperty("cursorIron",p.containerMenu.getCarried().is(Items.IRON_INGOT)?p.containerMenu.getCarried().getCount():0);
            j.addProperty("connectionObject",Integer.toHexString(System.identityHashCode(p.connection)));
            j.addProperty("listenerPlayerObject",Integer.toHexString(System.identityHashCode(p.connection.player)));
            var respawn=p.getRespawnConfig();
            if(respawn!=null) {
                j.addProperty("respawnDimension",respawn.respawnData().dimension().identifier().toString());
                j.addProperty("respawnPosition",respawn.respawnData().pos().toShortString());
                j.addProperty("respawnForced",respawn.forced());
            }
        }
        return j;
    }
    public static void observe(String phase,Entity e) {
        if(!relevant(e))return;
        if(e instanceof ItemEntity item&&item.getItem().is(Items.SUGAR_CANE)){
            if(phase.equals("TRACKING_ADD_RETURN"))caneAdds.incrementAndGet();
            if(phase.equals("TRACKING_REMOVE_RETURN"))caneRemoves.incrementAndGet();
        }
        try {JsonObject j=entity(e);j.addProperty("phase",phase);
            if(e.entityTags().contains("stage7_gateway_pig")) {
                j.addProperty("ownerWorld",dev.phoenium.ownership.OwnershipContext.current()
                    .map(context->context.region().world().value()).orElse("NONE"));
                j.addProperty("ownerContext",dev.phoenium.ownership.OwnershipContext.current()
                    .map(Object::toString).orElse("NONE"));
                if(phase.equals("TRACKING_ADD_RETURN")||phase.equals("TRACKING_REMOVE_RETURN")
                        ||phase.equals("TRACKING_UPDATE_RETURN"))
                    synchronized(gatewayTrackingEvents) {
                        gatewayTrackingEvents.addLast(j.deepCopy());
                        while(gatewayTrackingEvents.size()>8)gatewayTrackingEvents.removeFirst();
                    }
            }
            if(e instanceof FallingBlockEntity && (phase.equals("TRACKING_ADD_RETURN") || phase.equals("TRACKING_REMOVE_RETURN")))
                synchronized(fallingEvents) {
                    fallingEvents.addLast(j.deepCopy());
                    while(fallingEvents.size()>128)fallingEvents.removeFirst();
                }
            if(e.entityTags().stream().anyMatch(tag->tag.startsWith("stage7_projectile_"))
                    && (phase.equals("TRACKING_ADD_RETURN") || phase.equals("TRACKING_REMOVE_RETURN")))
                synchronized(projectileEvents) {
                    projectileEvents.addLast(j.deepCopy());
                    while(projectileEvents.size()>256)projectileEvents.removeFirst();
                }
            LoggerFactory.getLogger("Stage7RepairProbe").info("STAGE7_NATIVE {}",j);}
        catch(Throwable failed) {LoggerFactory.getLogger("Stage7RepairProbe").error("STAGE7_OBSERVER_FAILED phase={}",phase,failed);}
    }
    /** Test-only witness for the vanilla egg/snowball byte-3 impact broadcast. */
    public static void observeProjectileImpactEvent(Entity entity,byte event) {
        if(event!=3 || entity.entityTags().stream().noneMatch(tag->tag.startsWith("stage7_projectile_")))return;
        JsonObject observed=entity(entity);
        observed.addProperty("phase","NATIVE_IMPACT_ENTITY_EVENT");
        observed.addProperty("eventByte",event);
        synchronized(projectileImpactEvents) {
            projectileImpactEvents.addLast(observed.deepCopy());
            while(projectileImpactEvents.size()>128)projectileImpactEvents.removeFirst();
        }
        LoggerFactory.getLogger("Stage7RepairProbe").info("STAGE7_NATIVE {}",observed);
    }
    /** Test-only HEAD witness: the native portal block visited this exact entity. */
    public static void observeNoEntryContact(Entity entity,net.minecraft.world.level.block.state.BlockState state,
                                              net.minecraft.core.BlockPos pos) {
        if(entity.entityTags().stream().noneMatch(tag->tag.startsWith("stage7_noentry_"))
                && !(entity instanceof net.minecraft.world.entity.projectile.FishingHook hook
                    && hook.getPlayerOwner()!=null
                    && hook.getPlayerOwner().getName().getString().equals("PhoeniumTest0")))return;
        JsonObject contact=new JsonObject();
        contact.addProperty("uuid",entity.getUUID().toString());
        contact.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        contact.addProperty("world",entity.level().dimension().identifier().toString());
        contact.addProperty("block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        contact.addProperty("x",pos.getX());contact.addProperty("y",pos.getY());contact.addProperty("z",pos.getZ());
        synchronized(noEntryContactEvents) {
            noEntryContactEvents.addLast(contact);
            while(noEntryContactEvents.size()>256)noEntryContactEvents.removeFirst();
        }
        LoggerFactory.getLogger("Stage7RepairProbe").info("STAGE7_NO_ENTRY_CONTACT {}",contact);
    }
    public static void observeGatewayContact(Entity entity,net.minecraft.core.BlockPos pos) {
        if(!entity.entityTags().contains("stage7_gateway_pig"))return;
        JsonObject contact=new JsonObject();
        contact.addProperty("uuid",entity.getUUID().toString());
        contact.addProperty("world",entity.level().dimension().identifier().toString());
        contact.addProperty("x",pos.getX());contact.addProperty("y",pos.getY());contact.addProperty("z",pos.getZ());
        contact.addProperty("ownerWorld",dev.phoenium.ownership.OwnershipContext.current()
            .map(context->context.region().world().value()).orElse("NONE"));
        contact.addProperty("ownerContext",dev.phoenium.ownership.OwnershipContext.current()
            .map(Object::toString).orElse("NONE"));
        synchronized(gatewayContactEvents) {
            gatewayContactEvents.addLast(contact.deepCopy());
            while(gatewayContactEvents.size()>4)gatewayContactEvents.removeFirst();
        }
        LoggerFactory.getLogger("Stage7RepairProbe").info("STAGE7_GATEWAY_CONTACT {}",contact);
    }
    /** After the exact native PORTAL ticket call returns in Entity.placePortalTicket. */
    public static void observeGatewayTicket(Entity entity,net.minecraft.server.level.TicketType type,
                                            net.minecraft.world.level.ChunkPos chunk,int radius) {
        if(type!=net.minecraft.server.level.TicketType.PORTAL
                || !entity.entityTags().contains("stage7_gateway_pig"))return;
        JsonObject ticket=new JsonObject();
        ticket.addProperty("uuid",entity.getUUID().toString());
        ticket.addProperty("world",entity.level().dimension().identifier().toString());
        ticket.addProperty("chunkX",chunk.x());ticket.addProperty("chunkZ",chunk.z());
        ticket.addProperty("radius",radius);ticket.addProperty("ticketType","minecraft:portal");
        ticket.addProperty("ownerWorld",dev.phoenium.ownership.OwnershipContext.current()
            .map(context->context.region().world().value()).orElse("NONE"));
        ticket.addProperty("ownerContext",dev.phoenium.ownership.OwnershipContext.current()
            .map(Object::toString).orElse("NONE"));
        synchronized(gatewayTicketEvents) {
            gatewayTicketEvents.addLast(ticket.deepCopy());
            while(gatewayTicketEvents.size()>4)gatewayTicketEvents.removeFirst();
        }
        LoggerFactory.getLogger("Stage7RepairProbe").info("STAGE7_GATEWAY_TICKET {}",ticket);
    }
    public static JsonObject snapshot(MinecraftServer server) {
        JsonObject j=new JsonObject();JsonArray entities=new JsonArray(),players=new JsonArray();
        for(var level:server.getAllLevels())for(Entity e:level.getAllEntities())if(relevant(e))entities.add(entity(e));
        for(var player:server.getPlayerList().getPlayers())players.add(entity(player));
        JsonArray chests=new JsonArray();var nether=server.getLevel(net.minecraft.world.level.Level.NETHER);
        for(int x:new int[]{15,16,20}) {
            var pos=new net.minecraft.core.BlockPos(x,33,0);
            if(!nether.getChunkSource().hasChunk(x>>4,0))continue;
            var state=nether.getBlockState(pos);JsonObject chest=new JsonObject();chest.addProperty("position",x+",33,0");chest.addProperty("state",state.toString());
            chest.addProperty("chunk",(x>>4)+",0");int iron=0;
            if(nether.getBlockEntity(pos) instanceof net.minecraft.world.Container container) {
                for(int slot=0;slot<container.getContainerSize();slot++)if(container.getItem(slot).is(Items.IRON_INGOT))iron+=container.getItem(slot).getCount();
                if(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock block){
                    var combined=net.minecraft.world.level.block.ChestBlock.getContainer(block,state,nether,pos,true);
                    chest.addProperty("combinedSize",combined==null?0:combined.getContainerSize());
                }
            }
            chest.addProperty("iron",iron);chests.add(chest);
        }
        JsonObject platform=new JsonObject();var end=server.getLevel(net.minecraft.world.level.Level.END);
        var spawn=net.minecraft.server.level.ServerLevel.END_SPAWN_POINT;
        var origin=spawn.below();int minX=(origin.getX()-2)>>4,maxX=(origin.getX()+2)>>4;
        int minZ=(origin.getZ()-2)>>4,maxZ=(origin.getZ()+2)>>4;
        boolean ready=end!=null;
        if(ready)for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)
            ready &= end.getChunkSource().getChunkNow(x,z)!=null;
        platform.addProperty("ready",ready);
        platform.addProperty("origin",origin.toShortString());
        if(ready) {
            int foundation=0,air=0,other=0;
            for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)for(int dy=-1;dy<3;dy++) {
                var state=end.getBlockState(origin.offset(dx,dy,dz));
                if(dy==-1&&state.is(net.minecraft.world.level.block.Blocks.OBSIDIAN))foundation++;
                else if(dy>=0&&state.isAir())air++;
                else other++;
            }
            platform.addProperty("obsidianFoundation",foundation);
            platform.addProperty("airClearance",air);platform.addProperty("other",other);
        }
        JsonArray recentFalling=new JsonArray();
        synchronized(fallingEvents) {for(var event:fallingEvents)recentFalling.add(event.deepCopy());}
        j.add("fallingEvents",recentFalling);
        JsonArray recentProjectiles=new JsonArray();
        synchronized(projectileEvents) {for(var event:projectileEvents)recentProjectiles.add(event.deepCopy());}
        j.add("projectileEvents",recentProjectiles);
        JsonArray recentImpacts=new JsonArray();
        synchronized(projectileImpactEvents) {for(var event:projectileImpactEvents)recentImpacts.add(event.deepCopy());}
        j.add("projectileImpactEvents",recentImpacts);
        JsonArray recentNoEntryContacts=new JsonArray();
        synchronized(noEntryContactEvents) {for(var event:noEntryContactEvents)recentNoEntryContacts.add(event.deepCopy());}
        j.add("noEntryContactEvents",recentNoEntryContacts);
        JsonArray recentGatewayContacts=new JsonArray(),recentGatewayTickets=new JsonArray(),recentGatewayTracking=new JsonArray();
        synchronized(gatewayContactEvents) {for(var event:gatewayContactEvents)recentGatewayContacts.add(event.deepCopy());}
        synchronized(gatewayTicketEvents) {for(var event:gatewayTicketEvents)recentGatewayTickets.add(event.deepCopy());}
        synchronized(gatewayTrackingEvents) {for(var event:gatewayTrackingEvents)recentGatewayTracking.add(event.deepCopy());}
        j.add("gatewayContactEvents",recentGatewayContacts);
        j.add("gatewayTicketEvents",recentGatewayTickets);
        j.add("gatewayTrackingEvents",recentGatewayTracking);
        JsonArray respawnBlocks=new JsonArray();
        for(var dimension:java.util.List.of(net.minecraft.world.level.Level.OVERWORLD,net.minecraft.world.level.Level.NETHER)) {
            var scene=server.getLevel(dimension);
            if(scene==null)continue;
            for(int z:new int[]{9,10,11}) {
                var pos=new net.minecraft.core.BlockPos(8,65,z);
                if(scene.getChunkSource().getChunkNow(0,0)==null)continue;
                var state=scene.getBlockState(pos);
                JsonObject block=new JsonObject();
                block.addProperty("world",scene.dimension().identifier().toString());
                block.addProperty("position",pos.toShortString());
                block.addProperty("block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                block.addProperty("state",state.toString());
                if(state.hasProperty(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE))
                    block.addProperty("charge",state.getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE));
                respawnBlocks.add(block);
            }
        }
        j.add("respawnBlocks",respawnBlocks);
        j.add("endPlatform",platform);j.add("chests",chests);j.add("entities",entities);j.add("players",players);
        j.addProperty("caneAdds",caneAdds.get());j.addProperty("caneRemoves",caneRemoves.get());return j;
    }
    @Override public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher,access,selection)->dispatcher.register(
            Commands.literal("stage7-probe").then(Commands.literal("snapshot").executes(context->{
                String value=snapshot(context.getSource().getServer()).toString();
                context.getSource().sendSuccess(()->Component.literal(value),false);return 1;
            })).then(Commands.literal("graph")
                .then(Commands.argument("root",com.mojang.brigadier.arguments.StringArgumentType.word())
                    .then(Commands.argument("child",com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(context->{
                            java.util.UUID root=java.util.UUID.fromString(com.mojang.brigadier.arguments.StringArgumentType.getString(context,"root"));
                            java.util.UUID child=java.util.UUID.fromString(com.mojang.brigadier.arguments.StringArgumentType.getString(context,"child"));
                            String value=PortalGraphProbe.snapshot(context.getSource().getServer(),root,child).toString();
                            context.getSource().sendSuccess(()->Component.literal(value),false);return 1;
                        }))))));
    }
}
