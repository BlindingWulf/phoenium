package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullLegacyOwners;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.chunk.ChunkProtocolException;
import java.util.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.UniquelyIdentifyable;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;

/** Preserve the UUID read and original reached Owner edge, consuming exactly that input's receipt. */
@Mixin(EntityReference.class)
abstract class FullLegacyOwnerReadMixin {
    @WrapMethod(method = "readWithOldOwnerConversion")
    private static <T extends UniquelyIdentifyable> EntityReference<T> phoenium$owner(ValueInput input, String key, Level level,
                                                                                   Operation<EntityReference<T>> original) {
        return FullLegacyOwners.readOriginal(input, key, level);
    }
}
