package dev.phoenium.chunk;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The queued native item replay combines an exact player owner with the existing server-tick service turn. */
final class UseItemTickListServiceTurnTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }

    @Test
    void ownerReplayMayReachTickListOnlyInsideTheExistingServerTickTurn() throws Exception {
        try (var fixture = new FullServiceOperationFixture()) {
            ServerLevel world = fixture.world();
            try (var lease = fixture.leases.acquire();
                 var owner = OwnershipContext.enter(fixture.region, lease.epoch(), OperationCategory.OWNER_TASK)) {
                assertThrows(ChunkProtocolException.class,
                    () -> FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.TICK_LIST));
            }

            try (var serial = fixture.service.serial(FullFinishingServices.Phase.SERVER_TICK);
                 var lease = fixture.leases.acquire();
                 var owner = OwnershipContext.enter(fixture.region, lease.epoch(), OperationCategory.OWNER_TASK)) {
                assertDoesNotThrow(() -> FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.TICK_LIST));
                assertTrue(FullFinishingServices.currentTurn(fixture.server).startsWith("SERIAL(phase=SERVER_TICK"));
            }

            assertEquals("NONE", FullFinishingServices.currentTurn(fixture.server));
            assertEquals(1L, fixture.service.measurements().get("serialEntries"),
                "item use must reuse the enclosing tick turn, not create a global serial wrapper");
        }
    }
}
