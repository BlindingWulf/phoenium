package dev.phoenium.chunk;

import java.lang.instrument.*;
import java.security.ProtectionDomain;

/** Test-scoped hook installation on original classes; no Minecraft imitation or production agent. */
public final class FullRegistrationTestAgent {
    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                               ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("net/minecraft/server/level/GenerationChunkHolder")
                        && !name.equals("net/minecraft/world/level/chunk/status/ChunkStatusTasks")
                        && !name.equals("net/minecraft/world/level/chunk/ProtoChunk")
                        && !name.equals("net/minecraft/world/level/chunk/LevelChunk")
                        && !name.equals("net/minecraft/world/level/chunk/storage/SerializableChunkData")
                        && !name.equals("net/minecraft/server/level/ChunkMap")
                        && !name.equals("net/minecraft/server/level/ServerChunkCache")
                        && !name.equals("net/minecraft/server/level/ServerLevel")
                        && !name.equals("net/minecraft/world/level/Level")
                        && !name.equals("net/minecraft/world/level/LevelReader")
                        && !name.equals("net/minecraft/world/level/BlockCollisions")
                        && !name.equals("net/minecraft/nbt/CompoundTag")
                        && !name.equals("net/minecraft/nbt/ListTag")
                        && !name.equals("net/minecraft/world/level/storage/PrimaryLevelData")
                        && !FullFinishingServicesTestTransform.supports(name)) return null;
                try {
                    return (byte[]) Class.forName("dev.phoenium.chunk.FullRegistrationTestTransform", true, loader)
                            .getMethod("transform", String.class, byte[].class).invoke(null, name, bytes);
                } catch (Throwable failure) { throw new AssertionError("test hook installation failed for " + name, failure); }
            }
        });
    }
}
