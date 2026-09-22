package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullGenerationSource;
import dev.phoenium.chunk.FullRegistrationRuntime;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;

/** Selection is fixed for the server's disposable integration session; prerequisites still admit every stage. */
@Mixin(ChunkMap.class)
abstract class FullProductionMapMixin implements FullGenerationSource {
    @Shadow @Final private ServerLevel level;
    @Override public FullRegistrationRuntime phoenium$fullRegistration() {
        return Phoenium.liveRuntime().fullRegistration(level);
    }
    @Override public FullRegistrationRuntime phoenium$naturalSpawnStructures() {
        return Phoenium.liveRuntime().naturalSpawnStructures(level);
    }
}
