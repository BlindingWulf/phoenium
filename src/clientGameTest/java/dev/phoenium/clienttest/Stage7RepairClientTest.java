package dev.phoenium.clienttest;

import com.google.gson.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Installed Stage 7 cards. Commands prepare scenes/read state; client input causes the witnesses. */
public final class Stage7RepairClientTest implements FabricClientGameTest {
    static final String USER="PhoeniumTest0",OW="minecraft:overworld",NETHER="minecraft:the_nether",END="minecraft:the_end";
    final Path evidence=Path.of(System.getProperty("phoenium.repair.evidence"));
    final JsonObject receipt=new JsonObject();final JsonArray cards=new JsonArray(),observations=new JsonArray();
    ClientGameTestContext context;
    String active="CONNECT";
    @Override public void runTest(ClientGameTestContext ctx) {
        context=ctx;receipt.add("cards",cards);receipt.add("observations",observations);
        receipt.addProperty("username",USER);receipt.addProperty("facility","Fabric Client GameTest over TCP, separate dedicated server");
        receipt.addProperty("humanActions",false);receipt.addProperty("candidateSha256",System.getProperty("phoenium.repair.sha256"));
        String group=System.getProperty("phoenium.repair.group","");receipt.addProperty("group",group);
        String selectedCards=System.getProperty("phoenium.repair.cards","");
        receipt.addProperty("selectedCards",selectedCards);
        try {
            if(!selectedCards.isBlank() && !(group.equals("projectile-fireball")
                    && selectedCards.equals("P0-fireball-nether-return"))
                    && !(group.equals("arrow-trident")
                    && selectedCards.matches("P2-(arrow|spectral_arrow|trident)-(NETHER_ENTRY|NETHER_RETURN)"))
                    && !(group.equals("throwable-projectiles")
                    && selectedCards.equals("P3-experience_bottle-NETHER_RETURN"))
                    && !(group.equals("passenger-graph")
                    && selectedCards.equals("G0-pig-chicken-NETHER_ENTRY"))
                    && !(group.equals("vehicle-empty")
                    && selectedCards.matches("V0-(acacia_boat|birch_boat|cherry_boat|dark_oak_boat|jungle_boat|mangrove_boat|oak_boat|pale_oak_boat|spruce_boat|bamboo_raft|minecart)-(NETHER_ENTRY|NETHER_RETURN)"))
                    && !(group.equals("vehicle-chest")
                    && selectedCards.matches("V1-(acacia_chest_boat|birch_chest_boat|cherry_chest_boat|dark_oak_chest_boat|jungle_chest_boat|mangrove_chest_boat|oak_chest_boat|pale_oak_chest_boat|spruce_chest_boat|bamboo_chest_raft)-items-(NETHER_ENTRY|NETHER_RETURN)|V1-(oak_chest_boat|bamboo_chest_raft)-loot-(NETHER_ENTRY|NETHER_RETURN)"))
                    && !(group.equals("vehicle-minecart-chest")
                    && selectedCards.matches("V2-chest_minecart-(items|loot)-(NETHER_ENTRY|NETHER_RETURN)"))
                    && !(group.equals("item-end-platform")
                    && selectedCards.equals("I0-item-END_ENTRY"))
                    && !(group.equals("same-dimension-gateway")
                    && selectedCards.equals("G1-pig-END_GATEWAY"))
                    && !(group.equals("graph-probe")
                    && selectedCards.matches("GP-[a-z_]+-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT)(,GP-[a-z_]+-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT))*"))
                    && !(group.equals("entity-probe")
                    && selectedCards.matches("E0-[a-z_]+-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT)(,E0-[a-z_]+-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT))*"))
                    && !(group.equals("mob-census")
                    && selectedCards.matches("M3-[a-z_]+-(NETHER_ENTRY|NETHER_RETURN)(,M3-[a-z_]+-(NETHER_ENTRY|NETHER_RETURN))*"))
                    && !(group.equals("same-dimension-gateway-natural-item")
                    && selectedCards.equals("G4-item-END_GATEWAY-NATURAL"))
                    && !(group.equals("same-dimension-gateway-natural")
                    && selectedCards.equals("G3-pig-END_GATEWAY-NATURAL"))
                    && !(group.equals("same-dimension-gateway-ab")
                    && selectedCards.equals("G2-pig-END_GATEWAY-AB"))
                    && !(group.equals("mob-prefix")
                    && selectedCards.equals("M2-silverfish-NETHER_ENTRY"))
                    && !(group.equals("mob-prefix")
                    && selectedCards.equals("M2-silverfish-NETHER_RETURN"))
                    && !(group.equals("no-native-entry")
                    && selectedCards.matches("N0-(block_display|ender_dragon|glow_item_frame|interaction|item_display|item_frame|marker|painting|text_display|fishing_bobber|wither)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")))
                throw new IllegalArgumentException("unbound exact card selection: "+group+"/"+selectedCards);
            connect();
            if(group.equals("end-falling")){fallingEndCards();}
            else if(group.equals("projectile-fireball")){fireballNetherReturnCard();}
            else if(group.equals("hurting-projectiles")){hurtingProjectileMatrix();}
            else if(group.equals("arrow-trident")){arrowTridentMatrix(selectedCards);}
            else if(group.equals("throwable-projectiles")){throwableProjectileMatrix(selectedCards);}
            else if(group.equals("passenger-graph")){pigChickenGraphCard();}
            else if(group.equals("vehicle-empty")){emptyVehicleMatrix(selectedCards);}
            else if(group.equals("vehicle-chest")){chestVehicleMatrix(selectedCards);}
            else if(group.equals("vehicle-minecart-chest")){chestMinecartMatrix(selectedCards);}
            else if(group.equals("item-end-platform")){itemEndPlatformScopeCard();}
            else if(group.equals("mob-census")){mobCensus(selectedCards);}
            else if(group.equals("entity-probe")){entityProbe(selectedCards);}
            else if(group.equals("graph-probe")){graphProbe(selectedCards);}
            else if(group.equals("same-dimension-gateway")){pigEndGatewayCard(false);}
            else if(group.equals("same-dimension-gateway-ab")){pigEndGatewayCard(true);}
            else if(group.equals("same-dimension-gateway-natural")){naturalGatewayCard();}
            else if(group.equals("same-dimension-gateway-natural-item")){naturalGatewayItemCard();}
            else if(group.equals("mob-prefix")){
                cmd("gamemode creative "+USER);
                if(selectedCards.isBlank()||selectedCards.equals("M2-silverfish-NETHER_ENTRY"))
                    mobPortalCase("silverfish",OW,1,0);
                if(selectedCards.isBlank()||selectedCards.equals("M2-silverfish-NETHER_RETURN"))
                    mobPortalCase("silverfish",NETHER,1,1);
            }
            else if(group.equals("no-native-entry")){noNativeEntryMatrix(selectedCards);}
            else if(group.equals("selection-holes")){
                cmd("gamemode creative "+USER);
                int sequence=0;
                for(String type:List.of("skeleton","horse","donkey","mule","skeleton_horse",
                        "zombie_horse","llama","trader_llama"))
                    for(String from:List.of(OW,NETHER))mobPortalCase(type,from,1,sequence++);
            }
            else if(group.equals("merchant-holes")){
                cmd("gamemode creative "+USER);
                int sequence=0;
                for(String type:List.of("wandering_trader","villager"))
                    for(String from:List.of(OW,NETHER))mobPortalCase(type,from,1,sequence++);
            }
            else if(group.equals("armor-stand")){
                cmd("gamemode creative "+USER);
                decorativeLivingCase("armor_stand",OW,false,0);
                decorativeLivingCase("armor_stand",NETHER,true,1);
                decorativeLivingCase("mannequin",OW,false,2);
                decorativeLivingCase("mannequin",NETHER,false,3);
            }
            else if(group.equals("bed-anchor")){
                if(Boolean.getBoolean("phoenium.repair.restart"))bedAnchorRestartCards();
                else bedAnchorFirstCards();
            }
            else if(Boolean.getBoolean("phoenium.repair.restart")){restartCard();}
            else {mobPortals();containers();baseline();playerPortals();itemCard(OW,NETHER,3,0);itemCard(NETHER,OW,4,1);
                itemCard(OW,NETHER,2,2);itemCard(NETHER,OW,5,3);localPearl();pearlCard(OW,NETHER);pearlCard(NETHER,OW);coldDestination();respawns();
                cmd("execute in "+OW+" run setblock 8 65 8 minecraft:diamond_block");
                cmd("item replace entity "+USER+" hotbar.0 with minecraft:sugar_cane 11");
                context.waitTicks(10);checkIron("before-save-counts",60);snapshot("before-save");cmd("save-all flush");}
            health("final");disconnect();receipt.addProperty("pass",true);
        } catch(Throwable failure){receipt.addProperty("pass",false);receipt.addProperty("failedCard",active);
            receipt.addProperty("failure",failure.toString());try{snapshot("failure");}catch(Throwable ignored){}
            throw new AssertionError("Stage7 card "+active+" failed",failure);
        } finally {try{if(context.computeOnClient(c->c.player!=null))disconnect();}catch(Throwable ignored){}
            try{Files.createDirectories(evidence.getParent());Files.writeString(evidence,new GsonBuilder().setPrettyPrinting().create().toJson(receipt));}
            catch(IOException failure){throw new UncheckedIOException(failure);}}
    }
    void mobPortals()throws Exception {
        for(String world:List.of(OW,NETHER)) {
            cmd("execute in "+world+" run fill 0 64 7 10 73 11 minecraft:glass hollow");
            cmd("execute in "+world+" run fill 1 65 8 9 72 10 minecraft:air");
        }
        int sequence=0;
        for(String type:List.of("zombified_piglin","chicken","pig","enderman","magma_cube","slime"))
            for(String from:List.of(OW,NETHER)) mobPortalCase(type,from,1,sequence++);
        for(int size:List.of(2,4)) {
            mobPortalCase("magma_cube",NETHER,size,sequence++);
            mobPortalCase("slime",OW,size,sequence++);
        }
    }
    void fallingEndCards()throws Exception {
        active="F0-end-source-setup";
        position(OW,8.5,66,5.5,180,0);
        cmd("gamemode creative "+USER);
        for(String c:List.of("fill -3 64 -3 3 64 3 minecraft:obsidian",
                "fill -2 65 -2 2 76 2 minecraft:air",
                "setblock 0 65 0 minecraft:end_portal"))
            cmd("execute in "+OW+" run "+c);
        require(cmd("execute in "+OW+" run execute if block 0 65 0 minecraft:end_portal").contains("Test passed"),
            "source End portal block did not exist");
        JsonObject initial=probe();
        receipt.add("endPlatformInitially",initial.getAsJsonObject("endPlatform").deepCopy());
        String lastLandingBlock="";
        for(String material:List.of("sand","gravel","anvil")) {
            active="F1-"+material+"-native-fall-end-entry";
            cmd("execute in "+OW+" run fill 0 66 0 0 76 0 minecraft:air");
            cmd("execute in "+OW+" run setblock 0 65 0 minecraft:end_portal");
            cmd("execute in "+OW+" run setblock 0 72 0 minecraft:stone");
            cmd("execute in "+OW+" run setblock 0 73 0 minecraft:"+material);
            String beforeCellCommand="execute in "+OW+" run execute if block 0 73 0 minecraft:"+material;
            String beforeCellResponse=cmd(beforeCellCommand);
            require(beforeCellResponse.contains("Test passed"),
                "falling block support setup absent");
            snapshot(active+"-before-removal");
            cmd("execute in "+OW+" run setblock 0 72 0 minecraft:air");
            JsonObject source=null;
            for(int i=0;i<30;i++){context.waitTick();
                var seen=falling(probe(),null,material);
                if(seen!=null&&seen.get("world").getAsString().equals(OW)){source=seen;break;}}
            require(source!=null,"ordinary support removal did not create a native falling "+material+" entity");
            String uuid=source.get("uuid").getAsString();
            receipt.add(active+"-source",source.deepCopy());
            String afterCellCommand="execute in "+OW+" run execute if block 0 73 0 minecraft:air";
            String afterCellResponse=cmd(afterCellCommand);
            require(afterCellResponse.contains("Test passed"),
                "former "+material+" source cell still occupied after native fall");
            JsonObject sourceCell=new JsonObject();
            sourceCell.addProperty("world",OW);
            sourceCell.addProperty("position","0 73 0");
            sourceCell.addProperty("material","minecraft:"+material);
            sourceCell.addProperty("sourceUuid",uuid);
            sourceCell.addProperty("sourceObject",source.get("object").getAsString());
            sourceCell.addProperty("sourceFallingState",source.get("fallingState").getAsString());
            sourceCell.addProperty("beforeCommand",beforeCellCommand);
            sourceCell.addProperty("beforeResponse",beforeCellResponse);
            sourceCell.addProperty("afterCommand",afterCellCommand);
            sourceCell.addProperty("afterResponse",afterCellResponse);
            receipt.add(active+"-source-cell",sourceCell);
            snapshot(active+"-source-cell-after");
            JsonObject arrival=null;
            for(int i=0;i<350;i++){context.waitTick();
                var seen=fallingArrival(probe(),uuid,material);
                if(seen!=null){arrival=seen;break;}}
            require(arrival!=null,"native falling block End transfer/tracking not observed: "+material+" "+uuid);
            require(!arrival.get("object").equals(source.get("object")),"End falling block did not use native replacement identity");
            receipt.add(active+"-arrival",arrival.deepCopy());
            snapshot(active+"-arrival");
            context.waitTicks(30);
            JsonObject after=snapshot(active+"-settled");
            JsonObject platform=after.getAsJsonObject("endPlatform");
            require(platform.get("ready").getAsBoolean()&&platform.get("obsidianFoundation").getAsInt()==25,
                "End platform foundation missing after native entry");
            JsonObject landed=fallingLandingEvent(after,uuid);
            require(landed!=null,"native End falling-block landing/removal was not observed");
            String landedState=landed.get("fallingState").getAsString();
            String expectedBlock=landedState.substring(landedState.indexOf('{')+1,landedState.indexOf('}'));
            require(expectedBlock.equals("minecraft:"+material)
                    || material.equals("anvil")&&expectedBlock.equals("minecraft:chipped_anvil"),
                "unexpected native falling-block conversion: "+material+" to "+expectedBlock);
            require(cmd("execute in "+END+" run execute if block 100 49 0 "+expectedBlock).contains("Test passed"),
                "falling "+material+" did not continue into its native End landing state "+expectedBlock);
            lastLandingBlock=expectedBlock;
            receipt.add(active+"-landing",landed.deepCopy());
            pass(active,"support removal, portal contact, cross-dimension replacement, platform and native landing; uuid="+uuid);
        }
        active="F2-java-client-end-view";
        position(END,103.5,50,2.5,180,0);
        final String clientExpectedBlock=lastLandingBlock;
        require(context.computeOnClient(c->net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
            c.level.getBlockState(new BlockPos(100,49,0)).getBlock()).toString().equals(clientExpectedBlock)),
            "connected Java client cannot see final native End landing block");
        health(active);pass(active,"normal TCP Java client observed the final End block and remained usable");
    }
    void pigChickenGraphCard()throws Exception {
        active="G0-pig-chicken-NETHER_ENTRY";
        cmd("gamemode creative "+USER);
        position(OW,8.5,65,5.5,180,0);
        String pigTag="stage7_graph_pig", chickenTag="stage7_graph_chicken";
        cmd("execute in "+OW+" run summon minecraft:pig 0.5 65 2.5 "
            +"{Tags:[\"stage7_mob\",\""+pigTag+"\"],PersistenceRequired:1b}");
        cmd("execute in "+OW+" run summon minecraft:chicken 0.5 65 2.5 "
            +"{Tags:[\"stage7_mob\",\""+chickenTag+"\"],PersistenceRequired:1b}");
        cmd("execute in "+OW+" run ride @e[tag="+chickenTag+",limit=1] mount @e[tag="+pigTag+",limit=1]");
        JsonObject source=snapshot(active+"-source");
        JsonObject pig=taggedMob(source,pigTag),chicken=taggedMob(source,chickenTag);
        String pigUuid=pig.get("uuid").getAsString(),chickenUuid=chicken.get("uuid").getAsString();
        require(pig.get("world").getAsString().equals(OW)&&chicken.get("world").getAsString().equals(OW),
            "graph source members began outside Overworld");
        require(chicken.get("vehicleUuid").getAsString().equals(pigUuid)
            &&pig.getAsJsonArray("passengerUuids").size()==1
            &&pig.getAsJsonArray("passengerUuids").get(0).getAsString().equals(chickenUuid),
            "source chicken did not ride the exact pig UUID");
        receipt.add(active+"-sourcePig",pig.deepCopy());receipt.add(active+"-sourceChicken",chicken.deepCopy());
        cmd("execute in "+OW+" run data merge entity @e[tag="+pigTag+",limit=1] "
            +"{Motion:[0.0d,0.0d,-0.8d]}");
        JsonObject arrived=null, arrivedPig=null, arrivedChicken=null;
        for(int i=0;i<180;i++) {
            context.waitTick();JsonObject state=snapshot(active+"-wait");
            JsonObject currentPig=taggedMob(state,pigTag),currentChicken=taggedMob(state,chickenTag);
            if(currentPig.get("world").getAsString().equals(NETHER)
                    &&currentChicken.get("world").getAsString().equals(NETHER)) {
                arrived=state;arrivedPig=currentPig;arrivedChicken=currentChicken;break;
            }
        }
        require(arrived!=null,"native pig/chicken graph Nether transfer missing");
        require(arrivedPig.get("uuid").getAsString().equals(pigUuid)
            &&arrivedChicken.get("uuid").getAsString().equals(chickenUuid)
            &&!arrivedPig.get("object").equals(pig.get("object"))
            &&!arrivedChicken.get("object").equals(chicken.get("object")),
            "graph member UUID or native replacement identity changed");
        require(arrivedChicken.get("vehicleUuid").getAsString().equals(pigUuid)
            &&arrivedPig.getAsJsonArray("passengerUuids").size()==1
            &&arrivedPig.getAsJsonArray("passengerUuids").get(0).getAsString().equals(chickenUuid),
            "destination graph edge or passenger order changed");
        for(String uuid:List.of(pigUuid,chickenUuid)) {
            int count=0;
            for(JsonElement element:arrived.getAsJsonArray("entities"))
                if(element.getAsJsonObject().get("uuid").getAsString().equals(uuid))count++;
            require(count==1,"graph UUID was duplicated or absent after source retirement: "+uuid);
        }
        receipt.add(active+"-arrivalPig",arrivedPig.deepCopy());
        receipt.add(active+"-arrivalChicken",arrivedChicken.deepCopy());
        position(NETHER,8.5,65,5.5,180,0);
        JsonObject later=null,laterPig=null,laterChicken=null;
        for(int i=0;i<100;i++) {
            context.waitTicks(2);later=snapshot(active+"-later");
            laterPig=taggedMob(later,pigTag);laterChicken=taggedMob(later,chickenTag);
            if(laterPig.get("ticks").getAsInt()>arrivedPig.get("ticks").getAsInt()+20
                    &&laterChicken.get("ticks").getAsInt()>arrivedChicken.get("ticks").getAsInt()+20)break;
        }
        require(laterPig.get("ticks").getAsInt()>arrivedPig.get("ticks").getAsInt()+20
            &&laterChicken.get("ticks").getAsInt()>arrivedChicken.get("ticks").getAsInt()+20,
            "destination graph members did not advance 20 native server ticks within 200 client ticks: pig="
                +laterPig.get("ticks")+" chicken="+laterChicken.get("ticks"));
        require(laterChicken.get("vehicleUuid").getAsString().equals(pigUuid)
            &&laterPig.getAsJsonArray("passengerUuids").size()==1
            &&laterPig.getAsJsonArray("passengerUuids").get(0).getAsString().equals(chickenUuid),
            "graph edge did not persist after destination ticks");
        require(context.computeOnClient(c->{boolean sawPig=false,sawChicken=false;
            for(var entity:c.level.entitiesForRendering()) {
                if(entity.getUUID().toString().equals(pigUuid))sawPig=true;
                if(entity.getUUID().toString().equals(chickenUuid))sawChicken=true;
            }
            return sawPig&&sawChicken;
        }),"connected Java client did not track both graph members");
        health(active);pass(active,"native child-first replacement, two UUIDs, ordered pig/chicken edge, later ticks and client tracking");
    }
    void emptyVehicleMatrix(String selectedCards)throws Exception {
        cmd("gamemode creative "+USER);
        int exercised=0;
        for(String route:List.of("NETHER_ENTRY","NETHER_RETURN"))
            for(String type:List.of("acacia_boat","birch_boat","cherry_boat","dark_oak_boat",
                    "jungle_boat","mangrove_boat","oak_boat","pale_oak_boat",
                    "spruce_boat","bamboo_raft","minecart")) {
                String card="V0-"+type+"-"+route;
                if(!selectedCards.isBlank()&&!selectedCards.equals(card))continue;
                emptyVehicleCase(type,route);exercised++;
            }
        require(exercised==(selectedCards.isBlank()?22:1),
            "empty vehicle selection did not exercise planned cards: "+selectedCards+"/"+exercised);
    }
    void emptyVehicleCase(String type,String route)throws Exception {
        active="V0-"+type+"-"+route;
        String from=route.equals("NETHER_ENTRY")?OW:NETHER;
        String to=route.equals("NETHER_ENTRY")?NETHER:OW;
        position(from,8.5,65,5.5,180,0);
        String tag="stage7_vehicle_"+type+"_"+route.toLowerCase(Locale.ROOT);
        require(cmd("execute in "+from+" run execute if block 0 66 0 minecraft:nether_portal")
            .contains("Test passed"),"empty vehicle source lacks exact three-wide portal");
        String summon=cmd("execute in "+from+" run summon minecraft:"+type+" 0.5 65.2 3.5 "
            +"{Tags:[\""+tag+"\"],NoGravity:1b,Motion:[0.0d,0.0d,0.0d]}");
        require(summon.contains("Summoned")||summon.contains("summoned"),
            "native empty vehicle summon failed: "+summon);
        JsonObject source=null;
        for(int i=0;i<30;i++) {
            JsonObject state=snapshot(active+"-source-poll");
            for(JsonElement element:state.getAsJsonArray("entities")) {
                JsonObject entity=element.getAsJsonObject();
                if(entity.has("tags")&&entity.get("tags").getAsString().contains(tag)) {
                    source=taggedMob(state,tag);
                    break;
                }
            }
            if(source!=null)break;
            context.waitTick();
        }
        require(source!=null,"native empty vehicle source absent after bounded admission: "+active);
        String uuid=source.get("uuid").getAsString();
        require(source.get("type").getAsString().equals("minecraft:"+type)
            &&source.get("world").getAsString().equals(from)
            &&source.get("z").getAsDouble()>3.0
            &&source.getAsJsonArray("passengerUuids").size()==0
            &&source.get("vehicleUuid").getAsString().equals("UNAVAILABLE"),
            "empty vehicle source type/world/graph wrong: "+source);
        receipt.add(active+"-source",source.deepCopy());
        String moved=cmd("execute in "+from+" run tp @e[tag="+tag+",limit=1] 0.5 65.2 0.5");
        require(moved.contains("Teleported")||moved.contains("teleported"),
            "empty vehicle could not be placed in exact portal block: "+moved);
        JsonObject arrival=null;
        for(int i=0;i<180;i++) {
            context.waitTick();
            JsonObject seen=taggedMob(snapshot(active+"-wait"),tag);
            if(seen.get("world").getAsString().equals(to)){arrival=seen;break;}
        }
        require(arrival!=null,"native empty vehicle transfer absent: "+active+" "+uuid);
        require(arrival.get("uuid").getAsString().equals(uuid)
            &&arrival.get("type").getAsString().equals("minecraft:"+type)
            &&!arrival.get("object").equals(source.get("object"))
            &&arrival.getAsJsonArray("passengerUuids").size()==0
            &&arrival.get("vehicleUuid").getAsString().equals("UNAVAILABLE"),
            "empty vehicle replacement identity or graph changed: "+arrival);
        receipt.add(active+"-arrival",arrival.deepCopy());
        JsonObject scoped=snapshot(active+"-scoped");int matches=0;
        for(JsonElement element:scoped.getAsJsonArray("entities"))
            if(element.getAsJsonObject().get("uuid").getAsString().equals(uuid))matches++;
        require(matches==1,"empty vehicle source/destination UUID was lost or duplicated: "+matches);
        position(to,8.5,65,5.5,180,0);
        JsonObject later=null;
        for(int i=0;i<100;i++) {
            context.waitTicks(2);later=taggedMob(snapshot(active+"-later"),tag);
            if(later.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()+20)break;
        }
        require(later.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()+20
            &&later.get("world").getAsString().equals(to),
            "empty vehicle did not continue native destination ticks");
        require(context.computeOnClient(c->{for(var entity:c.level.entitiesForRendering())
            if(entity.getUUID().toString().equals(uuid))return true;return false;}),
            "connected Java client did not track empty vehicle");
        health(active);pass(active,"three-wide portal; source retirement; one destination UUID/new object; empty graph, later ticks and client tracking");
    }
    void chestVehicleMatrix(String selectedCards)throws Exception {
        cmd("gamemode creative "+USER);
        int exercised=0;
        for(String route:List.of("NETHER_ENTRY","NETHER_RETURN"))
            for(String type:List.of("acacia_chest_boat","birch_chest_boat","cherry_chest_boat",
                    "dark_oak_chest_boat","jungle_chest_boat","mangrove_chest_boat",
                    "oak_chest_boat","pale_oak_chest_boat","spruce_chest_boat","bamboo_chest_raft"))
                for(String storage:(type.equals("oak_chest_boat")||type.equals("bamboo_chest_raft"))
                        ?List.of("items","loot"):List.of("items")) {
                    String card="V1-"+type+"-"+storage+"-"+route;
                    if(!selectedCards.isBlank()&&!selectedCards.equals(card))continue;
                    chestVehicleCase(type,storage,route);exercised++;
                }
        require(exercised==(selectedCards.isBlank()?24:1),
            "chest vehicle selection did not exercise planned cards: "+selectedCards+"/"+exercised);
    }
    void requireChestStorage(JsonObject entity,String storage) {
        JsonArray items=entity.getAsJsonArray("vehicleItems");
        require(items!=null&&entity.has("vehicleLootTable")&&entity.has("vehicleLootSeed")
            &&entity.has("vehicleSlotCount")&&entity.get("vehicleSlotCount").getAsInt()==27,
            "chest vehicle observer omitted raw storage: "+entity);
        if(storage.equals("items")) {
            require(entity.get("vehicleLootTable").getAsString().equals("UNAVAILABLE")
                &&items.size()==2
                &&items.get(0).getAsJsonObject().get("slot").getAsInt()==0
                &&items.get(0).getAsJsonObject().get("item").getAsString().equals("minecraft:diamond")
                &&items.get(0).getAsJsonObject().get("count").getAsInt()==7
                &&items.get(1).getAsJsonObject().get("slot").getAsInt()==26
                &&items.get(1).getAsJsonObject().get("item").getAsString().equals("minecraft:sugar_cane")
                &&items.get(1).getAsJsonObject().get("count").getAsInt()==3,
                "chest vehicle slot contents changed: "+entity);
        } else {
            require(storage.equals("loot")&&items.size()==0
                &&entity.get("vehicleLootTable").getAsString().equals("minecraft:chests/simple_dungeon")
                &&entity.get("vehicleLootSeed").getAsLong()==123456789L,
                "latent chest vehicle loot table or seed changed: "+entity);
        }
    }
    int looseVehicleItems(JsonObject state,String item) {
        int count=0;
        for(JsonElement element:state.getAsJsonArray("entities")) {
            JsonObject entity=element.getAsJsonObject();
            if(entity.get("type").getAsString().equals("minecraft:item")
                    &&entity.has("item")&&entity.get("item").getAsString().equals(item))
                count+=entity.get("count").getAsInt();
        }
        return count;
    }
    void chestVehicleCase(String type,String storage,String route)throws Exception {
        active="V1-"+type+"-"+storage+"-"+route;
        String from=route.equals("NETHER_ENTRY")?OW:NETHER;
        String to=route.equals("NETHER_ENTRY")?NETHER:OW;
        position(from,8.5,65,5.5,180,0);
        String tag="stage7_vehicle_"+type+"_"+storage+"_"+route.toLowerCase(Locale.ROOT);
        require(cmd("execute in "+from+" run execute if block 0 66 0 minecraft:nether_portal")
            .contains("Test passed"),"chest vehicle source lacks exact three-wide portal");
        String extra=storage.equals("loot")
            ?",LootTable:\"minecraft:chests/simple_dungeon\",LootTableSeed:123456789L":"";
        String summon=cmd("execute in "+from+" run summon minecraft:"+type+" 0.5 65.2 3.5 "
            +"{Tags:[\""+tag+"\"],NoGravity:1b,Motion:[0.0d,0.0d,0.0d]"+extra+"}");
        require(summon.contains("Summoned")||summon.contains("summoned"),
            "native chest vehicle summon failed: "+summon);
        JsonObject admitted=null;
        for(int i=0;i<30;i++) {
            JsonObject state=snapshot(active+"-source-poll");
            for(JsonElement element:state.getAsJsonArray("entities")) {
                JsonObject entity=element.getAsJsonObject();
                if(entity.has("tags")&&entity.get("tags").getAsString().contains(tag)) {
                    admitted=taggedMob(state,tag);break;
                }
            }
            if(admitted!=null)break;
            context.waitTick();
        }
        require(admitted!=null,"native chest vehicle source absent after bounded admission: "+active);
        if(storage.equals("items")) {
            cmd("execute in "+from+" run item replace entity @e[tag="+tag+",limit=1] container.0 with minecraft:diamond 7");
            cmd("execute in "+from+" run item replace entity @e[tag="+tag+",limit=1] container.26 with minecraft:sugar_cane 3");
        }
        JsonObject sourceState=snapshot(active+"-source");
        JsonObject source=taggedMob(sourceState,tag);
        String uuid=source.get("uuid").getAsString();
        require(source.get("type").getAsString().equals("minecraft:"+type)
            &&source.get("world").getAsString().equals(from)
            &&source.get("z").getAsDouble()>3.0
            &&source.getAsJsonArray("passengerUuids").size()==0
            &&source.get("vehicleUuid").getAsString().equals("UNAVAILABLE"),
            "chest vehicle source type/world/graph wrong: "+source);
        requireChestStorage(source,storage);
        int diamonds=looseVehicleItems(sourceState,"minecraft:diamond");
        int cane=looseVehicleItems(sourceState,"minecraft:sugar_cane");
        receipt.add(active+"-source",source.deepCopy());
        String moved=cmd("execute in "+from+" run tp @e[tag="+tag+",limit=1] 0.5 65.2 0.5");
        require(moved.contains("Teleported")||moved.contains("teleported"),
            "chest vehicle could not be placed in exact portal block: "+moved);
        JsonObject arrival=null,arrivalState=null;
        for(int i=0;i<180;i++) {
            context.waitTick();
            JsonObject seenState=snapshot(active+"-wait");
            JsonObject seen=taggedMob(seenState,tag);
            if(seen.get("world").getAsString().equals(to)){
                arrival=seen;arrivalState=seenState;break;
            }
        }
        require(arrival!=null,"native chest vehicle transfer absent: "+active+" "+uuid);
        require(arrival.get("uuid").getAsString().equals(uuid)
            &&arrival.get("type").getAsString().equals("minecraft:"+type)
            &&!arrival.get("object").equals(source.get("object"))
            &&arrival.getAsJsonArray("passengerUuids").size()==0
            &&arrival.get("vehicleUuid").getAsString().equals("UNAVAILABLE"),
            "chest vehicle replacement identity or graph changed: "+arrival);
        requireChestStorage(arrival,storage);
        int arrivalDiamonds=looseVehicleItems(arrivalState,"minecraft:diamond");
        int arrivalCane=looseVehicleItems(arrivalState,"minecraft:sugar_cane");
        require(arrivalDiamonds==diamonds&&arrivalCane==cane,
            "chest vehicle transfer dropped or duplicated stored items");
        receipt.add(active+"-arrival",arrival.deepCopy());
        JsonObject scoped=snapshot(active+"-scoped");int matches=0;
        for(JsonElement element:scoped.getAsJsonArray("entities"))
            if(element.getAsJsonObject().get("uuid").getAsString().equals(uuid))matches++;
        require(matches==1,"chest vehicle source/destination UUID was lost or duplicated: "+matches);
        position(to,8.5,65,5.5,180,0);
        JsonObject later=null,laterState=null;
        for(int i=0;i<100;i++) {
            context.waitTicks(2);laterState=snapshot(active+"-later");
            later=taggedMob(laterState,tag);
            if(later.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()+20)break;
        }
        require(later.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()+20
            &&later.get("world").getAsString().equals(to),
            "chest vehicle did not continue native destination ticks");
        requireChestStorage(later,storage);
        int laterDiamonds=looseVehicleItems(laterState,"minecraft:diamond");
        int laterCane=looseVehicleItems(laterState,"minecraft:sugar_cane");
        require(laterDiamonds==diamonds&&laterCane==cane,
            "chest vehicle later ticks dropped or duplicated stored items");
        require(context.computeOnClient(c->{for(var entity:c.level.entitiesForRendering())
            if(entity.getUUID().toString().equals(uuid))return true;return false;}),
            "connected Java client did not track chest vehicle");
        receipt.add(active+"-later",later.deepCopy());
        JsonObject loose=new JsonObject();
        loose.addProperty("sourceDiamonds",diamonds);loose.addProperty("arrivalDiamonds",arrivalDiamonds);
        loose.addProperty("laterDiamonds",laterDiamonds);loose.addProperty("sourceSugarCane",cane);
        loose.addProperty("arrivalSugarCane",arrivalCane);loose.addProperty("laterSugarCane",laterCane);
        receipt.add(active+"-looseItems",loose);
        health(active);pass(active,"three-wide portal; native inventory or latent loot persistence; no loose item duplication; one replacement UUID/new object; empty graph, later ticks and client tracking");
    }
    void chestMinecartMatrix(String selectedCards)throws Exception {
        cmd("gamemode creative "+USER);
        int exercised=0;
        for(String route:List.of("NETHER_ENTRY","NETHER_RETURN"))
            for(String storage:List.of("items","loot")) {
                String card="V2-chest_minecart-"+storage+"-"+route;
                if(!selectedCards.isBlank()&&!selectedCards.equals(card))continue;
                chestMinecartCase(storage,route);exercised++;
            }
        require(exercised==(selectedCards.isBlank()?4:1),
            "chest minecart selection did not exercise planned cards: "+selectedCards+"/"+exercised);
    }
    void requireMinecartChestStorage(JsonObject entity,String storage) {
        JsonArray items=entity.getAsJsonArray("vehicleItems");
        require(items!=null&&entity.has("vehicleLootTable")&&entity.has("vehicleLootSeed")
            &&entity.has("vehicleSlotCount")&&entity.get("vehicleSlotCount").getAsInt()==27,
            "chest minecart observer omitted raw 27-slot storage: "+entity);
        if(storage.equals("items")) {
            require(entity.get("vehicleLootTable").getAsString().equals("UNAVAILABLE")
                &&items.size()==2
                &&items.get(0).getAsJsonObject().get("slot").getAsInt()==0
                &&items.get(0).getAsJsonObject().get("item").getAsString().equals("minecraft:diamond")
                &&items.get(0).getAsJsonObject().get("count").getAsInt()==7
                &&items.get(1).getAsJsonObject().get("slot").getAsInt()==26
                &&items.get(1).getAsJsonObject().get("item").getAsString().equals("minecraft:iron_ingot")
                &&items.get(1).getAsJsonObject().get("count").getAsInt()==13,
                "chest minecart slot contents changed: "+entity);
        } else {
            require(storage.equals("loot")&&items.size()==0
                &&entity.get("vehicleLootTable").getAsString().equals("minecraft:chests/simple_dungeon")
                &&entity.get("vehicleLootSeed").getAsLong()==123456789L,
                "latent chest minecart loot table or seed changed: "+entity);
        }
    }
    void chestMinecartCase(String storage,String route)throws Exception {
        active="V2-chest_minecart-"+storage+"-"+route;
        String from=route.equals("NETHER_ENTRY")?OW:NETHER;
        String to=route.equals("NETHER_ENTRY")?NETHER:OW;
        position(from,8.5,65,5.5,180,0);
        String tag="stage7_vehicle_chest_minecart_"+storage+"_"+route.toLowerCase(Locale.ROOT);
        require(cmd("execute in "+from+" run execute if block 0 66 0 minecraft:nether_portal")
            .contains("Test passed"),"chest minecart source lacks exact three-wide portal");
        String extra=storage.equals("loot")
            ?",LootTable:\"minecraft:chests/simple_dungeon\",LootTableSeed:123456789L":"";
        String summon=cmd("execute in "+from+" run summon minecraft:chest_minecart 0.5 65.2 3.5 "
            +"{Tags:[\""+tag+"\"],NoGravity:1b,Motion:[0.0d,0.0d,0.0d]"+extra+"}");
        require(summon.contains("Summoned")||summon.contains("summoned"),
            "native chest minecart summon failed: "+summon);
        JsonObject admitted=null;
        for(int i=0;i<30;i++) {
            JsonObject state=snapshot(active+"-source-poll");
            for(JsonElement element:state.getAsJsonArray("entities")) {
                JsonObject entity=element.getAsJsonObject();
                if(entity.has("tags")&&entity.get("tags").getAsString().contains(tag)) {
                    admitted=taggedMob(state,tag);break;
                }
            }
            if(admitted!=null)break;
            context.waitTick();
        }
        require(admitted!=null,"native chest minecart source absent after bounded admission: "+active);
        if(storage.equals("items")) {
            cmd("execute in "+from+" run item replace entity @e[tag="+tag+",limit=1] container.0 with minecraft:diamond 7");
            cmd("execute in "+from+" run item replace entity @e[tag="+tag+",limit=1] container.26 with minecraft:iron_ingot 13");
        }
        JsonObject sourceState=snapshot(active+"-source");
        JsonObject source=taggedMob(sourceState,tag);
        String uuid=source.get("uuid").getAsString();
        require(source.get("type").getAsString().equals("minecraft:chest_minecart")
            &&source.get("world").getAsString().equals(from)
            &&source.get("z").getAsDouble()>3.0
            &&source.getAsJsonArray("passengerUuids").size()==0
            &&source.get("vehicleUuid").getAsString().equals("UNAVAILABLE"),
            "chest minecart source type/world/graph wrong: "+source);
        requireMinecartChestStorage(source,storage);
        int diamonds=looseVehicleItems(sourceState,"minecraft:diamond");
        int iron=looseVehicleItems(sourceState,"minecraft:iron_ingot");
        receipt.add(active+"-source",source.deepCopy());
        String moved=cmd("execute in "+from+" run tp @e[tag="+tag+",limit=1] 0.5 65.2 0.5");
        require(moved.contains("Teleported")||moved.contains("teleported"),
            "chest minecart could not be placed in exact portal block: "+moved);
        JsonObject arrival=null,arrivalState=null;
        for(int i=0;i<180;i++) {
            context.waitTick();
            JsonObject seenState=snapshot(active+"-wait");
            JsonObject seen=taggedMob(seenState,tag);
            if(seen.get("world").getAsString().equals(to)){
                arrival=seen;arrivalState=seenState;break;
            }
        }
        require(arrival!=null,"native chest minecart transfer absent: "+active+" "+uuid);
        require(arrival.get("uuid").getAsString().equals(uuid)
            &&arrival.get("type").getAsString().equals("minecraft:chest_minecart")
            &&!arrival.get("object").equals(source.get("object"))
            &&arrival.getAsJsonArray("passengerUuids").size()==0
            &&arrival.get("vehicleUuid").getAsString().equals("UNAVAILABLE"),
            "chest minecart replacement identity or graph changed: "+arrival);
        requireMinecartChestStorage(arrival,storage);
        int arrivalDiamonds=looseVehicleItems(arrivalState,"minecraft:diamond");
        int arrivalIron=looseVehicleItems(arrivalState,"minecraft:iron_ingot");
        require(arrivalDiamonds==diamonds&&arrivalIron==iron,
            "chest minecart transfer dropped or duplicated stored items");
        receipt.add(active+"-arrival",arrival.deepCopy());
        JsonObject scoped=snapshot(active+"-scoped");int matches=0;
        for(JsonElement element:scoped.getAsJsonArray("entities"))
            if(element.getAsJsonObject().get("uuid").getAsString().equals(uuid))matches++;
        require(matches==1,"chest minecart source/destination UUID was lost or duplicated: "+matches);
        position(to,8.5,65,5.5,180,0);
        JsonObject later=null,laterState=null;
        for(int i=0;i<100;i++) {
            context.waitTicks(2);laterState=snapshot(active+"-later");
            later=taggedMob(laterState,tag);
            if(later.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()+20)break;
        }
        require(later.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()+20
            &&later.get("world").getAsString().equals(to),
            "chest minecart did not continue native destination ticks");
        requireMinecartChestStorage(later,storage);
        int laterDiamonds=looseVehicleItems(laterState,"minecraft:diamond");
        int laterIron=looseVehicleItems(laterState,"minecraft:iron_ingot");
        require(laterDiamonds==diamonds&&laterIron==iron,
            "chest minecart later ticks dropped or duplicated stored items");
        require(context.computeOnClient(c->{for(var entity:c.level.entitiesForRendering())
            if(entity.getUUID().toString().equals(uuid))return true;return false;}),
            "connected Java client did not track chest minecart");
        receipt.add(active+"-later",later.deepCopy());
        JsonObject loose=new JsonObject();
        loose.addProperty("sourceDiamonds",diamonds);loose.addProperty("arrivalDiamonds",arrivalDiamonds);
        loose.addProperty("laterDiamonds",laterDiamonds);loose.addProperty("sourceIron",iron);
        loose.addProperty("arrivalIron",arrivalIron);loose.addProperty("laterIron",laterIron);
        receipt.add(active+"-looseItems",loose);
        health(active);pass(active,"three-wide portal; native minecart inventory or latent loot persistence; no loose item duplication; one replacement UUID/new object; empty graph, later ticks and client tracking");
    }
    /** G4: the G3 gateway entered by a dropped item, a family with its own manager and tick path. */
    void naturalGatewayItemCard()throws Exception {
        active="G4-item-END_GATEWAY-NATURAL";
        final int exitX=1000,exitZ=1000;
        cmd("gamemode creative "+USER);
        for(String command:List.of("forceload add 0 0 16 16","fill 8 64 4 16 64 16 minecraft:end_stone",
                "fill 8 65 4 16 73 16 minecraft:air","setblock 12 66 8 minecraft:end_gateway"))
            cmd("execute in "+END+" run "+command);
        String configured=cmd("execute in "+END+" run data merge block 12 66 8 "
            +"{exit_portal:[I;"+exitX+",65,"+exitZ+"],ExactTeleport:0b,Age:200L}");
        require(configured.contains("Modified")||configured.contains("modified"),"natural gateway not configured: "+configured);
        position(END,12.5,65,14.5,180,0);
        cmd("tick freeze");
        try {
            String summoned=cmd("execute in "+END+" run summon minecraft:item 12.5 66 8.5 "
                +"{Item:{id:\"minecraft:sugar_cane\",count:3},NoGravity:1b,PickupDelay:32767s}");
            require(summoned.contains("Summoned")||summoned.contains("summoned"),"natural gateway item summon failed: "+summoned);
        } finally { cmd("tick unfreeze"); }
        JsonObject arrival=null;
        for(int i=0;i<400&&arrival==null;i++) {
            context.waitTick();
            for(var e:items(snapshot(active+"-wait-"+i),END)) {
                JsonObject item=e.getAsJsonObject();
                if(Math.abs(item.get("x").getAsDouble()-exitX)<24&&Math.abs(item.get("z").getAsDouble()-exitZ)<24){arrival=item;break;}
            }
        }
        require(arrival!=null,"dropped item did not reach the natural gateway exit");
        require(arrival.get("count").getAsInt()==3,"gateway changed the item count");
        JsonObject state=snapshot(active+"-arrived");
        int total=0;for(var e:items(state,END))total++;
        require(total==1,"item duplicated or lost by the gateway: "+total);
        receipt.add(active+"-arrival",arrival.deepCopy());
        health(active);
        pass(active,"native dropped item through a non-exact gateway with an unloaded exit; one item, count preserved, server healthy");
    }
    /**
     * G3: a gateway the way worlds really have it. ExactTeleport is off, so native getPortalPosition runs
     * findExitPosition and reads the exit's chunks, and nothing forceloads or builds the destination.
     */
    void naturalGatewayCard()throws Exception {
        active="G3-pig-END_GATEWAY-NATURAL";
        final int exitX=1000,exitZ=1000;
        cmd("gamemode creative "+USER);
        for(String command:List.of("forceload add 0 0 16 16","fill 8 64 4 16 64 16 minecraft:end_stone",
                "fill 8 65 4 16 73 16 minecraft:air","setblock 12 66 8 minecraft:end_gateway"))
            cmd("execute in "+END+" run "+command);
        String configured=cmd("execute in "+END+" run data merge block 12 66 8 "
            +"{exit_portal:[I;"+exitX+",65,"+exitZ+"],ExactTeleport:0b,Age:200L}");
        require(configured.contains("Modified")||configured.contains("modified"),"natural gateway not configured: "+configured);
        position(END,12.5,65,12.5,180,0);
        String tag="stage7_gateway_pig";
        cmd("tick freeze");
        JsonObject source;
        try {
            String summoned=cmd("execute in "+END+" run summon minecraft:pig 12.5 66 8.5 "
                +"{Tags:[\""+tag+"\"],NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}");
            require(summoned.contains("Summoned")||summoned.contains("summoned"),"natural gateway pig summon failed: "+summoned);
            source=taggedMob(snapshot(active+"-source"),tag);
        } finally { cmd("tick unfreeze"); }
        JsonObject arrival=null;
        for(int i=0;i<400;i++) {
            context.waitTick();
            JsonObject pig=taggedMob(snapshot(active+"-wait-"+i),tag);
            if(pig.get("world").getAsString().equals(END)&&Math.abs(pig.get("x").getAsDouble()-exitX)<24
                    &&Math.abs(pig.get("z").getAsDouble()-exitZ)<24){arrival=pig;break;}
        }
        require(arrival!=null,"native pig did not reach the natural gateway exit");
        require(arrival.get("uuid").equals(source.get("uuid"))&&arrival.get("object").equals(source.get("object"))
            &&arrival.get("nativeId").equals(source.get("nativeId"))&&arrival.get("portalCooldown").getAsInt()>0,
            "natural gateway same-object identity or cooldown changed");
        JsonObject later=arrival;
        for(int i=0;i<200&&later.get("ticks").getAsInt()<=arrival.get("ticks").getAsInt()+20;i++){
            context.waitTicks(2);later=taggedMob(snapshot(active+"-later"),tag);
        }
        require(later.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()+20,"natural gateway pig stopped ticking");
        receipt.add(active+"-arrival",arrival.deepCopy());
        health(active);
        pass(active,"native non-exact gateway exit discovery with an unloaded unforced exit chunk; same object, cooldown, later ticks");
    }
    /**
     * G1 keeps the normal one-region End and exits to chunk (10,10). G2 runs under the explicit
     * A/B fixture (server flag phoenium.stage3.explicitTopology): the entry chunk (0,0) belongs to
     * stage3-fixture-a and the exit chunk (60,0) to stage3-fixture-b, so the three native
     * callbacks must show two genuinely different region owners.
     */
    void pigEndGatewayCard(boolean ab)throws Exception {
        active=ab?"G2-pig-END_GATEWAY-AB":"G1-pig-END_GATEWAY";
        final int exitX=ab?968:160,exitZ=ab?8:160,exitChunkX=exitX>>4,exitChunkZ=exitZ>>4;
        if(ab) {
            // Natural spawning over the large artificial B block is unrelated load for this owner proof.
            for(String rule:List.of("minecraft:spawn_mobs","minecraft:do_mob_spawning","doMobSpawning"))
                receipt.addProperty(active+"-gamerule-"+rule,cmd("execute in "+END+" run gamerule "+rule+" false"));
        }
        cmd("gamemode creative "+USER);
        for(String command:List.of("forceload add 0 0 16 16",
                "forceload add "+exitX+" "+exitZ+" "+exitX+" "+exitZ,
                "fill 8 64 4 16 64 16 minecraft:end_stone",
                "fill 8 65 4 16 73 16 minecraft:air",
                "fill "+(exitX-2)+" 64 "+(exitZ-2)+" "+(exitX+2)+" 64 "+(exitZ+2)+" minecraft:end_stone",
                "fill "+(exitX-2)+" 65 "+(exitZ-2)+" "+(exitX+2)+" 73 "+(exitZ+2)+" minecraft:air",
                "setblock 12 66 8 minecraft:end_gateway"))
            cmd("execute in "+END+" run "+command);
        String configured=cmd("execute in "+END+" run data merge block 12 66 8 "
            +"{exit_portal:[I;"+exitX+",65,"+exitZ+"],ExactTeleport:1b,Age:200L}");
        require(configured.contains("Modified")||configured.contains("modified"),
            "exact End gateway exit could not be configured: "+configured);
        require(cmd("execute in "+END+" run execute if block 12 66 8 minecraft:end_gateway")
            .contains("Test passed"),"exact source End gateway block absent");
        position(END,12.5,65,12.5,180,0);
        String tag="stage7_gateway_pig";
        cmd("tick freeze");
        JsonObject source;
        try {
            String summoned=cmd("execute in "+END+" run summon minecraft:pig 12.5 66 8.5 "
                +"{Tags:[\""+tag+"\"],NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}");
            require(summoned.contains("Summoned")||summoned.contains("summoned"),
                "exact End gateway pig summon failed: "+summoned);
            source=taggedMob(snapshot(active+"-source"),tag);
            require(source.get("type").getAsString().equals("minecraft:pig")
                &&source.get("world").getAsString().equals(END)
                &&source.get("x").getAsDouble()==12.5
                &&source.get("y").getAsDouble()==66.0
                &&source.get("z").getAsDouble()==8.5
                &&!source.get("removed").getAsBoolean(),
                "gateway source pig identity or portal position wrong");
            receipt.add(active+"-source",source.deepCopy());
        } finally { cmd("tick unfreeze"); }
        String uuid=source.get("uuid").getAsString();
        JsonObject arrivalState=null,arrival=null;
        for(int i=0;i<120;i++) {
            context.waitTick();
            JsonObject state=snapshot(active+"-wait-"+i);
            JsonObject pig=taggedMob(state,tag);
            if(pig.get("world").getAsString().equals(END)
                    &&Math.abs(pig.get("x").getAsDouble()-(exitX+.5))<1
                    &&Math.abs(pig.get("z").getAsDouble()-(exitZ+.5))<1) {
                arrivalState=state;arrival=pig;break;
            }
        }
        require(arrival!=null,"native pig same-dimension End gateway move missing");
        require(arrival.get("uuid").getAsString().equals(uuid)
            &&arrival.get("object").equals(source.get("object"))
            &&arrival.get("nativeId").equals(source.get("nativeId"))
            &&arrival.get("portalCooldown").getAsInt()>0,
            "gateway native same-object identity or cooldown changed");
        int count=0;
        for(JsonElement element:arrivalState.getAsJsonArray("entities"))
            if(element.getAsJsonObject().get("uuid").getAsString().equals(uuid))count++;
        require(count==1,"gateway pig UUID duplicated or absent");
        require(arrival.get("ownershipDiagnostic").getAsString().contains("x="+exitChunkX+", z="+exitChunkZ)
            &&arrival.get("ownershipDiagnostic").getAsString().contains("state=OWNED"),
            "gateway same-object ownership route did not move to destination chunk: "
                +arrival.get("ownershipDiagnostic"));
        String vacancy=cmd("execute in "+END
            +" positioned 12 66 8 unless entity @e[tag="+tag+",distance=..4]");
        require(vacancy.contains("Test passed"),"gateway old source location remains addressable: "+vacancy);
        receipt.addProperty(active+"-oldVacancy",true);
        JsonObject contact=null,ticket=null;
        for(JsonElement element:arrivalState.getAsJsonArray("gatewayContactEvents")) {
            JsonObject event=element.getAsJsonObject();
            if(event.get("uuid").getAsString().equals(uuid)
                    &&event.get("world").getAsString().equals(END)
                    &&event.get("x").getAsInt()==12&&event.get("y").getAsInt()==66
                    &&event.get("z").getAsInt()==8
                    &&event.get("ownerWorld").getAsString().equals(END))contact=event;
        }
        for(JsonElement element:arrivalState.getAsJsonArray("gatewayTicketEvents")) {
            JsonObject event=element.getAsJsonObject();
            if(event.get("uuid").getAsString().equals(uuid)
                    &&event.get("world").getAsString().equals(END)
                    &&event.get("ticketType").getAsString().equals("minecraft:portal")
                    &&event.get("chunkX").getAsInt()==exitChunkX&&event.get("chunkZ").getAsInt()==exitChunkZ
                    &&event.get("radius").getAsInt()==3
                    &&event.get("ownerWorld").getAsString().equals(END))ticket=event;
        }
        require(contact!=null,"native gateway source contact lacked exact End owner witness");
        require(ticket!=null,"native gateway callback lacked exact destination PORTAL ticket witness");
        receipt.add(active+"-gatewayContact",contact.deepCopy());
        receipt.add(active+"-gatewayTicket",ticket.deepCopy());
        if(ab) {
            // The native ticket for chunk B must run under B, never under the entry chunk's owner A.
            require(contact.get("ownerContext").getAsString().contains("stage3-fixture-a"),
                "A/B gateway contact did not run under the entry owner A: "+contact.get("ownerContext"));
            require(ticket.get("ownerContext").getAsString().contains("stage3-fixture-b"),
                "A/B gateway PORTAL ticket did not run under the destination owner B: "+ticket.get("ownerContext"));
            require(!ticket.get("ownerContext").getAsString().contains("stage3-fixture-a"),
                "A/B gateway PORTAL ticket still ran under source owner A: "+ticket.get("ownerContext"));
            receipt.addProperty(active+"-sourceOwnerDiffersFromTicketOwner",true);
        }
        receipt.add(active+"-arrival",arrival.deepCopy());
        // The A/B card keeps the player in A: command-teleporting a session across fixture regions is
        // not the player-movement protocol. Client tracking is covered by the one-region G1 card.
        if(!ab)position(END,exitX+.5,65,exitZ+3.5,180,0);
        JsonObject laterState=null,later=null;
        for(int i=0;i<100;i++) {
            context.waitTicks(2);
            laterState=snapshot(active+"-later-poll");
            later=taggedMob(laterState,tag);
            if(later.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()+20)break;
        }
        require(later.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()+20
            &&later.get("uuid").equals(source.get("uuid"))
            &&later.get("object").equals(source.get("object"))
            &&later.get("nativeId").equals(source.get("nativeId"))
            &&later.get("portalCooldown").getAsInt()>0,
            "gateway pig did not retain one native incarnation and cooldown for 20 later ticks");
        JsonObject tracking=null;
        for(JsonElement element:laterState.getAsJsonArray("gatewayTrackingEvents")) {
            JsonObject event=element.getAsJsonObject();
            if(event.get("uuid").getAsString().equals(uuid)
                    &&event.get("phase").getAsString().equals("TRACKING_UPDATE_RETURN")
                    &&event.get("chunkX").getAsInt()==exitChunkX&&event.get("chunkZ").getAsInt()==exitChunkZ
                    &&event.get("ownerWorld").getAsString().equals(END))tracking=event;
        }
        if(ab) {
            // Same object at the destination chunk and ticking natively. Which region owns it after
            // the owner-free cut is not asserted here: the tracking service converges any entity
            // with a connected player's region. The runner checks the ordered owner sequence
            // (ticket deferred under A, placed under B, relocation committed to B) from the log.
            String diagnostic=later.get("ownershipDiagnostic").getAsString();
            require(diagnostic.contains("x="+exitChunkX+", z="+exitChunkZ)&&diagnostic.contains("state=OWNED"),
                "A/B gateway pig binding is not at the destination chunk: "+diagnostic);
            receipt.addProperty(active+"-pigBoundAtDestinationChunk",true);
            health(active);
            pass(active,"same native object/UUID at the destination chunk; PORTAL ticket deferred from owner A and "
                +"placed under destination owner B; later native ticks (ordered owner sequence is verified from the server log)");
            return;
        }
        require(tracking!=null,"gateway destination tracking lacked exact End owner witness");
        receipt.add(active+"-destinationTracking",tracking.deepCopy());
        if(!ab)require(context.computeOnClient(c->{for(var entity:c.level.entitiesForRendering())
            if(entity.getUUID().toString().equals(uuid))return true;return false;}),
            "connected Java client did not track same-object gateway pig at destination");
        receipt.addProperty(active+"-clientTracking",true);
        receipt.add(active+"-later",later.deepCopy());
        health(active);
        pass(active,"same object/native ID/UUID across chunks, exact native portal ticket, "
            +"source/destination/tracking owner witnesses, cooldown and later client tracking");
    }
    void itemEndPlatformScopeCard()throws Exception {
        active="I0-item-END_ENTRY";
        cmd("gamemode creative "+USER);
        position(OW,8.5,65,14.5,180,0);
        for(String command:List.of("fill 0 64 0 15 64 15 minecraft:obsidian",
                "fill 0 65 0 15 80 15 minecraft:air",
                "setblock 8 65 8 minecraft:end_portal"))
            cmd("execute in "+OW+" run "+command);
        require(cmd("execute in "+OW+" run execute if block 8 65 8 minecraft:end_portal")
            .contains("Test passed"),"isolated ItemEntity End portal block absent");
        String tag="stage7_item_end_platform_scope";
        String summon=cmd("execute in "+OW+" run summon minecraft:item 8.5 65.25 10.5 "
            +"{Tags:[\""+tag+"\"],Item:{id:\"minecraft:diamond\",count:1},PickupDelay:32767}");
        require(summon.contains("Summoned")||summon.contains("summoned"),
            "isolated native ItemEntity summon failed: "+summon);
        JsonObject sourceState=snapshot(active+"-source");
        JsonObject source=taggedMob(sourceState,tag);
        require(source.get("type").getAsString().equals("minecraft:item")
            &&source.get("world").getAsString().equals(OW)
            &&!source.get("removed").getAsBoolean(),"isolated item source identity wrong");
        receipt.add(active+"-source",source.deepCopy());
        String moved=cmd("execute in "+OW+" run tp @e[tag="+tag+"] 8.5 65.25 8.5");
        require(moved.contains("Teleported")||moved.contains("teleported"),
            "isolated ItemEntity could not enter exact End portal block: "+moved);
        JsonObject arrival=null;
        for(int i=0;i<160;i++) {
            context.waitTick();
            JsonObject state=snapshot(active+"-wait-"+i);
            for(JsonElement element:state.getAsJsonArray("entities")) {
                JsonObject entity=element.getAsJsonObject();
                if(entity.get("uuid").equals(source.get("uuid"))
                        &&entity.get("world").getAsString().equals(END)) {
                    arrival=entity;break;
                }
            }
            if(arrival!=null)break;
        }
        require(arrival!=null,"isolated ItemEntity never arrived after End entry");
        receipt.add(active+"-arrival",arrival.deepCopy());
        require(!arrival.get("object").equals(source.get("object"))
            &&arrival.get("nativeId").getAsInt()!=source.get("nativeId").getAsInt(),
            "isolated ItemEntity lacked native destination replacement");
        JsonObject settledState=snapshot(active+"-settled");
        JsonObject later=taggedMob(settledState,tag);
        require(later.get("uuid").equals(source.get("uuid"))
            &&later.get("world").getAsString().equals(END)
            &&later.get("object").equals(arrival.get("object"))
            &&later.get("nativeId").equals(arrival.get("nativeId")),
            "isolated ItemEntity did not retain one destination incarnation");
        receipt.add(active+"-later",later.deepCopy());
        health(active);
        pass(active,"native ItemEntity End entry created one destination replacement and retained identity");
    }
    void noNativeEntryMatrix(String selectedCards)throws Exception {
        cmd("gamemode creative "+USER);
        require(cmd("gamerule minecraft:mob_griefing false").contains("false"),
            "negative matrix could not disable boss block damage in the isolated scene");
        int exercised=0;
        for(String route:List.of("NETHER_ENTRY","NETHER_RETURN","END_ENTRY","END_EXIT","END_GATEWAY"))
            for(String type:List.of("block_display","ender_dragon","glow_item_frame","interaction",
                    "item_display","item_frame","marker","painting","text_display","fishing_bobber","wither")) {
                String card="N0-"+type+"-"+route;
                if(!selectedCards.isBlank()&&!selectedCards.equals(card))continue;
                noNativeEntryCase(type,route);
                exercised++;
            }
        require(exercised==(selectedCards.isBlank()?55:1),
            "no-entry selection did not exercise its exact planned card: "+selectedCards+"/"+exercised);
    }
    void noNativeEntryCase(String type,String route)throws Exception {
        active="N0-"+type+"-"+route;
        String sourceWorld=switch(route) {
            case "NETHER_ENTRY","END_ENTRY" -> OW;
            case "NETHER_RETURN" -> NETHER;
            case "END_EXIT","END_GATEWAY" -> END;
            default -> throw new IllegalArgumentException(route);
        };
        String destinationWorld=switch(route) {
            case "NETHER_ENTRY" -> NETHER;
            case "NETHER_RETURN","END_EXIT" -> OW;
            case "END_ENTRY","END_GATEWAY" -> END;
            default -> throw new IllegalArgumentException(route);
        };
        boolean nether=route.startsWith("NETHER"),gateway=route.equals("END_GATEWAY");
        int portalX=nether?0:gateway?12:8,portalY=nether||gateway?66:65,portalZ=nether?0:8;
        String block=nether?"minecraft:nether_portal":gateway?"minecraft:end_gateway":"minecraft:end_portal";
        if(!nether) {
            position(sourceWorld,8.5,65,14.5,180,0);
            for(String c:List.of("fill 0 64 0 15 64 15 minecraft:obsidian",
                    "fill 0 65 0 15 80 15 minecraft:air"))
                cmd("execute in "+sourceWorld+" run "+c);
        }
        cmd("execute in "+sourceWorld+" run setblock "+portalX+" "+portalY+" "+portalZ+" "+block);
        if(gateway) {
            String response=cmd("execute in "+END+" run data merge block 12 66 8 "
                +"{exit_portal:[I;14,65,14],ExactTeleport:1b,Age:200L}");
            require(response.contains("Modified")||response.contains("modified"),
                "End gateway exact far exit could not be configured: "+response);
            require(cmd("execute in "+END+" run data get block 12 66 8").contains("exit_portal"),
                "End gateway lost its exact far exit");
        }
        require(cmd("execute in "+sourceWorld+" run execute if block "+portalX+" "+portalY+" "+portalZ+" "+block)
            .contains("Test passed"),"negative fixture lacks exact source portal block "+active);
        if(nether)position(sourceWorld,8.5,65,5.5,180,0);
        if(type.equals("painting"))
            cmd("execute in "+sourceWorld+" run setblock "+portalX+" "+portalY+" "+(portalZ-1)+" minecraft:stone");
        if(type.equals("fishing_bobber")) {
            if(nether)cmd("execute in "+sourceWorld+" run setblock "+portalX+" "+portalY+" "+(portalZ-1)+" minecraft:stone");
            else {
                cmd("execute in "+sourceWorld+" run setblock "+portalX+" "+portalY+" "+(portalZ-1)+" minecraft:stone");
                if(gateway)cmd("execute in "+sourceWorld+" run setblock "+portalX+" "+(portalY-1)+" "+portalZ+" minecraft:stone");
            }
            cmd("item replace entity "+USER+" weapon.mainhand with minecraft:fishing_rod");
            if(nether)position(sourceWorld,portalX+0.5,65,portalZ+3.5,180,0);
            else position(sourceWorld,portalX+0.5,65,portalZ+2.5,180,gateway?0:35);
        }
        String tag="stage7_noentry_"+type+"_"+route.toLowerCase(Locale.ROOT);
        String nbt="{Tags:[\""+tag+"\"],NoGravity:1b";
        if(type.endsWith("_display"))nbt+=",width:0.9f,height:1.0f";
        if(type.equals("interaction"))nbt+=",width:0.9f,height:1.0f";
        if(type.equals("item_frame")||type.equals("glow_item_frame"))
            nbt+=",Facing:3b,Fixed:1b";
        if(type.equals("painting"))nbt+=",facing:0,variant:\"minecraft:kebab\"";
        if(type.equals("ender_dragon"))
            nbt+=",NoAI:1b,DragonPhase:10,Invulnerable:1b,Silent:1b";
        if(type.equals("wither"))
            nbt+=",NoAI:1b,Invul:0,Invulnerable:1b,Silent:1b,PersistenceRequired:1b";
        String playerUuid=null;
        if(type.equals("fishing_bobber")) {
            JsonArray players=probe().getAsJsonArray("players");
            require(players.size()==1,"no-entry fishing owner fixture requires one connected player");
            playerUuid=players.get(0).getAsJsonObject().get("uuid").getAsString();
        }
        nbt+="}";
        JsonObject sourceState=null;
        if(type.equals("fishing_bobber")) {
            require(context.computeOnClient(c->c.gameMode.useItem(c.player,
                net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction()),
                "connected Java client fishing-rod cast did not consume an action");
            JsonObject hook=null;
            for(int i=0;i<20;i++) {
                context.waitTick();
                JsonObject state=probe();
                for(JsonElement element:state.getAsJsonArray("entities")) {
                    JsonObject candidate=element.getAsJsonObject();
                    if(candidate.get("type").getAsString().equals("minecraft:fishing_bobber")
                            &&candidate.get("world").getAsString().equals(sourceWorld)
                            &&candidate.get("projectileOwnerUuid").getAsString().equals(playerUuid)) {
                        hook=candidate;break;
                    }
                }
                if(hook!=null)break;
            }
            require(hook!=null,"connected client cast did not create its native owned bobber");
            String tagged=cmd("execute in "+sourceWorld+" run tag @e[type=minecraft:fishing_bobber,limit=1] add "+tag);
            require(tagged.contains("Added")||tagged.contains("added"),"native client-cast bobber could not be tagged");
            for(int i=0;i<45;i++) {
                context.waitTick();
                JsonObject state=probe();
                JsonObject current=taggedMob(state,tag);
                if(Math.floor(current.get("x").getAsDouble())==portalX
                        &&Math.floor(current.get("y").getAsDouble())==portalY
                        &&Math.floor(current.get("z").getAsDouble())==portalZ) {
                    sourceState=snapshot(active+"-source");break;
                }
            }
            require(sourceState!=null,"native client-cast bobber never reached the exact portal block");
        } else {
            double spawnY=portalY+0.25;
            String summon="execute in "+sourceWorld+" run summon minecraft:"+type+" "
                +(portalX+0.5)+" "+spawnY+" "+(portalZ+0.5)+" "+nbt;
            String summonResult=cmd(summon);
            require(summonResult.contains("Summoned")||summonResult.contains("summoned"),
                "native no-entry summon failed: "+summonResult);
            sourceState=snapshot(active+"-source");
        }
        JsonObject source=taggedMob(sourceState,tag);
        String uuid=source.get("uuid").getAsString();
        require(source.get("type").getAsString().equals("minecraft:"+type)
            &&source.get("world").getAsString().equals(sourceWorld)
            &&!source.get("removed").getAsBoolean(),"no-entry source identity or world wrong");
        require(Math.floor(source.get("x").getAsDouble())==portalX
            &&Math.floor(source.get("y").getAsDouble())==portalY
            &&Math.floor(source.get("z").getAsDouble())==portalZ,
            "no-entry source not inside exact portal block: "+active+" "+source);
        require(!source.get("portalProcessPresent").getAsBoolean(),
            "source already has a portal process before native dwell");
        if(type.equals("fishing_bobber"))
            require(source.get("projectileOwnerUuid").getAsString().equals(playerUuid),
                "fishing bobber does not have the exact same-world player owner");
        receipt.add(active+"-source",source.deepCopy());
        JsonObject applicability=new JsonObject();
        applicability.addProperty("sourceWorld",sourceWorld);
        applicability.addProperty("destinationWorld",destinationWorld);
        applicability.addProperty("portalBlock",block);
        applicability.addProperty("portalX",portalX);
        applicability.addProperty("portalY",portalY);
        applicability.addProperty("portalZ",portalZ);
        applicability.addProperty("sourceProofKind",
            type.equals("ender_dragon")||type.equals("wither")||type.equals("fishing_bobber")
                ?"unconditional_canUsePortal_false":"tick_override_skips_portal_processing");
        applicability.addProperty("sourceJarSha256",
            "055D5A99D49917A03F68007A1AC2F1D0CBD84E28A16D953C6FFC943CE3E15E1E");
        applicability.addProperty("mappedBinarySha256",
            "BA0569DEEC34D82FB3B31BC71F2400EE735E5711D4C4F54CE5ED9A789EA52892");
        receipt.add(active+"-applicability",applicability);
        context.waitTicks(type.equals("painting")?105:35);
        JsonObject settledState=snapshot(active+"-settled");
        JsonObject settled=taggedMob(settledState,tag);
        int count=0;
        for(JsonElement element:settledState.getAsJsonArray("entities"))
            if(element.getAsJsonObject().get("uuid").getAsString().equals(uuid))count++;
        require(count==1,"negative fixture source UUID became absent or duplicated: "+active);
        require(settled.get("world").getAsString().equals(sourceWorld)
            &&settled.get("object").getAsString().equals(source.get("object").getAsString())
            &&settled.get("nativeId").getAsInt()==source.get("nativeId").getAsInt()
            &&!settled.get("removed").getAsBoolean(),
            "negative fixture source native instance did not remain unchanged: "+active);
        require(!settled.get("portalProcessPresent").getAsBoolean(),
            "negative fixture created a stuck portal session: "+active);
        require(settled.get("vehicleUuid").equals(source.get("vehicleUuid"))
            &&settled.get("passengerUuids").equals(source.get("passengerUuids")),
            "negative fixture graph changed: "+active);
        boolean contact=false;
        for(JsonElement element:settledState.getAsJsonArray("noEntryContactEvents")) {
            JsonObject event=element.getAsJsonObject();
            if(event.get("uuid").getAsString().equals(uuid)
                    &&event.get("type").getAsString().equals("minecraft:"+type)
                    &&event.get("world").getAsString().equals(sourceWorld)
                    &&event.get("block").getAsString().equals(block)
                    &&event.get("x").getAsInt()==portalX
                    &&event.get("y").getAsInt()==portalY
                    &&event.get("z").getAsInt()==portalZ)contact=true;
        }
        // The dragon's native canUsePortal(false) proof is unconditional. Its
        // boss tick does not deliver this stable fixture to entityInside.
        boolean sourceGuard=type.equals("wither")||type.equals("fishing_bobber");
        require(contact==sourceGuard,"native portal contact contradicted the source-proven route: "+active+"/"+contact);
        require(cmd("execute in "+sourceWorld+" run execute if block "+portalX+" "+portalY+" "+portalZ+" "+block)
            .contains("Test passed"),"source portal block did not survive negative dwell: "+active);
        receipt.add(active+"-settledState",settledState.deepCopy());
        receipt.add(active+"-settled",settled.deepCopy());
        health(active);
        pass(active,"source UUID/object/native id unchanged, worldwide destination absent, "
            +"portalProcess null, graph unchanged, native contact="+contact+", source proof applicable");
        if(type.equals("wither")) {
            // /kill drops a Nether Star. Remove the boss from this card's
            // portal before cleanup so that drop cannot start another route.
            String moved=cmd("execute in "+sourceWorld+" run tp @e[tag="+tag+"] 8.5 65 14.5");
            require(moved.contains("Teleported")||moved.contains("teleported"),
                "negative Wither could not be moved clear of portal for cleanup: "+moved);
        }
        cmd("execute in "+sourceWorld+" run kill @e[tag="+tag+"]");
    }
    void fireballNetherReturnCard()throws Exception {
        active="P0-fireball-nether-return";
        cmd("gamemode creative "+USER);
        position(OW,8.5,65,5.5,180,0);
        cmd("execute in "+OW+" run fill -1 65 -10 1 68 -10 minecraft:glass");
        require(cmd("execute in "+OW+" run execute if block 0 66 -10 minecraft:glass").contains("Test passed"),
            "fireball destination impact wall was not built");
        String tag="stage7_projectile_fireball_return";
        cmd("execute in "+NETHER+" run summon minecraft:fireball 0.5 66 3.5 "
            +"{Tags:[\""+tag+"\"],Motion:[0.0d,0.0d,-0.4d]}");
        JsonObject source=taggedProjectile(snapshot(active+"-source"),tag,NETHER);
        String uuid=source.get("uuid").getAsString();
        require(source.get("type").getAsString().equals("minecraft:fireball"),"wrong projectile fixture type");
        JsonObject arrival=null;
        for(int i=0;i<220;i++) {
            context.waitTick();
            JsonObject state=snapshot(active+"-wait");
            arrival=projectileEvent(state,uuid,OW,"TRACKING_ADD_RETURN");
            if(arrival!=null)break;
        }
        require(arrival!=null,"native fireball did not reach Overworld tracking");
        require(!arrival.get("object").equals(source.get("object")),"fireball did not replace native incarnation");
        JsonObject settled=snapshot(active+"-settled");
        int sourceCount=0,destinationCount=0,destinationAdds=0,sourceRemoves=0;
        JsonObject liveDestination=null;
        for(var element:settled.getAsJsonArray("entities")) {
            JsonObject entity=element.getAsJsonObject();
            if(!entity.get("uuid").getAsString().equals(uuid))continue;
            if(entity.get("world").getAsString().equals(NETHER))sourceCount++;
            if(entity.get("world").getAsString().equals(OW)) {destinationCount++;liveDestination=entity;}
        }
        for(var element:settled.getAsJsonArray("projectileEvents")) {
            JsonObject event=element.getAsJsonObject();
            if(event.get("uuid").getAsString().equals(uuid)
                    &&event.get("world").getAsString().equals(OW)
                    &&event.get("phase").getAsString().equals("TRACKING_ADD_RETURN"))destinationAdds++;
            if(event.get("uuid").getAsString().equals(uuid)
                    &&event.get("world").getAsString().equals(NETHER)
                    &&event.get("phase").getAsString().equals("TRACKING_REMOVE_RETURN"))sourceRemoves++;
        }
        require(sourceCount==0&&destinationCount==1&&destinationAdds==1&&sourceRemoves==1,
            "fireball source/destination tracker accounting failed: "+sourceCount+"/"+destinationCount+"/"+destinationAdds+"/"+sourceRemoves);
        require(liveDestination.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()
                &&liveDestination.get("z").getAsDouble()<arrival.get("z").getAsDouble(),
            "destination fireball did not continue its native movement tick");
        boolean clientTracked=false;
        for(int i=0;i<16;i++) {
            clientTracked=context.computeOnClient(c->{
                for(var entity:c.level.entitiesForRendering())if(entity.getUUID().toString().equals(uuid))return true;
                return false;
            });
            if(clientTracked)break;
            context.waitTick();
        }
        require(clientTracked,"Java client did not track destination fireball before impact");
        JsonObject impact=null;
        for(int i=0;i<100;i++) {
            context.waitTick();
            JsonObject state=snapshot(active+"-impact-wait");
            impact=projectileEvent(state,uuid,OW,"TRACKING_REMOVE_RETURN");
            if(impact!=null)break;
        }
        require(impact!=null,"destination fireball did not reach native impact/removal");
        require(cmd("execute in "+OW+" run execute if block 0 66 -10 minecraft:air").contains("Test passed"),
            "destination LargeFireball impact did not explode the glass witness");
        receipt.add(active+"-source",source.deepCopy());
        receipt.add(active+"-arrival",arrival.deepCopy());
        receipt.add(active+"-impact",impact.deepCopy());
        health(active);
        pass(active,"native Nether contact, exact replacement/tracking, later movement and destination explosion; UUID="+uuid);
    }
    JsonObject taggedProjectile(JsonObject state,String tag,String world) {
        JsonObject found=null;
        for(var element:state.getAsJsonArray("entities")) {
            JsonObject entity=element.getAsJsonObject();
            if(entity.get("world").getAsString().equals(world)
                    &&entity.get("tags").getAsString().contains(tag)) {
                require(found==null,"duplicate projectile fixture tag "+tag);
                found=entity;
            }
        }
        require(found!=null,"tagged projectile source absent: "+tag);
        return found;
    }
    JsonObject projectileEvent(JsonObject state,String uuid,String world,String phase) {
        JsonObject found=null;
        for(var element:state.getAsJsonArray("projectileEvents")) {
            JsonObject event=element.getAsJsonObject();
            if(event.get("uuid").getAsString().equals(uuid)
                    &&event.get("world").getAsString().equals(world)
                    &&event.get("phase").getAsString().equals(phase)) {
                require(found==null,"duplicate projectile event "+uuid+" "+phase);
                found=event;
            }
        }
        return found;
    }
    void hurtingProjectileMatrix()throws Exception {
        cmd("gamemode creative "+USER);
        int sequence=0;
        for(String type:List.of("fireball","small_fireball","dragon_fireball","wither_skull",
                "wind_charge","breeze_wind_charge"))
            for(String from:List.of(OW,NETHER))
                hurtingProjectileCase(type,from,sequence++);
    }
    void hurtingProjectileCase(String type,String from,int sequence)throws Exception {
        String to=from.equals(OW)?NETHER:OW;
        String route=from.equals(OW)?"NETHER_ENTRY":"NETHER_RETURN";
        active="P1-"+type+"-"+route;
        position(to,8.5,65,5.5,180,0);
        cmd("execute in "+to+" run fill -1 65 -10 1 68 -10 minecraft:glass");
        require(cmd("execute in "+to+" run execute if block 0 66 -10 minecraft:glass").contains("Test passed"),
            "projectile impact wall missing: "+active);
        if(type.equals("small_fireball")) {
            cmd("execute in "+to+" run setblock 0 65 -9 minecraft:netherrack");
            cmd("execute in "+to+" run setblock 0 66 -9 minecraft:air");
            require(cmd("execute in "+to+" run execute if block 0 66 -9 minecraft:air").contains("Test passed"),
                "small fireball fire witness was already lit");
        }
        if(type.equals("wind_charge")||type.equals("breeze_wind_charge")) {
            cmd("execute in "+to+" run setblock 1 66 -9 minecraft:air");
            cmd("execute in "+to+" run setblock 1 66 -9 minecraft:lever[face=wall,facing=south,powered=false]");
            require(cmd("execute in "+to+" run execute if block 1 66 -9 minecraft:lever[powered=false]").contains("Test passed"),
                "wind charge lever witness was not initially unpowered");
        }
        if(type.equals("dragon_fireball"))
            require(!cmd("execute in "+to+" run execute if entity @e[type=minecraft:area_effect_cloud,x=0,y=66,z=-9,distance=..4]")
                .contains("Test passed"),"dragon breath witness was present before fireball impact");
        String tag="stage7_projectile_"+sequence;
        cmd("execute in "+from+" run summon minecraft:"+type+" 0.5 66 3.5 "
            +"{Tags:[\""+tag+"\"],Motion:[0.0d,0.0d,-0.4d]}");
        JsonObject source=taggedProjectile(snapshot(active+"-source"),tag,from);
        require(source.get("type").getAsString().equals("minecraft:"+type),"projectile source type mismatch");
        String uuid=source.get("uuid").getAsString();
        JsonObject arrival=null;
        for(int tick=0;tick<220;tick++) {
            context.waitTick();
            arrival=projectileEvent(snapshot(active+"-wait"),uuid,to,"TRACKING_ADD_RETURN");
            if(arrival!=null)break;
        }
        require(arrival!=null,"native projectile portal transfer absent: "+active);
        require(arrival.get("uuid").getAsString().equals(uuid)
                &&!arrival.get("object").equals(source.get("object")),
            "projectile native replacement identity mismatch: "+active);
        JsonObject later=snapshot(active+"-after-arrival");
        int sourceCount=0,destinationCount=0,sourceRetires=0,destinationAdds=0;
        for(var element:later.getAsJsonArray("entities")) {
            JsonObject entity=element.getAsJsonObject();
            if(!entity.get("uuid").getAsString().equals(uuid))continue;
            if(entity.get("world").getAsString().equals(from))sourceCount++;
            if(entity.get("world").getAsString().equals(to))destinationCount++;
        }
        for(var element:later.getAsJsonArray("projectileEvents")) {
            JsonObject event=element.getAsJsonObject();
            if(!event.get("uuid").getAsString().equals(uuid))continue;
            if(event.get("world").getAsString().equals(from)
                    &&event.get("phase").getAsString().equals("TRACKING_REMOVE_RETURN"))sourceRetires++;
            if(event.get("world").getAsString().equals(to)
                    &&event.get("phase").getAsString().equals("TRACKING_ADD_RETURN"))destinationAdds++;
        }
        require(sourceCount==0&&destinationCount<=1&&sourceRetires==1&&destinationAdds==1,
            "projectile exact source/destination accounting failed: "+active+" "
                +sourceCount+"/"+destinationCount+"/"+sourceRetires+"/"+destinationAdds);
        boolean clientTracked=false;
        for(int tick=0;tick<16;tick++) {
            clientTracked=context.computeOnClient(c->{
                for(var entity:c.level.entitiesForRendering())if(entity.getUUID().toString().equals(uuid))return true;
                return false;
            });
            if(clientTracked)break;
            context.waitTick();
        }
        require(clientTracked,"client did not track destination projectile before impact: "+active);
        JsonObject impact=null,postArrival=null;
        for(int tick=0;tick<100;tick++) {
            context.waitTick();
            JsonObject state=snapshot(active+"-impact-wait");
            for(var element:state.getAsJsonArray("entities")) {
                JsonObject entity=element.getAsJsonObject();
                if(entity.get("uuid").getAsString().equals(uuid)
                        &&entity.get("world").getAsString().equals(to)
                        &&entity.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()
                        &&entity.get("z").getAsDouble()<arrival.get("z").getAsDouble())
                    postArrival=entity;
            }
            impact=projectileEvent(state,uuid,to,"TRACKING_REMOVE_RETURN");
            if(impact!=null)break;
        }
        require(impact!=null,"projectile native destination impact/removal absent: "+active);
        String nativeEffect;
        if(type.equals("fireball")||type.equals("wither_skull")) {
            require(postArrival!=null,"destination projectile had no later movement tick: "+active);
            nativeEffect=cmd("execute in "+to+" run execute if block 0 66 -10 minecraft:air");
            require(nativeEffect.contains("Test passed"),"native impact explosion did not destroy glass: "+active);
        } else if(type.equals("small_fireball")) {
            require(postArrival!=null,"destination small fireball had no later movement tick: "+active);
            nativeEffect=cmd("execute in "+to+" run execute if block 0 66 -9 minecraft:fire");
            require(nativeEffect.contains("Test passed"),"native small fireball did not ignite netherrack witness: "+active);
        } else if(type.equals("dragon_fireball")) {
            require(postArrival!=null,"destination dragon fireball had no later movement tick: "+active);
            nativeEffect=cmd("execute in "+to+" run execute if entity @e[type=minecraft:area_effect_cloud,x=0,y=66,z=-9,distance=..4]");
            require(nativeEffect.contains("Test passed"),"native dragon breath area cloud missing: "+active);
        } else {
            nativeEffect=cmd("execute in "+to+" run execute if block 1 66 -9 minecraft:lever[powered=true]");
            require(nativeEffect.contains("Test passed"),"native wind burst did not toggle lever: "+active);
        }
        receipt.add(active+"-source",source.deepCopy());
        receipt.add(active+"-arrival",arrival.deepCopy());
        receipt.add(active+"-impact",impact.deepCopy());
        if(postArrival!=null)receipt.add(active+"-continued",postArrival.deepCopy());
        receipt.addProperty(active+"-effectWitness",nativeEffect);
        health(active);
        pass(active,"native portal contact, exact replacement, client tracking, later native effect; UUID="+uuid);
    }
    void arrowTridentMatrix(String selectedCards)throws Exception {
        cmd("gamemode creative "+USER);
        if(!selectedCards.isBlank()) {
            String type=selectedCards.substring(3,selectedCards.lastIndexOf('-'));
            String route=selectedCards.substring(selectedCards.lastIndexOf('-')+1);
            arrowTridentCase(type,route.equals("NETHER_RETURN")?NETHER:OW,0);
            return;
        }
        int sequence=0;
        for(String type:List.of("arrow","spectral_arrow","trident"))
            for(String from:List.of(OW,NETHER))
                arrowTridentCase(type,from,sequence++);
    }
    void arrowTridentCase(String type,String from,int sequence)throws Exception {
        String to=from.equals(OW)?NETHER:OW;
        String route=from.equals(OW)?"NETHER_ENTRY":"NETHER_RETURN";
        active="P2-"+type+"-"+route;
        position(from,8.5,65,5.5,180,0);
        String ownerUuid=player().get("uuid").getAsString();
        UUID owner=UUID.fromString(ownerUuid);
        long high=owner.getMostSignificantBits(),low=owner.getLeastSignificantBits();
        String ownerTag="[I;"+(int)(high>>>32)+","+(int)high+","+(int)(low>>>32)+","+(int)low+"]";
        cmd("execute in "+to+" run fill -1 65 -10 1 68 -10 minecraft:glass");
        require(cmd("execute in "+to+" run execute if block 0 66 -10 minecraft:glass").contains("Test passed"),
            "arrow/trident destination impact wall missing: "+active);
        String tag="stage7_projectile_arrow_"+sequence;
        String item=type.equals("trident")
            ?",item:{id:\"minecraft:trident\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:loyalty\":1}}}"
            :"";
        cmd("execute in "+from+" run summon minecraft:"+type+" 0.5 66 3.5 "
            +"{Tags:[\""+tag+"\"],NoGravity:1b,Motion:[0.0d,0.0d,-0.4d],Owner:"+ownerTag+",pickup:1b"+item+"}");
        JsonObject source=taggedProjectile(snapshot(active+"-source"),tag,from);
        require(source.get("type").getAsString().equals("minecraft:"+type),"arrow/trident source type mismatch");
        require(source.get("projectileOwnerUuid").getAsString().equals(ownerUuid),
            "source arrow/trident did not resolve its native player owner");
        String uuid=source.get("uuid").getAsString();
        cmd("execute in "+to+" run tp "+USER+" 8.5 65 5.5 180 0");
        context.waitFor(c->c.player!=null&&c.level!=null&&c.gui.screen()==null
            &&c.level.dimension().identifier().toString().equals(to),1200);
        JsonObject arrival=null;
        for(int tick=0;tick<220;tick++) {
            context.waitTick();
            arrival=projectileEvent(snapshot(active+"-wait"),uuid,to,"TRACKING_ADD_RETURN");
            if(arrival!=null)break;
        }
        require(arrival!=null,"native arrow/trident portal transfer absent: "+active);
        require(!arrival.get("object").equals(source.get("object")),
            "arrow/trident native replacement identity mismatch: "+active);
        require(arrival.get("projectileOwnerUuid").getAsString().equals(ownerUuid),
            "destination arrow/trident did not preserve native owner UUID: "+active);
        JsonObject settled=snapshot(active+"-arrival");
        int sourceCount=0,destinationCount=0,sourceRetires=0,destinationAdds=0;
        for(var element:settled.getAsJsonArray("entities")) {
            JsonObject entity=element.getAsJsonObject();
            if(!entity.get("uuid").getAsString().equals(uuid))continue;
            if(entity.get("world").getAsString().equals(from))sourceCount++;
            if(entity.get("world").getAsString().equals(to))destinationCount++;
        }
        for(var element:settled.getAsJsonArray("projectileEvents")) {
            JsonObject event=element.getAsJsonObject();
            if(!event.get("uuid").getAsString().equals(uuid))continue;
            if(event.get("world").getAsString().equals(from)
                    &&event.get("phase").getAsString().equals("TRACKING_REMOVE_RETURN"))sourceRetires++;
            if(event.get("world").getAsString().equals(to)
                    &&event.get("phase").getAsString().equals("TRACKING_ADD_RETURN"))destinationAdds++;
        }
        require(sourceCount==0&&destinationCount==1&&sourceRetires==1&&destinationAdds==1,
            "arrow/trident exact source/destination accounting failed: "+active+" "
                +sourceCount+"/"+destinationCount+"/"+sourceRetires+"/"+destinationAdds);
        boolean clientTracked=false;
        for(int tick=0;tick<16;tick++) {
            clientTracked=context.computeOnClient(c->{
                for(var entity:c.level.entitiesForRendering())if(entity.getUUID().toString().equals(uuid))return true;
                return false;
            });
            if(clientTracked)break;
            context.waitTick();
        }
        require(clientTracked,"Java client did not track destination arrow/trident: "+active);
        JsonObject continued=null;
        for(int tick=0;tick<100;tick++) {
            context.waitTick();
            JsonObject state=snapshot(active+"-continued");
            for(var element:state.getAsJsonArray("entities")) {
                JsonObject entity=element.getAsJsonObject();
                if(entity.get("uuid").getAsString().equals(uuid)
                        &&entity.get("world").getAsString().equals(to)
                        &&entity.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()
                        &&entity.get("z").getAsDouble()<arrival.get("z").getAsDouble()) {
                    continued=entity;break;
                }
            }
            if(continued!=null)break;
        }
        require(continued!=null,"destination arrow/trident did not continue native movement: "+active);
        JsonObject impactState=null;
        String impactWitness=null;
        for(int tick=0;tick<130;tick++) {
            context.waitTick();
            JsonObject state=snapshot(active+"-impact-wait");
            for(var element:state.getAsJsonArray("entities")) {
                JsonObject entity=element.getAsJsonObject();
                if(!entity.get("uuid").getAsString().equals(uuid)
                        ||!entity.get("world").getAsString().equals(to))continue;
                require(entity.get("projectileOwnerUuid").getAsString().equals(ownerUuid),
                    "live destination arrow/trident lost its native owner UUID: "+active);
                if(type.equals("trident")) {
                    if(entity.get("noPhysics").getAsBoolean()
                            &&entity.get("motionZ").getAsDouble()>0.02
                            &&entity.get("z").getAsDouble()>-9.0) {
                        impactState=entity;
                        impactWitness="loyalty trident returned through the impact wall toward its owner";
                    }
                } else if(entity.get("z").getAsDouble()<-8.5
                        &&Math.abs(entity.get("motionZ").getAsDouble())<0.01) {
                    String check=cmd("execute in "+to+" run execute if data entity @e[tag="+tag+",limit=1] {inGround:1b}");
                    if(check.contains("Test passed")) {
                        impactState=entity;
                        impactWitness=check;
                    }
                }
            }
            if(impactState!=null)break;
        }
        require(impactState!=null,"native arrow impact or trident loyalty return missing: "+active);
        receipt.add(active+"-source",source.deepCopy());
        receipt.add(active+"-arrival",arrival.deepCopy());
        receipt.add(active+"-continued",continued.deepCopy());
        receipt.add(active+"-impact",impactState.deepCopy());
        receipt.addProperty(active+"-impactWitness",impactWitness);
        health(active);
        pass(active,"native portal contact, owner-preserving replacement, client tracking and impact or loyalty effect; UUID="+uuid);
    }
    void throwableProjectileMatrix(String selectedCards)throws Exception {
        cmd("gamemode creative "+USER);
        if(selectedCards.equals("P3-experience_bottle-NETHER_RETURN")) {
            throwableProjectileCase("experience_bottle",NETHER,0);
            return;
        }
        int sequence=0;
        for(String type:List.of("egg","snowball","splash_potion","lingering_potion","experience_bottle"))
            for(String from:List.of(OW,NETHER))
                throwableProjectileCase(type,from,sequence++);
    }
    void throwableProjectileCase(String type,String from,int sequence)throws Exception {
        String to=from.equals(OW)?NETHER:OW;
        String route=from.equals(OW)?"NETHER_ENTRY":"NETHER_RETURN";
        active="P3-"+type+"-"+route;
        position(to,8.5,65,5.5,180,0);
        cmd("execute in "+to+" run fill -1 65 -10 1 68 -10 minecraft:glass");
        require(cmd("execute in "+to+" run execute if block 0 66 -10 minecraft:glass").contains("Test passed"),
            "throwable destination impact wall missing: "+active);
        String targetTag="stage7_mob_throwable_"+sequence;
        JsonObject targetBefore=null;
        if(type.equals("snowball")||type.equals("splash_potion")) {
            String target=type.equals("snowball")?"blaze":"pig";
            String x=type.equals("snowball")?"0.5":"1.5";
            cmd("execute in "+to+" run summon minecraft:"+target+" "+x+" 65 -9 "
                +"{Tags:[\"stage7_mob\",\""+targetTag+"\"],NoAI:1b,NoGravity:1b}");
            targetBefore=taggedProjectile(snapshot(active+"-target-before"),targetTag,to);
            require(targetBefore.get("health").getAsFloat()>3.0F,"throwable target not healthy before impact");
            require(!targetBefore.get("poisoned").getAsBoolean(),"throwable target already poisoned");
        }
        if(type.equals("lingering_potion"))
            require(!cmd("execute in "+to+" run execute if entity @e[type=minecraft:area_effect_cloud,x=0,y=66,z=-9,distance=..4]")
                .contains("Test passed"),"lingering cloud present before projectile impact");
        if(type.equals("experience_bottle"))
            require(!cmd("execute in "+to+" run execute if entity @e[type=minecraft:experience_orb,x=0,y=66,z=-9,distance=..4]")
                .contains("Test passed"),"XP orb present before projectile impact");
        String tag="stage7_projectile_throwable_"+sequence;
        String item=type.equals("splash_potion")||type.equals("lingering_potion")
            ?",Item:{id:\"minecraft:"+type+"\",count:1,components:{\"minecraft:potion_contents\":{potion:\"minecraft:poison\"}}}"
            :"";
        cmd("execute in "+from+" run summon minecraft:"+type+" 0.5 66 3.5 "
            +"{Tags:[\""+tag+"\"],NoGravity:1b,Motion:[0.0d,0.0d,-0.4d]"+item+"}");
        JsonObject source=taggedProjectile(snapshot(active+"-source"),tag,from);
        require(source.get("type").getAsString().equals("minecraft:"+type),"throwable source type mismatch");
        String uuid=source.get("uuid").getAsString();
        JsonObject arrival=null;
        for(int tick=0;tick<220;tick++) {
            context.waitTick();
            arrival=projectileEvent(snapshot(active+"-wait"),uuid,to,"TRACKING_ADD_RETURN");
            if(arrival!=null)break;
        }
        require(arrival!=null,"native throwable portal transfer absent: "+active);
        require(!arrival.get("object").equals(source.get("object")),
            "throwable native replacement identity mismatch: "+active);
        JsonObject settled=snapshot(active+"-arrival");
        int sourceCount=0,destinationCount=0,sourceRetires=0,destinationAdds=0;
        for(var element:settled.getAsJsonArray("entities")) {
            JsonObject entity=element.getAsJsonObject();
            if(!entity.get("uuid").getAsString().equals(uuid))continue;
            if(entity.get("world").getAsString().equals(from))sourceCount++;
            if(entity.get("world").getAsString().equals(to))destinationCount++;
        }
        for(var element:settled.getAsJsonArray("projectileEvents")) {
            JsonObject event=element.getAsJsonObject();
            if(!event.get("uuid").getAsString().equals(uuid))continue;
            if(event.get("world").getAsString().equals(from)
                    &&event.get("phase").getAsString().equals("TRACKING_REMOVE_RETURN"))sourceRetires++;
            if(event.get("world").getAsString().equals(to)
                    &&event.get("phase").getAsString().equals("TRACKING_ADD_RETURN"))destinationAdds++;
        }
        require(sourceCount==0&&destinationCount==1&&sourceRetires==1&&destinationAdds==1,
            "throwable exact source/destination accounting failed: "+active+" "
                +sourceCount+"/"+destinationCount+"/"+sourceRetires+"/"+destinationAdds);
        boolean clientTracked=false;
        for(int tick=0;tick<16;tick++) {
            clientTracked=context.computeOnClient(c->{
                for(var entity:c.level.entitiesForRendering())if(entity.getUUID().toString().equals(uuid))return true;
                return false;
            });
            if(clientTracked)break;
            context.waitTick();
        }
        require(clientTracked,"Java client did not track destination throwable: "+active);
        JsonObject impact=null,continued=null;
        for(int tick=0;tick<100;tick++) {
            context.waitTick();
            JsonObject state=snapshot(active+"-impact-wait");
            for(var element:state.getAsJsonArray("entities")) {
                JsonObject entity=element.getAsJsonObject();
                if(entity.get("uuid").getAsString().equals(uuid)
                        &&entity.get("world").getAsString().equals(to)
                        &&entity.get("ticks").getAsInt()>arrival.get("ticks").getAsInt()
                        &&entity.get("z").getAsDouble()<arrival.get("z").getAsDouble())continued=entity;
            }
            impact=projectileEvent(state,uuid,to,"TRACKING_REMOVE_RETURN");
            if(impact!=null)break;
        }
        require(impact!=null&&continued!=null,"throwable impact or destination continuation absent: "+active);
        JsonObject effectState=snapshot(active+"-effect");
        String effectWitness;
        if(type.equals("egg")||type.equals("snowball")) {
            JsonObject event=null;
            for(var element:effectState.getAsJsonArray("projectileImpactEvents")) {
                JsonObject seen=element.getAsJsonObject();
                if(seen.get("uuid").getAsString().equals(uuid)
                        &&seen.get("world").getAsString().equals(to)
                        &&seen.get("eventByte").getAsInt()==3)event=seen;
            }
            require(event!=null,"vanilla byte-3 projectile impact event absent: "+active);
            receipt.add(active+"-nativeEvent",event.deepCopy());
            effectWitness="byte-3 native impact event for exact projectile UUID";
            if(type.equals("snowball")) {
                JsonObject after=taggedProjectile(effectState,targetTag,to);
                require(after.get("health").getAsFloat()<targetBefore.get("health").getAsFloat()-2.5F,
                    "native snowball did not inflict three damage on Blaze: "+active);
                receipt.add(active+"-targetAfter",after.deepCopy());
            }
        } else if(type.equals("splash_potion")) {
            JsonObject after=taggedProjectile(effectState,targetTag,to);
            require(after.get("poisoned").getAsBoolean(),"native splash potion did not poison target: "+active);
            receipt.add(active+"-targetAfter",after.deepCopy());
            effectWitness="poison effect on isolated target after potion impact";
        } else if(type.equals("lingering_potion")) {
            effectWitness=cmd("execute in "+to+" run execute if entity @e[type=minecraft:area_effect_cloud,x=0,y=66,z=-9,distance=..4]");
            require(effectWitness.contains("Test passed"),"native lingering potion area cloud absent: "+active);
        } else {
            effectWitness=cmd("execute in "+to+" run execute if entity @e[type=minecraft:experience_orb,x=0,y=66,z=-9,distance=..4]");
            require(effectWitness.contains("Test passed"),"native experience bottle XP orbs absent: "+active);
        }
        receipt.add(active+"-source",source.deepCopy());
        receipt.add(active+"-arrival",arrival.deepCopy());
        receipt.add(active+"-continued",continued.deepCopy());
        receipt.add(active+"-impact",impact.deepCopy());
        receipt.addProperty(active+"-effectWitness",effectWitness);
        health(active);
        pass(active,"native portal contact, replacement, client tracking and type-specific impact effect; UUID="+uuid);
    }
    void bedAnchorFirstCards()throws Exception {
        active="R0-respawn-scene";
        cmd("gamemode survival "+USER);
        cmd("setworldspawn 0 65 0");
        cmd("time set day");
        for(String world:List.of(OW,NETHER)) {
            for(String setup:List.of("fill -3 64 -3 13 64 15 minecraft:stone",
                    "fill -3 65 -3 13 73 15 minecraft:air"))
                cmd("execute in "+world+" run "+setup);
        }
        position(OW,8.5,65,7.5,180,0);
        cmd("item replace entity "+USER+" hotbar.0 with minecraft:red_bed 1");
        context.getInput().pressKey(o->o.keyHotbarSlots[0]);context.waitTicks(6);
        context.getInput().lookAt(new BlockPos(8,64,10));context.waitTicks(3);
        context.getInput().pressKey(o->o.keyUse);context.waitTicks(12);
        JsonObject placed=probe();
        int bedParts=0,headZ=-1;
        for(int z:new int[]{9,10,11}) {
            JsonObject part=respawnBlock(placed,OW,z);
            if(part!=null&&part.get("block").getAsString().equals("minecraft:red_bed")) {
                bedParts++;
                if(part.get("state").getAsString().contains("part=head"))headZ=z;
            }
        }
        require(bedParts==2&&headZ>=0,"ordinary client bed placement did not make two bed halves");
        active="R1-bed-bind-and-cross-dimension-death";
        context.getInput().lookAt(new BlockPos(8,65,headZ));context.waitTicks(3);
        context.getInput().pressKey(o->o.keyUse);context.waitTicks(12);
        JsonObject bound=player();
        require(bound.has("respawnDimension")&&bound.get("respawnDimension").getAsString().equals(OW)
            &&bound.get("respawnPosition").getAsString().equals("8, 65, "+headZ),
            "ordinary bed interaction did not save the native bed spawn: "+bound);
        receipt.add("bedBound",bound.deepCopy());
        position(NETHER,8.5,65,7.5,180,0);
        deathAndClientRespawn();
        JsonObject atBed=player();
        require(atBed.get("world").getAsString().equals(OW)
            &&Math.abs(atBed.get("x").getAsDouble()-8)<5
            &&Math.abs(atBed.get("z").getAsDouble()-headZ)<5,
            "cross-dimension death did not use the saved bed: "+atBed);
        health(active);pass(active,"client bed placement/use saved spawn; Nether death returned near intact Overworld bed");

        active="R2-client-bed-break-and-fallback";
        position(OW,8.5,65,7.5,180,0);
        context.getInput().lookAt(new BlockPos(8,65,10));context.waitTicks(3);
        context.getInput().holdKey(o->o.keyAttack);context.waitTicks(25);
        context.getInput().releaseKey(o->o.keyAttack);context.waitTicks(10);
        JsonObject broken=probe();
        require(respawnBlock(broken,OW,9).get("block").getAsString().equals("minecraft:air")
            &&respawnBlock(broken,OW,10).get("block").getAsString().equals("minecraft:air")
            &&respawnBlock(broken,OW,11).get("block").getAsString().equals("minecraft:air"),
            "ordinary block action did not break both bed halves");
        receipt.add("bedBroken",broken.deepCopy());
        deathAndClientRespawn();
        JsonObject fallback=player();
        require(fallback.get("world").getAsString().equals(OW)
            &&Math.abs(fallback.get("x").getAsDouble())<32&&Math.abs(fallback.get("z").getAsDouble())<32
            &&!fallback.has("respawnDimension"),
            "broken bed was reused or world fallback missing: "+fallback);
        health(active);pass(active,"client broke bed; next death used configured native world fallback");

        active="R3-anchor-place-charge-bind";
        position(NETHER,8.5,65,7.5,180,0);
        cmd("item replace entity "+USER+" hotbar.0 with minecraft:respawn_anchor 1");
        context.getInput().pressKey(o->o.keyHotbarSlots[0]);context.waitTicks(5);
        context.getInput().lookAt(new BlockPos(8,64,10));context.waitTicks(3);
        context.getInput().pressKey(o->o.keyUse);context.waitTicks(10);
        JsonObject placedAnchor=probe();
        int anchorZ=anchorPosition(placedAnchor);
        require(anchorZ>=0,"ordinary client anchor placement missing");
        receipt.addProperty("anchorZ",anchorZ);
        cmd("item replace entity "+USER+" hotbar.0 with minecraft:glowstone 2");
        context.getInput().lookAt(new BlockPos(8,65,anchorZ));context.waitTicks(3);
        context.getInput().pressKey(o->o.keyUse);context.waitTicks(12);
        JsonObject charged=probe();
        require(respawnBlock(charged,NETHER,anchorZ).get("charge").getAsInt()==1,
            "normal glowstone use did not charge anchor once");
        require(player().get("glowstone").getAsInt()==1,"glowstone item consumption was not exactly one");
        context.getInput().pressKey(o->o.keyHotbarSlots[1]);context.waitTicks(5);
        context.getInput().pressKey(o->o.keyUse);context.waitTicks(12);
        JsonObject anchorBound=player();
        require(anchorBound.has("respawnDimension")&&anchorBound.get("respawnDimension").getAsString().equals(NETHER)
            &&anchorBound.get("respawnPosition").getAsString().equals("8, 65, "+anchorZ),
            "charged anchor interaction did not bind native spawn: "+anchorBound);
        receipt.add("anchorBound",anchorBound.deepCopy());
        cmd("save-all flush");health(active);pass(active,"client placed anchor, used one glowstone, separately bound Nether spawn and saved world");
    }
    void bedAnchorRestartCards()throws Exception {
        active="R4-anchor-persisted-consumption";
        JsonObject saved=probe(),before=player();
        int anchorZ=anchorPosition(saved);
        require(anchorZ>=0&&respawnBlock(saved,NETHER,anchorZ).get("charge").getAsInt()==1
            &&before.has("respawnDimension")&&before.get("respawnDimension").getAsString().equals(NETHER),
            "bound charged anchor did not survive normal save/restart");
        position(NETHER,8.5,65,7.5,180,0);
        deathAndClientRespawn();
        JsonObject arrived=player(),after=probe();
        require(arrived.get("world").getAsString().equals(NETHER)
            &&Math.abs(arrived.get("x").getAsDouble()-8)<5
            &&Math.abs(arrived.get("z").getAsDouble()-anchorZ)<5,
            "charged anchor respawn location missing: "+arrived);
        require(respawnBlock(after,NETHER,anchorZ).get("charge").getAsInt()==0,
            "native anchor charge was not consumed exactly once");
        receipt.add("anchorConsumed",after.deepCopy());
        health(active);pass(active,"saved bound Nether anchor selected after death and one charge consumed");
        active="R5-empty-anchor-fallback-reconnect";
        // The next kill must occur after native post-respawn invulnerability ends.
        context.waitTicks(100);
        deathAndClientRespawn();
        JsonObject fallback=player();
        require(fallback.get("world").getAsString().equals(OW)
            &&Math.abs(fallback.get("x").getAsDouble())<32&&Math.abs(fallback.get("z").getAsDouble())<32
            &&!fallback.has("respawnDimension"),
            "empty anchor was reused or world fallback missing: "+fallback);
        require(respawnBlock(probe(),NETHER,anchorZ).get("charge").getAsInt()==0,
            "empty anchor charge changed twice");
        disconnect();context.waitTicks(20);connect();context.waitTicks(20);
        require(player().get("world").getAsString().equals(OW),"post-anchor reconnect lost fallback session");
        health(active);pass(active,"empty anchor fell back, charge remained zero, reconnect usable");
    }
    void deathAndClientRespawn()throws Exception {
        // A just-teleported or just-respawned player can still have native damage
        // immunity. /kill reports its target even when that hit is ignored, so
        // establish a settled live incarnation before issuing the one death.
        context.waitTicks(120);
        JsonObject old=player();
        require(!old.get("removed").getAsBoolean()&&old.get("health").getAsDouble()>0,
            "death fixture did not begin with a live player: "+old);
        cmd("kill "+USER);context.waitTicks(110);
        JsonObject dead=player();
        receipt.add(active+"-deathRetirement",dead.deepCopy());
        require(dead.get("object").equals(old.get("object")),
            "death fixture changed player incarnation before client Respawn");
        require(dead.get("removed").getAsBoolean()&&dead.get("deathTime").getAsInt()>=20,
            "native death retirement missing: "+dead);
        context.runOnClient(c->c.player.respawn());
        for(int i=0;i<180;i++){context.waitTick();JsonObject current=player();
            if(!current.get("removed").getAsBoolean()&&current.get("health").getAsDouble()>0
                &&!current.get("object").equals(old.get("object")))return;}
        throw new AssertionError("normal client Respawn did not publish new player session");
    }
    JsonObject respawnBlock(JsonObject state,String world,int z) {
        for(JsonElement value:state.getAsJsonArray("respawnBlocks")) {
            JsonObject block=value.getAsJsonObject();
            if(block.get("world").getAsString().equals(world)
                &&block.get("position").getAsString().equals("8, 65, "+z))return block;
        }
        return null;
    }
    int anchorPosition(JsonObject state) {
        int found=-1;
        for(int z:new int[]{9,10,11}) {
            JsonObject block=respawnBlock(state,NETHER,z);
            if(block!=null&&block.get("block").getAsString().equals("minecraft:respawn_anchor")) {
                require(found<0,"multiple scene anchors found");found=z;
            }
        }
        return found;
    }
    JsonObject probe()throws IOException {return JsonParser.parseString(cmd("stage7-probe snapshot")).getAsJsonObject();}
    JsonObject falling(JsonObject state,String uuid,String material) {
        JsonObject found=null;
        for(JsonElement value:state.getAsJsonArray("entities")) {
            JsonObject e=value.getAsJsonObject();
            if(!e.get("type").getAsString().equals("minecraft:falling_block"))continue;
            if(uuid!=null&&!e.get("uuid").getAsString().equals(uuid))continue;
            if(!e.get("fallingState").getAsString().contains("minecraft:"+material))continue;
            require(found==null,"duplicate live falling block for "+material+" "+uuid);found=e;
        }
        return found;
    }
    JsonObject fallingArrival(JsonObject state,String uuid,String material) {
        JsonObject live=falling(state,uuid,material);
        if(live!=null&&live.get("world").getAsString().equals(END))return live;
        JsonObject found=null;
        for(JsonElement value:state.getAsJsonArray("fallingEvents")) {
            JsonObject e=value.getAsJsonObject();
            if(!e.get("uuid").getAsString().equals(uuid)
                    || !e.get("world").getAsString().equals(END)
                    || !e.get("fallingState").getAsString().contains("minecraft:"+material)
                    || !e.get("phase").getAsString().equals("TRACKING_ADD_RETURN"))continue;
            require(found==null,"duplicate End tracking addition for "+material+" "+uuid);
            found=e;
        }
        return found;
    }
    JsonObject fallingLandingEvent(JsonObject state,String uuid) {
        JsonObject found=null;
        for(JsonElement value:state.getAsJsonArray("fallingEvents")) {
            JsonObject e=value.getAsJsonObject();
            if(!e.get("uuid").getAsString().equals(uuid)
                    || !e.get("world").getAsString().equals(END)
                    || !e.get("phase").getAsString().equals("TRACKING_REMOVE_RETURN"))continue;
            require(found==null,"duplicate End falling-block retirement for "+uuid);
            found=e;
        }
        return found;
    }
    /** Per-type summon NBT for the entity-family probe (types with required fields or very short lives). */
    static final java.util.Map<String,String> PROBE_NBT=java.util.Map.ofEntries(
        java.util.Map.entry("area_effect_cloud",",Duration:1200,Radius:2.0f,Particle:{type:\"minecraft:effect\",color:16711680}"),
        java.util.Map.entry("experience_orb",",Value:5s"),
        java.util.Map.entry("firework_rocket",",LifeTime:400,FireworksItem:{id:\"minecraft:firework_rocket\",count:1,components:{\"minecraft:fireworks\":{flight_duration:3}}}"),
        java.util.Map.entry("evoker_fangs",",Warmup:0"),
        java.util.Map.entry("llama_spit",""),
        java.util.Map.entry("item",",Item:{id:\"minecraft:diamond\",count:1},PickupDelay:32767"),
        java.util.Map.entry("falling_block",",BlockState:{Name:\"minecraft:sand\"},Time:1"),
        java.util.Map.entry("tnt",",fuse:400s"),
        java.util.Map.entry("eye_of_ender",""),
        java.util.Map.entry("end_crystal",",ShowBottom:0b"),
        java.util.Map.entry("ominous_item_spawner",",item:{id:\"minecraft:arrow\",count:1},spawn_item_after_ticks:400L"));
    /**
     * Loose, defect-finding probe for entity families without a retained portal manager: place the entity in the
     * Nether portal cell and record what native does. Only server health is required; the outcome is data.
     */
    void entityProbe(String selected)throws Exception {
        cmd("gamemode creative "+USER);
        int sequence=0;
        for(String card:selected.split(",")) {
            var parts=java.util.regex.Pattern.compile("E0-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT)").matcher(card);
            require(parts.matches(),"bad probe card "+card);
            String route=parts.group(2);
            active=card;String type=parts.group(1),from,to;
            switch(route){
                case "NETHER_ENTRY"->{from=OW;to=NETHER;}
                case "NETHER_RETURN"->{from=NETHER;to=OW;}
                case "END_ENTRY"->{from=OW;to=END;}
                default->{from=END;to=OW;}
            }
            String tag="stage7_probe_"+sequence++;
            position(OW,8.5,65,5.5,180,0);
            String at="0.5 65 0.5";
            if(route.startsWith("END")) {
                // Local scene: an isolated End portal block over an obsidian floor. The dimension change itself is native.
                int y=route.equals("END_ENTRY")?65:70;
                for(String c:List.of("forceload add 0 0 15 15","fill 4 "+(y-1)+" 4 12 "+(y-1)+" 12 minecraft:obsidian",
                        "fill 4 "+y+" 4 12 "+(y+6)+" 12 minecraft:air","setblock 8 "+y+" 8 minecraft:end_portal"))
                    cmd("execute in "+from+" run "+c);
                at="8.5 "+y+".25 8.5";
            }
            String summoned=cmd("execute in "+from+" run summon minecraft:"+type+" "+at+" {Tags:[\"stage7_mob\",\""+tag+"\"],NoGravity:1b"
                +PROBE_NBT.getOrDefault(type,"")+"}");
            receipt.addProperty(card+"-summon",summoned);
            String outcome="GONE",world="NONE";
            for(int i=0;i<100;i++) {
                context.waitTick();
                JsonObject found=null;
                for(var e:snapshot(card+"-wait-"+i).getAsJsonArray("entities")) {var o=e.getAsJsonObject();
                    if(o.has("tags")&&o.get("tags").getAsString().contains(tag))found=o;}
                if(found==null)continue;
                world=found.get("world").getAsString();outcome=world.equals(to)?"TRANSFERRED":"STAYED";
                if(outcome.equals("TRANSFERRED"))break;
            }
            receipt.addProperty(card+"-outcome",outcome);receipt.addProperty(card+"-world",world);
            health(card);pass(card,"entity-family probe outcome="+outcome+" world="+world);
            for(String w:List.of(OW,NETHER,END))cmd("execute in "+w+" run kill @e[tag=stage7_mob]");
        }
    }
    /**
     * Defect-finding probe: the connected test player rides a vehicle that is carried into a Nether portal.
     * The vehicle ticks the portal; the rider travels with it natively. Only server health is required.
     */
    boolean creditsSeen;
    /**
     * Native: a vehicle carrying a player who has not seen the End credits cannot leave the End through the exit
     * portal (Entity.canTeleport). The player's own first exit shows the credits and respawns them; this does that
     * through the real client so the rider cards below observe the post-credits native behaviour.
     */
    void seeEndCredits(int y)throws Exception {
        active=active+"-credits";
        position(END,5.5,y,5.5,180,0);
        cmd("execute in "+END+" run tp "+USER+" 8.5 "+(y+0.1)+" 8.5 180 0");
        context.waitFor(c->c.gui.screen() instanceof net.minecraft.client.gui.screens.WinScreen,1200);
        context.waitTicks(10);
        context.runOnClient(c->{if(c.gui.screen() instanceof net.minecraft.client.gui.screens.WinScreen w)w.onClose();});
        awaitWorld(OW,600);
        health(active);creditsSeen=true;
        receipt.addProperty(active,"credits shown, client respawned the player in "+player().get("world").getAsString());
    }
    void graphProbe(String selected)throws Exception {
        cmd("gamemode creative "+USER);
        int sequence=0;
        for(String card:selected.split(",")) {
            var parts=java.util.regex.Pattern.compile("GP-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT)").matcher(card);
            require(parts.matches(),"bad graph probe card "+card);
            String route=parts.group(2);
            active=card;String type=parts.group(1),from,to;
            switch(route){
                case "NETHER_ENTRY"->{from=OW;to=NETHER;}
                case "NETHER_RETURN"->{from=NETHER;to=OW;}
                case "END_ENTRY"->{from=OW;to=END;}
                default->{from=END;to=OW;}
            }
            String tag="stage7_probe_g"+sequence++;
            int y=route.equals("END_EXIT")?70:65;
            String carry="0.5 65 0.5";
            if(route.startsWith("END")) {
                for(String c:List.of("forceload add 0 0 15 15","fill 4 "+(y-1)+" 4 12 "+(y-1)+" 12 minecraft:obsidian",
                        "fill 4 "+y+" 4 12 "+(y+6)+" 12 minecraft:air","setblock 8 "+y+" 8 minecraft:end_portal"))
                    cmd("execute in "+from+" run "+c);
                carry="8.5 "+y+".25 8.5";
            }
            if(route.equals("END_EXIT")&&!creditsSeen) seeEndCredits(y);
            position(from,route.startsWith("END")?5.5:8.5,y,5.5,180,0);
            cmd("execute in "+from+" run summon minecraft:"+type+" 4.5 "+y+" 5.5 {Tags:[\"stage7_mob\",\""+tag+"\"],PersistenceRequired:1b}");
            receipt.addProperty(card+"-ride",cmd("ride "+USER+" mount @e[tag="+tag+",limit=1]"));
            receipt.addProperty(card+"-carry",cmd("execute in "+from+" run tp @e[tag="+tag+",limit=1] "+carry));
            String outcome="NO_TRANSFER",vehicleWorld="UNKNOWN",playerWorld="UNKNOWN";
            for(int i=0;i<160;i++) {
                context.waitTick();
                JsonObject state=snapshot(card+"-wait-"+i);
                playerWorld=player().get("world").getAsString();
                for(var e:state.getAsJsonArray("entities")) {var o=e.getAsJsonObject();
                    if(o.has("tags")&&o.get("tags").getAsString().contains(tag))vehicleWorld=o.get("world").getAsString();}
                if(playerWorld.equals(to)){outcome="PLAYER_TRANSFERRED";break;}
            }
            String riding="UNKNOWN";
            if(outcome.equals("PLAYER_TRANSFERRED")) {
                for(int i=0;i<40;i++) {
                    context.waitTick();
                    riding=cmd("execute as @e[tag="+tag+",limit=1] on passengers if entity @s[name="+USER+"]").contains("Test passed")?"RIDING":"NOT_RIDING";
                    if(riding.equals("RIDING"))break;
                }
                snapshot(card+"-after");
                vehicleWorld=taggedMob(snapshot(card+"-vehicle"),tag).get("world").getAsString();
            }
            receipt.addProperty(card+"-outcome",outcome);receipt.addProperty(card+"-playerWorld",playerWorld);
            receipt.addProperty(card+"-vehicleWorld",vehicleWorld);receipt.addProperty(card+"-riding",riding);
            health(card);pass(card,"graph probe outcome="+outcome+" player="+playerWorld+" vehicle="+vehicleWorld+" riding="+riding);
            cmd("ride "+USER+" dismount");
            for(String w:List.of(OW,NETHER,END))cmd("execute in "+w+" run kill @e[tag=stage7_mob]");
            position(OW,8.5,65,5.5,180,0);
        }
    }
    static final java.util.Set<String> PLACED_INTO_PORTAL=java.util.Set.of("squid","glow_squid","tadpole","pufferfish","tropical_fish","salmon","shulker");
    /** Data-driven mob caller-profile census: one exact Nether-route observation per M3 card. */
    void mobCensus(String selected)throws Exception {
        cmd("gamemode creative "+USER);
        int sequence=0;
        for(String card:selected.split(",")) {
            var parts=java.util.regex.Pattern.compile("M3-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN)").matcher(card);
            require(parts.matches(),"bad census card "+card);
            mobPortalCase(parts.group(1),parts.group(2).equals("NETHER_ENTRY")?OW:NETHER,1,sequence++,card);
            // Each census card is independent: arrivals from earlier cards must not hunt or crowd the next
            // (for example an Axolotl hunting a Pufferfish). Cleanup follows the card's PASS record.
            for(String world:List.of(OW,NETHER))cmd("execute in "+world+" run kill @e[tag=stage7_mob]");
        }
    }
    void mobPortalCase(String type,String from,int size,int sequence)throws Exception {
        mobPortalCase(type,from,size,sequence,null);
    }
    void mobPortalCase(String type,String from,int size,int sequence,String cardName)throws Exception {
            active=cardName!=null ? cardName : type.equals("silverfish") ? "M2-silverfish-"+(from.equals(OW)?"NETHER_ENTRY":"NETHER_RETURN")
                : "M0-"+type+"-size"+size+"-"+from;
            String to=from.equals(OW)?NETHER:OW;
            position(from,8.5,65,5.5,180,0);
            String tag="stage7_portal_mob_"+sequence;
            boolean cube=type.equals("magma_cube")||type.equals("slime");
            String sizeData=cube?",Size:"+(size-1):"";
            cmd("execute in "+from+" run summon minecraft:"+type+" 0.5 65 2.5 {Tags:[\"stage7_mob\",\""+tag+"\"],PersistenceRequired:1b"+sizeData+"}");
            JsonObject before=taggedMob(snapshot(active+"-source"),tag);
            require(before.get("world").getAsString().equals(from),"mob must begin outside its source portal");
            if(cube)require(before.get("size").getAsInt()==size,"cube size setup changed");
            if(cardName!=null&&PLACED_INTO_PORTAL.contains(type)) {
                // Water-bound mobs (and the Shulker) cannot reach the portal on foot: local scene placement into the portal cell only.
                // The dimension change that follows remains the native portal entry.
                cmd("execute in "+from+" run tp @e[tag="+tag+",limit=1] 0.5 65 0.5");
            } else
            // Initial scene velocity; no position/teleport or transfer helper is applied to this mob.
            cmd("execute in "+from+" run data merge entity @e[tag="+tag+",limit=1] {Motion:[0.0d,0.0d,-0.8d]}");
            JsonObject arrived=null;
            for(int i=0;i<180;i++) {context.waitTick();JsonObject current=taggedMob(snapshot(active+"-wait"),tag);
                if(current.get("world").getAsString().equals(to)){arrived=current;break;}}
            require(arrived!=null,"native mob portal transfer missing: "+tag);
            require(arrived.get("uuid").equals(before.get("uuid"))&&!arrived.get("object").equals(before.get("object")),"native mob replacement identity not preserved");
            if(cube)require(arrived.get("size").getAsInt()==size&&arrived.get("health").getAsDouble()>0,"cube size or health changed on arrival");
            position(to,8.5,65,5.5,180,0);
            JsonObject later=arrived;
            for(int i=0;i<160&&later.get("ticks").getAsInt()<=arrived.get("ticks").getAsInt()+25;i++) {
                context.waitTicks(2);later=taggedMob(snapshot(active+"-later"),tag);
            }
            if(type.equals("chicken"))require(later.get("eggTime").getAsInt()<arrived.get("eggTime").getAsInt(),"later native chicken AI/egg timer did not progress");
            require(later.get("world").getAsString().equals(to)&&later.get("ticks").getAsInt()>arrived.get("ticks").getAsInt()+20,"destination native mob ticks did not progress");
            String uuid=later.get("uuid").getAsString();
            require(context.computeOnClient(c->{for(var e:c.level.entitiesForRendering())if(e.getUUID().toString().equals(uuid))return true;return false;}),"destination mob tracking absent");
            health(active);pass(active,"native portal lookup, replacement, destination tracking and later ticks; UUID="+uuid);
            if(type.equals("magma_cube")&&from.equals(NETHER)&&size==1) cubeReverseAfterCooldown(tag,arrived,uuid,size);
            else if(cube) holdCube(to,tag);
    }
    void decorativeLivingCase(String type,String from,boolean equipped,int sequence)throws Exception {
        active="A0-"+type.replace('_','-')+"-"+from+"-"+(equipped?"equipped":"empty");
        String to=from.equals(OW)?NETHER:OW,tag="stage7_decorative_"+sequence;
        position(from,8.5,65,5.5,180,0);
        String equipment=equipped?",ShowArms:1b,NoBasePlate:1b,ArmorItems:[{},{},{},{id:\"minecraft:diamond_helmet\",count:1}],HandItems:[{id:\"minecraft:iron_sword\",count:1},{}]":"";
        cmd("execute in "+from+" run summon minecraft:"+type+" 0.5 65 2.5 {Tags:[\""+tag+"\"],PersistenceRequired:1b"+equipment+"}");
        JsonObject before=taggedMob(snapshot(active+"-source"),tag);
        require(before.get("world").getAsString().equals(from),"armor stand source world changed before contact");
        cmd("execute in "+from+" run tp @e[tag="+tag+",limit=1] 0.5 65 0.5");
        JsonObject arrived=null;
        for(int i=0;i<180;i++) {context.waitTick();JsonObject now=taggedMob(snapshot(active+"-wait"),tag);
            if(now.get("world").getAsString().equals(to)){arrived=now;break;}}
        require(arrived!=null,"native armor stand portal transfer missing");
        require(arrived.get("uuid").equals(before.get("uuid"))&&!arrived.get("object").equals(before.get("object")),
            "armor stand replacement identity changed");
        require(arrived.get("head").equals(before.get("head"))&&arrived.get("mainhand").equals(before.get("mainhand")),
            type+" equipment changed on transfer");
        if(type.equals("armor_stand"))
            require(arrived.get("small").equals(before.get("small"))&&arrived.get("marker").equals(before.get("marker")),
                "armor stand size changed on transfer");
        position(to,8.5,65,5.5,180,0);
        JsonObject later=arrived;
        for(int i=0;i<160&&later.get("ticks").getAsInt()<=arrived.get("ticks").getAsInt()+25;i++) {
            context.waitTicks(2);later=taggedMob(snapshot(active+"-later"),tag);
        }
        require(later.get("ticks").getAsInt()>arrived.get("ticks").getAsInt()+20,"armor stand did not tick after arrival");
        String uuid=later.get("uuid").getAsString();
        require(context.computeOnClient(c->{for(var e:c.level.entitiesForRendering())if(e.getUUID().toString().equals(uuid))return true;return false;}),
            "armor stand client tracking absent");
        health(active);pass(active,"native portal, replacement, tracking and later ticks; UUID="+uuid);
    }
    void holdCube(String world,String tag)throws Exception {
        // Same-world scene placement after the native portal and later-tick assertions.
        cmd("execute in "+world+" run tp @e[tag="+tag+",limit=1] 5.5 65 9.5");
        require(taggedMob(snapshot("held-"+tag),tag).get("world").getAsString().equals(world),"cube holding scene changed dimension");
    }
    void cubeReverseAfterCooldown(String tag,JsonObject firstArrival,String uuid,int size)throws Exception {
        active="M1-magma-cube-reverse-after-cooldown";
        holdCube(OW,tag);
        context.waitTicks(310);
        JsonObject before=taggedMob(snapshot(active+"-before"),tag);
        require(before.get("world").getAsString().equals(OW),"cube left the destination before controlled reverse setup");
        // Local position setup after natural cooldown. The dimension change remains native portal entry.
        cmd("execute in "+OW+" run tp @e[tag="+tag+",limit=1] 0.5 65 2.5");
        cmd("execute in "+OW+" run data merge entity @e[tag="+tag+",limit=1] {Motion:[0.0d,0.0d,-0.8d]}");
        JsonObject returned=null;
        for(int i=0;i<180;i++){context.waitTick();JsonObject current=taggedMob(snapshot(active+"-wait"),tag);
            if(current.get("world").getAsString().equals(NETHER)){returned=current;break;}}
        require(returned!=null,"magma cube failed native reverse portal entry after cooldown");
        require(returned.get("uuid").getAsString().equals(uuid)
            &&!returned.get("object").getAsString().equals(firstArrival.get("object").getAsString())
            &&returned.get("size").getAsInt()==size,"reverse cube replacement identity/size changed");
        position(NETHER,8.5,65,5.5,180,0);context.waitTicks(30);
        JsonObject later=taggedMob(snapshot(active+"-later"),tag);
        require(later.get("ticks").getAsInt()>returned.get("ticks").getAsInt()+20,"reverse cube native ticks did not progress");
        require(context.computeOnClient(c->{for(var e:c.level.entitiesForRendering())if(e.getUUID().toString().equals(uuid))return true;return false;}),
            "reverse cube client tracking absent");
        health(active);pass(active,"same UUID, new native replacement after natural cooldown and local scene repositioning");
        holdCube(NETHER,tag);
    }
    JsonObject taggedMob(JsonObject state,String tag) {
        JsonObject found=null;
        for(var e:state.getAsJsonArray("entities")) {var o=e.getAsJsonObject();
            if(o.has("tags")&&o.get("tags").getAsString().contains(tag)) {require(found==null,"duplicate tagged mob "+tag);found=o;}}
        require(found!=null,"tagged mob absent "+tag);return found;
    }
    void containers()throws Exception {
        active="C0-geometry";
        for(String c:List.of("forceload add 0 -16 32 16","fill 12 32 -3 23 37 5 minecraft:glass hollow",
                "fill 12 32 -3 23 32 5 minecraft:sea_lantern",
                "setblock 15 33 0 minecraft:chest[facing=north,type=left]","setblock 16 33 0 minecraft:chest[facing=north,type=right]",
                "setblock 20 33 0 minecraft:chest[facing=north]","item replace block 15 33 0 container.0 with minecraft:iron_ingot 23"))cmd("execute in "+NETHER+" run "+c);
        cmd("clear "+USER+" minecraft:iron_ingot");cmd("item replace entity "+USER+" hotbar.0 with minecraft:iron_ingot 37");
        JsonObject geometry=snapshot("verified-double-chest-geometry");
        require(geometry.getAsJsonArray("chests").size()==3,"three actual chest block entities required");
        for(int i=0;i<2;i++)require(geometry.getAsJsonArray("chests").get(i).getAsJsonObject().get("combinedSize").getAsInt()==54,"halves did not form a real double container");
        require(geometry.getAsJsonArray("chests").get(0).getAsJsonObject().get("chunk").getAsString().equals("0,0")
            &&geometry.getAsJsonArray("chests").get(1).getAsJsonObject().get("chunk").getAsString().equals("1,0"),"double chest did not span the chunk edge");
        checkIron("initial",60);
        active="C1-normal-close";
        for(int i=0;i<5;i++){openChest(20,27);closeMenu();checkIron("ordinary-close-"+i,60);}
        pass(active,"five real ordinary chest open/close cycles, iron total 60");
        active="C2-double-counts-and-mob";openChest(15,54);
        cmd("execute in "+NETHER+" run summon minecraft:magma_cube 0.5 65 2.5 {Tags:[\"stage7_mob\",\"stage7_container_mob\"],PersistenceRequired:1b,Size:0,Motion:[0.0d,0.0d,-0.8d]}");
        clickSlot(0,0,net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);checkIron("double-transfer-out",60);
        int slot=context.computeOnClient(c->{for(int i=54;i<c.player.containerMenu.slots.size();i++)if(c.player.containerMenu.getSlot(i).getItem().is(Items.IRON_INGOT))return i;return -1;});
        require(slot>=54,"counted iron inventory slot missing");
        clickSlot(slot,1,net.minecraft.world.inventory.ContainerInput.PICKUP);require(player().get("cursorIron").getAsInt()>0,"real cursor pickup missing");checkIron("cursor-held",60);
        clickSlot(1,0,net.minecraft.world.inventory.ContainerInput.PICKUP);require(player().get("cursorIron").getAsInt()==0,"real cursor deposit missing");
        for(int i=0;i<120;i++){context.waitTick();if(taggedMob(snapshot("container-active-mob"),"stage7_container_mob").get("world").getAsString().equals(OW))break;}
        require(taggedMob(snapshot("container-mob-arrived"),"stage7_container_mob").get("world").getAsString().equals(OW),"mob portal did not complete while container was open");
        holdCube(OW,"stage7_container_mob");
        require(player().get("menuOpen").getAsBoolean(),"double menu unexpectedly closed during mob transfer");checkIron("double-and-mob",60);
        int menuA=player().get("menuId").getAsInt();closeMenu();openChest(20,27);int menuB=player().get("menuId").getAsInt();
        require(menuB!=menuA,"replacement reused old menu id");
        // Already-admitted delayed A work is exercised by NativeContainerCloseTest,
        // including a replacement with the SAME numeric ID. Vanilla 26.2 deliberately
        // ignores the ID of a newly arriving close packet; injecting one here would
        // request B's close, not deliver the retained A operation.
        context.waitTicks(12);
        require(player().get("menuId").getAsInt()==menuB&&player().get("menuOpen").getAsBoolean(),"replacement menu B unexpectedly closed");
        closeMenu();checkIron("menu-replacement",60);health(active);pass(active,"verified (15,33,0)/(16,33,0), native transfers/cursor totals, mob portal during open menu, ordinary close A/open B; delayed admitted A covered by native unit fixture");
        active="C3-disconnect-open-menu";openChest(15,54);require(player().get("menuOpen").getAsBoolean(),"disconnect fixture was not open");
        disconnect();context.waitTicks(25);connect();checkIron("reconnected-menu",60);require(!player().get("menuOpen").getAsBoolean(),"stale container after reconnect");
        openChest(15,54);closeMenu();health(active);pass(active,"disconnect while double container open, reconnect, exact totals and usable reopen");
        active="C4-menu-death";openChest(15,54);cmd("spawnpoint "+USER+" 17 33 3");
        JsonObject beforeDeath=player();require(beforeDeath.get("menuOpen").getAsBoolean(),"death fixture was not open before kill");
        cmd("kill "+USER);context.waitTicks(110);JsonObject dead=player();
        require(dead.get("removed").getAsBoolean()&&dead.get("deathTime").getAsInt()>=20,"delayed menu death lacked native retirement");
        checkIron("native-death-disposition",60);
        context.runOnClient(c->c.player.respawn());context.waitFor(c->c.player!=null&&c.player.isAlive()&&c.gui.screen()==null,600);context.waitTicks(30);
        require(!player().get("object").equals(beforeDeath.get("object")),"menu death did not replace player");
        checkIron("menu-death-respawn",60);openChest(15,54);closeMenu();
        // Recover the witnessed native death drops through normal pickup, then put the
        // counted supply in the saved chest before later independent inventory scenes.
        JsonObject disposition=snapshot("death-drops-before-recovery");
        for(var e:disposition.getAsJsonArray("entities")){var drop=e.getAsJsonObject();
            if(drop.has("item")&&drop.get("item").getAsString().equals("minecraft:iron_ingot")){
                position(drop.get("world").getAsString(),drop.get("x").getAsDouble(),drop.get("y").getAsDouble(),drop.get("z").getAsDouble(),180,0);context.waitTicks(20);
            }}
        openChest(15,54);
        for(int recoverySlot=54;recoverySlot<90;recoverySlot++){final int index=recoverySlot;
            if(context.computeOnClient(c->c.player.containerMenu.getSlot(index).getItem().is(Items.IRON_INGOT)))
                clickSlot(recoverySlot,0,net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);}
        closeMenu();checkIron("recovered-death-drops-in-chests",60);
        require(player().get("iron").getAsInt()==0,"counted iron was not returned to saved containers");
        require(snapshot("saved-chest-counts").getAsJsonArray("chests").asList().stream().mapToInt(e->e.getAsJsonObject().get("iron").getAsInt()).sum()==60,"saved physical chests must contain all 60 ingots");
        health(active);pass(active,"menu observed open before death, native retirement, delayed respawn, exact unchanged-rule death totals, native drop recovery and 60 ingots returned to saved chests");
    }
    void openChest(int x,int chestSlots)throws Exception {
        position(NETHER,x+.5,33,3.5,180,0);context.getInput().lookAt(new BlockPos(x,33,0));context.getInput().pressKey(o->o.keyUse);
        context.waitFor(c->c.player.containerMenu!=c.player.inventoryMenu,300);context.waitTicks(8);
        require(player().get("menuSlots").getAsInt()==chestSlots+36,"wrong native container geometry");
    }
    void closeMenu()throws Exception {context.getInput().pressKey(o->o.keyInventory);context.waitFor(c->c.player.containerMenu==c.player.inventoryMenu&&c.gui.screen()==null,300);context.waitTicks(8);}
    void clickSlot(int slot,int button,net.minecraft.world.inventory.ContainerInput input)throws Exception {
        context.runOnClient(c->c.gameMode.handleContainerInput(c.player.containerMenu.containerId,slot,button,input,c.player));context.waitTicks(8);
    }
    void checkIron(String label,int expected)throws Exception {
        JsonObject state=snapshot(label);int total=0;
        for(var e:state.getAsJsonArray("players")){var p=e.getAsJsonObject();total+=p.get("iron").getAsInt()+p.get("cursorIron").getAsInt();}
        for(var e:state.getAsJsonArray("chests"))total+=e.getAsJsonObject().get("iron").getAsInt();
        for(var e:state.getAsJsonArray("entities")){var o=e.getAsJsonObject();if(o.has("item")&&o.get("item").getAsString().equals("minecraft:iron_ingot"))total+=o.get("count").getAsInt();}
        require(total==expected,label+" inventory + physical chest halves + cursor + dropped iron: "+total+" expected "+expected);
    }
    void baseline()throws Exception {
        active="I0";position(OW,8.5,65,5.5,180,0);context.waitTicks(30);
        JsonObject before=player();context.getInput().holdKey(o->o.keyUp);context.waitTicks(18);context.getInput().releaseKey(o->o.keyUp);
        context.waitTicks(12);JsonObject after=player();require(Math.abs(after.get("z").getAsDouble()-before.get("z").getAsDouble())>1,"native movement did not progress");
        context.runOnClient(c->c.getConnection().sendChat("STAGE7_AUTOMATED_CHAT"));
        // Keep the baseline input control clear of the still-ticking portal mobs.
        // This is scene geometry only; no entity is moved, removed or disabled.
        cmd("execute in "+OW+" run fill 60 100 -2 68 100 10 minecraft:sea_lantern");
        position(OW,64.5,101,5.5,180,45);cmd("item replace entity "+USER+" hotbar.0 with minecraft:cobblestone 8");
        context.getInput().pressKey(o->o.keyHotbarSlots[0]);context.waitTicks(10);
        context.waitFor(c->c.player.getMainHandItem().is(Items.COBBLESTONE)&&c.player.getMainHandItem().getCount()==8,300);
        context.getInput().lookAt(new BlockPos(64,100,3));context.waitTicks(2);
        String aim=context.computeOnClient(c->String.valueOf(c.hitResult));
        JsonObject input=new JsonObject();input.addProperty("label","baseline-placement-input");input.addProperty("clientHit",aim);observations.add(input);
        require(context.computeOnClient(c->c.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit&&hit.getBlockPos().equals(new BlockPos(64,100,3))),"baseline use did not target its support block: "+aim);
        context.getInput().pressKey(o->o.keyUse);context.waitTicks(12);
        require(cmd("execute in "+OW+" if block 64 101 3 minecraft:cobblestone").contains("Test passed"),"real block placement missing");
        cmd("item replace entity "+USER+" hotbar.1 with minecraft:iron_pickaxe");context.getInput().pressKey(o->o.keyHotbarSlots[1]);context.waitTicks(10);
        context.getInput().lookAt(new BlockPos(64,101,3));context.getInput().holdKey(o->o.keyAttack);context.waitTicks(35);context.getInput().releaseKey(o->o.keyAttack);context.waitTicks(10);
        require(cmd("execute in "+OW+" if block 64 101 3 minecraft:air").contains("Test passed"),"real block break missing");
        position(OW,14.5,65,5.5,-90,0);
        cmd("item replace entity "+USER+" hotbar.0 with minecraft:sugar_cane 1");context.getInput().pressKey(o->o.keyHotbarSlots[0]);context.waitTicks(8);
        long caneAdds=snapshot("before-moving-drop").get("caneAdds").getAsLong();
        context.getInput().holdKey(o->o.keyUp);context.getInput().pressKey(o->o.keyDrop);context.waitTicks(22);context.getInput().releaseKey(o->o.keyUp);
        context.waitTicks(30);JsonObject moving=snapshot("moving-drop");JsonArray dropped=items(moving,OW);
        require(player().get("x").getAsDouble()>16,"real moving drop did not cross the chunk boundary");
        require(moving.get("caneAdds").getAsLong()==caneAdds+1,"moving drop did not publish exactly one native item");
        if(player().get("cane").getAsInt()==0){
            require(dropped.size()==1,"uncollected moving drop not present");JsonObject pickup=dropped.get(0).getAsJsonObject();
            position(OW,pickup.get("x").getAsDouble(),pickup.get("y").getAsDouble(),pickup.get("z").getAsDouble()+2,180,0);
            context.getInput().holdKey(o->o.keyUp);context.waitTicks(10);context.getInput().releaseKey(o->o.keyUp);context.waitTicks(15);
        }
        require(player().get("cane").getAsInt()==1,"moving drop/pickup did not return one item");
        require(items(snapshot("moving-picked-up"),OW).size()==0,"moving pickup left duplicate item");
        cmd("clear "+USER+" minecraft:sugar_cane");
        cmd("execute in "+OW+" run summon minecraft:chicken -8 65 6 {Tags:[\"stage7_mob\",\"stage7_baseline\"],PersistenceRequired:1b}");
        JsonObject firstMob=mob(snapshot("mob-start"));context.waitTicks(45);JsonObject laterMob=mob(snapshot("mob-ticked"));
        require(firstMob.get("uuid").equals(laterMob.get("uuid"))&&laterMob.get("ticks").getAsInt()>firstMob.get("ticks").getAsInt()+20,"native mob ticks did not progress");
        health(active);pass(active,"native movement across chunk boundary, placement, break, moving drop/pickup, chat and bounded chicken ticking");
    }
    void playerPortals()throws Exception {
        active="I1";for(String destination:List.of(NETHER,OW)){
            String source=destination.equals(NETHER)?OW:NETHER;
            position(source,.5,65,3.0,180,0);context.waitTicks(310);
            context.getInput().holdKey(o->o.keyUp);context.waitTicks(10);context.getInput().releaseKey(o->o.keyUp);
            awaitWorld(destination,300);snapshot("player-portal-"+destination);
            // Move away so the native cooldown counts down outside the portal.
            position(destination,8.5,65,5.5,180,0);context.waitTicks(12);
        }health(active);pass(active,"real client entered linked native portals in both directions");
    }
    void itemCard(String from,String to,int count,int sequence)throws Exception {
        itemCard(from,to,count,sequence,0,0);
    }
    void itemCard(String from,String to,int count,int sequence,int fromX,int toX)throws Exception {
        active=sequence==4?"I6":sequence<2?"I2-"+from:"I3-"+sequence;
        position(from,fromX+.5,65,1.45,180,0);cmd("clear "+USER+" minecraft:sugar_cane");
        cmd("item replace entity "+USER+" hotbar.0 with minecraft:sugar_cane "+count);
        context.getInput().pressKey(o->o.keyHotbarSlots[0]);
        context.waitFor(c->c.player!=null&&c.player.getMainHandItem().is(Items.SUGAR_CANE)&&c.player.getMainHandItem().getCount()==count,600);
        context.runOnClient(c->c.player.drop(true)); // vanilla full-stack drop, normal serverbound action
        JsonObject item=null;
        for(int i=0;i<200;i++){context.waitTick();JsonArray found=items(snapshot("item-wait-"+sequence),to);
            if(found.size()==1){item=found.get(0).getAsJsonObject();break;}}
        require(item!=null,"dropped stack did not arrive through portal");
        require(item.get("count").getAsInt()==count,"transferred quantity changed");String uuid=item.get("uuid").getAsString();
        JsonObject state=snapshot("item-arrived-"+sequence);require(items(state,from).size()==0,"source item duplicate");
        require(items(state,to).size()==1,"destination duplicate");
        // Player positioning is setup only; the item already crossed via its own portal tick.
        position(to,toX+8.5,65,5.5,180,0);context.waitTicks(35);
        final String trackedUuid=uuid;
        require(context.computeOnClient(c->{for(var entity:c.level.entitiesForRendering())if(entity instanceof ItemEntity&&entity.getUUID().toString().equals(trackedUuid))return true;return false;}),"destination item never reached client tracking");
        // Observe the current position after native flight/settling, not the earlier arrival coordinates.
        item=items(snapshot("item-current-position-"+sequence),to).get(0).getAsJsonObject();
        position(to,item.get("x").getAsDouble(),item.get("y").getAsDouble(),item.get("z").getAsDouble(),180,0);
        for(int i=0;i<80&&player().get("cane").getAsInt()!=count;i++)context.waitTick();
        require(player().get("cane").getAsInt()==count,"destination native pickup wrong quantity");
        require(items(snapshot("item-picked-up-"+sequence),to).size()==0,"collected item remains addressable");
        cmd("clear "+USER+" minecraft:sugar_cane");position(to,toX+8.5,65,5.5,180,0);
        health(active);pass(active,"native full-stack drop; destination client tracking; source absent; pickup count="+count+" UUID="+uuid);
    }
    void localPearl()throws Exception {
        active="I4";position(OW,8.5,65,6.5,180,25);heal();equipPearl();
        double z=player().get("z").getAsDouble();context.getInput().pressKey(o->o.keyUse);context.waitTicks(65);
        JsonObject p=player();require(p.get("world").getAsString().equals(OW),"local pearl changed dimension");
        require(Math.abs(p.get("z").getAsDouble()-z)>2,"local pearl did not teleport its owner");
        require(p.get("health").getAsDouble()<20,"local pearl did not apply native damage");
        require(pearls(snapshot("local-pearl-end")).size()==0,"local pearl did not retire");health(active);pass(active,"real item use, native local hit, teleport, damage and discard");
    }
    void pearlCard(String from,String to)throws Exception {
        active="I5-"+from;position(from,.5,65,3.0,180,0);heal();equipPearl();
        JsonObject before=player();context.getInput().pressKey(o->o.keyUse);
        awaitWorld(to,240);context.waitTicks(20);JsonObject after=player();
        require(before.get("object").equals(after.get("object")),"native player dimension travel replaced the player object");
        require(after.get("health").getAsDouble()<20,"portal pearl hit did not damage player");
        require(pearls(snapshot("pearl-hit-"+to)).size()==0,"portal pearl failed to retire after hit");
        require(after.get("object").equals(after.get("listenerPlayerObject")),"listener player diverged after portal hit");
        position(to,8.5,65,5.5,180,0);health(active);pass(active,"real thrown pearl portal transfer followed by native collision and player-world transition");
    }
    void respawns()throws Exception {
        active="I7";for(int cycle=0;cycle<2;cycle++){
            position(OW,8.5,65,5.5,180,0);cmd("spawnpoint "+USER+" 8 65 5");cmd("clear "+USER);
            JsonObject old=player();cmd("kill "+USER);context.waitTicks(110);
            JsonObject dead=player();require(dead.get("removed").getAsBoolean()&&dead.get("deathTime").getAsInt()>=20,"native death retirement not observed");
            require(dead.get("object").equals(old.get("object")),"death changed player before respawn");
            for(JsonElement e:snapshot("death-retired-"+cycle).getAsJsonArray("entities"))require(!e.getAsJsonObject().get("uuid").equals(old.get("uuid")),"old player still published after retirement");
            context.runOnClient(c->c.player.respawn());
            for(int i=0;i<150;i++){context.waitTick();if(!player().get("removed").getAsBoolean()&&player().get("health").getAsDouble()>0)break;}
            JsonObject fresh=player();require(!fresh.get("object").equals(old.get("object")),"respawn did not replace player");
            require(fresh.get("object").equals(fresh.get("listenerPlayerObject")),"respawn listener not converged");
            context.waitTicks(30);JsonObject beforeMovement=player();
            context.getInput().holdKey(o->o.keyUp);context.waitTicks(10);context.getInput().releaseKey(o->o.keyUp);context.waitTicks(10);
            require(moved(beforeMovement,player()),"post-respawn native movement did not progress");
            snapshot("respawn-gameplay-"+cycle);disconnect();context.waitTicks(20);connect();context.waitTicks(20);
            require(snapshot("respawn-reconnect-"+cycle).getAsJsonArray("players").size()==1,"duplicate UUID after reconnect");health(active);
        }pass(active,"two native deaths, observed retirement, delayed client respawn, movement and same-UUID reconnects");
    }
    void coldDestination()throws Exception {
        active="I6-setup";
        // Fresh distant portals, with only their own native setup chunks loaded. No provider
        // futures, slots, or entity teleport methods are replaced by the harness.
        remotePortal(OW,4096);remotePortal(NETHER,512);
        itemCard(OW,NETHER,7,4,4096,512);
    }
    void remotePortal(String world,int x)throws Exception {
        String prefix="execute in "+world+" run ";
        for(String c:List.of("forceload add "+x+" 0",
            "fill "+(x-12)+" 64 -12 "+(x+12)+" 76 12 minecraft:glass hollow",
            "fill "+(x-12)+" 64 -12 "+(x+12)+" 64 12 minecraft:sea_lantern",
            "fill "+(x-2)+" 64 0 "+(x+2)+" 69 0 minecraft:obsidian",
            "fill "+(x-1)+" 65 0 "+(x+1)+" 68 0 minecraft:air",
            "setblock "+(x-1)+" 65 0 minecraft:fire"))cmd(prefix+c);
        require(cmd(prefix+"execute if block "+x+" 66 0 minecraft:nether_portal").contains("Test passed"),"distant native portal absent");
    }
    void restartCard()throws Exception {
        active="I8";context.waitTicks(35);require(player().get("cane").getAsInt()==11,"inventory failed save/restart");
        require(cmd("execute in "+OW+" if block 8 65 8 minecraft:diamond_block").contains("Test passed"),"persistent block absent");
        // Native spawn yaw faces the persistence marker. Turn into the clear aisle;
        // attempting to walk through that saved solid block is not a liveness test.
        context.getInput().lookAt(new BlockPos(8,65,3));
        JsonObject beforeMovement=player();
        context.getInput().holdKey(o->o.keyUp);context.waitTicks(12);context.getInput().releaseKey(o->o.keyUp);context.waitTicks(10);
        require(moved(beforeMovement,player()),"post-restart native movement did not progress");
        checkIron("restart-counts",60);openChest(15,54);closeMenu();checkIron("restart-menu-counts",60);
        position(OW,8.5,65,5.5,180,0);
        JsonObject savedMob=mob(snapshot("restart-mob-before")),later=savedMob;
        for(int i=0;i<160&&later.get("ticks").getAsInt()<=savedMob.get("ticks").getAsInt()+25;i++){
            context.waitTicks(2);later=mob(snapshot("restart-mob-later"));}
        require(later.get("ticks").getAsInt()>savedMob.get("ticks").getAsInt()+20,"saved mob stopped ticking after restart");
        String mobUuid=later.get("uuid").getAsString();
        require(context.computeOnClient(c->{for(var e:c.level.entitiesForRendering())if(e.getUUID().toString().equals(mobUuid))return true;return false;}),"saved mob tracking absent after restart");
        snapshot("restart-gameplay");pass(active,"same saved world; 11 cane, diamond block, 60 chest iron, real double-menu use, movement and saved mob ticking/client tracking");
    }
    void equipPearl()throws Exception {cmd("item replace entity "+USER+" hotbar.0 with minecraft:ender_pearl 16");context.getInput().pressKey(o->o.keyHotbarSlots[0]);context.waitTicks(30);require(context.computeOnClient(c->c.player.getMainHandItem().is(Items.ENDER_PEARL)),"client pearl supply absent");}
    void heal()throws Exception {cmd("effect give "+USER+" minecraft:instant_health 1 5 true");context.waitTicks(5);}
    void position(String world,double x,double y,double z,float yaw,float pitch)throws Exception {
        cmd("execute in "+world+" run tp "+USER+" "+x+" "+y+" "+z+" "+yaw+" "+pitch);
        context.waitFor(c->c.player!=null&&c.level!=null&&c.gui.screen()==null
            &&c.level.dimension().identifier().toString().equals(world)
            &&Math.abs(c.player.getX()-x)<.5&&Math.abs(c.player.getZ()-z)<.5,1200);
        context.waitTicks(10);
    }
    void awaitWorld(String world,int ticks)throws Exception {for(int i=0;i<ticks;i++){context.waitTick();if(player().get("world").getAsString().equals(world)){context.waitTicks(10);return;}}throw new AssertionError("player did not arrive in "+world);}
    void connect(){context.runOnClient(c->ConnectScreen.startConnecting(c.gui.screen(),c,ServerAddress.parseString(System.getProperty("phoenium.repair.address")),new ServerData("Stage7 automated",System.getProperty("phoenium.repair.address"),ServerData.Type.OTHER),false,null));context.waitFor(c->c.player!=null&&c.level!=null,1200);context.waitTicks(25);}
    void disconnect(){context.runOnClient(c->{if(c.getConnection()!=null)c.getConnection().getConnection().disconnect(Component.literal("Stage7 automated reconnect"));c.disconnect(new TitleScreen(),false);});context.waitFor(c->c.level==null,600);}
    JsonObject player()throws Exception {JsonArray ps=JsonParser.parseString(cmd("stage7-probe snapshot")).getAsJsonObject().getAsJsonArray("players");require(ps.size()==1,"expected one native logged-in test player: "+ps);return ps.get(0).getAsJsonObject();}
    JsonObject snapshot(String label)throws Exception {JsonObject s=JsonParser.parseString(cmd("stage7-probe snapshot")).getAsJsonObject();JsonObject row=s.deepCopy();row.addProperty("label",label);observations.add(row);return s;}
    JsonArray items(JsonObject state,String world){JsonArray out=new JsonArray();for(JsonElement e:state.getAsJsonArray("entities")){JsonObject o=e.getAsJsonObject();if(o.get("type").getAsString().equals("minecraft:item")&&o.get("item").getAsString().equals("minecraft:sugar_cane")&&o.get("world").getAsString().equals(world))out.add(o);}return out;}
    JsonArray pearls(JsonObject state){JsonArray out=new JsonArray();for(JsonElement e:state.getAsJsonArray("entities"))if(e.getAsJsonObject().get("type").getAsString().equals("minecraft:ender_pearl"))out.add(e);return out;}
    JsonObject mob(JsonObject state){for(JsonElement e:state.getAsJsonArray("entities"))if(e.getAsJsonObject().get("tags").getAsString().contains("stage7_baseline"))return e.getAsJsonObject();throw new AssertionError("bounded native chicken absent");}
    void health(String label)throws Exception {String d=cmd("phoenium diagnostics");require(d.contains("lifecycle=RUNNING")&&d.contains("scheduler=RUNNING"),"unhealthy scheduler: "+d);JsonObject row=new JsonObject();row.addProperty("label",label);row.addProperty("diagnostics",d);observations.add(row);}
    void pass(String card,String details){JsonObject c=new JsonObject();c.addProperty("card",card);c.addProperty("result","PASS");c.addProperty("details",details);cards.add(c);System.out.println("STAGE7_CARD_PASS "+card+" "+details);}
    static boolean moved(JsonObject before,JsonObject after){
        double dx=after.get("x").getAsDouble()-before.get("x").getAsDouble();
        double dz=after.get("z").getAsDouble()-before.get("z").getAsDouble();
        return before.get("world").equals(after.get("world"))&&dx*dx+dz*dz>.25;
    }
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    Socket openRconSocket()throws IOException {
        InetSocketAddress address=new InetSocketAddress("127.0.0.1",Integer.getInteger("phoenium.repair.rconPort"));
        for(int attempt=1;attempt<=8;attempt++){
            Socket socket=new Socket();
            try {socket.connect(address,1500);return socket;}
            catch(BindException transientBind){
                socket.close();
                if(attempt==8)throw transientBind;
                // Connect failed before authentication or a command write. Retrying
                // this fresh socket cannot duplicate any RCON side effect.
                System.out.println("STAGE7_RCON_CONNECT_RETRY attempt="+attempt+" cause="+transientBind);
                try {Thread.sleep(25L*attempt);}
                catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted before RCON command was sent",interrupted);
                }
            }catch(IOException failure){socket.close();throw failure;}
        }
        throw new IOException("unreachable RCON connect retry state");
    }
    String cmd(String command)throws IOException {
        try(Socket socket=openRconSocket()){socket.setSoTimeout(15000);
            DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));OutputStream out=socket.getOutputStream();
            write(out,1,3,"phoenium-stage7-local-test");read(in);write(out,2,2,command);
            Packet first=read(in);require(first.id()==2,"wrong RCON command response");
            // Minecraft splits command output into 4096-character responses. Once the
            // command has been received, an unknown packet type is a side-effect-free
            // ordered terminator; it cannot be coalesced with the original request.
            write(out,3,0,"");StringBuilder result=new StringBuilder(first.text());
            for(int packets=0;packets<256;packets++){Packet next=read(in);if(next.id()==3)return result.toString();
                if(next.id()!=2)throw new IOException("wrong RCON continuation id");result.append(next.text());}
            throw new IOException("RCON response exceeded bounded packet count");}
    }
    static void write(OutputStream out,int id,int type,String text)throws IOException {byte[] body=text.getBytes(StandardCharsets.UTF_8);ByteBuffer b=ByteBuffer.allocate(body.length+14).order(ByteOrder.LITTLE_ENDIAN);b.putInt(body.length+10).putInt(id).putInt(type).put(body).put((byte)0).put((byte)0);out.write(b.array());out.flush();}
    record Packet(int id,String text){}
    static Packet read(DataInputStream in)throws IOException {int size=Integer.reverseBytes(in.readInt());if(size<10||size>1000000)throw new IOException("bad RCON length");byte[] b=in.readNBytes(size);if(b.length!=size)throw new EOFException();int id=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();if(id<0)throw new IOException("RCON refused");return new Packet(id,new String(b,8,size-10,StandardCharsets.UTF_8));}
}
