package dev.phoenium.c2meprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Test-only observation target for the exact captured async-deserialize queue drain. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.ChunkIoMainThreadTaskUtils", priority = 1)
abstract class DrainProbeMixin { }
