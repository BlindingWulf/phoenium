package dev.phoenium.engine.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * S9-E3: the level's own Level.randValue, for every thread not running a tile (EngineMixinPlugin routes the field's
 * reads and writes in Level and ServerLevel through TileEngine.randValue / setRandValue; these accessors are skipped).
 */
@Mixin(Level.class)
public interface EngineLevelAccess {
    @Accessor("randValue")
    int phoenium$randValue();

    @Accessor("randValue")
    void phoenium$setRandValue(int value);
}
