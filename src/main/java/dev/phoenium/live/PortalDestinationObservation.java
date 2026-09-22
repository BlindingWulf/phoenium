package dev.phoenium.live;

import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.OwnershipContext;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.portal.TeleportTransition;

/** Diagnostics-only native query observations. Never creates ownership, provider slots or work. */
public final class PortalDestinationObservation {
    private static final ThreadLocal<PortalDestinationObservation> CURRENT = new ThreadLocal<>();
    private final Entity entity;
    private final ObjectOwnership binding;
    private final CrossDimensionReplacementBoundary.Token token;
    private String query="requestedStatus=UNAVAILABLE chunk=UNAVAILABLE create=UNAVAILABLE providerFuture=UNAVAILABLE";
    private boolean observed, unfinishedObserved;
    private PortalDestinationObservation(Entity entity,ObjectOwnership binding,CrossDimensionReplacementBoundary.Token token) {
        this.entity=entity;this.binding=binding;this.token=token;
    }
    static TeleportTransition prepare(Entity entity,ObjectOwnership binding,CrossDimensionReplacementBoundary.Token token,
            Supplier<TeleportTransition> nativeLookup) {
        if(OwnershipContext.current().isPresent()) throw new IllegalStateException("portal destination preparation retained source owner");
        if(!Boolean.getBoolean("phoenium.test.diagnostics")) return nativeLookup.get();
        var previous=CURRENT.get(); var observation=new PortalDestinationObservation(entity,binding,token); CURRENT.set(observation);
        try { return nativeLookup.get(); }
        catch(RuntimeException | Error failure) { observation.report("FAILED_DESTINATION_LOOKUP:"+failure.getClass().getSimpleName());throw failure; }
        finally { if(previous==null) CURRENT.remove();else CURRENT.set(previous); }
    }
    public static void query(ServerLevel level,int x,int z,ChunkStatus status,boolean create) {
        var observation=CURRENT.get();if(observation==null)return;
        try {
            observation.query="destinationWorld="+level.dimension().identifier()+" requestedStatus="+status.getName()
                +" chunk="+x+","+z+" create="+create+" providerFuture=UNAVAILABLE";
            if(!observation.observed) {observation.observed=true;observation.report("FIRST_DESTINATION_QUERY");}
        } catch(Throwable ignored) { }
    }
    public static void selected(CompletableFuture<?> future) {
        var observation=CURRENT.get();if(observation==null)return;
        try {
            boolean done=future.isDone();
            observation.query += " selectedNativeFuture="+System.identityHashCode(future)+" selectedDone="+done;
            if(!done && !observation.unfinishedObserved) {
                observation.unfinishedObserved=true;
                observation.report("FIRST_UNFINISHED_DESTINATION_DEPENDENCY");
            }
        }
        catch(Throwable ignored) { }
    }
    private void report(String phase) {
        try { org.slf4j.LoggerFactory.getLogger(PortalDestinationObservation.class).info(
            "PHOENIUM_PORTAL_DESTINATION generation={} type={} uuid={} native={} registry={} sourceWorld={} owner={} phase={} {}",
            token.generation(),entity.getType(),token.source().objectId(),token.source().nativeIncarnation(),token.source().registryIncarnation(),
            binding.routingKey().world(),OwnershipContext.current().map(Object::toString).orElse("NONE"),phase,query);
        } catch(Throwable ignored) { }
    }
}
