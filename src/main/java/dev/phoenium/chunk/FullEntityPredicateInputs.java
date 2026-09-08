package dev.phoenium.chunk;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.advancements.predicates.entity.EntitySubPredicate;

/** Records the exact inputs selected by EntityPredicate's original combine call. The
 * constructor's caller-owned map is not evidence: vanilla may already have captured
 * different values. These records belong to their predicate instance, with no registry
 * of historical constructions and no second evaluation or reordered combination. */
public final class FullEntityPredicateInputs {
    public interface Access { Produced phoenium$entityPredicateInputs(); }
    public record Produced(EntitySubPredicate original, List<EntitySubPredicate> parts) { }
    private static final ThreadLocal<Frame> BUILDING = new ThreadLocal<>();
    private static final class Frame { EntitySubPredicate[] array; }

    private FullEntityPredicateInputs() { }

    public static Produced capture(Supplier<EntitySubPredicate> original) {
        Frame parent = BUILDING.get(), frame = new Frame();
        BUILDING.set(frame);
        try {
            EntitySubPredicate result = original.get();
            // Empty and single-element maps return the actual constant/element. For the
            // other branch retain the very array returned by vanilla's sorted stream.
            List<EntitySubPredicate> parts = frame.array == null ? List.of(result)
                    : Collections.unmodifiableList(Arrays.asList(frame.array));
            return new Produced(result, parts);
        } finally {
            if (parent == null) BUILDING.remove(); else BUILDING.set(parent);
        }
    }

    /** Exact original Stream.toArray result; returns the same array to vanilla. */
    public static Object[] selectedArray(Object[] array) {
        Frame frame = BUILDING.get();
        if (frame != null) {
            if (frame.array != null) throw new ChunkProtocolException("entity predicate combined its input array twice");
            frame.array = (EntitySubPredicate[]) Objects.requireNonNull(array);
        }
        return array;
    }
}
