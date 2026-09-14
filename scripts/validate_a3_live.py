"""Assert native A3 observations, then compare enabled/control selected work and carts."""
import argparse, collections, json, math, re
from pathlib import Path

def read(path): return json.loads(path.read_text())
def entities(s,tag=None): return {e['uuid']:e for e in s['entities'] if tag is None or tag in e['tags']}
def one(s,tag):
    found=list(entities(s,tag).values()); assert len(found)==1,(tag,len(found)); return found[0]
def members(s): return {m['id']:m for m in s['policy']['members']}
def obs(s,key,field): return s['nativeObservations'].get(key,{}).get(field,0)

def validate(root,require_restart=True):
    report=read(root/'report.json')
    if require_restart: assert report['status']=='PASS_NATIVE_WORKLOAD_PENDING_VALIDATION',root
    enabled=report['enabled']=='true'
    first={p.stem:read(p) for p in (root/'first/native').glob('*.json')}
    restart={p.stem:read(p) for p in (root/'restart/native').glob('*.json')}
    if require_restart: assert len(report['phases'])==2
    for phase in report['phases'] if require_restart else report['phases'][:1]:
        assert phase['exitCode']==0 and phase['status']=='PASS_CONSOLE_SAMPLE',phase
    if not require_restart: restart={}
    for s in list(first.values())+list(restart.values()):
        for name in ['duplicate','bypass','fallback','ownershipViolations','unleased','barriers','producerChunkMutations']:
            assert re.search(r'\b'+name+r'=0\b',s['diagnostics']),(s['label'],name)
        assert s['policy']['enabled']==enabled
        if not enabled: assert s['policy']['memberships']==s['policy']['deferred']==0
        grouped=collections.defaultdict(lambda:[0]*4)
        for m in members(s).values(): grouped[json.dumps(m['scope'],sort_keys=True)][m['phase']]+=1
        for counts in grouped.values(): assert max(counts)-min(counts)<=1,(s['label'],counts)
    for phase in [first,restart] if require_restart else [first]:
        assert phase['stopped']['policy']['memberships']==phase['stopped']['policy']['scopes']==0
    if require_restart:
        assert restart['cleanup']['policy']['memberships']==restart['cleanup']['policy']['scopes']==0
        assert not restart['cleanup']['entities']
    # Every native tick, AI step, navigation tick and despawn check is still reached.
    for before,after,tag,n in [('below-before','below-after','below',16),('dense-before','dense-after',None,80),
            ('farm-before','farm-after','dense-passive',80),('dimensions-before','dimensions-after','dimension',16)]:
        a,b=first[before],first[after]
        for key,e in entities(a,tag).items():
            assert key in entities(b),(after,key)
            assert entities(b)[key]['tickCount']-e['tickCount']==n,(after,key,'tickCount')
            for field in ['ticks','aiTicks','navigationTicks','despawnChecks']:
                assert obs(b,key,field)-obs(a,key,field)==n,(after,key,field,obs(b,key,field)-obs(a,key,field))
    assert first['below-after']['policy']['deferred']==0
    assert first['dense-after']['policy']==first['reobserved']['policy']
    if enabled:
        assert first['dense-after']['policy']['deferred']>0
        for e in entities(first['farm-after'],'independent').values():assert members(first['farm-after'])[e['uuid']]['deferred']==0
        for e in entities(first['dimensions-after'],'dimension').values():assert members(first['dimensions-after'])[e['uuid']]['deferred']==0
        if 'dimension-retired' in first:
            assert not any(m['scope']['region']['world']['value']=='minecraft:the_nether' for m in members(first['dimension-retired']).values())
    # Actual canUse invocations, not merely permission counters, bound service intervals.
    a,b=first['dense-before'],first['dense-after']; worldStart=next(iter(entities(a).values()))['gameTime']
    bins=collections.Counter(); maxGap=0
    for key,e in entities(a).items():
        o=b['nativeObservations'][key]
        ticks=sorted(int(t) for t in o['acquisitionTicks'] if e['tickCount']<int(t)<=e['tickCount']+80)
        assert ticks and ticks[0]-e['tickCount']<=8 and e['tickCount']+80-ticks[-1]<8,(key,ticks)
        gaps=[y-x for x,y in zip(ticks,ticks[1:])]; maxGap=max(maxGap,*gaps)
        assert max(gaps)<=8,(key,gaps)
        for t,n in o['acquisitionWorldTicks'].items():
            if worldStart<int(t)<=worldStart+80:bins[(int(t)-worldStart-1)//2]+=n
    timerA,timerB=one(first['farm-before'],'timer'),one(first['farm-after'],'timer')
    assert timerB['age']-timerA['age']==80
    assert timerA['effects'][0]['duration']-timerB['effects'][0]['duration']==80
    assert not timerA['navigationDone'],'Explicit navigation must first produce a path'
    assert math.dist(timerA['position'],timerB['position'])>1,'Navigation must move the mob'
    assert any(e.get('target') for e in entities(first['ai-after'],'dense-hostile').values()),'Hostile target acquisition absent'
    assert len(entities(first['breeding-after'],'newborn'))==1
    for e in entities(first['breeding-after'],'breeder').values(): assert e['age']>0 and e['inLove']==0
    movedA,movedB=one(first['relocation-before'],'mover'),one(first['relocation-after'],'mover')
    assert movedA['uuid']==movedB['uuid'] and movedA['incarnation']==movedB['incarnation']
    if enabled:
        assert members(first['relocation-before'])[movedA['uuid']]['scope']!=members(first['relocation-after'])[movedB['uuid']]['scope']
    unloaded=entities(first['unload-before'],'unload'); assert len(unloaded)==2
    for key,e in unloaded.items():
        assert key not in entities(first['unloaded']) and key not in members(first['unloaded'])
        assert entities(first['reloaded'])[key]['incarnation']!=e['incarnation']
    # Native vehicle / passenger callbacks run all 32 ticks, including a 16-cart overlap.
    a,b=first['vehicles-before'],first['vehicles-after']; carts=entities(a,'dense-cart')|entities(a,'moving-cart')
    assert len(carts)==17
    for key,e in carts.items():
        assert entities(b)[key]['tickCount']-e['tickCount']==32
        assert obs(b,key,'ticks')-obs(a,key,'ticks')==32
    rider=one(first['pickup'],'rider'); cart=one(first['pickup'],'moving-cart')
    assert rider['vehicle']==cart['uuid'] and cart['passengers']==[rider['uuid']]
    assert one(b,'rider')['tickCount']==32 and one(b,'moving-cart')['position'][0]>cart['position'][0]+5
    assert len({tuple(e['position']) for e in entities(b,'dense-cart').values()})>1
    # Persisted identities, age/effect clocks, passenger edges and reconstructed memberships.
    persisted=entities(first['pre-stop'])
    result=dict(status='PASS_NATIVE_A3' if require_restart else 'PASS_A3_FIRST_PHASE_ONLY',workers=report['workers'],enabled=enabled,productionSha256=report['productionSha256'],
        denseSelectedCalls=sum(bins.values()),densePeakTwoTickCalls=max(bins.values()),maxNativeAcquisitionGap=maxGap,
        hostileTargets=sum(bool(e.get('target')) for e in entities(first['ai-after'],'dense-hostile').values()),
        persistedEntities=len(persisted),vehicleTicks=17*32,passengerPickupTick=cart['tickCount'])
    if not require_restart:
        (root/'first-validation.json').write_text(json.dumps(result,indent=2)+'\n');return result,first
    loaded=entities(restart['restart-before'])
    assert persisted.keys()==loaded.keys(),('persisted identities',persisted.keys()-loaded.keys(),loaded.keys()-persisted.keys())
    for key,e in persisted.items():
        assert e['passengers']==loaded[key]['passengers'] and e['vehicle']==loaded[key]['vehicle']
    ta,tb=one(first['pre-stop'],'timer'),one(restart['restart-before'],'timer')
    assert tb['age']-ta['age']==tb['tickCount'],('restart age',ta,tb)
    assert ta['effects'][0]['duration']-tb['effects'][0]['duration']==tb['tickCount']
    for key,e in entities(restart['restart-before'],'dense-hostile').items():
        end=entities(restart['restart-after'])[key]
        assert end['tickCount']-e['tickCount']==16
        if enabled: assert members(restart['restart-after'])[key]['allowed']>0
    if enabled: assert first['pre-stop']['policy']['memberships']==restart['restart-before']['policy']['memberships']
    (root/'validation.json').write_text(json.dumps(result,indent=2)+'\n');return result,first

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--enabled',type=Path,required=True);p.add_argument('--control',type=Path,required=True)
    p.add_argument('--first-only',action='store_true',help='Explicitly partial validation; leaves restart gate unaccepted.')
    args=p.parse_args();on,a=validate(args.enabled,not args.first_only);off,b=validate(args.control,not args.first_only)
    assert on['enabled'] and not off['enabled'] and on['workers']==off['workers']
    assert on['productionSha256']==off['productionSha256']
    assert on['denseSelectedCalls']<off['denseSelectedCalls']*.5
    assert on['densePeakTwoTickCalls']<off['densePeakTwoTickCalls']*.6
    for label in ['pickup','vehicles-after']:
        for tag in ['moving-cart','rider']:
            x,y=one(a[label],tag),one(b[label],tag)
            for field in ['position','velocity']:assert math.dist(x[field],y[field])<1e-6,(label,tag,field,x[field],y[field])
        positions=lambda s:sorted(tuple(e['position']) for e in entities(s,'dense-cart').values())
        for x,y in zip(positions(a[label]),positions(b[label])):assert math.dist(x,y)<1e-6,(label,'cart collision parity',x,y)
    result=dict(status='PASS_A3_FIRST_PHASE_COMPARISON' if args.first_only else 'PASS_A3_COMPARISON',enabled=on,control=off,selectedCallReduction=1-on['denseSelectedCalls']/off['denseSelectedCalls'])
    (args.enabled/'comparison.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
if __name__=='__main__':main()
