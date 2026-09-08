package dev.phoenium.chunk;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.ownership.OwnershipContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FullStructureDemandTest {
    @BeforeAll static void bootstrap() { FullSavedVillagerInputTest.bootstrap(); }
    @Test void independentConsumersHaveDistinctNonpersistentTicketsAndIndependentAcknowledgement() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new ChunkPos(20, 0);
        var updates = new AtomicInteger();
        var first = new FullStructureDemand(f.runtime); var second = new FullStructureDemand(f.runtime);
        try {
            updates(f, updates);
            first.require(position); second.require(position);
            var tickets = f.world.tickets.getTickets(position.pack());
            assertEquals(2, tickets.size()); assertNotSame(tickets.getFirst().getType(), tickets.get(1).getType());
            assertTrue(tickets.stream().allMatch(ticket -> !ticket.getType().persist() && !ticket.getType().doesSimulate()));
            var packed = assertInstanceOf(CompoundTag.class, TicketStorage.CODEC.encodeStart(NbtOps.INSTANCE, f.world.tickets).getOrThrow());
            assertFalse(packed.contains("tickets"), "private prerequisite tickets are absent from the original saved representation");
            first.close(); assertEquals(1, f.world.tickets.getTickets(position.pack()).size());
            second.require(position); assertEquals(2, updates.get(), "same consumer retains its original ticket without repeated distance work");
            second.close(); assertTrue(f.world.tickets.getTickets(position.pack()).isEmpty());
        } finally { first.close(); second.close(); f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        FullInitializedInputBindingTest.assertCleanup(f);
    }
    @Test void reentrantCloseDuringOriginalInsertionSkipsUnreachedDistanceWork() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new ChunkPos(20, 0);
        var updates = new AtomicInteger(); var demand = new FullStructureDemand(f.runtime);
        try {
            updates(f, updates);
            TicketStorage.ChunkUpdated listener = (key, level, adding) -> {
                assertTrue(OwnershipContext.current().isEmpty());
                if (adding) demand.close();
            };
            set(f.world.tickets, TicketStorage.class, "loadingChunkUpdatedListener", listener);
            demand.require(position);
            assertEquals(0, updates.get()); assertEquals(0, demand.size());
            assertTrue(f.world.tickets.getTickets(position.pack()).isEmpty());
        } finally { demand.close(); f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        FullInitializedInputBindingTest.assertCleanup(f);
    }
    @Test void throwingOriginalInsertionListenerPreservesFailureAndRemovesCommittedTicket() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new ChunkPos(20, 0);
        var demand = new FullStructureDemand(f.runtime);
        try {
            var original = new IllegalStateException("insertion listener");
            var cleanup = new IllegalArgumentException("removal listener");
            TicketStorage.ChunkUpdated listener = (key, level, adding) -> {
                assertTrue(OwnershipContext.current().isEmpty());
                if (adding) throw original; else throw cleanup;
            };
            set(f.world.tickets, TicketStorage.class, "loadingChunkUpdatedListener", listener);
            assertSame(original, assertThrows(IllegalStateException.class, () -> demand.require(position)));
            assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
            assertEquals(0, demand.size()); assertTrue(f.world.tickets.getTickets(position.pack()).isEmpty());
        } finally { demand.close(); f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        FullInitializedInputBindingTest.assertCleanup(f);
    }
    private static void updates(FullRegistrationFixture f, AtomicInteger count) throws Exception {
        var manager = stub(DistanceManager.class, List.of(new Answer(DistanceManager.class.getMethod("runAllUpdates", ChunkMap.class), args -> {
            assertTrue(OwnershipContext.current().isEmpty()); assertFalse(FullFinishingServices.finishing());
            count.incrementAndGet(); return false;
        })));
        set(f.world.cache, ServerChunkCache.class, "distanceManager", manager);
    }
}
