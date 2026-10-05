"""Differential comparison of two headless census runs (usually vanilla control vs the Phoenium candidate).

Usage: python scripts/stage7/compare_census.py <vanilla-first-result.json> <phoenium-first-result.json> [--out report.json]
Prints per-outcome agreement and every card where the two disagree. A disagreement is a lead, not yet a verdict:
RNG-driven entities (a mob wandering out of the cell) differ between any two runs of the same server.
"""
import json
import sys
import collections

vanilla = {c['card']: c for c in json.load(open(sys.argv[1], encoding='utf-8'))['cards']}
phoenium = {c['card']: c for c in json.load(open(sys.argv[2], encoding='utf-8'))['cards']}
common = sorted(set(vanilla) & set(phoenium))
table = collections.Counter((vanilla[c]['outcome'], phoenium[c]['outcome']) for c in common)
print('cards compared:', len(common), 'only vanilla:', len(set(vanilla) - set(phoenium)), 'only phoenium:', len(set(phoenium) - set(vanilla)))
for (v, p), n in sorted(table.items()): print(f'  vanilla={v:12} phoenium={p:12} {n}')
diff = [(c, vanilla[c]['outcome'], phoenium[c]['outcome']) for c in common if vanilla[c]['outcome'] != phoenium[c]['outcome']]
print('DISAGREEMENTS', len(diff))
for c, v, p in diff: print(f'  {c}: vanilla={v} phoenium={p}')
if '--out' in sys.argv:
    json.dump(dict(compared=len(common), table={f'{v}/{p}': n for (v, p), n in table.items()}, disagreements=diff),
              open(sys.argv[sys.argv.index('--out') + 1], 'w'), indent=1)
