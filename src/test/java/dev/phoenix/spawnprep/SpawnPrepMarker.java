package dev.phoenix.spawnprep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code <world>/phoenix-spawnprep.json}: what was prepared, so later launches neither redo it nor forget an interrupted run.
 * Also the status file a management app can poll while preparation runs.
 */
public record SpawnPrepMarker(int version, State state, String dimension, int centerX, int centerZ, int radiusBlocks, int done,
                              int total, int generated, int existing, int failed, long updatedEpochMs) {
    public enum State { RUNNING, PARTIAL, COMPLETE, CANCELLED }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Null for anything unreadable: the caller then treats the world as unprepared. */
    public static SpawnPrepMarker fromJson(String json) {
        try {
            SpawnPrepMarker m = GSON.fromJson(json, SpawnPrepMarker.class);
            return m == null || m.state() == null || m.dimension() == null ? null : m;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    public static SpawnPrepMarker read(Path file) {
        try {
            return Files.exists(file) ? fromJson(Files.readString(file, StandardCharsets.UTF_8)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Temp file then atomic rename, so a crash never leaves a half-written marker. */
    public void write(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
