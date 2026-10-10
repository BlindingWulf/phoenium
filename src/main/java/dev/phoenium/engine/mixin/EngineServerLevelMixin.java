package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S9-E1 (engine mode only): ServerLevel.tick's entity loop runs through the tile engine; block events and block-update
 * broadcasts from a tile are deferred to the server thread; a tile thread has its own path-type cache. Applied after
 * Lithium's ServerLevel mixins (the plugin then makes Lithium's active-navigation registration synchronized, routes
 * Level.randValue through TileEngine and gives a tile its own copy of Lithium's cached random-tick position).
 *
 * <p>S9-E3: tickChunk calls made while the chunk phase records are recorded in their chunk's tile.
 */
@Mixin(value = ServerLevel.class, priority = 2000)
abstract class EngineServerLevelMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void phoenium$tileEntities(EntityTickList list, Consumer<Entity> body, Operation<Void> original) {
        TileEngine.tickEntities((ServerLevel) (Object) this, list, body, original);
    }

    @WrapMethod(method = "tickChunk")
    private void phoenium$recordTickChunk(LevelChunk chunk, int tickSpeed, Operation<Void> original) {
        if (!TileEngine.recordTickChunk((ServerLevel) (Object) this, chunk, tickSpeed)) original.call(chunk, tickSpeed);
    }

    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferBlockEvent(BlockPos pos, Block block, int b0, int b1, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ServerLevel level = (ServerLevel) (Object) this;
        BlockPos at = pos.immutable();
        context.defer(() -> level.blockEvent(at, block, b0, b1));
        ci.cancel();
    }

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferBlockUpdated(BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ServerLevel level = (ServerLevel) (Object) this;
        BlockPos at = pos.immutable();
        // vanilla drops the position from the path-type cache at once; the tile's own cache does the same
        context.pathTypes().invalidate(at);
        context.defer(() -> level.sendBlockUpdated(at, old, current, updateFlags));
        ci.cancel();
    }

    /** Vanilla posts the POI change to the server executor, which runs it at once on the server thread. */
    @Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferPoiChange(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ServerLevel level = (ServerLevel) (Object) this;
        BlockPos at = pos.immutable();
        context.defer(() -> level.updatePOIOnBlockStateChange(at, oldState, newState));
        ci.cancel();
    }

    @Inject(method = "getPathTypeCache", at = @At("HEAD"), cancellable = true)
    private void phoenium$tilePathTypes(CallbackInfoReturnable<PathTypeCache> cir) {
        TileContext context = TileEngine.current();
        if (context != null) cir.setReturnValue(context.pathTypes());
    }
}
