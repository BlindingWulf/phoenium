package dev.phoenium.mixin;
import dev.phoenium.live.LiveTickPhaseBoundary;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
public final class LiveTickPhasePlugin implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) { }
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String target, String mixin) { return true; }
    public void acceptTargets(Set<String> own, Set<String> other) { }
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.equals("dev.phoenium.mixin.LiveLevelTickPhaseMixin")) {
            LiveTickPhaseBoundary.requireInstalledLevelCallbacks(node);
            LiveTickPhaseBoundary.installLevel(node);
        }
        else if (mixin.equals("dev.phoenium.mixin.LiveChunkTickPhaseMixin")) LiveTickPhaseBoundary.installCache(node);
        else if (!mixin.equals("dev.phoenium.mixin.LiveTickReadinessMapMixin")) throw new IllegalStateException("unexpected tick adapter " + mixin);
    }
}
