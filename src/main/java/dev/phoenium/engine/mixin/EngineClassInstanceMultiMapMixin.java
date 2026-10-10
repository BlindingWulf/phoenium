package dev.phoenium.engine.mixin;

import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S9-E1 (engine mode only): carrier for EngineMixinPlugin.synchronize. An entity section's per-class lists are created
 * lazily on read (vanilla {@code find}; Lithium's group and raw-type lists), so two tiles whose entity queries reach the
 * same section would both write its map. Applied after Lithium's mixins; the plugin then marks those methods
 * ACC_SYNCHRONIZED: a bounded, uncontended monitor per section map that never encloses a callback or a wait.
 */
@Mixin(value = ClassInstanceMultiMap.class, priority = 2000)
abstract class EngineClassInstanceMultiMapMixin {
}
