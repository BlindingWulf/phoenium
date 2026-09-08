"""Stage 5 client contract: Stage 4's exact ordered effects plus mandatory native restart evidence.

The original temporary-unload/readback path and the actual second lifecycle both
retain their native unpack requirements. No Stage 4 normalization is changed.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
from compare_stage4_trace import load
from compare_stage5_gameplay import require
from validate_stage4_client_restart import validate as restart

FIXTURE_IDS={'00000001-0000-0002-0000-000300000004':340573321,
    '00000005-0000-0006-0000-000700000008':681146642,
    '00000009-0000-000a-0000-000b0000000c':1021719963}

def tracker_clock(client, origin):
    require(client['trackingClock'] is True, 'client comparison requires its declared tracker input clock')
    pattern=re.compile(r'STAGE5_TRACKER_CLOCK uuid=([0-9a-f-]+) gameTime=(\d+) before=(\d+) after=(\d+)')
    clocks={}
    for uuid,now,before,after in pattern.findall(Path(client['log']).read_text(encoding='utf-8')):
        rows=clocks.setdefault(uuid,[]); value=[int(now)-origin,int(before),int(after)]
        require(not rows or value[0]>rows[-1][0], 'duplicate or reversed offered native tracker tick')
        require(value[2]>value[1], 'original tracker body did not advance its own clock')
        rows.append(value)
    require(set(clocks)==set(FIXTURE_IDS), 'missing or foreign original tracker clock coverage')
    return clocks

def fixture_identities(report):
    observed={}
    for event in report['events']:
        if event.startswith(('tracking.pair ','tracking.remove ','tracking.delivered ')):
            uuid=re.search(r'\buuid=([0-9a-f-]+)',event).group(1)
            identity=int(re.search(r'\bentityId=(\d+)',event).group(1))
            require(FIXTURE_IDS.get(uuid)==identity,'changed native fixture ID/hash-table input')
            observed[uuid]=identity
    require(observed==FIXTURE_IDS,'missing fixed native fixture identity coverage')

def compare(reference,candidate):
    roots=[Path(reference),Path(candidate)]; streams=[]; receipts=[]; probes=[]; clocks=[]
    for root in roots:
        evidence=root/'evidence'; client=json.loads((evidence/'STAGE4-CLIENT.json').read_text())
        require(client['realTcpConnection'] and not client['microsoftAuthentication'] and client['serverExitCode']==0,'client lifecycle failed')
        require(client['viewDistance']==client['simulationDistance'] and client['viewDistance'] in (5,8),'client distance changed')
        probes.append(client['probeSha256'])
        for phase in ('first','restart'):
            identity=json.loads((evidence/f'production-identity-{phase}.json').read_text())
            require(identity['pass'] and identity['expectedSha256']==identity['installedSha256']==client['productionSha256'],
                'client installed artifact changed before '+phase)
            require(identity['installedSha256']!='017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534','Stage 3 is not a Stage 5 control')
        disk=restart(evidence/'STAGE4-CLIENT.json');require(disk['result']=='PASS','native saved tick/unpack/deadline failed')
        receipts.append(dict(viewDistance=client['viewDistance'],simulationDistance=client['simulationDistance'],savedDelay=disk['savedDelay']))
        report,stream=load(evidence/'STAGE4-CLIENT-before-restart-trace.json')
        fixture_identities(report)
        clocks.append(tracker_clock(client,report['startedAt']))
        require(report['result']=='PASS' and not report['missing'],'client trace lacks required original effects or ownership')
        for marker in ('topology.merge','topology.split','block.deferred'):
            require(any(e.startswith(marker+' ') for e in report['events']),'missing client lifecycle '+marker)
        for marker in ('topology.body','tracking.body'):
            for owner in ('a','b'):
                require(any(e.startswith(marker+' ') and 'contextRegion=stage3-fixture-'+owner in e for e in report['events']),
                    'missing current client '+marker+' owner '+owner)
        streams.append(stream)
    require(probes[0]==probes[1],'client probe artifact differs between controls')
    require(receipts[0]==receipts[1],'native saved delay or actual client distances differ')
    require(clocks[0]==clocks[1],'offered original tracker clocks differ')
    mismatch=next((dict(index=i,reference=a,candidate=b) for i,(a,b) in enumerate(zip(*streams)) if a!=b),None)
    if mismatch is None and len(streams[0])!=len(streams[1]):mismatch=dict(lengths=list(map(len,streams)))
    return dict(status='PASS' if mismatch is None else 'FAIL',firstMismatch=mismatch,
        orderedEvents=list(map(len,streams)),nativeRestart=receipts,
        originalTrackerCalls={uuid:len(rows) for uuid,rows in clocks[0].items()},
        digests=[hashlib.sha256('\n'.join(s).encode()).hexdigest().upper() for s in streams])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--reference',required=True);p.add_argument('--candidate',required=True);p.add_argument('--output',required=True)
    args=p.parse_args()
    try:result=compare(args.reference,args.candidate)
    except Exception as error:result=dict(status='FAIL',failure=str(error))
    Path(args.output).write_text(json.dumps(result,indent=2));print(json.dumps(result))
    if result['status']!='PASS':raise SystemExit(1)
