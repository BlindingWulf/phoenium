package dev.phoenium.chunk;

import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.nbt.*;

/** Selected saved-input collection writers, including iterator/entry/list views, use the same
 * nonblocking observation turn. Existing tags and original readers keep their identities/order.
 * No gameplay collection, entity or chunk section is wrapped by this input-only mechanism.
 */
public final class FullSavedCollections {
    public interface CompoundAccess {
        Map<String, Tag> phoenium$savedMap();
        void phoenium$savedMap(Map<String, Tag> map);
    }
    public interface ListAccess {
        List<Tag> phoenium$savedList();
        void phoenium$savedList(List<Tag> list);
    }
    public static <T> List<T> roots(List<T> list, FullSavedInput source) {
        if (list instanceof InputList<?> prior && prior.source.get() == source) return list;
        return new InputList<>(list, source);
    }
    /** The reload factory's returned list is the custody handoff: keep its tracker alive even
     * while the original factory allocates its callback, before ReloadProcessor can retain it. */
    public static List<CompoundTag> retainedReloadRoots(List<CompoundTag> list, FullSavedInput source) {
        return new InputList<>(list, source, true);
    }
    static FullSavedInput source(List<?> list) { return list instanceof InputList<?> input ? input.source.get() : null; }
    static FullSavedInput source(CompoundTag tag) {
        return ((CompoundAccess) (Object) tag).phoenium$savedMap() instanceof InputMap input ? input.source.get() : null;
    }
    static void watch(CompoundTag tag, FullSavedInput source) {
        var access = (CompoundAccess) (Object) tag;
        var map = access.phoenium$savedMap();
        if (map instanceof InputMap prior && prior.source.get() == source) return;
        if (map instanceof InputMap prior && prior.source.get() != null)
            throw new ChunkProtocolException("shared mutable saved tag has another input custodian");
        access.phoenium$savedMap(new InputMap(map, source));
    }
    static void watch(ListTag tag, FullSavedInput source) {
        var access = (ListAccess) (Object) tag;
        var list = access.phoenium$savedList();
        var prior = source(list);
        if (prior != null && prior != source)
            throw new ChunkProtocolException("shared mutable saved list has another input custodian");
        access.phoenium$savedList(roots(list, source));
    }
    private static final class InputList<T> extends AbstractList<T> {
        final List<T> delegate;
        final WeakReference<FullSavedInput> source;
        private final FullSavedInput retainedSource;
        InputList(List<T> delegate, FullSavedInput source) { this(delegate, source, false); }
        InputList(List<T> delegate, FullSavedInput source, boolean retain) {
            this.delegate = delegate; this.source = new WeakReference<>(source);
            retainedSource = retain ? source : null;
        }
        private <R> R mutate(java.util.function.Supplier<R> action) {
            var current = source.get(); return current == null ? action.get() : current.mutate(action);
        }
        public int size() { return delegate.size(); }
        public T get(int index) { return delegate.get(index); }
        public T set(int index, T value) { return mutate(() -> delegate.set(index, value)); }
        public void add(int index, T value) { mutate(() -> { delegate.add(index, value); return null; }); }
        public T remove(int index) { return mutate(() -> delegate.remove(index)); }
        public void clear() { mutate(() -> { delegate.clear(); return null; }); }
    }
    private static final class InputMap extends AbstractMap<String, Tag> {
        final Map<String, Tag> delegate;
        final WeakReference<FullSavedInput> source;
        InputMap(Map<String, Tag> delegate, FullSavedInput source) { this.delegate = delegate; this.source = new WeakReference<>(source); }
        private <R> R mutate(java.util.function.Supplier<R> action) {
            var current = source.get(); return current == null ? action.get() : current.mutate(action);
        }
        public Tag get(Object key) { return delegate.get(key); }
        public boolean containsKey(Object key) { return delegate.containsKey(key); }
        public int size() { return delegate.size(); }
        public Tag put(String key, Tag value) { return mutate(() -> delegate.put(key, value)); }
        public Tag remove(Object key) { return mutate(() -> delegate.remove(key)); }
        public void clear() { mutate(() -> { delegate.clear(); return null; }); }
        public Set<Entry<String, Tag>> entrySet() {
            return new AbstractSet<>() {
                public int size() { return delegate.size(); }
                public Iterator<Entry<String, Tag>> iterator() {
                    var iterator = delegate.entrySet().iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Entry<String, Tag> next() {
                            var entry = iterator.next();
                            return new SimpleEntry<>(entry) {
                                public Tag setValue(Tag value) {
                                    Tag old = mutate(() -> entry.setValue(value)); super.setValue(value); return old;
                                }
                            };
                        }
                        public void remove() { mutate(() -> { iterator.remove(); return null; }); }
                    };
                }
            };
        }
    }
}
