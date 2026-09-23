package dev.phoenium.stage7repair;

import java.util.IdentityHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.phoenium.stage7repair.PortalNativeCallCorrelation.Classification.*;
import static org.junit.jupiter.api.Assertions.*;

class PortalNativeCallCorrelationTest {
    private final PortalNativeCallCorrelation calls = new PortalNativeCallCorrelation();
    private final UUID fireballUuid = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private final Object fireball = new Object();

    private IdentityHashMap<Object, UUID> graph(Object root, UUID uuid) {
        IdentityHashMap<Object, UUID> members = new IdentityHashMap<>();
        members.put(root, uuid);
        return members;
    }

    @Test void deliberateUnretainedFireballNativePortalPathIsCaught() {
        assertEquals(UNRETAINED_ROOT, calls.within(fireball, graph(fireball, fireballUuid),
            () -> calls.classify(fireball, fireballUuid, false)));
        assertEquals(OUTSIDE_PORTAL, calls.classify(fireball, fireballUuid, false));
    }

    @Test void directTeleportWithNoHandlePortalCallIsExcluded() {
        assertEquals(OUTSIDE_PORTAL, calls.classify(fireball, fireballUuid, false));
        assertEquals(0, calls.depth());
    }

    @Test void identityAndUuidMustBothMatch() {
        Object duplicateUuid = new Object();
        assertEquals(OUTSIDE_PORTAL, calls.within(fireball, graph(fireball, fireballUuid),
            () -> calls.classify(duplicateUuid, fireballUuid, false)));
        assertEquals(OUTSIDE_PORTAL, calls.within(fireball, graph(fireball, fireballUuid),
            () -> calls.classify(fireball, UUID.randomUUID(), false)));
    }

    @Test void retainedGraphPassengerIsRecognizedAfterNativeEjection() {
        Object passenger = new Object();
        UUID passengerUuid = UUID.randomUUID();
        IdentityHashMap<Object, UUID> members = graph(fireball, fireballUuid);
        members.put(passenger, passengerUuid);
        assertEquals(RETAINED_PASSENGER, calls.within(fireball, members,
            () -> calls.classify(passenger, passengerUuid, true)));
        assertEquals(UNRETAINED_PASSENGER, calls.within(fireball, members,
            () -> calls.classify(passenger, passengerUuid, false)));
    }

    @Test void throwingNativeCallClearsPortalScope() {
        RuntimeException boom = new RuntimeException("native failure");
        assertSame(boom, assertThrows(RuntimeException.class, () ->
            calls.within(fireball, graph(fireball, fireballUuid), () -> { throw boom; })));
        assertEquals(OUTSIDE_PORTAL, calls.classify(fireball, fireballUuid, false));
        assertEquals(0, calls.depth());
    }
}
