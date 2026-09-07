package dev.phoenium.mixin;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfileRepository;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullLegacyOwnerCache;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.players.*;
import org.spongepowered.asm.mixin.*;

/** Every actual cache reader/writer participates; the original two maps are retained. */
@Mixin(CachedUserNameToIdResolver.class)
abstract class FullLegacyOwnerCacheMixin implements FullLegacyOwnerCache.Access {
    @Shadow @Final private Map<String, Object> profilesByName;
    @Shadow @Final private Map<UUID, Object> profilesByUUID;
    @Shadow @Final private GameProfileRepository profileRepository;
    @Shadow @Final private Gson gson;
    @Shadow @Final private File file;
    @Shadow @Final private AtomicLong operationCount;
    @Shadow private boolean resolveOfflineUsers;
    @Unique private FullLegacyOwnerCache phoenium$cache;
    @Unique private final java.util.concurrent.locks.ReentrantLock phoenium$cacheInitialization = new java.util.concurrent.locks.ReentrantLock();
    @Override public FullLegacyOwnerCache phoenium$ownerCache() {
        phoenium$cacheInitialization.lock(); try {
        if (phoenium$cache == null) phoenium$cache = new FullLegacyOwnerCache((UserNameToIdResolver) this,
                profilesByName, profilesByUUID, profileRepository, gson, file, operationCount,
                () -> resolveOfflineUsers, value -> resolveOfflineUsers = value);
        return phoenium$cache;
        } finally { phoenium$cacheInitialization.unlock(); }
    }
    @WrapMethod(method = "get(Ljava/lang/String;)Ljava/util/Optional;")
    private Optional<NameAndId> phoenium$name(String name, Operation<Optional<NameAndId>> original) { return phoenium$ownerCache().get(name); }
    @WrapMethod(method = "get(Ljava/util/UUID;)Ljava/util/Optional;")
    private Optional<NameAndId> phoenium$id(UUID id, Operation<Optional<NameAndId>> original) { return phoenium$ownerCache().get(id); }
    @WrapMethod(method = "add(Lnet/minecraft/server/players/NameAndId;)V")
    private void phoenium$add(NameAndId identity, Operation<Void> original) { phoenium$ownerCache().add(identity); }
    @WrapMethod(method = "resolveOfflineUsers(Z)V")
    private void phoenium$policy(boolean value, Operation<Void> original) { phoenium$ownerCache().offline(value); }
    @WrapMethod(method = "save()V")
    private void phoenium$save(Operation<Void> original) { phoenium$ownerCache().save(); }
}
