package dev.phoenium.live;

import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.OwnershipContext;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.portal.TeleportTransition;

/** Diagnostics-only native query observations. Never creates ownership, provider slots or work. */
public final class PortalDestinationObservation {
    private static final ThreadLocal<PortalDestinationObservation> CURRENT = new ThreadLocal<>();
    private final Entity entity;
    private final ObjectOwnership binding;
    private final CrossDimensionReplacementBoundary.Token token;
    private final NativePortalTransfer.Authority authority;
    private final BooleanSupplier sourceCurrent;
    private String query="requestedStatus=UNAVAILABLE chunk=UNAVAILABLE create=UNAVAILABLE providerFuture=UNAVAILABLE";
    private boolean observed, unfinishedObserved, resultObserved;
    private int queryCount;
    private PortalDestinationObservation(Entity entity,ObjectOwnership binding,CrossDimensionReplacementBoundary.Token token,
            NativePortalTransfer.Authority authority,BooleanSupplier sourceCurrent) {
        this.entity=entity;this.binding=binding;this.token=token;
        this.authority=authority;this.sourceCurrent=sourceCurrent;
    }
    static TeleportTransition prepare(Entity entity,ObjectOwnership binding,CrossDimensionReplacementBoundary.Token token,
            NativePortalTransfer.Authority authority,BooleanSupplier sourceCurrent,Supplier<TeleportTransition> nativeLookup) {
        if(OwnershipContext.current().isPresent()) throw new IllegalStateException("portal destination preparation retained source owner");
        var previous=CURRENT.get();
        var observation=new PortalDestinationObservation(entity,binding,token,authority,sourceCurrent); CURRENT.set(observation);
        try { return nativeLookup.get(); }
        catch(RuntimeException | Error failure) { observation.report("FAILED_DESTINATION_LOOKUP:"+failure.getClass().getSimpleName());throw failure; }
        finally { observation.report("DESTINATION_LOOKUP_EXIT queries="+observation.queryCount);if(previous==null) CURRENT.remove();else CURRENT.set(previous); }
    }
    /** EndPortalBlock calls the original platform method once, at its native point.
     * Dependency reads are prepared owner-free; the entire native block operation has
     * destination service authority. An unretained caller keeps the vanilla path. */
    public static void endPlatform(Entity entity,ServerLevel level,BlockPos origin,Runnable nativePlatform) {
        var observation=CURRENT.get();
        if(observation==null || observation.entity!=entity) {nativePlatform.run();return;}
        if(OwnershipContext.current().isPresent())
            throw new IllegalStateException("End platform preparation retained a source owner");
        if(!observation.sourceCurrent.getAsBoolean())
            throw new IllegalStateException("End platform source changed before preparation");
        observation.authority.withDestinationPlatform(level,origin,observation.sourceCurrent,nativePlatform);
    }
    public static void query(ServerLevel level,int x,int z,ChunkStatus status,boolean create) {
        var observation=CURRENT.get();if(observation==null)return;
        try {
            observation.queryCount++;
            observation.query="destinationWorld="+level.dimension().identifier()+" requestedStatus="+status.getName()
                +" chunk="+x+","+z+" create="+create+" query="+observation.queryCount+" providerFuture=UNAVAILABLE";
            if(!observation.observed) {observation.observed=true;observation.report("FIRST_DESTINATION_QUERY");}
        } catch(Throwable ignored) { }
    }
    public static void selected(ServerLevel level,int x,int z,ChunkStatus status,CompletableFuture<?> future) {
        var observation=CURRENT.get();if(observation==null)return;
        try {
            boolean done=future.isDone();
            observation.query += " selectedNativeFuture="+System.identityHashCode(future)+" selectedDone="+done;
            observation.query += holder(level,x,z,status,future);
            if(!done && !observation.unfinishedObserved) {
                observation.unfinishedObserved=true;
                observation.report("FIRST_UNFINISHED_DESTINATION_DEPENDENCY");
            }
        }
        catch(Throwable ignored) { }
    }
    public static void result(ServerLevel level,int x,int z,ChunkStatus status,net.minecraft.world.level.chunk.ChunkAccess result) {
        var observation=CURRENT.get();if(observation==null||observation.resultObserved)return;
        try {
            observation.resultObserved=true;
            observation.query+=" nativeResult="+(result==null ? "NULL" : result.getClass().getSimpleName()+"@"+System.identityHashCode(result))
                +holder(level,x,z,status,null);
            observation.report("FIRST_DESTINATION_QUERY_RETURN");
        } catch(Throwable unavailable) { }
    }
    private static String holder(ServerLevel level,int x,int z,ChunkStatus status,CompletableFuture<?> selected) {
        return dev.phoenium.chunk.BlockEntityChunkQueryDiagnostic.portalHolderObservation(level,x,z,status,selected);
    }
    private void report(String phase) {
        if(!Boolean.getBoolean("phoenium.test.diagnostics"))return;
        try { org.slf4j.LoggerFactory.getLogger(PortalDestinationObservation.class).info(
            "PHOENIUM_PORTAL_DESTINATION generation={} type={} uuid={} native={} registry={} sourceWorld={} owner={} phase={} {}",
            token.generation(),entity.getType(),token.source().objectId(),token.source().nativeIncarnation(),token.source().registryIncarnation(),
            binding.routingKey().world(),OwnershipContext.current().map(Object::toString).orElse("NONE"),phase,query);
        } catch(Throwable ignored) { }
    }
}
