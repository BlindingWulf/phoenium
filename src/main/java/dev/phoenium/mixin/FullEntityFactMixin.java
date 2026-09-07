package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.chunk.*;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;

/** Entity-attached identity/fact sidecar follows cached aliases without global tombstones. */
@Mixin(Entity.class)
abstract class FullEntityFactMixin implements FullEntityFacts.Access {
    @Shadow private int id;
    @Unique private final long phoenium$incarnation = FullEntityFacts.nextIncarnation();
    @Unique private volatile FullEntityFacts.Fact phoenium$fact;
    @Override public FullEntityFacts.Fact phoenium$referenceFact() { return phoenium$fact; }
    @Override public void phoenium$referenceFact(FullEntityFacts.Fact fact) { phoenium$fact = fact; }
    @Override public long phoenium$entityIncarnation() { return phoenium$incarnation; }
    @Override public int phoenium$rawEntityId() { return id; }
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void phoenium$construction(EntityType<?> type, Level level, CallbackInfo ci) { FullEntityFacts.constructed((Entity) (Object) this); }
    @WrapMethod(method = "getUUID()Ljava/util/UUID;")
    private UUID phoenium$uuid(Operation<UUID> original) { return FullEntityFacts.uuid((Entity) (Object) this, original::call); }
    @WrapMethod(method = "getScoreboardName()Ljava/lang/String;")
    private String phoenium$scoreboardName(Operation<String> original) { return FullEntityFacts.scoreboardName((Entity) (Object) this, original::call); }
    @WrapMethod(method = "getId()I")
    private int phoenium$id(Operation<Integer> original) { return FullEntityFacts.id((Entity) (Object) this, original::call); }
    @WrapMethod(method = "isRemoved()Z")
    private boolean phoenium$removed(Operation<Boolean> original) { return FullEntityFacts.removed((Entity) (Object) this, original::call); }
    @WrapMethod(method = "typeHolder()Lnet/minecraft/core/Holder;")
    private Holder<EntityType<?>> phoenium$type(Operation<Holder<EntityType<?>>> original) { return FullEntityFacts.type((Entity) (Object) this, original::call); }
    @WrapMethod(method = "setId(I)V")
    private void phoenium$idWriter(int value, Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        try (var turn = FullEntityFacts.writer(entity)) { try { original.call(value); } finally { FullEntityFacts.publish(entity); } }
    }
    @WrapMethod(method = "setUUID(Ljava/util/UUID;)V")
    private void phoenium$uuidWriter(UUID value, Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        try (var turn = FullEntityFacts.writer(entity)) { try { original.call(value); } finally { FullEntityFacts.publish(entity); } }
    }
    @WrapMethod(method = "setInvulnerable(Z)V")
    private void phoenium$invulnerabilityWriter(boolean value, Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        try (var turn = FullEntityFacts.writer(entity)) { try { original.call(value); } finally { FullEntityFacts.publish(entity); } }
    }
    @WrapMethod(method = "setLevel(Lnet/minecraft/world/level/Level;)V")
    private void phoenium$worldWriter(Level value, Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        try (var turn = FullEntityFacts.writer(entity)) { try { original.call(value); } finally { FullEntityFacts.publish(entity); } }
    }
    @WrapMethod(method = "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V")
    private void phoenium$removalWriter(Entity.RemovalReason reason, Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        try (var turn = FullEntityFacts.writer(entity)) { try { original.call(reason); } finally { FullEntityFacts.publish(entity); } }
    }
    @WrapOperation(method = "setRemoved", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;removalReason:Lnet/minecraft/world/entity/Entity$RemovalReason;", opcode = Opcodes.PUTFIELD), require = 1)
    private void phoenium$removalPrefix(Entity entity, Entity.RemovalReason reason, Operation<Void> original) {
        original.call(entity, reason); FullEntityFacts.publish(entity);
    }
    @WrapMethod(method = "unsetRemoved()V")
    private void phoenium$unremoveWriter(Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        try (var turn = FullEntityFacts.writer(entity)) { try { original.call(); } finally { FullEntityFacts.publish(entity); } }
    }
    @WrapMethod(method = "load(Lnet/minecraft/world/level/storage/ValueInput;)V")
    private void phoenium$loadWriter(ValueInput input, Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        try (var turn = FullEntityFacts.writer(entity)) { try { original.call(input); } finally { FullEntityFacts.publish(entity); } }
    }
    @WrapOperation(method = "load", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;invulnerable:Z", opcode = Opcodes.PUTFIELD), require = 1)
    private void phoenium$invulnerabilityPrefix(Entity entity, boolean value, Operation<Void> original) {
        original.call(entity, value); FullEntityFacts.publish(entity);
    }
}
