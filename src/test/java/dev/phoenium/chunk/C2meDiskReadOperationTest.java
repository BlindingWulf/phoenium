package dev.phoenium.chunk;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the actual locked provider token, never a token-shaped mock. */
final class C2meDiskReadOperationTest {
    private static final Path BASE = Path.of("build/stage8-entry/META-INF/jars/c2me-fabric-base-mc26.2-0.4.2-alpha.0.43-all.jar");
    private record Fixture(URLClassLoader loader, Object token, C2meDiskReadOperation operation) implements AutoCloseable {
        boolean cancel() throws Exception { return (boolean) token.getClass().getMethod("cancel").invoke(token); }
        public void close() throws Exception { loader.close(); }
    }
    private Fixture fixture() throws Exception {
        var loader = new URLClassLoader(new java.net.URL[] {BASE.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Object token = loader.loadClass("com.ishland.flowsched.scheduler.Cancellable").getConstructor().newInstance();
        return new Fixture(loader, token, new C2meDiskReadOperation(token));
    }

    @Test void cancelBeforeAdmissionCannotCreateOrCommitWork() throws Exception {
        try (var f = fixture()) {
            assertTrue(f.cancel());
            assertThrows(C2meDiskReadOperation.Control.class, f.operation::check);
            assertThrows(C2meDiskReadOperation.Control.class, f.operation::beginCommit);
            assertEquals(C2meDiskReadOperation.Terminal.CANCELLED,
                    f.operation.settle(new C2meDiskReadOperation.Control(C2meDiskReadOperation.Terminal.CANCELLED)));
        }
    }

    @Test void latePrivateProducerCannotRegainAuthorityAfterCancellation() throws Exception {
        try (var f = fixture()) {
            f.operation.check();
            assertTrue(f.cancel());
            assertFalse(f.cancel());
            var control = assertThrows(C2meDiskReadOperation.Control.class, f.operation::check);
            assertTrue(f.operation.mustBypassRecovery(control));
            assertThrows(C2meDiskReadOperation.Control.class, f.operation::beginCommit);
            assertEquals(C2meDiskReadOperation.Terminal.CANCELLED, f.operation.settle(control));
            assertThrows(C2meDiskReadOperation.Control.class, f.operation::check);
            assertThrows(IllegalStateException.class, () -> f.operation.settle(control));
        }
    }

    @Test void queuedPoiLosesToCancellationBeforeOwnerExecution() throws Exception {
        try (var f = fixture()) {
            f.operation.check();
            AtomicInteger effects = new AtomicInteger();
            Runnable queued = () -> { f.operation.beginCommit(); effects.incrementAndGet(); };
            assertTrue(f.cancel());
            assertThrows(C2meDiskReadOperation.Control.class, queued::run);
            assertEquals(0, effects.get());
        }
    }

    @Test void commitWinsBeforePoiAndLateTokenCancellationReturnsFalse() throws Exception {
        try (var f = fixture()) {
            f.operation.beginCommit();
            assertFalse(f.cancel());
            f.operation.check();
            assertEquals(C2meDiskReadOperation.Terminal.APPLIED, f.operation.settle(null));
            assertThrows(IllegalStateException.class, f.operation::check);
        }
    }

    @Test void actualTokenCasArbitratesRacingCommitAndCancel() throws Exception {
        for (int i = 0; i < 100; i++) try (var f = fixture()) {
            var start = new CountDownLatch(1);
            var cancelled = new AtomicInteger();
            var committed = new AtomicInteger();
            var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread cancel = Thread.ofPlatform().start(() -> {
                try { start.await(); if (f.cancel()) cancelled.incrementAndGet(); }
                catch (Throwable failure) { unexpected.set(failure); }
            });
            Thread commit = Thread.ofPlatform().start(() -> {
                try { start.await(); f.operation.beginCommit(); committed.incrementAndGet(); }
                catch (C2meDiskReadOperation.Control expected) { }
                catch (Throwable failure) { unexpected.set(failure); }
            });
            start.countDown(); cancel.join(); commit.join();
            assertNull(unexpected.get());
            assertEquals(1, cancelled.get() + committed.get());
        }
    }

    @Test void failedOwnerDrainCannotRetryOrTurnIntoCancellation() throws Exception {
        try (var f = fixture()) {
            f.operation.beginCommit();
            var failure = new IllegalStateException("exact provider drain failure");
            f.operation.nonReplayableFailure(failure);
            assertFalse(f.cancel());
            f.operation.retire(C2meDiskReadOperation.Terminal.SHUTDOWN);
            assertSame(failure, assertThrows(IllegalStateException.class, f.operation::check));
            assertTrue(f.operation.mustBypassRecovery(failure));
            assertEquals(C2meDiskReadOperation.Terminal.FAILED, f.operation.settle(failure));
        }
    }

    @Test void supersededAndStoppedProducerCannotPublishAndNewActionIsIndependent() throws Exception {
        for (var reason : new C2meDiskReadOperation.Terminal[] {
                C2meDiskReadOperation.Terminal.SUPERSEDED, C2meDiskReadOperation.Terminal.SHUTDOWN}) {
            try (var old = fixture(); var fresh = fixture()) {
                old.operation.retire(reason);
                var control = assertThrows(C2meDiskReadOperation.Control.class, old.operation::check);
                assertThrows(C2meDiskReadOperation.Control.class, old.operation::beginCommit);
                assertEquals(reason, old.operation.settle(control));
                fresh.operation.beginCommit();
                assertEquals(C2meDiskReadOperation.Terminal.APPLIED, fresh.operation.settle(null));
            }
        }
    }

    @Test void cancellationThenStopKeepsTheFirstDisposition() throws Exception {
        try (var f = fixture()) {
            assertTrue(f.cancel());
            f.operation.retire(C2meDiskReadOperation.Terminal.SHUTDOWN);
            assertEquals(C2meDiskReadOperation.Terminal.CANCELLED, f.operation.terminal());
        }
    }

    @Test void committedEffectFinishesWhenStopOrReplacementArrives() throws Exception {
        for (var reason : new C2meDiskReadOperation.Terminal[] {
                C2meDiskReadOperation.Terminal.SHUTDOWN, C2meDiskReadOperation.Terminal.SUPERSEDED}) {
            try (var f = fixture()) {
                f.operation.beginCommit(); f.operation.retire(reason); f.operation.check();
                assertFalse(f.cancel());
                assertEquals(C2meDiskReadOperation.Terminal.APPLIED, f.operation.settle(null));
            }
        }
    }

    @Test void externalRetirementCancelsNativeTokenOutsideOperationMonitor() throws Exception {
        try (var f = fixture()) {
            var locks = new java.util.ArrayList<Boolean>();
            f.token.getClass().getMethod("setup", Runnable.class).invoke(f.token,
                    (Runnable) () -> locks.add(Thread.holdsLock(f.operation)));
            f.operation.retire(C2meDiskReadOperation.Terminal.SHUTDOWN);
            assertEquals(java.util.List.of(false), locks);
            assertTrue((boolean) f.token.getClass().getMethod("isCancelled").invoke(f.token));
            var control = assertThrows(C2meDiskReadOperation.Control.class, f.operation::check);
            assertEquals(C2meDiskReadOperation.Terminal.SHUTDOWN, f.operation.settle(control));
        }
    }
}
