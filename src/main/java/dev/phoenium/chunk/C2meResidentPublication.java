package dev.phoenium.chunk;

import java.util.function.BooleanSupplier;
import net.minecraft.world.level.chunk.LevelChunk;

/** Holder-lifetime evidence, independent of the already acknowledged FULL operation.
 * No provider state is written here. Retirement releases the provider context and state. */
final class C2meResidentPublication {
    private C2meFullAccess.Observation observation;
    private C2meFullAccess.FullState state;
    private BooleanSupplier runtime;

    C2meResidentPublication(C2meFullAccess.Observation observation, BooleanSupplier runtime) {
        this.observation = observation;
        this.runtime = runtime;
    }

    synchronized void published(C2meFullAccess.FullState state) {
        if (observation == null) return;
        observation.requirePublication(state);
        this.state = state;
    }

    synchronized boolean runtimeOpen() { return runtime != null && runtime.getAsBoolean(); }
    synchronized boolean pendingCurrent() {
        if (!runtimeOpen() || observation == null) return false;
        try {
            if (observation.stillCurrent()) return true;
            retire(); return false;
        }
        catch (ChunkProtocolException stale) { retire(); return false; }
    }
    synchronized boolean published() { return state != null; }
    synchronized LevelChunk chunk() { return current() ? state.chunk() : null; }
    synchronized boolean current() {
        if (!runtimeOpen() || observation == null || state == null) return false;
        try {
            observation.requirePublication(state);
            var slot = (java.util.concurrent.CompletableFuture<?>) observation.statusSlot();
            return slot.isDone() && !slot.isCompletedExceptionally();
        } catch (ChunkProtocolException stale) {
            retire();
            return false;
        }
    }
    synchronized boolean matches(net.minecraft.server.level.ServerLevel world, net.minecraft.server.level.ChunkHolder holder) {
        return observation != null && observation.map() == world.getChunkSource().chunkMap
            && observation.userDataHolder() == holder;
    }
    synchronized String observation() {
        return "provider=C2ME providerRuntimeOpen=" + runtimeOpen()
            + " " + (observation == null ? "providerBackingHolderMatch=false providerStatusSlotMatch=false providerFullStateCurrent=false"
                : observation.residentChecks(state));
    }
    synchronized void retire() { observation = null; state = null; runtime = null; }
}
