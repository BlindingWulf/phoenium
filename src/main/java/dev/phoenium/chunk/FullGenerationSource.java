package dev.phoenium.chunk;

/** Generation selection and the independent, purpose-scoped structure reader binding. */
public interface FullGenerationSource {
    FullRegistrationRuntime phoenium$fullRegistration();
    default FullRegistrationRuntime phoenium$naturalSpawnStructures() { return phoenium$fullRegistration(); }
}
