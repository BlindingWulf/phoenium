package dev.phoenium.chunk;

import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

public final class NativePlayerPortalTestHooks {
    static Object dimensions,handoff;
    static java.util.function.Consumer<ServerPlayer> damage=p->{};
    static java.util.function.Consumer<ThrownEnderpearl> effects=p->{};
    static java.util.function.Consumer<ServerPlayer> trigger=p->{};
    public static Entity owner(Entity nativeOwner) { return handoff==null ? nativeOwner : (Entity)PortalDestinationTestHooks.invoke(handoff,"currentOwner",nativeOwner); }
    public static boolean capture(ThrownEnderpearl pearl,ServerLevel level,Vec3 position,ServerPlayer player) {
        return handoff!=null && (boolean)PortalDestinationTestHooks.invoke(handoff,"capture",pearl,level,position,player);
    }
    public static boolean suspend(ServerPlayer player) { return dimensions!=null && (boolean)PortalDestinationTestHooks.invoke(dimensions,"suspendAfterSourceRemoval",player); }
    public static ServerPlayer teleport(ServerPlayer player,TeleportTransition transition) {
        boolean began=dimensions!=null && (boolean)PortalDestinationTestHooks.invoke(dimensions,"begin",player,transition);
        try {
            var method=ServerPlayer.class.getMethod("phoenium$testNativePlayerTeleport",TeleportTransition.class);
            return (ServerPlayer)method.invoke(player,transition);
        } catch(java.lang.reflect.InvocationTargetException e) { if(e.getCause() instanceof RuntimeException r) throw r; if(e.getCause() instanceof Error r) throw r; throw new AssertionError(e.getCause()); }
        catch(ReflectiveOperationException e) {throw new AssertionError(e);}
        finally { if(dimensions!=null) PortalDestinationTestHooks.invoke(dimensions,"finishNativeCall",player,began); }
    }
    public static void damage(ThrownEnderpearl pearl,ServerPlayer player) { damage.accept(player); }
    public static void effects(ThrownEnderpearl pearl,ServerLevel level,Vec3 position) { effects.accept(pearl); pearl.discard(); }
    public static void hitSuffix(ThrownEnderpearl pearl,ServerPlayer player,ServerLevel level,Vec3 position) {damage(pearl,player);effects(pearl,level,position);}
    public static void trigger(ServerPlayer player,ServerLevel oldLevel) {trigger.accept(player);}
}
