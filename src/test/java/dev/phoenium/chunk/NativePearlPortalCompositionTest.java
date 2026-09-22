package dev.phoenium.chunk;

import dev.phoenium.live.*;
import dev.phoenium.service.RegionalServiceKind;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native pearl replacement composes with the existing retained continuation. Portal search,
 * world insertion, ticket endpoint and collision suffix endpoints are controlled here. */
class NativePearlPortalCompositionTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() {
        NativePortalTestHooks.ticket=entity->entity.placePortalTicket(BlockPos.containing(entity.position()));
        NativePortalTestHooks.projectile=entity->{}; NativePortalTestHooks.pearl=entity->{}; NativePortalTestHooks.hit=(entity,result)->{};
    }
    @Test void oneNativePearlTransferOneTicketAndOneRetainedSourceSuffix() throws Exception {
        var f=new NativeItemPortalTransferTest.Fixture();
        var source=new ThrownEnderpearl(EntityTypes.ENDER_PEARL,f.overworld); source.setPos(0,65,0); f.publish(source,f.from);
        var binding=f.objects.current(source.getUUID().toString()).orElseThrow();
        var boundary=new CrossDimensionReplacementBoundary();
        var token=boundary.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),((FullEntityFacts.Access)source).phoenium$entityIncarnation(),binding.incarnation()));
        boundary.prepared(token);
        var transition=f.transition(f.nether);
        var transfer=new NativePortalTransfer(f.authority,f.objects,boundary,token,source,binding,transition,()->true);
        AtomicInteger tickets=new AtomicInteger(), projectile=new AtomicInteger(), retainedHit=new AtomicInteger(), hits=new AtomicInteger(), pearl=new AtomicInteger();
        NativePortalTestHooks.ticket=entity->{ f.owner(f.to); assertNotSame(source,entity); tickets.incrementAndGet(); };
        NativePortalTestHooks.projectile=entity->{ f.owner(f.from); assertSame(source,entity); projectile.incrementAndGet(); };
        var hit=new BlockHitResult(source.position(),net.minecraft.core.Direction.UP,BlockPos.ZERO,false);
        NativePortalTestHooks.hit=(entity,result)->{
            f.owner(f.from); assertSame(source,entity); assertSame(hit,result); retainedHit.incrementAndGet();
            if (result.getType()!=HitResult.Type.MISS && entity.isAlive()) hits.incrementAndGet();
        };
        NativePortalTestHooks.pearl=entity->{ f.owner(f.from); assertSame(source,entity); pearl.incrementAndGet(); };
        Entity replacement=transfer.run(); assertInstanceOf(ThrownEnderpearl.class,replacement);
        var continuationClass=Class.forName("dev.phoenium.live.RetainedPearlTickContinuation");
        var constructor=continuationClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Portal portal=(Portal)Blocks.NETHER_PORTAL; var processor=new PortalProcessor(portal,BlockPos.ZERO);
        Object continuation=constructor.newInstance(token,source,f.overworld,processor,portal,BlockPos.ZERO,hit,0,0,null);
        var resume=continuationClass.getDeclaredMethod("resume"); resume.setAccessible(true);
        var authorization=boundary.authorizeReplacementContinuation(token);
        f.dispatcher.executeAndDrain(f.from,RegionalServiceKind.ENTITY_PORTAL_SOURCE, ignored->{
            try { resume.invoke(continuation); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        boundary.settle(authorization);
        assertEquals(1,tickets.get()); assertEquals(1,projectile.get()); assertEquals(1,retainedHit.get()); assertEquals(1,pearl.get());
        assertEquals(0,hits.get(),"native hit guard skips the retired source; later replacement hit is a separate tick");
        var duplicate=assertThrows(InvocationTargetException.class,()->resume.invoke(continuation));
        assertInstanceOf(IllegalStateException.class,duplicate.getCause()); assertEquals(1,retainedHit.get());
        assertEquals(1,f.objects.liveCount()); assertEquals(1,Collections.frequency(f.order,"insert"));
        assertEquals(0,boundary.pendingCount());
    }
}
