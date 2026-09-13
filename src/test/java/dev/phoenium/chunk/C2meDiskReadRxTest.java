package dev.phoenium.chunk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual locked Rx operators: throwing from onErrorResumeNext fabricates CompositeException. */
final class C2meDiskReadRxTest {
    @TempDir Path temporary;

    private URLClassLoader provider() throws Exception {
        var urls = new ArrayList<java.net.URL>();
        try (var outer = new JarFile("build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar")) {
            for (var entry : java.util.Collections.list(outer.entries())) {
                if (!entry.getName().startsWith("META-INF/jars/")) continue;
                if (!entry.getName().contains("base-") && !entry.getName().contains("rxjava-")
                        && !entry.getName().contains("reactive-streams-")) continue;
                Path target = temporary.resolve(Path.of(entry.getName()).getFileName());
                try (var input = outer.getInputStream(entry)) { Files.copy(input, target); }
                urls.add(target.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    @Test void cancelledActionMustReachSchedulerAsCancellationNotCompositeFailure() throws Exception {
        try (var loader = provider()) {
            var token = loader.loadClass("com.ishland.flowsched.scheduler.Cancellable").getConstructor().newInstance();
            var operation = new C2meDiskReadOperation(token);
            token.getClass().getMethod("cancel").invoke(token);
            var control = assertThrows(C2meDiskReadOperation.Control.class, operation::check);
            var single = loader.loadClass("io.reactivex.rxjava3.core.Single");
            var function = loader.loadClass("io.reactivex.rxjava3.functions.Function");
            Object forbiddenRecovery = Proxy.newProxyInstance(loader, new Class<?>[] {function}, (p, m, a) -> {
                throw new AssertionError("cancelled disk read reached provider fallback");
            });
            Object source = single.getMethod("error", Throwable.class).invoke(null, control);
            // Both async fallback and final recover-from-errors are real SingleResumeNext nodes.
            for (int i = 0; i < 2; i++) source = single.getMethod("onErrorResumeNext", function).invoke(source,
                    C2meDiskReadRx.guardRecovery(forbiddenRecovery, operation));
            Object finalSource = source;
            var failure = assertThrows(InvocationTargetException.class,
                    () -> single.getMethod("blockingGet").invoke(finalSource)).getCause();
            assertInstanceOf(C2meDiskReadOperation.Control.class, failure);
        }
    }

    @Test void failedPoiDrainMustPropagateTheExactFailureWithoutRecoveryOrWrapping() throws Exception {
        try (var loader = provider()) {
            var token = loader.loadClass("com.ishland.flowsched.scheduler.Cancellable").getConstructor().newInstance();
            var operation = new C2meDiskReadOperation(token);
            operation.beginCommit();
            var original = new IllegalStateException("exact POI failure");
            operation.nonReplayableFailure(original);
            var single = loader.loadClass("io.reactivex.rxjava3.core.Single");
            var function = loader.loadClass("io.reactivex.rxjava3.functions.Function");
            Object recovery = Proxy.newProxyInstance(loader, new Class<?>[] {function}, (p, m, a) -> {
                throw new AssertionError("non-replayable failure reached recovery");
            });
            Object source = single.getMethod("error", Throwable.class).invoke(null, original);
            Object guarded = single.getMethod("onErrorResumeNext", function).invoke(source,
                    C2meDiskReadRx.guardRecovery(recovery, operation));
            var failure = assertThrows(InvocationTargetException.class,
                    () -> single.getMethod("blockingGet").invoke(guarded)).getCause();
            assertSame(original, failure);
        }
    }
}
