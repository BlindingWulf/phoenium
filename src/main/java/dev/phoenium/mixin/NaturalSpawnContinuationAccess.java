package dev.phoenium.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NaturalSpawner.class)
public interface NaturalSpawnContinuationAccess {
    @Invoker("getRandomPosWithin") static BlockPos phoenium$randomPos(Level level, LevelChunk chunk) { throw new AssertionError(); }
    @Invoker("isRightDistanceToPlayerAndSpawnPoint") static boolean phoenium$rightDistance(ServerLevel level,
            ChunkAccess chunk, BlockPos.MutableBlockPos pos, double distance) { throw new AssertionError(); }
    @Invoker("getMobForSpawn") static Mob phoenium$createMob(ServerLevel level, EntityType<?> type) { throw new AssertionError(); }
    @Invoker("isValidPositionForMob") static boolean phoenium$validMob(ServerLevel level, Mob mob, double distance) { throw new AssertionError(); }
}
