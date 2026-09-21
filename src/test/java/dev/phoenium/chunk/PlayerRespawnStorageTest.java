package dev.phoenium.chunk;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.invoke.MethodHandles;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual production storage hook/cleanup closure with native EntitySectionStorage.
 * Hidden subclass supplies only instantiation of the abstract mixin, not alternate behavior. */
final class PlayerRespawnStorageTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failureBeforeNativeCallbackReleasesOnlyItsClaimedUuidAndSection(boolean sectionInserted) throws Exception {
        try (var f = new PlayerRespawnHandoffTest.Fixture()) {
            var h = f.open(); f.oldRemoval(h); var next = f.prepare(h);
            Class<?> hook = Class.forName("dev.phoenium.mixin.PlayerRespawnStorageMixin");
            var writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/phoenium/mixin/RespawnStorageWitness", null,
                "dev/phoenium/mixin/PlayerRespawnStorageMixin", null); writer.visitEnd();
            Class<?> concrete = MethodHandles.privateLookupIn(hook, MethodHandles.lookup()).defineHiddenClass(writer.toByteArray(), true).lookupClass();
            Object instance = allocate(concrete);
            Set<UUID> known = new HashSet<>();
            var sections = new EntitySectionStorage<Entity>(Entity.class, ignored -> Visibility.TICKING);
            set(instance, hook, "knownUuids", known); set(instance, hook, "sectionStorage", sections);
            var manager = allocate(PersistentEntitySectionManager.class);
            var claim = hook.getDeclaredMethod("phoenium$respawnStorageClaim", PersistentEntitySectionManager.class, EntityAccess.class, Operation.class);
            claim.setAccessible(true);
            var injected = new IllegalStateException("failure before callback installation");
            assertSame(injected, assertThrows(IllegalStateException.class, () -> h.insertWorld(f.world, next, () -> {
                try {
                    assertEquals(true, claim.invoke(instance, manager, next, (Operation<Boolean>) args -> known.add(next.getUUID())));
                    if (sectionInserted) {
                        sections.getOrCreateSection(0L).add(next);
                        FullEntityFacts.stored(next, instance);
                    }
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                throw injected;
            })));
            assertTrue(known.contains(f.uuid)); h.rollback(); h.rollback();
            assertTrue(known.isEmpty()); assertNull(sections.getSection(0L)); f.assertEmpty();
            try (var turn = f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class, () -> f.service.entityFacts.trackingCandidate(f.world, next));
            }
            var reconnected = f.reconnect(); h.rollback(); f.assertCurrent(reconnected);
        }
    }
}
