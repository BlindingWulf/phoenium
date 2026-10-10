package dev.phoenium.chunk;

import java.lang.reflect.*;
import java.util.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.*;

/** Call-site plumbing only. Fixture reflection invokes the preserved original method. */
public final class FullFinishingServicesTestHooks {
    @SuppressWarnings("unchecked") private static <T> T original(Class<?> owner, Object receiver, String method, Class<?>[] parameters, Object... arguments) {
        try { return (T) owner.getMethod("phoenium$originalFacts$" + method, parameters).invoke(receiver, arguments); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            if (wrapped.getCause() instanceof Error error) throw error;
            throw new AssertionError(wrapped.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    // S9-D13: as FullEntityFactMixin: the getter body runs, then a foreign alias's value is replaced by its fact.
    public static UUID getUUID(Entity entity) { return FullEntityFacts.uuid(entity, (UUID) original(Entity.class, entity, "getUUID", new Class<?>[0])); }
    public static int getId(Entity entity) {
        // getId()'s two id field reads, each substituted (FullEntityFactMixin's ModifyExpressionValue).
        int raw = ((FullEntityFacts.Access) entity).phoenium$rawEntityId();
        if (FullEntityFacts.idField(entity, raw) == 0) throw new IllegalStateException("Tried to access entity ID before ID assignment");
        return FullEntityFacts.idField(entity, raw);
    }
    public static boolean isRemoved(Entity entity) { return FullEntityFacts.removed(entity, (boolean) (Boolean) original(Entity.class, entity, "isRemoved", new Class<?>[0])); }
    public static String getScoreboardName(Entity entity) { return FullEntityFacts.scoreboardName(entity, (String) original(Entity.class, entity, "getScoreboardName", new Class<?>[0])); }
    @SuppressWarnings("unchecked") public static Holder<EntityType<?>> typeHolder(Entity entity) {
        return FullEntityFacts.type(entity, (Holder<EntityType<?>>) original(Entity.class, entity, "typeHolder", new Class<?>[0]));
    }
    private static void write(Entity entity, String method, Class<?>[] parameters, Object... args) {
        try (var turn = FullEntityFacts.writer(entity)) { try { original(Entity.class, entity, method, parameters, args); } finally { FullEntityFacts.publish(entity); } }
    }
    public static void setId(Entity entity, int value) { write(entity, "setId", new Class<?>[]{int.class}, value); }
    public static void setUUID(Entity entity, UUID value) { write(entity, "setUUID", new Class<?>[]{UUID.class}, value); }
    public static void setInvulnerable(Entity entity, boolean value) { write(entity, "setInvulnerable", new Class<?>[]{boolean.class}, value); }
    public static void setLevel(Entity entity, Level value) { write(entity, "setLevel", new Class<?>[]{Level.class}, value); }
    public static void setRemoved(Entity entity, Entity.RemovalReason reason) { write(entity, "setRemoved", new Class<?>[]{Entity.RemovalReason.class}, reason); }
    public static void unsetRemoved(Entity entity) { write(entity, "unsetRemoved", new Class<?>[0]); }
    public static boolean enemy(LivingEntity entity) { return FullEntityFacts.enemy(entity, entity::canBeSeenAsEnemy); }
    public static boolean isCreative(Player entity) { return FullEntityFacts.creative(entity, entity::isCreative); }
    public static boolean isSpectator(Player entity) { return FullEntityFacts.spectator(entity, entity::isSpectator); }
    public static Entity levelLookup(ServerLevel level, UUID id) {
        return FullFinishingServices.finishing() ? FullFinishingServices.forServer(level.getServer()).entityFacts.lookup(level, id)
                : original(ServerLevel.class, level, "getEntityInAnyDimension", new Class<?>[]{UUID.class}, id);
    }
    public static Player playerLookup(ServerLevel level, UUID id) {
        return FullFinishingServices.finishing() ? FullFinishingServices.forServer(level.getServer()).scoreboard.lookupPlayer(id)
                : original(ServerLevel.class, level, "getPlayerInAnyDimension", new Class<?>[]{UUID.class}, id);
    }
    public static void lookupAdd(EntityLookup<?> lookup, EntityAccess value) { lookupBody(lookup, value, "add"); }
    public static void lookupRemoveBody(EntityLookup<?> lookup, EntityAccess value) { lookupBody(lookup, value, "remove"); }
    private static void lookupBody(EntityLookup<?> lookup, EntityAccess value, String method) {
        try (var turn = value instanceof Entity entity && entity.level() instanceof ServerLevel level
                ? FullFinishingServices.forServer(level.getServer()).referenceAccess() : null) {
            original(EntityLookup.class, lookup, method, new Class<?>[]{EntityAccess.class}, value);
        }
    }
    public static Object lookupPut(Map<Object, Object> map, Object key, Object value) {
        Object before = map.put(key, value); if (value instanceof Entity entity) FullEntityFacts.inserted(entity); return before;
    }
    public static Object lookupRemove(Map<Object, Object> map, Object key) {
        Object before = map.remove(key); if (before instanceof Entity entity) FullEntityFacts.removed(entity, (UUID) key); return before;
    }
    public static Object lookupIntPut(Int2ObjectMap<Object> map, int id, Object value) {
        Object before = map.put(id, value);
        if (value instanceof Entity entity) FullEntityFacts.indexed(entity, id, before instanceof Entity prior ? prior : null);
        return before;
    }
    public static Object lookupIntRemove(Int2ObjectMap<Object> map, int id) {
        Object before = map.remove(id);
        if (before instanceof Entity entity) FullEntityFacts.unindexed(entity, id);
        return before;
    }
}
