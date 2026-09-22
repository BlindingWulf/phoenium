package dev.phoenium.chunk;

import dev.phoenium.live.ItemPortalTransitionManager;
import java.util.Arrays;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class PortalDestinationTestHooks {
    static ItemPortalTransitionManager items;
    static dev.phoenium.live.MobPortalTransitionManager mobs;
    static Object pearls;
    static net.minecraft.world.phys.HitResult controlledHit;
    static Entity controlledBase;
    static java.util.function.Consumer<net.minecraft.world.entity.monster.cubemob.AbstractCubeMob> cubeTail;
    public static void cubeTail(net.minecraft.world.entity.monster.cubemob.AbstractCubeMob cube) {
        if (cubeTail != null) cubeTail.accept(cube);
    }
    public static void beginMob(Mob mob) { if (mobs != null) mobs.begin(mob); }
    public static void completeMob(Mob mob) { if (mobs != null) mobs.complete(mob); }
    public static void finishMob(Mob mob) { if (mobs != null) mobs.finish(mob); }
    public static boolean itemCutIsEarly() {
        try (var stream=PortalDestinationTestHooks.class.getResourceAsStream("/dev/phoenium/mixin/ItemPortalEntityMixin.class")) {
            var node=new ClassNode(); new ClassReader(stream).accept(node,ClassReader.SKIP_CODE);
            for(var method:node.methods) if(method.name.equals("phoenium$retainItemPortal"))
                for(var annotation:method.visibleAnnotations) if(annotation.desc.endsWith("/Inject;"))
                    return atTarget(annotation).contains("getPortalDestination");
            throw new AssertionError("production item injection absent");
        } catch(java.io.IOException failure) { throw new AssertionError(failure); }
    }
    private static String atTarget(AnnotationNode annotation) {
        for(int i=0;i<annotation.values.size();i+=2) if(annotation.values.get(i).equals("at")) {
            var ats=(java.util.List<?>)annotation.values.get(i+1);
            return ((AnnotationNode)ats.getFirst()).values.toString();
        }
        throw new AssertionError("production invocation selector absent");
    }
    public static boolean capture(Entity entity) {
        if(mobs!=null && entity instanceof Mob mob) return mobs.suspendDestination(mob,mob.portalProcess);
        if(pearls!=null && entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl)
            return (boolean)pearlCall("suspendDestination",entity,entity.portalProcess);
        if(items==null || !(entity instanceof ItemEntity item) || !itemCutIsEarly()) return false;
        try { return (boolean)items.getClass().getMethod("suspendDestination",ItemEntity.class,PortalProcessor.class).invoke(items,item,item.portalProcess); }
        catch(java.lang.reflect.InvocationTargetException e) { if(e.getCause() instanceof RuntimeException r) throw r; throw new AssertionError(e.getCause()); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    public static boolean suspended(Entity entity) {
        if(mobs!=null && entity instanceof Mob mob) return mobs.suspended(mob);
        if(pearls!=null && entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl) return (boolean)pearlCall("suspended",entity);
        return items!=null && entity instanceof ItemEntity item && items.suspended(item);
    }
    public static boolean complete(Entity entity) {
        if(entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl) {
            if(pearls!=null && (boolean)pearlCall("completeTick",entity)) return true;
            if(NativePlayerPortalTestHooks.handoff!=null && (boolean)invoke(NativePlayerPortalTestHooks.handoff,"pending",entity)) return true;
        }
        return items!=null && entity instanceof ItemEntity item && items.complete(item);
    }
    public static boolean returnBase(Entity entity) {
        if(suspended(entity)) return true;
        if(controlledBase==entity) { NativePortalTestHooks.baseSuffix(entity); return true; }
        return false;
    }
    public static void beginPearl(Entity entity,int x,int z,Entity owner) { if(pearls!=null) pearlCall("beginTick",entity,x,z,owner); }
    public static net.minecraft.world.phys.HitResult hitResult(Entity entity,java.util.function.Predicate<Entity> predicate) {
        var result=controlledHit==null ? net.minecraft.world.entity.projectile.ProjectileUtil.getHitResultOnMoveVector(entity,predicate) : controlledHit;
        if(pearls!=null) pearlCall("retainHitResult",entity,result); return result;
    }
    public static void blockEffects(Entity entity) { if(controlledHit==null) { try { var m=Entity.class.getDeclaredMethod("applyEffectsFromBlocks"); m.setAccessible(true); m.invoke(entity); } catch(ReflectiveOperationException e) { throw new AssertionError(e); } } }
    static Object pearlCall(String name,Object... args) { return invoke(pearls,name,args); }
    static Object invoke(Object target,String name,Object... args) {
        try {
            var method=Arrays.stream(target.getClass().getDeclaredMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
            method.setAccessible(true); return method.invoke(target,args);
        } catch(java.lang.reflect.InvocationTargetException e) { if(e.getCause() instanceof RuntimeException r) throw r; if(e.getCause() instanceof Error r) throw r; throw new AssertionError(e.getCause()); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
