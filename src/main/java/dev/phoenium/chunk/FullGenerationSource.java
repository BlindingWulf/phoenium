package dev.phoenium.chunk;

/** Explicit generation-map binding. Ordinary ChunkMap deliberately does not implement this yet. */
public interface FullGenerationSource {
    FullRegistrationRuntime phoenium$fullRegistration();
}
