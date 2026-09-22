package dev.phoenium.chunk;

import dev.phoenium.live.PhoeniumLiveRuntime;
import dev.phoenium.ownership.ObjectOwnership;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/** Only the test scene's live-runtime publisher endpoint. Real scope and native loop remain in use. */
public final class NaturalSpawnSceneHooks {
    static boolean active;
    public static FreshPassengerAdmission.NaturalSpawnScope scope(PhoeniumLiveRuntime runtime, ServerLevel world) {
        if (!active) return runtime.naturalSpawnPassengerScope(world);
        return FreshPassengerAdmission.naturalSpawnScope(world, new FreshPassengerAdmission.Publisher() {
            public void requireOwners(Set<ChunkPos> positions) { throw new AssertionError("unexpected passenger"); }
            public ObjectOwnership publish(Entity entity) { throw new AssertionError("unexpected passenger"); }
            public ObjectOwnership current(String uuid) { throw new AssertionError("unexpected passenger"); }
            public void retire(ObjectOwnership identity) { throw new AssertionError("unexpected passenger"); }
        });
    }
}
