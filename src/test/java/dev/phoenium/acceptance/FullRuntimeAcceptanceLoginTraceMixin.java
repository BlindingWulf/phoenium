package dev.phoenium.acceptance;

import java.util.Queue;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Narrow, server-side login trace used only by the disposable FULL acceptance harness.
 * It records the configuration-to-game transition and leaves all login behavior vanilla.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
abstract class FullRuntimeAcceptanceLoginTraceMixin {
    @Shadow private ConfigurationTask currentTask;
    @Shadow private Queue<ConfigurationTask> configurationTasks;

    @Unique private int phoenium$diagnosticTicks;

    @Inject(method = "startConfiguration", at = @At("HEAD"))
    private void phoenium$startConfiguration(CallbackInfo callback) {
        trace("start-configuration");
    }

    @Inject(method = "returnToWorld", at = @At("HEAD"))
    private void phoenium$returnToWorld(CallbackInfo callback) {
        trace("return-to-world");
    }

    @Inject(method = "handleSelectKnownPacks", at = @At("HEAD"))
    private void phoenium$selectKnownPacksEntry(CallbackInfo callback) {
        trace("select-known-packs-entry");
    }

    @Inject(method = "handleSelectKnownPacks", at = @At("RETURN"))
    private void phoenium$selectKnownPacksExit(CallbackInfo callback) {
        trace("select-known-packs-exit");
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void phoenium$configurationTick(CallbackInfo callback) {
        if (++phoenium$diagnosticTicks == 1 || phoenium$diagnosticTicks % 100 == 0) {
            trace("configuration-tick-" + phoenium$diagnosticTicks);
        }
    }

    @Inject(method = "handleConfigurationFinished", at = @At("HEAD"))
    private void phoenium$configurationFinishedEntry(ServerboundFinishConfigurationPacket packet, CallbackInfo callback) {
        trace("configuration-finished-entry");
    }

    @Inject(method = "handleConfigurationFinished", at = @At("RETURN"))
    private void phoenium$configurationFinishedExit(ServerboundFinishConfigurationPacket packet, CallbackInfo callback) {
        trace("configuration-finished-exit");
    }

    private void trace(String event) {
        String task = currentTask == null ? "none" : String.valueOf(currentTask.type());
        int queued = configurationTasks == null ? -1 : configurationTasks.size();
        System.out.println("PHOENIUM_FULL_LOGIN_TRACE event=" + event + " thread="
                + Thread.currentThread().getName() + " task=" + task + " queued=" + queued);
    }
}
