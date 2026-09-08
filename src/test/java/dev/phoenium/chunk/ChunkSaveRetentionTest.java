package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.OperationCategory;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkSaveRetentionTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }

    @Test void originalVisibleUseSurvivesRetirementButCannotMintANewSource() throws Exception {
        var f = new FullRegistrationFixture(true);
        ChunkSaveRetention visible = null;
        try {
            f.startBaseline(ChunkPos.ZERO); f.runGenerationTasks();
            var holder = f.holder(ChunkPos.ZERO);
            var chunk = holder.getLatestChunk();
            assertNotNull(chunk);
            try (var lease = f.owner.leases().acquire();
                 var owner = OwnershipContext.enter(f.ownerId, lease.epoch(), OperationCategory.OWNER_TASK)) {
                var unload = ChunkSaveRetention.retain(f.authority, holder, chunk);
                f.services.retireResidentHolder(((FullHolderEvidence) (Object) holder).phoenium$lifetime());
                assertFalse(unload.current());
                visible = unload.fork();
                unload.close();
                assertTrue(visible.matches(chunk));
                assertThrows(ChunkProtocolException.class, () -> ChunkSaveRetention.retain(f.authority, holder, chunk));
                try (var eagerSelection = visible.fork()) { assertTrue(eagerSelection.matches(chunk)); }
            }
            visible.close(); visible = null;
            assertNull(((FullHolderEvidence) (Object) holder).phoenium$lifetime());
        } finally { if (visible != null) visible.close(); f.stop(); }
    }
}
