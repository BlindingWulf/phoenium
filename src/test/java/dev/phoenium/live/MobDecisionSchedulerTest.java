package dev.phoenium.live;

import static org.junit.jupiter.api.Assertions.*;
import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import org.junit.jupiter.api.Test;

class MobDecisionSchedulerTest {
    private static final MobDecisionScheduler.Scope A = scope("minecraft:overworld", "a");
    private static final MobDecisionScheduler.Scope B = scope("minecraft:overworld", "b");
    private static final MobDecisionScheduler.Scope N = scope("minecraft:the_nether", "a");
    private static MobDecisionScheduler.Scope scope(String world, String region) {
        return new MobDecisionScheduler.Scope(new RegionId(new WorldId(world), region), MobDecisionScheduler.Category.HOSTILE);
    }
    private static MobDecisionScheduler scheduler() { return new MobDecisionScheduler(new MobDecisionScheduler.Settings(true, 4)); }
    private static void populate(MobDecisionScheduler s, MobDecisionScheduler.Scope scope, int n) {
        for (int i = 0; i < n; i++) s.observe(scope.region().toString() + i, i + 1, 0, scope);
    }
    private static boolean gate(MobDecisionScheduler s, MobDecisionScheduler.MemberView m, int tick, long worldTick) {
        return s.allow(m.id(), m.incarnation(), m.scope(), tick, worldTick, false);
    }
    @Test void disabledRetainsNoPolicyMembershipAndAlwaysRuns() {
        var s = new MobDecisionScheduler(new MobDecisionScheduler.Settings(false, 4));
        populate(s, A, 100);
        assertEquals(0, s.snapshot().memberships());
        assertTrue(s.allow("missing", 1, A, 1, 1, false));
        assertEquals(0, s.snapshot().deferred());
    }
    @Test void invalidThresholdCannotEnableAnUndefinedPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new MobDecisionScheduler.Settings(true, 0));
        assertThrows(IllegalArgumentException.class, () -> new MobDecisionScheduler.Settings(true, -1));
    }
    @Test void exactThresholdRemainsNative() {
        var s = scheduler(); populate(s, A, 4);
        for (int tick = 0; tick < 32; tick++) for (var m : s.snapshot().members()) assertTrue(gate(s,m,tick,tick));
        assertEquals(0, s.snapshot().deferred());
    }
    @Test void adversarialUuidHashesStillDistributeEquallyAcrossNativeOpportunities() {
        var s = scheduler();
        for (int i=0;i<4096;i++) s.observe(new UUID(0, (long)i << 32 | i).toString(), i+1, 0, A);
        int[] slots = new int[4];
        for (int tick=0;tick<8;tick+=2) for (var m:s.snapshot().members())
            if(gate(s,m,tick,tick)) slots[tick/2]++;
        assertArrayEquals(new int[]{1024,1024,1024,1024}, slots);
        assertEquals(12288, s.snapshot().deferred());
    }
    @Test void everyStableEntityReceivesOneServicePerEightTickWindow() {
        var s = scheduler(); populate(s,A,100);
        Map<String,Integer> last = new HashMap<>();
        for(int tick=0;tick<128;tick+=2) for(var m:s.snapshot().members()) if(gate(s,m,tick,tick)) {
            Integer before=last.put(m.id(),tick);
            if(before!=null) assertEquals(8,tick-before);
        }
        assertEquals(100,last.size());
        s.snapshot().members().forEach(m -> assertEquals(16,m.allowed()));
    }
    @Test void distinctRegionsCannotConsumeEachOthersThresholdOrCohorts() {
        var s=scheduler(); populate(s,A,64); populate(s,B,4);
        for(var m:s.snapshot().members()) if(m.scope().equals(B)) assertTrue(gate(s,m,2,2));
        assertEquals(2,s.snapshot().scopes());
    }
    @Test void worldIsPartOfEveryScope() {
        var s=scheduler(); populate(s,A,64); populate(s,N,4);
        for(var m:s.snapshot().members()) if(m.scope().equals(N)) assertTrue(gate(s,m,2,2));
        assertEquals(2,s.snapshot().scopes());
    }
    @Test void hostileAndPassiveDensityAreIndependent() {
        var s=scheduler(); populate(s,A,64);
        var passive=new MobDecisionScheduler.Scope(A.region(),MobDecisionScheduler.Category.PASSIVE);
        s.observe("cow",1,0,passive);
        assertTrue(s.allow("cow",1,passive,2,2,false));
    }
    @Test void mountedAndCarrierExemptionAlwaysRuns() {
        var s=scheduler(); populate(s,A,64);
        for(int t=0;t<16;t++) for(var m:s.snapshot().members()) assertTrue(s.allow(m.id(),m.incarnation(),A,t,t,true));
        assertEquals(0,s.snapshot().deferred());
    }
    @Test void duplicateAddressabilityDoesNotDuplicateOrResetPendingDeadline() {
        var s=scheduler(); populate(s,A,8);
        var m=s.snapshot().members().stream().filter(x->x.phase()==1).findFirst().orElseThrow();
        assertFalse(gate(s,m,100,0));
        for(int t=102;t<=106;t+=2) {
            s.observe(m.id(),m.incarnation(),0,A);
            boolean allowed=gate(s,m,t,0);
            assertEquals(t==106,allowed);
        }
        assertEquals(8,s.snapshot().memberships());
        assertEquals(1,s.snapshot().deadlines());
    }
    @Test void bothSelectorsShareOneGatePerMobTick() {
        var s=scheduler(); populate(s,A,8);
        for(var m:s.snapshot().members()) { boolean a=gate(s,m,0,0); assertEquals(a,gate(s,m,0,0)); }
        assertEquals(8,s.snapshot().allowed()+s.snapshot().deferred());
    }
    @Test void retirementRebalancesCohortsAndPrunesEmptyScopes() {
        var s=scheduler(); populate(s,A,97);
        var initial=s.snapshot().members();
        for(var removed:initial) {
            s.retire(removed.id(),removed.incarnation());
            int[] counts=new int[4]; s.snapshot().members().forEach(m->counts[m.phase()]++);
            assertTrue(Arrays.stream(counts).max().orElseThrow()-Arrays.stream(counts).min().orElseThrow()<=1);
        }
        assertEquals(0,s.snapshot().memberships()); assertEquals(0,s.snapshot().scopes());
    }
    @Test void staleRetirementAndRelocationCannotTouchAReplacement() {
        var s=scheduler(); s.observe("x",10,0,A); s.observe("x",11,1,B);
        s.observe("x",10,0,A);
        s.retire("x",10); s.relocate("x",0,N.region());
        var m=s.snapshot().members().getFirst();
        assertEquals(11,m.incarnation()); assertEquals(B,m.scope());
    }
    @Test void unloadReloadCreatesFreshExactMembership() {
        var s=scheduler(); s.observe("x",10,0,A); s.retire("x",10); s.observe("x",11,1,A);
        assertEquals(1,s.snapshot().memberships()); assertFalse(s.snapshot().members().getFirst().pending());
    }
    @Test void restartReconstructsFromEntitiesWithoutPersistedSchedulerAuthority() {
        var s=scheduler(); populate(s,A,12); var old=s.snapshot().members(); s.close();
        assertEquals(0,s.snapshot().memberships()); assertEquals(0,s.snapshot().scopes());
        var restarted=scheduler(); for(var m:old) restarted.observe(m.id(),m.incarnation()+100,0,A);
        assertEquals(12,restarted.snapshot().memberships());
        int[] slots=new int[4]; for(int t=0;t<8;t+=2) for(var m:restarted.snapshot().members()) if(gate(restarted,m,t,t))slots[t/2]++;
        assertArrayEquals(new int[]{3,3,3,3},slots);
    }
    @Test void relocationPreservesFirstDeferredTickAndPreventsRepeatedTransferStarvation() {
        var s=scheduler(); populate(s,A,20); populate(s,B,20);
        var selected=s.snapshot().members().stream().filter(m->m.scope().equals(A)&&m.phase()==1).findFirst().orElseThrow();
        assertFalse(gate(s,selected,100,0));
        for(int t=102;t<=106;t+=2) {
            var destination=t==104?A:B;
            s.relocate(selected.id(),0,destination.region());
            var m=s.snapshot().members().stream().filter(v->v.id().equals(selected.id())).findFirst().orElseThrow();
            long offPhase=((m.phase()+1)%4)*2L;
            assertEquals(t==106,s.allow(m.id(),m.incarnation(),destination,t,offPhase,false));
        }
        assertEquals(40,s.snapshot().memberships());
    }
    @Test void topologyOwnerReobservationPreservesDeadlineToo() {
        var s=scheduler(); populate(s,A,20); populate(s,B,20);
        var m=s.snapshot().members().stream().filter(v->v.scope().equals(A)&&v.phase()==1).findFirst().orElseThrow();
        assertFalse(gate(s,m,0,0)); s.observe(m.id(),m.incarnation(),0,B);
        assertTrue(s.allow(m.id(),m.incarnation(),B,6,0,false));
    }
    @Test void entityTickWrapAndWorldTimeRewindDoNotRestartDeadline() {
        var s=scheduler(); populate(s,A,8);
        var m=s.snapshot().members().stream().filter(v->v.phase()==1).findFirst().orElseThrow();
        assertFalse(gate(s,m,Integer.MAX_VALUE-2,0));
        assertTrue(gate(s,m,Integer.MIN_VALUE+3,-1000));
    }
    @Test void nextAvailableNativeOpportunityRunsWhenDeadlinePassedWhileGoalWasBlocked() {
        var s=scheduler(); populate(s,A,8);
        var m=s.snapshot().members().stream().filter(v->v.phase()==1).findFirst().orElseThrow();
        assertFalse(gate(s,m,0,0)); assertTrue(gate(s,m,100,0));
    }
    @Test void independentOwnerThreadsCanAccountWithoutGlobalGameplaySerialization() throws Exception {
        var s=scheduler();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=List.of(A,N).stream().map(scope->executor.submit(()->{
                populate(s,scope,100);
                for(int t=0;t<100;t+=2) for(int i=0;i<100;i++)
                    s.allow(scope.region().toString()+i,i+1,scope,t,t,false);
                for(int i=0;i<100;i++)s.retire(scope.region().toString()+i,i+1);
            })).toList();
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }
        assertEquals(0,s.snapshot().memberships()); assertEquals(0,s.snapshot().scopes());
    }
    @Test void onlyExactReviewedGoalClassesCanBeDeferred() {
        assertTrue(MobDecisionContext.reviewedGoal(RandomStrollGoal.class));
        assertTrue(MobDecisionContext.reviewedGoal(WaterAvoidingRandomStrollGoal.class));
        assertTrue(MobDecisionContext.reviewedGoal(NearestAttackableTargetGoal.class));
        assertFalse(MobDecisionContext.reviewedGoal(TemptGoal.class));
        assertFalse(MobDecisionContext.reviewedGoal(BreedGoal.class));
        assertFalse(MobDecisionContext.reviewedGoal(MeleeAttackGoal.class));
        assertFalse(MobDecisionContext.reviewedGoal(UnknownStroll.class));
    }
    private abstract static class UnknownStroll extends RandomStrollGoal { UnknownStroll(){super(null,1);} }
}
