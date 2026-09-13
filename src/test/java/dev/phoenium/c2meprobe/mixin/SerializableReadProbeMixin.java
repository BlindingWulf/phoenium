package dev.phoenium.c2meprobe.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Adds the disposable observer plugin's entry/return receipts to the locked vanilla read hook. */
@Pseudo @Mixin(targets = "net.minecraft.world.level.chunk.storage.SerializableChunkData", priority = 1)
abstract class SerializableReadProbeMixin { }
