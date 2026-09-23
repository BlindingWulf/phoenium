package dev.phoenium.mixin;

import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Selects the exact ServerChunkCache/Level adapter for Lithium's overwritten methods. */
public final class PhoeniumMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String BASE_SERVER = "dev.phoenium.mixin.ServerChunkCacheMixin";
    private static final String LITHIUM_SERVER = "dev.phoenium.mixin.LithiumServerChunkCacheMixin";
    private static final String BASE_LEVEL = "dev.phoenium.mixin.FullFinishingLevelQueriesMixin";
    private static final String LITHIUM_LEVEL = "dev.phoenium.mixin.LithiumFullFinishingLevelQueriesMixin";
    private static final String GEYSER_FLOODGATE_SKIN = "dev.phoenium.mixin.GeyserFloodgateSkinUploaderMixin";
    private boolean lithium;
    private boolean lithiumChunkAccess;
    private boolean geyserFloodgate;

    @Override public void onLoad(String mixinPackage) {
        lithium = FabricLoader.getInstance().isModLoaded("lithium");
        lithiumChunkAccess = lithium && lithiumChunkAccessEnabled(FabricLoader.getInstance().getConfigDir());
        geyserFloodgate = FabricLoader.getInstance().isModLoaded("geyser-fabric")
            && FabricLoader.getInstance().isModLoaded("floodgate");
    }

    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.equals("dev.phoenium.mixin.MobDecisionMixin")
                || mixinClassName.equals("dev.phoenium.mixin.MobDecisionGoalSelectorMixin"))
            return dev.phoenium.live.MobDecisionScheduler.Settings.fromProperties().enabled();
        if (mixinClassName.equals(BASE_SERVER)) return !lithiumChunkAccess;
        if (mixinClassName.equals(LITHIUM_SERVER)) return lithiumChunkAccess;
        if (mixinClassName.equals(BASE_LEVEL)) return !lithiumChunkAccess;
        if (mixinClassName.equals(LITHIUM_LEVEL)) return lithiumChunkAccess;
        if (mixinClassName.equals(GEYSER_FLOODGATE_SKIN)) return geyserFloodgate;
        return true;
    }

    private static boolean lithiumChunkAccessEnabled(Path configDir) {
        Path config = configDir.resolve("lithium.properties");
        if (!Files.isRegularFile(config)) return true;
        try {
            for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                String entry = line.trim();
                if (entry.isEmpty() || entry.startsWith("#") || entry.startsWith("!")) continue;
                int separator = entry.indexOf('=');
                if (separator < 0) separator = entry.indexOf(':');
                if (separator < 0) continue;
                String key = entry.substring(0, separator).trim();
                String value = entry.substring(separator + 1).trim();
                if (key.equals("mixin.world.chunk_access")) return !value.equalsIgnoreCase("false");
            }
        } catch (IOException ignored) {
            // Lithium's default is enabled; retain the default adapter selection if the file is unreadable.
        }
        return true;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
        if (mixinClassName.equals("dev.phoenium.mixin.MobPortalLivingMixin")
                || mixinClassName.equals("dev.phoenium.mixin.MobPortalMobMixin")
                || mixinClassName.equals("dev.phoenium.mixin.MobPortalCubeMixin")
                || mixinClassName.equals("dev.phoenium.mixin.MobPortalHorseMixin")
                || mixinClassName.equals("dev.phoenium.mixin.MobPortalVillagerMixin"))
            dev.phoenium.live.MobPortalNativeSuffixes.install(targetClass);
        if (mixinClassName.equals("dev.phoenium.mixin.FallingBlockPortalTickMixin"))
            dev.phoenium.live.FallingBlockPortalNativeSuffix.install(targetClass);
        if (mixinClassName.equals("dev.phoenium.mixin.TntPortalTickMixin"))
            dev.phoenium.live.TntPortalNativeSuffix.install(targetClass);
        if (mixinClassName.equals("dev.phoenium.mixin.SkeletonPortalCallerShapeMixin"))
            dev.phoenium.live.SkeletonPortalCallerShape.verify(targetClass);
        if (mixinClassName.equals("dev.phoenium.mixin.VaultTickContinuationMixin")
                || mixinClassName.equals("dev.phoenium.mixin.VaultOutputNeighborMixin")
                || mixinClassName.equals("dev.phoenium.mixin.VaultSetChangedAccess"))
            dev.phoenium.service.VaultContinuationPin.verify(targetClass);
    }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }
}
