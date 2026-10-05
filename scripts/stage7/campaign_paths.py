"""Shared audit-root locations for the Stage 7 runners.

BASE_AUDIT is the preserved historical evidence root (read-only inputs: seeds, plans, the
v2 census). AUDIT is where new runs write; it defaults to the continuation root and can be
redirected with PHOENIUM_STAGE7_AUDIT. Runners never write into BASE_AUDIT.
"""
from __future__ import annotations

import os
from pathlib import Path

WORK = Path(__file__).resolve().parents[2]
ROOT = WORK.parent.parent
BASE_AUDIT = ROOT / '.audit' / 'sol-ultra-final-campaign-20260923'
AUDIT = Path(os.environ.get('PHOENIUM_STAGE7_AUDIT',
                            str(ROOT / '.audit' / 'sonnet-stage7-closeout-20261004')))
