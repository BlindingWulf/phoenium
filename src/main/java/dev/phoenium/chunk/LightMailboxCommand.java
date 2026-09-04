package dev.phoenium.chunk;

import java.util.Objects;
import net.minecraft.util.thread.StrictQueue;

/** Only wraps the original light task at the exact ingress/dispatcher call sites. */
public final class LightMailboxCommand implements Runnable {
    public record Witness(LightInputService.OccurrenceId occurrence, LightInputService.Insertion insertion) {
        public Witness { Objects.requireNonNull(occurrence); Objects.requireNonNull(insertion); }
    }
    private final Runnable original;
    private final Witness witness;
    public LightMailboxCommand(Runnable original, Witness witness) {
        this.original = Objects.requireNonNull(original); this.witness = Objects.requireNonNull(witness);
    }
    @Override public void run() { original.run(); }
    public static Witness witness(Runnable task) {
        if (task instanceof StrictQueue.RunnableWithPriority priority) task = priority.task();
        return task instanceof LightMailboxCommand command ? command.witness : null;
    }
}
