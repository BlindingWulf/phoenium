package dev.phoenium.chunk;

import net.minecraft.nbt.CompoundTag;

/** Exact protected input identity at the original saved reader, not a content digest alias. */
public interface FullSavedValueInput { CompoundTag phoenium$originalSavedTag(); }
