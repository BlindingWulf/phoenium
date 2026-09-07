package dev.phoenium.chunk;

import java.nio.charset.StandardCharsets;
import java.util.*;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/** Disposable real-world fixture. Only the saved inputs are injected; every execution endpoint is vanilla. */
public final class FullRuntimeAcceptanceProbe implements DedicatedServerModInitializer {
    private static final Set<Long> CHUNKS = Set.of(new ChunkPos(32, 4).pack(), new ChunkPos(33, 4).pack(),
            new ChunkPos(34, 4).pack(), new ChunkPos(35, 4).pack());
    private static final Map<Long, String> SUFFIXES = new LinkedHashMap<>();
    private static final Map<String, Integer> READERS = new LinkedHashMap<>();
    private static final BlockPos SAVED_BED = new BlockPos(2032, 75, -1104);
    private static final Set<UUID> BED_CHECKS = new HashSet<>();
    private static final Set<BlockPos> VAULT_POSITIONS = new HashSet<>();
    private static int vaultTicks, vaultNeighborReads;
    private static int richVillagers, privateNeighbors;
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
                    String result = verify(context.getSource().getLevel());
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
        // Odd chunks present a genuine nonair immediate block-entity shape to the even source.
        if ((access.getPos().x() & 1) != 0) {
            var pos = new BlockPos(access.getPos().getMinBlockX(), 200, access.getPos().getMinBlockZ() + 8);
            proto.setBlockState(pos, Blocks.SHULKER_BOX.defaultBlockState(), 0);
        }
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

    /** Test-only witness that the original LivingEntity bed lookup consumed the saved position. */
    public static void recordBedCheck(LivingEntity entity, BlockPos position, Boolean result) {
        if (!entity.entityTags().contains(marker(phase())) || !SAVED_BED.equals(position)) return;
        BED_CHECKS.add(entity.getUUID());
        System.out.println("PHOENIUM_FULL_BED_CHECK phase=" + phase() + " entity=" + entity.getUUID()
                + " position=" + position + " result=" + result + " distinct=" + BED_CHECKS.size());
    }

    public static void recordVaultTick(ServerLevel level, BlockPos position) {
        if (!Boolean.getBoolean("phoenium.test.vaultDiagnostic")) return;
        vaultTicks++;
        VAULT_POSITIONS.add(position);
        if (vaultTicks <= 5 || vaultTicks % 100 == 0)
            System.out.println("PHOENIUM_VAULT_TICK position=" + position + " ticks=" + vaultTicks);
    }

    public static void recordVaultNeighbor(BlockPos position) {
        if (!Boolean.getBoolean("phoenium.test.vaultDiagnostic") || !VAULT_POSITIONS.contains(position)) return;
        vaultNeighborReads++;
        if (vaultNeighborReads <= 5 || vaultNeighborReads % 100 == 0)
            System.out.println("PHOENIUM_VAULT_NEIGHBOR position=" + position + " reads=" + vaultNeighborReads);
    }

    private static void registerVaultDiagnosticCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
                Commands.literal("phoenium").then(Commands.literal("vaultprobe").executes(context -> {
                    String result = "PHOENIUM_VAULT_DIAGNOSTICS ticks=" + vaultTicks
                            + " neighborReads=" + vaultNeighborReads + " positions=" + VAULT_POSITIONS.size();
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
        int frogs = 0, villagers = 0;
        for (Entity entity : level.getAllEntities()) if (entity.entityTags().contains(marker(phase()))) {
            require(entity.getTeam() != null && entity.getTeam().getName().equals("full_probe"), "original saved team lost");
            if (entity instanceof Frog) frogs++; else if (entity instanceof Villager) villagers++;
            else throw new IllegalStateException("unexpected surviving saved root " + entity);
        }
        require(frogs == 4 && villagers == 4, "real entity inventory differs: frogs=" + frogs + " villagers=" + villagers);
        require(BED_CHECKS.size() == 4, "saved bed check witness missing: " + BED_CHECKS.size());
        require(!dev.phoenium.Phoenium.liveRuntime().diagnostics().toLogLine().contains("barriers=1"), "live scheduler barrier");
        return "FULL_LIVE_OK phase=" + phase() + " sources=4 savedReaders=8 richVillagers=4 frogs=4 teams=true"
                + " separatePublication=true bedChecks=" + BED_CHECKS.size() + " privateNeighbors=" + privateNeighbors + " origin="
                + (phase().equals("fresh") ? "ADMITTED_PROTO" : "ADMITTED_IMPOSTER");
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
