package dev.phoenium.transfer;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** A root and every reachable passenger must cross the ownership boundary together. */
public record TransferGroup(String root, Map<String, Set<String>> passengers) {
    public TransferGroup { Objects.requireNonNull(root); passengers = passengers.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()))); }
    public Set<String> completeMembers() {
        TreeSet<String> visited = new TreeSet<>(); walk(root, visited);
        return Set.copyOf(visited);
    }
    public void requireComplete(Set<String> offered) {
        if (!completeMembers().equals(Set.copyOf(offered))) throw new IllegalArgumentException("passenger transfer must include the complete root graph");
    }
    private void walk(String node, Set<String> visited) {
        if (!visited.add(node)) return;
        passengers.getOrDefault(node, Set.of()).forEach(child -> walk(child, visited));
    }
}
