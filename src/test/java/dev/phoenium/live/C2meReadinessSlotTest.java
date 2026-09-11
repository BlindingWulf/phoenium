package dev.phoenium.live;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class C2meReadinessSlotTest {
    @Test void reflectiveAdapterReturnsTheExactBackingStatusSlotIdentity() throws Exception {
        var holder = new FakeHolder();
        Method backing = FakeHolder.class.getMethod("getBackingHolder");
        Method future = FakeBacking.class.getMethod("getFutureForStatus0", FakeStatus.class);
        var access = C2meReadinessSlot.reflective(backing, future, FakeStatus.BLOCK_TICKING);
        assertSame(holder.backing.slot, access.identity(holder));
        holder.backing.slot = new CompletableFuture<>();
        assertSame(holder.backing.slot, access.identity(holder));
    }

    @Test void reflectiveAdapterFailsClosedWhenTheProviderDoesNotReturnAFuture() throws Exception {
        var holder = new BadHolder();
        Method backing = BadHolder.class.getMethod("getBackingHolder");
        Method future = BadBacking.class.getMethod("getFutureForStatus0", FakeStatus.class);
        var access = C2meReadinessSlot.reflective(backing, future, FakeStatus.BLOCK_TICKING);
        var failure = assertThrows(IllegalStateException.class, () -> access.identity(holder));
        assertTrue(failure.getMessage().contains("not a CompletableFuture"));
    }

    @Test void reflectiveAdapterFailsClosedOnInaccessibleContractMember() throws Exception {
        Method backing = PrivateHolder.class.getDeclaredMethod("getBackingHolder");
        Method future = FakeBacking.class.getMethod("getFutureForStatus0", FakeStatus.class);
        var access = C2meReadinessSlot.reflective(backing, future, FakeStatus.BLOCK_TICKING);
        var failure = assertThrows(IllegalStateException.class, () -> access.identity(new PrivateHolder()));
        assertTrue(failure.getMessage().contains("cannot access"));
    }

    @Test void reflectiveAdapterPreservesRuntimeAndErrorCausesFromProvider() throws Exception {
        Method runtimeBacking = ThrowingRuntimeHolder.class.getMethod("getBackingHolder");
        Method errorBacking = ThrowingErrorHolder.class.getMethod("getBackingHolder");
        Method future = FakeBacking.class.getMethod("getFutureForStatus0", FakeStatus.class);
        var runtimeAccess = C2meReadinessSlot.reflective(runtimeBacking, future, FakeStatus.BLOCK_TICKING);
        var errorAccess = C2meReadinessSlot.reflective(errorBacking, future, FakeStatus.BLOCK_TICKING);
        var runtime = assertThrows(IllegalArgumentException.class, () -> runtimeAccess.identity(new ThrowingRuntimeHolder()));
        var error = assertThrows(AssertionError.class, () -> errorAccess.identity(new ThrowingErrorHolder()));
        assertEquals("runtime-provider", runtime.getMessage()); assertEquals("error-provider", error.getMessage());
    }

    public enum FakeStatus { BLOCK_TICKING }
    public static final class FakeHolder {
        final FakeBacking backing = new FakeBacking();
        public FakeBacking getBackingHolder() { return backing; }
    }
    public static final class FakeBacking {
        CompletableFuture<Void> slot = new CompletableFuture<>();
        public CompletableFuture<Void> getFutureForStatus0(FakeStatus status) { return slot; }
    }
    public static final class BadHolder {
        final BadBacking backing = new BadBacking();
        public BadBacking getBackingHolder() { return backing; }
    }
    public static final class BadBacking {
        public Object getFutureForStatus0(FakeStatus status) { return new Object(); }
    }
    public static final class PrivateHolder {
        @SuppressWarnings("unused") private FakeBacking getBackingHolder() { return new FakeBacking(); }
    }
    public static final class ThrowingRuntimeHolder {
        public FakeBacking getBackingHolder() { throw new IllegalArgumentException("runtime-provider"); }
    }
    public static final class ThrowingErrorHolder {
        public FakeBacking getBackingHolder() { throw new AssertionError("error-provider"); }
    }
}
