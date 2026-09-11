package dev.phoenium.chunk;

import java.util.HashMap;
import java.util.Map;

/** Test-only shape matching C2ME's public ChunkMap current-holder accessor. */
public interface C2mePublicationView {
    PublishedHolders getCurrentChunkHolders();

    final class PublishedHolders {
        private final Map<Long, Object> values = new HashMap<>();
        public Object get(long key) { return values.get(key); }
        public void put(long key, Object value) { values.put(key, value); }
        public void remove(long key) { values.remove(key); }
    }
}
