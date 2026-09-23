package dev.phoenium.live;

/** Pinned Minecraft 26.2 tails; no caller locals cross these super calls. */
public interface MobPortalNativeAccess {
    interface LivingSuffix {
        void phoenium$resumeMobLivingBaseSuffix();
        void phoenium$resumeMobLivingTickSuffix();
    }
    interface MobSuffix {
        void phoenium$resumeMobBaseSuffix();
        void phoenium$resumeMobTickSuffix();
    }
    interface CubeSuffix {
        void phoenium$resumeMobCubeTickSuffix();
    }
    interface HorseSuffix {
        void phoenium$resumeMobHorseTickSuffix();
    }
    interface VillagerSuffix {
        void phoenium$resumeMobVillagerTickSuffix();
    }
}
