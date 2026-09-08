package dev.phoenium.mixin;

import dev.phoenium.chunk.FullStructureInputs;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface FullStructureChunkAccessMixin extends FullStructureInputs.ChunkView {
    @Override @Accessor("structuresRefences") Map<Structure, LongSet> phoenium$structureReferences();
    @Override @Accessor("structureStarts") Map<Structure, StructureStart> phoenium$structureStarts();
}
