package dev.phoenium.save;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetainedChunkSnapshotTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void savedTagsCannotChangeAfterTheOwnerProducesTheSnapshot() {
        var entity = new CompoundTag();
        var child = new CompoundTag(); child.putString("value", "at-cut");
        entity.put("child", child);
        var block = new CompoundTag(); block.putString("value", "block-at-cut");
        var structures = new CompoundTag(); structures.putString("value", "structure-at-cut");
        var entities = new ArrayList<>(List.of(entity));
        var blocks = new ArrayList<>(List.of(block));
        var source = new SerializableChunkData(null, new ChunkPos(8, 1), 0, 40, 20,
                null, null, null, null, null, Map.of(), new ChunkAccess.PackedTicks(List.of(), List.of()),
                null, false, List.of(), entities, blocks, structures);
        var retained = RetainedChunkSnapshot.detach(source);
        child.putString("value", "later-entity");
        block.putString("value", "later-block");
        structures.putString("value", "later-structure");
        entities.clear(); blocks.clear();
        assertEquals("at-cut", retained.entities().getFirst().getCompound("child").orElseThrow().getStringOr("value", ""));
        assertEquals("block-at-cut", retained.blockEntities().getFirst().getStringOr("value", ""));
        assertEquals("structure-at-cut", retained.structureData().getStringOr("value", ""));
        assertThrows(UnsupportedOperationException.class, () -> retained.entities().clear());
        assertEquals(40, retained.lastUpdateTime());
        assertEquals(new ChunkPos(8, 1), retained.chunkPos());
    }
}
