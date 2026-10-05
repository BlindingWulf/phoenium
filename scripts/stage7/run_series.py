"""Run installed cards back to back, each in its own fresh namespace, and summarize.

Usage: python scripts/stage7/run_series.py --prefix <tag> --workers 1,4 --candidate <jar> \
           --run "<group>:<card1,card2>[:explicit]" [--run ...]

Every (run spec x worker) gets the name <prefix>-<index>-w<workers>. A failed run is preserved and the
series continues unless --stop-on-fail is given. The summary lists pass/fail per run with its first failure.
"""
import argparse
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from campaign_paths import AUDIT, WORK  # noqa: E402

parser = argparse.ArgumentParser()
parser.add_argument('--prefix', required=True)
parser.add_argument('--workers', default='1,4')
parser.add_argument('--candidate', default='', help='default: the freshly built jar, staged under a hashed name')
parser.add_argument('--run', action='append', required=True)
parser.add_argument('--stop-on-fail', action='store_true')
args = parser.parse_args()
if not args.candidate:
    import hashlib, shutil
    built = WORK / 'build' / 'libs' / 'phoenium-0.2.0-stage2.jar'
    digest = hashlib.sha256(built.read_bytes()).hexdigest()
    staged = AUDIT / 'candidate' / f'phoenium-dev-{digest[:12]}.jar'
    staged.parent.mkdir(parents=True, exist_ok=True)
    if not staged.exists():
        shutil.copy2(built, staged)
    args.candidate = str(staged)
print('CANDIDATE', args.candidate, flush=True)

summary = []
for index, spec in enumerate(args.run):
    group, _, rest = spec.partition(':')
    cards, _, flags = rest.partition(':')
    for workers in args.workers.split(','):
        name = f'{args.prefix}-{index}-w{workers}'
        command = [sys.executable, '-u', str(HERE / 'run_installed.py'), name, workers, f'--group={group}',
                   f'--candidate={args.candidate}']
        if cards:
            command.append(f'--cards={cards}')
        if 'explicit' in flags.split(','):
            command += ['--explicit-topology', '--explicit-separate-b=60']
        print('RUN', name, group, cards[:80], flush=True)
        log = AUDIT / 'logs' / f'{name}.out'
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open('wb') as out:
            code = subprocess.call(command, cwd=WORK, stdout=out, stderr=subprocess.STDOUT)
        result_path = AUDIT / 'installed' / name / 'first-result.json'
        result = json.loads(result_path.read_text()) if result_path.is_file() else {}
        receipt_path = AUDIT / 'installed' / name / 'first-client-receipt.json'
        receipt = json.loads(receipt_path.read_text()) if receipt_path.is_file() else {}
        row = {'name': name, 'group': group, 'workers': workers, 'exit': code, 'pass': bool(result.get('pass')),
               'cardsPassed': len(receipt.get('cards', [])), 'failedCard': receipt.get('failedCard'),
               'failure': (receipt.get('failure') or result.get('failure') or '')[:300],
               'violations': (result.get('txStatus') or {}).get('violations'),
               'pending': (result.get('txStatus') or {}).get('pending')}
        summary.append(row)
        print(json.dumps(row), flush=True)
        if not row['pass'] and args.stop_on_fail:
            break
(AUDIT / f'{args.prefix}-series.json').write_text(json.dumps(summary, indent=2))
print('SERIES', 'PASS' if all(r['pass'] for r in summary) else 'FAIL', flush=True)
sys.exit(0 if all(r['pass'] for r in summary) else 1)
