"""Stage 9 S9-E13 (R-C2) gate 2: teleports, portals, respawns and joins under load with the move split. Extracted from
drafts/gate-draft-rc2.md section 3 (written for D32). D34+: an in-drain level change (sign click, respawn) arrives in a
non-move packet, so the early flush runs before it and the invalid-pending branch (moveSplitSkipped) is no longer
reached this way: the hard check is moveSplitEarly > 0 during the load; Skipped and Inline are information.

32 bots on a flat L5 server (rc2common9e13): 16 wanderers (4 of them killed twice, 4 of them reconnecting once), 8
signers clicking waxed run_command signs while walking (same-level 300-block jumps and Nether round trips: the only
in-drain teleports), 4 porters (Nether portal out, sign back); every 5 s three wanderers are teleported by RCON. Then
deterministic storms at quiescence (cluster, +200 split, Nether and back). Audits after the load and each storm.
Hard: every transition happened; 0 unpaired / ghosts / stale ids in both dimensions; moveSplitEarly > 0 during the
load on the split arms; must-be-zero counters 0.

    set S8_CANDIDATE=<hashed jar>
    python gates/scripts/rc2teleport9e13r2.py <name> <engine-workers> <base-port> [--arm=...] [-D...]
Results: .audit/stage9-20261009/gates/rc2-teleport/<name>/rc2-teleport-result.json

r2 (2026-10-14, X1 R-C2 triage): the setup probe teleported plan['bots'][8] (a wanderer) to the probe sign while the bot
program clicks with the first bot that has 'signs' (index 16), so every X1 run failed "block not loaded" and never tested
the sign format. The probe now teleports that same first signer. Nothing else changed.
"""
import random, sys, time

sys.path.insert(0, r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage9-20261009\gates\scripts')
import rc2common9e13 as C  # noqa: E402

g = C.Gate('rc2-teleport', 'stage9-engine-rc2-teleport-v2')
S, NY, ROUNDS, LOAD_S = C.SURFACE, 70, 3, 150
NETHER, OVER = 'the_nether', 'overworld'
rng = random.Random(9013)

plan = C.new_plan()
for i in range(16):                                            # wanderers; 0-3 victims, 4-7 churners
    C.add(plan, {'wander': {'anchor': [-60 + 40 * (i % 4), S, -60 + 40 * (i // 4)], 'tether': 30},
                 'victim': i < 4, 'churn': 4 <= i < 8, 'home': [-60 + 40 * (i % 4), S, -60 + 40 * (i // 4)]})
for s in range(8):                                             # signers
    sx = -84 + 24 * s
    nx, nz = -28 + 8 * s, -20
    C.add(plan, {'home': [sx, S, -70], 'signs': [
        {'name': 'jump', 'go': [sx, S, -88], 'back': [sx + 300, S, -88], 'dim': None,
         'goCmd': 'execute at @s run tp @s ~300 ~ ~', 'backCmd': 'execute at @s run tp @s ~-300 ~ ~'},
        {'name': 'dim', 'go': [sx, S, -76], 'back': [nx, NY, nz + 3], 'dim': NETHER,
         'goCmd': f'execute in minecraft:the_nether run tp @s {nx}.5 {NY} {nz}.5',
         'backCmd': f'execute in minecraft:overworld run tp @s {sx}.5 {S} -72.5'}]})
for p, px in enumerate((-40, 40, -64, 64)):                    # porters (portal plane x px..px+1, z 60)
    nx, nz = px // 8, 7
    C.add(plan, {'home': [px, S, 64], 'portal': {'front': [px, S, 62], 'yawIn': 0, 'yawOut': 3.14159,
                 'netherSign': [nx, NY, nz + 4], 'px': px, 'nx': nx, 'nz': nz,
                 'backCmd': f'execute in minecraft:overworld run tp @s {px}.5 {S} 64.5'}})
WANDERERS = [b['name'] for b in plan['bots'] if 'wander' in b]
VICTIMS = [b['name'] for b in plan['bots'] if b.get('victim')]


def sign(x, y, z, command, dim=OVER):
    # VERIFY on 26.2 (setup probe): SNBT text components with click_event {action, command} (1.21.5+ format), waxed.
    text = '{text:"s9",click_event:{action:"run_command",command:"%s"}}' % command
    nbt = '{is_waxed:1b,front_text:{messages:[%s,"","",""]},back_text:{messages:[%s,"","",""]}}' % (text, text)
    g.rc(f'execute in minecraft:{dim} run setblock {x} {y} {z} minecraft:oak_sign[rotation=8]{nbt}', 'Changed the block')


try:
    g.boot()
    g.forceload(-112, -112, 111, 111)
    g.forceload(192, -112, 399, -49)
    g.forceload(160, -32, 255, 31)
    g.forceload(-48, -48, 47, 47, dim=NETHER)
    g.clear_box(-100, -100, 100, 100)
    g.clear_box(200, -100, 400, -60)
    g.clear_box(160, -30, 250, 30)
    g.rc(f'execute in minecraft:{NETHER} run fill -32 69 -32 31 69 31 minecraft:netherrack', C.OK_FILL)
    g.rc(f'execute in minecraft:{NETHER} run fill -32 70 -32 31 76 31 minecraft:air', C.OK_FILL)
    for b in plan['bots']:
        for leg in b.get('signs', []):
            sign(*leg['go'], leg['goCmd'])
            sign(*leg['back'], leg['backCmd'], dim=NETHER if leg['dim'] else OVER)
        if 'portal' in b:
            P = b['portal']
            g.rc(f'fill {P["px"] - 1} {S - 1} 60 {P["px"] + 2} {S + 3} 60 minecraft:obsidian', C.OK_FILL)
            g.rc(f'fill {P["px"]} {S} 60 {P["px"] + 1} {S + 2} 60 minecraft:nether_portal[axis=x]', C.OK_FILL)
            g.rc(f'execute in minecraft:{NETHER} run fill {P["nx"] - 1} {NY - 1} {P["nz"]} {P["nx"] + 2} {NY + 3} {P["nz"]} minecraft:obsidian', C.OK_FILL)
            g.rc(f'execute in minecraft:{NETHER} run fill {P["nx"]} {NY} {P["nz"]} {P["nx"] + 1} {NY + 2} {P["nz"]} minecraft:nether_portal[axis=x]', C.OK_FILL)
            sign(*P['netherSign'], P['backCmd'], dim=NETHER)
    sign(0, S, 90, 'execute at @s run tp @s ~ ~ ~-6')            # probe sign for the format check
    g.join(plan, op=False)
    probe = next(b['name'] for b in plan['bots'] if b.get('signs'))   # first signer = the bot program's probe clicker
    g.rc(f'tp {probe} 0.5 {S} 87.5 facing 0.5 {S} 90.5', 'Teleported')
    C.wait(2)
    g.bots.send('probe 0 90 @signs', 30)                          # click (0, S, 90): the bot moves 6 blocks north
    reply = g.rc(f'execute if entity @a[name={probe},x=-3,y={S - 2},z=78,dx=6,dy=4,dz=6]')
    g.check('setup: waxed run_command sign teleports its clicker (26.2 SNBT format)', 'Test passed' in reply, reply[-120:])
    if 'Test passed' not in reply:
        raise RuntimeError('sign probe failed: fix the sign NBT before trusting this gate')
    for b in plan['bots']:
        g.rc(f'tp {b["name"]} {b["home"][0] + 0.5} {S} {b["home"][2] + 0.5}', 'Teleported')
    C.wait(5)
    before = g.invariants('setup')

    # ---------------------------------------------------------------------------------------------- load
    marks = g.bots.marks()
    ids = [g.bots.post(f'wander {LOAD_S} @wander'), g.bots.post(f'signs {ROUNDS} @signs'), g.bots.post(f'portal {ROUNDS} @portal')]
    t0 = time.time()
    killed = churned = 0
    while time.time() - t0 < LOAD_S:
        for name in rng.sample(WANDERERS, 3):
            g.rc(f'tp {name} {rng.randint(-90, 90)}.5 {S} {rng.randint(-90, 90)}.5', 'Teleported|No entity')
        el = time.time() - t0
        if killed < 2 and el > 40 + 60 * killed:
            for name in VICTIMS:
                g.rc(f'kill {name}', 'Killed|No entity')
            killed += 1
        if not churned and el > 70:
            ids.append(g.bots.post('reconnect @churn'))
            churned = 1
        time.sleep(5)
    for cid in ids:
        g.bots.wait_done(cid, marks, 600)
    rows = g.bots.events()
    acts = [r for r in rows if r['event'] == 'act']
    sign_acts = [a for a in acts if a['name'] == 'sign']
    portal_out = [a for a in acts if a['name'] == 'portal']
    portal_back = [a for a in acts if a['name'] == 'portalBack']
    g.ev['signs'] = (sum(a['ok'] for a in sign_acts), len(sign_acts))
    g.ev['portals'] = (sum(a['ok'] for a in portal_out), sum(a['ok'] for a in portal_back), len(portal_out))
    g.ev['deaths'] = sum(r['event'] == 'death' for r in rows)
    g.ev['respawns'] = sum(r['event'] == 'respawn' and r['bot'] in VICTIMS for r in rows)
    g.ev['reconnects'] = (sum(a['name'] == 'reconnect' and a['ok'] for a in acts), sum(a['name'] == 'reconnect' for a in acts))
    g.check('load: every sign click teleported (96 of 96)', g.ev['signs'] == (8 * ROUNDS * 4, 8 * ROUNDS * 4), g.ev['signs'])
    g.check('load: every portal trip arrived and every sign return came back (12/12)',
            g.ev['portals'] == (4 * ROUNDS, 4 * ROUNDS, 4 * ROUNDS), g.ev['portals'])
    g.check('load: 8 deaths, 8 respawns', g.ev['deaths'] == 8 and g.ev['respawns'] >= 8, (g.ev['deaths'], g.ev['respawns']))
    g.check('load: 4 reconnects, all back', g.ev['reconnects'] == (4, 4), g.ev['reconnects'])
    after = g.invariants('load', before)
    for key in ('moveSplitDeferred', 'moveSplitParallel', 'moveSplitSkipped', 'moveSplitInline', 'moveSplitRechecked',
                'moveSplitEarly'):
        g.ev['load.' + key] = C.delta(after, before, key)
    if g.arm != 'vanilla':
        g.check('load: in-drain level changes and interactions flushed the pending move first (moveSplitEarly > 0)',
                g.ev['load.moveSplitEarly'] > 0, g.ev['load.moveSplitEarly'])
    g.info['inline'] = g.ev['load.moveSplitInline']
    g.info['skipped'] = g.ev['load.moveSplitSkipped']
    g.audit('load')

    # ---------------------------------------------------------------------------------------------- storms
    names = [b['name'] for b in plan['bots']]
    for i, n in enumerate(names):
        g.rc(f'execute in minecraft:overworld run tp {n} {(i % 8) * 3 - 12}.5 {S} {(i // 8) * 3 - 6}.5', 'Teleported')
    g.audit('cluster')
    for n in names[::2]:
        g.rc(f'execute as {n} at @s run tp @s ~200 ~ ~', 'Teleported')
    g.audit('split200')
    for i, n in enumerate(names[1::2]):
        g.rc(f'execute in minecraft:{NETHER} run tp {n} {(i % 4) * 4 - 8}.5 {NY} {(i // 4) * 4 - 8}.5', 'Teleported')
    g.audit('nether')
    for i, n in enumerate(names[1::2]):
        g.rc(f'execute in minecraft:overworld run tp {n} {(i % 4) * 4 - 8}.5 {S} {(i // 4) * 4 - 8}.5', 'Teleported')
    g.audit('back')
except Exception as e:  # noqa: BLE001
    g.fail(e)
finally:
    g.finish(min_checks=24)
