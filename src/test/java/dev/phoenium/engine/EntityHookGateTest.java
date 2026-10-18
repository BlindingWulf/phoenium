package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Stage 11 (2026-10-18): the engine's tile threads run non-player entity ticks, so a mod's entity damage/death callback
 * or entity mixin runs on two or three threads at once (s11tdds-D43-1: 10 overlapping entries into a plain map from
 * AFTER_DEATH on "Phoenium Tile 2"). The gate keeps tile parallelism only when every such hook belongs to a certified
 * stack mod; otherwise tiles run on the server thread alone, as with workers=0.
 */
class EntityHookGateTest {
    private static final Set<String> CERTIFIED = EntityHookGate.DEFAULT_CERTIFIED;

    private static EntityHookGate.Hook event(String mod) { return new EntityHookGate.Hook(mod, "event", "ServerLivingEntityEvents.AFTER_DEATH"); }
    private static EntityHookGate.Hook mixin(String mod, String target) { return new EntityHookGate.Hook(mod, "mixin", target); }

    @Test
    void noHooksKeepsParallel() {
        EntityHookGate.Decision d = EntityHookGate.decide(List.of(), CERTIFIED, "auto", Set.of());
        assertTrue(d.parallel());
        assertTrue(d.foreign().isEmpty());
    }

    @Test
    void certifiedStackHooksKeepParallel() {
        List<EntityHookGate.Hook> hooks = List.of(mixin("phoenium", "net.minecraft.world.entity.Entity"),
            mixin("lithium", "net.minecraft.world.entity.LivingEntity"), event("fabric-entity-events-v1"),
            mixin("fabric-api", "net.minecraft.world.entity.LivingEntity"), mixin("servercore", "net.minecraft.world.entity.Mob"),
            mixin("clumps", "net.minecraft.world.entity.ExperienceOrb"), mixin("c2me-base", "net.minecraft.world.entity.Entity"));
        EntityHookGate.Decision d = EntityHookGate.decide(hooks, CERTIFIED, "auto", Set.of());
        assertTrue(d.parallel(), d.describe());
    }

    @Test
    void foreignEventListenerTurnsTilesSerial() {
        EntityHookGate.Decision d = EntityHookGate.decide(List.of(event("phoenium-s11-archetypes"), event("fabric-entity-events-v1")),
            CERTIFIED, "auto", Set.of());
        assertFalse(d.parallel());
        assertEquals(List.of("phoenium-s11-archetypes"), d.foreign());
        assertTrue(d.describe().contains("phoenium-s11-archetypes"), d.describe());
    }

    @Test
    void foreignEntityMixinTurnsTilesSerial() {
        EntityHookGate.Decision d = EntityHookGate.decide(List.of(mixin("universal-graves", "net.minecraft.world.entity.LivingEntity")),
            CERTIFIED, "auto", Set.of());
        assertFalse(d.parallel());
        assertEquals(List.of("universal-graves"), d.foreign());
    }

    @Test
    void ownerAllowOverrideKeepsParallelButReportsForeignMods() {
        EntityHookGate.Decision d = EntityHookGate.decide(List.of(event("graves")), CERTIFIED, "allow", Set.of());
        assertTrue(d.parallel());
        assertEquals(List.of("graves"), d.foreign());
        assertTrue(d.describe().contains("override=allow"), d.describe());
    }

    @Test
    void perModAllowListExemptsOnlyThoseMods() {
        List<EntityHookGate.Hook> hooks = List.of(event("ledger"), mixin("graves", "net.minecraft.world.entity.LivingEntity"));
        assertFalse(EntityHookGate.decide(hooks, CERTIFIED, "auto", Set.of("ledger")).parallel());
        EntityHookGate.Decision d = EntityHookGate.decide(hooks, CERTIFIED, "auto", Set.of("ledger", "graves"));
        assertTrue(d.parallel(), d.describe());
    }

    @Test
    void unknownModeFailsSafeToSerial() {
        assertFalse(EntityHookGate.decide(List.of(event("graves")), CERTIFIED, "bogus", Set.of()).parallel());
    }

    @Test
    void foreignListIsSortedAndUnique() {
        List<EntityHookGate.Hook> hooks = List.of(event("zeta"), mixin("alpha", "net.minecraft.world.entity.Mob"), event("zeta"));
        assertEquals(List.of("alpha", "zeta"), EntityHookGate.decide(hooks, CERTIFIED, "auto", Set.of()).foreign());
    }

    @Test
    void entityTargetsAreEntityPackageAndServerPlayer() {
        assertTrue(EntityHookGate.isEntityTarget("net.minecraft.world.entity.LivingEntity"));
        assertTrue(EntityHookGate.isEntityTarget("net/minecraft/world/entity/projectile/arrow/AbstractArrow"));
        assertTrue(EntityHookGate.isEntityTarget("net.minecraft.server.level.ServerPlayer"));
        assertFalse(EntityHookGate.isEntityTarget("net.minecraft.server.level.ServerLevel"));
        assertFalse(EntityHookGate.isEntityTarget("net.minecraft.world.level.block.Block"));
        assertFalse(EntityHookGate.isEntityTarget("net.minecraft.server.MinecraftServer"));
    }

    @Test
    void certifiedMatchesFabricModulesAndListedIdsOnly() {
        assertTrue(EntityHookGate.certified("fabric-api", CERTIFIED));
        assertTrue(EntityHookGate.certified("fabric-entity-events-v1", CERTIFIED));
        assertTrue(EntityHookGate.certified("phoenium", CERTIFIED));
        assertFalse(EntityHookGate.certified("fabricated-mod", CERTIFIED));
        assertFalse(EntityHookGate.certified("universal-graves", CERTIFIED));
        assertFalse(EntityHookGate.certified(null, CERTIFIED));
    }

    // ---- gate v2 (owner 2026-10-18: do not serialize harmless mods; do not miss late callbacks)

    private static final String SP = "net.minecraft.server.level.ServerPlayer";
    private static final String PLAYER = "net.minecraft.world.entity.player.Player";

    @Test
    void serverThreadOnlyPlayerHooksAreNotTileHooks() {
        // luckperms 5.5.85: ServerPlayer permissions + restoreFrom; player-roles: readAdditionalSaveData, getTabListDisplayName
        assertFalse(EntityHookGate.isTileHook(List.of(SP), List.of("permissions", "restoreFrom")));
        assertFalse(EntityHookGate.isTileHook(List.of(SP), List.of("readAdditionalSaveData", "getTabListDisplayName")));
        assertFalse(EntityHookGate.isTileHook(List.of(PLAYER, SP), List.of("<init>", "addAdditionalSaveData", "onUpdateAbilities")));
    }

    @Test
    void playerDamageAndDeathHooksAreTileHooks() {
        // a mob's tile thread kills or hurts a player: essential-commands, styled-chat, sgui hook ServerPlayer.die/hurtServer
        assertTrue(EntityHookGate.isTileHook(List.of(SP), List.of("die")));
        assertTrue(EntityHookGate.isTileHook(List.of(SP), List.of("permissions", "hurtServer")));
        assertTrue(EntityHookGate.isTileHook(List.of(PLAYER), List.of("getDisplayName")));   // death messages read it on tiles
    }

    @Test
    void nonPlayerEntityHooksAreAlwaysTileHooks() {
        assertTrue(EntityHookGate.isTileHook(List.of("net.minecraft.world.entity.LivingEntity"), List.of("dropAllDeathLoot")));
        assertTrue(EntityHookGate.isTileHook(List.of("net.minecraft.world.entity.LivingEntity"), List.of("readAdditionalSaveData")));
        assertTrue(EntityHookGate.isTileHook(List.of(SP, "net.minecraft.world.entity.Entity"), List.of("permissions")));
    }

    @Test
    void unreadableSelectorsCountAsTileHooks() {
        assertTrue(EntityHookGate.isTileHook(List.of(SP), null));
    }

    @Test
    void mixinsWithoutInjectorsAddNoCallbacks() {
        // e.g. goml's Villager mixin and server_translations_api's ServerPlayer mixin only add fields or interfaces
        assertFalse(EntityHookGate.isTileHook(List.of("net.minecraft.world.entity.npc.villager.Villager"), List.of()));
        assertFalse(EntityHookGate.isTileHook(List.of(SP), List.of()));
    }

    @Test
    void lateListenerIsSeenByIdentityChange() {
        Object[] a = {new Object()}, b = {new Object()};
        List<Object[]> before = List.of(a, b);
        assertFalse(EntityHookGate.anyChanged(before, List.of(a, b)));
        Object[] grown = {a[0], new Object()};                      // ArrayBackedEvent.register replaces the array
        assertTrue(EntityHookGate.anyChanged(before, List.of(grown, b)));
        assertTrue(EntityHookGate.anyChanged(before, List.<Object[]>of(a)));   // a missing event counts as a change
    }
}
