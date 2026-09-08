package dev.phoenium.chunk;

import java.lang.reflect.InvocationTargetException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.network.chat.contents.NbtContents;
import net.minecraft.network.chat.contents.ScoreContents;
import net.minecraft.network.chat.contents.SelectorContents;
import net.minecraft.network.chat.contents.data.EntityDataSource;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Test plumbing invokes the preserved original body exactly once through production guards. */
public final class FullSavedContextTestHooks {
    private static final ThreadLocal<Runnable> SELECTOR_PREFIX = new ThreadLocal<>();
    private static final ThreadLocal<Runnable> LIVE_BLOCK_PREFIX = new ThreadLocal<>();
    public static void liveBlockPrefix() {
        Runnable prefix = LIVE_BLOCK_PREFIX.get(); if (prefix != null) prefix.run();
    }
    static AutoCloseable liveBlockPrefix(Runnable prefix) {
        if (LIVE_BLOCK_PREFIX.get() != null) throw new AssertionError("nested live block prefix");
        LIVE_BLOCK_PREFIX.set(prefix); return () -> LIVE_BLOCK_PREFIX.remove();
    }
    /** Reads the vanilla private display payload in both transformed and installed probes. */
    public static Component text(Display.TextDisplay entity) {
        try {
            var method = Display.TextDisplay.class.getDeclaredMethod("getText");
            method.setAccessible(true);
            return (Component) method.invoke(entity);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            if (wrapped.getCause() instanceof Error error) throw error;
            throw new AssertionError(wrapped.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T original(Class<?> owner, Object receiver, String method, Class<?>[] parameters, Object... arguments) {
        try { return (T) owner.getMethod("phoenium$originalContext$" + method, parameters).invoke(receiver, arguments); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            if (wrapped.getCause() instanceof Error error) throw error;
            throw new AssertionError(wrapped.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    public static void textReader(Display.TextDisplay entity, ValueInput input) {
        FullSavedContextInputs.textReader(entity, input,
                () -> original(Display.TextDisplay.class, entity, "readAdditionalSaveData", new Class<?>[]{ValueInput.class}, input));
    }
    public static CompoundTag get(CommandStorage storage, Identifier id) {
        return FullCommandStorageInputs.get(storage, id,
                () -> original(CommandStorage.class, storage, "get", new Class<?>[]{Identifier.class}, id));
    }
    public static void set(CommandStorage storage, Identifier id, CompoundTag contents) {
        FullCommandStorageInputs.set(storage, id, contents,
                () -> original(CommandStorage.class, storage, "set", new Class<?>[]{Identifier.class, CompoundTag.class}, id, contents));
    }
    public static boolean blockLoaded(ServerLevel world, BlockPos position) {
        return FullSavedContextInputs.blockLoaded(world, position, () -> world.isLoaded(position));
    }
    public static BlockEntity blockEntity(ServerLevel world, BlockPos position) {
        return FullSavedContextInputs.blockEntity(world, position, () -> world.getBlockEntity(position));
    }
    private static MutableComponent originalResolve(Class<?> owner, Object receiver, ResolutionContext context, int depth)
            throws CommandSyntaxException {
        try {
            return (MutableComponent) owner.getMethod("phoenium$originalContext$resolve", ResolutionContext.class, int.class)
                    .invoke(receiver, context, depth);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof CommandSyntaxException syntax) throw syntax;
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            if (wrapped.getCause() instanceof Error error) throw error;
            throw new AssertionError(wrapped.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    public static MutableComponent selectorResolve(SelectorContents contents, ResolutionContext context, int depth)
            throws CommandSyntaxException {
        return FullSavedContextInputs.selectorName(contents.selector().source(),
                () -> {
                    Runnable prefix = SELECTOR_PREFIX.get();
                    if (prefix != null) prefix.run();
                    return originalResolve(SelectorContents.class, contents, context, depth);
                });
    }
    static AutoCloseable selectorPrefix(Runnable prefix) {
        if (SELECTOR_PREFIX.get() != null) throw new AssertionError("nested selector test prefix");
        SELECTOR_PREFIX.set(prefix);
        return () -> SELECTOR_PREFIX.remove();
    }
    public static MutableComponent scoreResolve(ScoreContents contents, ResolutionContext context, int depth)
            throws CommandSyntaxException {
        var selected = contents.name().left();
        return selected.isEmpty() ? originalResolve(ScoreContents.class, contents, context, depth)
                : FullSavedContextInputs.selectorScore(selected.get().source(),
                () -> originalResolve(ScoreContents.class, contents, context, depth));
    }
    public static MutableComponent nbtResolve(NbtContents contents, ResolutionContext context, int depth)
            throws CommandSyntaxException {
        return contents.dataSource() instanceof EntityDataSource entities
                ? FullSavedContextInputs.selectorNbt(entities.selector().source(),
                () -> originalResolve(NbtContents.class, contents, context, depth))
                : originalResolve(NbtContents.class, contents, context, depth);
    }
}
