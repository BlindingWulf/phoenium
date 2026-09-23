package dev.phoenium.chunk;

import dev.phoenium.live.ArmorStandPortalTransitionManager;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native inherited LivingEntity.tick contact, delayed destination lookup, transfer and suffix. */
class ArmorStandPortalNativeTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() {
        if(PortalDestinationTestHooks.armorStands!=null)PortalDestinationTestHooks.armorStands.close();
        PortalDestinationTestHooks.armorStands=null;
    }
    @ParameterizedTest @CsvSource({"armor_stand,true","armor_stand,false","mannequin,true","mannequin,false"})
    void decorativeLivingReleasesOwnerBeforeNativePortalLookup(String type,boolean ready) throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture();
        var manager=new ArmorStandPortalTransitionManager(f.authority,f.objects);
        PortalDestinationTestHooks.armorStands=manager;
        f.levelData=(net.minecraft.world.level.storage.LevelData)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{net.minecraft.world.level.storage.ServerLevelData.class},(proxy,method,args)->
                method.getName().equals("getDifficulty") ? net.minecraft.world.Difficulty.NORMAL : null);
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var registries=NativePortalDestinationPreparationTest.dynamicRegistries();
        set(f.overworld,Level.class,"registryAccess",registries);
        set(f.nether,Level.class,"registryAccess",registries);
        var env=net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build();
        set(f.overworld,net.minecraft.server.level.ServerLevel.class,"environmentAttributes",env);
        set(f.nether,net.minecraft.server.level.ServerLevel.class,"environmentAttributes",env);
        var stand=(LivingEntity)net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
            net.minecraft.resources.Identifier.parse("minecraft:"+type)).create(f.overworld,
                net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        assertNotNull(stand);
        assertEquals(net.minecraft.world.entity.LivingEntity.class,stand.getClass().getMethod("tick").getDeclaringClass());
        stand.setPos(.44,65.31,.02);var route=f.key(f.overworld);f.publish(stand,route);
        NativePortalDestinationPreparationTest.setPortal(stand,args->{
            ((Portal)Blocks.NETHER_PORTAL).getPortalDestination(f.overworld,stand,BlockPos.ZERO);
            return f.transition(f.nether);
        });
        var reads=new AtomicInteger();
        f.chunkRead=args->{
            reads.incrementAndGet();assertSame(ChunkStatus.EMPTY,args[2]);assertEquals(true,args[3]);
            var dependency=new CompletableFuture<Void>();
            if(ready)dependency.complete(null);
            ChunkSynchronousAccessGuard.beforeWait(dependency::isDone);dependency.complete(null);
            assertTrue(OwnershipContext.current().isEmpty());
            assertTrue(f.runtimes.values().stream().noneMatch(r->r.leases().hasActiveLease()));
            return null;
        };
        assertDoesNotThrow(()->f.dispatcher.executeAndDrain(route,RegionalServiceKind.CHUNK_GAMEPLAY,ignored->{
            manager.begin(stand);
            try {stand.tick();assertTrue(manager.complete(stand));}
            finally {manager.finish(stand);}
        }));
        assertEquals(0,reads.get());assertEquals(1,manager.pendingCount());
        assertEquals(1,manager.advance());assertTrue(reads.get()>1);
        assertTrue(stand.isRemoved());assertNotSame(stand,f.replacement);
        assertEquals(stand.getUUID(),f.replacement.getUUID());
        assertEquals(1,java.util.Collections.frequency(f.order,"insert"));
        assertEquals(1,java.util.Collections.frequency(f.order,"base-suffix"));
        assertTrue(f.order.indexOf("post")<f.order.indexOf("base-suffix"));
        assertEquals(0,manager.pendingCount());
    }
}
