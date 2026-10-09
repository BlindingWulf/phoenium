package dev.phoenium.mixin;

import dev.phoenium.core.FastThreadLocal;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** S9-D9: the server thread's first act gives it its own FastThreadLocal slots, before any Phoenium scope opens. */
@Mixin(MinecraftServer.class)
abstract class ServerThreadBindingMixin {
    @Inject(method = "runServer()V", at = @At("HEAD"), require = 1)
    private void phoenium$bindServerThread(CallbackInfo ci) {
        FastThreadLocal.bindServerThread();
    }
}
