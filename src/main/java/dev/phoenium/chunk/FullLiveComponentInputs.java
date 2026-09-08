package dev.phoenium.chunk;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Dependency-only views of audited, directly encoded component fields. Every method is
 * called with the exact same-world regional read claim; no serializer or resolver runs here. */
public final class FullLiveComponentInputs {
    public interface EntityAccess { Component phoenium$rawCustomName(); }
    public interface TextAccess { Component phoenium$rawDisplayText(); }
    public interface ChunkAccessView { Map<BlockPos, BlockEntity> phoenium$rawBlockEntities(); }
    public interface NamedBlockAccess { Component phoenium$rawBlockName(); }

    enum Field { CUSTOM_NAME, DISPLAY_TEXT }
    record Measure(long bytes, String unmet) { }
    record Snapshot(Component root, List<Node> nodes, long bytes,
                    FullSavedTextInputs.Requirements requirements, String unmet) {
        boolean valid(Component current) {
            if (current != root) return false;
            for (Node node : nodes) if (!node.valid()) return false;
            return true;
        }
    }
    private record Node(MutableComponent component, ComponentContents contents, Style style,
                        List<Component> siblings, Component[] siblingValues,
                        Object[] arguments, Object[] argumentValues) {
        boolean valid() {
            if (component.getContents() != contents || component.getStyle() != style
                    || component.getSiblings() != siblings || siblings.size() != siblingValues.length) return false;
            for (int i = 0; i < siblingValues.length; i++) if (siblings.get(i) != siblingValues[i]) return false;
            if (arguments != null) {
                if (!(contents instanceof TranslatableContents translated) || translated.getArgs() != arguments
                        || arguments.length != argumentValues.length) return false;
                for (int i = 0; i < argumentValues.length; i++) if (arguments[i] != argumentValues[i]) return false;
            }
            return true;
        }
    }

    private FullLiveComponentInputs() { }

    static Component entity(Entity entity, Field field) {
        return switch (field) {
            case CUSTOM_NAME -> ((EntityAccess) entity).phoenium$rawCustomName();
            case DISPLAY_TEXT -> entity instanceof TextAccess access ? access.phoenium$rawDisplayText() : null;
        };
    }
    static BlockEntity block(ChunkAccess chunk, BlockPos position) {
        return ((ChunkAccessView) chunk).phoenium$rawBlockEntities().get(position);
    }
    static Component blockName(BlockEntity block) {
        return block instanceof NamedBlockAccess access ? access.phoenium$rawBlockName() : null;
    }
    static boolean supportedBlock(BlockEntity block) { return block instanceof NamedBlockAccess; }

    /** Allocation-free scalar pass. The original resolver stops descending after depth 100. */
    static Measure measure(Component root, int depth) {
        long[] bytes = {0}; String unmet = walk(root, depth, null, bytes);
        return new Measure(bytes[0], unmet);
    }

    /** The caller reserves {@link Measure#bytes} before this detached capture. */
    static Snapshot capture(FullSavedInput.EntityFact consumer, Component root, int depth, long allowance) {
        if (root == null) return new Snapshot(null, List.of(), 32L,
                FullSavedTextInputs.discoverInterpreted(consumer, Component.empty(), 101), null);
        long[] bytes = {0}; var nodes = new ArrayList<Node>();
        String unmet = walk(root, depth, nodes, bytes);
        if (bytes[0] > allowance)
            return new Snapshot(root, List.of(), bytes[0], null, "live component grew after its admitted scalar measurement");
        var requirements = unmet == null ? FullSavedTextInputs.discoverInterpreted(consumer, root, depth) : null;
        return new Snapshot(root, List.copyOf(nodes), bytes[0], requirements, unmet);
    }

    private static String walk(Component component, int depth, List<Node> output, long[] bytes) {
        if (component == null || depth > 100) return null;
        if (component.getClass() != MutableComponent.class)
            return "live component implementation is not the audited vanilla MutableComponent: " + component.getClass().getName();
        MutableComponent mutable = (MutableComponent) component;
        ComponentContents contents = mutable.getContents(); List<Component> siblings = mutable.getSiblings(); Style style = mutable.getStyle();
        Object[] args = contents instanceof TranslatableContents translated ? translated.getArgs() : null;
        bytes[0] = Math.addExact(bytes[0], Math.addExact(128L,
                Math.addExact(Math.multiplyExact(8L, siblings.size()), args == null ? 0L : Math.multiplyExact(8L, args.length))));
        Component[] siblingCopy = null; Object[] argumentCopy = null;
        if (output != null) {
            siblingCopy = siblings.toArray(Component[]::new);
            argumentCopy = args == null ? null : args.clone();
            output.add(new Node(mutable, contents, style, siblings, siblingCopy, args, argumentCopy));
        }
        if (!(contents instanceof PlainTextContents || contents instanceof KeybindContents || contents instanceof TranslatableContents
                || contents instanceof SelectorContents || contents instanceof ScoreContents || contents instanceof NbtContents
                || contents instanceof ObjectContents))
            return "live component contributor is not audited: " + contents.getClass().getName();
        if (args != null) for (Object argument : args) if (argument instanceof Component nested) {
            String unmet = walk(nested, depth + 1, output, bytes); if (unmet != null) return unmet;
        }
        if (contents instanceof SelectorContents selector && selector.separator().isPresent()) {
            String unmet = walk(selector.separator().get(), depth + 1, output, bytes); if (unmet != null) return unmet;
        } else if (contents instanceof NbtContents nbt && nbt.separator().isPresent()) {
            String unmet = walk(nbt.separator().get(), depth + 1, output, bytes); if (unmet != null) return unmet;
        } else if (contents instanceof ObjectContents object && object.fallback().isPresent()) {
            String unmet = walk(object.fallback().get(), depth + 1, output, bytes); if (unmet != null) return unmet;
        }
        for (Component sibling : siblings) {
            String unmet = walk(sibling, depth + 1, output, bytes); if (unmet != null) return unmet;
        }
        if (style.getHoverEvent() instanceof HoverEvent.ShowText hover) return walk(hover.value(), depth + 1, output, bytes);
        return null;
    }
}
