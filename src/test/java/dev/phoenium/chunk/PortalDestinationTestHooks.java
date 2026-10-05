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
    static dev.phoenium.live.FallingBlockPortalTransitionManager falling;
    static dev.phoenium.live.ArmorStandPortalTransitionManager armorStands;
    static java.util.function.Consumer<net.minecraft.world.entity.item.FallingBlockEntity> fallingTail;
    public static void fallingTail(net.minecraft.world.entity.item.FallingBlockEntity block) {
        if(fallingTail!=null)fallingTail.accept(block);
    }
    static Object pearls;
    static net.minecraft.world.phys.HitResult controlledHit;
    static Entity controlledBase;
    static java.util.function.Consumer<net.minecraft.world.entity.monster.cubemob.AbstractCubeMob> cubeTail;
    public static void cubeTail(net.minecraft.world.entity.monster.cubemob.AbstractCubeMob cube) {
        if (cubeTail != null) cubeTail.accept(cube);
    }
    static java.util.function.Consumer<net.minecraft.world.entity.animal.equine.AbstractHorse> horseTail;
    public static void horseTail(net.minecraft.world.entity.animal.equine.AbstractHorse horse) {
        if(horseTail!=null)horseTail.accept(horse);
    }
    /** Lifted reviewed outer tail observed at its first instruction: (receiver, "Owner.resumeName", captured int or -1). */
    static java.util.function.BiConsumer<Object,String> outerTail;
    static java.util.List<Object[]> outerTails;
    public static void outerTail(Object self,String id,int captured) {
        if(outerTails!=null)outerTails.add(new Object[]{self,id,captured});
        if(outerTail!=null)outerTail.accept(self,id);
    }
    public static void beginMob(Mob mob) { if (mobs != null) mobs.begin(mob); }
    public static void beginFalling(net.minecraft.world.entity.item.FallingBlockEntity block) {
        if(falling!=null)falling.begin(block);
    }
    public static boolean completeFalling(net.minecraft.world.entity.item.FallingBlockEntity block,
            net.minecraft.world.level.block.Block originalBlock) {
        return falling!=null && falling.complete(block,originalBlock);
    }
    public static void finishFalling(net.minecraft.world.entity.item.FallingBlockEntity block) {
        if(falling!=null)falling.finish(block);
    }
    public static void endPlatform(net.minecraft.world.level.ServerLevelAccessor destination,
            net.minecraft.core.BlockPos origin,boolean dropResources,Entity entity) {
        dev.phoenium.live.PortalDestinationObservation.endPlatform(entity,(net.minecraft.server.level.ServerLevel)destination,
            origin,()->net.minecraft.world.level.levelgen.feature.EndPlatformFeature.createEndPlatform(destination,origin,dropResources));
    }
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
        if(armorStands!=null && (entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                || entity instanceof net.minecraft.world.entity.decoration.Mannequin))
            return armorStands.suspendDestination((net.minecraft.world.entity.LivingEntity)entity,entity.portalProcess);
        if(falling!=null && entity instanceof net.minecraft.world.entity.item.FallingBlockEntity block)
            return falling.suspendDestination(block,block.portalProcess);
        if(mobs!=null && entity instanceof Mob mob) return mobs.suspendDestination(mob,mob.portalProcess);
        if(pearls!=null && entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl)
            return (boolean)pearlCall("suspendDestination",entity,entity.portalProcess);
        if(items==null || !(entity instanceof ItemEntity item) || !itemCutIsEarly()) return false;
        try { return (boolean)items.getClass().getMethod("suspendDestination",ItemEntity.class,PortalProcessor.class).invoke(items,item,item.portalProcess); }
        catch(java.lang.reflect.InvocationTargetException e) { if(e.getCause() instanceof RuntimeException r) throw r; throw new AssertionError(e.getCause()); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    public static boolean suspended(Entity entity) {
        if(armorStands!=null && (entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                || entity instanceof net.minecraft.world.entity.decoration.Mannequin))
            return armorStands.suspended((net.minecraft.world.entity.LivingEntity)entity);
        if(falling!=null && entity instanceof net.minecraft.world.entity.item.FallingBlockEntity block)
            return falling.suspended(block);
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
