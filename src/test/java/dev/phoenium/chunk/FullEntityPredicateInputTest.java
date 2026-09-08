package dev.phoenium.chunk;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.predicates.entity.EntitySubPredicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FullEntityPredicateInputTest {
    @BeforeAll static void bootstrap() { FullSavedVillagerInputTest.bootstrap(); }

    @Test void originalEmptyAndSingleBranchesRetainTheirActualResultAfterCallerMapMutation() {
        var map = new LinkedHashMap<Codec<? extends EntitySubPredicate>, EntitySubPredicate>();
        var empty = new EntityPredicate(map);
        var firstCalls = new AtomicInteger();
        EntitySubPredicate first = (entity, world, position) -> { firstCalls.incrementAndGet(); return true; };
        var key = key(first); map.put(key, first);
        var single = new EntityPredicate(map);
        map.put(key, (entity, world, position) -> { throw new AssertionError("replacement must never execute"); });

        assertSame(EntitySubPredicate.ALWAYS_TRUE, inputs(empty).original());
        assertEquals(List.of(EntitySubPredicate.ALWAYS_TRUE), inputs(empty).parts());
        assertSame(first, inputs(single).original()); assertEquals(List.of(first), inputs(single).parts());
        assertEquals(0, firstCalls.get(), "dependency production cannot evaluate the predicate");
        assertTrue(inputs(single).original().matches(null, null, null)); assertEquals(1, firstCalls.get());
    }

    @Test void originalSortedArrayAndShortCircuitRemainAuthoritativeAfterCallerMapReplacement() {
        var calls = new ArrayList<String>();
        EntitySubPredicate first = (entity, world, position) -> { calls.add("first"); return true; };
        EntitySubPredicate second = (entity, world, position) -> { calls.add("second"); return false; };
        EntitySubPredicate third = (entity, world, position) -> { calls.add("third"); return true; };
        var map = new LinkedHashMap<Codec<? extends EntitySubPredicate>, EntitySubPredicate>();
        map.put(key(first), first); map.put(key(second), second); map.put(key(third), third);
        var predicate = new EntityPredicate(map);
        map.clear(); map.put(key(third), third);

        assertEquals(List.of(first, second, third), inputs(predicate).parts());
        assertThrows(UnsupportedOperationException.class, () -> inputs(predicate).parts().clear());
        assertTrue(calls.isEmpty());
        assertFalse(inputs(predicate).original().matches(null, null, null));
        assertEquals(List.of("first", "second"), calls);
    }

    @Test void twoPartOriginalAndPreservesExactFailureAndUnreachedTail() {
        var failure = new IllegalArgumentException("original first failure");
        var tail = new AtomicInteger();
        EntitySubPredicate first = (entity, world, position) -> { throw failure; };
        EntitySubPredicate second = (entity, world, position) -> { tail.incrementAndGet(); return true; };
        var map = new LinkedHashMap<Codec<? extends EntitySubPredicate>, EntitySubPredicate>();
        map.put(key(first), first); map.put(key(second), second);
        var predicate = new EntityPredicate(map);
        assertEquals(List.of(first, second), inputs(predicate).parts());
        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> inputs(predicate).original().matches(null, null, null)));
        assertEquals(0, tail.get());
    }

    @Test void nestedConstructionAndFactoryFailureRestoreTheEnclosingWitness() {
        var nested = new EntityPredicate[1];
        var map = new LinkedHashMap<Codec<? extends EntitySubPredicate>, EntitySubPredicate>() {
            @Override public Set<Map.Entry<Codec<? extends EntitySubPredicate>, EntitySubPredicate>> entrySet() {
                nested[0] = new EntityPredicate(Map.of());
                return super.entrySet();
            }
        };
        EntitySubPredicate first = (entity, world, position) -> true;
        EntitySubPredicate second = (entity, world, position) -> false;
        map.put(key(first), first); map.put(key(second), second);
        var outer = new EntityPredicate(map);
        assertEquals(List.of(first, second), inputs(outer).parts());
        assertEquals(List.of(EntitySubPredicate.ALWAYS_TRUE), inputs(nested[0]).parts());
        var failure = new IllegalStateException("failed combine producer");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> new EntityPredicate(new HashMap<>() {
            @Override public boolean isEmpty() { throw failure; }
        })));
        assertEquals(List.of(EntitySubPredicate.ALWAYS_TRUE), inputs(new EntityPredicate(Map.of())).parts());
    }

    private static Codec<EntitySubPredicate> key(EntitySubPredicate value) {
        return Codec.STRING.xmap(ignored -> value, ignored -> "fixture");
    }

    private static FullEntityPredicateInputs.Produced inputs(EntityPredicate predicate) {
        var result = assertInstanceOf(FullEntityPredicateInputs.Access.class, predicate).phoenium$entityPredicateInputs();
        assertNotNull(result); return result;
    }
}
