package dev.phoenium.chunk;

import java.util.Objects;

/** Immutable proof that one logically identified POI installation completed. */
public record PoiInstallReceipt(PoiInstallService.InstallId install) {
    public PoiInstallReceipt { Objects.requireNonNull(install, "install"); }
}
