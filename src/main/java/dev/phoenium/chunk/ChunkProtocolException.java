package dev.phoenium.chunk;

/** An adapter/ownership invariant failure, distinct from an original Minecraft throwable. */
public final class ChunkProtocolException extends IllegalStateException {
    public ChunkProtocolException(String message) { super(message); }
    public ChunkProtocolException(String message, Throwable cause) { super(message, cause); }
}
