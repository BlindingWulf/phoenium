package dev.phoenium.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.entity.EntityTickList;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

final class RegionalEntityTickLifecycleTest {
    @Test
    void removalDuringTickListIterationIsFilteredAtTheNativeConsumerBoundary() throws Exception {
        Entity first = entity(101);
        Entity removedDuringIteration = entity(102);
        Field removalReason = entityField("removalReason");
        EntityTickList entities = new EntityTickList();
        entities.add(first);
        entities.add(removedDuringIteration);
        List<Integer> admitted = new ArrayList<>();

        RegionalChunkGameplayService.forEachLiveEntityTickAlias(entities, entity -> {
            admitted.add(entity.getId());
            if (entity == first) {
                setUnchecked(removalReason, removedDuringIteration, Entity.RemovalReason.DISCARDED);
                entities.remove(removedDuringIteration);
            }
        });

        assertEquals(List.of(101), admitted,
            "the old EntityTickList snapshot must not re-admit an entity removed by an earlier tick");
    }

    @Test
    void entityDiscardedByQueuedOwnerWorkBeforeItsOwnerTurnIsSkipped() throws Exception {
        Entity arrow = entity(103);
        org.junit.jupiter.api.Assertions.assertFalse(RegionalChunkGameplayService.removedBeforeOwnerTurn(arrow));
        // A cap discard queued by an earlier entity's tick runs first inside this entity's drain.
        setUnchecked(entityField("removalReason"), arrow, Entity.RemovalReason.DISCARDED);
        org.junit.jupiter.api.Assertions.assertTrue(RegionalChunkGameplayService.removedBeforeOwnerTurn(arrow),
            "a removed entity's owner turn must be skipped, as ServerLevel's consumer skips it");
    }

    private static Entity entity(int id) throws Exception {
        Entity entity = (Entity) unsafe().allocateInstance(ExperienceOrb.class);
        set(entityField("id"), entity, id);
        return entity;
    }

    private static Field entityField(String name) throws Exception {
        Field field = Entity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Field field, Object target, Object value) throws Exception {
        Unsafe unsafe = unsafe();
        long offset = unsafe.objectFieldOffset(field);
        if (field.getType() == int.class) unsafe.putInt(target, offset, (Integer) value);
        else unsafe.putObject(target, offset, value);
    }

    private static void setUnchecked(Field field, Object target, Object value) {
        try {
            set(field, target, value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
