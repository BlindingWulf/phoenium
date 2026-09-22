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
    static final String USER="PhoeniumTest0",OW="minecraft:overworld",NETHER="minecraft:the_nether";
    final Path evidence=Path.of(System.getProperty("phoenium.repair.evidence"));
    final JsonObject receipt=new JsonObject();final JsonArray cards=new JsonArray(),observations=new JsonArray();
    ClientGameTestContext context;
    String active="CONNECT";
    @Override public void runTest(ClientGameTestContext ctx) {
        context=ctx;receipt.add("cards",cards);receipt.add("observations",observations);
        receipt.addProperty("username",USER);receipt.addProperty("facility","Fabric Client GameTest over TCP, separate dedicated server");
        receipt.addProperty("humanActions",false);receipt.addProperty("candidateSha256",System.getProperty("phoenium.repair.sha256"));
        try {
            connect();
            if(Boolean.getBoolean("phoenium.repair.restart")){restartCard();}
            else {baseline();playerPortals();itemCard(OW,NETHER,3,0);itemCard(NETHER,OW,4,1);
                itemCard(OW,NETHER,2,2);itemCard(NETHER,OW,5,3);localPearl();pearlCard(OW,NETHER);pearlCard(NETHER,OW);coldDestination();respawns();
                cmd("execute in "+OW+" run setblock 8 65 8 minecraft:diamond_block");
                cmd("item replace entity "+USER+" hotbar.0 with minecraft:sugar_cane 11");
                context.waitTicks(10);snapshot("before-save");cmd("save-all flush");}
            health("final");disconnect();receipt.addProperty("pass",true);
        } catch(Throwable failure){receipt.addProperty("pass",false);receipt.addProperty("failedCard",active);
            receipt.addProperty("failure",failure.toString());try{snapshot("failure");}catch(Throwable ignored){}
            throw new AssertionError("Stage7 card "+active+" failed",failure);
        } finally {try{if(context.computeOnClient(c->c.player!=null))disconnect();}catch(Throwable ignored){}
            try{Files.createDirectories(evidence.getParent());Files.writeString(evidence,new GsonBuilder().setPrettyPrinting().create().toJson(receipt));}
            catch(IOException failure){throw new UncheckedIOException(failure);}}
    }
    void baseline()throws Exception {
        active="I0";position(OW,8.5,65,5.5,180,0);context.waitTicks(30);
        JsonObject before=player();context.getInput().holdKey(o->o.keyUp);context.waitTicks(18);context.getInput().releaseKey(o->o.keyUp);
        context.waitTicks(12);JsonObject after=player();require(Math.abs(after.get("z").getAsDouble()-before.get("z").getAsDouble())>1,"native movement did not progress");
        context.runOnClient(c->c.getConnection().sendChat("STAGE7_AUTOMATED_CHAT"));
        // Place and break via real client attack/use at a prepared reachable pad.
        position(OW,8.5,65,5.5,180,45);cmd("item replace entity "+USER+" hotbar.0 with minecraft:cobblestone 8");
        context.getInput().pressKey(o->o.keyHotbarSlots[0]);context.waitTicks(10);
        context.getInput().lookAt(new BlockPos(8,64,3));context.getInput().pressKey(o->o.keyUse);context.waitTicks(12);
        require(cmd("execute in "+OW+" if block 8 65 3 minecraft:cobblestone").contains("Test passed"),"real block placement missing");
        cmd("item replace entity "+USER+" hotbar.1 with minecraft:iron_pickaxe");context.getInput().pressKey(o->o.keyHotbarSlots[1]);context.waitTicks(10);
        context.getInput().lookAt(new BlockPos(8,65,3));context.getInput().holdKey(o->o.keyAttack);context.waitTicks(35);context.getInput().releaseKey(o->o.keyAttack);context.waitTicks(10);
        require(cmd("execute in "+OW+" if block 8 65 3 minecraft:air").contains("Test passed"),"real block break missing");
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
        cmd("execute in "+OW+" run summon minecraft:chicken -8 65 6 {Tags:[\"stage7_mob\"],PersistenceRequired:1b}");
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
        JsonObject beforeMovement=player();
        context.getInput().holdKey(o->o.keyUp);context.waitTicks(12);context.getInput().releaseKey(o->o.keyUp);context.waitTicks(10);
        require(moved(beforeMovement,player()),"post-restart native movement did not progress");
        snapshot("restart-gameplay");pass(active,"same saved world, retained inventory/block, real reconnect and movement");
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
    JsonArray items(JsonObject state,String world){JsonArray out=new JsonArray();for(JsonElement e:state.getAsJsonArray("entities")){JsonObject o=e.getAsJsonObject();if(o.get("type").getAsString().equals("minecraft:item")&&o.get("world").getAsString().equals(world))out.add(o);}return out;}
    JsonArray pearls(JsonObject state){JsonArray out=new JsonArray();for(JsonElement e:state.getAsJsonArray("entities"))if(e.getAsJsonObject().get("type").getAsString().equals("minecraft:ender_pearl"))out.add(e);return out;}
    JsonObject mob(JsonObject state){for(JsonElement e:state.getAsJsonArray("entities"))if(e.getAsJsonObject().get("type").getAsString().equals("minecraft:chicken"))return e.getAsJsonObject();throw new AssertionError("bounded native chicken absent");}
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
            write(out,1,3,"phoenium-stage7-local-test");read(in);write(out,2,2,command);return read(in);}
    }
    static void write(OutputStream out,int id,int type,String text)throws IOException {byte[] body=text.getBytes(StandardCharsets.UTF_8);ByteBuffer b=ByteBuffer.allocate(body.length+14).order(ByteOrder.LITTLE_ENDIAN);b.putInt(body.length+10).putInt(id).putInt(type).put(body).put((byte)0).put((byte)0);out.write(b.array());out.flush();}
    static String read(DataInputStream in)throws IOException {int size=Integer.reverseBytes(in.readInt());if(size<10||size>1000000)throw new IOException("bad RCON length");byte[] b=in.readNBytes(size);if(b.length!=size)throw new EOFException();if(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt()<0)throw new IOException("RCON refused");return new String(b,8,size-10,StandardCharsets.UTF_8);}
}
