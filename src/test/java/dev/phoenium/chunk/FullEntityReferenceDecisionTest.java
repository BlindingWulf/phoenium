package dev.phoenium.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original reference/lookup/consumer methods; controlled world directories and predicate leaves. */
final class FullEntityReferenceDecisionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void sameOwnerControlAndCachedReferenceUseTheActualIncarnation() throws Exception {
        UUID id = UUID.randomUUID(); var target = target(id); AtomicInteger lookups = new AtomicInteger();
        var ref = EntityReference.<LivingEntity>of(id);
        var level = lookup(id, new AtomicReference<>(target), lookups);
        set(target, Entity.class, "level", level);
        assertSame(target, ref.getEntity(level, LivingEntity.class));
        assertSame(target, ref.getEntity(level, LivingEntity.class));
        assertEquals(1, lookups.get());
        assertEquals(id, ref.getUUID());
    }

    @Test void originalAnyDimensionLookupUsesOriginThenServerOrderAndDoesNotSkipWrongType() throws Exception {
        UUID id = UUID.randomUUID(); List<String> order = new ArrayList<>();
        LivingEntity foreign = target(id);
        ServerLevel second = stub(ServerLevel.class, List.of(new Answer(ServerLevel.class.getMethod("getEntity", UUID.class),
                args -> { order.add("second"); return foreign; })));
        ServerLevel third = stub(ServerLevel.class, List.of(new Answer(ServerLevel.class.getMethod("getEntity", UUID.class),
                args -> { order.add("third"); throw new AssertionError("first nonnull wins"); })));
        MinecraftServer server = stub(MinecraftServer.class, List.of(new Answer(MinecraftServer.class.getMethod("getAllLevels"),
                args -> List.of(second, third))));
        AtomicReference<Entity> local = new AtomicReference<>();
        ServerLevel origin = stub(ServerLevel.class, List.of(
                new Answer(ServerLevel.class.getMethod("getEntity", UUID.class), args -> { order.add("origin"); return local.get(); }),
                new Answer(ServerLevel.class.getMethod("getServer"), args -> server)));
        assertSame(foreign, EntityReference.getLivingEntity(EntityReference.of(id), origin));
        assertEquals(List.of("origin", "second"), order);
        order.clear(); local.set(stub(Entity.class, List.of()));
        assertNull(EntityReference.getLivingEntity(EntityReference.of(id), origin));
        assertEquals(List.of("origin"), order, "type filtering follows lookup; it does not try another level");
    }

    @Test void crossDimensionTargetStorageActuallyInvokesMutablePredicateAndKeepsIdentity() throws Exception {
        List<String> calls = new ArrayList<>();
        LivingEntity foreign = stub(LivingEntity.class, List.of(
                new Answer(LivingEntity.class.getMethod("canBeSeenAsEnemy"), args -> { calls.add("foreign-predicate"); return true; })));
        set(foreign, Entity.class, "type", EntityTypes.PIG);
        Mob caller = stub(Mob.class, List.of());
        ServerLevel origin = stub(ServerLevel.class, List.of()), nether = stub(ServerLevel.class, List.of());
        set(origin, Level.class, "dimension", Level.OVERWORLD); set(nether, Level.class, "dimension", Level.NETHER);
        set(caller, Entity.class, "level", origin); set(foreign, Entity.class, "level", nether);
        caller.setTarget(foreign); // Actual Mob.asValidTarget -> Mob/LivingEntity.canAttack.
        assertSame(foreign, caller.getTargetUnchecked());
        assertEquals(List.of("foreign-predicate"), calls);
        caller.getTarget();
        assertEquals(2, calls.size(), "cached Mob.target reuse validates again; raw pointer is not a read permit");
    }

    @Test void removalBetweenLookupAndUseRequiresValidationAndThenResolvesReplacement() throws Exception {
        UUID id = UUID.randomUUID(); LivingEntity old = target(id), replacement = target(id);
        AtomicReference<LivingEntity> current = new AtomicReference<>(old); AtomicInteger lookups = new AtomicInteger();
        ServerLevel level = lookup(id, current, lookups); var ref = EntityReference.<LivingEntity>of(id);
        LivingEntity retained = ref.getEntity(level, LivingEntity.class);
        set(old, Entity.class, "removalReason", Entity.RemovalReason.DISCARDED); current.set(replacement);
        assertSame(old, retained, "a returned Java reference does not turn into the new incarnation");
        assertTrue(retained.isRemoved());
        assertSame(replacement, ref.getEntity(level, LivingEntity.class)); assertEquals(2, lookups.get());
    }

    @Test void movementAndDimensionChangeDoNotInvalidateVanillasLiveCacheButDoInvalidateAuthorityCut() throws Exception {
        UUID id = UUID.randomUUID(); LivingEntity entity = target(id); AtomicInteger lookups = new AtomicInteger();
        ServerLevel origin = lookup(id, new AtomicReference<>(entity), lookups);
        var ref = EntityReference.<LivingEntity>of(id); assertSame(entity, ref.getEntity(origin, LivingEntity.class));
        ServerLevel foreign = stub(ServerLevel.class, List.of());
        set(entity, Entity.class, "level", foreign); set(entity, Entity.class, "position", new Vec3(4096, 80, 0));
        assertSame(entity, ref.getEntity(origin, LivingEntity.class)); assertEquals(1, lookups.get());
        // An owner-published receipt changes independently of the cached Java reference.
        var before = new IdentityCut(entity, 7, 11); var afterMove = new IdentityCut(entity, 7, 12);
        assertFalse(before.validAt(afterMove));
        assertFalse(before.validAt(new IdentityCut(target(id), 8, 11)));
    }

    @Test void deniedPresentEntityAccessIsNotConvertedIntoNotFound() throws Exception {
        UUID id = UUID.randomUUID(); LivingEntity entity = target(id);
        var denied = new ChunkProtocolException("present foreign target: fact entitlement unavailable");
        ServerLevel level = stub(ServerLevel.class, List.of(new Answer(ServerLevel.class.getMethod("getEntityInAnyDimension", UUID.class),
                args -> { throw denied; })));
        var ref = EntityReference.<LivingEntity>of(id);
        assertSame(denied, assertThrows(ChunkProtocolException.class, () -> ref.getEntity(level, LivingEntity.class)));
        assertSame(entity, ref.getEntity(lookup(id, new AtomicReference<>(entity), new AtomicInteger()), LivingEntity.class));
    }

    @Test void originalProjectilePairingConsumesOwnersIdWithoutTargetGameplayCalls() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        Entity owner = stub(Entity.class, List.of(new Answer(Entity.class.getMethod("getId"), args -> { ids.incrementAndGet(); return 709; })));
        Projectile projectile = stub(Projectile.class, List.of());
        projectile.setId(708);
        set(projectile, Entity.class, "level", stub(ServerLevel.class, List.of()));
        set(projectile, Entity.class, "uuid", UUID.randomUUID()); set(projectile, Entity.class, "type", EntityTypes.ARROW);
        projectile.setOwner(owner);
        ServerEntity tracker = stub(ServerEntity.class, List.of(
                new Answer(ServerEntity.class.getMethod("getPositionBase"), args -> Vec3.ZERO),
                new Answer(ServerEntity.class.getMethod("getLastSentMovement"), args -> Vec3.ZERO)));
        ClientboundAddEntityPacket packet = (ClientboundAddEntityPacket) projectile.getAddEntityPacket(tracker);
        assertEquals(709, packet.getData()); assertEquals(1, ids.get()); assertSame(owner, projectile.getOwner());
    }

    @Test void originalNeutralMobSavedReaderResolvesForeignReferenceThenUsesConcreteGolemValidation() throws Exception {
        UUID id = UUID.randomUUID(); AtomicInteger predicates = new AtomicInteger();
        LivingEntity target = stub(LivingEntity.class, List.of(new Answer(LivingEntity.class.getMethod("canBeSeenAsEnemy"),
                args -> { predicates.incrementAndGet(); return true; })));
        set(target, Entity.class, "uuid", id); set(target, Entity.class, "type", EntityTypes.PIG);
        set(target, Entity.class, "level", stub(ServerLevel.class, List.of()));
        ServerLevel origin = lookup(id, new AtomicReference<>(target), new AtomicInteger());
        AtomicReference<EntityReference<LivingEntity>> retained = new AtomicReference<>();
        var golemType = net.minecraft.world.entity.animal.golem.IronGolem.class;
        var golem = stub(golemType, List.of(
                new Answer(golemType.getMethod("isPlayerCreated"), args -> false),
                new Answer(golemType.getMethod("setPersistentAngerTarget", EntityReference.class), args -> {
                    @SuppressWarnings("unchecked") var ref = (EntityReference<LivingEntity>)args[0]; retained.set(ref); return null;
                }),
                new Answer(golemType.getMethod("getPersistentAngerTarget"), args -> retained.get())));
        set(golem, Entity.class, "level", origin);
        var tag = new net.minecraft.nbt.CompoundTag(); tag.putLong("anger_end_time", 42);
        tag.putIntArray("angry_at", net.minecraft.core.UUIDUtil.uuidToIntArray(id));
        golem.readPersistentAngerSaveData(origin, net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, net.minecraft.core.RegistryAccess.EMPTY, tag));
        assertEquals(42, golem.getPersistentAngerEndTime()); assertSame(target, golem.getTargetUnchecked());
        assertEquals(1, predicates.get()); assertSame(target, retained.get().getEntity(origin, LivingEntity.class));
    }

    @Test void concretePlayerAxolotlAndArmorStandPredicatesReadDifferentState() throws Exception {
        var mode = new AtomicReference<>(net.minecraft.world.level.GameType.SURVIVAL);
        var player = stub(net.minecraft.server.level.ServerPlayer.class, List.of(
                new Answer(net.minecraft.server.level.ServerPlayer.class.getMethod("gameMode"), args -> mode.get()),
                new Answer(LivingEntity.class.getMethod("getHealth"), args -> 20F)));
        var abilities = new net.minecraft.world.entity.player.Abilities();
        set(player, net.minecraft.world.entity.player.Player.class, "abilities", abilities);
        assertTrue(player.canBeSeenAsEnemy()); abilities.invulnerable = true; assertFalse(player.canBeSeenAsEnemy());
        abilities.invulnerable = false; mode.set(net.minecraft.world.level.GameType.SPECTATOR); assertFalse(player.canBeSeenAsEnemy());
        var dead = new AtomicReference<>(false);
        var axolotl = stub(net.minecraft.world.entity.animal.axolotl.Axolotl.class, List.of(
                new Answer(net.minecraft.world.entity.animal.axolotl.Axolotl.class.getMethod("isPlayingDead"), args -> dead.get()),
                new Answer(LivingEntity.class.getMethod("getHealth"), args -> 14F)));
        assertTrue(axolotl.canBeSeenAsEnemy()); dead.set(true); assertFalse(axolotl.canBeSeenAsEnemy());
        var marker = new AtomicReference<>(false);
        var stand = stub(net.minecraft.world.entity.decoration.ArmorStand.class, List.of(
                new Answer(Entity.class.getMethod("isInvisible"), args -> false),
                new Answer(net.minecraft.world.entity.decoration.ArmorStand.class.getMethod("isMarker"), args -> marker.get()),
                new Answer(LivingEntity.class.getMethod("getHealth"), args -> { throw new AssertionError("ArmorStand override does not read health"); })));
        assertTrue(stand.canBeSeenAsEnemy()); marker.set(true); assertFalse(stand.canBeSeenAsEnemy());
    }

    @Test void savedFishingHookReaderIgnoresOwnerRatherThanCallingTheMutatingSetOwnerOverride() throws Exception {
        var hookType = net.minecraft.world.entity.projectile.FishingHook.class;
        var hook = stub(hookType, List.of(new Answer(hookType.getMethod("setOwner", Entity.class),
                args -> { throw new AssertionError("not a saved load edge"); })));
        set(hook, Entity.class, "level", stub(ServerLevel.class, List.of()));
        var tag = new net.minecraft.nbt.CompoundTag(); tag.putIntArray("Owner", net.minecraft.core.UUIDUtil.uuidToIntArray(UUID.randomUUID()));
        var reader = hookType.getDeclaredMethod("readAdditionalSaveData", net.minecraft.world.level.storage.ValueInput.class);
        reader.setAccessible(true); reader.invoke(hook, net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, net.minecraft.core.RegistryAccess.EMPTY, tag));
        assertNull(hook.getOwner()); assertNull(hook.getPlayerOwner());
    }

    private record IdentityCut(Entity object, long incarnation, long revision) {
        boolean validAt(IdentityCut now) { return object == now.object && incarnation == now.incarnation && revision == now.revision; }
    }
    private static LivingEntity target(UUID id) throws Exception {
        LivingEntity target = stub(LivingEntity.class, List.of()); set(target, Entity.class, "uuid", id); return target;
    }
    private static ServerLevel lookup(UUID id, AtomicReference<LivingEntity> target, AtomicInteger count) throws Exception {
        return stub(ServerLevel.class, List.of(new Answer(ServerLevel.class.getMethod("getEntityInAnyDimension", UUID.class),
                args -> { assertEquals(id, args[0]); count.incrementAndGet(); return target.get(); })));
    }
}
