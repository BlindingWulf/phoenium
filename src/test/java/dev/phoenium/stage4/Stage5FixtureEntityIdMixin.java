package dev.phoenium.stage4;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explicit pre-publication native identities for the clocked comparison fixture. */
@Mixin(Entity.class)
abstract class Stage5FixtureEntityIdMixin {
    @Unique private boolean phoenium$fixtureIdAssigned;

    @Inject(method = "load(Lnet/minecraft/world/level/storage/ValueInput;)V", at = @At("RETURN"), require = 1)
    private void phoenium$fixtureIdentity(ValueInput input, CallbackInfo ci) {
        if (!Boolean.getBoolean("phoenium.stage5.trackerClock")) return;
        Entity entity = (Entity) (Object) this;
        if (!dev.phoenium.stage4probe.Stage4Trace.relevantEntity(entity)) return;
        // HashCommon.mix(id) is exactly 1/2/3. These positive IDs keep the native
        // hash-table input stable across capacities, without sorting its iteration.
        int id = switch (entity.getUUID().toString()) {
            case "00000001-0000-0002-0000-000300000004" -> 340573321;
            case "00000005-0000-0006-0000-000700000008" -> 681146642;
            case "00000009-0000-000a-0000-000b0000000c" -> 1021719963;
            default -> throw new IllegalStateException("unknown fixture UUID before native publication");
        };
        if (!(entity.level() instanceof ServerLevel level) || phoenium$fixtureIdAssigned
                || level.getEntity(entity.getUUID()) != null || level.getEntity(entity.getId()) != null
                || level.getEntity(id) != null)
            throw new IllegalStateException("fixture numeric identity is already published or occupied");
        int before = entity.getId();
        entity.setId(id); phoenium$fixtureIdAssigned = true;
        System.out.println("STAGE5_FIXTURE_ENTITY_ID uuid=" + entity.getUUID() + " id=" + id
            + " allocated=" + before + " beforePublication=true");
    }
}
