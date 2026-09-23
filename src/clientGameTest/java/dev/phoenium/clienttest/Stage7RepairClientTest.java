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
        try {
            connect();
            if(group.equals("end-falling")){fallingEndCards();}
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
            require(cmd("execute in "+OW+" run execute if block 0 73 0 minecraft:"+material).contains("Test passed"),
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
    void mobPortalCase(String type,String from,int size,int sequence)throws Exception {
            active="M0-"+type+"-size"+size+"-"+from;String to=from.equals(OW)?NETHER:OW;
            position(from,8.5,65,5.5,180,0);
            String tag="stage7_portal_mob_"+sequence;
            boolean cube=type.equals("magma_cube")||type.equals("slime");
            String sizeData=cube?",Size:"+(size-1):"";
            cmd("execute in "+from+" run summon minecraft:"+type+" 0.5 65 2.5 {Tags:[\"stage7_mob\",\""+tag+"\"],PersistenceRequired:1b"+sizeData+"}");
            JsonObject before=taggedMob(snapshot(active+"-source"),tag);
            require(before.get("world").getAsString().equals(from),"mob must begin outside its source portal");
            if(cube)require(before.get("size").getAsInt()==size,"cube size setup changed");
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
    String cmd(String command)throws IOException {
        try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",Integer.getInteger("phoenium.repair.rconPort")),1500);socket.setSoTimeout(15000);
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
