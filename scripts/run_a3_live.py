"""Bounded native A3 workload on the accepted provider composition. No timing benchmark."""
import argparse, hashlib, json, shutil, subprocess
from pathlib import Path
from run_stage6_console import inventory, lifecycle

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / 'build/projectile-cap-live/stack16-w4/evidence-w4/server-1789315341299'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def action(command, expect, **kw): return dict(command=command, expect=expect, **kw)
def snapshot(label): return action('a3 snapshot '+label, 'A3_SNAPSHOT '+label)
def step(ticks): return action('tick step '+str(ticks),'Stepping',settleSeconds=ticks/20+0.8)
def summon(kind,x,y,z,tags,extra=''):
    return action(f'summon minecraft:{kind} {x} {y} {z} {{Tags:{json.dumps(["a3",*tags])},PersistenceRequired:1b'+(','+extra if extra else '')+'}', 'Summoned new')

def plan(smoke):
    first=[action('tick freeze','The game is frozen'),action('gamerule minecraft:spawn_mobs false','false'),
        action('gamerule minecraft:advance_time false','false'),action('time set midnight','time marker'),
        action('gamerule minecraft:max_entity_cramming 0','0'),
        action('forceload add -16 -16 47 31','Marked',settleSeconds=2),
        action('execute if loaded 0 65 0 run say A3_READY','A3_READY',pollReadiness=True),
        action('fill -12 64 -12 28 64 12 minecraft:stone','Successfully filled'),
        action('fill -12 65 -12 28 68 12 minecraft:air','blocks',regex=False),
        summon('zombie',0,65,0,['below','mover']),summon('zombie',2,65,0,['below']),
        snapshot('below-before'),step(16),snapshot('below-after')]
    for i in range(30):first.append(summon('zombie',(i%6)*0.35,65,(i//6)*0.35,['dense-hostile']))
    first += [snapshot('dense-before'),step(80),snapshot('dense-after'),action('a3 reobserve','A3_REOBSERVE'),snapshot('reobserved')]
    if smoke:
        first += [action('kill @e[tag=a3]','Killed'),step(25),snapshot('cleanup')]
        return {'first':first}
    first += [action('forceload add 96 -16 143 31','Marked',settleSeconds=2),
        action('execute if loaded 112 65 0 run say A3_B_READY','A3_B_READY',pollReadiness=True),
        action('fill 100 64 -12 140 64 16 minecraft:stone','Successfully filled'),
        action('fill 100 65 -12 140 68 16 minecraft:air','blocks')]
    for i in range(24):
        tags=['dense-passive']
        if i==0:tags+=['timer','navigator']
        if i in [1,2]:tags+=['breeder']
        first.append(summon('cow',106 if i==0 else 112+(i%6)*0.4,65,(i//6)*0.4,tags,'Age:-24000' if i==0 else ''))
    first += [summon('zombie',132,65,8,['independent']),summon('zombie',134,65,8,['independent']),
        step(2),action('effect give @e[tag=timer] minecraft:glowing 60 0 true','Applied'),
        action('a3 navigate navigator 106 65 8','A3_NAVIGATE 1'),
        snapshot('farm-before'),step(80),snapshot('farm-after')]
    # Same-shaped scope in a different dimension stays independent.
    first += [action('execute in minecraft:the_nether run forceload add 0 0 15 15','Marked',settleSeconds=2),
        action('execute in minecraft:the_nether run fill 0 64 0 8 64 8 minecraft:stone','Successfully filled'),
        action('execute in minecraft:the_nether run fill 0 65 0 8 68 8 minecraft:air','blocks')]
    for i in range(2):
        item=summon('zombie',3+i,65,3,['dimension']);item['command']='execute in minecraft:the_nether run '+item['command'];first.append(item)
    first += [snapshot('dimensions-before'),step(16),snapshot('dimensions-after')]
    # All vehicle work remains native. The moving pair is isolated from the dense cart cluster.
    first += [action('forceload add 192 -16 255 15','Marked',settleSeconds=2),
        action('execute if loaded 224 65 0 run say A3_RAIL_READY','A3_RAIL_READY',pollReadiness=True),
        action('fill 194 64 -4 254 64 4 minecraft:stone','Successfully filled'),
        action('fill 194 65 -4 254 68 4 minecraft:air','blocks'),
        action('fill 196 64 0 228 64 0 minecraft:redstone_block','Successfully filled'),
        action('fill 196 65 0 228 65 0 minecraft:powered_rail[shape=east_west,powered=true]','Successfully filled'),
        action('setblock 195 65 0 minecraft:stone','Changed the block'),
        action('setblock 229 65 0 minecraft:stone','Changed the block'),
        summon('minecart',204.5,65.1,0.5,['moving-cart'],'Motion:[0.2d,0d,0d]'),
        summon('pig',204.7,65.1,0.5,['rider']),
        action('fill 236 64 0 252 64 0 minecraft:redstone_block','Successfully filled'),
        action('fill 236 65 0 252 65 0 minecraft:powered_rail[shape=east_west,powered=true]','Successfully filled'),
        action('setblock 235 65 0 minecraft:stone','Changed the block'),
        action('setblock 253 65 0 minecraft:stone','Changed the block')]
    for i in range(16):first.append(summon('minecart',244.0+i*0.015,65.1,0.5,['dense-cart']))
    first += [snapshot('vehicles-before'),step(2),snapshot('pickup'),step(30),snapshot('vehicles-after')]
    # Actual hostile acquisition/chasing after the comparable no-target work window.
    first += [summon('iron_golem',8,65,4,['target'],'NoAI:1b'),
        action('effect give @e[tag=target] minecraft:resistance 60 4 true','Applied'),
        snapshot('ai-before'),step(24),snapshot('ai-after'),
        action('tp @e[tag=breeder] 112 65 4','Teleported'),
        action('execute as @e[tag=breeder] run data merge entity @s {InLove:600}','Modified entity data'),
        snapshot('breeding-before'),step(80),
        action('tag @e[type=minecraft:cow,tag=!a3,x=100,y=64,z=-12,dx=40,dy=8,dz=28] add newborn','Added tag'),
        action('tag @e[tag=newborn] add a3','Added tag'),snapshot('breeding-after'),
        snapshot('relocation-before'),action('tp @e[tag=mover] 116 65 10','Teleported'),step(2),snapshot('relocation-after')]
    # A separate bounded pen is far outside spawn tickets. Real entity unload/reload, no synthetic retirement.
    first += [action('forceload add 320 0 335 15','Marked',settleSeconds=2),
        action('execute if loaded 320 65 0 run say A3_UNLOAD_READY','A3_UNLOAD_READY',pollReadiness=True),
        action('fill 318 64 -2 325 64 5 minecraft:stone','Successfully filled'),
        action('fill 318 65 -2 325 68 5 minecraft:glass hollow','Successfully filled'),
        summon('cow',320.5,65,1.5,['unload']),summon('cow',322.5,65,1.5,['unload']),step(8),snapshot('unload-before'),
        action('save-all flush','Saved the game'),action('forceload remove 320 0 335 15','Unmarked'),
        action('execute unless entity @e[tag=unload] run say A3_UNLOADED','A3_UNLOADED',pollReadiness=True,timeout=90),
        snapshot('unloaded'),
        action('forceload add 320 0 335 15','Marked',settleSeconds=3),
        action('execute if loaded 320 65 0 run say A3_RELOAD_READY','A3_RELOAD_READY',pollReadiness=True),
        step(8),snapshot('reloaded'),snapshot('pre-stop')]
    restart=[action('tick freeze','The game is frozen'),
        action('execute if loaded 112 65 0 run say A3_RESTART_READY','A3_RESTART_READY',pollReadiness=True),
        action('say A3_RESTART_SETTLE','A3_RESTART_SETTLE',settleSeconds=3),snapshot('restart-before'),step(16),snapshot('restart-after'),
        action('kill @e[tag=a3]','Killed'),
        step(25),snapshot('cleanup')]
    return {'first':first,'restart':restart}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--workers',type=int,choices=[1,4],required=True)
    parser.add_argument('--port',type=int,default=25649)
    parser.add_argument('--enabled',choices=['true','false'],default='true')
    parser.add_argument('--smoke',action='store_true')
    parser.add_argument('--first-only',action='store_true',help='Partial workload only; cannot establish A3 acceptance.')
    parser.add_argument('--full-authoritative',choices=['true','false'],default='true',
        help='Also exercise authoritative FULL saved-chunk loading after the native startup boundary.')
    args=parser.parse_args()
    output=args.output.resolve()
    if output.exists():raise SystemExit('Refusing existing output '+str(output))
    output.mkdir(parents=True); server=output/'server';server.mkdir()
    for name in ['fabric-server-launch.jar','.fabric','libraries','versions','config']:
        source=TEMPLATE/name
        if source.is_dir():shutil.copytree(source,server/name)
        elif source.exists():shutil.copy2(source,server/name)
    (server/'mods').mkdir()
    for source in (TEMPLATE/'mods').glob('*.jar'):
        if source.name.startswith(('phoenium-','panda-a2-')):continue
        shutil.copy2(source,server/'mods'/source.name)
    production=ROOT/'build/libs/phoenium-0.2.0-beta.1.jar'
    shutil.copy2(production,server/'mods'/production.name)
    shutil.copy2(ROOT/'build/test-harness/a3-live-probe.jar',server/'mods/a3-live-probe.jar')
    props={'level-name':'a3-disposable','level-seed':'444444','level-type':'minecraft:flat','generator-settings':'{}',
        'online-mode':'false','enforce-secure-profile':'false','server-ip':'127.0.0.1','server-port':str(args.port),
        'enable-rcon':'false','difficulty':'normal','gamemode':'creative','view-distance':'2','simulation-distance':'2',
        'spawn-protection':'0','sync-chunk-writes':'true','pause-when-empty-seconds':'-1','max-players':'2'}
    (server/'server.properties').write_text(''.join(f'{k}={v}\n' for k,v in props.items()))
    (server/'eula.txt').write_text('eula=true\n')
    installed=inventory(server)
    hashes={item['sha256'] for item in installed}
    assert '8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53' in hashes
    assert '8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8' in hashes
    (output/'installed.json').write_text(json.dumps(installed,indent=2))
    workload=plan(args.smoke)
    if args.first_only: workload.pop('restart',None)
    for actions in workload.values():
        for item in actions:
            if item.get('pollReadiness'):
                item['command']=item['command'].replace('A3_', 'ATHREE_')
                item['expect']=item['expect'].replace('A3_', 'ATHREE_')
    (output/'plan.json').write_text(json.dumps(workload,indent=2))
    report={'status':'UNACCEPTED','workers':args.workers,'enabled':args.enabled,'productionSha256':sha(production),
        'fullAuthoritative':args.full_authoritative,
        'sourceHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'phases':[]}
    for phase,actions in workload.items():
        command=[shutil.which('java'),'-Xmx2G',f'-Dphoenium.full.authoritative={args.full_authoritative}','-Dphoenium.test.diagnostics=true',
            '-Dphoenium.stage3.explicitTopology=true','-Dphoenium.test.chunkFixtureSupport=true','-Dphoenium.stage3.chunkTrace=true',
            '-Dphoenium.stage4.candidate=true',f'-Dphoenium.gameplay.workers={args.workers}',
            f'-Dphoenium.protection.mobDecisions.enabled={args.enabled}','-Dphoenium.protection.mobDecisions.threshold=4',
            f'-Dphoenium.a3.evidence={output/phase/"native"}','-Dmixin.debug.export=true',
            '-jar','fabric-server-launch.jar','nogui']
        result=lifecycle(server,output,phase,command,actions,installed)
        report['phases'].append({k:v for k,v in result.items() if k not in ['metrics','startup','commands']})
        if result['status']!='PASS_CONSOLE_SAMPLE':break
    else:report['status']='PASS_NATIVE_WORKLOAD_PENDING_VALIDATION'
    (output/'report.json').write_text(json.dumps(report,indent=2))
    raise SystemExit(0 if report['status']=='PASS_NATIVE_WORKLOAD_PENDING_VALIDATION' else 1)

if __name__=='__main__':main()
