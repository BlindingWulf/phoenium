package dev.phoenium.chunk;

import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

final class FullSavedTextInputTest {
    @Test void selectorOptionScanDoesNotTreatNestedNbtTextAsARegisteredPredicate() {
        assertTrue(FullSavedTextInputs.hasOption("@e[distance=..32,predicate=minecraft:ready]", "predicate"));
        assertFalse(FullSavedTextInputs.hasOption("@s[nbt={data:{text:\"x,predicate=minecraft:false\"}}]", "predicate"));
        assertEquals(List.of("!minecraft:first", "phoenium:second"), FullSavedTextInputs.optionValues(
                "@e[nbt={text:\"predicate=hidden\"},predicate=!minecraft:first,predicate=phoenium:second]", "predicate"));
    }
    @BeforeAll static void bootstrap() { FullSavedVillagerInputTest.bootstrap(); }

    @Test void originalTextReaderRetainsLiteralSiblingsAndHoverWithoutWorldResolution() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var source = text();
        source.store("text", ComponentSerialization.CODEC, Component.literal("saved ").append(Component.literal("display"))
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("hover")))));
        f.proto.getEntities().clear(); f.proto.addEntity(source);
        var tracker = new FullSavedInput(); var fact = tracker.capture(List.of(source)).entities().getFirst();
        var requirements = FullSavedTextInputs.discover(fact, f.level.registryAccess());
        assertNull(requirements.unmet()); assertFalse(requirements.needsContext()); assertEquals(0, f.nextId);
        f.run();
        assertEquals(1, f.inserted.size());
        var loaded = assertInstanceOf(Display.TextDisplay.class, f.inserted.getFirst());
        var loadedText = FullSavedContextTestHooks.text(loaded);
        assertEquals("saved display", loadedText.getString());
        var hover = assertInstanceOf(HoverEvent.ShowText.class, loadedText.getStyle().getHoverEvent());
        assertEquals("hover", hover.value().getString()); assertEquals(0, f.localReads);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void productionBindsLiteralDisplayWithoutInvokingItsSavedResolver(boolean reload) throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            var source = text(); source.putString("text", "literal text");
            saved(f, ChunkPos.ZERO, reload, source); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertEquals(Set.of(FullInitializedInputs.Purpose.SELF_INITIALIZED), graph.inputReceipt().targets().getFirst().purposes());
            assertEquals(0, f.world.nextId); assertTrue(f.world.inserted.isEmpty());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void staticTraversalRetainsSelectorScoreBlockAndInterpretedStorageRequirements() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var source = text(); var parent = component("text", "root"); var extra = new ListTag();
        extra.add(component("selector", "@e[type=minecraft:sheep,distance=..4]"));
        var score = new CompoundTag(); var scoreValue = component("name", "Alice"); scoreValue.putString("objective", "visits");
        score.put("score", scoreValue); extra.add(score);
        var block = component("nbt", "Items"); block.putString("block", "~16 ~ ~-32"); extra.add(block);
        var storage = component("nbt", "message"); storage.putString("storage", "phoenium:display"); storage.putBoolean("interpret", true);
        var hover = component("action", "show_text"); hover.put("value", storage); parent.put("hover_event", hover);
        parent.put("extra", extra); source.put("text", parent);
        var fact = new FullSavedInput().capture(List.of(source)).entities().getFirst();
        var result = FullSavedTextInputs.discover(fact, f.level.registryAccess());
        assertNull(result.unmet()); assertTrue(result.needsContext());
        assertEquals(List.of(new BlockPos(24, 8, -24)), result.blocks());
        assertEquals(List.of(new FullSavedTextInputs.Selector("@e[type=minecraft:sheep,distance=..4]", false, false, true),
                new FullSavedTextInputs.Selector("Alice", false, true, false, FullSavedTextInputs.Use.SCORE)), result.selectors());
        assertEquals(List.of(new FullSavedTextInputs.Score("Alice", "visits")), result.scores());
        assertEquals(List.of(new FullSavedTextInputs.NbtRead(FullSavedTextInputs.Source.BLOCK, "~16 ~ ~-32", new BlockPos(24, 8, -24), "Items", false, 2),
                new FullSavedTextInputs.NbtRead(FullSavedTextInputs.Source.STORAGE, "phoenium:display", null, "message", true, 2)), result.nbtReads());
        assertEquals(0, f.nextId); assertTrue(f.inserted.isEmpty());
    }

    @Test void savedTextPayloadAccountingUsesLongAndRejectsExactOverflow() {
        var large = new FullSavedTextInputs.Saved(null, Vec2.ZERO, (long) Integer.MAX_VALUE + 19);
        var services = new FullSavedServicesFacts(null, null, null, null, null, large);
        assertEquals((long) Integer.MAX_VALUE + 91, services.payloadBytes());
        var overflow = new FullSavedTextInputs.Saved(null, Vec2.ZERO, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,
                () -> new FullSavedServicesFacts(null, null, null, null, null, overflow).payloadBytes());
    }

    @Test void localBlockCoordinatesMatchOriginalParsedCoordinatesAtNormalizedSavedRotation() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var source = text(); source.store("Rotation", Vec2.CODEC, new Vec2(450, 400));
        var block = component("nbt", "id"); block.putString("block", "^3 ^2 ^9"); source.put("text", block);
        var fact = new FullSavedInput().capture(List.of(source)).entities().getFirst();
        var result = FullSavedTextInputs.discover(fact, f.level.registryAccess());
        var commandSource = new CommandSourceStack(CommandSource.NULL, new Vec3(8, 8, 8), new Vec2(40, 90),
                f.level, PermissionSet.NO_PERMISSIONS, "test", Component.empty(), f.level.getServer(), null);
        var original = BlockPosArgument.blockPos().parse(new com.mojang.brigadier.StringReader("^3 ^2 ^9")).getBlockPos(commandSource);
        assertEquals(List.of(original), result.blocks()); assertEquals(0, f.nextId);
    }

    @Test void protectedNestedTextAliasInvalidatesSourceAndDetachedFactsKeepOriginalMeaning() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var source = text(); var parent = component("text", "root"); var extra = new ListTag();
        var child = component("text", "before"); extra.add(child); parent.put("extra", extra); source.put("text", parent);
        var tracker = new FullSavedInput(); var original = tracker.capture(List.of(source));
        assertFalse(FullSavedTextInputs.discover(original.entities().getFirst(), f.level.registryAccess()).needsContext());
        child.remove("text"); child.putString("selector", "@a");
        assertNull(tracker.snapshot());
        assertFalse(FullSavedTextInputs.discover(original.entities().getFirst(), f.level.registryAccess()).needsContext());
        var changed = tracker.capture(List.of(source));
        assertTrue(changed.revision() > original.revision());
        assertEquals(List.of(new FullSavedTextInputs.Selector("@a", false, true, false)),
                FullSavedTextInputs.discover(changed.entities().getFirst(), f.level.registryAccess()).selectors());
    }

    @Test void exactCodecDiscriminatorAndOriginalInvalidDecodeDoNotInventContexts() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1); var source = text();
        var literal = component("type", "text"); literal.putString("text", "literal"); literal.putString("selector", "@e"); source.put("text", literal);
        var fact = new FullSavedInput().capture(List.of(source)).entities().getFirst();
        assertFalse(FullSavedTextInputs.discover(fact, f.level.registryAccess()).needsContext());
        source.put("text", component("type", "unknown"));
        fact = new FullSavedInput().capture(List.of(source.copy())).entities().getFirst();
        assertFalse(FullSavedTextInputs.discover(fact, f.level.registryAccess()).needsContext());
    }

    @Test void oversizedSavedTextRetainsItsMeasuredPayloadForExpandedAdmission() {
        var source = text(); source.put("text", StringTag.valueOf("x".repeat(FullSavedInput.MAX_FACT_BYTES)));
        var snapshot = new FullSavedInput().capture(List.of(source));
        assertEquals(1, snapshot.entities().size()); assertNull(snapshot.unmet());
        assertTrue(snapshot.payloadBytes() > FullSavedInput.MAX_FACT_BYTES);
    }

    private static CompoundTag text() { var tag = tag(8, 8, 0); tag.putString("id", "minecraft:text_display"); return tag; }
    private static CompoundTag component(String key, String value) { var tag = new CompoundTag(); tag.putString(key, value); return tag; }
}
