package dev.phoenium.chunk;

import dev.phoenium.ownership.ObjectOwnership;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/** Exact, synchronous fresh-spawn input custody. This never inserts or starts tracking an entity. */
public final class FreshPassengerAdmission implements AutoCloseable {
    public enum Phase { IDENTITY_PUBLISHED_NOT_TRACKED, TRACKING_STARTED, TRACKING_PUBLISHED, RETIRED }
    public enum EntryPath { BULK_INSERTION, DIRECT_FINALIZATION_INSERTION, DETACHED_FINALIZATION_INSERTION }
    public interface Publisher {
        void requireOwners(Set<ChunkPos> positions);
        ObjectOwnership publish(Entity entity);
        ObjectOwnership current(String uuid);
        void retire(ObjectOwnership identity);
    }
    private static final AtomicLong ADMISSION_SEQUENCE = new AtomicLong();
    private static final AtomicLong OUTER_SEQUENCE = new AtomicLong();
    /** Open detached custodies; zero keeps every native insertion and relation edge on its untouched path. */
    private static final AtomicInteger DETACHED_OPEN = new AtomicInteger();
    private static final ThreadLocal<ArrayDeque<NaturalSpawnScope>> NATURAL_SCOPES =
        ThreadLocal.withInitial(ArrayDeque::new);
    private final FullEntityFacts facts;
    private final ServerLevel world;
    private final Entity root;
    private final Entity expectedOuterCandidate;
    private final Publisher publisher;
    private final long admissionIdentity;
    private final long outerGeneration;
    private final int nestedDepth;
    private final EntryPath entryPath;
    private final List<Member> ordered = new ArrayList<>();
    private final IdentityHashMap<Entity, Member> members = new IdentityHashMap<>();
    private final List<FullEntityFacts.LoadedBatch> inputs = new ArrayList<>();
    private boolean closed;
    private String cancellation;
    private String exitReason = "ACTIVE";
    private Entity activeRoot;
    private boolean detachedCounted, extending;

    private FreshPassengerAdmission(FullEntityFacts facts, ServerLevel world, Entity root, Entity expectedOuterCandidate,
            Publisher publisher, EntryPath entryPath, long outerGeneration, int nestedDepth) {
        this.facts = facts; this.world = world; this.root = root; this.expectedOuterCandidate = expectedOuterCandidate;
        this.publisher = publisher; this.entryPath = entryPath; this.outerGeneration = outerGeneration;
        this.nestedDepth = nestedDepth; this.admissionIdentity = ADMISSION_SEQUENCE.incrementAndGet(); this.activeRoot = root;
    }
    public static FreshPassengerAdmission begin(ServerLevel world, Entity root, Publisher publisher) {
        return begin(world, root, root, publisher, EntryPath.BULK_INSERTION, 0, 0);
    }
    private static FreshPassengerAdmission begin(ServerLevel world, Entity root, Entity expectedOuterCandidate,
            Publisher publisher, EntryPath entryPath, long outerGeneration, int nestedDepth) {
        var facts = FullFinishingServices.forServer(world.getServer()).entityFacts;
        var admission = new FreshPassengerAdmission(facts, world, root, expectedOuterCandidate, publisher,
            entryPath, outerGeneration, nestedDepth);
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
            admission.inputs.add(FullEntityFacts.loadedBatch(world,
                admission.ordered.stream().map(member -> member.entity).toList()));
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
    public static NaturalSpawnScope naturalSpawnScope(ServerLevel world, Publisher publisher) {
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(publisher, "publisher");
        ArrayDeque<NaturalSpawnScope> stack = NATURAL_SCOPES.get();
        NaturalSpawnScope parent = stack.peek();
        ScopeState state = parent != null && parent.state.world == world && !parent.closed
            ? parent.state : new ScopeState(world, publisher, OUTER_SEQUENCE.incrementAndGet());
        var scope = new NaturalSpawnScope(state, parent, parent != null && parent.state == state ? parent.depth + 1 : 0);
        stack.push(scope); state.facts.naturalScopeEntered();
        return scope;
    }
    public static NaturalSpawnScope currentNaturalSpawnScope(ServerLevel world) {
        NaturalSpawnScope scope = NATURAL_SCOPES.get().peek();
        return scope != null && !scope.closed && scope.state.world == world ? scope : null;
    }

    /**
     * Zombie/Drowned/Husk.finalizeSpawn inserts a fresh vehicle that already carries {@code finalizing},
     * which its caller inserts later (spawners, eggs, summon, siege, reinforcements, conversions, or a
     * nested natural rider such as the zombie-horse jockey). Outside the exact natural candidate the
     * custody is detached: it lives while an inserted graph member carries an uninserted one.
     */
    public static boolean finalizationInsertion(ServerLevel world, Entity finalizing, Entity insertionRoot,
            Supplier<Publisher> publisher, BooleanSupplier original) {
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(finalizing, "finalizing");
        Objects.requireNonNull(insertionRoot, "insertionRoot"); Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(original, "original");
        NaturalSpawnScope scope = currentNaturalSpawnScope(world);
        if (scope != null) {
            Entity candidate = scope.state.finalizing.peek();
            if (candidate == finalizing || candidate != null && insertionRoot.getPassengers().size() > 0
                    && containsIdentity(insertionRoot, candidate))
                return scope.directInsertion(insertionRoot, original);
        }
        if (insertionRoot == finalizing || insertionRoot.getPassengers().isEmpty()
                || !containsIdentity(insertionRoot, finalizing)) return original.getAsBoolean();
        var facts = FullFinishingServices.forServer(world.getServer()).entityFacts;
        FreshPassengerAdmission admission = facts.pendingAdmission(finalizing);
        if (admission == null) {
            admission = begin(world, insertionRoot, finalizing, publisher.get(),
                EntryPath.DETACHED_FINALIZATION_INSERTION, 0, 0);
            admission.detachedCounted = true; DETACHED_OPEN.incrementAndGet();
            facts.directAdmissionPrepared();
        } else {
            if (!admission.detachedFor(finalizing))
                throw admission.failure(admission.members.get(finalizing),
                    "detached direct insertion found its candidate in another pending custody");
            if (!admission.contains(insertionRoot)) admission.extendDetached(insertionRoot, true);
            else admission.validate();
        }
        boolean accepted;
        try { accepted = original.getAsBoolean(); }
        catch (RuntimeException | Error failure) {
            try { admission.settleDetached("detached direct insertion failed"); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        facts.directInsertionReturned(accepted);
        admission.detachedCustodyChanged();
        return accepted;
    }
    /** Husk's later parched bulk joins the same detached graph before the native loop tracks it. */
    public static void finalizationBulkInsertion(ServerLevel world, Entity finalizing, Entity insertionRoot,
            Runnable original) {
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(finalizing, "finalizing");
        Objects.requireNonNull(insertionRoot, "insertionRoot"); Objects.requireNonNull(original, "original");
        NaturalSpawnScope scope = currentNaturalSpawnScope(world);
        if (scope != null && scope.state.finalizing.peek() == finalizing) { scope.bulkInsertion(insertionRoot, original); return; }
        FreshPassengerAdmission admission = DETACHED_OPEN.get() == 0 ? null
            : FullFinishingServices.forServer(world.getServer()).entityFacts.pendingAdmission(finalizing);
        if (admission == null || !admission.detachedFor(finalizing)) {
            if (scope != null) scope.bulkInsertion(insertionRoot, original); else original.run();
            return;
        }
        admission.extendDetached(admission.activeRoot, false);
        if (!admission.contains(insertionRoot))
            throw admission.failure(admission.members.get(finalizing),
                "finalization bulk insertion is outside the detached candidate graph");
        admission.validate(); original.run(); admission.validate();
        admission.detachedCustodyChanged();
    }
    public static boolean detachedCustodyOpen() { return DETACHED_OPEN.get() != 0; }
    /** Native insertion of one detached graph member returned; settle once nothing carries an uninserted member. */
    public static void insertionReturned(Entity entity) {
        if (DETACHED_OPEN.get() == 0 || !(entity.level() instanceof ServerLevel world)) return;
        var facts = FullFinishingServices.existingPassengerFacts(world.getServer());
        FreshPassengerAdmission admission = facts == null ? null : facts.pendingAdmission(entity);
        if (admission != null) admission.detachedCustodyChanged();
    }
    static void relationChanged(FullEntityFacts facts, Entity vehicle) {
        if (DETACHED_OPEN.get() == 0) return;
        FreshPassengerAdmission admission = facts.pendingAdmission(vehicle);
        if (admission != null) admission.detachedCustodyChanged();
    }
    private boolean detachedFor(Entity candidate) {
        return entryPath == EntryPath.DETACHED_FINALIZATION_INSERTION && expectedOuterCandidate == candidate && !closed;
    }
    private void extendDetached(Entity newActiveRoot, boolean allowCandidateReparent) {
        extending = true;
        try { extendCurrentGraph(newActiveRoot, allowCandidateReparent); } finally { extending = false; }
    }
    /** The detached custody is required exactly while an inserted member carries an uninserted entity. */
    void detachedCustodyChanged() {
        if (entryPath != EntryPath.DETACHED_FINALIZATION_INSERTION || closed || extending) return;
        for (Member member : ordered)
            if (!member.entity.isRemoved() && facts.hasStorage(member.entity) && carriesUninserted(member.entity)) return;
        settleDetached("detached custody carries no uninserted passenger");
    }
    private boolean carriesUninserted(Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers())
            if (!facts.hasStorage(passenger) || carriesUninserted(passenger)) return true;
        return false;
    }
    private void settleDetached(String reason) {
        if (closed) return;
        boolean upgraded = !expectedOuterCandidate.isRemoved() && facts.hasStorage(expectedOuterCandidate);
        close(reason);
        facts.directAdmissionSettled(this, upgraded);
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
    boolean contains(Entity entity) { return members.containsKey(entity); }
    ServerLevel world() { return world; }
    /** Adds only newly attached exact members while the same outer candidate is still finalizing. */
    private void extendCurrentGraph(Entity newActiveRoot, boolean allowCandidateReparent) {
        if (closed || cancellation != null) throw failure(members.get(root), "cannot extend retired admission");
        int originalSize = ordered.size();
        IdentityHashMap<Entity, List<Entity>> children = new IdentityHashMap<>();
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Entity> entities = new ArrayDeque<>(); ArrayDeque<Entity> expectedParents = new ArrayDeque<>();
        entities.push(newActiveRoot); expectedParents.push(newActiveRoot);
        while (!entities.isEmpty()) {
            Entity entity = entities.pop(); Entity encodedParent = expectedParents.pop();
            Entity parent = encodedParent == newActiveRoot && entity == newActiveRoot ? null : encodedParent;
            if (!seen.add(entity)) throw failure(members.get(entity), "cyclic or duplicate graph node during extension");
            if (!(entity instanceof FullEntityFacts.Access access) || entity.level() != world || entity.isRemoved()
                    || entity.getVehicle() != parent)
                throw facts.passengerFailure(root, entity, parent == null ? 0 : 1,
                    "extended graph world/removal/parent mismatch", this);
            List<Entity> direct = List.copyOf(entity.getPassengers()); children.put(entity, direct);
            Member existing = members.get(entity);
            if (existing == null) {
                existing = new Member(entity, parent, parent == null ? 0 : members.get(parent).depth + 1,
                    access.phoenium$entityIncarnation(), entity.getUUID(), access.phoenium$rawEntityId(),
                    entity.chunkPosition(), direct);
                members.put(entity, existing); ordered.add(existing);
            } else if (existing.parent != parent) {
                if (!allowCandidateReparent || entity != expectedOuterCandidate)
                    throw failure(existing, "extended graph changed an existing parent edge");
                existing.parent = parent;
            }
            for (int index = direct.size() - 1; index >= 0; index--) {
                entities.push(direct.get(index)); expectedParents.push(entity);
            }
        }
        for (int index = 0; index < originalSize; index++) {
            Member member = ordered.get(index);
            if (seen.contains(member.entity)) continue;
            if (!facts.hasStorage(member.entity)) throw failure(member, "extended graph detached an uninserted admitted member");
            Entity currentParent = member.entity.getVehicle();
            if (member.parent != currentParent) {
                if (!allowCandidateReparent || member.entity != expectedOuterCandidate)
                    throw failure(member, "extended graph changed a retained inserted parent edge");
                member.parent = currentParent;
            }
            List<Entity> retainedChildren = List.copyOf(member.entity.getPassengers());
            if (retainedChildren.stream().anyMatch(child -> !members.containsKey(child) && !seen.contains(child)))
                throw failure(member, "retained inserted prefix gained an unrelated passenger");
            children.put(member.entity, retainedChildren);
        }
        activeRoot = newActiveRoot;
        if (ordered.size() == originalSize) {
            for (Member member : ordered) member.children = children.get(member.entity);
            validate(); return;
        }
        Set<ChunkPos> positions = new LinkedHashSet<>(); Set<UUID> uuids = new HashSet<>(); Set<Integer> ids = new HashSet<>();
        for (Member member : ordered) {
            positions.add(member.position);
            if (!uuids.add(member.uuid) || !ids.add(member.id)) throw failure(member, "extended graph identity collision");
        }
        publisher.requireOwners(Set.copyOf(positions));
        for (int index = originalSize; index < ordered.size(); index++) {
            Member member = ordered.get(index);
            if (facts.hasRoute(member.entity) || publisher.current(member.uuid.toString()) != null)
                throw failure(member, "extended graph collides with an existing identity");
        }
        List<Entity> additions = ordered.subList(originalSize, ordered.size()).stream().map(member -> member.entity).toList();
        inputs.add(FullEntityFacts.loadedBatch(world, additions));
        for (int index = originalSize; index < ordered.size(); index++) {
            Member member = ordered.get(index); member.binding = publisher.publish(member.entity);
            facts.admitPassengerIdentity(member.entity, this);
        }
        for (Member member : ordered) {
            List<Entity> updated = children.get(member.entity);
            if (!sameAliases(member.children, updated)) FullEntityFacts.passengersChanged(member.entity);
            member.children = updated;
        }
        validate();
    }
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
        return "entryPath=" + entryPath + ",admissionIdentity=" + admissionIdentity
            + ",outerGeneration=" + outerGeneration + ",nestedDepth=" + nestedDepth
            + ",scopeExit=" + exitReason + ",world=" + world.dimension().identifier()
            + (member == null ? ",UNRELATED" : ",phase=" + member.phase
            + ",nativeIncarnation=" + member.incarnation + ",uuid=" + member.uuid + ",runtimeId=" + member.id
            + ",admittedBinding=" + member.binding + ",currentBinding=" + current);
    }
    String successDescription() {
        Member outer = members.get(expectedOuterCandidate);
        return "entryPath=" + entryPath + " admissionIdentity=" + admissionIdentity
            + " outerGeneration=" + outerGeneration + " nestedDepth=" + nestedDepth
            + " rootType=" + root.getType() + " outerType=" + expectedOuterCandidate.getType()
            + " rootPhase=" + members.get(root).phase + " outerPhase=" + (outer == null ? "UNRELATED" : outer.phase)
            + " scopeExit=" + exitReason;
    }
    private static boolean sameAliases(List<Entity> expected, List<Entity> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) if (expected.get(i) != actual.get(i)) return false;
        return true;
    }
    /** Native storage owns the inserted prefix; unused input identities are retired exactly. */
    @Override public void close() { close(false, "explicit admission close"); }
    void shutdown() { close(true, "service or world shutdown"); }
    private void close(String reason) { close(false, reason); }
    private void close(boolean shutdown, String reason) {
        if (closed) return;
        closed = true; exitReason = reason;
        if (detachedCounted) { detachedCounted = false; DETACHED_OPEN.decrementAndGet(); }
        RuntimeException runtimeFailure = null; Error errorFailure = null;
        for (Member member : ordered) {
            try {
                facts.releasePassengerIdentity(member.entity, this);
                if (member.binding != null && (shutdown || !facts.hasStorage(member.entity))) {
                    publisher.retire(member.binding); member.phase = Phase.RETIRED;
                }
            } catch (RuntimeException failure) {
                if (runtimeFailure == null) runtimeFailure = failure; else runtimeFailure.addSuppressed(failure);
            } catch (Error failure) {
                if (errorFailure == null) errorFailure = failure; else errorFailure.addSuppressed(failure);
            }
        }
        for (int index = inputs.size() - 1; index >= 0; index--) try { inputs.get(index).close(); }
        catch (RuntimeException problem) {
            if (runtimeFailure != null) runtimeFailure.addSuppressed(problem);
            else if (errorFailure != null) errorFailure.addSuppressed(problem);
            else runtimeFailure = problem;
        }
        if (errorFailure != null) {
            if (runtimeFailure != null) errorFailure.addSuppressed(runtimeFailure);
            throw errorFailure;
        }
        if (runtimeFailure != null) throw runtimeFailure;
    }

    /**
     * Exact lifetime shared by one native NaturalSpawner.spawnCategoryForPosition invocation and
     * any reentrant invocation in the same world. Direct vehicle insertion is admitted only while
     * the exact outer candidate is inside its native finalizeSpawn call. The admission remains
     * alive until that same candidate reaches the later native bulk insertion or the outer method
     * exits through any return/throw path.
     */
    public static final class NaturalSpawnScope implements AutoCloseable {
        private final ScopeState state;
        private final NaturalSpawnScope parent;
        private final int depth;
        private boolean closed;
        private NaturalSpawnScope(ScopeState state, NaturalSpawnScope parent, int depth) {
            this.state = state; this.parent = parent; this.depth = depth;
        }
        public Finalization finalizing(Entity candidate) {
            requireCurrent(); Objects.requireNonNull(candidate, "candidate");
            if (candidate.level() != state.world) throw new ChunkProtocolException("natural-spawn candidate changed world");
            state.finalizing.push(candidate);
            return new Finalization(this, candidate);
        }
        /** The original native call is always invoked exactly once and its boolean is preserved. */
        public boolean directInsertion(Entity insertionRoot, BooleanSupplier original) {
            requireCurrent(); Objects.requireNonNull(insertionRoot, "insertionRoot"); Objects.requireNonNull(original, "original");
            Entity candidate = state.finalizing.peek();
            if (candidate == null || insertionRoot == candidate || insertionRoot.getPassengers().isEmpty()
                    || !containsIdentity(insertionRoot, candidate)) return original.getAsBoolean();
            FreshPassengerAdmission admission = state.byMember.get(candidate);
            if (admission == null) {
                admission = FreshPassengerAdmission.begin(state.world, insertionRoot, candidate, state.publisher,
                    EntryPath.DIRECT_FINALIZATION_INSERTION, state.generation, depth);
                state.register(admission); state.facts.directAdmissionPrepared();
            } else {
                if (admission.expectedOuterCandidate != candidate)
                    throw new ChunkProtocolException("reentrant direct insertion changed the admitted natural-spawn candidate");
                if (!admission.contains(insertionRoot)) {
                    admission.extendCurrentGraph(insertionRoot, true); state.register(admission);
                } else admission.validate();
            }
            boolean accepted = original.getAsBoolean();
            state.facts.directInsertionReturned(accepted);
            return accepted;
        }
        /** Preserves the original bulk path while reusing any exact direct-finalization admission. */
        public void bulkInsertion(Entity insertionRoot, Runnable original) {
            requireCurrent(); Objects.requireNonNull(insertionRoot, "insertionRoot"); Objects.requireNonNull(original, "original");
            FreshPassengerAdmission admission = state.byMember.get(insertionRoot);
            Entity candidate = state.finalizing.peek();
            if (candidate != null) {
                FreshPassengerAdmission candidateAdmission = state.byMember.get(candidate);
                if (candidateAdmission != null) {
                    candidateAdmission.extendCurrentGraph(candidateAdmission.activeRoot, false); state.register(candidateAdmission);
                    admission = state.byMember.get(insertionRoot);
                    if (admission != candidateAdmission)
                        throw new ChunkProtocolException("finalization bulk insertion is outside the admitted candidate graph");
                    admission.validate(); original.run(); admission.validate(); return;
                }
            }
            if (admission == null && insertionRoot.getPassengers().isEmpty()) { original.run(); return; }
            if (admission == null) {
                admission = FreshPassengerAdmission.begin(state.world, insertionRoot, insertionRoot, state.publisher,
                    EntryPath.BULK_INSERTION, state.generation, depth);
                state.register(admission);
            } else if (admission.expectedOuterCandidate != insertionRoot) {
                throw new ChunkProtocolException("bulk insertion attempted to consume another candidate's provisional identity");
            }
            RuntimeException runtimeFailure = null; Error errorFailure = null;
            try {
                admission.validate();
                original.run();
                admission.validate();
            } catch (RuntimeException failure) {
                runtimeFailure = failure; throw failure;
            } catch (Error failure) {
                errorFailure = failure; throw failure;
            } finally {
                try {
                    boolean succeeded = runtimeFailure == null && errorFailure == null;
                    state.finish(admission, succeeded ? "native bulk insertion completed" : "native bulk insertion failed", succeeded);
                } catch (RuntimeException | Error cleanup) {
                    if (runtimeFailure != null) runtimeFailure.addSuppressed(cleanup);
                    else if (errorFailure != null) errorFailure.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
        private void requireCurrent() {
            if (closed || NATURAL_SCOPES.get().peek() != this)
                throw new ChunkProtocolException("natural-spawn passenger scope is not current");
        }
        @Override public void close() {
            if (closed) return;
            ArrayDeque<NaturalSpawnScope> stack = NATURAL_SCOPES.get();
            if (stack.peek() != this) throw new ChunkProtocolException("natural-spawn passenger scope closed out of order");
            stack.pop(); closed = true;
            try {
                if (parent == null || parent.state != state) state.finishAll("outer natural-spawn scope exited");
            } finally { if (stack.isEmpty()) NATURAL_SCOPES.remove(); }
        }
    }
    public static final class Finalization implements AutoCloseable {
        private final NaturalSpawnScope scope; private final Entity candidate; private boolean closed;
        private Finalization(NaturalSpawnScope scope, Entity candidate) { this.scope = scope; this.candidate = candidate; }
        @Override public void close() {
            if (closed) return;
            scope.requireCurrent();
            if (scope.state.finalizing.peek() != candidate)
                throw new ChunkProtocolException("natural-spawn finalization scope closed out of order");
            scope.state.finalizing.pop(); closed = true;
        }
    }
    private static final class ScopeState {
        final ServerLevel world; final Publisher publisher; final long generation; final FullEntityFacts facts;
        final ArrayDeque<Entity> finalizing = new ArrayDeque<>();
        final Set<FreshPassengerAdmission> admissions = Collections.newSetFromMap(new IdentityHashMap<>());
        final IdentityHashMap<Entity, FreshPassengerAdmission> byMember = new IdentityHashMap<>();
        ScopeState(ServerLevel world, Publisher publisher, long generation) {
            this.world = world; this.publisher = publisher; this.generation = generation;
            this.facts = FullFinishingServices.forServer(world.getServer()).entityFacts;
        }
        void register(FreshPassengerAdmission admission) {
            admissions.add(admission);
            for (Member member : admission.ordered) {
                FreshPassengerAdmission prior = byMember.putIfAbsent(member.entity, admission);
                if (prior != null && prior != admission)
                    throw new ChunkProtocolException("natural-spawn graph overlaps another pending admission");
            }
        }
        void finish(FreshPassengerAdmission admission, String reason, boolean upgraded) {
            if (!admissions.remove(admission)) return;
            for (Member member : admission.ordered) byMember.remove(member.entity, admission);
            boolean alreadyRetired = admission.closed;
            admission.close(reason);
            if (!alreadyRetired && admission.entryPath == EntryPath.DIRECT_FINALIZATION_INSERTION)
                facts.directAdmissionSettled(admission, upgraded);
        }
        void finishAll(String reason) {
            RuntimeException runtimeFailure = null; Error errorFailure = null;
            for (FreshPassengerAdmission admission : List.copyOf(admissions)) {
                try { finish(admission, reason, false); }
                catch (RuntimeException failure) { if (runtimeFailure == null) runtimeFailure = failure; else runtimeFailure.addSuppressed(failure); }
                catch (Error failure) { if (errorFailure == null) errorFailure = failure; else errorFailure.addSuppressed(failure); }
            }
            finalizing.clear(); byMember.clear(); admissions.clear();
            if (errorFailure != null) throw errorFailure;
            if (runtimeFailure != null) throw runtimeFailure;
        }
    }
    private static boolean containsIdentity(Entity root, Entity target) {
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Entity> pending = new ArrayDeque<>(); pending.push(root);
        while (!pending.isEmpty()) {
            Entity entity = pending.pop();
            if (!seen.add(entity)) throw new ChunkProtocolException("cyclic or duplicate natural-spawn passenger graph");
            if (entity == target) return true;
            List<Entity> children = entity.getPassengers();
            for (int index = children.size() - 1; index >= 0; index--) pending.push(children.get(index));
        }
        return false;
    }
    private static final class Member {
        final Entity entity; Entity parent; final int depth, id; final long incarnation; final UUID uuid;
        final ChunkPos position; List<Entity> children;
        ObjectOwnership binding; Phase phase = Phase.IDENTITY_PUBLISHED_NOT_TRACKED;
        Member(Entity entity, Entity parent, int depth, long incarnation, UUID uuid, int id,
                ChunkPos position, List<Entity> children) {
            this.entity = entity; this.parent = parent; this.depth = depth; this.incarnation = incarnation;
            this.uuid = uuid; this.id = id; this.position = position; this.children = children;
        }
    }
}
