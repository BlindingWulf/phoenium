package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullCommandStorageInputs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.CommandStorage;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CommandStorage.class)
abstract class FullCommandStorageMixin {
    @WrapMethod(method = "get")
    private CompoundTag phoenium$get(Identifier id, Operation<CompoundTag> original) {
        return FullCommandStorageInputs.get((CommandStorage) (Object) this, id, () -> original.call(id));
    }
    @WrapMethod(method = "set")
    private void phoenium$set(Identifier id, CompoundTag contents, Operation<Void> original) {
        FullCommandStorageInputs.set((CommandStorage) (Object) this, id, contents, () -> original.call(id, contents));
    }
}
