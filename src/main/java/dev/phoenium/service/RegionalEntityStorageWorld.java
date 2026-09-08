package dev.phoenium.service;

import net.minecraft.server.level.ServerLevel;

/** Identity of the original entity-manager callback's enclosing world, never execution authority. */
public interface RegionalEntityStorageWorld {
    ServerLevel phoenium$entityStorageWorld();
}
