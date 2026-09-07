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
    private static int richVillagers, privateNeighbors;
    public static String phase() { return System.getProperty("phoenium.test.fullFixturePhase", ""); }
    private static boolean target(ChunkPos pos) { return CHUNKS.contains(pos.pack()); }
    private static String marker(String phase) { return "phoenium_full_" + phase; }

    @Override public void onInitializeServer() {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || phase().isEmpty())
            throw new IllegalStateException("FULL acceptance fixture requires explicitly selected authoritative runtime");
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
            require(villager.getVillagerXp() == 81 && villager.getVillagerData().level() == 3,
                    "saved villager experience/profession state lost");
            require(villager.getAttributeValue(Attributes.SCALE) == 3.0 && villager.getOffers().size() == 1
                    && villager.getInventory().getItem(0).getCount() == 7, "rich saved villager fields lost");
            richVillagers++;
        }
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
        require(!dev.phoenium.Phoenium.liveRuntime().diagnostics().toLogLine().contains("barriers=1"), "live scheduler barrier");
        return "FULL_LIVE_OK phase=" + phase() + " sources=4 savedReaders=8 richVillagers=4 frogs=4 teams=true"
                + " separatePublication=true privateNeighbors=" + privateNeighbors + " origin="
                + (phase().equals("fresh") ? "ADMITTED_PROTO" : "ADMITTED_IMPOSTER");
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
