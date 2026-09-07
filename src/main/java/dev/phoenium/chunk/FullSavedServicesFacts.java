package dev.phoenium.chunk;

import java.util.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;

/** Detached values for original saved service edges; never invokes an entity reader. */
public record FullSavedServicesFacts(UUID entityId, String team, String legacyOwner, UUID owner, UUID anger,
                                     FullSavedTextInputs.Saved text) {
    public FullSavedServicesFacts(UUID entityId, String team, String legacyOwner, UUID owner, UUID anger) {
        this(entityId, team, legacyOwner, owner, anger, FullSavedTextInputs.Saved.EMPTY);
    }
    public static final FullSavedServicesFacts EMPTY = new FullSavedServicesFacts(null, null, null, null, null);
    private static final Set<String> LEGACY_OWNER_READERS = Set.of("minecraft:cat", "minecraft:wolf", "minecraft:parrot",
            "minecraft:nautilus", "minecraft:zombie_nautilus", "minecraft:horse", "minecraft:donkey", "minecraft:mule",
            "minecraft:llama", "minecraft:trader_llama", "minecraft:skeleton_horse", "minecraft:zombie_horse",
            "minecraft:camel", "minecraft:camel_husk");
    public static FullSavedServicesFacts capture(CompoundTag tag, String type) {
        return capture(tag, type, null);
    }
    static FullSavedServicesFacts capture(CompoundTag tag, String type, FullSavedInput custodian) {
        UUID owner = tag.read("Owner", UUIDUtil.CODEC).orElse(null);
        String legacy = owner == null && LEGACY_OWNER_READERS.contains(type) ? tag.getString("Owner").orElse(null) : null;
        return new FullSavedServicesFacts(tag.read("UUID", UUIDUtil.CODEC).orElse(null), tag.getString("Team").orElse(null),
                legacy, owner, tag.read("angry_at", UUIDUtil.CODEC).orElse(null),
                type.equals("minecraft:text_display") ? FullSavedTextInputs.capture(tag, custodian) : FullSavedTextInputs.Saved.EMPTY);
    }
    public int payloadBytes() { return 72 + 2 * ((team == null ? 0 : team.length()) + (legacyOwner == null ? 0 : legacyOwner.length())) + text.payloadBytes(); }
}
