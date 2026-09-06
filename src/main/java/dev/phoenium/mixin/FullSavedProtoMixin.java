package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullSavedInput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import java.util.List;

@Mixin(ProtoChunk.class)
abstract class FullSavedProtoMixin implements FullSavedInput.Access {
    @Unique private final FullSavedInput phoenium$saved = new FullSavedInput();
    @Shadow @Final @Mutable private List<CompoundTag> entities;
    public FullSavedInput phoenium$savedInput() { return phoenium$saved; }
    @WrapMethod(method = "getEntities")
    private List<CompoundTag> phoenium$entityList(Operation<List<CompoundTag>> original) {
        if (!phoenium$saved.tracking()) return original.call();
        entities = dev.phoenium.chunk.FullSavedCollections.roots(original.call(), phoenium$saved);
        return entities;
    }
    @WrapMethod(method = "addEntity(Lnet/minecraft/nbt/CompoundTag;)V")
    private void phoenium$savedWriter(CompoundTag tag, Operation<Void> original) {
        Runnable notify;
        phoenium$saved.beforeWrite();
        try {
            original.call(tag);
            notify = phoenium$saved.written(((ProtoChunk) (Object) this).getEntities());
        } finally { phoenium$saved.endWrite(); }
        if (notify != null) notify.run();
    }
}
