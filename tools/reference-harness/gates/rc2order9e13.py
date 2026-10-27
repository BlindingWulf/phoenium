"""Stage 9 S9-E13 (R-C2) gate 1: per-connection packet order with the move split. Extracted from drafts/gate-draft-rc2.md
section 2 (written for D32). D34+: causality is a hard check in every arm (F1 fixed by moveSplit.early); --allow-divergence
turns it back into information (only for a deliberate run with -Dphoenium.engine.moveSplit.early=false).

R-C2 defers a move packet's ChunkMap.move to the end of the frame's queued packets; handleMovePlayer itself (position,
fall damage, stats) stays inline. This gate checks every per-connection order the server can observe:
  ledger     each bot: hop 8 blocks (raw position) -> select hotbar slot k%9 -> place on ledger cell k, one TCP write order;
             cell k is reachable only from hop k and has the right wool only if the slot came between use k-1 and use k
  fifo       chat 's9q <name> n' every 4th hop, '/scoreboard players set @s s9seq n' every 8th hop (task queue, after the
             flush: a regression guard; chat/commands never run inside the drain)
  approach   observer-side causality: a mover crossing the 96-block range swings in the same write; vanilla shows the
             observer spawn -> animation (in) and no animation before removal (out); D32 sent it to the pre-flush seenBy (F1)
  audits     pairing / ghosts / stale ids at quiescence, players and a 7x4 armor-stand grid
Arms: --arm=check (default) | split | vanilla; causality is hard unless --allow-divergence.

    set S8_CANDIDATE=<hashed jar>
    python gates/scripts/rc2order9e13.py <name> <engine-workers> <base-port> [--arm=...] [--require-causality] [-D...]
Results: .audit/stage9-20261009/gates/rc2-order/<name>/rc2-order-result.json
"""
import re, sys

sys.path.insert(0, r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage9-20261009\gates\scripts')
import rc2common9e13 as C  # noqa: E402

g = C.Gate('rc2-order', 'stage9-engine-rc2-order-v1')
S = C.SURFACE
WOOL = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray']
ROUNDS, CELLS, STEP = 6, 40, 8
DS = [124, 116, 108, 100, 92, 84, 76, 68, 60]            # the 96-block range is crossed between 100 and 92
OBS = [(-1111.5 + 320 * j, 1032.5) for j in range(8)]    # chunk centres 320 apart: nobody sees another observer's movers
SIDES = (('x', 1), ('x', -1), ('z', 1))
STANDS = [[x + 0.5, S, z + 0.5] for x in range(-144, 145, 48) for z in range(-420, -259, 48)]
TEMPLATE = {1: -440, -1: -444}

plan = C.new_plan()
for ox, oz in OBS:
    o = C.add(plan, {'observer': True, 'home': [ox, S, oz]})
    for axis, side in SIDES:
        home = [ox + side * DS[0], S, oz] if axis == 'x' else [ox, S, oz + side * DS[0]]
        C.add(plan, {'mover': True, 'home': home, 'approach': {
            'ox': ox, 'oz': oz, 'y': S, 'axis': axis, 'side': side, 'ds': DS, 'range': C.RANGE, 'periodMs': 250,
            'pauseMs': 1500, 'rounds': ROUNDS, 'observer': o['name']}})
for b in plan['bots']:
    d = 1 if b['index'] % 2 == 0 else -1
    b['lane'] = {'x0': -156 * d, 'dir': d, 'step': STEP, 'cells': CELLS, 'y': S, 'z': -400 + 4 * b['index'], 'periodMs': 250,
                 'cmd': True}
NAMES = [b['name'] for b in plan['bots']]


def causality(all_rows, t0):
    """Observer-side outcome of every crossing after t0 (join-time pairings at world spawn are excluded): paired / removed
    in time, animation delivered / extra."""
    ids = C.last_ids(all_rows)
    rows = [r for r in all_rows if r['ts'] >= t0]
    by_bot = C.by_bot(rows)
    out = {'in': 0, 'inSeen': 0, 'inAnimDelivered': 0, 'out': 0, 'outUnseen': 0, 'outAnimBeforeRemoval': 0, 'missing': []}
    pair_counts = {}
    for c in (r for r in rows if r['event'] == 'cross'):
        obs, mover, t = c['observer'], c['bot'], c['ts']
        window = [e for e in by_bot.get(obs, []) if t - 50 <= e['ts'] <= t + 3000]
        if c['leg'] == 'in':
            out['in'] += 1
            see = next((e for e in window if e['event'] == 'see' and (e.get('who') == mover or e.get('id') == ids.get(mover))), None)
            if see is None:
                out['missing'].append(('in', obs, mover, c['r']))
                continue
            out['inSeen'] += 1
            out['inAnimDelivered'] += any(e['event'] == 'anim' and e['id'] == see['id'] and e['ts'] >= see['ts'] for e in window)
        else:
            out['out'] += 1
            gone = next((e for e in window if e['event'] == 'unsee' and e['id'] == ids.get(mover)), None)
            if gone is None:
                out['missing'].append(('out', obs, mover, c['r']))
                continue
            out['outUnseen'] += 1
            out['outAnimBeforeRemoval'] += any(e['event'] == 'anim' and e['id'] == gone['id'] and t - 50 <= e['ts'] <= gone['ts']
                                               for e in window)
    for spec in plan['bots']:
        if 'approach' not in spec:
            continue
        obs, mover = spec['approach']['observer'], spec['name']
        evs = [e for e in by_bot.get(obs, []) if e['event'] in ('see', 'unsee')
               and (e.get('who') == mover or e.get('id') == ids.get(mover))]
        pair_counts[f'{obs}<-{mover}'] = (sum(e['event'] == 'see' for e in evs), sum(e['event'] == 'unsee' for e in evs))
    return out, pair_counts


try:
    g.boot()
    g.forceload(-176, -448, 175, -257)
    g.clear_box(-170, -446, 170, -262)
    for k in range(CELLS):
        g.rc(f'setblock {-156 + STEP * k} {S} {TEMPLATE[1]} minecraft:{WOOL[k % 9]}_wool', 'Changed the block')
        g.rc(f'setblock {156 - STEP * k} {S} {TEMPLATE[-1]} minecraft:{WOOL[k % 9]}_wool', 'Changed the block')
    for ox, oz in OBS:                     # lanes force-loaded only while they are cleared (the players load them later)
        g.forceload(int(ox) - 130, int(oz) - 2, int(ox) + 130, int(oz) + 130)
        g.clear_box(int(ox) - 130, int(oz) - 2, int(ox) + 130, int(oz) + 2)
        g.clear_box(int(ox) - 2, int(oz), int(ox) + 2, int(oz) + 130)
        g.unforce(int(ox) - 130, int(oz) - 2, int(ox) + 130, int(oz) + 130)
    for x, y, z in STANDS:
        g.summon('armor_stand', x, y, z, 'NoGravity:1b,Invulnerable:1b,Tags:["s9stand"]')
    g.rc('scoreboard objectives add s9seq dummy', 'Created|already')
    g.join(plan, op=True)
    for name in NAMES:
        for k, colour in enumerate(WOOL):
            g.rc(f'item replace entity {name} hotbar.{k} with minecraft:{colour}_wool 64', 'Replaced')
    C.wait(10)                                                      # every mover's chunks loaded around its start
    before = g.invariants('setup')

    # ---------------------------------------------------------------------------------------------- approach
    t_app = C.now_ms()
    g.bots.send('approach', ROUNDS * 2 * (len(DS) * 0.25 + 1.5) + 90)
    C.wait(3)
    rows = g.bots.events()
    cz, pairs = causality(rows, t_app)
    g.ev['approach'] = {k: v for k, v in cz.items() if k != 'missing'}
    g.ev['approachPairs'] = pairs
    expected = 24 * ROUNDS
    g.check('approach: every mover crossed the range 2 x rounds times', cz['in'] == expected and cz['out'] == expected,
            (cz['in'], cz['out'], expected))
    g.check('approach: every in-crossing paired the observer, every out-crossing removed it (3 s)',
            not cz['missing'] and cz['inSeen'] == cz['in'] and cz['outUnseen'] == cz['out'], cz['missing'][:20] or None)
    g.check('approach: never paired beyond range, never left paired (see/unsee counts == crossings)',
            all(s == ROUNDS and u == ROUNDS for s, u in pairs.values()), {k: v for k, v in pairs.items() if v != (ROUNDS, ROUNDS)} or None)
    rate_in = cz['inAnimDelivered'] / max(1, cz['inSeen'])
    rate_out = cz['outAnimBeforeRemoval'] / max(1, cz['outUnseen'])
    verdict = 'VANILLA' if rate_in >= 0.9 and rate_out <= 0.1 else 'DIVERGES'
    g.info['causality'] = {'inDeliveredRate': round(rate_in, 3), 'outExtraRate': round(rate_out, 3), 'verdict': verdict,
                           'expected': 'VANILLA'}                   # D34+: the early flush restores vanilla order
    if '--allow-divergence' not in g.flags or g.arm == 'vanilla':   # the vanilla arm calibrates the probe
        g.check('approach: per-connection causality (move pairs before the same drain\'s swing)', verdict == 'VANILLA',
                g.info['causality'])
    g.audit('approach', stands=STANDS)

    # ---------------------------------------------------------------------------------------------- ledger
    for b in plan['bots']:
        g.rc(f'tp {b["name"]} {b["lane"]["x0"] + 0.5} {S} {b["lane"]["z"] + 0.5}', 'Teleported')
    C.wait(8)
    mark = len(g.s.lines)
    before = g.diag('ledger-before')
    g.bots.send('ledger', CELLS * 0.25 + 90)
    C.wait(4)
    wrong = []
    for b in plan['bots']:
        lane = b['lane']
        zc = lane['z'] + 1
        reply = g.rc(f'execute if blocks -156 {S} {zc} 156 {S} {zc} -156 {S} {TEMPLATE[lane["dir"]]} all')
        if 'Test passed' not in reply:
            bad = []
            for k in range(CELLS):
                x = lane['x0'] + lane['dir'] * STEP * k
                if 'Test passed' not in g.rc(f'execute if block {x} {S} {zc} minecraft:{WOOL[k % 9]}_wool'):
                    bad.append(k)
            wrong.append((b['name'], bad[:10], len(bad)))
    g.check('ledger: every cell placed with the slot selected just before it (move -> slot -> use order, 1280 cells)',
            not wrong, wrong[:10] or None)
    lines = g.s.lines[mark:]
    chats, cmds = {n: [] for n in NAMES}, {n: [] for n in NAMES}
    for line in lines:
        m = re.search(r'<(S9R\d{3})> s9q (S9R\d{3}) (\d+)', line)
        if m and m.group(1) == m.group(2):
            chats[m.group(1)].append(int(m.group(3)))
        m = re.search(r'\[(S9R\d{3}): Set \[s9seq\] for (S9R\d{3}) to (\d+)\]', line)
        if m and m.group(1) == m.group(2):
            cmds[m.group(1)].append(int(m.group(3)))
    bad_chat = {n: v for n, v in chats.items() if v != list(range(CELLS // 4))}
    bad_cmd = {n: v for n, v in cmds.items() if v != list(range(CELLS // 8))}
    g.check('fifo: chat per connection complete and in order', not bad_chat, dict(list(bad_chat.items())[:6]) or None)
    g.check('fifo: commands per connection complete and in order', not bad_cmd, dict(list(bad_cmd.items())[:6]) or None)
    after = g.invariants('ledger', before)
    g.ev['ledgerDeferred'] = C.delta(after, before, 'moveSplitDeferred')
    g.ev['ledgerParallelFlushes'] = C.delta(after, before, 'moveSplitParallel')
    g.ev['ledgerRechecked'] = C.delta(after, before, 'moveSplitRechecked')
    g.audit('ledger', stands=STANDS)
except Exception as e:  # noqa: BLE001
    g.fail(e)
finally:
    g.finish(min_checks=25)
