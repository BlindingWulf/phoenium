package dev.phoenium.chunk;

/** Supplies publication observation only; it does not select Phoenium FULL execution. */
public interface FullPublicationSource {
    FullPublishedInputs phoenium$publishedInputs();
}
