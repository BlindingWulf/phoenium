package dev.phoenium.live;

import dev.phoenium.Phoenium;
import java.util.Set;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;

/** Only a selector's inactive acquisition site consults this context. It grants no ownership. */
public final class MobDecisionContext {
    private static final ThreadLocal<Mob> CURRENT = new ThreadLocal<>();
    private static final Set<String> HOSTILE = Set.of("net.minecraft.world.entity.monster.zombie.Zombie",
        "net.minecraft.world.entity.monster.skeleton.Skeleton", "net.minecraft.world.entity.monster.Creeper");
    private static final Set<String> PASSIVE = Set.of("net.minecraft.world.entity.animal.cow.Cow",
        "net.minecraft.world.entity.animal.pig.Pig", "net.minecraft.world.entity.animal.sheep.Sheep",
        "net.minecraft.world.entity.animal.chicken.Chicken");
    private MobDecisionContext() { }
    public static MobDecisionScheduler.Category category(Class<?> type) {
        if (HOSTILE.contains(type.getName())) return MobDecisionScheduler.Category.HOSTILE;
        if (PASSIVE.contains(type.getName())) return MobDecisionScheduler.Category.PASSIVE;
        return null;
    }
    public static boolean reviewedGoal(Class<?> type) {
        return type == NearestAttackableTargetGoal.class || type == RandomStrollGoal.class
            || type == WaterAvoidingRandomStrollGoal.class;
    }
    public static void selector(Mob mob, Runnable original) {
        Mob previous = CURRENT.get();
        CURRENT.set(mob);
        try { original.run(); }
        finally { if (previous == null) CURRENT.set(null); else CURRENT.set(previous); }
    }
    public static boolean allow(Goal goal) {
        Mob mob = CURRENT.get();
        return mob == null || !reviewedGoal(goal.getClass()) || Phoenium.liveRuntime().allowMobDecision(mob);
    }
}
