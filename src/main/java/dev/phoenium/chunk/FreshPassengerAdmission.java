package dev.phoenium.chunk;

import dev.phoenium.ownership.ObjectOwnership;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/** Exact, synchronous fresh-spawn input custody. This never inserts or starts tracking an entity. */
public final class FreshPassengerAdmission implements AutoCloseable {
    public enum Phase { IDENTITY_PUBLISHED_NOT_TRACKED, TRACKING_STARTED, TRACKING_PUBLISHED, RETIRED }
    public interface Publisher {
        void requireOwners(Set<ChunkPos> positions);
        ObjectOwnership publish(Entity entity);
        ObjectOwnership current(String uuid);
        void retire(ObjectOwnership identity);
    }
    private final FullEntityFacts facts;
    private final ServerLevel world;
    private final Entity root;
    private final Publisher publisher;
    private final List<Member> ordered = new ArrayList<>();
    private final IdentityHashMap<Entity, Member> members = new IdentityHashMap<>();
    private FullEntityFacts.LoadedBatch inputs;
    private boolean closed;
    private String cancellation;

    private FreshPassengerAdmission(FullEntityFacts facts, ServerLevel world, Entity root, Publisher publisher) {
        this.facts = facts; this.world = world; this.root = root; this.publisher = publisher;
    }
    public static FreshPassengerAdmission begin(ServerLevel world, Entity root, Publisher publisher) {
        var facts = FullFinishingServices.forServer(world.getServer()).entityFacts;
        var admission = new FreshPassengerAdmission(facts, world, root, publisher);
        try {
            facts.requireFreshWorld(world);
            admission.capture(root, null, 0);
            Set<ChunkPos> positions = new LinkedHashSet<>();
            for (Member member : admission.ordered) positions.add(member.position);
            publisher.requireOwners(Set.copyOf(positions));
            // Reject collisions before publishing anything. UUID is only a collision key, never identity.
            Set<UUID> uuids = new HashSet<>(); Set<Integer> ids = new HashSet<>();
            for (Member member : admission.ordered) {
                if (!uuids.add(member.uuid) || !ids.add(member.id) || facts.hasRoute(member.entity)
                        || publisher.current(member.uuid.toString()) != null)
                    throw admission.failure(member, "fresh graph collides with an existing identity");
            }
            admission.inputs = FullEntityFacts.loadedBatch(world,
                admission.ordered.stream().map(member -> member.entity).toList());
            for (Member member : admission.ordered) {
                member.binding = publisher.publish(member.entity);
                facts.admitPassengerIdentity(member.entity, admission);
            }
            admission.validate();
            return admission;
        } catch (Throwable failure) {
            admission.cancellation = "admission failed";
            try { admission.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof ChunkProtocolException || failure instanceof Error) throw failure;
            var diagnosed = admission.failure(admission.members.get(root), "identity admission failed: " + failure);
            diagnosed.initCause(failure); throw diagnosed;
        }
    }
    private void capture(Entity entity, Entity parent, int depth) {
        if (members.containsKey(entity)) throw failure(members.get(entity), "cyclic or duplicate graph node");
        if (!(entity instanceof FullEntityFacts.Access access) || entity.level() != world || entity.isRemoved()
                || entity.getVehicle() != parent)
            throw facts.passengerFailure(root, entity, depth, "fresh graph world/removal/parent mismatch", this);
        var member = new Member(entity, parent, depth, access.phoenium$entityIncarnation(), entity.getUUID(),
            access.phoenium$rawEntityId(), entity.chunkPosition(), List.copyOf(entity.getPassengers()));
        members.put(entity, member); ordered.add(member);
        for (Entity child : member.children) capture(child, entity, depth + 1);
    }
    /** Called while the admitted owner still holds the complete graph closure. */
    public void validate() {
        if (closed || cancellation != null) throw failure(members.get(root), "retired admission: " + cancellation);
        Set<ChunkPos> positions = new LinkedHashSet<>();
        for (Member member : ordered) positions.add(member.position);
        try { publisher.requireOwners(Set.copyOf(positions)); }
        catch (RuntimeException changed) { throw failure(members.get(root), "graph owner/route invalidated: " + changed.getMessage()); }
        for (Member member : ordered) {
            Entity entity = member.entity;
            if (entity.level() != world || entity.isRemoved() || entity.getVehicle() != member.parent
                    || !sameAliases(member.children, entity.getPassengers()))
                throw failure(member, "graph detached, replaced, removed, or changed world");
            var access = (FullEntityFacts.Access) entity;
            var fact = access.phoenium$referenceFact();
            if (access.phoenium$entityIncarnation() != member.incarnation || fact == null || fact.alias() != entity
                    || fact.world() != world || fact.incarnation() != member.incarnation || fact.removed()
                    || !fact.uuid().equals(member.uuid) || fact.runtimeId() != member.id
                    || !entity.chunkPosition().equals(member.position))
                throw failure(member, "native incarnation, object identity, or position changed");
            FullEntityFacts.Candidate candidate;
            try { candidate = facts.trackingCandidate(world, entity); }
            catch (ChunkProtocolException missing) { throw failure(member, "published route unavailable: " + missing.getMessage()); }
            if (!candidate.position().equals(member.position)) throw failure(member, "published route differs from admitted position");
            ObjectOwnership current;
            try { current = publisher.current(member.uuid.toString()); }
            catch (RuntimeException changed) { throw failure(member, "object route unavailable: " + changed.getMessage()); }
            if (member.binding == null || !member.binding.equals(current))
                throw failure(member, "ownership incarnation, epoch, or topology changed");
        }
    }
    public Phase phase(Entity entity) { return Objects.requireNonNull(members.get(entity)).phase; }
    ServerLevel world() { return world; }
    void trackingStarted(Entity entity) {
        validate(); Member member = members.get(entity);
        if (member.phase != Phase.IDENTITY_PUBLISHED_NOT_TRACKED)
            throw failure(member, "duplicate tracking-start during graph admission");
        member.phase = Phase.TRACKING_STARTED;
    }
    void trackingCompleted(Entity entity) {
        validate(); Member member = members.get(entity);
        if (member.phase != Phase.TRACKING_STARTED) throw failure(member, "tracking completion without start");
        member.phase = Phase.TRACKING_PUBLISHED;
    }
    void removed(Entity entity) {
        cancellation = "graph member removed";
        Member member = members.get(entity);
        if (member != null && member.binding != null && !facts.hasStorage(entity)) publisher.retire(member.binding);
    }
    private ChunkProtocolException failure(Member member, String reason) {
        return facts.passengerFailure(root, member == null ? root : member.entity,
            member == null ? 0 : member.depth, reason, this);
    }
    String describe(Entity entity) {
        Member member = members.get(entity);
        Object current;
        try { current = member == null ? null : publisher.current(member.uuid.toString()); }
        catch (RuntimeException unavailable) { current = "UNAVAILABLE: " + unavailable.getMessage(); }
        return "world=" + world.dimension().identifier() + (member == null ? ",UNRELATED" : ",phase=" + member.phase
            + ",nativeIncarnation=" + member.incarnation + ",uuid=" + member.uuid + ",runtimeId=" + member.id
            + ",admittedBinding=" + member.binding + ",currentBinding=" + current);
    }
    private static boolean sameAliases(List<Entity> expected, List<Entity> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) if (expected.get(i) != actual.get(i)) return false;
        return true;
    }
    /** Native storage owns the inserted prefix; unused input identities are retired exactly. */
    @Override public void close() { close(false); }
    void shutdown() { close(true); }
    private void close(boolean shutdown) {
        if (closed) return;
        closed = true;
        try {
            for (Member member : ordered) {
                facts.releasePassengerIdentity(member.entity, this);
                if (member.binding != null && (shutdown || !facts.hasStorage(member.entity))) {
                    publisher.retire(member.binding); member.phase = Phase.RETIRED;
                }
            }
        } finally { if (inputs != null) inputs.close(); }
    }
    private static final class Member {
        final Entity entity, parent; final int depth, id; final long incarnation; final UUID uuid;
        final ChunkPos position; final List<Entity> children;
        ObjectOwnership binding; Phase phase = Phase.IDENTITY_PUBLISHED_NOT_TRACKED;
        Member(Entity entity, Entity parent, int depth, long incarnation, UUID uuid, int id,
                ChunkPos position, List<Entity> children) {
            this.entity = entity; this.parent = parent; this.depth = depth; this.incarnation = incarnation;
            this.uuid = uuid; this.id = id; this.position = position; this.children = children;
        }
    }
}
