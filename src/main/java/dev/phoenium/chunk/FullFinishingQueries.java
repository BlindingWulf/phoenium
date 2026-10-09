package dev.phoenium.chunk;

import dev.phoenium.mixin.FullLevelThreadAccessMixin;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Private read authority for the enumerated calls in one admitted FULL_FINISH operation.
 * The original selector, including its UNKNOWN ticket prefix, always runs. An unfinished
 * public FULL future is never completed or replaced: only this getter's local join result
 * can use the exact retained preparation. Such a result never enters the ordinary cache.
 */
public final class FullFinishingQueries {
    private static final ThreadLocal<Invocation> CURRENT = new dev.phoenium.core.FastThreadLocal<>();
    /** S9-D4: open invocations on all threads. A thread increments before its own reads and decrements
     * only after removing its scope, so zero proves this thread has none and skips the ThreadLocal probe
     * on the hot chunk-getter/block-read hooks. */
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE = new java.util.concurrent.atomic.AtomicInteger();
    private FullFinishingQueries() { }
    private static Invocation current() { return ACTIVE.get() == 0 ? null : CURRENT.get(); }
    /** True when no initialized-query, block-entity diagnostic or portal-destination scope is open anywhere,
     * so an ordinary chunk-cache getChunk has no observer to notify. */
    public static boolean ordinaryChunkQuery() {
        return ACTIVE.get() == 0 && BlockEntityChunkQueryDiagnostic.idle()
                && dev.phoenium.live.PortalDestinationObservation.idle();
    }

    public static Invocation enter(FullRegistrationRuntime runtime, FullRegistrationRuntime.Graph source,
                                   ChunkStageClaim claim, FullInitializedInputs.Receipt receipt) {
        Objects.requireNonNull(runtime); Objects.requireNonNull(source); Objects.requireNonNull(claim);
        if (CURRENT.get() != null) throw breach("overlapping initialized FULL query operation");
        source.requireFinishingAuthority(claim, receipt);
        Invocation invocation = new Invocation(runtime, source, claim, receipt);
        CURRENT.set(invocation); ACTIVE.incrementAndGet();
        try { invocation.requireCurrent(); return invocation; }
        catch (Throwable failure) { invocation.close(); throw failure; }
    }

    public static final class Invocation implements AutoCloseable {
        private FullRegistrationRuntime runtime;
        private FullRegistrationRuntime.Graph source;
        private ChunkStageClaim claim;
        private FullInitializedInputs.Receipt receipt;
        private final Map<ChunkPos, FullInitializedInputs.Target> targets = new HashMap<>();
        private Query query;
        private Shape shape;
        private BlockRead blockRead;
        private LocationRead locationRead;
        private Invocation(FullRegistrationRuntime runtime, FullRegistrationRuntime.Graph source,
                           ChunkStageClaim claim, FullInitializedInputs.Receipt receipt) {
            this.runtime = runtime; this.source = source; this.claim = claim; this.receipt = receipt;
            for (var target : receipt.targets()) if (targets.put(target.position(), target) != null)
                throw breach("duplicate initialized target in operation receipt");
        }
        private void requireCurrent() {
            if (CURRENT.get() != this || claim == null) throw breach("expired initialized FULL query operation");
            try {
                claim.require(receipt.operation());
                if (!claim.belongsTo(runtime.custody) || receipt.world() != runtime.level()
                        || receipt.server() != runtime.server() || !receipt.source().equals(source.work)
                        || !receipt.operation().equals(source.finishing.stage())
                        || receipt.operation().kind() != ChunkStageKind.FULL_FINISH)
                    throw breach("foreign initialized FULL source, work or operation");
                source.requireFinishingAuthority(claim, receipt);
            } catch (ChunkProtocolException failure) {
                throw ChunkSynchronousAccessGuard.breach(failure);
            }
        }
        private FullInitializedInputs.Target target(LevelReader world, ChunkPos position,
                                                   FullInitializedInputs.Purpose purpose) {
            requireCurrent();
            var target = targets.get(position);
            if (world != receipt.world() || target == null || !target.purposes().contains(purpose))
                throw breach("initialized query has no exact world/position/purpose receipt");
            if (runtime.visible(position) != target.holder()
                    || target.holder() != null && ((ChunkHolder) target.holder()).getTicketLevel() != target.ticketLevel())
                throw breach("initialized query holder or ticket incarnation changed");
            if (target.holder() != null) {
                var evidence = (FullHolderEvidence) target.holder();
                if (purpose != FullInitializedInputs.Purpose.ENVIRONMENT
                        && purpose != FullInitializedInputs.Purpose.STRUCTURE_REFERENCES
                        && purpose != FullInitializedInputs.Purpose.STRUCTURE_STARTS
                        && (evidence.phoenium$futures().get(ChunkStatus.SPAWN.getIndex()) != target.lowerSlot()
                        || evidence.phoenium$futures().get(ChunkStatus.FULL.getIndex()) != target.publicSlot())
                        )
                    throw breach("initialized query selected a replacement holder slot");
            }
            if (purpose == FullInitializedInputs.Purpose.ENVIRONMENT && biomePresent(target)) {
                if (!FullBiomeInputs.validates(target.holder(), target.biomes())
                        || !target.biomes().actual().getPos().equals(position)
                        || target.route() == null || !target.route().equals(claim.frozenRoute()))
                    throw breach("environment query lacks its exact original biome slot/result/object enclosure");
            }
            if (purpose == FullInitializedInputs.Purpose.STRUCTURE_REFERENCES
                    || purpose == FullInitializedInputs.Purpose.STRUCTURE_STARTS) {
                var status = purpose == FullInitializedInputs.Purpose.STRUCTURE_REFERENCES
                        ? ChunkStatus.STRUCTURE_REFERENCES : ChunkStatus.STRUCTURE_STARTS;
                var evidence = FullStructureInputs.evidence(target, status);
                if (!FullStatusInputs.validates(target.holder(), evidence)
                        || !evidence.actual().getPos().equals(position) || !claim.frozenRoute().equals(target.route()))
                    throw breach("structure query lacks exact original status/slot/result/object/route evidence");
                source.requireStructureInput(target.holder());
            }
            if (target.actual() != null) {
                if (target.actual().getLevel() != receipt.world() || !target.actual().getPos().equals(position)
                        || target.work() == null || target.lifecycle() == null
                        || !target.work().lifecycle().equals(target.lifecycle())
                        || target.route() == null || !target.route().equals(claim.frozenRoute()))
                    throw breach("initialized query object/work/route does not belong to the operation enclosure");
                if (target.availability() == FullInitializedInputs.Availability.PREPARED) {
                    var producer = runtime.findGraph(target.holder()).orElse(null);
                    var prepared = producer == null ? null : producer.prepared().orElse(null);
                    if (prepared == null || !prepared.work().equals(target.work())
                            || !prepared.lifecycle().equals(target.lifecycle()) || prepared.holder() != target.holder()
                            || prepared.actual() != target.actual() || !prepared.attempt().equals(target.preparationAttempt())
                            || !(((ChunkAccess) target.holder().getChunkIfPresentUnchecked(ChunkStatus.SPAWN))
                                instanceof ImposterProtoChunk imposter) || imposter.getWrapped() != target.actual())
                        throw breach("initialized query preparation receipt or object changed");
                } else if (target.availability() == FullInitializedInputs.Availability.PUBLISHED
                        && (target.publication() == null || !runtime.published.validates(target.publication())))
                    throw breach("initialized query publication receipt changed");
            }
            return target;
        }
        @Override public void close() {
            if (claim == null) return;
            if (CURRENT.get() != this || query != null || shape != null || blockRead != null || locationRead != null)
                throw breach("initialized FULL query scope escaped its synchronous operation");
            CURRENT.set(null); ACTIVE.decrementAndGet(); targets.clear(); runtime = null; source = null; claim = null; receipt = null;
        }
    }

    private static final class Query {
        final Invocation invocation;
        final FullInitializedInputs.Target target;
        final ChunkStatus status;
        final boolean create;
        final FullInitializedInputs.Purpose purpose;
        CompletableFuture<ChunkResult<ChunkAccess>> selected;
        LevelChunk privateResult;
        Query(Invocation invocation, FullInitializedInputs.Target target, ChunkStatus status,
              boolean create, FullInitializedInputs.Purpose purpose) {
            this.invocation = invocation; this.target = target; this.status = status; this.create = create; this.purpose = purpose;
        }
    }
    private record Shape(Invocation invocation, BlockGetter world, BlockPos position, BlockState state) { }
    private record BlockRead(FullInitializedInputs.Target target, BlockPos position, boolean privateInput) { }
    private record LocationRead(ServerLevel world, BlockPos position) { }

    /** Only the original LocationPredicate body opens this scope. Its conditions and
     * short-circuiting remain in vanilla; each reached chunk getter checks a declared input. */
    public static boolean locationPredicate(ServerLevel world, double x, double y, double z,
                                             java.util.function.BooleanSupplier original) {
        Invocation invocation = current();
        if (!FullFinishingServices.finishing()) return original.getAsBoolean();
        if (invocation == null || invocation.receipt.contexts() == null || world != invocation.receipt.world())
            throw breach("location predicate has no exact saved FULL context");
        invocation.requireCurrent();
        var parent = invocation.locationRead;
        invocation.locationRead = new LocationRead(world, BlockPos.containing(x, y, z));
        try { return original.getAsBoolean(); }
        finally { invocation.locationRead = parent; }
    }

    /** The ordinary cache getter still selects its own future and performs its reached
     * ticket effects. Only this location-predicate call can borrow an unpublished FULL. */
    public static ChunkAccess predicateChunk(ServerLevel world, int x, int z, ChunkStatus status, boolean create,
                                              Supplier<ChunkAccess> original) {
        Invocation invocation = current();
        if (invocation == null || invocation.locationRead == null || invocation.query != null) {
            try { return BlockEntityChunkQueryDiagnostic.chunk(world, x, z, status, create, original); }
            catch (RuntimeException | Error failure) {
                dev.phoenium.service.NaturalSpawnContinuation.failedChunkQuery(world, x, z, status, create, failure);
                throw failure;
            }
        }
        invocation.requireCurrent();
        if (world != invocation.locationRead.world) throw breach("location predicate getter changed worlds");
        if (status == ChunkStatus.STRUCTURE_REFERENCES || status == ChunkStatus.STRUCTURE_STARTS) {
            if (!create || invocation.receipt.structures() == null)
                throw breach("structure getter has no declared structure context");
            return query(world, status == ChunkStatus.STRUCTURE_REFERENCES
                    ? FullInitializedInputs.Purpose.STRUCTURE_REFERENCES : FullInitializedInputs.Purpose.STRUCTURE_STARTS,
                    x, z, status, create, original);
        }
        if (status != ChunkStatus.FULL
                || !invocation.receipt.contexts().predicateFullChunks().contains(new ChunkPos(x, z)))
            throw breach("location predicate getter has no declared exact FULL context input");
        return query(world, FullInitializedInputs.Purpose.BLOCK_CONTEXT, x, z, status, create, original);
    }

    /** Called at the exact difficulty, collision, or positional biome getChunk invocation. */
    public static ChunkAccess query(LevelReader world, FullInitializedInputs.Purpose purpose,
                                    int x, int z, ChunkStatus status, boolean create,
                                    Supplier<ChunkAccess> original) {
        Invocation invocation = current();
        if (invocation == null) return original.get();
        boolean audited = switch (purpose) {
            case DIFFICULTY, COLLISION -> status == ChunkStatus.FULL && !create;
            case DYNAMIC_SHAPE_MUTATION -> status == ChunkStatus.FULL && create;
            case ENVIRONMENT -> status == ChunkStatus.BIOMES && !create;
            case BLOCK_CONTEXT -> status == ChunkStatus.FULL && invocation.locationRead != null;
            case STRUCTURE_REFERENCES -> status == ChunkStatus.STRUCTURE_REFERENCES && create && invocation.locationRead != null;
            case STRUCTURE_STARTS -> status == ChunkStatus.STRUCTURE_STARTS && create && invocation.locationRead != null;
            default -> false;
        };
        if (!audited) throw breach("unaudited initialized query status/create/purpose combination");
        if (purpose == FullInitializedInputs.Purpose.DYNAMIC_SHAPE_MUTATION) {
            if (!(world instanceof Level level) || invocation.shape == null
                    || !position(invocation.shape.position).equals(new ChunkPos(x, z)))
                throw breach("dynamic query purpose has no exact audited shape invocation");
            requireShape(level, invocation.shape.position);
        }
        if (invocation.query != null) throw breach("nested initialized getter selection");
        var target = invocation.target(world, new ChunkPos(x, z), purpose);
        Query query = new Query(invocation, target, status, create, purpose);
        invocation.query = query;
        try {
            ChunkAccess result = original.get();
            invocation.requireCurrent();
            if (query.privateResult != null && result != query.privateResult)
                throw breach("private initialized getter returned another object");
            if (status == ChunkStatus.FULL) {
                boolean originalReady = query.selected != null && query.selected.isDone();
                if (originalReady && query.selected.getNow(null).orElse(null) != result)
                    throw breach("initialized getter changed its original ready/absent result");
                // FULL,false may return the original cached absent result without selecting a
                // future. No private object is supplied and the original null branch stays null.
                if (!originalReady && target.actual() != null && result != null && result != target.actual()
                        && !(result instanceof ImposterProtoChunk imposter && imposter.getWrapped() == target.actual()))
                    throw breach("initialized getter returned a foreign ready object");
                if (target.availability() == FullInitializedInputs.Availability.ABSENT && result != null)
                    throw breach("absent initialized input became present during the operation");
            } else if (status == ChunkStatus.BIOMES) {
                if (query.selected == null && result == null) {
                    // The original BIOMES,false cache can retain an absent lookup even after
                    // the holder becomes eligible. Preserve its original generator fallback.
                } else if (biomePresent(target)) {
                    if (target.biomes() == null || !sameBiomeInput(target.biomes().actual(), result))
                        throw breach("environment getter returned another original biome object");
                } else if (result != null) throw breach("absent biome input became present during the operation");
            } else if (status == ChunkStatus.STRUCTURE_REFERENCES || status == ChunkStatus.STRUCTURE_STARTS) {
                if (!invocation.receipt.structures().allows(target, status, result))
                    throw breach("structure getter returned an uncaptured original status/cache object");
            }
            return result;
        } finally { invocation.query = null; }
    }

    /** Original getChunkFutureMainThread has already performed all selector effects. */
    public static void selected(ServerLevel world, int x, int z, ChunkStatus status, boolean create,
                                CompletableFuture<ChunkResult<ChunkAccess>> selected) {
        BlockEntityChunkQueryDiagnostic.selected(world, x, z, status, create, selected);
        Invocation invocation = current();
        if (invocation == null || invocation.query == null) return;
        Query query = invocation.query;
        invocation.target(world, new ChunkPos(x, z), query.purpose);
        if (query.selected != null || status != query.status || create != query.create
                || !query.target.position().equals(new ChunkPos(x, z)))
            throw breach("initialized query selector does not match its exact typed invocation");
        query.selected = Objects.requireNonNull(selected);
        if (status == ChunkStatus.FULL && selected != (query.target.holder() == null || query.target.ticketLevel() > ChunkLevel.byStatus(ChunkStatus.FULL)
                ? GenerationChunkHolder.UNLOADED_CHUNK_FUTURE : query.target.publicSlot()))
            throw breach("initialized getter selected another original FULL future");
        if (status == ChunkStatus.BIOMES && (biomePresent(query.target)
                ? query.target.biomes() == null || selected != query.target.biomes().slot()
                : selected != GenerationChunkHolder.UNLOADED_CHUNK_FUTURE))
            throw breach("environment getter selected another original biome/absent future");
        if (status == ChunkStatus.STRUCTURE_REFERENCES || status == ChunkStatus.STRUCTURE_STARTS) {
            var evidence = FullStructureInputs.evidence(query.target, status);
            if (evidence == null || selected != evidence.slot() || !selected.isDone())
                throw breach("structure getter selected another or unfinished original status future");
        }
        // Ready, absent and exceptional/cancelled results retain the original join/error branch.
        if (selected.isDone()) return;
        if (status != ChunkStatus.FULL || query.target.availability() != FullInitializedInputs.Availability.PREPARED
                || selected != query.target.publicSlot() || query.target.actual() == null)
            throw breach("unfinished query lacks the exact retained FULL preparation");
        query.privateResult = query.target.actual();
    }

    /** True only between the exact original selector and this getter's local consumption. */
    public static boolean usesPrivateResult() {
        Invocation invocation = current();
        if (invocation == null || invocation.query == null || invocation.query.privateResult == null) return false;
        invocation.requireCurrent();
        return true;
    }

    /** Null means execute the original join. The selected future itself stays untouched. */
    public static ChunkResult<ChunkAccess> privateJoin(CompletableFuture<?> selected) {
        if (!usesPrivateResult()) return null;
        Query query = current().query;
        if (query.selected != selected) throw breach("initialized query joined a different dependency");
        current().target(current().receipt.world(), query.target.position(), query.purpose);
        return ChunkResult.of(query.privateResult);
    }

    /** Captured only at BlockCollisions.computeNext's original collision-shape invocation. */
    public static VoxelShape collisionShape(BlockState state, BlockGetter world, BlockPos position,
                                            Supplier<VoxelShape> original) {
        Invocation invocation = current();
        if (invocation == null) return original.get();
        invocation.requireCurrent();
        if (world != invocation.receipt.world()) throw breach("foreign collision shape world");
        var target = invocation.target((LevelReader) world, position(position), FullInitializedInputs.Purpose.COLLISION);
        if (target.actual() == null || target.actual().getBlockState(position) != state)
            throw breach("collision shape used an absent or replaced initialized block state");
        Shape previous = invocation.shape;
        invocation.shape = new Shape(invocation, world, position.immutable(), state);
        try { return original.get(); }
        finally { invocation.shape = previous; }
    }

    private static Shape requireShape(Level world, BlockPos position) {
        Invocation invocation = current();
        Shape shape = invocation == null ? null : invocation.shape;
        if (shape == null || shape.invocation != invocation || shape.world != world || !shape.position.equals(position)
                || !(shape.state.getBlock() instanceof ShulkerBoxBlock || shape.state.getBlock() instanceof MovingPistonBlock))
            throw breach("dynamic shape mutation is outside the audited collision call and block position");
        invocation.target(world, position(position), FullInitializedInputs.Purpose.DYNAMIC_SHAPE_MUTATION);
        return shape;
    }

    public static LevelChunk dynamicChunk(Level world, BlockPos position, Supplier<LevelChunk> original) {
        Invocation invocation = current();
        if (invocation == null || invocation.shape == null) return original.get();
        requireShape(world, position);
        return (LevelChunk) query(world, FullInitializedInputs.Purpose.DYNAMIC_SHAPE_MUTATION,
                position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, true, original::get);
    }

    /** The original IMMEDIATE creation, registration and shape-state reads execute once. */
    public static BlockEntity dynamicBlockEntity(LevelChunk chunk, BlockPos position,
                                                LevelChunk.EntityCreationType creation, Supplier<BlockEntity> original) {
        Invocation invocation = current();
        if (invocation != null && invocation.locationRead != null && invocation.shape == null) {
            var target = invocation.target(chunk.getLevel(), chunk.getPos(), FullInitializedInputs.Purpose.BLOCK_CONTEXT);
            if (creation != LevelChunk.EntityCreationType.IMMEDIATE || target.actual() != chunk
                    || !invocation.receipt.contexts().predicateFullChunks().contains(chunk.getPos()))
                throw breach("location predicate block entity lacks its exact original IMMEDIATE input");
            FullSavedContextInputs.consumingBlockTag(chunk, position);
            return original.get();
        }
        if (invocation == null || invocation.shape == null) return original.get();
        requireShape(chunk.getLevel(), position);
        var target = invocation.target(chunk.getLevel(), chunk.getPos(), FullInitializedInputs.Purpose.DYNAMIC_SHAPE_MUTATION);
        if (target.actual() != chunk || creation != LevelChunk.EntityCreationType.IMMEDIATE)
            throw breach("dynamic shape mutation has no exact initialized object/creation authority");
        return original.get();
    }

    /** Original BlockDataSource readiness is retained; only an exact frozen private preparation can satisfy its unpublished branch. */
    static boolean blockLoaded(ServerLevel world, BlockPos position, java.util.function.BooleanSupplier original) {
        Invocation invocation = current();
        if (invocation == null) return original.getAsBoolean();
        var target = invocation.target(world, position(position), FullInitializedInputs.Purpose.BLOCK_CONTEXT);
        if (invocation.blockRead != null) throw breach("nested saved block context readiness call");
        boolean loaded = original.getAsBoolean();
        invocation.requireCurrent();
        if (!loaded) return false;
        if (target.actual() == null) throw breach("saved block context became present outside its retained input");
        invocation.blockRead = new BlockRead(target, position.immutable(),
                target.availability() == FullInitializedInputs.Availability.PREPARED);
        return true;
    }

    /** The original serializer still runs at its original call and observes the retained exact block entity. */
    static BlockEntity blockEntity(ServerLevel world, BlockPos position, Supplier<BlockEntity> original) {
        Invocation invocation = current();
        if (invocation == null) return original.get();
        var target = invocation.target(world, position(position), FullInitializedInputs.Purpose.BLOCK_CONTEXT);
        BlockRead read = invocation.blockRead;
        if (read == null || read.target != target || !read.position.equals(position))
            throw breach("saved block context entity read lacks its exact readiness prefix");
        try {
            if (read.privateInput) {
                // Reproduce Level.getBlockEntity's prefix while selecting the retained preparation
                // at its private getChunkAt suffix. IMMEDIATE retains lazy creation, pending-tag
                // promotion, registration and their original exception point.
                if (!world.isInValidBounds(position)
                        || !world.isClientSide() && Thread.currentThread() != ((FullLevelThreadAccessMixin) world).phoenium$levelThread())
                    return null;
                FullSavedContextInputs.consumingBlockTag(target.actual(), position);
                return target.actual().getBlockEntity(position, LevelChunk.EntityCreationType.IMMEDIATE);
            }
            BlockEntity reached = original.get();
            invocation.requireCurrent();
            // Inspect only after the original published getter has performed any lazy promotion.
            // A null original result (bounds/thread/removed branch) stays null without a private read.
            if (reached != null && target.actual().getBlockEntities().get(position) != reached)
                throw breach("saved block context selected another block entity");
            return reached;
        } finally { invocation.blockRead = null; }
    }

    private static ChunkProtocolException breach(String message) {
        return ChunkSynchronousAccessGuard.breach(new ChunkProtocolException(message));
    }
    private static ChunkPos position(BlockPos pos) { return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4); }
    private static boolean biomePresent(FullInitializedInputs.Target target) {
        return target.holder() != null && target.ticketLevel() <= ChunkLevel.byStatus(ChunkStatus.BIOMES);
    }
    /** Vanilla may cache the original proto before FULL replaces holder slots with an imposter.
     * The BIOMES reader only consumes the shared biome containers; identity is checked for each
     * container and its coordinate interpretation, while the original cached wrapper is returned.
     */
    private static boolean sameBiomeInput(ChunkAccess expected, ChunkAccess original) {
        if (expected == original) return true;
        if (original == null || !expected.getPos().equals(original.getPos())
                || expected.getMinY() != original.getMinY() || expected.getHeight() != original.getHeight()) return false;
        var expectedSections = expected.getSections();
        var originalSections = original.getSections();
        if (expectedSections.length != originalSections.length) return false;
        for (int i = 0; i < expectedSections.length; i++)
            if (expectedSections[i].getBiomes() != originalSections[i].getBiomes()) return false;
        return true;
    }
}
