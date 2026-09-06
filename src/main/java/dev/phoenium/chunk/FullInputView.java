package dev.phoenium.chunk;
import net.minecraft.server.level.ChunkHolder;
/** Read only original visible-holder lookup. Does not opt a ChunkMap into FULL selection. */
public interface FullInputView {
    ChunkHolder phoenium$visibleInput(long position);
    FullInputNotifications phoenium$inputChanges();
}
