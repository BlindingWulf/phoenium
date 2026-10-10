package dev.phoenium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S9-D13: a bound provider method answers exactly as Method.invoke for the shapes Phoenium binds. */
class BoundMethodTest {
    private static final java.lang.invoke.MethodHandles.Lookup LOOKUP = java.lang.invoke.MethodHandles.lookup();
    public static class Holders {
        public Object get(long key) { return key == 7L ? "seven" : null; }
        public boolean full() { return true; }
        public Object fail() { throw new IllegalStateException("provider failure"); }
        public Object checked() throws java.io.IOException { throw new java.io.IOException("provider io"); }
        public static Object status(Object vanilla) { return "status:" + vanilla; }
        public static Object singleton() { return "singleton"; }
    }

    @Test
    void returnsWhatReflectionReturnsIncludingBoxedPrimitivesAndInterfaceDispatch() throws Exception {
        Holders holders = new Holders();
        assertParity(Holders.class.getMethod("get", long.class), holders, 7L);
        assertParity(Holders.class.getMethod("get", long.class), holders, 8L);
        assertParity(Holders.class.getMethod("full"), holders);
        assertParity(Holders.class.getMethod("status", Object.class), null, "FULL");
        assertParity(Holders.class.getMethod("singleton"), null);
        List<String> list = new ArrayList<>(List.of("a", "b"));
        assertParity(List.class.getMethod("size"), list);
        assertParity(List.class.getMethod("get", int.class), list, 1);
        assertEquals(Boolean.TRUE, BoundMethod.of(LOOKUP, Holders.class.getMethod("full")).invoke(holders));
    }

    @Test
    void providerExceptionsArriveWrappedAsReflectionWrapsThem() throws Exception {
        Holders holders = new Holders();
        var runtime = assertThrows(InvocationTargetException.class, () -> BoundMethod.of(LOOKUP, Holders.class.getMethod("fail")).invoke(holders));
        assertInstanceOf(IllegalStateException.class, runtime.getCause());
        assertEquals("provider failure", runtime.getCause().getMessage());
        var checked = assertThrows(InvocationTargetException.class, () -> BoundMethod.of(LOOKUP, Holders.class.getMethod("checked")).invoke(holders));
        assertInstanceOf(java.io.IOException.class, checked.getCause());
        var outOfRange = assertThrows(InvocationTargetException.class,
            () -> BoundMethod.of(LOOKUP, List.class.getMethod("get", int.class)).invoke(List.of(), 3));
        assertInstanceOf(IndexOutOfBoundsException.class, outOfRange.getCause());
    }

    @Test
    void rejectedCallsFailUnwrappedAsReflectionRejectsThem() throws Exception {
        Method get = Holders.class.getMethod("get", long.class);
        BoundMethod bound = BoundMethod.of(LOOKUP, get);
        assertSameRejection(NullPointerException.class, () -> get.invoke(null, 7L), () -> bound.invoke(null, 7L));
        assertSameRejection(IllegalArgumentException.class, () -> get.invoke("not a holder", 7L), () -> bound.invoke("not a holder", 7L));
        assertSameRejection(IllegalArgumentException.class, () -> get.invoke(new Holders(), "seven"), () -> bound.invoke(new Holders(), "seven"));
        assertSameRejection(IllegalArgumentException.class, () -> get.invoke(new Holders(), (Object) null), () -> bound.invoke(new Holders(), null));
        assertSameRejection(IllegalArgumentException.class, () -> get.invoke(new Holders()), () -> bound.invoke(new Holders()));
        assertThrows(IllegalArgumentException.class, () -> BoundMethod.of(LOOKUP, String.class.getMethod("regionMatches", int.class, String.class, int.class, int.class)));
    }

    @Test
    void aMethodOfAClassTheCallerCannotNameStillAnswers() throws Exception {
        // A class defined by another loader: the direct call cannot name it, so the exact handle call answers.
        byte[] bytes;
        try (var in = BoundMethodTest.class.getResourceAsStream("BoundMethodTest$Holders.class")) { bytes = in.readAllBytes(); }
        ClassLoader isolated = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals(Holders.class.getName())) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> foreign = isolated.loadClass(Holders.class.getName());
        assertNotSame(Holders.class, foreign);
        Object holders = foreign.getConstructor().newInstance();
        assertEquals("seven", BoundMethod.of(LOOKUP, foreign.getMethod("get", long.class)).invoke(holders, 7L));
        assertEquals(Boolean.TRUE, BoundMethod.of(LOOKUP, foreign.getMethod("full")).invoke(holders));
        var failure = assertThrows(InvocationTargetException.class, () -> BoundMethod.of(LOOKUP, foreign.getMethod("fail")).invoke(holders));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertThrows(IllegalArgumentException.class, () -> BoundMethod.of(LOOKUP, foreign.getMethod("full")).invoke(new Holders()));
    }

    private static void assertParity(Method method, Object receiver, Object... arguments) throws Exception {
        Object expected = method.invoke(receiver, arguments);
        BoundMethod bound = BoundMethod.of(LOOKUP, method);
        Object actual = arguments.length == 0 ? bound.invoke(receiver) : bound.invoke(receiver, arguments[0]);
        assertEquals(expected, actual, method.toString());
    }

    @FunctionalInterface private interface Call { Object call() throws Exception; }
    private static void assertSameRejection(Class<? extends Throwable> type, Call reflective, Call bound) {
        assertThrows(type, reflective::call, "reflection");
        assertThrows(type, bound::call, "bound");
    }
}
