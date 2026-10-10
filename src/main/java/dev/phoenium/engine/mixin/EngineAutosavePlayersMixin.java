package dev.phoenium.engine.mixin;

import dev.phoenium.engine.EngineAutosave;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S9-E14 (engine mode only): a spreading autosave's PlayerList.saveAll() records the online players; each PlayerList.tick()
 * after it saves the next of them that is still online (PlayerList.save: player data, stats, advancements), within the
 * autosave budget ({@link EngineAutosave}). A player who left meanwhile was saved by the logout; a respawned player is
 * looked up by UUID and its current object is saved. Any other saveAll (shutdown, reload, /save-all) runs as vanilla and
 * drops the queue.
 */
@Mixin(PlayerList.class)
abstract class EngineAutosavePlayersMixin {
    @Shadow @Final private List<ServerPlayer> players;

    @Unique private final EngineAutosave.Queue<UUID> phoenium$playerSaves = new EngineAutosave.Queue<>();

    @Shadow protected abstract void save(ServerPlayer player);

    @Shadow public abstract @Nullable ServerPlayer getPlayer(UUID uuid);

    @Inject(method = "saveAll", at = @At("HEAD"), cancellable = true)
    private void phoenium$recordPlayerSaves(CallbackInfo ci) {
        if (EngineAutosave.recordPlayers()) {
            UUID[] online = new UUID[this.players.size()];
            for (int i = 0; i < online.length; i++) online[i] = this.players.get(i).getUUID();
            EngineAutosave.recordedPlayers(this.phoenium$playerSaves, online);
            ci.cancel();
            return;
        }
        EngineAutosave.fullSave(this.phoenium$playerSaves);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void phoenium$drainPlayerSaves(CallbackInfo ci) {
        if (!this.phoenium$playerSaves.busy()) return;
        EngineAutosave.drainPlayers(this.phoenium$playerSaves, uuid -> {
            ServerPlayer player = this.getPlayer(uuid);
            if (player == null) return false;
            this.save(player);
            return true;
        });
    }
}
