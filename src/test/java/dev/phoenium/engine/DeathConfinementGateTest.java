package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Stage 11 P1 prototype (2026-10-18, {@code phoenium.engine.deathConfinement}, default off): a {@code die} called on a
 * tile thread is deferred to the server thread, so hooks reachable only inside {@code die} stop being tile hooks. The
 * set is proven from the 26.2 call graph (STAGE11_CALLBACK_CONFINEMENT_DESIGN.md §3a); confinement off changes nothing.
 */
class DeathConfinementGateTest {
    private static final String SP = "net.minecraft.server.level.ServerPlayer";
    private static final String PLAYER = "net.minecraft.world.entity.player.Player";
    private static final String LIVING = "net.minecraft.world.entity.LivingEntity";

    @Test
    void deathPathIsExactlyTheSetProvenFromTheCallGraph() {
        assertEquals(Set.of("die", "dropAllDeathLoot", "dropCustomDeathLoot", "dropEquipment", "dropExperience", "createWitherRose"),
            EntityHookGate.DEATH_PATH);
    }

    @Test
    void withConfinementOffDeathHooksStayTileHooks() {
        assertTrue(EntityHookGate.isTileHook(List.of(SP), List.of("die"), false));
        assertTrue(EntityHookGate.isTileHook(List.of(LIVING), List.of("dropAllDeathLoot"), false));
        assertTrue(EntityHookGate.isTileHook(List.of(LIVING), List.of("dropAllDeathLoot")));   // the v2.1 rule is the off rule
    }

    @Test
    void withConfinementDeathOnlyHooksAreNotTileHooks() {
        assertFalse(EntityHookGate.isTileHook(List.of(LIVING), List.of("dropAllDeathLoot"), true));     // universal-graves
        assertFalse(EntityHookGate.isTileHook(List.of(SP), List.of("die"), true));
        assertFalse(EntityHookGate.isTileHook(List.of("net.minecraft.world.entity.TamableAnimal"), List.of("die"), true));
        assertFalse(EntityHookGate.isTileHook(List.of("net.minecraft.world.entity.Mob"), List.of("dropCustomDeathLoot"), true));
        assertFalse(EntityHookGate.isTileHook(List.of(LIVING), List.of("dropEquipment", "dropExperience", "createWitherRose"), true));
    }

    @Test
    void confinedPlayerHooksMayMixDeathAndServerThreadOnlyMethods() {
        assertFalse(EntityHookGate.isTileHook(List.of(SP), List.of("openMenu", "die"), true));             // sgui
        assertFalse(EntityHookGate.isTileHook(List.of(SP), List.of("die", "readAdditionalSaveData"), true));
        assertTrue(EntityHookGate.isTileHook(List.of(PLAYER, SP), List.of("die", "getDisplayName"), true)); // styledchat: name is read outside death
        assertTrue(EntityHookGate.isTileHook(List.of(SP), List.of("die", "hurtServer"), true));
    }

    @Test
    void serverThreadOnlyMethodsStillNeedPlayerTargetsUnderConfinement() {
        assertTrue(EntityHookGate.isTileHook(List.of(LIVING), List.of("die", "readAdditionalSaveData"), true));
        assertTrue(EntityHookGate.isTileHook(List.of(SP, LIVING), List.of("die", "permissions"), true));
    }

    @Test
    void lootTableDropsAreNotDeathOnly() {
        // Shearing, gifts, interaction loot and Creeper.killedEntity call it outside die.
        assertTrue(EntityHookGate.isTileHook(List.of(LIVING), List.of("dropFromLootTable"), true));
    }

    @Test
    void unknownAndEmptyInjectorsAreUnchangedByConfinement() {
        assertTrue(EntityHookGate.isTileHook(List.of(LIVING), null, true));
        assertFalse(EntityHookGate.isTileHook(List.of(LIVING), List.of(), true));
    }

    @Test
    void onlyEventsFiredInsideDieLeaveTheTilesUnderConfinement() {
        assertFalse(EntityHookGate.isTileEvent("ServerLivingEntityEvents.AFTER_DEATH", true));
        assertFalse(EntityHookGate.isTileEvent("ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY", true));
        // Decisions are made inside hurtServer, before die: they still run on the tile.
        assertTrue(EntityHookGate.isTileEvent("ServerLivingEntityEvents.ALLOW_DEATH", true));
        assertTrue(EntityHookGate.isTileEvent("ServerLivingEntityEvents.ALLOW_DAMAGE", true));
        assertTrue(EntityHookGate.isTileEvent("ServerLivingEntityEvents.AFTER_DAMAGE", true));
        assertTrue(EntityHookGate.isTileEvent("ServerPlayerEvents.ALLOW_DEATH", true));
        assertTrue(EntityHookGate.isTileEvent("ServerEntityEvents.EQUIPMENT_CHANGE", true));
        assertTrue(EntityHookGate.isTileEvent("ServerLivingEntityEvents.MOB_CONVERSION", true));
    }

    @Test
    void withConfinementOffEveryScannedEventIsATileEvent() {
        assertTrue(EntityHookGate.isTileEvent("ServerLivingEntityEvents.AFTER_DEATH", false));
        assertTrue(EntityHookGate.isTileEvent("ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY", false));
    }
}
