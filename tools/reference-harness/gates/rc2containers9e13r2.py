"""Stage 9 S9-E13 (R-C2) gate 4: containers with the move split. Extracted from drafts/gate-draft-rc2.md section 5
(written for D32); D34+ arm evidence (moveSplitEarly) in rc2common9e13.finish.

Survival, peaceful. 24 chest bots open chests while walking (open + window clicks in the same drain as deferred moves),
8 of the 16 chests shared by two viewers, then walk away until the server closes the menu; 8 droppers drop and pick up
cobblestone while walking. Hard: every open, exact per-type totals, 0 single-viewer resyncs, every walk-away closed by
the server at the stillValid distance, cobblestone conserved, common audits and counters.

    set S8_CANDIDATE=<hashed jar>
    python gates/scripts/rc2containers9e13r2.py <name> <engine-workers> <base-port> [--arm=...] [-D...]
Results: .audit/stage9-20261009/gates/rc2-containers/<name>/rc2-containers-result.json

r2 (2026-10-14, X1 R-C2 triage): in X1 rcon-check two shared chests lost 5 extra items of one type while the partner viewer
held exactly those 5 (saved player data; nothing lost or duplicated). Both viewers' withdraw remainders and deposits used
the same first-free low slots, so a stale client view made one take the other's type. r2 gives each viewer of a shared
chest a disjoint slot range (bots/rc2_bots_r2.js, chest.slots) and adds a hard item-conservation check (chests + every
player + loose item entities, per item type). The existing exact per-type checks are unchanged.
"""
import math, re, sys

sys.path.insert(0, r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage9-20261009\gates\scripts')
import rc2common9e13 as C  # noqa: E402

C.BOT = C.STAGE9 / 'gates' / 'bots' / 'rc2_bots_r2.js'

g = C.Gate('rc2-containers', 'stage9-engine-rc2-containers-v2', props={'gamemode': 'survival'})
S, ROUNDS = C.SURFACE, 10
LOOP = [(round(3 * math.cos(a * math.pi / 4)), round(3 * math.sin(a * math.pi / 4))) for a in range(8)]


def items(kind, slots, count=16):
    return ','.join(f'{{Slot:{s}b,id:"minecraft:{kind}",count:{count}}}' for s in slots)


plan = C.new_plan()
STATIONS = []
for i in range(8):                                               # shared double chests
    cx, cz = -140 + 40 * i, 500
    st = {'pos': [cx, S, cz], 'shared': True, 'blocks': [[cx, S, cz], [cx + 1, S, cz]], 'bots': []}
    for k, item in enumerate(('diamond', 'emerald')):
        b = C.add(plan, {'home': [cx + LOOP[k * 4][0], S, cz + LOOP[k * 4][1]], 'chest': {
            'pos': [cx, S, cz], 'item': item, 'shared': True, 'phase': k * 4, 'slots': [0, 14] if k == 0 else [14, 27], 'awayYaw': 0 if k == 0 else 3.14159,
            'loop': [[cx + dx, S, cz + dz] for dx, dz in LOOP]}})
        st['bots'].append((b['name'], item))
    STATIONS.append(st)
for i in range(8):                                               # single chests
    cx, cz = -140 + 40 * i, 560
    b = C.add(plan, {'home': [cx + 3, S, cz], 'chest': {'pos': [cx, S, cz], 'item': 'gold_ingot', 'shared': False, 'phase': 0,
                                                       'awayYaw': 0, 'loop': [[cx + dx, S, cz + dz] for dx, dz in LOOP]}})
    STATIONS.append({'pos': [cx, S, cz], 'shared': False, 'blocks': [[cx, S, cz]], 'bots': [(b['name'], 'gold_ingot')]})
for d in range(8):
    C.add(plan, {'home': [-8 + 2 * d, S, 640], 'drop': {'pen': [-12, 628, 12, 652]}})
DROPPERS = [b['name'] for b in plan['bots'] if 'drop' in b]


def chest_totals(st):
    tot = {}
    for x, y, z in st['blocks']:
        reply = g.rc(f'data get block {x} {y} {z} Items')
        for comp in re.findall(r'\{([^{}]*)\}', reply):
            c = re.search(r'count: (\d+)', comp); k = re.search(r'id: "minecraft:([a-z_]+)"', comp)
            if c and k:
                tot[k.group(1)] = tot.get(k.group(1), 0) + int(c.group(1))
    return tot


try:
    g.boot()
    g.forceload(-160, 480, 191, 671)
    g.clear_box(-150, 488, 190, 580)
    g.clear_box(-14, 626, 14, 654)
    g.rc(f'fill -13 {S} 627 13 {S} 653 minecraft:oak_fence hollow', C.OK_FILL)
    for st in STATIONS:
        if st['shared']:
            x, y, z = st['pos']
            fill = items('diamond', range(13)) + ',' + items('emerald', range(14, 27))
            g.rc(f'setblock {x} {y} {z} minecraft:chest[facing=north,type=right]{{Items:[{fill}]}}', 'Changed the block')
            g.rc(f'setblock {x + 1} {y} {z} minecraft:chest[facing=north,type=left]{{Items:[{fill}]}}', 'Changed the block')
        else:
            x, y, z = st['pos']
            g.rc(f'setblock {x} {y} {z} minecraft:chest[facing=north]{{Items:[{items("gold_ingot", range(13))}]}}', 'Changed the block')
    initial = [chest_totals(st) for st in STATIONS]
    g.join(plan, op=False)
    for n in DROPPERS:
        g.rc(f'item replace entity {n} hotbar.0 with minecraft:cobblestone 64', 'Replaced')
    C.wait(5)
    before = g.invariants('setup')

    marks = g.bots.marks()
    ids = [g.bots.post(f'chest {ROUNDS} @chest'), g.bots.post('drop 60 @drop')]
    for cid in ids:
        g.bots.wait_done(cid, marks, 600)
    rows = g.bots.events()
    opens = [r for r in rows if r['event'] == 'act' and r['name'] == 'open']
    g.ev['opens'] = (sum(o['ok'] for o in opens), len(opens))
    g.check(f'chest: every open while walking succeeded ({24 * ROUNDS})', g.ev['opens'] == (24 * ROUNDS, 24 * ROUNDS), g.ev['opens'])
    resync = [r for r in rows if r['event'] == 'resync']
    g.ev['singleResyncs'] = sum(1 for r in resync if not r.get('shared'))
    g.info['sharedResyncs'] = sum(1 for r in resync if r.get('shared'))
    g.check('chest: no full resync on single-viewer chests (stateId order kept)', g.ev['singleResyncs'] == 0, g.ev['singleResyncs'])
    wrong = []
    for st, init in zip(STATIONS, initial):
        now = chest_totals(st)
        for name, item in st['bots']:
            have = re.search(r'Found (\d+)', g.rc(f'clear {name} minecraft:{item} 0'))
            have = int(have.group(1)) if have else 0
            if have != ROUNDS:
                wrong.append((name, item, 'inventory', have))
        for item in {i for _, i in st['bots']}:
            users = sum(1 for _, i in st['bots'] if i == item)
            if now.get(item, 0) != init.get(item, 0) - ROUNDS * users:
                wrong.append((st['pos'], item, 'chest', init.get(item), now.get(item)))
    g.check('chest: exact inventories and per-type chest totals', not wrong, wrong[:12] or None)
    names = [b['name'] for b in plan['bots']]
    cons = {}
    for item in ('diamond', 'emerald', 'gold_ingot'):
        chests0 = sum(init.get(item, 0) for init in initial)
        chests1 = sum(chest_totals(st).get(item, 0) for st in STATIONS)
        players = 0
        for n in names:
            m = re.search(r'Found (\d+)', g.rc(f'clear {n} minecraft:{item} 0'))
            players += int(m.group(1)) if m else 0
        g.rc('scoreboard objectives add s9cons dummy', 'Created|already')
        g.rc('scoreboard players set #sum s9cons 0', 'Set')
        g.rc(f'execute as @e[type=item,nbt={{Item:{{id:"minecraft:{item}"}}}}] store result score @s s9cons run data get entity @s Item.count')
        g.rc('scoreboard players operation #sum s9cons += @e[type=item] s9cons')
        loose = int(re.search(r'has (-?\d+)', g.rc('scoreboard players get #sum s9cons')).group(1))
        g.rc('scoreboard players reset @e[type=item] s9cons')
        cons[item] = (chests0, chests1, players, loose)
    g.ev['conservation'] = cons
    g.check('chest: items conserved per type (chests + players + loose == initial chests)',
            all(c1 + p + lo == c0 for c0, c1, p, lo in cons.values()), cons)
    g.rc('scoreboard objectives add s9items dummy', 'Created|already')
    g.rc('scoreboard players set #sum s9items 0', 'Set')
    g.rc('execute as @e[type=item,nbt={Item:{id:"minecraft:cobblestone"}}] store result score @s s9items run data get entity @s Item.count')
    g.rc('scoreboard players operation #sum s9items += @e[type=item] s9items')
    loose = int(re.search(r'has (-?\d+)', g.rc('scoreboard players get #sum s9items')).group(1))
    held = 0
    for n in DROPPERS:
        m = re.search(r'Found (\d+)', g.rc(f'clear {n} minecraft:cobblestone 0'))
        held += int(m.group(1)) if m else 0
    dropped = sum(r.get('n', 0) for r in rows if r['event'] == 'dropDone')
    g.ev['cobblestone'] = {'held': held, 'loose': loose, 'dropped': dropped}
    g.check('drop: cobblestone conserved (held + loose == 512) and drops happened', held + loose == 512 and dropped >= 200,
            g.ev['cobblestone'])
    after = g.invariants('chest', before)

    g.bots.send('walkaway @chest', 120)
    rows = g.bots.events()
    away = [r for r in rows if r['event'] == 'walkaway']
    bad = [(r['bot'], r['closed'], r.get('dist')) for r in away if not r['closed'] or not 7.5 <= r.get('dist', 0) <= 10.0]
    g.check('walkaway: 24/24 menus closed by the server at the stillValid distance', len(away) == 24 and not bad, bad or len(away))
    g.audit('containers')
except Exception as e:  # noqa: BLE001
    g.fail(e)
finally:
    g.finish(min_checks=15)
