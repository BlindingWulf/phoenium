package dev.phoenium.c2meprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Test-only observation target for C2ME's locked asynchronous DISK path. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDiskAsync", priority = 1)
abstract class AsyncReadProbeMixin { }
