"""Tally Gradle JUnit XML results: suites, tests, skipped, failures, errors (plus skipped names)."""
import glob
import re
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'build/test-results/test')
suites = tests = skipped = failures = errors = 0
skipped_names = []
for path in sorted(glob.glob(str(root / 'TEST-*.xml'))):
    text = Path(path).read_text(encoding='utf-8')
    head = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', text[:800])
    if not head:
        continue
    suites += 1
    tests += int(head[1]); skipped += int(head[2]); failures += int(head[3]); errors += int(head[4])
    for case in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<skipped', text):
        skipped_names.append(f'{case[2]}.{case[1]}')
print(f'suites={suites} tests={tests} skipped={skipped} failures={failures} errors={errors}')
for name in skipped_names:
    print('  skipped:', name)
sys.exit(1 if failures or errors else 0)
