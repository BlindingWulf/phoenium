package dev.phoenium.chunk;

import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.phoenium.ownership.OwnershipContext;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Disposable real-world fixture. Only the saved inputs are injected; every execution endpoint is vanilla. */
public final class FullRuntimeAcceptanceProbe implements DedicatedServerModInitializer {
    private static final Set<Long> CHUNKS = Set.of(new ChunkPos(32, 4).pack(), new ChunkPos(33, 4).pack(),
            new ChunkPos(34, 4).pack(), new ChunkPos(35, 4).pack());
    private static final Map<Long, String> SUFFIXES = new LinkedHashMap<>();
    private static final Map<String, Integer> READERS = new LinkedHashMap<>();
    private static final BlockPos SAVED_BED = new BlockPos(2032, 75, -1104);
    private static final Set<UUID> BED_CHECKS = new HashSet<>();
    private static final Set<BlockPos> SHULKER_POSITIONS = Set.of(new BlockPos(527, 200, 72), new BlockPos(528, 200, 72),
            new BlockPos(559, 200, 72), new BlockPos(560, 200, 72));
    private static int collisionQueries, immediateShulkerReads;
    private static final ThreadLocal<Entity> SAVED_READER = new ThreadLocal<>();
    private static final ThreadLocal<CollisionScope> COLLISION_SCOPE = new ThreadLocal<>();
    private static final BlockPos SAVED_VAULT = new BlockPos(143, -31, 28);
    private static int vaultTicks, vaultDirtyAttempts, vaultDirtyCompleted;
    private static int vaultNeighborReads, vaultCrossChunkReads, vaultOwnerScopes, vaultOrdinaryScopes;
    private static final ThreadLocal<VaultDirtyScope> VAULT_SCOPE = new ThreadLocal<>();
    private static int richVillagers, privateNeighbors;
    private static int scoreboardAdds, scoreboardRemoves;
    public static String phase() { return System.getProperty("phoenium.test.fullFixturePhase", ""); }
    private static boolean target(ChunkPos pos) { return CHUNKS.contains(pos.pack()); }
    private static String marker(String phase) { return "phoenium_full_" + phase; }

    @Override public void onInitializeServer() {
        boolean vaultDiagnostic = Boolean.getBoolean("phoenium.test.vaultDiagnostic");
        if (vaultDiagnostic) registerVaultDiagnosticCommand();
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty()) {
            if (vaultDiagnostic) return;
            throw new IllegalStateException("FULL acceptance fixture requires explicitly selected authoritative runtime");
        }
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (level.dimension() != Level.OVERWORLD || !target(chunk.getPos())) return;
            require(FullFinishingServices.finishing(), "actual Fabric load event escaped admitted FULL finishing");
            var runtime = dev.phoenium.Phoenium.liveRuntime().fullRegistration(level);
            var holder = ((FullInputView) level.getChunkSource().chunkMap).phoenium$visibleInput(chunk.getPos().pack());
            var graph = runtime.graph(holder);
            require(graph.prepared().orElseThrow().actual() == chunk, "Fabric callback changed actual prepared identity");
            var slot = ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex());
            require(slot == null || !slot.isDone(), "public FULL completed before original suffix returned");
            long peers = graph.inputReceipt().targets().stream().filter(t -> !t.position().equals(chunk.getPos())
                    && t.availability() == FullInitializedInputs.Availability.PREPARED).count();
            privateNeighbors += (int) peers;
            require(SUFFIXES.putIfAbsent(chunk.getPos().pack(), graph.work.toString()) == null, "duplicate source suffix");
            System.out.println("PHOENIUM_FULL_LIVE_SUFFIX phase=" + phase() + " chunk=" + chunk.getPos()
                    + " privateNeighbors=" + peers + " publicPending=true admitted=true");
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
                Commands.literal("fullprobe").executes(context -> {
                    String result;
                    try {
                        result = verify(context.getSource().getLevel());
                    } catch (Throwable failure) {
                        System.out.println("PHOENIUM_FULL_LIVE_VERIFY_FAILURE type="
                                + failure.getClass().getName() + " message=" + failure.getMessage());
                        failure.printStackTrace(System.out);
                        throw failure;
                    }
                    context.getSource().sendSuccess(() -> Component.literal(result), false); return 1;
                })));
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
                Commands.literal("fullbedready").executes(context -> {
                    var chunk = context.getSource().getLevel().getChunkSource().getChunkNow(127, -69);
                    String result = chunk == null
                            ? "FULL_BED_PENDING chunk=[127, -69]"
                            : "FULL_BED_READY chunk=[127, -69]";
                    context.getSource().sendSuccess(() -> Component.literal(result), false); return chunk == null ? 0 : 1;
                })));
    }

    public static void generated(ServerLevel level, ChunkAccess access) {
        if (!phase().equals("fresh") || level.dimension() != Level.OVERWORLD || !target(access.getPos())) return;
        require(access instanceof ProtoChunk && !(access instanceof ImposterProtoChunk), "fresh fixture is not real generation");
        var proto = (ProtoChunk) access;
        for (var tag : roots(access.getPos(), "fresh")) proto.addEntity(tag);
        // This is a generation fixture write, before the real SPAWN writer releases.
        // Both sides present a nonair shape to their peer, whichever original supplier finishes
        // first. No test hook dictates finishing/publication order. These real blocks persist.
        int borderX = access.getPos().getMinBlockX() + ((access.getPos().x() & 1) == 0 ? 15 : 0);
        var pos = new BlockPos(borderX, 200, access.getPos().getMinBlockZ() + 8);
        proto.setBlockState(pos, Blocks.SHULKER_BOX.defaultBlockState(), 0);
        System.out.println("PHOENIUM_FULL_LIVE_GENERATED chunk=" + access.getPos() + " roots=2");
    }

    public static SerializableChunkData persisted(SerializableChunkData data, ServerLevel level) {
        if (!Boolean.getBoolean("phoenium.test.fullFixtureSeedDisk") || level.dimension() != Level.OVERWORLD
                || !target(data.chunkPos()) || data.chunkStatus() != ChunkStatus.FULL) return data;
        var entities = new ArrayList<>(data.entities());
        entities.addAll(roots(data.chunkPos(), "reload"));
        return new SerializableChunkData(data.containerFactory(), data.chunkPos(), data.minSectionY(), data.lastUpdateTime(),
                data.inhabitedTime(), data.chunkStatus(), data.blendingData(), data.belowZeroRetrogen(), data.upgradeData(),
                data.carvingMask(), data.heightmaps(), data.packedTicks(), data.postProcessingSections(), data.lightCorrect(),
                data.sectionData(), List.copyOf(entities), data.blockEntities(), data.structureData());
    }

    private static List<CompoundTag> roots(ChunkPos pos, String phase) {
        double x = pos.getMinBlockX() + ((pos.x() & 1) == 0 ? 15.8 : 0.2);
        double z = pos.getMinBlockZ() + 8.0;
        var tadpole = FullInitializedInputBindingTest.tag(x, z, 24000);
        var villager = FullSavedVillagerInputTest.richVillager(pos.getMinBlockX() + 6.0, z, -200);
        List<CompoundTag> roots = List.of(tadpole, villager);
        for (int i = 0; i < roots.size(); i++) {
            var tag = roots.get(i);
            tag.getListOrEmpty("Pos").set(1, DoubleTag.valueOf(200));
            tag.putBoolean("NoAI", true); tag.putBoolean("NoGravity", true); tag.putBoolean("Invulnerable", true);
            tag.putBoolean("PersistenceRequired", true); tag.putString("Team", "full_probe");
            var tags = new ListTag(); tags.add(StringTag.valueOf(marker(phase)));
            tags.add(StringTag.valueOf(marker(phase) + "_" + pos.x() + "_" + i)); tag.put("Tags", tags);
            tag.store("UUID", UUIDUtil.CODEC, UUID.nameUUIDFromBytes((phase + ":" + pos + ":" + i).getBytes(StandardCharsets.UTF_8)));
        }
        return roots;
    }

    /** Called after the original reader returns, on its newly constructed object. Never used by discovery. */
    public static void loaded(Entity entity) {
        if (!entity.entityTags().contains(marker(phase()))) return;
        require(FullFinishingServices.finishing(), "fixture saved reader did not execute inside authoritative FULL");
        String id = entity.entityTags().stream().filter(s -> s.startsWith(marker(phase()) + "_")).findFirst().orElseThrow();
        require(READERS.merge(id, 1, Integer::sum) == 1, "saved reader replayed: " + id);
        PlayerTeam team = entity.getTeam();
        System.out.println("PHOENIUM_FULL_ENTITY_LOAD_RETURN phase=" + phase() + " id=" + id
                + " entity=" + entity.getUUID() + " type=" + entity.getType() + " team="
                + (team == null ? "<none>" : team.getName()) + " teamIdentity="
                + (team == null ? 0 : System.identityHashCode(team)) + " tags=" + entity.entityTags()
                + " sourceReader=" + (SAVED_READER.get() == null ? "<none>" : SAVED_READER.get().getUUID()));
        if (entity instanceof Villager villager) {
            require(SAVED_BED.equals(villager.getSleepingPos().orElse(null)),
                    "saved villager sleeping position lost: " + villager.getSleepingPos());
            require(villager.getVillagerXp() == 81 && villager.getVillagerData().level() == 3,
                    "saved villager experience/profession state lost");
            require(villager.getAttributeValue(Attributes.SCALE) == 3.0 && villager.getOffers().size() == 1
                    && villager.getInventory().getItem(0).getCount() == 7, "rich saved villager fields lost");
            richVillagers++;
        }
    }

    public static void scoreboardAddBefore(Scoreboard scoreboard, String member, PlayerTeam team) {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty()) return;
        scoreboardAdds++;
        System.out.println("PHOENIUM_FULL_SCOREBOARD_ADD phase=" + phase() + " member=" + member
                + " team=" + team.getName() + " teamIdentity=" + System.identityHashCode(team)
                + " result=before finishing=" + FullFinishingServices.finishing() + " sourceReader="
                + (SAVED_READER.get() == null ? "<none>" : SAVED_READER.get().getUUID()));
    }

    public static void scoreboardAddReturn(Scoreboard scoreboard, String member, PlayerTeam team, boolean result) {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty()) return;
        System.out.println("PHOENIUM_FULL_SCOREBOARD_ADD_RETURN phase=" + phase() + " member=" + member
                + " team=" + team.getName() + " teamIdentity=" + System.identityHashCode(team)
                + " result=" + result + " finishing=" + FullFinishingServices.finishing() + " sourceReader="
                + (SAVED_READER.get() == null ? "<none>" : SAVED_READER.get().getUUID()));
    }

    public static void scoreboardRemoveBefore(Scoreboard scoreboard, String member, PlayerTeam team) {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty()) return;
        scoreboardRemoves++;
        System.out.println("PHOENIUM_FULL_SCOREBOARD_REMOVE phase=" + phase() + " member=" + member
                + " team=" + team.getName() + " teamIdentity=" + System.identityHashCode(team)
                + " result=before finishing=" + FullFinishingServices.finishing() + " sourceReader="
                + (SAVED_READER.get() == null ? "<none>" : SAVED_READER.get().getUUID()));
    }

    public static void scoreboardRemoveReturn(Scoreboard scoreboard, String member, PlayerTeam team) {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty()) return;
        System.out.println("PHOENIUM_FULL_SCOREBOARD_REMOVE_RETURN phase=" + phase() + " member=" + member
                + " team=" + team.getName() + " teamIdentity=" + System.identityHashCode(team)
                + " result=completed finishing=" + FullFinishingServices.finishing() + " sourceReader="
                + (SAVED_READER.get() == null ? "<none>" : SAVED_READER.get().getUUID()));
    }

    public static void scoreboardRemoveSingleReturn(Scoreboard scoreboard, String member, boolean result) {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty()) return;
        System.out.println("PHOENIUM_FULL_SCOREBOARD_REMOVE_SINGLE_RETURN phase=" + phase() + " member=" + member
                + " result=" + result + " finishing=" + FullFinishingServices.finishing() + " sourceReader="
                + (SAVED_READER.get() == null ? "<none>" : SAVED_READER.get().getUUID()));
    }

    /** Test-only witness that the original LivingEntity bed lookup consumed the saved position. */
    public static void recordBedCheck(LivingEntity entity, BlockPos position, Boolean result) {
        if (!entity.entityTags().contains(marker(phase())) || !SAVED_BED.equals(position)) return;
        BED_CHECKS.add(entity.getUUID());
        System.out.println("PHOENIUM_FULL_BED_CHECK phase=" + phase() + " entity=" + entity.getUUID()
                + " position=" + position + " result=" + result + " distinct=" + BED_CHECKS.size());
    }

    public static void recordVaultTick(ServerLevel level, BlockPos position) {
        if (!Boolean.getBoolean("phoenium.test.vaultDiagnostic")) return;
        if (!SAVED_VAULT.equals(position)) return;
        vaultTicks++;
        if (vaultTicks <= 5 || vaultTicks % 100 == 0)
            System.out.println("PHOENIUM_VAULT_TICK world=" + level.dimension()
                    + " position=" + position + " tick=" + level.getGameTime() + " ticks=" + vaultTicks);
    }

    public static void savedReader(Entity entity, Runnable original) {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty()
                || !FullFinishingServices.finishing()) { original.run(); return; }
        Entity parent = SAVED_READER.get();
        SAVED_READER.set(entity);
        try { original.run(); }
        finally { if (parent == null) SAVED_READER.remove(); else SAVED_READER.set(parent); }
    }

    /**
     * Test-only wrapper around the original BlockCollisions shape call.  A collision query is
     * counted only after the original shape call consumed the retained private result and the
     * original IMMEDIATE block-entity lookup observed the saved Shulker.  Ordinary server ticks
     * at the same coordinates are deliberately ignored.
     */
    public static VoxelShape collisionShape(BlockState state, BlockGetter world, BlockPos position,
                                             java.util.function.Supplier<VoxelShape> original) {
        CollisionScope scope = beginCollisionScope(state, world, position);
        if (scope == null) return original.get();
        CollisionScope parent = COLLISION_SCOPE.get();
        COLLISION_SCOPE.set(scope);
        try {
            VoxelShape result = original.get();
            if (scope.privateReturned && scope.immediateSeen) {
                collisionQueries++;
                System.out.println("PHOENIUM_FULL_COLLISION_QUERY phase=" + phase() + " entity=" + scope.entity
                        + " source=" + scope.source + " target=" + scope.target
                        + " status=FULL access=IMMEDIATE owner=true finishing=true privateResult=true"
                        + " publicPending=true completed=true count=" + collisionQueries);
            }
            return result;
        } finally {
            if (parent == null) COLLISION_SCOPE.remove(); else COLLISION_SCOPE.set(parent);
        }
    }

    private static CollisionScope beginCollisionScope(BlockState state, BlockGetter world, BlockPos position) {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty()
                || !(world instanceof ServerLevel level) || !(state.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)
                || !SHULKER_POSITIONS.contains(position) || !FullFinishingServices.finishing()
                || level.dimension() != Level.OVERWORLD) return null;
        Entity reader = SAVED_READER.get();
        if (!(reader instanceof net.minecraft.world.entity.animal.frog.Tadpole)
                || reader.level() != level || !reader.entityTags().contains(marker(phase()))) return null;
        var reservation = FullFinishingServices.currentReservation(level.getServer());
        if (reservation == null || reservation.stage().kind() != ChunkStageKind.FULL_FINISH
                || OwnershipContext.current().isEmpty() || !reservation.owns(reader))
            throw new IllegalStateException("saved Shulker collision has no active FULL_FINISH reservation");
        ChunkPos source = new ChunkPos(reservation.stage().work().lifecycle().chunkKey().x(),
                reservation.stage().work().lifecycle().chunkKey().z());
        ChunkPos target = new ChunkPos(position.getX() >> 4, position.getZ() >> 4);
        if (!CHUNKS.contains(source.pack()) || !CHUNKS.contains(target.pack()) || source.equals(target))
            return null;
        var view = (FullInputView) level.getChunkSource().chunkMap;
        var sourceHolder = view.phoenium$visibleInput(source.pack());
        var targetHolder = view.phoenium$visibleInput(target.pack());
        var runtime = dev.phoenium.Phoenium.liveRuntime().fullRegistration(level);
        var sourceGraph = runtime.findGraph(sourceHolder).orElseThrow(
                () -> new IllegalStateException("saved Shulker collision source graph missing: " + source));
        require(sourceGraph.prepared().isPresent(), "saved Shulker source graph lost PREPARED state");
        require(sourceGraph.finishing.stage().equals(reservation.stage()), "saved Shulker source stage changed");
        require(sourceGraph.inputReceipt() != null, "saved Shulker source receipt missing");
        var retained = sourceGraph.inputReceipt().targets().stream().filter(input -> input.position().equals(target))
                .findFirst().orElseThrow(() -> new IllegalStateException("saved Shulker target receipt missing"));
        // A later source may legitimately consume a now-published peer. It is not private proof.
        if (retained.availability() != FullInitializedInputs.Availability.PREPARED
                || retained.publicSlot() == null || retained.publicSlot().isDone()) return null;
        require(retained.purposes().contains(FullInitializedInputs.Purpose.COLLISION)
                && retained.purposes().contains(FullInitializedInputs.Purpose.DYNAMIC_SHAPE_MUTATION),
                "saved Shulker target lacks exact collision/IMMEDIATE purposes");
        require(retained.holder() == targetHolder && retained.actual() != null
                && retained.actual().getBlockState(position) == state,
                "saved Shulker collision target identity changed");
        require(((FullHolderEvidence) targetHolder).phoenium$futures().get(ChunkStatus.FULL.getIndex()) == retained.publicSlot(),
                "saved Shulker target public slot changed");
        return new CollisionScope(source, target, position, reader.getUUID(), retained);
    }

    /** Observe the private result while its exact query is still active, before getChunkAt returns. */
    public static void privateQueryReturned(java.util.concurrent.CompletableFuture<?> selected,
                                             net.minecraft.server.level.ChunkResult<ChunkAccess> result) {
        CollisionScope scope = COLLISION_SCOPE.get();
        if (scope == null || result == null) return;
        require(FullFinishingQueries.usesPrivateResult() && selected == scope.retained.publicSlot()
                && result.orElse(null) == scope.retained.actual() && !selected.isDone(),
                "saved Shulker private query did not consume its exact pending input");
        scope.privateReturned = true;
    }

    public static void recordImmediateShulker(LevelChunk chunk, BlockPos position,
                                               LevelChunk.EntityCreationType creation, BlockEntity result) {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty()
                || creation != LevelChunk.EntityCreationType.IMMEDIATE || !SHULKER_POSITIONS.contains(position)
                || !(result instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)) return;
        CollisionScope scope = COLLISION_SCOPE.get();
        if (scope == null || !scope.target.equals(chunk.getPos()) || !scope.position.equals(position)
                || !scope.privateReturned || chunk != scope.retained.actual() || OwnershipContext.current().isEmpty()
                || !FullFinishingServices.finishing()) return;
        scope.immediateSeen = true;
        immediateShulkerReads++;
        System.out.println("PHOENIUM_FULL_SHULKER_IMMEDIATE phase=" + phase() + " chunk=" + chunk.getPos()
                + " position=" + position + " owner=true finishing=true privateResult=true count=" + immediateShulkerReads);
    }

    public static void beginVaultDirtyOutput(ServerLevel level, BlockPos position, BlockState state) {
        if (!Boolean.getBoolean("phoenium.test.vaultDiagnostic") || !SAVED_VAULT.equals(position)) return;
        if (VAULT_SCOPE.get() != null) throw new IllegalStateException("nested saved vault dirty-output scope");
        boolean owner = OwnershipContext.current().isPresent();
        boolean finishing = FullFinishingServices.finishing();
        if (!owner || finishing) throw new IllegalStateException("saved vault ordinary tick context owner="
                + owner + " finishing=" + finishing);
        vaultDirtyAttempts++;
        vaultOwnerScopes++;
        vaultOrdinaryScopes++;
        VaultDirtyScope scope = new VaultDirtyScope(level, position, level.getGameTime(), state);
        VAULT_SCOPE.set(scope);
        System.out.println("PHOENIUM_VAULT_DIRTY_BEGIN world=" + level.dimension()
                + " position=" + position + " tick=" + scope.tick + " owner=true finishing=false");
    }

    public static void recordVaultNeighborRead(ServerLevel level, BlockPos position) {
        VaultDirtyScope scope = VAULT_SCOPE.get();
        if (scope == null || scope.level != level) return;
        vaultNeighborReads++;
        if (ChunkPos.containing(position).equals(ChunkPos.containing(scope.position))) return;
        vaultCrossChunkReads++;
        if (vaultNeighborReads <= 5 || vaultNeighborReads % 100 == 0)
            System.out.println("PHOENIUM_VAULT_NEIGHBOR world=" + level.dimension()
                    + " source=" + scope.position + " neighbor=" + position + " tick=" + scope.tick
                    + " reads=" + vaultNeighborReads + " crossChunk=" + vaultCrossChunkReads);
    }

    public static void completeVaultDirtyOutput(ServerLevel level, BlockPos position) {
        VaultDirtyScope scope = VAULT_SCOPE.get();
        if (scope == null || scope.level != level || !scope.position.equals(position)) return;
        vaultDirtyCompleted++;
        System.out.println("PHOENIUM_VAULT_DIRTY_COMPLETE world=" + level.dimension()
                + " position=" + position + " tick=" + scope.tick + " completed=" + vaultDirtyCompleted);
    }

    public static void endVaultDirtyOutput() {
        VAULT_SCOPE.remove();
    }

    private static void registerVaultDiagnosticCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
                Commands.literal("phoenium").then(Commands.literal("vaultprobe").executes(context -> {
                    String result = "PHOENIUM_VAULT_DIAGNOSTICS ticks=" + vaultTicks
                            + " dirtyAttempts=" + vaultDirtyAttempts + " dirtyCompleted=" + vaultDirtyCompleted
                            + " neighborReads=" + vaultNeighborReads + " crossChunkReads=" + vaultCrossChunkReads
                            + " ownerScopes=" + vaultOwnerScopes + " ordinaryScopes=" + vaultOrdinaryScopes
                            + " scopeCleared=" + (VAULT_SCOPE.get() == null);
                    context.getSource().sendSuccess(() -> Component.literal(result), false); return 1;
                }))));
    }

    private static String verify(ServerLevel level) {
        if (SUFFIXES.size() != CHUNKS.size()) return "FULL_LIVE_PENDING phase=" + phase() + " suffixes=" + SUFFIXES.size();
        require(READERS.size() == 8 && richVillagers == 4, "saved reader completion/state missing: " + READERS);
        var runtime = dev.phoenium.Phoenium.liveRuntime().fullRegistration(level);
        for (long packed : CHUNKS) {
            var holder = ((FullInputView) level.getChunkSource().chunkMap).phoenium$visibleInput(packed);
            var actual = holder.getChunkIfPresent(ChunkStatus.FULL);
            if (!(actual instanceof LevelChunk)) return "FULL_LIVE_PENDING phase=" + phase() + " publication=" + packed;
            var publication = runtime.published.observe(holder);
            require(publication != null && publication.actual() == actual, "actual public FULL has no admitted provenance");
            require(publication.origin() == (phase().equals("fresh") ? FullPublishedInputs.Origin.ADMITTED_PROTO
                    : FullPublishedInputs.Origin.ADMITTED_IMPOSTER), "wrong real generation/reload origin");
        }
        for (long packed : CHUNKS) {
            ChunkPos position = new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed));
            if (!level.areEntitiesActuallyLoadedAndTicking(position))
                return "FULL_LIVE_PENDING phase=" + phase() + " entityReadiness=" + position;
        }
        int frogs = 0, villagers = 0;
        for (Entity entity : level.getAllEntities()) if (entity.entityTags().contains(marker(phase()))) {
            require(entity.getTeam() != null && entity.getTeam().getName().equals("full_probe"),
                    "original saved team lost entity=" + entity.getUUID() + " type=" + entity.getType()
                            + " position=" + entity.blockPosition() + " tags=" + entity.entityTags()
                            + " team=" + (entity.getTeam() == null ? "<none>" : entity.getTeam().getName()));
            if (entity instanceof Frog) frogs++; else if (entity instanceof Villager) villagers++;
            else throw new IllegalStateException("unexpected surviving saved root " + entity);
        }
        require(frogs == 4 && villagers == 4, "real entity inventory differs: frogs=" + frogs + " villagers=" + villagers);
        if (BED_CHECKS.size() < 4)
            return "FULL_LIVE_PENDING phase=" + phase() + " bedChecks=" + BED_CHECKS.size();
        require(BED_CHECKS.size() == 4, "saved bed check witness missing: " + BED_CHECKS.size());
        require(collisionQueries > 0, "original FULL collision query witness missing");
        require(immediateShulkerReads > 0, "original ShulkerBox IMMEDIATE witness missing");
        require(!dev.phoenium.Phoenium.liveRuntime().diagnostics().toLogLine().contains("barriers=1"), "live scheduler barrier");
        return "FULL_LIVE_OK phase=" + phase() + " sources=4 savedReaders=8 richVillagers=4 frogs=4 teams=true"
                + " separatePublication=true bedChecks=" + BED_CHECKS.size() + " collisionQueries=" + collisionQueries
                + " immediateShulkerReads=" + immediateShulkerReads + " privateNeighbors=" + privateNeighbors + " origin="
                + (phase().equals("fresh") ? "ADMITTED_PROTO" : "ADMITTED_IMPOSTER");
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
    private record VaultDirtyScope(ServerLevel level, BlockPos position, long tick, BlockState state) { }
    private static final class CollisionScope {
        final ChunkPos source, target;
        final BlockPos position;
        final UUID entity;
        final FullInitializedInputs.Target retained;
        boolean privateReturned, immediateSeen;
        CollisionScope(ChunkPos source, ChunkPos target, BlockPos position, UUID entity, FullInitializedInputs.Target retained) {
            this.source = source; this.target = target; this.position = position.immutable();
            this.entity = entity; this.retained = retained;
        }
    }
}
