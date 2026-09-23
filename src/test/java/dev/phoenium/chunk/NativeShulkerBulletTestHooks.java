package dev.phoenium.chunk;

import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Test-only native suffix signals; production source code is exercised for every transfer effect. */
public final class NativeShulkerBulletTestHooks {
    private NativeShulkerBulletTestHooks() { }
    static Consumer<ShulkerBullet> afterProjectile = ignored -> { };
    static DirectSuffix directSuffix = (ignored, target, hit, movement) -> { };
    public static void afterProjectile(ShulkerBullet bullet) { afterProjectile.accept(bullet); }
    public static void directSuffix(ShulkerBullet bullet, Entity target, HitResult hit, Vec3 movement) {
        directSuffix.accept(bullet, target, hit, movement);
    }
    @FunctionalInterface interface DirectSuffix {
        void accept(ShulkerBullet bullet, Entity target, HitResult hit, Vec3 movement);
    }
}
