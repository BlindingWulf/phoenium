"""Stage 9 J19/J20: the Stage 7 A3 native workload with Phoenium in ENGINE mode (S9-E5 engine A3 port).

S9-E8 copy of a3live9e.py (that file is unchanged, so the D23 evidence stays reproducible): `--extra -Dname=value`
(repeatable) adds engine properties to both phases' command lines, e.g. the shipping schedule (-Dphoenium.engine.schedule=dag)
and the D26 self-checks (-Dphoenium.engine.protection.check=1); they are recorded in report.json (engineExtra). Validate with
gates/scripts/validate_a3_9e8.py (strict reobserve, protection faults 0, every membership at its mob's tile).

The workload is Phoenium/scripts/run_a3_live.py's plan() (imported, unchanged: dense hostile and passive scopes, an
independent scope, a Nether scope, carts, hostile acquisition, breeding, relocation, real unload/reload, restart) and the
server is driven by the same console lifecycle (run_stage6_console.lifecycle, via its engine copy console9e.py whose
stop check reads the engine stop line). Differences from run_a3_live.py, all
because engine mode has no owner regions: Phoenium is the given hashed candidate started with -Dphoenium.engine.tiles=true
and N tile threads instead of the Stage 3 explicit-topology flags; the A3 probe is the given test harness jar (built from
the same source, with the S9-E5 engine path); java is the pinned JDK 25. The static inputs are run_a3_live.py's TEMPLATE.

    python gates/scripts/a3live9e8.py --output <new dir> --candidate <jar> --probe <a3-live-probe.jar> --engine-workers N
                                      [--enabled true|false] [--port P] [--source-head <commit>] [--extra -Dphoenium.engine.x=y ...]

Validate with gates/scripts/validate_a3_9e8.py --enabled <on dir> --control <off dir>.
"""
import argparse, hashlib, json, pathlib, shutil, sys

SCRIPTS = pathlib.Path(r'C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium\scripts')
sys.path.insert(0, str(SCRIPTS))
import run_a3_live as A  # noqa: E402  (plan, TEMPLATE)
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from console9e import inventory, lifecycle  # noqa: E402  (engine stop check; see console9e.py)

JAVA = r'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe'
C2ME = '8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53'
SCALABLELUX = '8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8'


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest().upper()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', required=True, type=pathlib.Path)
    p.add_argument('--candidate', required=True, type=pathlib.Path)
    p.add_argument('--probe', required=True, type=pathlib.Path)
    p.add_argument('--engine-workers', type=int, required=True)
    p.add_argument('--enabled', choices=['true', 'false'], default='true')
    p.add_argument('--port', type=int, default=25649)
    p.add_argument('--source-head', default='')
    p.add_argument('--first-only', action='store_true', help='Partial workload only; cannot establish A3 acceptance.')
    p.add_argument('--extra', action='append', default=[], help='Extra -Dname=value engine property (repeatable).')
    args = p.parse_args()
    for extra in args.extra:
        if not extra.startswith('-Dphoenium.engine.'):
            raise SystemExit('--extra takes -Dphoenium.engine.* properties only: ' + extra)
    output = args.output.resolve()
    if output.exists():
        raise SystemExit('Refusing existing output ' + str(output))
    output.mkdir(parents=True)
    server = output / 'server'
    server.mkdir()
    for name in ['fabric-server-launch.jar', '.fabric', 'libraries', 'versions', 'config']:
        source = A.TEMPLATE / name
        if source.is_dir():
            shutil.copytree(source, server / name)
        elif source.exists():
            shutil.copy2(source, server / name)
    (server / 'mods').mkdir()
    for source in (A.TEMPLATE / 'mods').glob('*.jar'):
        if source.name.startswith(('phoenium-', 'panda-a2-')):
            continue
        shutil.copy2(source, server / 'mods' / source.name)
    candidate_sha = sha(args.candidate)
    shutil.copy2(args.candidate, server / 'mods' / ('phoenium-' + candidate_sha[:12].lower() + '.jar'))
    shutil.copy2(args.probe, server / 'mods/a3-live-probe.jar')
    props = {'level-name': 'a3-disposable', 'level-seed': '444444', 'level-type': 'minecraft:flat', 'generator-settings': '{}',
             'online-mode': 'false', 'enforce-secure-profile': 'false', 'server-ip': '127.0.0.1', 'server-port': str(args.port),
             'enable-rcon': 'false', 'difficulty': 'normal', 'gamemode': 'creative', 'view-distance': '2', 'simulation-distance': '2',
             'spawn-protection': '0', 'sync-chunk-writes': 'true', 'pause-when-empty-seconds': '-1', 'max-players': '2'}
    (server / 'server.properties').write_text(''.join(f'{k}={v}\n' for k, v in props.items()))
    (server / 'eula.txt').write_text('eula=true\n')
    installed = inventory(server)
    hashes = {item['sha256'] for item in installed}
    assert C2ME in hashes and SCALABLELUX in hashes
    assert sum(1 for item in installed if item['file'].startswith('phoenium-')) == 1
    (output / 'installed.json').write_text(json.dumps(installed, indent=2))
    workload = A.plan(False)
    if args.first_only:
        workload.pop('restart', None)
    for actions in workload.values():
        for item in actions:
            if item.get('pollReadiness'):
                item['command'] = item['command'].replace('A3_', 'ATHREE_')
                item['expect'] = item['expect'].replace('A3_', 'ATHREE_')
    (output / 'plan.json').write_text(json.dumps(workload, indent=2))
    report = {'status': 'UNACCEPTED', 'mode': 'engine-tiles', 'workers': args.engine_workers, 'enabled': args.enabled,
              'productionSha256': candidate_sha, 'candidate': str(args.candidate), 'probeSha256': sha(args.probe),
              'sourceHead': args.source_head, 'engineExtra': args.extra, 'phases': []}
    for phase, actions in workload.items():
        command = [JAVA, '-Xmx2G', '-Dphoenium.test.diagnostics=true', '-Dphoenium.engine.tiles=true',
                   f'-Dphoenium.engine.workers={args.engine_workers}', *args.extra,
                   f'-Dphoenium.protection.mobDecisions.enabled={args.enabled}', '-Dphoenium.protection.mobDecisions.threshold=4',
                   f'-Dphoenium.a3.evidence={output / phase / "native"}', '-Dmixin.debug.export=true',
                   '-Djava.net.useSystemProxies=false', '-Dminecraft.api.session.host=http://127.0.0.1:9',
                   '-Dminecraft.api.services.host=http://127.0.0.1:9',
                   '-Dminecraft.api.profiles.host=http://127.0.0.1:9', '-jar', 'fabric-server-launch.jar', 'nogui']
        result = lifecycle(server, output, phase, command, actions, installed)
        report['phases'].append({k: v for k, v in result.items() if k not in ['metrics', 'startup', 'commands']})
        log = (output / (phase + '.log')).read_text(encoding='utf-8', errors='replace')
        report['phases'][-1]['engineStart'] = next((l for l in log.splitlines() if 'PHOENIUM_STAGE2 started' in l), None)
        report['phases'][-1]['engineStop'] = next((l for l in log.splitlines() if 'PHOENIUM_STAGE2 stopped' in l), None)
        # 26.2 authlib ignores the host redirect unless services, session AND profiles are all set; check the effective
        # environment the server logged, not the flags (until 2026-10-18 the profiles host was missing and PROD was used).
        report['phases'][-1]['mojangHostsRedirected'] = 'Environment[sessionHost=http://127.0.0.1:9' in log
        if not report['phases'][-1]['mojangHostsRedirected']:
            report['status'] = 'FAIL_NETWORK_ISOLATION'
            break
        if result['status'] != 'PASS_CONSOLE_SAMPLE':
            break
    else:
        report['status'] = 'PASS_NATIVE_WORKLOAD_PENDING_VALIDATION'
    (output / 'report.json').write_text(json.dumps(report, indent=2))
    raise SystemExit(0 if report['status'] == 'PASS_NATIVE_WORKLOAD_PENDING_VALIDATION' else 1)


if __name__ == '__main__':
    main()
