"""Run a command with extra environment variables, without cmd.exe quoting (paths with spaces survive).

    python envrun.py NAME=value [NAME=value ...] -- <program> <args...>

Exits with the child's exit code. Used for testsched jobs that need S8_CANDIDATE (2026-10-14).
"""
import os, subprocess, sys

if '--' not in sys.argv:
    sys.exit(__doc__)
cut = sys.argv.index('--')
env = dict(os.environ)
for kv in sys.argv[1:cut]:
    k, sep, v = kv.partition('=')
    if not sep or not k:
        sys.exit(f'bad NAME=value: {kv!r}')
    env[k] = v
cmd = sys.argv[cut + 1:]
if not cmd:
    sys.exit(__doc__)
sys.exit(subprocess.call(cmd, env=env))
