package dev.phoenium.chunk;

/** Physical argument's short-lived insertion witness contains no owner authority. */
public interface LightInvocationArgument {
    LightMailboxCommand.Witness phoenium$lightWitness();
    void phoenium$lightWitness(LightMailboxCommand.Witness witness);
}
