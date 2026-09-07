package dev.phoenium.chunk;

import java.lang.reflect.InvocationTargetException;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Test plumbing invokes the preserved original body exactly once through production guards. */
public final class FullSavedContextTestHooks {
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
}
