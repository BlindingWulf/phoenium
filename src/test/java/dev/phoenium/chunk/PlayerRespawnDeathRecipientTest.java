package dev.phoenium.chunk;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerRespawnDeathRecipientTest {
    @BeforeAll static void bootstrap(){NativeItemPortalTransferTest.bootstrap();}
    @Test void exactDeathPositionSurvivesNativeRemovalButNeverRestoresTracking()throws Exception {
        try(var f=new PlayerRespawnHandoffTest.Fixture()) {
            FullEntityFacts.PublishedPosition before;
            try(var turn=f.service.referenceAccess()) {before=f.service.entityFacts.publishedPosition(f.player);}
            f.nativeDeathTicks();var retired=PlayerRespawnHandoff.deathPosition(f.player);
            assertNotNull(retired);assertEquals(before.position(),retired.position());assertSame(f.player,retired.candidate().alias());
            try(var turn=f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class,()->f.service.entityFacts.publishedPosition(f.player));
                var cut=f.service.scoreboard.trackingCut(f.players);f.service.scoreboard.requireTrackingSession(cut,cut.sessions().getFirst());
            }
            var handoff=f.open();f.oldRemoval(handoff);assertNull(PlayerRespawnHandoff.deathPosition(f.player));handoff.rollback();
        }
    }
    @Test void incompleteRemovalCannotSupplyARecipientAndMovementInvalidatesTheCompletedReceipt()throws Exception {
        try(var f=new PlayerRespawnHandoffTest.Fixture()) {
            f.nativeDeathTicks(19);assertNull(PlayerRespawnHandoff.deathPosition(f.player));
            f.nativeDeathTicks(1);assertNotNull(PlayerRespawnHandoff.deathPosition(f.player));
            FullEntityFacts.routed(f.player);assertNull(PlayerRespawnHandoff.deathPosition(f.player));
        }
    }
    /** A block-event broadcast while the player waits on the End credits crashed the server: native had already removed the
     * player (CHANGED_DIMENSION), so its published routing facts were gone while it was still a connection recipient. */
    @Test void endCreditsPositionIsCapturedBeforeRemovalAndNeverRestoresTracking()throws Exception {
        try(var f=new PlayerRespawnHandoffTest.Fixture()) {
            FullEntityFacts.PublishedPosition before;
            try(var turn=f.service.referenceAccess()) {before=f.service.entityFacts.publishedPosition(f.player);}
            assertNull(PlayerRespawnHandoff.deathPosition(f.player));
            try(var turn=f.service.referenceAccess()) {PlayerRespawnHandoff.captureCreditsPosition(f.player);}
            assertNull(PlayerRespawnHandoff.deathPosition(f.player),"unusable until the completed-removal bit is published");
            f.creditsRemoval(f.player,true);
            var retired=PlayerRespawnHandoff.deathPosition(f.player);
            assertNotNull(retired);assertEquals(before.position(),retired.position());assertSame(f.player,retired.candidate().alias());
            try(var turn=f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class,()->f.service.entityFacts.publishedPosition(f.player));
            }
            FullEntityFacts.routed(f.player);assertNull(PlayerRespawnHandoff.deathPosition(f.player));
        }
    }
}
