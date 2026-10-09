package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.mixin.PearlPortalProcessorAccess;
import dev.phoenium.ownership.*;
import dev.phoenium.service.RegionalServiceKind;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Exact standalone ArmorStand and Mannequin inherited LivingEntity.tick continuation. */
public final class ArmorStandPortalTransitionManager implements AutoCloseable {
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<Key, Entry> active = new ConcurrentHashMap<>();
    private final ThreadLocal<LivingEntity> ticking = new dev.phoenium.core.FastThreadLocal<>();
    private volatile boolean closed;

    public ArmorStandPortalTransitionManager(NativePortalTransfer.Authority authority, ObjectOwnershipRegistry objects) {
        this.authority=Objects.requireNonNull(authority);this.objects=Objects.requireNonNull(objects);
    }
    private static boolean supported(LivingEntity stand) {
        return stand.getClass()==ArmorStand.class || stand.getClass()==Mannequin.class;
    }
    public void begin(LivingEntity stand) {
        if (closed || !authority.running() || !supported(stand)) return;
        if (ticking.get()!=null) throw new IllegalStateException("nested armor-stand portal tick");
        ticking.set(stand);
    }
    public boolean suspendDestination(LivingEntity stand, PortalProcessor processor) {
        if (closed || !authority.running() || ticking.get()!=stand || stand.isRemoved()
                || !supported(stand) || !(stand.level() instanceof ServerLevel level)
                || processor==null || stand.portalProcess!=processor) return false;
        Portal portal=((PearlPortalProcessorAccess)processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock) && !(portal instanceof EndPortalBlock)) return false;
        if (stand.isPassenger() || stand.isVehicle())
            throw new IllegalStateException("armor-stand passenger graph has no native continuation");
        ObjectOwnership binding=objects.current(stand.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(),binding.objectEpoch(),OwnershipContext.current().orElseThrow().region());
        var token=replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access)stand).phoenium$entityIncarnation(),binding.incarnation()));
        Entry entry=new Entry(stand,level,processor,binding,token);
        if (active.putIfAbsent(new Key(stand),entry)!=null) {
            replacements.cancel(token);throw new IllegalStateException("armor-stand portal captured twice");
        }
        NativePortalTransfer.retained(stand,binding,token,null,"CAPTURED_ARMOR_STAND_TICK");
        return true;
    }
    public boolean suspended(LivingEntity stand) { return active.containsKey(new Key(stand)); }
    public boolean complete(LivingEntity stand) {
        Entry entry=active.get(new Key(stand));
        if (entry==null || ticking.get()!=stand) return false;
        if (entry.retained) throw new IllegalStateException("armor-stand tail captured twice");
        entry.retained=true;return true;
    }
    public void finish(LivingEntity stand) {
        Entry entry=active.get(new Key(stand));
        if (entry!=null && !entry.retained) cancel(entry,"UNRETAINED_PREFIX");
        if (ticking.get()==stand) ticking.set(null);
    }
    public int advance() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("armor-stand coordinator retained source owner");
        if (closed || !authority.running()) return 0;
        var entries=new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(entry->entry.token.generation()));
        int settled=0;
        for (Entry entry:entries) {
            if (!entry.retained || entry.advancing || active.get(new Key(entry.stand))!=entry) continue;
            if (!current(entry)) { cancel(entry,"STALE_BEFORE_NATIVE");continue; }
            entry.advancing=true;
            try {
                if (!entry.destinationPrepared) {
                    NativePortalTransfer.retained(entry.stand,entry.binding,entry.token,null,"DESTINATION_PREPARING");
                    entry.transition=PortalDestinationObservation.prepare(entry.stand,entry.binding,entry.token,
                        authority,()->current(entry),()->entry.processor.getPortalDestination(entry.level,entry.stand));
                    if (!current(entry)) { cancel(entry,"STALE_AFTER_PREPARATION");continue; }
                    replacements.prepared(entry.token);entry.destinationPrepared=true;
                }
                boolean permitted=entry.transition!=null && entry.level.isAllowedToEnterPortal(entry.transition.newLevel())
                    && (entry.level.dimension()==entry.transition.newLevel().dimension()
                        || entry.stand.canTeleport(entry.level,entry.transition.newLevel()));
                if (permitted && entry.level.dimension()==entry.transition.newLevel().dimension())
                    throw new IllegalStateException("same-dimension armor-stand portal needs a separate native adapter");
                if (permitted) {
                    if (entry.visibilityReady==null)
                        entry.visibilityReady=authority.demandDestinationEntityVisibility(entry.stand,entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick())) throw new IllegalStateException("armor-stand visibility deadline: "+entry.visibilityReady);
                        continue;
                    }
                }
                var transfer=permitted ? new NativePortalTransfer(authority,objects,replacements,entry.token,
                    entry.stand,entry.binding,entry.transition,()->active.get(new Key(entry.stand))==entry
                        && entry.visibilityReady.getAsBoolean()) : null;
                Entity result=transfer==null ? null : transfer.run();
                var continuation=result==null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token,entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(),RegionalServiceKind.ENTITY_PORTAL_SOURCE,()->{
                    if (active.get(new Key(entry.stand))!=entry || entry.consumed || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access)entry.stand).phoenium$entityIncarnation()!=entry.token.source().nativeIncarnation()
                            || result==null && !current(entry)
                            || result!=null && entry.stand.getRemovalReason()!=Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale armor-stand native tick tail");
                    entry.consumed=true;
                    ((PearlPortalNativeAccess.EntitySuffix)entry.stand).phoenium$resumePearlPortalBaseTickSuffix();
                    ((MobPortalNativeAccess.LivingSuffix)entry.stand).phoenium$resumeMobLivingBaseSuffix();
                    ((MobPortalNativeAccess.LivingSuffix)entry.stand).phoenium$resumeMobLivingTickSuffix();
                });
                replacements.settle(continuation);
                if (transfer!=null) transfer.settled();
                else NativePortalTransfer.retained(entry.stand,entry.binding,entry.token,null,"SETTLED_NATIVE_NO_TRANSFER");
                active.remove(new Key(entry.stand),entry);settled++;
            } catch (RuntimeException | Error failure) {
                cancel(entry,"FAILED:"+failure.getClass().getSimpleName());throw failure;
            } finally {entry.advancing=false;}
        }
        return settled;
    }
    private boolean current(Entry entry) {
        ObjectOwnership current=objects.current(entry.token.source().objectId()).orElse(null);
        return replacements.isCurrent(entry.token) && authority.worldActive(entry.level)
            && (entry.transition==null || authority.worldActive(entry.transition.newLevel()))
            && !entry.stand.isRemoved() && entry.stand.level()==entry.level && entry.stand.portalProcess==entry.processor
            && entry.stand.position().equals(entry.position) && entry.stand.getDeltaMovement().equals(entry.motion)
            && entry.stand.getYRot()==entry.yRot && entry.stand.getXRot()==entry.xRot && entry.stand.getPose()==entry.pose
            && ((FullEntityFacts.Access)entry.stand).phoenium$entityIncarnation()==entry.token.source().nativeIncarnation()
            && entry.stand.getUUID().toString().equals(entry.token.source().objectId())
            && entry.processor.getEntryPosition().equals(entry.entryPosition)
            && ((PearlPortalProcessorAccess)entry.processor).phoenium$portal()==entry.portal
            && entry.processor.getPortalTime()==entry.portalTime
            && entry.processor.isInsidePortalThisTick()==entry.insidePortal
            && current!=null && current.incarnation()==entry.binding.incarnation()
            && current.objectEpoch().equals(entry.binding.objectEpoch())
            && current.routingKey().equals(entry.binding.routingKey());
    }
    public boolean onRemoval(Entity entity,Entity.RemovalReason reason) {
        if (!(entity instanceof LivingEntity stand) || !supported(stand)) return false;
        Entry entry=active.get(new Key(stand));if (entry==null) return false;
        if (entry.consumed && stand.getRemovalReason()==Entity.RemovalReason.CHANGED_DIMENSION) return true;
        cancel(entry,"EXTERNAL_REMOVAL:"+reason);return false;
    }
    public void worldClosing(ServerLevel level) {
        for(Entry entry:List.copyOf(active.values()))
            if (entry.level==level || entry.transition!=null && entry.transition.newLevel()==level) cancel(entry,"WORLD_CLOSED");
    }
    private void cancel(Entry entry,String reason) {
        NativePortalTransfer.retained(entry.stand,entry.binding,entry.token,
            entry.transition==null ? null : entry.transition.newLevel(),reason);
        replacements.cancel(entry.token);active.remove(new Key(entry.stand),entry);
    }
    public int pendingCount() {return active.size();}
    @Override public void close() {
        closed=true;for(Entry entry:List.copyOf(active.values()))cancel(entry,"SHUTDOWN");
        replacements.close();ticking.set(null);
    }
    private record Key(LivingEntity stand) {
        @Override public int hashCode(){return System.identityHashCode(stand);}
        @Override public boolean equals(Object other){return other instanceof Key key && key.stand==stand;}
    }
    private static final class Entry {
        final LivingEntity stand;final ServerLevel level;final PortalProcessor processor;
        final ObjectOwnership binding;final CrossDimensionReplacementBoundary.Token token;
        final Portal portal;final BlockPos entryPosition;final int portalTime;final boolean insidePortal;
        final Vec3 position,motion;final float yRot,xRot;final net.minecraft.world.entity.Pose pose;
        volatile boolean retained,consumed,advancing,destinationPrepared;
        final PortalVisibilityDeadline visibilityDeadline = new PortalVisibilityDeadline();BooleanSupplier visibilityReady;TeleportTransition transition;
        Entry(LivingEntity stand,ServerLevel level,PortalProcessor processor,ObjectOwnership binding,
                CrossDimensionReplacementBoundary.Token token) {
            this.stand=stand;this.level=level;this.processor=processor;this.binding=binding;this.token=token;
            this.portal=((PearlPortalProcessorAccess)processor).phoenium$portal();
            this.entryPosition=processor.getEntryPosition().immutable();this.portalTime=processor.getPortalTime();
            this.insidePortal=processor.isInsidePortalThisTick();this.position=stand.position();this.motion=stand.getDeltaMovement();
            this.yRot=stand.getYRot();this.xRot=stand.getXRot();this.pose=stand.getPose();
        }
    }
}
