package dev.phoenium.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Opt-in generator, not a gate: walks the tick()/baseTick() override chain of every Mob caller profile in the
 * pinned census, lifts each declaring class with the production lifter, and writes the pin file for review.
 * PHOENIUM_TAIL_PINS=&lt;output.txt&gt; and PHOENIUM_TAIL_CENSUS_INPUT=&lt;census.json&gt; enable it.
 */
@EnabledIfEnvironmentVariable(named = "PHOENIUM_TAIL_PINS", matches = ".+")
class MobTailCensusTest {
    private static final java.util.Set<String> LEGACY = java.util.Set.of(
        "net/minecraft/world/entity/Entity", MobPortalNativeSuffixes.LIVING, MobPortalNativeSuffixes.MOB,
        MobPortalNativeSuffixes.CUBE, MobPortalNativeSuffixes.HORSE, MobPortalNativeSuffixes.VILLAGER);

    @Test void generate() throws Exception {
        JsonObject census = JsonParser.parseString(Files.readString(Path.of(System.getenv("PHOENIUM_TAIL_CENSUS_INPUT")))).getAsJsonObject();
        Map<String, Map<String, TreeSet<String>>> users = new TreeMap<>();
        for (var element : census.getAsJsonArray("rows")) {
            JsonObject row = element.getAsJsonObject();
            if (!row.get("route").getAsString().equals("NETHER_ENTRY") || !row.get("graph").getAsString().equals("standalone")
                    || !row.get("variant").isJsonNull()) continue;
            JsonObject callers = row.getAsJsonObject("sourceReference").getAsJsonObject("callerMethods");
            String type = row.get("entityType").getAsString();
            String tickOwner = callers.getAsJsonObject("tick").get("owner").getAsString().replace('.', '/');
            if (!isMob(tickOwner)) continue;
            for (String method : List.of("tick", "baseTick")) {
                String owner = callers.getAsJsonObject(method).get("owner").getAsString().replace('.', '/');
                for (String declaring : chain(owner, method))
                    users.computeIfAbsent(declaring, k -> new TreeMap<>())
                        .computeIfAbsent(method, k -> new TreeSet<>()).add(type);
            }
        }
        List<String> lines = new ArrayList<>(), failures = new ArrayList<>();
        lines.add("# owner|method|capture|emptyTail|fingerprint  (generated from the pinned 26.2 bytecode; review before use)");
        for (var byClass : users.entrySet()) {
            if (LEGACY.contains(byClass.getKey())) continue;
            ClassNode node = node(byClass.getKey());
            for (String method : byClass.getValue().keySet()) {
                try {
                    var lifted = MobPortalOuterTails.lift(node, method);
                    lines.add(byClass.getKey() + "|" + method + "|" + lifted.capture() + "|" + lifted.emptyTail() + "|" + lifted.fingerprint());
                } catch (RuntimeException unliftable) {
                    failures.add(byClass.getKey() + "." + method + " : " + unliftable.getMessage());
                }
            }
        }
        Files.writeString(Path.of(System.getenv("PHOENIUM_TAIL_PINS")), String.join("\n", lines) + "\n");
        Files.writeString(Path.of(System.getenv("PHOENIUM_TAIL_PINS") + ".failures"), String.join("\n", failures) + "\n");
    }

    private static boolean isMob(String owner) throws Exception {
        for (String cls = owner; cls != null && !cls.equals("java/lang/Object"); cls = node(cls).superName)
            if (cls.equals(MobPortalNativeSuffixes.MOB)) return true;
        return false;
    }

    private static List<String> chain(String owner, String method) throws Exception {
        List<String> declaring = new ArrayList<>();
        for (String cls = owner; cls != null && !cls.equals("java/lang/Object"); ) {
            ClassNode node = node(cls);
            if (node.methods.stream().anyMatch(m -> m.name.equals(method) && m.desc.equals("()V"))) declaring.add(cls);
            cls = node.superName;
        }
        return declaring;
    }

    private static ClassNode node(String cls) throws Exception {
        try (InputStream in = MobTailCensusTest.class.getResourceAsStream("/" + cls + ".class")) {
            if (in == null) throw new IllegalStateException("class not on test classpath: " + cls);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
