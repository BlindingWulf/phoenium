package dev.phoenium.live;

/** Four pinned Minecraft 26.2 tails; no caller locals cross these super calls. */
public interface MobPortalNativeAccess {
    interface LivingSuffix {
        void phoenium$resumeMobLivingBaseSuffix();
        void phoenium$resumeMobLivingTickSuffix();
    }
    interface MobSuffix {
        void phoenium$resumeMobBaseSuffix();
        void phoenium$resumeMobTickSuffix();
    }
}
