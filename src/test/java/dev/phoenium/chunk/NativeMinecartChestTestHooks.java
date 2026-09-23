package dev.phoenium.chunk;

import java.util.function.Consumer;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;

/** Test-only source-owner witness for ChestMinecart's inherited abstract tick tail. */
public final class NativeMinecartChestTestHooks {
    public static Consumer<MinecartChest> afterAbstract = ignored -> { };

    public static void abstractSuffix(MinecartChest cart) { afterAbstract.accept(cart); }
}
