"""Compare native vehicle/navigation instructions with A3 enabled and disabled.

Only Mixin's generated handler identifiers are normalized; instructions, query
calls, control flow, constants, and native/Lithium method names remain intact.
The loaded old-cart implementation is compared live; both native cart variants
are also characterized by A3NativeBoundaryTest against the pinned game jar.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

CLASSES = [
    'vehicle/boat/AbstractBoat',
    'vehicle/boat/AbstractChestBoat',
    'vehicle/minecart/AbstractMinecart',
    'vehicle/minecart/AbstractMinecartContainer',
    'vehicle/minecart/OldMinecartBehavior',
    'ai/navigation/PathNavigation',
]


def instructions(root, name):
    path = root / 'server/.mixin.out/class/net/minecraft/world/entity' / (name + '.class')
    assert path.is_file(), path
    raw = subprocess.check_output([shutil.which('javap'), '-p', '-c', str(path)], text=True)
    assert 'dev/phoenium/live/MobDecision' not in raw and 'inactiveAcquisition' not in raw, name
    # Identifiers assigned by Mixin depend on which other mixins were selected.
    normalized = re.sub(r'md[0-9a-f]{6}\$', 'mdHASH$', raw)
    normalized = re.sub(r'handler\$[a-z]{3}[0-9]{3}\$phoenium-a3-harness',
                        'handler$ID$phoenium-a3-harness', normalized)
    return raw, normalized


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--enabled-run', type=Path, required=True)
    parser.add_argument('--control-run', type=Path, required=True)
    args = parser.parse_args()
    results = []
    for name in CLASSES:
        active, normalized = instructions(args.enabled_run, name)
        control, expected = instructions(args.control_run, name)
        assert normalized == expected, 'Native instructions differ: ' + name
        if name.endswith('OldMinecartBehavior'):
            assert 'lithium' in active and 'getPushableEntities' in active
        if name.endswith('PathNavigation'):
            assert 'lithium$setNavigationActive' in active and 'lithium$setNavigationInactive' in active
        results.append(dict(className=name, identicalNormalizedInstructions=True,
                            sha256=hashlib.sha256(normalized.encode()).hexdigest().upper(),
                            lithiumPresent='lithium' in active))
    report = dict(status='PASS', enabledRun=str(args.enabled_run), controlRun=str(args.control_run),
                  normalization='Mixin-generated identifiers only', classes=results)
    output = args.enabled_run / 'vehicle-export-audit.json'
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
