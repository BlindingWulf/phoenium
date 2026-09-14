package dev.phoenium.stage7;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.ClassReader;
import sun.misc.Unsafe;

/** Native 26.2 characterization, not acceptance of a production staggering implementation. */
final class A3NativeBoundaryTest {
    private static final String ENTITY = "net/minecraft/world/entity/";
    private static final String AI = ENTITY + "ai/";
    private static final String CART = ENTITY + "vehicle/minecart/";

    @Test void mobAiBoundaryIncludesSemanticClockAndAllControls() throws Exception {
        MethodNode method = method(ENTITY + "Mob", "serverAiStep");
        assertTrue((method.access & Opcodes.ACC_FINAL) != 0);
        assertTrue(writes(method, "noActionTime"));
        assertEquals(1, count(method, AI + "sensing/Sensing", "tick"));
        assertEquals(2, count(method, AI + "goal/GoalSelector", "tick"));
        assertEquals(2, count(method, AI + "goal/GoalSelector", "tickRunningGoals"));
        assertEquals(1, count(method, AI + "navigation/PathNavigation", "tick"));
        assertEquals(1, count(method, ENTITY + "Mob", "customServerAiStep"));
        for (String control : List.of("MoveControl", "LookControl", "JumpControl"))
            assertEquals(1, count(method, AI + "control/" + control, "tick"));
    }

    @Test void selectorSeparatesCleanupAcquisitionAndRunningGoals() throws Exception {
        MethodNode method = method(AI + "goal/GoalSelector", "tick");
        List<String> calls = calls(method);
        int cleanup = calls.indexOf(AI + "goal/WrappedGoal.canContinueToUse");
        int acquisition = calls.indexOf(AI + "goal/WrappedGoal.canUse");
        int running = calls.indexOf(AI + "goal/GoalSelector.tickRunningGoals");
        assertTrue(cleanup >= 0 && cleanup < acquisition && acquisition < running);
        assertEquals(1, count(method, AI + "goal/WrappedGoal", "canUse"));
        assertEquals(1, count(method, AI + "goal/WrappedGoal", "start"));
    }

    @Test void nativeSelectorKeepsRunningGoalTicksIndependentOfNewAcquisition() {
        GoalSelector selector = new GoalSelector();
        CountingGoal running = new CountingGoal();
        selector.addGoal(0, running);
        selector.tick();
        assertEquals(1, running.starts);
        assertEquals(1, running.ticks);
        selector.tickRunningGoals(false);
        assertEquals(2, running.ticks);
        assertEquals(1, running.acquisitions);
        running.continueRunning = false;
        // Merely calling tickRunningGoals cannot perform native cleanup.
        selector.tickRunningGoals(false);
        assertEquals(0, running.stops);
        running.allowStart = false;
        selector.tick();
        assertEquals(1, running.stops);
        assertEquals(3, running.ticks);
    }

    @Test void nativeTemptationCooldownLivesInsideCanUseAndMustNotBeDeferred() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        // The cooldown branch does not read mob/world; use the real method without a server fixture.
        TemptGoal goal = (TemptGoal) unsafe.allocateInstance(TemptGoal.class);
        Field cooldown = TemptGoal.class.getDeclaredField("calmDown");
        cooldown.setAccessible(true);
        cooldown.setInt(goal, 50);
        for (int i = 0; i < 20; i++) assertFalse(goal.canUse());
        assertEquals(30, cooldown.getInt(goal));
        assertTrue(writes(method(AI + "goal/TemptGoal", "canUse"), "calmDown"));
        assertEquals(1, count(method(AI + "goal/TemptGoal", "canContinueToUse"),
            AI + "goal/TemptGoal", "canUse"));
    }

    @Test void nativeBreedingAdvancesThroughRunningGoalAndAnimalLifecycle() throws Exception {
        MethodNode tick = method(AI + "goal/BreedGoal", "tick");
        assertTrue(writes(tick, "loveTime"));
        assertEquals(1, count(tick, AI + "navigation/PathNavigation", "moveTo"));
        assertEquals(1, count(tick, AI + "goal/BreedGoal", "breed"));
        assertEquals(2, count(method(ENTITY + "AgeableMob", "aiStep"), ENTITY + "AgeableMob", "setAge"));
        assertTrue(writes(method(ENTITY + "animal/Animal", "aiStep"), "inLove"));
    }

    @Test void sensingTickOnlyInvalidatesLineOfSightCaches() throws Exception {
        List<String> calls = calls(method(AI + "sensing/Sensing", "tick"));
        assertEquals(List.of("it/unimi/dsi/fastutil/ints/IntSet.clear",
            "it/unimi/dsi/fastutil/ints/IntSet.clear"), calls);
        assertEquals(1, count(method(AI + "sensing/Sensing", "hasLineOfSight"),
            ENTITY + "Mob", "hasLineOfSight"));
    }

    @Test void navigationTickIncludesProgressAndDelayedRecomputeIsSeparate() throws Exception {
        MethodNode tick = method(AI + "navigation/PathNavigation", "tick");
        assertTrue(writes(tick, "tick"));
        assertEquals(1, count(tick, AI + "navigation/PathNavigation", "recomputePath"));
        assertEquals(1, count(tick, AI + "navigation/PathNavigation", "followThePath"));
        assertEquals(1, count(tick, AI + "control/MoveControl", "setWantedPosition"));
        MethodNode recompute = method(AI + "navigation/PathNavigation", "recomputePath");
        assertTrue(writes(recompute, "hasDelayedRecomputation"));
        assertTrue(writes(recompute, "timeLastRecompute"));
        assertEquals(1, count(recompute, AI + "navigation/PathNavigation", "createPath"));
        assertEquals(2, count(recompute, "net/minecraft/world/level/Level", "getGameTime"));
    }

    @Test void synchronousAttackPathCreationBypassesDelayedRecompute() throws Exception {
        MethodNode attack = method(AI + "goal/MeleeAttackGoal", "canUse");
        assertEquals(1, count(attack, AI + "navigation/PathNavigation", "createPath"));
        assertEquals(0, count(attack, AI + "navigation/PathNavigation", "recomputePath"));
        assertTrue(writes(attack, "lastCanUseCheck"));
    }

    @Test void ordinaryCartTickOwnsTimersPortalAndVehiclePhysics() throws Exception {
        MethodNode tick = method(CART + "AbstractMinecart", "tick");
        for (String name : List.of("setHurtTime", "setDamage", "handlePortal", "updateFluidInteraction"))
            assertEquals(1, count(tick, CART + "AbstractMinecart", name));
        assertEquals(1, count(tick, CART + "MinecartBehavior", "tick"));
        MethodNode oldTick = method(CART + "OldMinecartBehavior", "tick");
        assertEquals(1, count(oldTick, CART + "OldMinecartBehavior", "moveAlongTrack"));
        assertEquals(1, count(oldTick, CART + "OldMinecartBehavior", "pushAndPickupEntities"));
    }

    @Test void bothMinecartCollisionPathsMutatePassengersAndVelocity() throws Exception {
        MethodNode old = method(CART + "OldMinecartBehavior", "pushAndPickupEntities");
        assertEquals(1, count(old, ENTITY + "Entity", "startRiding"));
        assertEquals(2, count(old, ENTITY + "Entity", "push"));
        MethodNode pickup = method(CART + "NewMinecartBehavior", "pickupEntities");
        assertEquals(1, count(pickup, ENTITY + "Entity", "startRiding"));
        MethodNode push = method(CART + "NewMinecartBehavior", "pushEntities");
        assertEquals(2, count(push, ENTITY + "Entity", "push"));
        MethodNode pair = method(CART + "AbstractMinecart", "pushOtherMinecart");
        assertTrue(count(pair, CART + "AbstractMinecart", "setDeltaMovement") > 1);
        assertTrue(count(pair, CART + "AbstractMinecart", "getDeltaMovement") > 1);
    }

    @Test void experimentalCartCollisionResultControlsImmediateFurtherMovement() throws Exception {
        List<String> calls = calls(method(CART + "AbstractMinecart", "move"));
        int collision = calls.indexOf(CART + "MinecartBehavior.pushAndPickupEntities");
        assertTrue(collision > 0);
        assertTrue(calls.subList(collision + 1, calls.size()).stream().anyMatch(s -> s.endsWith(".move")));
    }

    @Test void serverNativeTickAdvancesAgeThenTicksEntityThenPassengers() throws Exception {
        MethodNode tick = method("net/minecraft/server/level/ServerLevel", "tickNonPassenger");
        assertTrue(writes(tick, "tickCount"));
        List<String> calls = calls(tick);
        assertTrue(calls.indexOf(ENTITY + "Entity.tick") <
            calls.indexOf("net/minecraft/server/level/ServerLevel.tickPassenger"));
        assertEquals(1, count(method("net/minecraft/server/level/ServerLevel", "tickPassenger"),
            ENTITY + "Entity", "rideTick"));
    }

    @Test void oldUuidPhaseSpecificationDoesNotBoundPeakWorkForAdversarialIdentities() {
        int population = 4096;
        int stride = 4;
        int[] slots = new int[stride];
        for (int i = 0; i < population; i++) {
            UUID uuid = new UUID(0, ((long) i) << 32 | Integer.toUnsignedLong(i));
            assertEquals(0, uuid.hashCode());
            for (int tick = 0; tick < stride; tick++)
                if (NativeProtectionPolicyModel.shouldRun(tick, uuid, stride)) slots[tick]++;
        }
        assertArrayEquals(new int[] {population, 0, 0, 0}, slots,
            "fixed-stride fairness alone does not establish smoothing or a hard CPU budget");
    }

    private static final class CountingGoal extends Goal {
        int acquisitions, starts, ticks, stops;
        boolean continueRunning = true, allowStart = true;
        @Override public boolean canUse() { acquisitions++; return allowStart; }
        @Override public boolean canContinueToUse() { return continueRunning; }
        @Override public void start() { starts++; }
        @Override public void stop() { stops++; }
        @Override public void tick() { ticks++; }
        @Override public boolean requiresUpdateEveryTick() { return true; }
    }

    private static MethodNode method(String name, String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = A3NativeBoundaryTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        List<MethodNode> matches = node.methods.stream().filter(m -> m.name.equals(method)).toList();
        assertEquals(1, matches.size(), name + "." + method);
        return matches.getFirst();
    }
    private static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) result.add(call.owner + "." + call.name);
        return result;
    }
    private static long count(MethodNode method, String owner, String name) {
        return calls(method).stream().filter(call -> call.equals(owner + "." + name)).count();
    }
    private static boolean writes(MethodNode method, String field) {
        for (var instruction : method.instructions)
            if (instruction instanceof FieldInsnNode access && access.getOpcode() == Opcodes.PUTFIELD
                && access.name.equals(field)) return true;
        return false;
    }
}
