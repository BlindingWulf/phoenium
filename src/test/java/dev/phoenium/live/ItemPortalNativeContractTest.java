package dev.phoenium.live;

import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemPortalNativeContractTest {
    @Test void nativeReplacementOrderingAndItemReturnSuffixArePinned() throws Exception {
        var nativeCalls=calls(method("net/minecraft/world/entity/Entity","teleportCrossDimension"));
        ordered(nativeCalls,"create","restoreFrom","removeAfterChangingDimensions","teleportSetPosition","addDuringTeleport","resetEmptyTime","onTransition","teleportSpectators");
        var item=calls(method("net/minecraft/world/entity/item/ItemEntity","teleport"));
        assertEquals(List.of("teleport","level","isClientSide","mergeWithNeighbours"),item);
    }
    @Test void retainedItemSuffixCallsAndWritesMatchTheActualMappedNativeSuffix() throws Exception {
        var original=method("net/minecraft/world/entity/item/ItemEntity","tick");
        List<String> calls=calls(original);
        var suffix=method("dev/phoenium/mixin/ItemPortalTickMixin","phoenium$resumeItemTickSuffix");
        assertEquals(calls.subList(calls.indexOf("tick")+1,calls.size()),calls(suffix));
        List<String> nativeWrites=new ArrayList<>(); boolean after=false;
        for (var instruction: original.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("tick")) after=true;
            if (after && instruction instanceof FieldInsnNode field && field.getOpcode()==org.objectweb.asm.Opcodes.PUTFIELD) nativeWrites.add(field.name+field.desc);
        }
        List<String> suffixWrites=new ArrayList<>();
        for (var instruction:suffix.instructions) if (instruction instanceof FieldInsnNode field && field.getOpcode()==org.objectweb.asm.Opcodes.PUTFIELD) suffixWrites.add(field.name+field.desc);
        assertEquals(nativeWrites,suffixWrites);
        assertFalse(calls(suffix).contains("tick")); assertFalse(calls(suffix).contains("getPortalDestination"));
    }
    @Test void itemCutPrecedesDestinationAndBaseSuffixPreservesNativeFireSetter() throws Exception {
        assertTrue(dev.phoenium.chunk.PortalDestinationTestHooks.itemCutIsEarly(), "actual production selector must precede destination lookup");
        var handle=calls(method("net/minecraft/world/entity/Entity","handlePortal"));
        ordered(handle,"processPortalCooldown","processPortalTeleportation","setPortalCooldown","getPortalDestination","isAllowedToEnterPortal","teleport");
        assertTrue(calls(method("dev/phoenium/mixin/PearlPortalEntityMixin","phoenium$resumePearlPortalBaseTickSuffix")).contains("setRemainingFireTicks"));
        var enclosing=calls(method("net/minecraft/server/level/ServerLevel","tickNonPassenger"));
        ordered(enclosing,"setOldPosAndRot","tick","pop","getPassengers");
    }
    @Test void pearlUsesOneSharedNativeTransferAndOneExistingRetainedContinuation() throws Exception {
        var calls=calls(method("dev/phoenium/live/PearlPortalTransitionManager","replaceCrossDimension"));
        assertEquals(1,Collections.frequency(calls,"run"));
        assertFalse(calls.contains("create")); assertFalse(calls.contains("restoreFrom")); assertFalse(calls.contains("addDuringTeleport"));
        var node=node("dev/phoenium/live/PearlPortalTransitionManager");
        long resumes=node.methods.stream().filter(m->m.name.startsWith("lambda$replaceCrossDimension")).flatMap(m->calls(m).stream()).filter("resume"::equals).count();
        assertEquals(1,resumes);
        var retained=calls(method("dev/phoenium/live/RetainedPearlTickContinuation","resume"));
        assertEquals(1,Collections.frequency(retained,"phoenium$resumePearlPortalThrowableTickSuffix"));
        assertFalse(retained.contains("teleport")); assertFalse(retained.contains("tick"));
    }
    @Test void newProductionMixinsAreRegisteredAndTestPlumbingIsSeparate() throws Exception {
        String config;
        try(var stream=getClass().getResourceAsStream("/phoenium.mixins.json")) { config=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
        for(String name:List.of("NativePortalTransferMixin","NativePortalStorageMixin","ItemPortalEntityMixin","ItemPortalTickMixin")) assertTrue(config.contains('"'+name+'"'));
        assertFalse(config.contains("Test"));
    }
    private static void ordered(List<String> calls,String... order) {
        int last=-1; for(String name:order) { int found=calls.subList(last+1,calls.size()).indexOf(name); assertTrue(found>=0,name+calls); last+=found+1; }
    }
    private static List<String> calls(MethodNode method) {
        List<String> names=new ArrayList<>(); for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call) names.add(call.name); return names;
    }
    private static MethodNode method(String owner,String name) throws Exception {
        var methods=node(owner).methods.stream().filter(m->m.name.equals(name)).toList(); assertEquals(1,methods.size(),owner+"."+name); return methods.getFirst();
    }
    private static ClassNode node(String owner) throws Exception {
        try(InputStream stream=ItemPortalNativeContractTest.class.getResourceAsStream("/"+owner+".class")) {
            assertNotNull(stream,owner); var node=new ClassNode(); new ClassReader(stream).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES); return node;
        }
    }
}
