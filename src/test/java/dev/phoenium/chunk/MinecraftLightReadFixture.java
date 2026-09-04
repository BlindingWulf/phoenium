package dev.phoenium.chunk;

import com.mojang.serialization.Lifecycle;
import dev.phoenium.ownership.OwnershipContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.AbstractConsecutiveExecutor;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the unmodified 26.2 read, POI consistency, retain/queue and dispatcher schedule methods.
 * Only world environment accessors and the final asynchronous executor are controlled. The
 * queue witness wraps the real StrictQueue.push, so a counter at method return cannot pass.
 * Constructorless allocation is confined to test environments, as in the metadata regression.
 */
final class MinecraftLightReadFixture extends LightProtocolFixture {
    final List<String> order = new ArrayList<>();
    final ArrayDeque<Runnable> processing = new ArrayDeque<>();
    final ChunkPos position = new ChunkPos(4, 0);
    final PalettedContainerFactory factory;
    final RegistryAccess.Frozen registries;
    final ServerLevel level;
    final PoiManager manager;
    final BoundEngine engine;
    SerializableChunkData data;
    ChunkStageClaim activeClaim;
    LightInputService.Insertion activeInsertion;
    LightInputService.Kind failKind;
    Throwable exactFailure;
    boolean throwAfterInsertion;
    boolean rejectDispatch;
    boolean readReturned;
    boolean malformedReadSuffix;
    Consumer<LightInputService.Kind> beforeCall = kind -> { };
    private int nextOccurrence;

    MinecraftLightReadFixture(boolean sky, boolean installedPoi) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, new Biome.BiomeBuilder().temperature(0.8F).downfall(0.4F)
                .specialEffects(new BiomeSpecialEffects.Builder().waterColor(0).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build(), RegistrationInfo.BUILT_IN);
        var structures = new MappedRegistry<Structure>(Registries.STRUCTURE, Lifecycle.stable());
        registries = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes, structures)).freeze();
        factory = PalettedContainerFactory.create(registries);
        var dimension = new DimensionType(false, sky, false, false, 1, 0, 48, 48,
                HolderSet.direct(), 0, null, null, null, null, HolderSet.direct(), Optional.empty());
        engine = allocate(BoundEngine.class); engine.fixture = this;
        var dispatcher = new ChunkTaskDispatcher(TaskScheduler.wrapExecutor("light-test", processing::add), command -> {
            if (rejectDispatch) {
                var rejection = new java.util.concurrent.RejectedExecutionException("fixture dispatch");
                activeInsertion.rejected(rejection);
                throw rejection;
            }
            processing.add(command);
        });
        set(engine, ThreadedLevelLightEngine.class, "taskDispatcher", dispatcher);
        var consecutive = (AbstractConsecutiveExecutor<?>) field(dispatcher, ChunkTaskDispatcher.class, "dispatcher");
        @SuppressWarnings("unchecked") var actualQueue = (StrictQueue<Runnable>) field(consecutive, AbstractConsecutiveExecutor.class, "queue");
        set(consecutive, AbstractConsecutiveExecutor.class, "queue", new StrictQueue<Runnable>() {
            @Override public Runnable pop() { return actualQueue.pop(); }
            @Override public boolean push(Runnable command) {
                assertNotNull(activeInsertion, "exact original ingress is bound at insertion");
                boolean pushed = actualQueue.push(command);
                if (pushed) activeInsertion.inserted();
                return pushed;
            }
            @Override public boolean isEmpty() { return actualQueue.isEmpty(); }
            @Override public int size() { return actualQueue.size(); }
        });
        ServerChunkCache cache = stub(ServerChunkCache.class, List.of(
                new Answer(ServerChunkCache.class.getMethod("getLightEngine"), engine)));
        MinecraftServer server = stub(MinecraftServer.class, List.of(
                new Answer(MinecraftServer.class.getMethod("getResourceManager"), null),
                new Answer(MinecraftServer.class.getMethod("registryAccess"), registries),
                new Answer(MinecraftServer.class.getMethod("getStructureManager"), null)));
        level = stub(ServerLevel.class, List.of(
                new Answer(ServerLevel.class.getMethod("dimensionType"), dimension),
                new Answer(ServerLevel.class.getMethod("getChunkSource"), cache),
                new Answer(ServerLevel.class.getMethod("palettedContainerFactory"), factory),
                new Answer(ServerLevel.class.getMethod("registryAccess"), registries),
                new Answer(ServerLevel.class.getMethod("getServer"), server),
                new Answer(ServerLevel.class.getMethod("getSeed"), 0L)));
        manager = allocate(PoiManager.class);
        var storage = new Long2ObjectOpenHashMap<Optional<PoiSection>>() {
            @Override public Optional<PoiSection> get(long section) {
                order.add("POI:" + SectionPos.y(section));
                assertTrue(containsKey(section), "actual installed/known-empty POI precedes real consistency");
                assertEquals(ChunkCustodyModel.StageState.CLAIMED, pending.state().orElseThrow());
                return super.get(section);
            }
        };
        set(manager, SectionStorage.class, "storage", storage);
        set(manager, SectionStorage.class, "levelHeightAccessor", level);
        // Exercise the real packed-storage known-empty installation before publishing the receipt.
        Method unpack = java.util.Arrays.stream(SectionStorage.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("unpackChunk") && m.getParameterCount() == 2).findFirst().orElseThrow();
        unpack.setAccessible(true); unpack.invoke(manager, position, null);
        if (installedPoi) {
            for (int y = 0; y < 3; y++) storage.put(SectionPos.of(position, y).asLong(), Optional.of(new PoiSection(() -> { })));
        }
        installPoi();
    }

    SerializableChunkData.SectionData section(int y, boolean blocks, boolean sky, boolean reconstruct) {
        return new SerializableChunkData.SectionData(y, reconstruct ? new LevelChunkSection(factory) : null,
                blocks ? new DataLayer() : null, sky ? new DataLayer() : null);
    }
    void prepare(List<SerializableChunkData.SectionData> sections) {
        data = new SerializableChunkData(factory, position, 0, 0, 0, ChunkStatus.EMPTY, null, null,
                UpgradeData.EMPTY, null, Map.of(), new ChunkAccess.PackedTicks(List.of(), List.of()),
                malformedReadSuffix ? null : new ShortList[3], true, sections, List.of(), List.of(), new CompoundTag());
        List<LightInputService.SectionInput> inputs = new ArrayList<>();
        long sequence = 0;
        for (var section : sections) {
            String key = Long.toString(SectionPos.of(position, section.y()).asLong());
            if (section.blockLight() != null) inputs.add(input(LightInputService.Layer.BLOCK, key, ++sequence));
            if (level.dimensionType().hasSkyLight() && section.skyLight() != null) inputs.add(input(LightInputService.Layer.SKY, key, ++sequence));
        }
        assertTrue(light.register(batch, !inputs.isEmpty(), inputs));
    }
    Object read(ChunkStageClaim claim) throws Throwable {
        activeClaim = claim;
        try {
            ProtoChunk result = data.read(level, manager, new RegionStorageInfo("lio", net.minecraft.world.level.Level.OVERWORLD, "chunk"), position);
            readReturned = true; order.add("READ_RETURN");
            assertTrue(light.receipt(batch).isEmpty(), "receipt cannot precede normal read completion");
            light.sealRead(claim, batch); order.add("SEALED");
            return result;
        } finally { activeClaim = null; activeInsertion = null; }
    }
    Object fresh(ChunkStageClaim claim) throws Throwable {
        claim.require(stage);
        var map = allocate(net.minecraft.server.level.ChunkMap.class);
        set(map, net.minecraft.server.level.ChunkMap.class, "level", level);
        var cache = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
        cache.put(position.pack(),(byte)1);
        set(map, net.minecraft.server.level.ChunkMap.class, "chunkTypeCache", cache);
        Method original = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("lambda$scheduleChunkLoad$3",ChunkPos.class,Optional.class);
        original.setAccessible(true);
        try {
            Object result = original.invoke(map,position,Optional.empty());
            assertEquals((byte)-1,cache.get(position.pack()),"original fresh marker preserves FULL to replaceable");
            return result;
        } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }
    private void physical(LightInputService.Kind kind, SectionPos section, DataLayer payload, LightInputService.PhysicalIngress body) {
        var id = occurrences().get(nextOccurrence);
        assertEquals(kind, id.kind());
        if (section != null) assertEquals(Long.toString(section.asLong()), id.sectionKey());
        if (payload != null) {
            // Every repeated input is checked by original encounter order, not a coordinate set.
            var expected = new ArrayList<DataLayer>();
            for (var entry : data.sectionData()) {
                if (entry.blockLight() != null) expected.add(entry.blockLight());
                if (level.dimensionType().hasSkyLight() && entry.skyLight() != null) expected.add(entry.skyLight());
            }
            assertSame(expected.get(nextOccurrence - 1), payload);
        }
        try {
            light.invoke(activeClaim, id, insertion -> {
                assertFalse(custody.gate().isHeldByCurrentThread());
                assertTrue(OwnershipContext.current().isPresent());
                assertEquals(LightInputService.State.INVOKING, light.snapshot(batch).orElseThrow().occurrences().get(nextOccurrence).state());
                beforeCall.accept(kind);
                activeInsertion = insertion; physical++; order.add(kind + (section == null ? "" : ":" + section.y()));
                if (kind == failKind && !throwAfterInsertion) throw exactFailure;
                body.submit(insertion);
                if (kind == failKind) throw exactFailure;
            });
            nextOccurrence++;
        } catch (Throwable failure) { MinecraftLightReadFixture.<RuntimeException>raise(failure); }
        finally { activeInsertion = null; }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> void raise(Throwable failure) throws E { throw (E) failure; }

    static final class BoundEngine extends ThreadedLevelLightEngine {
        MinecraftLightReadFixture fixture;
        private BoundEngine() { super(null, null, false, null, null); }
        @Override public void retainData(ChunkPos pos, boolean retain) {
            assertEquals(fixture.position, pos); assertTrue(retain);
            fixture.physical(LightInputService.Kind.RETAIN, null, null, insertion -> super.retainData(pos, retain));
        }
        @Override public void queueSectionData(LightLayer layer, SectionPos pos, DataLayer payload) {
            fixture.physical(layer == LightLayer.BLOCK ? LightInputService.Kind.BLOCK : LightInputService.Kind.SKY,
                    pos, payload, insertion -> super.queueSectionData(layer, pos, payload));
        }
    }

    private record Answer(Method method, Object value) { }
    private static int subclassSequence;
    /** Finite environment getter stubs; read, POI consistency and light bodies remain original. */
    private static <T> T stub(Class<T> parent, List<Answer> answers) throws Exception {
        String name = "dev/phoenium/chunk/ReadEnvironment" + ++subclassSequence;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, Type.getInternalName(parent), null);
        writer.visitField(Opcodes.ACC_PUBLIC, "answers", "[Ljava/lang/Object;", null, null).visitEnd();
        for (int i = 0; i < answers.size(); i++) {
            Method method = answers.get(i).method(); Class<?> result = method.getReturnType();
            var mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method.getName(), Type.getMethodDescriptor(method), null, null);
            mv.visitCode(); mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitFieldInsn(Opcodes.GETFIELD, name, "answers", "[Ljava/lang/Object;");
            mv.visitLdcInsn(i); mv.visitInsn(Opcodes.AALOAD);
            if (result == long.class) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long"); mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false); mv.visitInsn(Opcodes.LRETURN);
            } else { mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(result)); mv.visitInsn(Opcodes.ARETURN); }
            mv.visitMaxs(0, 0); mv.visitEnd();
        }
        writer.visitEnd();
        Class<?> type = new ClassLoader(parent.getClassLoader()) {
            Class<?> define() { return defineClass(name.replace('/', '.'), writer.toByteArray(), 0, writer.toByteArray().length); }
        }.define();
        T instance = parent.cast(allocate(type));
        type.getField("answers").set(instance, answers.stream().map(Answer::value).toArray());
        return instance;
    }
    static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }
    private static Object field(Object instance, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private static void set(Object instance, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
}
