package dev.phoenix.spawnprep.mixin;

import dev.phoenix.spawnprep.SpawnPrep;
import net.minecraft.util.Util;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs spawn preparation at the end of vanilla's level preparation, before "Done" and before the watchdog starts. */
@Mixin(MinecraftServer.class)
public abstract class PrepareLevelsMixin {
    /** Same 10 ms startup wait vanilla uses while it loads its own initial chunks. */
    private static final long PREPARE_WAIT_NANOS = 10_000_000L;

    @Inject(method = "createLevels", at = @At("HEAD"))
    private void phoenix$captureNewWorld(CallbackInfo ci) {
        SpawnPrep.newWorld = !((MinecraftServer) (Object) this).getWorldData().overworldData().isInitialized();
    }

    @Inject(method = "prepareLevels", at = @At("TAIL"))
    private void phoenix$prepareSpawn(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        MinecraftServerAccess access = (MinecraftServerAccess) server;
        SpawnPrep.run(server, () -> {
            access.phoenix$setNextTickTimeNanos(Util.getNanos() + PREPARE_WAIT_NANOS);
            access.phoenix$waitUntilNextTick();
        });
    }
}
