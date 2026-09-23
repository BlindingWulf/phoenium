"""Serial installed W1/W4 groups with exact-byte resume and an optional completion gate.

Every group delegates to run-installed.py, which owns and normally stops its own server.
Failed instances remain preserved. Choose a new --name after repairing a failure.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys

SCRIPT_PATH = pathlib.Path(__file__).resolve()
WORK = (SCRIPT_PATH.parents[2] if SCRIPT_PATH.parent.name == 'stage7' else
        SCRIPT_PATH.parents[2] / '.candidate-worktrees' / 'stage7-final-sol-ultra')
ROOT = WORK.parent.parent
AUDIT = ROOT / '.audit' / 'sol-ultra-final-campaign-20260923'
RUNNER = WORK / 'scripts' / 'stage7' / 'run_installed.py'
CLIENT_SOURCE = WORK / 'src/clientGameTest/java/dev/phoenium/clienttest/Stage7RepairClientTest.java'
RESTART = {'legacy', 'end-falling', 'bed-anchor'}
DEFAULT_GROUPS = ('end-falling', 'selection-holes', 'merchant-holes', 'armor-stand', 'legacy', 'bed-anchor')
CARD_PATTERN = re.compile(r'[A-Za-z0-9:_-]+')

def planned_no_entry_card(card):
    plan_path = AUDIT / 'NO_NATIVE_ENTRY_MATRIX_PLAN.json'
    if not plan_path.is_file():
        return False
    plan = json.loads(plan_path.read_text(encoding='utf-8'))
    return plan.get('caseCount') == 60 and any(
        item.get('card') == card and item.get('fixtureState') == 'PLANNED'
        for item in plan.get('cards', []))

def available_groups():
    source = CLIENT_SOURCE.read_text(encoding='utf-8')
    # Unknown names enter the client's legacy `else` branch. Discover explicit
    # branches so an invalid group never silently runs legacy cards.
    return {'legacy'} | set(re.findall(r'group\.equals\("([^"]+)"\)', source))

def validate_cards(cards, groups):
    if not cards:
        return []
    selected = cards.split(',')
    if len(groups) != 1 or not selected or len(set(selected)) != len(selected) \
            or any(CARD_PATTERN.fullmatch(card) is None for card in selected):
        raise ValueError('--cards requires one group and unique comma-separated card IDs')
    source = CLIENT_SOURCE.read_text(encoding='utf-8')
    gradle = (WORK / 'scripts/stage7-repair-client.gradle').read_text(encoding='utf-8')
    if 'phoenium.repair.cards' not in source or "'cards'" not in gradle:
        raise RuntimeError('--cards unavailable: client harness does not implement the selection property')
    exact_pairs = set(re.findall(r'group\.equals\("([^"]+)"\)\s*&&\s*selectedCards\.equals\("([^"]+)"\)', source))
    dynamic_no_entry = (groups[0] == 'no-native-entry'
                        and 'noNativeEntryMatrix(selectedCards)' in source
                        and 'selectedCards.matches("N0-' in source
                        and len(selected) == 1 and planned_no_entry_card(selected[0]))
    dynamic_arrow = (groups[0] == 'arrow-trident'
                     and 'selectedCards.matches("P2-' in source
                     and 'arrowTridentMatrix(selectedCards)' in source
                     and len(selected) == 1
                     and re.fullmatch(r'P2-(arrow|spectral_arrow|trident)-(NETHER_ENTRY|NETHER_RETURN)',
                                      selected[0]) is not None)
    dynamic_vehicle = (groups[0] == 'vehicle-empty'
                       and 'selectedCards.matches("V0-' in source
                       and 'emptyVehicleMatrix(selectedCards)' in source
                       and len(selected) == 1
                       and re.fullmatch(r'V0-(acacia_boat|birch_boat|cherry_boat|dark_oak_boat|jungle_boat|mangrove_boat|oak_boat|pale_oak_boat|spruce_boat|bamboo_raft|minecart)-(NETHER_ENTRY|NETHER_RETURN)',
                                        selected[0]) is not None)
    dynamic_chest_vehicle = (groups[0] == 'vehicle-chest'
                             and 'selectedCards.matches("V1-' in source
                             and 'chestVehicleMatrix(selectedCards)' in source
                             and len(selected) == 1
                             and re.fullmatch(r'V1-(acacia_chest_boat|birch_chest_boat|cherry_chest_boat|dark_oak_chest_boat|jungle_chest_boat|mangrove_chest_boat|oak_chest_boat|pale_oak_chest_boat|spruce_chest_boat|bamboo_chest_raft)-items-(NETHER_ENTRY|NETHER_RETURN)|V1-(oak_chest_boat|bamboo_chest_raft)-loot-(NETHER_ENTRY|NETHER_RETURN)',
                                              selected[0]) is not None)
    dynamic_chest_minecart = (groups[0] == 'vehicle-minecart-chest'
                              and 'selectedCards.matches("V2-chest_minecart-' in source
                              and 'chestMinecartMatrix(selectedCards)' in source
                              and len(selected) == 1
                              and re.fullmatch(r'V2-chest_minecart-(items|loot)-(NETHER_ENTRY|NETHER_RETURN)',
                                               selected[0]) is not None)
    if len(selected) != 1 or ((groups[0], selected[0]) not in exact_pairs
                              and not dynamic_no_entry and not dynamic_arrow and not dynamic_vehicle
                              and not dynamic_chest_vehicle and not dynamic_chest_minecart):
        raise RuntimeError('--cards unavailable: requested card has no exact client selection branch')
    return selected

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--name', required=True, help='Unique output namespace; an existing failed instance is never reused')
    parser.add_argument('--candidate', type=pathlib.Path, required=True)
    parser.add_argument('--workers', default='1,4', help='Comma-separated 1 and/or 4')
    parser.add_argument('--groups', default=','.join(DEFAULT_GROUPS), help='Comma-separated installed group names')
    parser.add_argument('--cards', help='Exact comma-separated card IDs in one group; requires client card selection support')
    parser.add_argument('--seed', type=int, default=2704)
    parser.add_argument('--resume', action='store_true', help='Skip only successful same-hash phases')
    parser.add_argument('--gate', action='store_true', help='Run the complete pinned coverage gate after these groups')
    args = parser.parse_args()
    candidate = args.candidate.resolve(strict=True)
    candidate_sha = sha(candidate)
    workers = [int(item) for item in args.workers.split(',')]
    groups = args.groups.split(',')
    assert workers and all(worker in (1, 4) for worker in workers)
    assert groups and len(set(groups)) == len(groups) and all(group in available_groups() for group in groups)
    assert args.name and all(char.isalnum() or char in '-_' for char in args.name)
    selected_cards = validate_cards(args.cards, groups)
    if selected_cards and groups[0] in RESTART:
        raise ValueError('--cards currently supports single-phase groups only; restart groups need phase-specific card selection')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=WORK, text=True).strip()
    summary = {'name': args.name, 'candidate': str(candidate), 'candidateSha256': candidate_sha,
               'head': head, 'workers': workers, 'groups': groups, 'cards': selected_cards,
               'seed': args.seed, 'phases': []}
    for worker in workers:
        for group in groups:
            run_name = f'{args.name}-{group}-w{worker}'
            run_dir = AUDIT / 'installed' / run_name
            for phase in ('first', 'restart') if group in RESTART else ('first',):
                receipt = run_dir / f'{phase}-result.json'
                if receipt.exists():
                    result = json.loads(receipt.read_text())
                    inputs = json.loads((run_dir / 'inputs.json').read_text())
                    if not args.resume or not result.get('pass') or result.get('candidateSha256') != candidate_sha \
                            or inputs.get('head') != head or inputs.get('group') != group or inputs.get('seed') != args.seed \
                            or inputs.get('cards', []) != selected_cards:
                        raise RuntimeError(f'Existing phase cannot be reused: {receipt}')
                    print('RESUME', run_name, phase, 'PASS', flush=True)
                else:
                    if phase == 'first' and run_dir.exists():
                        raise RuntimeError(f'Incomplete or failed instance preserved: {run_dir}')
                    command = [sys.executable, '-u', str(RUNNER), run_name, str(worker),
                               '--group=' + group, '--seed=' + str(args.seed)]
                    if selected_cards:
                        command.append('--cards=' + ','.join(selected_cards))
                    if phase == 'first':
                        command.append('--candidate=' + str(candidate))
                    else:
                        command.append('--restart')
                    print('RUN', run_name, phase, flush=True)
                    output = run_dir / f'{phase}-sweep.log'
                    # The runner creates the instance directory for first phase. Keep the
                    # outer log beside it only after it returns; do not pre-create the run.
                    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
                    if not run_dir.exists():
                        preflight_log = AUDIT / f'{run_name}-{phase}-preflight.log'
                        preflight_log.write_text(completed.stdout + completed.stderr, encoding='utf-8')
                        print('FAIL', run_name, phase, 'preflight', str(preflight_log), flush=True)
                        summary['phases'].append({'run': run_name, 'phase': phase, 'pass': False,
                                                  'preflightLog': str(preflight_log)})
                        (AUDIT / (args.name + '-sweep-results.json')).write_text(json.dumps(summary, indent=2) + '\n')
                        return completed.returncode or 1
                    output.write_text(completed.stdout + completed.stderr)
                    if completed.returncode:
                        print('FAIL', run_name, phase, str(receipt), flush=True)
                        summary['phases'].append({'run': run_name, 'phase': phase, 'pass': False, 'result': str(receipt)})
                        (AUDIT / (args.name + '-sweep-results.json')).write_text(json.dumps(summary, indent=2) + '\n')
                        return completed.returncode
                    result = json.loads(receipt.read_text())
                    observed_cards = [card['card'] for card in json.loads((run_dir / f'{phase}-client-receipt.json').read_text())['cards']]
                    if selected_cards and set(observed_cards) != set(selected_cards):
                        raise RuntimeError(f'Selected cards did not match completed cards: {selected_cards} vs {observed_cards}')
                    print('PASS', run_name, phase, 'cards', len(observed_cards), flush=True)
                summary['phases'].append({'run': run_name, 'phase': phase, 'pass': True, 'result': str(receipt)})
                (AUDIT / (args.name + '-sweep-results.json')).write_text(json.dumps(summary, indent=2) + '\n')
    if args.gate:
        oracle = ROOT / '.candidate-worktrees' / 'respawn-lifecycle-repair' / '.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2'
        command = [sys.executable, str(WORK / 'scripts/stage7/build_portal_coverage.py'),
                   '--census', str(AUDIT / 'PORTAL_BEHAVIOR_CENSUS_V2.json'),
                   '--frozen', str(ROOT / '.audit/sol-ultra-portal-respawn-20260922-192748/PORTAL_REQUIRED_CASES.json'),
                   '--candidate', str(candidate),
                   '--native-source', str(oracle / 'minecraft-merged-043a8b3edf-26.2-sources.jar'),
                   '--mapped-binary', str(oracle / 'minecraft-merged-043a8b3edf-26.2.jar'),
                   '--worktree', str(WORK), '--installed-root', str(AUDIT / 'installed'),
                   '--output', str(AUDIT / 'PORTAL_COVERAGE_V2.json'), '--gate']
        completed = subprocess.run(command, cwd=ROOT)
        return completed.returncode
    return 0

if __name__ == '__main__':
    sys.exit(main())
