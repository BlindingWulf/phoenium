package dev.phoenium.a3;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.live.MobDecisionContext;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.service.RegionalServiceDispatcher;
import dev.phoenium.service.RegionalServiceKind;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.goal.Goal;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Bounded observation-only counters plus explicit owner-routed fixture commands. Never production. */
public final class MobDecisionServerProbe implements DedicatedServerModInitializer {
    private static final Map<String, Observation> OBSERVED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Class<?>,Field> MOB_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    static final class Observation {
        long ticks, aiTicks, acquisitions, navigationTicks, despawnChecks;
        final Map<Integer,Integer> acquisitionTicks = new TreeMap<>();
        final Map<Long,Integer> acquisitionWorldTicks = new TreeMap<>();
    }
    private static Observation observed(Entity entity) {
        if(!entity.entityTags().contains("a3")) return null;
        if(OBSERVED.size()>1024) throw new IllegalStateException("A3 fixture exceeded bounded identities");
        return OBSERVED.computeIfAbsent(entity.getUUID().toString(),ignored->new Observation());
    }
    public static void tick(Entity entity, boolean ai) {
        var o=observed(entity); if(o!=null) { if(ai)o.aiTicks++; else o.ticks++; }
    }
    public static void navigation(Mob mob) { var o=observed(mob); if(o!=null)o.navigationTicks++; }
    public static void despawn(Mob mob) { var o=observed(mob); if(o!=null)o.despawnChecks++; }
    public static void acquisition(Goal goal) {
        if(!MobDecisionContext.reviewedGoal(goal.getClass())) return;
        try {
            Field field=MOB_FIELDS.computeIfAbsent(goal.getClass(),type->{
                for(Class<?> c=type;c!=null;c=c.getSuperclass()) try { Field f=c.getDeclaredField("mob");f.setAccessible(true);return f; }
                catch(NoSuchFieldException absent) { }
                throw new IllegalStateException("reviewed goal mob field absent");
            });
            Mob mob=(Mob)field.get(goal); var o=observed(mob);
            if(o!=null) {
                if(o.acquisitionTicks.size()>4096)throw new IllegalStateException("A3 observation exceeded tick bound");
                o.acquisitions++; o.acquisitionTicks.merge(mob.tickCount,1,Integer::sum);
                o.acquisitionWorldTicks.merge(mob.level().getGameTime(),1,Integer::sum);
            }
        } catch(IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    @Override public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,selection)->dispatcher.register(Commands.literal("a3")
            .then(Commands.literal("snapshot").then(Commands.argument("label",StringArgumentType.word()).executes(context->{
                String label=StringArgumentType.getString(context,"label");
                snapshot(context.getSource().getServer(),label);
                context.getSource().sendSuccess(()->Component.literal("A3_SNAPSHOT "+label),false);return 1;
            })))
            .then(Commands.literal("reobserve").executes(context->{
                int n=0; for(var level:context.getSource().getServer().getAllLevels()) for(var entity:level.getAllEntities())
                    if(entity.entityTags().contains("a3")){Phoenium.liveRuntime().onEntityAddressable(entity);n++;}
                int count=n;context.getSource().sendSuccess(()->Component.literal("A3_REOBSERVE "+count),false);return n;
            }))
            .then(Commands.literal("navigate").then(Commands.argument("tag",StringArgumentType.word())
                .then(Commands.argument("x",DoubleArgumentType.doubleArg()).then(Commands.argument("y",DoubleArgumentType.doubleArg())
                .then(Commands.argument("z",DoubleArgumentType.doubleArg()).executes(context->{
                    String tag=StringArgumentType.getString(context,"tag"); int count=0;
                    for(var level:context.getSource().getServer().getAllLevels())for(var e:level.getAllEntities())if(e instanceof Mob mob && e.entityTags().contains(tag)){
                        owned(mob,()->{
                            if(!mob.getNavigation().moveTo(DoubleArgumentType.getDouble(context,"x"),DoubleArgumentType.getDouble(context,"y"),DoubleArgumentType.getDouble(context,"z"),1))
                                throw new IllegalStateException("A3 fixture navigation did not produce a path");
                        });count++;
                    }
                    int n=count;context.getSource().sendSuccess(()->Component.literal("A3_NAVIGATE "+n),false);return n;
                }))))))));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->snapshot(server,"stopped"));
    }
    private static void owned(Entity entity,Runnable body) {
        try {
            var runtime=Phoenium.liveRuntime();
            Field objectsField=runtime.getClass().getDeclaredField("objects");objectsField.setAccessible(true);
            var objects=(ObjectOwnershipRegistry)objectsField.get(runtime);
            Field dispatcherField=runtime.getClass().getDeclaredField("regionalDispatcher");dispatcherField.setAccessible(true);
            var dispatcher=(RegionalServiceDispatcher)dispatcherField.get(runtime);
            var binding=objects.current(entity.getUUID().toString()).orElseThrow();
            dispatcher.ensureClosure(binding.routingKey(),1);
            dispatcher.executeAndDrain(binding.routingKey(),RegionalServiceKind.CHUNK_GAMEPLAY,ignored->body.run());
        } catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    public static void snapshot(MinecraftServer server,String label) {
        if(!label.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("invalid snapshot label");
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("label",label);report.put("policy",Phoenium.liveRuntime().mobDecisionSnapshot());
        report.put("nativeObservations",OBSERVED);
        report.put("diagnostics",Phoenium.liveRuntime().diagnostics().toLogLine());
        report.put("fullDiagnostics",Phoenium.liveRuntime().fullDiagnostics());
        List<Map<String,Object>> entities=new ArrayList<>();
        if(!label.equals("stopped"))for(var level:server.getAllLevels())for(var entity:level.getAllEntities())if(entity.entityTags().contains("a3")) {
            Map<String,Object> e=new LinkedHashMap<>();
            e.put("uuid",entity.getUUID().toString());e.put("tags",entity.entityTags());e.put("type",entity.getClass().getName());
            e.put("world",level.dimension().identifier().toString());e.put("gameTime",level.getGameTime());
            e.put("tickCount",entity.tickCount);e.put("position",List.of(entity.getX(),entity.getY(),entity.getZ()));
            e.put("velocity",List.of(entity.getDeltaMovement().x,entity.getDeltaMovement().y,entity.getDeltaMovement().z));
            e.put("passengers",entity.getPassengers().stream().map(p->p.getUUID().toString()).toList());
            e.put("vehicle",entity.getVehicle()==null?"":entity.getVehicle().getUUID().toString());
            if(entity instanceof FullEntityFacts.Access access)e.put("incarnation",access.phoenium$entityIncarnation());
            if(entity instanceof LivingEntity living) {
                e.put("health",living.getHealth());e.put("effects",living.getActiveEffects().stream().map(effect->Map.of("type",effect.getEffect().toString(),"duration",effect.getDuration())).toList());
            }
            if(entity instanceof Mob mob){e.put("noActionTime",mob.getNoActionTime());e.put("noAi",mob.isNoAi());e.put("navigationDone",mob.getNavigation().isDone());e.put("target",mob.getTarget()==null?"":mob.getTarget().getUUID().toString());}
            if(entity instanceof AgeableMob ageable)e.put("age",ageable.getAge());
            if(entity instanceof Animal animal)e.put("inLove",animal.getInLoveTime());
            entities.add(e);
        }
        report.put("entities",entities);
        try {
            Path root=Path.of(System.getProperty("phoenium.a3.evidence","a3-evidence"));Files.createDirectories(root);
            Files.writeString(root.resolve(label+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    }
}
