package dev.phoenium.clienttest;

import java.nio.file.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Calls the actual prelaunch/restart client guard against swapped real local files. */
public final class Stage5ArtifactIdentityChecks {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalStateException("identity evidence already exists");
        Files.createDirectories(output);
        Path candidate = root.resolve("build/libs/phoenium-0.2.0-stage2.jar");
        Path stage4 = root.resolve("run/stage4/final-artifacts/phoenium-0.2.0-stage2.jar");
        Path stage3 = root.resolve("run/stage3-closure/final-artifacts/phoenium-0.2.0-stage2.jar");
        Method guard = Stage4ClientTraceTest.class.getDeclaredMethod("verifyInstalledProduction", Path.class, Path.class, String.class, boolean.class);
        guard.setAccessible(true);
        List<Map<String,Object>> results = new ArrayList<>();
        for (String phase : List.of("first", "restart")) {
            check(guard, output, results, "candidate-"+phase, candidate, candidate, true, true, true, phase);
            check(guard, output, results, "reference4-"+phase, stage4, stage4, false, true, true, phase);
            check(guard, output, results, "swapped-candidate-"+phase, candidate, stage4, true, true, false, phase);
            check(guard, output, results, "swapped-reference-"+phase, stage4, candidate, false, true, false, phase);
            check(guard, output, results, "candidate-as-reference-"+phase, candidate, candidate, false, true, false, phase);
            check(guard, output, results, "stage3-as-reference-"+phase, stage3, stage3, false, true, false, phase);
            check(guard, output, results, "stage3-as-candidate-"+phase, stage3, stage3, true, true, false, phase);
            check(guard, output, results, "stage4-as-candidate-"+phase, stage4, stage4, true, true, false, phase);
            check(guard, output, results, "original-stage4-reference3-"+phase, stage3, stage3, false, false, true, phase);
        }
        Files.writeString(output.resolve("report.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(Map.of("status","PASS","checks",results)), StandardOpenOption.CREATE_NEW);
        System.out.println("STAGE5_ARTIFACT_IDENTITY PASS checks=" + results.size());
    }
    private static void check(Method guard, Path root, List<Map<String,Object>> results, String name,
                              Path expected, Path actual, boolean candidate, boolean stage5, boolean accepted, String phase) throws Exception {
        Path output = root.resolve(name); Files.createDirectories(output.resolve("mods"));
        Files.copy(actual, output.resolve("mods/phoenium-0.2.0-stage2.jar"));
        String hash = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(expected)));
        System.setProperty("phoenium.stage4.expectedProductionSha256", hash);
        System.setProperty("phoenium.stage5", Boolean.toString(stage5));
        boolean passed = true;
        try { guard.invoke(null, output, output.resolve("trace.json"), phase, candidate); }
        catch (InvocationTargetException failure) {
            if (!(failure.getCause() instanceof IllegalStateException)) throw failure;
            passed = false;
        }
        if (passed != accepted) throw new AssertionError("actual client guard accepted/rejected incorrectly: " + name);
        results.add(Map.of("case",name,"accepted",passed,"expectedAcceptance",accepted,"pass",true));
    }
}
