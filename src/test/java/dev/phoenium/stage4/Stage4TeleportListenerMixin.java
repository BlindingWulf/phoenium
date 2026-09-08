package dev.phoenium.stage4;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.stage4probe.Stage4Trace;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

import java.lang.reflect.Field;

/** Test-only observation of the native teleport acknowledgement boundary. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class Stage4TeleportListenerMixin {
    @WrapMethod(method = "handleAcceptTeleportPacket")
    private void phoenium$accept(ServerboundAcceptTeleportationPacket packet, Operation<Void> original) {
        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        Object before = field(listener, "awaitingTeleport");
        Object beforeTime = field(listener, "awaitingTeleportTime");
        original.call(packet);
        Object after = field(listener, "awaitingTeleport");
        Object afterTime = field(listener, "awaitingTeleportTime");
        if (Stage4Trace.active()) {
            Stage4Trace.event("teleport.accept", "packetId=" + packet.getId()
                + " awaitingBefore=" + before + " awaitingAfter=" + after
                + " awaitingTimeBefore=" + beforeTime + " awaitingTimeAfter=" + afterTime
                + " player=" + listener.player.getX() + "," + listener.player.getY() + ","
                + listener.player.getZ() + " lastSection=" + listener.player.getLastSectionPos());
        }
        System.out.println("STAGE4_TRACE_TELEPORT_ACCEPT packetId=" + packet.getId()
            + " awaitingBefore=" + before + " awaitingAfter=" + after
            + " awaitingTimeBefore=" + beforeTime + " awaitingTimeAfter=" + afterTime);
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable failure) {
            return "<unavailable:" + failure.getClass().getSimpleName() + ">";
        }
    }
}
