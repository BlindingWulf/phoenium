package dev.phoenium.chunk;

import java.lang.reflect.InvocationTargetException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.ValueInput;

/** Test plumbing invokes the preserved original body exactly once through production guards. */
public final class FullSavedContextTestHooks {
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
}
