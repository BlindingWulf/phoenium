package dev.phoenium.acceptance;

import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs the actual candidate scheduling path which produces PrepareSpawnTask.spawnPosition. */
@Mixin(PlayerSpawnFinder.class)
abstract class FullRuntimeAcceptancePlayerSpawnFinderMixin {
    @Shadow private ServerLevel level;
    @Shadow private BlockPos spawnSuggestion;
    @Shadow private int radius;
    @Shadow private int nextCandidateIndex;
    @Unique private int phoenium$candidates;

    @Inject(method = "scheduleCandidate", at = @At("HEAD"))
    private void phoenium$traceCandidate(int x, int z, int candidate,
                                          Supplier<Optional<Vec3>> supplier, CallbackInfo callback) {
        int sequence = ++phoenium$candidates;
        if (sequence > 24 && sequence % 100 != 0) return;
        System.out.println("PHOENIUM_FULL_SPAWN_CANDIDATE sequence=" + sequence
                + " candidate=" + candidate + " next=" + nextCandidateIndex
                + " origin=" + spawnSuggestion + " radius=" + radius
                + " block=" + x + "," + z + " level=" + level.dimension());
    }
}
