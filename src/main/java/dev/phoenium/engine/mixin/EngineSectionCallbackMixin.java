package dev.phoenium.engine.mixin;

import dev.phoenium.engine.EngineConfig;
import dev.phoenium.engine.EngineProtections;
import dev.phoenium.engine.EngineTrackerIndex;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S9-E1 (engine mode only): while a tile runs, no entity changes section membership. A move into another section is
 * applied after the colour (once per phase per entity; the entity stays in its old section meanwhile, which entity
 * queries' 2-block inflation covers at normal speeds). A removal is applied after the colour, once; isRemoved() is
 * already true, and later moves of that entity are ignored.
 *
 * <p>S9-E6: once a section change is stored (inline, or applied after the colour), the entity's tracker follows in the
 * tracker index ({@link EngineTrackerIndex}). S9-E8: so do its protection memberships ({@link EngineProtections#sectionMoved}).
 */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
abstract class EngineSectionCallbackMixin {
    @Shadow @Final private EntityAccess entity;
    @Shadow private long currentSectionKey;

    @Unique private long phoenium$movePhase = -1L;
    @Unique private boolean phoenium$removeDeferred;

    @Shadow public abstract void onMove();

    @Shadow public abstract void onRemove(Entity.RemovalReason reason);

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferMove(CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        if (this.phoenium$removeDeferred) {
            ci.cancel();
            return;
        }
        if (SectionPos.asLong(this.entity.blockPosition()) == this.currentSectionKey) return;
        ci.cancel();
        long phase = TileEngine.phaseId();
        if (this.phoenium$movePhase == phase) return;
        this.phoenium$movePhase = phase;
        context.defer(() -> {
            this.phoenium$movePhase = -1L;
            if (!this.phoenium$removeDeferred) this.onMove();
        });
    }

    @Inject(method = "onMove", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
        target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager$Callback;currentSectionKey:J"))
    private void phoenium$trackerFollows(CallbackInfo ci) {
        if (!(this.entity instanceof Entity moved)) return;
        if (EngineConfig.TRACKER_INDEX) EngineTrackerIndex.sectionMoved(moved);
        EngineProtections.sectionMoved(moved);
    }

    @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ci.cancel();
        if (this.phoenium$removeDeferred) return;
        this.phoenium$removeDeferred = true;
        context.defer(() -> this.onRemove(reason));
    }
}
