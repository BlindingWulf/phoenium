package dev.phoenium.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class RegionalTrackingAdapterContractTest {
    @Test
    void productionConfigurationInstallsEveryExactChunkMapTrackingAdapter() throws Exception {
        String configuration;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/phoenium.mixins.json"))) {
            configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String mixin : List.of("RegionalChunkMapAccess", "RegionalChunkMapTrackingMixin",
                "RegionalDistanceManagerAccess", "RegionalPlayerMapAccess", "RegionalTrackedEntityAccess"))
            assertTrue(configuration.contains("\"" + mixin + "\""), "missing production tracker adapter " + mixin);
    }
}
