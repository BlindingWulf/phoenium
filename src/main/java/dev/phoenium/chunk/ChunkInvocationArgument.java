package dev.phoenium.chunk;

/** Original argument carries an opaque synchronous invocation locator, never a lease/holder. */
public interface ChunkInvocationArgument {
    SynchronousChunkInvocation.Key phoenium$invocation();
    void phoenium$invocation(SynchronousChunkInvocation.Key key);
}
