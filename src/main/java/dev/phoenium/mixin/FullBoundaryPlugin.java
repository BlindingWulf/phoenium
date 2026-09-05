package dev.phoenium.mixin;

import dev.phoenium.chunk.FullStageBoundary;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Late, single-method adapter over vanilla plus the installed Fabric lifecycle injections. */
public final class FullBoundaryPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
        if (!mixinClassName.equals("dev.phoenium.mixin.FullStageBoundaryMixin"))
            throw new IllegalStateException("unexpected FULL adapter mixin: " + mixinClassName);
        FullStageBoundary.requireInstalledCallbacks(targetClass);
        FullStageBoundary.install(targetClass);
    }
}
