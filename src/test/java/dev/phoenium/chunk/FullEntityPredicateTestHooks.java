package dev.phoenium.chunk;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.predicates.entity.EntitySubPredicate;

public final class FullEntityPredicateTestHooks {
    private FullEntityPredicateTestHooks() { }
    public static EntitySubPredicate capture(Map<?, ?> parts, EntityPredicate instance) {
        var captured = FullEntityPredicateInputs.capture(() -> {
            try {
                var method = EntityPredicate.class.getDeclaredMethod("phoenium$originalCombine", Map.class);
                method.setAccessible(true);
                return (EntitySubPredicate) method.invoke(null, parts);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new AssertionError(failure.getCause());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        try {
            var field = EntityPredicate.class.getDeclaredField("phoenium$entityInputs");
            field.setAccessible(true); field.set(instance, captured);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return captured.original();
    }
}
