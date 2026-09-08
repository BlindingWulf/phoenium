"""Sequential installed controls on one production/probe pair; stop on the first failed gate."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from run_stage4_acceptance import sha256
from compare_stage5_gameplay import compare, require, validate_intervals
from validate_stage5_persistence import validate as persistence
from validate_stage4_client_restart import validate as restart

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--mode',choices=['native','clients'],required=True)
    parser.add_argument('--distance',type=int,choices=[5,8],default=5)
    args=parser.parse_args();root=args.output.resolve()
    require(not root.exists(),'control campaign output already exists');root.mkdir(parents=True)
    production=Path('build/libs/phoenium-0.2.0-stage2.jar');probe=Path('build/test-harness/stage4-acceptance-probe.jar')
    production_hash=sha256(production);probe_hash=sha256(probe)
    runs={};results=[]
    for kind,workers in [('frozen',1),('candidate',1),('candidate',2),('candidate',4)]:
        require(sha256(production)==production_hash and sha256(probe)==probe_hash,'artifact changed between controls')
        name=f'{args.mode}-{kind}-{workers}-{args.distance}';out=root/name
        print('START '+name,flush=True)
        if args.mode=='native':
            command=[sys.executable,'scripts/run_stage5_gameplay.py','--output',str(out),'--workers',str(workers),
                '--furnaces-per-region','32','--warmup','200','--samples','3','--ticks','200',
                '--trace','--callback-trace','--churn','--distance',str(args.distance)]
            if kind=='frozen':command+=['--reference','--production','run/stage4/final-artifacts/phoenium-0.2.0-stage2.jar']
        else:
            command=['gradlew.bat','runStage4ClientGameTest','-Pstage5=true',f'-Pstage5Workers={workers}',
                '-Pstage4RunId=stage5-'+root.name+'-'+name,'-Pstage4ServerDirectory='+str(out),
                '-Pstage4EvidenceDir='+str(out/'evidence'),f'-Pstage4ViewDistance={args.distance}',
                f'-Pstage4SimulationDistance={args.distance}','-Pstage4PacketTrace=true','--no-daemon','--console=plain']
            if kind=='frozen':command+=['-Pstage4Reference=true','-Pstage4ReferenceProduction=run/stage4/final-artifacts/phoenium-0.2.0-stage2.jar']
        with (root/(name+'.log')).open('w',encoding='utf-8') as log:
            subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True)
        require(sha256(production)==production_hash and sha256(probe)==probe_hash,'artifact changed during a control run')
        runs[workers if kind=='candidate' else 'frozen']=out
        evidence=out/'evidence'
        if args.mode=='native':
            disk=persistence(evidence/'final.json',evidence/'mca-before-restart')
        else:
            deadline=restart(evidence/'STAGE4-CLIENT.json')
            (out/'native-deadline.json').write_text(json.dumps(deadline,indent=2))
            require(deadline['result']=='PASS','native restart deadline failed')
            gameplay=evidence/'gameplay'
            disk=persistence(gameplay/'client-saved.json',evidence/'mca-before-restart',gameplay/'client-reloaded.json',
                gameplay/'client-restored-after.json' if workers>1 else None)
            if kind=='candidate':
                before=json.loads((gameplay/'client-saved.json').read_text())
                interval=validate_intervals(before['gameplay'],workers>1)
                (out/'before-restart-body-intervals.json').write_text(json.dumps(interval,indent=2))
        (out/'furnace-persistence.json').write_text(json.dumps(disk,indent=2))
        if kind=='candidate':
            for control,reference in [('frozen',runs['frozen'])]+([('candidate-one',runs[1])] if workers>1 else []):
                target=root/f'compare-{control}-{workers}.json'
                if args.mode=='native':
                    result=compare(reference,out,workers>1);target.write_text(json.dumps(result,indent=2))
                else:
                    command=[sys.executable,'scripts/compare_stage5_client.py','--reference',str(reference),
                        '--candidate',str(out),'--output',str(target)]
                    with target.with_suffix('.log').open('w') as log:subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True)
        results.append(dict(name=name,status='PASS',workers=workers,reference=kind=='frozen'))
        (root/'partial.json').write_text(json.dumps(results,indent=2));print('PASS '+name,flush=True)
    (root/'report.json').write_text(json.dumps(dict(status='PASS_CONTROL_CAMPAIGN',mode=args.mode,
        productionSha256=production_hash,probeSha256=probe_hash,runs=results),indent=2))

if __name__=='__main__':main()
