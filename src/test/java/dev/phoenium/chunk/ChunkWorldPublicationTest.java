package dev.phoenium.chunk;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import dev.phoenium.live.PhoeniumLiveRuntime;
import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ChunkWorldPublicationTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void producersCannotInitializeAnUnpublishedWorldOrLoseOtherWorldRoutes() throws Exception {
        Thread coordinator=Thread.currentThread();
        var server=stub(MinecraftServer.class,List.of(new Answer(MinecraftServer.class.getMethod("isSameThread"),a->Thread.currentThread()==coordinator)));
        try(var f=new FullServiceOperationFixture(server);var executor=Executors.newSingleThreadExecutor()) {
            var runtime=new PhoeniumLiveRuntime(org.slf4j.LoggerFactory.getLogger("world-publication"));
            var overworld=f.world();var nether=f.world();set(nether,Level.class,"dimension",Level.NETHER);
            var first=runtime.requireChunkWorld(overworld);
            var failure=executor.submit(()->assertThrows(IllegalStateException.class,()->runtime.requireChunkWorld(nether))).get(5,TimeUnit.SECONDS);
            assertTrue(failure.getMessage().contains("before coordinator publication"));
            assertEquals(1,first.directory().snapshot().generation().value());
            var second=runtime.requireChunkWorld(nether);
            executor.submit(()->{
                for(int i=0;i<1000;i++) {
                    var a=runtime.requireChunkWorld(overworld);var b=runtime.requireChunkWorld(nether);
                    assertSame(first.services(),a.services());assertSame(second.services(),b.services());
                    assertTrue(a.directory().resolve(new RoutingKey(new WorldId("minecraft:overworld"),0,0)).isPresent());
                    assertTrue(b.directory().resolve(new RoutingKey(new WorldId("minecraft:the_nether"),0,0)).isPresent());
                }
            }).get(5,TimeUnit.SECONDS);
            assertEquals(2,first.directory().snapshot().generation().value());
        }
    }
    @Test void publishedWorldRejectsAnotherNativeInstanceOfSameDimension() throws Exception {
        var server=stub(MinecraftServer.class,List.of(new Answer(MinecraftServer.class.getMethod("isSameThread"),a->true)));
        try(var f=new FullServiceOperationFixture(server)) {
            var runtime=new PhoeniumLiveRuntime(org.slf4j.LoggerFactory.getLogger("world-instance"));
            var first=runtime.requireChunkWorld(f.world());
            assertThrows(IllegalStateException.class,()->runtime.requireChunkWorld(f.world()));
            assertEquals(1,first.directory().snapshot().generation().value());
        }
    }
}
