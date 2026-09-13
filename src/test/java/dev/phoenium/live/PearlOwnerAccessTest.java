package dev.phoenium.live;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Version-pinned guard for the 26.2 pearl owner and persisted-reference seam. */
class PearlOwnerAccessTest {
    @Test void lockedMinecraftOwnerAndPersistenceMethodsRemainTheExpected26_2Shape() throws Exception {
        Method owner = ThrownEnderpearl.class.getDeclaredMethod("getOwner");
        assertEquals(Entity.class, owner.getReturnType());
        assertTrue(Modifier.isPublic(owner.getModifiers()));

        Method setOwner = ThrownEnderpearl.class.getDeclaredMethod("setOwner", EntityReference.class);
        assertTrue(Modifier.isProtected(setOwner.getModifiers()));

        Method persistedRead = Projectile.class.getDeclaredMethod("readAdditionalSaveData", ValueInput.class);
        Method persistedWrite = Projectile.class.getDeclaredMethod("addAdditionalSaveData", ValueOutput.class);
        assertTrue(Modifier.isProtected(persistedRead.getModifiers()));
        assertTrue(Modifier.isProtected(persistedWrite.getModifiers()));

        Method savePearls = ServerPlayer.class.getDeclaredMethod("saveEnderPearls", ValueOutput.class);
        Method loadPearls = ServerPlayer.class.getDeclaredMethod("loadAndSpawnEnderPearls", ValueInput.class);
        Method loadPearl = ServerPlayer.class.getDeclaredMethod("loadAndSpawnEnderPearl", ValueInput.class);
        assertTrue(Modifier.isPrivate(savePearls.getModifiers()));
        assertTrue(Modifier.isPublic(loadPearls.getModifiers()));
        assertTrue(Modifier.isPrivate(loadPearl.getModifiers()));
        assertEquals("ender_pearls", ServerPlayer.ENDER_PEARLS_TAG);
        assertEquals("ender_pearl_dimension", ServerPlayer.ENDER_PEARL_DIMENSION_TAG);

        Method removal = ThrownEnderpearl.class.getDeclaredMethod("onRemoval", Entity.RemovalReason.class);
        assertTrue(Modifier.isPublic(removal.getModifiers()));
        assertNotNull(Entity.RemovalReason.valueOf("UNLOADED_WITH_PLAYER"));
    }
}
