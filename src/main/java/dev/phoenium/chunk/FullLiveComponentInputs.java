package dev.phoenium.chunk;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Dependency-only views of audited, directly encoded component fields. Every method is
 * called with the exact same-world regional read claim; no serializer or resolver runs here. */
public final class FullLiveComponentInputs {
    public interface EntityAccess { Component phoenium$rawCustomName(); }
    public interface TextAccess { Component phoenium$rawDisplayText(); }
    public interface MannequinAccess { Component phoenium$rawDescription(); }
    public interface ChunkAccessView { Map<BlockPos, BlockEntity> phoenium$rawBlockEntities(); }
    public interface NamedBlockAccess { Component phoenium$rawBlockName(); }
    public interface CommandBlockAccess { BaseCommandBlock phoenium$rawCommandBlock(); }
    public interface CommandAccess {
        Component phoenium$rawCommandName();
        Component phoenium$rawLastOutput();
        boolean phoenium$rawTrackOutput();
    }
    public interface SignAccess {
        SignText phoenium$rawFrontText();
        SignText phoenium$rawBackText();
    }
    public interface SignTextAccess {
        Component[] phoenium$rawMessages();
        Component[] phoenium$rawFilteredMessages();
    }

    enum Field { CUSTOM_NAME, DISPLAY_TEXT, MANNEQUIN_DESCRIPTION }
    enum BlockField {
        CUSTOM_NAME, COMMAND_LAST_OUTPUT,
        SIGN_FRONT_MESSAGES, SIGN_FRONT_FILTERED_MESSAGES,
        SIGN_BACK_MESSAGES, SIGN_BACK_FILTERED_MESSAGES
    }
    record Measure(long bytes, String unmet) { }
    record Snapshot(Component root, List<Node> nodes, long bytes,
                    FullSavedTextInputs.Requirements requirements, String unmet) {
        boolean valid(Component current) {
            if (current != root) return false;
            for (Node node : nodes) if (!node.valid()) return false;
            return true;
        }
    }
    record BlockSnapshot(BlockEntity block, BlockField field, Object fieldOwner,
                         boolean presence, Object firstArray, Object secondArray,
                         Component[] firstValues, Component[] secondValues,
                         List<Snapshot> components, long bytes,
                         List<FullSavedTextInputs.Requirements> requirements, String unmet) {
        boolean valid(BlockEntity current) {
            if (current != block) return false;
            if (current == null) return components.isEmpty();
            return switch (field) {
                case CUSTOM_NAME -> {
                    Component value;
                    Object owner;
                    if (current instanceof CommandBlockAccess blockAccess) {
                        owner = blockAccess.phoenium$rawCommandBlock();
                        value = owner instanceof CommandAccess command ? command.phoenium$rawCommandName() : null;
                    } else {
                        owner = current;
                        value = current instanceof NamedBlockAccess access ? access.phoenium$rawBlockName() : null;
                    }
                    yield owner == fieldOwner && validComponents(Collections.singletonList(value));
                }
                case COMMAND_LAST_OUTPUT -> {
                    Object owner = current instanceof CommandBlockAccess access ? access.phoenium$rawCommandBlock() : null;
                    boolean tracked = owner instanceof CommandAccess command && command.phoenium$rawTrackOutput();
                    Component value = tracked ? ((CommandAccess) owner).phoenium$rawLastOutput() : null;
                    yield owner == fieldOwner && tracked == presence && validComponents(Collections.singletonList(value));
                }
                case SIGN_FRONT_MESSAGES, SIGN_FRONT_FILTERED_MESSAGES,
                     SIGN_BACK_MESSAGES, SIGN_BACK_FILTERED_MESSAGES -> {
                    SignText text = signText(current, field);
                    if (text != fieldOwner) yield false;
                    if (text == null) yield components.isEmpty() && firstArray == null && secondArray == null;
                    if (!(text instanceof SignTextAccess access)) yield false;
                    Component[] messages = access.phoenium$rawMessages();
                    Component[] filtered = access.phoenium$rawFilteredMessages();
                    List<Component> roots = messages.length < 4 || filtered.length < 4
                            ? List.of() : signComponents(field, messages, filtered);
                    yield messages == firstArray && filtered == secondArray
                            && same(messages, firstValues) && same(filtered, secondValues)
                            && validComponents(roots);
                }
            };
        }
        private boolean validComponents(List<Component> current) {
            if (current.size() != components.size()) return false;
            for (int i = 0; i < current.size(); i++) if (!components.get(i).valid(current.get(i))) return false;
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
            case MANNEQUIN_DESCRIPTION -> entity instanceof MannequinAccess access ? access.phoenium$rawDescription() : null;
        };
    }
    static BlockEntity block(ChunkAccess chunk, BlockPos position) {
        return ((ChunkAccessView) chunk).phoenium$rawBlockEntities().get(position);
    }
    static boolean supportedBlock(BlockEntity block, BlockField field) {
        if (block == null) return true;
        return switch (field) {
            case CUSTOM_NAME -> block instanceof NamedBlockAccess || block instanceof CommandBlockAccess access
                    && access.phoenium$rawCommandBlock() instanceof CommandAccess;
            case COMMAND_LAST_OUTPUT -> block instanceof CommandBlockAccess access
                    && access.phoenium$rawCommandBlock() instanceof CommandAccess;
            case SIGN_FRONT_MESSAGES, SIGN_FRONT_FILTERED_MESSAGES,
                 SIGN_BACK_MESSAGES, SIGN_BACK_FILTERED_MESSAGES -> block instanceof SignAccess;
        };
    }

    static BlockField blockField(String path) {
        List<String> fields = FullNbtPathInputs.leadingFields(path, 2);
        if (!fields.isEmpty() && fields.getFirst().equals("CustomName")) return BlockField.CUSTOM_NAME;
        if (!fields.isEmpty() && fields.getFirst().equals("LastOutput")) return BlockField.COMMAND_LAST_OUTPUT;
        if (fields.size() < 2) return null;
        if (fields.get(0).equals("front_text") && fields.get(1).equals("messages")) return BlockField.SIGN_FRONT_MESSAGES;
        if (fields.get(0).equals("front_text") && fields.get(1).equals("filtered_messages")) return BlockField.SIGN_FRONT_FILTERED_MESSAGES;
        if (fields.get(0).equals("back_text") && fields.get(1).equals("messages")) return BlockField.SIGN_BACK_MESSAGES;
        if (fields.get(0).equals("back_text") && fields.get(1).equals("filtered_messages")) return BlockField.SIGN_BACK_FILTERED_MESSAGES;
        return null;
    }

    static Measure measureBlock(BlockEntity block, BlockField field, int depth) {
        if (block == null) return new Measure(32L, null);
        if (!supportedBlock(block, field)) return new Measure(32L,
                "instantiated block component producer is not audited for " + block.getClass().getName() + " and " + field);
        var roots = blockComponents(block, field);
        long bytes = 64L;
        for (Component component : roots) {
            Measure measured = measure(component, depth);
            if (measured.unmet() != null) return new Measure(bytes, measured.unmet());
            bytes = Math.addExact(bytes, measured.bytes());
        }
        if (isSign(field)) {
            SignText text = signText(block, field);
            if (text != null) {
                SignTextAccess access = (SignTextAccess) text;
                bytes = Math.addExact(bytes, Math.addExact(Math.multiplyExact(8L, Math.min(4, access.phoenium$rawMessages().length)),
                        Math.multiplyExact(8L, Math.min(4, access.phoenium$rawFilteredMessages().length))));
            }
        }
        return new Measure(bytes, null);
    }

    /** Rechecks the scalar bound before cloning arrays or component structure. */
    static BlockSnapshot captureBlock(FullSavedInput.EntityFact consumer, BlockEntity block,
                                      BlockField field, int depth, long allowance) {
        Measure measured = measureBlock(block, field, depth);
        if (measured.unmet() != null) return new BlockSnapshot(block, field, null, false, null, null,
                null, null, List.of(), measured.bytes(), List.of(), measured.unmet());
        if (measured.bytes() > allowance) return new BlockSnapshot(block, field, null, false, null, null,
                null, null, List.of(), measured.bytes(), List.of(), "live component grew after its admitted scalar measurement");
        if (block == null) return new BlockSnapshot(null, field, null, false, null, null,
                null, null, List.of(), measured.bytes(), List.of(), null);

        Object owner = block; boolean presence = true; Object first = null, second = null;
        Component[] firstValues = null, secondValues = null;
        if (field == BlockField.CUSTOM_NAME && block instanceof CommandBlockAccess access) owner = access.phoenium$rawCommandBlock();
        else if (field == BlockField.COMMAND_LAST_OUTPUT) {
            owner = ((CommandBlockAccess) block).phoenium$rawCommandBlock();
            presence = ((CommandAccess) owner).phoenium$rawTrackOutput();
        } else if (isSign(field)) {
            owner = signText(block, field);
            if (owner != null) {
                SignTextAccess access = (SignTextAccess) owner;
                Component[] messages = access.phoenium$rawMessages(), filtered = access.phoenium$rawFilteredMessages();
                first = messages; second = filtered;
                firstValues = Arrays.copyOf(messages, Math.min(4, messages.length));
                secondValues = Arrays.copyOf(filtered, Math.min(4, filtered.length));
            }
        }
        var roots = blockComponents(block, field); var snapshots = new ArrayList<Snapshot>();
        var requirements = new ArrayList<FullSavedTextInputs.Requirements>(); long capturedBytes = 64L;
        if (isSign(field) && firstValues != null) capturedBytes = Math.addExact(capturedBytes,
                Math.addExact(Math.multiplyExact(8L, firstValues.length), Math.multiplyExact(8L, secondValues.length)));
        for (Component root : roots) {
            Snapshot snapshot = capture(consumer, root, depth, Math.max(0L, Math.subtractExact(allowance, capturedBytes)));
            if (snapshot.unmet() != null) return new BlockSnapshot(block, field, owner, presence, first, second,
                    firstValues, secondValues, List.of(), measured.bytes(), List.of(), snapshot.unmet());
            capturedBytes = Math.addExact(capturedBytes, snapshot.bytes()); snapshots.add(snapshot);
            requirements.add(snapshot.requirements());
        }
        var result = new BlockSnapshot(block, field, owner, presence, first, second, firstValues, secondValues,
                List.copyOf(snapshots), capturedBytes, List.copyOf(requirements), null);
        if (capturedBytes != measured.bytes() || !result.valid(block))
            throw new ChunkProtocolException("live block component changed inside its exact regional dependency read");
        return result;
    }

    private static List<Component> blockComponents(BlockEntity block, BlockField field) {
        return switch (field) {
            case CUSTOM_NAME -> {
                Component value;
                if (block instanceof CommandBlockAccess access)
                    value = ((CommandAccess) access.phoenium$rawCommandBlock()).phoenium$rawCommandName();
                else value = ((NamedBlockAccess) block).phoenium$rawBlockName();
                yield Collections.singletonList(value);
            }
            case COMMAND_LAST_OUTPUT -> {
                CommandAccess command = (CommandAccess) ((CommandBlockAccess) block).phoenium$rawCommandBlock();
                yield Collections.singletonList(command.phoenium$rawTrackOutput() ? command.phoenium$rawLastOutput() : null);
            }
            case SIGN_FRONT_MESSAGES, SIGN_FRONT_FILTERED_MESSAGES,
                 SIGN_BACK_MESSAGES, SIGN_BACK_FILTERED_MESSAGES -> {
                SignText text = signText(block, field);
                if (text == null) yield List.of();
                SignTextAccess access = (SignTextAccess) text;
                Component[] messages = access.phoenium$rawMessages(), filtered = access.phoenium$rawFilteredMessages();
                // The pinned codec indexes slots 0..3 directly. Short arrays retain its
                // original failure; entries beyond index 3 never contribute to its output.
                yield messages.length < 4 || filtered.length < 4 ? List.of() : signComponents(field, messages, filtered);
            }
        };
    }
    private static List<Component> signComponents(BlockField field, Component[] messages, Component[] filtered) {
        if (field == BlockField.SIGN_FRONT_MESSAGES || field == BlockField.SIGN_BACK_MESSAGES)
            return Arrays.asList(messages).subList(0, 4);
        if (field != BlockField.SIGN_FRONT_FILTERED_MESSAGES && field != BlockField.SIGN_BACK_FILTERED_MESSAGES)
            throw new IllegalArgumentException("not a sign field: " + field);
        // filtered_messages is omitted when each filtered component equals its raw peer,
        // so both live graphs contribute to the selected field's presence and exact value.
        return new AbstractList<>() {
            @Override public Component get(int index) {
                return index < 4 ? filtered[index] : messages[index - 4];
            }
            @Override public int size() { return 8; }
        };
    }
    private static SignText signText(BlockEntity block, BlockField field) {
        if (!(block instanceof SignAccess access)) return null;
        return switch (field) {
            case SIGN_FRONT_MESSAGES, SIGN_FRONT_FILTERED_MESSAGES -> access.phoenium$rawFrontText();
            case SIGN_BACK_MESSAGES, SIGN_BACK_FILTERED_MESSAGES -> access.phoenium$rawBackText();
            default -> null;
        };
    }
    private static boolean isSign(BlockField field) {
        return switch (field) {
            case SIGN_FRONT_MESSAGES, SIGN_FRONT_FILTERED_MESSAGES,
                 SIGN_BACK_MESSAGES, SIGN_BACK_FILTERED_MESSAGES -> true;
            default -> false;
        };
    }
    private static boolean same(Component[] current, Component[] expected) {
        if (current == null || expected == null || current.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (current[i] != expected[i]) return false;
        return true;
    }

    /** Allocation-free scalar pass. The original resolver stops descending after depth 100. */
    static Measure measure(Component root, int depth) {
        long[] bytes = {0}; String unmet = walk(root, depth, null, bytes);
        return new Measure(Math.max(32L, bytes[0]), unmet);
    }

    /** The caller reserves {@link Measure#bytes} before this detached capture. */
    static Snapshot capture(FullSavedInput.EntityFact consumer, Component root, int depth, long allowance) {
        if (root == null) return allowance < 32L
                ? new Snapshot(null, List.of(), 32L, null, "live component grew after its admitted scalar measurement")
                : new Snapshot(null, List.of(), 32L,
                FullSavedTextInputs.discoverInterpreted(consumer, Component.empty(), 101), null);
        Measure measured = measure(root, depth);
        if (measured.unmet() != null) return new Snapshot(root, List.of(), measured.bytes(), null, measured.unmet());
        if (measured.bytes() > allowance)
            return new Snapshot(root, List.of(), measured.bytes(), null, "live component grew after its admitted scalar measurement");
        long[] bytes = {0}; var nodes = new ArrayList<Node>();
        String unmet = walk(root, depth, nodes, bytes);
        if (bytes[0] != measured.bytes())
            throw new ChunkProtocolException("live component changed inside its exact regional dependency read (depth="
                    + depth + ", measured=" + measured.bytes() + ", captured=" + bytes[0] + ")");
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
