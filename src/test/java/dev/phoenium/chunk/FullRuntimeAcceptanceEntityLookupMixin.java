package dev.phoenium.acceptance;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityLookup.class)
abstract class FullRuntimeAcceptanceEntityLookupMixin {
    private static boolean fixture(Object value) {
        return value instanceof Entity entity && entity.entityTags().stream().anyMatch(tag -> tag.startsWith("phoenium_full_"));
    }
    private static String identity(Object value) {
        return value instanceof Entity entity ? entity.getUUID() + ":" + entity.getType() + ":" + entity.entityTags() : String.valueOf(value);
    }
    @WrapOperation(method = "add", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object inserted(Int2ObjectMap<Object> map, int id, Object value, Operation<Object> original) {
        Object previous = original.call(map, id, value);
        if (fixture(value) || fixture(previous))
            System.out.println("PHOENIUM_FULL_VISIBLE_PUT id=" + id + " value=" + identity(value) + " previous=" + identity(previous));
        return previous;
    }
    @WrapOperation(method = "remove", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;remove(I)Ljava/lang/Object;"))
    private Object removed(Int2ObjectMap<Object> map, int id, Operation<Object> original) {
        Object previous = original.call(map, id);
        if (fixture(previous)) System.out.println("PHOENIUM_FULL_VISIBLE_REMOVE id=" + id + " value=" + identity(previous));
        return previous;
    }
}
