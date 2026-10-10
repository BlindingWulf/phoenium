package dev.phoenium.engine;

import dev.phoenium.core.WorldId;
import dev.phoenium.live.MobDecisionContext;
import dev.phoenium.live.MobDecisionScheduler;
import dev.phoenium.live.PearlMembershipLedger;
import dev.phoenium.live.PearlOwnerAccess;
import dev.phoenium.live.ProjectileMembershipLedger;
import dev.phoenium.region.RegionId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S9-E5 (engine mode only): the Stage 7 protections with the engine's scopes. Policies, properties and defaults are default
 * mode's (each off unless enabled; the same scheduler and ledger classes); only the scope that default mode keys by owner
 * region is keyed here by the engine tile (4x4 chunks) holding the object:
 * <ul>
 * <li>A3 mob decisions ({@code phoenium.protection.mobDecisions.*}): (tile of the mob, category). The selector hooks are
 * default mode's, consulted from tile threads (the scheduler is atomic per key).</li>
 * <li>projectile cap ({@code phoenium.protection.projectileCap*}): (tile, projectile type). A projectile classified into
 * another tile moves its membership with its admission age, as a region change does in default mode.</li>
 * <li>pearl cap ({@code phoenium.protection.pearlCap*}): (world, owning player), as in default mode.</li>
 * </ul>
 * Admission at tracking start, retirement at tracking end (Fabric's entity load/unload events, on the server thread: engine
 * mode applies entity adds, removals and section moves out of tiles there). A selected excess member is discarded at the end
 * of the server tick (no tile running, no entity iteration in progress) once, if its identity is still a live member;
 * the cleanup follows the identity to its current membership (default mode reroutes it to the current owner). An object
 * gone by then settles as a rejection. The incarnation is the native entity id (new for every entity object, including a
 * reload of the same UUID).
 *
 * <p>S9-E8: a membership follows its object when the object's tile changes, not at its next decision, load or
 * classification. The transition is the entity's section change as the engine applies it (PersistentEntitySectionManager
 * Callback.onMove storing the new section: inline on the server thread, or after the tile's colour / phase for a move made
 * in a tile; every tile change is a section change). There the A3 membership is relocated (MobDecisionScheduler.relocate,
 * which keeps the pending deadline, as default mode does when the owner region changes) and a projectile is readmitted to
 * its new tile with its admission age. A dimension change, a conversion and an unload are a removal plus an add of an
 * entity object, i.e. a retirement plus an admission. Every {@link EngineConfig#PROTECTION_CHECK} ticks (start of the server
 * tick: no tile running, every deferred move and removal applied) each membership is checked against its object: no live
 * object (orphan) or another tile / world than the object's (stale) is counted, logged and repaired; the six counters and
 * projectileLateRelocations (a tile change first seen at classification) must stay 0.
 */
public final class EngineProtections {
    private EngineProtections() { }

    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");
    private static final MobDecisionScheduler MOBS = new MobDecisionScheduler(MobDecisionScheduler.Settings.fromProperties());
    private static final ProjectileMembershipLedger PROJECTILES = new ProjectileMembershipLedger();
    private static final PearlMembershipLedger PEARLS = new PearlMembershipLedger();
    public static final boolean MOB_DECISIONS = MOBS.settings().enabled();
    static final boolean PROJECTILE_CAP = PROJECTILES.settings().enabled();
    static final boolean PEARL_CAP = PEARLS.settings().enabled();
    private static final boolean ANY = MOB_DECISIONS || PROJECTILE_CAP || PEARL_CAP;

    private static final Map<WorldId, Map<Long, RegionId>> TILES = new ConcurrentHashMap<>();
    // server thread only
    private static final Map<Entity, ProjectileMembershipLedger.Membership> PROJECTILE_MEMBERS = new IdentityHashMap<>();
    private static final Map<ProjectileMembershipLedger.ProjectileIdentity, Entity> PROJECTILE_ALIASES = new HashMap<>();
    private static final Map<Entity, PearlMembershipLedger.Membership> PEARL_MEMBERS = new IdentityHashMap<>();
    private static final Map<PearlMembershipLedger.PearlIdentity, Entity> PEARL_ALIASES = new HashMap<>();
    private static final ArrayDeque<Cleanup> CLEANUPS = new ArrayDeque<>();
    private static int pendingProjectiles;
    private static int pendingPearls;
    private static long projectileAdmissions;
    private static long projectileExecutions;
    private static long projectileRejections;
    private static long projectileRelocations;
    private static long pearlAdmissions;
    private static long pearlExecutions;
    private static long pearlRejections;
    // S9-E8 transitions and self-check (server thread)
    private static long mobRelocations;
    private static long projectileLateRelocations;
    private static long checkTicks;
    private static long protectionChecks;
    private static long mobStale;
    private static long mobOrphans;
    private static long projectileStale;
    private static long projectileOrphans;
    private static long pearlStale;
    private static long pearlOrphans;
    private static final java.util.concurrent.atomic.LongAdder OFF_THREAD = new java.util.concurrent.atomic.LongAdder();
    private static int faultLogs;
    private static volatile boolean closed;

    private record Cleanup(Entity entity, boolean pearl) { }

    /** From TileEngine.install: listeners only when a protection is enabled. */
    static void install() {
        if (!ANY) return;
        ServerEntityEvents.ENTITY_LOAD.register(EngineProtections::loaded);
        ServerEntityEvents.ENTITY_UNLOAD.register(EngineProtections::unloaded);
        ServerTickEvents.END_SERVER_TICK.register(EngineProtections::drain);
        ServerTickEvents.START_SERVER_TICK.register(EngineProtections::check);
    }

    /** The engine tile holding a chunk, as a region identity for the default-mode scheduler and ledger scopes. */
    static RegionId tile(ServerLevel level, ChunkPos chunk) {
        WorldId world = WorldId.of(level.dimension().identifier().toString());
        int tileX = TileGeometry.tile(chunk.x(), EngineConfig.TILE_SHIFT);
        int tileZ = TileGeometry.tile(chunk.z(), EngineConfig.TILE_SHIFT);
        Map<Long, RegionId> tiles = TILES.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>());
        if (tiles.size() > 4096) tiles.clear();   // bounded; a scope identity is a value, made again on demand
        return tiles.computeIfAbsent(TileGeometry.key(tileX, tileZ), ignored -> new RegionId(world, "tile:" + tileX + "," + tileZ));
    }

    // ---- A3 mob decisions ----

    /** MobDecisionContext's gate in engine mode: the default-mode scheduler with the mob's tile as its scope. */
    public static boolean allowMobDecision(Mob mob) {
        if (!MOB_DECISIONS || closed || !(mob.level() instanceof ServerLevel level)) return true;
        MobDecisionScheduler.Category category = MobDecisionContext.category(mob.getClass());
        if (category == null) return true;
        String id = mob.getUUID().toString();
        MobDecisionScheduler.Scope scope = new MobDecisionScheduler.Scope(tile(level, mob.chunkPosition()), category);
        MOBS.observe(id, mob.getId(), mob.getId(), scope);
        return MOBS.allow(id, mob.getId(), scope, mob.tickCount, level.getGameTime(), mob.isPassenger() || mob.isVehicle());
    }

    /** (Re)observes one mob at its current tile, as default mode does when an object becomes addressable. */
    public static void observeMob(Entity entity) {
        if (!MOB_DECISIONS || closed || !(entity instanceof Mob) || !(entity.level() instanceof ServerLevel level)) return;
        MobDecisionScheduler.Category category = MobDecisionContext.category(entity.getClass());
        if (category != null) MOBS.observe(entity.getUUID().toString(), entity.getId(), entity.getId(),
            new MobDecisionScheduler.Scope(tile(level, entity.chunkPosition()), category));
    }

    public static MobDecisionScheduler.Snapshot mobDecisionSnapshot() { return MOBS.snapshot(); }

    // ---- admission, relocation, retirement (server thread) ----

    private static void loaded(Entity entity, ServerLevel level) {
        if (closed) return;
        TileContext context = TileEngine.current();
        if (context != null) {                       // not expected (adds are deferred out of tiles): stay on the server thread
            context.defer(() -> loaded(entity, level));
            return;
        }
        observeMob(entity);
        if (PEARL_CAP && entity instanceof ThrownEnderpearl pearl) admitPearl(pearl, level);
        if (PROJECTILE_CAP && entity instanceof Projectile) admitProjectile(entity, tile(level, entity.chunkPosition()));
    }

    private static void unloaded(Entity entity, ServerLevel level) {
        if (closed) return;
        TileContext context = TileEngine.current();
        if (context != null) {
            context.defer(() -> unloaded(entity, level));
            return;
        }
        if (MOB_DECISIONS && entity instanceof Mob) MOBS.retire(entity.getUUID().toString(), entity.getId());
        ProjectileMembershipLedger.Membership projectile = PROJECTILE_MEMBERS.remove(entity);
        if (projectile != null) {
            PROJECTILE_ALIASES.remove(projectile.identity(), entity);
            PROJECTILES.retire(projectile.objectId(), projectile.incarnation());
        }
        PearlMembershipLedger.Membership pearl = PEARL_MEMBERS.remove(entity);
        if (pearl != null) {
            PEARL_ALIASES.remove(pearl.identity(), entity);
            PEARLS.retire(pearl.objectId(), pearl.incarnation());
        }
    }

    private static void admitProjectile(Entity entity, RegionId tile) {
        String type = projectileType(entity);
        if (type == null) return;
        ProjectileMembershipLedger.Admission admission =
            PROJECTILES.admit(new ProjectileMembershipLedger.Scope(tile, type), entity.getUUID().toString(), entity.getId());
        PROJECTILE_MEMBERS.put(entity, admission.membership());
        PROJECTILE_ALIASES.put(admission.membership().identity(), entity);
        for (ProjectileMembershipLedger.Membership selected : admission.cleanup()) {
            Entity member = PROJECTILE_ALIASES.get(selected.identity());
            if (member == null) {                    // no live alias: settle at once
                PROJECTILES.settleCleanup(selected);
                projectileRejections++;
                continue;
            }
            CLEANUPS.add(new Cleanup(member, false));
            pendingProjectiles++;
            projectileAdmissions++;
        }
    }

    private static void admitPearl(ThrownEnderpearl pearl, ServerLevel level) {
        UUID player = PearlOwnerAccess.playerId(pearl);
        if (player == null) return;
        PearlMembershipLedger.Admission admission = PEARLS.admit(
            new PearlMembershipLedger.Scope(WorldId.of(level.dimension().identifier().toString()), player),
            pearl.getUUID().toString(), pearl.getId());
        PEARL_MEMBERS.put(pearl, admission.membership());
        PEARL_ALIASES.put(admission.membership().identity(), pearl);
        List<PearlMembershipLedger.Membership> cleanup = admission.cleanup();
        for (PearlMembershipLedger.Membership selected : cleanup) {
            Entity member = PEARL_ALIASES.get(selected.identity());
            if (member == null) {
                PEARLS.settleCleanup(selected);
                pearlRejections++;
                continue;
            }
            CLEANUPS.add(new Cleanup(member, true));
            pendingPearls++;
            pearlAdmissions++;
        }
    }

    /**
     * S9-E8: the entity's section change was stored (server thread; from the section callback): its memberships move to the
     * tile it is in now. Not expected in a tile (a tile's section changes are applied after its colour or phase) nor off the
     * server thread (counted as protectionOffThread; the self-check repairs what it left).
     */
    public static void sectionMoved(Entity entity) {
        if (!ANY || closed || !(entity.level() instanceof ServerLevel level)) return;
        TileContext context = TileEngine.current();
        if (context != null) {
            context.defer(() -> sectionMoved(entity));
            return;
        }
        if (!TileEngine.onServerThread()) {
            OFF_THREAD.increment();
            return;
        }
        RegionId tile = null;
        if (MOB_DECISIONS && entity instanceof Mob && MobDecisionContext.category(entity.getClass()) != null) {
            String id = entity.getUUID().toString();
            MobDecisionScheduler.Scope scope = MOBS.scopeOf(id);
            tile = tile(level, entity.chunkPosition());
            if (scope != null && !scope.region().equals(tile)) {
                MOBS.relocate(id, entity.getId(), tile);
                mobRelocations++;
            }
        }
        if (PROJECTILE_CAP) {
            ProjectileMembershipLedger.Membership membership = PROJECTILE_MEMBERS.get(entity);
            if (membership != null) {
                if (tile == null) tile = tile(level, entity.chunkPosition());
                if (!membership.scope().region().equals(tile)) {
                    projectileRelocations++;
                    admitProjectile(entity, tile);
                }
            }
        }
    }

    /**
     * From the entity phase's classification (server thread): a projectile now in another tile moves its membership. Since
     * S9-E8 the section change has moved it already; a move first seen here is counted (projectileLateRelocations, must be 0).
     */
    static void classified(ServerLevel level, Entity entity, ChunkPos chunk) {
        ProjectileMembershipLedger.Membership membership = PROJECTILE_MEMBERS.get(entity);
        if (membership == null || closed) return;
        RegionId tile = tile(level, chunk);
        if (membership.scope().region().equals(tile)) return;
        projectileLateRelocations++;
        fault("projectile relocated late", entity, membership.scope().region(), tile);
        projectileRelocations++;
        admitProjectile(entity, tile);
    }

    /**
     * S9-E8 self-check, every {@link EngineConfig#PROTECTION_CHECK} ticks at the start of the server tick (no tile running,
     * every deferred move and removal applied): each membership against its object. Repairs what it counts.
     */
    private static void check(MinecraftServer server) {
        if (closed || EngineConfig.PROTECTION_CHECK <= 0 || ++checkTicks % EngineConfig.PROTECTION_CHECK != 0) return;
        if (TileEngine.current() != null || !TileEngine.onServerThread()) return;
        protectionChecks++;
        Map<WorldId, ServerLevel> levels = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) levels.put(WorldId.of(level.dimension().identifier().toString()), level);
        if (MOB_DECISIONS) {
            for (MobDecisionScheduler.MemberView member : MOBS.snapshot().members()) {
                ServerLevel level = levels.get(member.scope().region().world());
                Entity entity = level == null ? null : level.getEntity(UUID.fromString(member.id()));
                if (entity == null || entity.isRemoved() || entity.getId() != member.incarnation()) {
                    mobOrphans++;
                    fault("A3 orphan", member.id(), member.scope().region(), null);
                    MOBS.retire(member.id(), member.incarnation());
                    continue;
                }
                RegionId tile = tile(level, entity.chunkPosition());
                if (!tile.equals(member.scope().region())) {
                    mobStale++;
                    fault("A3 stale", entity, member.scope().region(), tile);
                    MOBS.relocate(member.id(), member.registryIncarnation(), tile);
                }
            }
        }
        for (Entity entity : new ArrayList<>(PROJECTILE_MEMBERS.keySet())) {
            ProjectileMembershipLedger.Membership membership = PROJECTILE_MEMBERS.get(entity);
            if (membership == null) continue;
            if (entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) {
                projectileOrphans++;
                fault("projectile orphan", entity, membership.scope().region(), null);
                PROJECTILE_MEMBERS.remove(entity, membership);
                PROJECTILE_ALIASES.remove(membership.identity(), entity);
                PROJECTILES.retire(membership.objectId(), membership.incarnation());
                continue;
            }
            RegionId tile = tile(level, entity.chunkPosition());
            if (!tile.equals(membership.scope().region())) {
                projectileStale++;
                fault("projectile stale", entity, membership.scope().region(), tile);
                projectileRelocations++;
                admitProjectile(entity, tile);
            }
        }
        for (Entity entity : new ArrayList<>(PEARL_MEMBERS.keySet())) {
            PearlMembershipLedger.Membership membership = PEARL_MEMBERS.get(entity);
            if (membership == null) continue;
            boolean live = !entity.isRemoved() && entity.level() instanceof ServerLevel;
            WorldId world = live ? WorldId.of(entity.level().dimension().identifier().toString()) : null;
            if (live && world.equals(membership.scope().world())) continue;
            if (live) pearlStale++; else pearlOrphans++;
            fault(live ? "pearl stale" : "pearl orphan", entity, null, null);
            PEARL_MEMBERS.remove(entity, membership);
            PEARL_ALIASES.remove(membership.identity(), entity);
            PEARLS.retire(membership.objectId(), membership.incarnation());
            if (live && entity instanceof ThrownEnderpearl pearl) admitPearl(pearl, (ServerLevel) entity.level());
        }
    }

    private static void fault(String what, Object object, RegionId had, RegionId now) {
        if (faultLogs >= 10) return;
        faultLogs++;
        String name = object instanceof Entity entity ? entity.getType() + " " + entity.getUUID() + " id " + entity.getId()
            + " at " + entity.chunkPosition() : String.valueOf(object);
        LOGGER.warn("PHOENIUM_ENGINE protection {}: {} membership {} object tile {}", what, name, had, now);
    }

    /** End of the server tick: each selected member still live is discarded once, under its current membership. */
    private static void drain(MinecraftServer server) {
        Cleanup cleanup;
        while ((cleanup = CLEANUPS.poll()) != null) {
            Entity entity = cleanup.entity();
            boolean executed;
            if (cleanup.pearl()) {
                pendingPearls--;
                PearlMembershipLedger.Membership current = PEARL_MEMBERS.get(entity);
                executed = current != null && !closed && PEARLS.isCurrent(current) && !entity.isRemoved();
                if (executed) entity.discard();      // its unload retires the membership
                if (current != null && !closed) {
                    PEARLS.settleCleanup(current);
                    PEARL_MEMBERS.remove(entity, current);
                    PEARL_ALIASES.remove(current.identity(), entity);
                }
                if (executed) pearlExecutions++; else pearlRejections++;
            } else {
                pendingProjectiles--;
                ProjectileMembershipLedger.Membership current = PROJECTILE_MEMBERS.get(entity);
                executed = current != null && !closed && PROJECTILES.isCurrent(current) && !entity.isRemoved();
                if (executed) entity.discard();
                if (current != null && !closed) {
                    PROJECTILES.settleCleanup(current);
                    PROJECTILE_MEMBERS.remove(entity, current);
                    PROJECTILE_ALIASES.remove(current.identity(), entity);
                }
                if (executed) projectileExecutions++; else projectileRejections++;
            }
        }
    }

    private static String projectileType(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? null : key.toString();
    }

    // ---- lifecycle and diagnostics (server thread) ----

    /** At engine stop: later loads and unloads are ignored, every table is empty. */
    static void close() {
        if (closed) return;
        closed = true;
        drain(null);                                 // pending cleanups settle as rejections
        MOBS.close();
        PROJECTILE_MEMBERS.clear();
        PROJECTILE_ALIASES.clear();
        PEARL_MEMBERS.clear();
        PEARL_ALIASES.clear();
        PROJECTILES.close();
        PEARLS.close();
    }

    /** Default mode's diagnostics names for the same quantities, plus the A3 snapshot totals. */
    static String describe() {
        MobDecisionScheduler.Snapshot mobs = MOBS.snapshot();
        return "pearlMemberships=" + PEARL_MEMBERS.size() + " pendingPearlCleanups=" + pendingPearls
            + " pearlCap=" + (PEARL_CAP ? PEARLS.settings().cap() : "OFF") + " pearlCleanupAdmissions=" + pearlAdmissions
            + " pearlCleanupExecutions=" + pearlExecutions + " pearlCleanupRejections=" + pearlRejections
            + " projectileMemberships=" + PROJECTILE_MEMBERS.size() + " pendingProjectileCleanups=" + pendingProjectiles
            + " projectileCap=" + (PROJECTILE_CAP ? PROJECTILES.settings().cap() : "OFF")
            + " projectileCleanupAdmissions=" + projectileAdmissions + " projectileCleanupExecutions=" + projectileExecutions
            + " projectileCleanupRejections=" + projectileRejections + " projectileRelocations=" + projectileRelocations
            + " mobDecisions=" + (MOB_DECISIONS ? "ON" : "OFF") + " mobDecisionThreshold=" + MOBS.settings().threshold()
            + " mobDecisionMemberships=" + mobs.memberships() + " mobDecisionScopes=" + mobs.scopes()
            + " mobDecisionAllowed=" + mobs.allowed() + " mobDecisionDeferred=" + mobs.deferred()
            + " mobDecisionDeadlines=" + mobs.deadlines()
            + " mobDecisionRelocations=" + mobRelocations + " projectileLateRelocations=" + projectileLateRelocations
            + " protectionChecks=" + protectionChecks + " mobDecisionStale=" + mobStale + " mobDecisionOrphans=" + mobOrphans
            + " projectileStale=" + projectileStale + " projectileOrphans=" + projectileOrphans + " pearlStale=" + pearlStale
            + " pearlOrphans=" + pearlOrphans + " protectionOffThread=" + OFF_THREAD.sum();
    }
}
