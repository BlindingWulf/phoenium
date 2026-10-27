"""r10 copy (2026-10-19, main Opus): portal FIXTURE timing only; every check and threshold unchanged from r9. Ten r7-r9 runs
(rc1/TRANSITIONS_PORTAL_FLAKE_FINDING.md) failed 4/10 on D45 and D43 alike from vanilla portal re-entry: the portal mobs are
counted ~17-22 s after crossing, past their 300-tick PortalCooldown, and can walk back through the Nether arrival portal
(~(8.4, 70, 12.6); rc1-d45-t9d PZ63 found at the overworld portal with PortalCooldown 285); the dimhop cows land ~9 blocks
from that portal and wander up to ~13 blocks. r10: (1) the portal entities are counted (and probed) 4 s after the dimhop
teleport, i.e. ~5-7 s after crossing, inside the cooldown, and BEFORE the bot joins (in r7-r9 the bot also joined only after
the mobs had crossed, so no concurrency is lost); (2) the dimhop cows land at (-12, 70, -12), ~31 blocks from the arrival
portal (inside the lava-free box, x/z -16..31); (3) the bot pearl step, the dimhop counts, the r8/r9 diagnostics and all
three portal checks are unchanged and run in the same order after it.
r9 copy (2026-10-19, main Opus): dimhop diagnostics only, every check unchanged from r8. rc1-d45-t8a failed the same
check again (dimhopNether 3, dimhopOverworld 1, dimhopBack 4): one cow was in the overworld before the tp back. r9 records,
right after the r8 counts, Pos and PortalCooldown of every dimhop cow in each level (evidence.dimhopProbe: the logged
'has the following entity data' lines, in order overworld Pos, overworld PortalCooldown, Nether Pos, Nether
PortalCooldown). A cow that walked through the Nether arrival portal shows an overworld Pos near the overworld portal
(x 63-65, z ~100) and a PortalCooldown > 0; a cow the first tp never moved shows its summon spot (x 12.5-15.5, z 12.5).
r8 copy (2026-10-19, main Opus): dimhop diagnostics only, every check unchanged from r7. rc1-d45-t7 (D45 RC1) failed
'portals: command dimension moves both ways' with dimhopNether 3 / dimhopBack 4 (first in 33 transitions runs): all four
cows were alive (the overworld tp moved them back), so one was not in the Nether at the count. Hypothesis: it walked into
the Nether arrival portal of the portal phase (~(8,70,12.5), ~9 blocks from the dimhop landing at (4,70,4); PZ64 had
PortalCooldown 300 at the probe) and crossed to the overworld. r8 records, after the Nether count and before the tp back,
the dimhop count in the overworld (evidence.dimhopOverworld) and keeps the tp-back reply line (evidence.dimhopTpReply).
r7 copy (2026-10-15): portal diagnostics only, every check unchanged from r6. d43-transitions-w5 (r5 gate) failed the
portal check with Nether 4 / overworld 1 (per type: Nether zombie 2, cow 2, minecart 0; overworld cow 1, minecart 0), yet
all six portal entities (PZ63, PZ64, PC63, PC64, PCR and the cart) were alive 18 s later in the end-kill UUID dump: a
misplacement at count time, not a loss. The cart was in neither level's count. Portal entities get a 300-tick cooldown
and are counted ~17 s after crossing, so a return crossing ("portal bounce", findings/MP-1) can overlap the reads.
r7 gives the six portal entities fixed UUIDs and, right after the counts, looks each one up directly (a UUID finds it in
any level): Pos, PortalCooldown and the cart's Passengers (evidence.portalProbe); the per-type counts are read again
4 s later (evidence.portalTypesLater). Extra arguments after the four are appended to the JVM flags (a control run with
-Dphoenium.engine.minWidth=0).
r6 copy (2026-10-14): the Nether landing box is made lava-free; every check unchanged. bis-transitions-r5-d39 aborted at
the dimhop teleport back ('No entity was found'): the 4 dimhop cows sent to the Nether (4.5, 70, 4.5) were in no
dimension 18 s later, and nowhere in the saved world. The saved Nether shows flowing lava over the box floor at y 70
(x 0..7, z 0..4 and around): with a blank seed the Nether differs per run, and lava from outside the carved box flowed in.
The cows burned, and lava destroys the drops (no beef or leather saved). Before the box is carved, every lava block within
8 blocks of it (x -24..39, y 66..90, z -24..39) is replaced by netherrack.
r5 copy (2026-10-14): the world is generated without structures (generate-structures=false); every check unchanged.
bis-transitions-r4-d35 found the held end memberships: 13 village mobs (villagers, nitwit, cats, sheep, pigs, an iron
golem) of the flat world's generated village, all at Health 0 with DeathTime 0 on two reads 1 s apart (not ticked), in
chunks x 10-11, the ring just outside the forceloaded box (chunks -2..9). A forced chunk is entity-ticking (level 31) and
its neighbours are loaded at levels 32-33 without entity ticking (vanilla ticket propagation), so once the bot has left
nothing ticks them: a mob killed there by the end kill never finishes its death and stays in the world until its chunk
unloads (at the stop), as in vanilla, and its A3 membership with it (the engine follows the entity's lifetime). The
village is incidental to this gate: every conversion group is summoned and counted by tag. Without it the end check
judges the release path of the gate's own mobs, as intended. The r4 diagnostics stay.
r4 copy (2026-10-14), diagnostics only, every check unchanged from r3: the UUID of every non-player entity is also
recorded before EVERY kill command of the run (not only the end kills), and a failed drain probes that whole set.
bis-transitions-r3-d39: 2 memberships held for the 62 s drain, but none of the 86 UUIDs recorded before the end kills was
still in any level, so the held entities were already not alive at the first end kill (`@e` skips them): most likely
corpses of an earlier phase's kill whose death never finished. The probe now reaches them.
r3 copy (2026-10-14), diagnostics only, every check unchanged from r2: before each end kill the UUID of every
non-player entity (all levels) is recorded; if a membership is still held after the drain, each recorded UUID is looked
up directly (a UUID argument has no isAlive filter, unlike @e) for Health, DeathTime twice 1 s apart (is it ticked?),
Pos and its chunk's forceload state and dimension (evidence.endStuck). bis-transitions-r2-d35: 2 memberships held for
the whole 62 s drain with nothing alive and orphans 0, released only at the stop, although the play areas are
forceloaded; so 'dying mob in a chunk that stopped entity-ticking' is not established. Portal phase (also diagnostics,
no check change): the portal mobs carry a CustomName so vanilla logs any death with its cause (evidence.portalDeaths),
and the crossing is counted per type on both sides (evidence.portalTypes); bis-transitions-r2-d39 counted 5 in the
Nether (usual 6, D35 t1 7) and 0 in the overworld.
r2 copy (2026-10-14): the end check is judged after the memberships drain (dead-but-not-removed mobs, see the end
phase); transitions9e8.py stays unchanged as the D26-D42 evidence.
Stage 9 S9-E8 (D26+): engine-mode transitions gate for the per-tile / per-scope protection memberships.

Owner directive 2026-10-10 item 2: a membership must follow its object at the transition (tile change, conversion, portal,
passenger, leash, unload), and the invariant is a counter that must be 0. This gate drives those transitions across engine
tile edges (tiles are 4x4 chunks = 64 blocks; the scenario sits on the tile corner (64, 64)) on a fresh flat L5 server
(s8lib builder, read-only) with Phoenium = S8_CANDIDATE in engine mode, the given tile threads and schedule, all three
protections on (A3 threshold 4, projectile cap 6, pearl cap 2) and every self-check at 1 (protection memberships, tracker
index full rebuild, spawn memo), so every tick start compares every membership with its object.

A3 covers exactly Zombie, Skeleton, Creeper, Cow, Pig, Sheep, Chicken (MobDecisionContext), so the mobs are those.
Scenario (game running unless noted; floors at y 64 walled in, so no mob leaves the entity-ticking area):
  wander      75 A3 mobs in a walled pen across the tile corner, plus a spider jockey and a chicken jockey, 40 s
  hops        8 cows teleported 65 blocks back and forth (server-thread moves into another tile), 20 times
  vehicles    4 minecarts with zombie / cow riders shuttling on a powered rail line over a tile edge (bumpers), 48 s
  leashes     a cow leashed to an armour stand that is moved over a tile edge (the leash pulls the cow across); a cow
              leashed to a fence knot on the tile edge
  conversions lightning on the tile edge (pig -> zombified piglin, villager -> witch); piglins / hoglins with TimeInOverworld
              near 300 (zombified piglin / zoglin); zombie villagers with ConversionTime (villager); husks and zombies with
              DrownedConversionTime (zombie, drowned: into and out of an A3 class); a tadpole at Age 23995 (frog); medium
              slimes killed on the edge (split)
  projectiles 8 snowballs (cap 6: the 2 oldest are discarded) and 4 arrows flying over a tile edge (NoGravity)
  portals     mobs, a minecart with a rider and a snowball through a Nether portal straddling a tile edge; an ender pearl
              thrown through it by a headless offline client (its landing moves the thrower to the Nether); cows moved
              to the Nether and back by command
  unload      a separately force-loaded tile with mobs and shuttling carts is unloaded mid-run and loaded again
  end         every non-player entity killed: memberships 0
Checks: after every step, mobDecisionStale/Orphans, projectileStale/Orphans, pearlStale/Orphans, projectileLateRelocations,
protectionOffThread, trackerIndexStale, trackerReverseStale, trackerIndexOffThread, spawnMemoStale and the engine traps
are 0 and the per-tick protection self-check advanced; per step, evidence that the transition happened (relocations,
converted / transferred / reloaded entities). End: memberships 0, clean stop whose counters are 0, no protection fault log
line, no bad log line, no crash report.

    set S8_CANDIDATE=<hashed jar>
    python gates/scripts/transitions9e8.py <name> <engine-workers> <schedule colours|dag> <base-port>
Results: .audit/stage9-20261009/gates/transitions/<name>/transitions-result.json
"""
import json, os, pathlib, re, subprocess, sys, threading, time

SCRIPTS8 = pathlib.Path(r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage8-compat-20261007\scripts8')
sys.path.insert(0, str(SCRIPTS8))
import s8lib as L  # noqa: E402

STAGE9 = pathlib.Path(r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage9-20261009')
BOT = STAGE9 / 'gates' / 'bots' / 'pearl_bot.js'
NODE_MODULES = L.EV / 'tools' / 'bots' / 'node_modules'
TRAPS = ('loadTraps', 'writeTraps', 'storageTraps')
MUST_BE_ZERO = ('mobDecisionStale', 'mobDecisionOrphans', 'projectileStale', 'projectileOrphans', 'pearlStale', 'pearlOrphans',
                'projectileLateRelocations', 'protectionOffThread', 'trackerIndexStale', 'trackerReverseStale',
                'trackerIndexOffThread', 'spawnMemoStale') + TRAPS
USER = 'S9Transit'
NETHER = 'minecraft:the_nether'
KEEP = 'PersistenceRequired:1b'
OK_FILL = 'Successfully filled|No blocks'

name, engine_workers, schedule, base = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4])
assert schedule in ('colours', 'dag'), schedule
ENGINE = ['-Dphoenium.engine.tiles=true', f'-Dphoenium.engine.workers={engine_workers}', f'-Dphoenium.engine.schedule={schedule}',
          '-Dphoenium.protection.mobDecisions.enabled=true', '-Dphoenium.protection.mobDecisions.threshold=4',
          '-Dphoenium.protection.projectileCap.enabled=true', '-Dphoenium.protection.projectileCap=6',
          '-Dphoenium.protection.pearlCap.enabled=true', '-Dphoenium.protection.pearlCap=2',
          '-Dphoenium.engine.protection.check=1', '-Dphoenium.engine.trackerIndex.check=1', '-Dphoenium.engine.trackerIndex.full=1',
          '-Dphoenium.engine.spawnMemo.check=1'] + sys.argv[5:]                # r7: extra JVM flags (controls)
run = STAGE9 / 'gates' / 'transitions' / name
ports = {'java': base, 'rcon': base + 1, 'bedrock': base - 7000, 'voice': base - 1500}
for k, p in ports.items():
    if not L.free_port(p, udp=k in ('bedrock', 'voice')):
        sys.exit(f'port busy {k}={p}')
manifest = L.build_server(run / 'server', L.STACK_LAYERS[L.FINAL], ports, f'S9E8-TRANS-{name}',
                          extra_props={'level-type': 'minecraft:flat', 'generator-settings': '{}', 'difficulty': 'easy',
                                       'generate-structures': 'false',          # r5: no generated village (see the header)
                                       'spawn-protection': '0', 'gamemode': 'creative', 'view-distance': '4',
                                       'simulation-distance': '4'})
res = {'schema': 'stage9-engine-transitions-v1', 'name': name, 'engineWorkers': engine_workers, 'schedule': schedule,
       'engineProps': ENGINE, 'utc': L.now(), 'candidate': str(L.CANDIDATE), 'candidateSha256': L.CANDIDATE_SHA, 'ports': ports,
       'manifest': manifest, 'checks': [], 'diagnostics': {}, 'evidence': {}}
ev = res['evidence']


def check(label, ok, detail=None):
    res['checks'].append({'label': label, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + label, '' if detail is None else detail, flush=True)


s = L.Server(run / 'server', 'console.log', workers=1, extra_props=ENGINE).start()


ALL_UUIDS = {}                                                          # r4: every non-player UUID seen before any kill
PORTAL_UUIDS = {k: f'[I;9001,7,0,{i}]' for i, k in enumerate(('PZ63', 'PC63', 'PZ64', 'PC64', 'CART', 'PCR'), 1)}   # r7


def command(text, expect=None, timeout=30):
    # r4: who is about to be killed (diagnostic). Not before the projectile kill: its 2 s would let shots land first.
    if text.startswith('kill ') and 'tag=shot' not in text:
        ALL_UUIDS.update(uuids_now())
    start = len(s.lines)
    s.console(text)
    if expect:
        return s.wait_for(expect, timeout, start=start)[1]
    return None


def diag(label):
    start = len(s.lines)
    s.console('phoenium diagnostics')
    line = s.wait_for(r'mode=engine-tiles lifecycle=', 30, start=start)[1]
    values = {}
    for key, value in re.findall(r'(\w+)=(\S+)', line.split('mode=engine-tiles', 1)[1]):
        values[key] = int(value) if value.lstrip('-').isdigit() else value
    res['diagnostics'][label] = values
    return values


def count(selector, dimension=None):
    """Entities matching the selector ('Test passed. Count: N' in 26.2, 'Test failed' for none); with a dimension,
    positioned at its origin. g11r2 parsed 'count: ' and read every count as 0."""
    text = f'execute if entity {selector}'
    if dimension:
        text = f'execute in {dimension} positioned 0 64 0 run ' + text
    start = len(s.lines)
    s.console(text)
    line = s.wait_for(r'Test (passed|failed)', 30, start=start)[1]
    m = re.search(r'[Cc]ount: (\d+)', line)
    if 'Test passed' in line and not m:
        raise RuntimeError('unparsed count reply: ' + line[-120:])
    return int(m.group(1)) if m else 0


def uuid_str(ints):
    h = ''.join(f'{v & 0xffffffff:08x}' for v in ints)
    return f'{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}'


def uuids_now():
    """r3: the UUID of every non-player entity in every level, from the console replies of one execute-as."""
    start = len(s.lines)
    s.console('execute as @e[type=!minecraft:player] run data get entity @s UUID')
    time.sleep(2)
    out = {}
    for line in list(s.lines)[start:]:
        m = re.search(r'\]: (.+?) has the following entity data: \[I; (-?\d+), (-?\d+), (-?\d+), (-?\d+)\]', line)
        if m:
            out[uuid_str([int(v) for v in m.groups()[1:]])] = m.group(1)
    return out


def reply(text, timeout=10):
    start = len(s.lines)
    s.console(text)
    try:
        return s.wait_for(r'has the following entity data|No entity was found|Found no elements|is marked for force|'
                          r'is not marked for force|Chunk at|Unknown|Incorrect|Invalid', timeout, start=start)[1][-220:]
    except Exception as e:  # noqa: BLE001
        return f'no reply ({type(e).__name__})'


def probe(u, name):
    """r3: a recorded entity looked up by UUID (found even when dead): health, death time twice, position, chunk state."""
    first = reply(f'data get entity {u} DeathTime')
    if 'No entity was found' in first:
        return None
    out = {'uuid': u, 'name': name, 'deathTime0': first, 'health': reply(f'data get entity {u} Health'),
           'pos': reply(f'data get entity {u} Pos')}
    time.sleep(1)
    out['deathTime1'] = reply(f'data get entity {u} DeathTime')
    out['forceload'] = reply(f'execute as {u} at @s run forceload query ~ ~')
    out['vehicle'] = reply(f'execute as {u} on vehicle run data get entity @s id')
    return out


def invariants(label, previous=None):
    """Every must-be-0 counter is 0, and the per-tick protection self-check advanced since the previous step."""
    d = diag(label)
    bad = {k: d.get(k) for k in MUST_BE_ZERO if d.get(k) != 0}
    check(f'{label}: self-check faults and traps 0', not bad, bad or None)
    if previous is not None:
        check(f'{label}: protection self-check ran', d.get('protectionChecks', 0) > previous.get('protectionChecks', 0),
              (previous.get('protectionChecks'), d.get('protectionChecks')))
    return d


def delta(after, before, key):
    return after.get(key, 0) - before.get(key, 0)


def wait(seconds):
    time.sleep(seconds)


bot = None
bot_lines = []


def bot_reader():
    for line in bot.stdout:
        bot_lines.append(line.rstrip())


def bot_wait(prefix, timeout=60, start=0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for line in bot_lines[start:]:
            if line.startswith(prefix):
                return line
            if line.startswith('ERROR'):
                raise RuntimeError('bot: ' + line)
        time.sleep(0.1)
    raise TimeoutError('bot did not print ' + prefix)


def summon(kind, x, y, z, nbt='', dimension=None):
    text = f'summon minecraft:{kind} {x} {y} {z} {{{nbt}}}'
    if dimension:
        text = f'execute in {dimension} run ' + text
    command(text, 'Summoned new')


def walls(x0, z0, x1, z1):
    for box in (f'{x0} 65 {z0} {x1} 66 {z0}', f'{x0} 65 {z1} {x1} 66 {z1}', f'{x0} 65 {z0} {x0} 66 {z1}', f'{x1} 65 {z0} {x1} 66 {z1}'):
        command(f'fill {box} minecraft:cobblestone_wall', OK_FILL)


try:
    res['engineStart'] = s.wait_for(r'PHOENIUM_STAGE2 started lifecycle=RUNNING', 900)[1]
    s.wait_for(r'Done \(', 120)
    check('engine mode', 'mode=engine-tiles' in res['engineStart'] and f'workers={engine_workers}' in res['engineStart']
          and (engine_workers == 0 or 'parallel=TILES' in res['engineStart']), res['engineStart'][-200:])
    command('gamerule minecraft:spawn_mobs false', 'false')
    command('time set 14000', r'Set .* to \d+ tick|Set the time')                                         # night for ~4 game minutes: no sun burning
    command('tick freeze', 'The game is frozen')
    command('forceload add -32 -32 159 159', 'Marked|already')               # tiles (0,0) (1,0) (0,1) (1,1) and a margin
    command('forceload add 256 0 319 63', 'Marked|already')                  # the unload tile (4,0)
    command(f'execute in {NETHER} run forceload add -32 -32 47 47', 'Marked|already')
    for _ in range(240):
        if all('Test passed' in command(t, r'Test (passed|failed)') for t in (
                'execute if loaded 64 64 64', 'execute if loaded 150 64 150', 'execute if loaded 300 64 30',
                f'execute in {NETHER} run execute if loaded 8 64 8')):
            break
        wait(0.5)
    for x0 in (0, 64):
        for z0 in (0, 64):
            command(f'fill {x0} 64 {z0} {x0 + 63} 64 {z0 + 63} minecraft:grass_block', OK_FILL)
            command(f'fill {x0} 65 {z0} {x0 + 63} 70 {z0 + 63} minecraft:air', OK_FILL)
    command('fill 256 64 0 319 64 63 minecraft:grass_block', OK_FILL)
    command('fill 256 65 0 319 70 63 minecraft:air', OK_FILL)
    command(f'execute in {NETHER} run fill -16 69 -16 31 69 31 minecraft:netherrack', OK_FILL)
    # r6: no lava within 8 blocks of the landing box (walls and ceiling), in 4 pieces under the fill limit
    for lx, lz in ((-24, -24), (8, -24), (-24, 8), (8, 8)):
        command(f'execute in {NETHER} run fill {lx} 66 {lz} {lx + 31} 90 {lz + 31} minecraft:netherrack replace minecraft:lava', OK_FILL)
    command(f'execute in {NETHER} run fill -16 70 -16 31 76 31 minecraft:air', OK_FILL)
    walls(0, 0, 127, 127)                                                    # the floor's edge
    walls(256, 0, 319, 63)
    walls(48, 48, 80, 80)                                                    # the pen on the tile corner (64, 64)
    command('fill 30 64 40 100 64 40 minecraft:redstone_block', OK_FILL)     # cart line z=40 over the edge x=64
    command('fill 30 65 40 100 65 40 minecraft:powered_rail[shape=east_west,powered=true]', OK_FILL)
    command('setblock 29 65 40 minecraft:stone', 'Changed the block')        # bumpers: a stopped cart is launched back
    command('setblock 101 65 40 minecraft:stone', 'Changed the block')
    command('fill 280 64 20 300 64 20 minecraft:redstone_block', OK_FILL)    # cart line in the unload tile
    command('fill 280 65 20 300 65 20 minecraft:powered_rail[shape=east_west,powered=true]', OK_FILL)
    command('setblock 279 65 20 minecraft:stone', 'Changed the block')
    command('setblock 301 65 20 minecraft:stone', 'Changed the block')
    command('fill 62 64 100 65 68 100 minecraft:obsidian', OK_FILL)         # Nether portal x 63..64 (tile edge x=64), z=100
    command('fill 63 65 100 64 67 100 minecraft:nether_portal[axis=x]', OK_FILL)
    d0 = invariants('setup')
    check('protections active', d0.get('mobDecisions') == 'ON' and d0.get('projectileCap') == 6 and d0.get('pearlCap') == 2,
          {k: d0.get(k) for k in ('mobDecisions', 'projectileCap', 'pearlCap')})

    # ------------------------------------------------------------------------------------------------ wander
    kinds = ['zombie'] * 24 + ['cow'] * 24 + ['pig'] * 8 + ['sheep'] * 8 + ['chicken'] * 8
    for i, kind in enumerate(kinds):
        summon(kind, 57.5 + (i % 15), 65, 57.5 + (i // 15) * 3, f'{KEEP},Tags:["w"]')
    summon('spider', 60.5, 65, 66.5, f'{KEEP},Tags:["jockey"],Passengers:[{{id:"minecraft:skeleton",{KEEP},Tags:["jockey"]}}]')
    summon('chicken', 67.5, 65, 61.5, f'{KEEP},Tags:["jockey"],Passengers:[{{id:"minecraft:zombie",IsBaby:1b,{KEEP},Tags:["jockey"]}}]')
    wait(1.0)
    d = diag('wander-summoned')
    check('wander: the mobs are A3 members', d.get('mobDecisionMemberships', 0) >= 70, d.get('mobDecisionMemberships'))
    command('tick unfreeze', 'The game is running normally')
    wait(40)
    d_wander = invariants('wander', d)
    ev['wanderRelocations'] = delta(d_wander, d, 'mobDecisionRelocations')
    check('wander: memberships moved at the tile crossings', ev['wanderRelocations'] > 0, ev['wanderRelocations'])

    # ------------------------------------------------------------------------------------------------ hops
    for i in range(8):
        summon('cow', 20.5 + 2 * i, 65, 30.5, f'{KEEP},Tags:["hop"]')
    wait(1.0)
    before = diag('hops-before')
    for i in range(20):
        command(f'execute as @e[tag=hop] at @s run tp @s ~ ~ ~{65 if i % 2 == 0 else -65}', 'Teleported')
        wait(0.5)
    d_hops = invariants('hops', before)
    ev['hopRelocations'] = delta(d_hops, before, 'mobDecisionRelocations')
    check('hops: every teleport into another tile moved the membership', ev['hopRelocations'] >= 8 * 20, ev['hopRelocations'])

    # ------------------------------------------------------------------------------------------------ vehicles
    for i, rider in enumerate(('zombie', 'cow', 'zombie', 'cow')):
        summon('minecart', 40.5 + 4 * i, 65, 40.5,
               f'Tags:["cart"],Motion:[0.4d,0d,0d],Passengers:[{{id:"minecraft:{rider}",{KEEP},Tags:["rider"]}}]')
    before = diag('vehicles-before')
    series = []
    for _ in range(12):
        wait(4)
        series.append(count('@e[tag=rider,x=64,y=0,z=0,dx=200,dy=400,dz=200]'))
    ev['ridersEastSeries'] = series
    ev['riders'] = count('@e[tag=rider]')
    d_veh = invariants('vehicles', before)
    ev['vehicleRelocations'] = delta(d_veh, before, 'mobDecisionRelocations')
    changes = sum(1 for a, b in zip(series, series[1:]) if a != b)
    check('vehicles: riders shuttled over the tile edge with their carts, memberships followed',
          changes >= 3 and ev['vehicleRelocations'] >= 8, {'seriesChanges': changes, 'relocations': ev['vehicleRelocations']})

    # ------------------------------------------------------------------------------------------------ leashes
    summon('armor_stand', 58.5, 65, 20.5, 'Tags:["holder"],NoGravity:1b,UUID:[I;1234,5678,9012,3456]')
    summon('cow', 54.5, 65, 20.5, f'{KEEP},Tags:["leashed"],leash:{{UUID:[I;1234,5678,9012,3456]}}')
    command('setblock 64 65 10 minecraft:oak_fence', 'Changed the block')
    summon('cow', 62.5, 65, 10.5, f'{KEEP},Tags:["knotted"],leash:[I;64,65,10]')
    wait(1.0)
    before = diag('leashes-before')
    ev['leashBefore'] = command('data get entity @e[tag=leashed,limit=1] leash', r'entity data|Found no elements|No entity')[-160:]
    for x in range(60, 82, 3):
        command(f'tp @e[tag=holder] {x}.5 65 20.5', 'Teleported')
        wait(1.0)
    wait(2.0)
    ev['leashAfter'] = command('data get entity @e[tag=leashed,limit=1] leash', r'entity data|Found no elements|No entity')[-160:]
    ev['leashedEast'] = count('@e[tag=leashed,x=64,y=0,z=0,dx=200,dy=400,dz=200]')
    ev['knot'] = command('data get entity @e[tag=knotted,limit=1] leash', r'entity data|Found no elements|No entity')[-160:]
    d_leash = invariants('leashes', before)
    ev['leashRelocations'] = delta(d_leash, before, 'mobDecisionRelocations')
    check('leashes: the leashed cow was pulled into the next tile, still leashed, membership followed',
          ev['leashedEast'] == 1 and 'entity data' in ev['leashAfter'] and ev['leashRelocations'] >= 1,
          {k: ev[k] for k in ('leashedEast', 'leashAfter', 'leashRelocations')})
    check('leashes: the fence-knot leash on the tile edge holds', 'entity data' in ev['knot'], ev['knot'])

    # ------------------------------------------------------------------------------------------------ conversions
    # Isolated, so that only the conversions decide the counts: in g11r2 the wander zombies killed the cured villagers and
    # the villagers fled the lightning. A conversion copies tags and NoAI (ConversionType.convertCommon, 26.2), so every
    # group is counted exactly by tag and type. NoAI where the conversion ignores it (lightning: Pig/Villager.thunderHit;
    # cure: ZombieVillager.tick); AI and a pen where it needs it (piglin, hoglin: isConverting; husk, zombie: Zombie.tick).
    # No player is online here, so the bolts set no fire (ServerLevel.canSpreadFireAround).
    command('kill @e[type=!minecraft:player]', 'Killed|No entity')
    wait(2.0)
    command('kill @e[type=!minecraft:player]', 'Killed|No entity')          # their drops and XP orbs
    walls(6, 58, 14, 70)                                                    # piglin pen on the edge z=64
    walls(112, 58, 120, 70)                                                 # hoglin pen on the edge z=64, 100 blocks away
    wait(1.0)
    before = diag('conversions-before')
    for i in range(6):
        summon('pig', 63.5 + (i % 2), 65, 24.5 + 2 * i, f'{KEEP},NoAI:1b,Tags:["struck"]')
    for x, z in ((63.5, 44.5), (64.5, 44.5), (64.0, 45.5)):
        summon('villager', x, 65, z, f'{KEEP},NoAI:1b,Tags:["struck"]')
    for i in range(4):
        summon('piglin', 9.5 + (i % 2), 65, 62.5 + i, f'{KEEP},Tags:["timed"],TimeInOverworld:{280 + 4 * i}')
        summon('hoglin', 115.5 + (i % 2), 65, 62.5 + i, f'{KEEP},Tags:["timed"],TimeInOverworld:{285 + 3 * i}')
        summon('zombie_villager', 63.5 + (i % 2), 65, 118.5 + i, f'{KEEP},NoAI:1b,Tags:["timed"],ConversionTime:{40 + 10 * i}')
        summon('husk', 63.0 + (i % 2) * 2, 65, 52.5 + i, f'{KEEP},Tags:["timed"],DrownedConversionTime:{20 + 5 * i}')
        summon('zombie', 63.0 + (i % 2) * 2, 65, 75.5 - i, f'{KEEP},Tags:["timed"],DrownedConversionTime:{20 + 5 * i}')
    summon('tadpole', 63.5, 65, 70.5, f'{KEEP},Tags:["timed"],Age:23995')
    for i in range(4):
        summon('slime', 63.5 + (i % 2), 65, 13.5 + 1.5 * i, f'{KEEP},Tags:["split"],Size:1')
    wait(1.0)
    for i in range(6):
        command(f'summon minecraft:lightning_bolt {63.5 + (i % 2)} 65 {24.5 + 2 * i}', 'Summoned new')
    command('summon minecraft:lightning_bolt 64.0 65 44.8', 'Summoned new')
    command('kill @e[tag=split]', 'Killed')
    wait(25)                                 # TimeInOverworld 300, ConversionTime <= 70, DrownedConversionTime <= 35, Age +5
    conv = {}
    for tag, kind in (('struck', 'pig'), ('struck', 'zombified_piglin'), ('struck', 'villager'), ('struck', 'witch'),
                      ('timed', 'piglin'), ('timed', 'zombified_piglin'), ('timed', 'hoglin'), ('timed', 'zoglin'),
                      ('timed', 'zombie_villager'), ('timed', 'villager'), ('timed', 'tadpole'), ('timed', 'frog'),
                      ('timed', 'husk'), ('timed', 'zombie'), ('timed', 'drowned'), ('split', 'slime')):
        conv[f'{tag}.{kind}'] = count(f'@e[tag={tag},type=minecraft:{kind}]')
    ev['conversions'] = conv
    d_conv = invariants('conversions', before)
    check('conversions: lightning on the tile edge (6 pigs -> zombified piglins, 3 villagers -> witches)',
          conv['struck.pig'] == 0 and conv['struck.zombified_piglin'] == 6 and conv['struck.villager'] == 0
          and conv['struck.witch'] == 3, {k: v for k, v in conv.items() if k.startswith('struck.')})
    check('conversions: timed (4 piglins, 4 hoglins, 4 zombie villagers, 1 tadpole)', conv['timed.piglin'] == 0
          and conv['timed.zombified_piglin'] == 4 and conv['timed.hoglin'] == 0 and conv['timed.zoglin'] == 4
          and conv['timed.zombie_villager'] == 0 and conv['timed.villager'] == 4 and conv['timed.tadpole'] == 0
          and conv['timed.frog'] == 1, {k: conv[k] for k in ('timed.piglin', 'timed.zombified_piglin', 'timed.hoglin',
                                                             'timed.zoglin', 'timed.zombie_villager', 'timed.villager',
                                                             'timed.tadpole', 'timed.frog')})
    check('conversions: into and out of an A3 class (4 husks -> zombies, 4 zombies -> drowned)', conv['timed.husk'] == 0
          and conv['timed.zombie'] == 4 and conv['timed.drowned'] == 4, {k: conv[k] for k in ('timed.husk', 'timed.zombie', 'timed.drowned')})
    check('conversions: 4 medium slimes split on the tile edge (2-4 each)', 8 <= conv['split.slime'] <= 16, conv['split.slime'])
    command('kill @e[type=!minecraft:player]', 'Killed|No entity')          # a clean board for the portal phase
    wait(2.0)

    # ------------------------------------------------------------------------------------------------ projectiles
    before = diag('projectiles-before')
    for i in range(8):
        summon('snowball', 60.5, 70, 28.5 + i, 'Tags:["shot"],NoGravity:1b,Motion:[0.25d,0d,0d]')
    for i in range(4):
        summon('arrow', 68.5, 70, 32.5 + i, 'Tags:["shot"],NoGravity:1b,Motion:[-0.3d,0d,0d],pickup:0b')
    wait(6)
    d_proj = invariants('projectiles', before)
    ev['projectileRelocations'] = delta(d_proj, before, 'projectileRelocations')
    ev['projectileCleanups'] = delta(d_proj, before, 'projectileCleanupAdmissions')
    check('projectiles: memberships moved at the tile crossings', ev['projectileRelocations'] >= 8, ev['projectileRelocations'])
    check('projectiles: the cap discarded the oldest in the crowded tile', ev['projectileCleanups'] >= 2, ev['projectileCleanups'])
    command('kill @e[tag=shot]', 'Killed|No entity')

    # ------------------------------------------------------------------------------------------------ portals
    before = diag('portals-before')
    for x in (63.5, 64.5):
        # r3 diagnostics: named, so vanilla logs any death with its cause (bis-transitions-r2-d39: 5 portal entities in the
        # Nether, usual 6, none in the overworld, and nothing in the log says which one or why)
        summon('zombie', x, 65, 100.5, f'{KEEP},Tags:["portal"],CustomName:"PZ{int(x)}",UUID:{PORTAL_UUIDS["PZ" + str(int(x))]}')
        summon('cow', x, 65, 100.5, f'{KEEP},Tags:["portal"],CustomName:"PC{int(x)}",UUID:{PORTAL_UUIDS["PC" + str(int(x))]}')
    summon('minecart', 64.0, 65, 100.5, f'Tags:["portal"],UUID:{PORTAL_UUIDS["CART"]},Passengers:[{{id:"minecraft:cow",{KEEP},'
                                        f'Tags:["portal"],CustomName:"PCR",UUID:{PORTAL_UUIDS["PCR"]}}}]')
    portal_log_start = len(s.lines)
    summon('snowball', 63.5, 66.5, 97.5, 'Tags:["portal"],NoGravity:1b,Motion:[0d,0d,0.3d]')
    for i in range(4):
        summon('cow', 12.5 + i, 65, 12.5, f'{KEEP},Tags:["dimhop"]')
    wait(1.0)
    command(f'execute in {NETHER} run tp @e[tag=dimhop] -12 70 -12', 'Teleported')   # r10: ~31 blocks from the arrival portal
    wait(4)                                                                  # r10: count inside the 300-tick PortalCooldown
    ev['portalNether'] = count('@e[tag=portal,distance=..400]', NETHER)
    ev['portalOverworld'] = count('@e[tag=portal,distance=..400]', 'minecraft:overworld')
    # r3 diagnostics (no check): per type on both sides, and every logged death of a named portal mob
    ev['portalTypes'] = {f'{dim_name}:{kind}': count(f'@e[tag=portal,type=minecraft:{kind},distance=..400]', dim)
                         for dim_name, dim in (('nether', NETHER), ('overworld', 'minecraft:overworld'))
                         for kind in ('zombie', 'cow', 'minecart', 'snowball')}
    ev['portalDeaths'] = [l[-220:] for l in list(s.lines)[portal_log_start:] if re.search(r'\bP(Z|C)\w*\b.*died|died.*\bP(Z|C)', l)]
    # r7 diagnostics (no check): each portal entity by UUID (any level), then the per-type counts again 4 s later
    ev['portalProbe'] = {}
    for label, ints in PORTAL_UUIDS.items():
        u = uuid_str([int(v) for v in ints[3:-1].split(',')])
        ev['portalProbe'][label] = {'pos': reply(f'data get entity {u} Pos'),
                                    'portalCooldown': reply(f'data get entity {u} PortalCooldown')}
        if label == 'CART':
            ev['portalProbe'][label]['passengers'] = reply(f'data get entity {u} Passengers')
    wait(4)
    ev['portalTypesLater'] = {f'{dim_name}:{kind}': count(f'@e[tag=portal,type=minecraft:{kind},distance=..400]', dim)
                              for dim_name, dim in (('nether', NETHER), ('overworld', 'minecraft:overworld'))
                              for kind in ('zombie', 'cow', 'minecart', 'snowball')}
    env = dict(os.environ, NODE_PATH=str(NODE_MODULES))
    bot = subprocess.Popen(['node', str(BOT), '--host', '127.0.0.1', '--port', str(ports['java']), '--user', USER,
                            '--version', '1.21.11'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           text=True, env=env, cwd=run)
    threading.Thread(target=bot_reader, daemon=True).start()
    bot_wait('READY', 120)
    command(f'tp {USER} 63.9 65 104.5', 'Teleported')                       # south of the portal (plane z=100)
    command(f'give {USER} minecraft:ender_pearl 4', 'Gave')
    wait(1.5)
    pearls_before = diag('pearl-before')
    start = len(bot_lines)
    bot.stdin.write('throw 5\n')                                             # mineflayer yaw 0 = north (-z): into the portal
    bot.stdin.flush()
    bot_wait('THROWN', 30, start)
    wait(8)
    ev['dimhopNether'] = count('@e[tag=dimhop,distance=..400]', NETHER)
    ev['botNether'] = count(f'@a[name={USER},distance=..400]', NETHER)
    ev['dimhopOverworld'] = count('@e[tag=dimhop,distance=..400]', 'minecraft:overworld')   # r8 diagnostic, no check
    probe_start = len(s.lines)                                               # r9 diagnostic, no check
    for dim in ('minecraft:overworld', NETHER):
        for field in ('Pos', 'PortalCooldown'):
            s.console(f'execute in {dim} positioned 0 64 0 as @e[tag=dimhop,distance=..400] run data get entity @s {field}')
            wait(0.6)
    wait(1.0)
    ev['dimhopProbe'] = [l[-200:] for l in list(s.lines)[probe_start:] if 'has the following entity data' in l]
    ev['dimhopTpReply'] = command('execute in minecraft:overworld run tp @e[tag=dimhop] 14.5 65 14.5', 'Teleported')
    wait(3)
    ev['dimhopBack'] = count('@e[tag=dimhop,distance=..400]', 'minecraft:overworld')
    d_portal = invariants('portals', before)
    ev['pearlAdmissionsDelta'] = delta(d_portal, pearls_before, 'pearlCleanupAdmissions')
    check('portals: mobs, the cart with its rider and the snowball crossed into the Nether', ev['portalNether'] >= 6
          and ev['portalOverworld'] == 0, {k: ev[k] for k in ('portalNether', 'portalOverworld')})
    check('portals: command dimension moves both ways', ev['dimhopNether'] == 4 and ev['dimhopBack'] == 4,
          {k: ev[k] for k in ('dimhopNether', 'dimhopBack')})
    check('portals: the pearl through the portal moved its thrower to the Nether', ev['botNether'] == 1, ev['botNether'])

    # ------------------------------------------------------------------------------------------------ unload
    for i in range(12):
        summon('cow' if i % 2 else 'zombie', 284.5 + i, 65, 30.5 + (i % 3) * 4, f'{KEEP},Tags:["unload"]')
    for i in range(2):
        summon('minecart', 284.5 + 6 * i, 65, 20.5,
               f'Tags:["unload"],Motion:[0.4d,0d,0d],Passengers:[{{id:"minecraft:cow",{KEEP},Tags:["unload"]}}]')
    wait(3)
    before = diag('unload-before')
    ev['unloadBefore'] = count('@e[tag=unload]')
    command('forceload remove 256 0 319 63', 'Unmarked|not marked|No chunks')
    gone = None
    for _ in range(60):
        wait(0.5)
        if count('@e[tag=unload]') == 0:
            gone = diag('unloaded')
            break
    check('unload: the tile unloaded with its mobs and moving carts', gone is not None, ev['unloadBefore'])
    command('forceload add 256 0 319 63', 'Marked|already')
    back = 0
    for _ in range(60):
        wait(0.5)
        back = count('@e[tag=unload]')
        if back >= ev['unloadBefore']:
            break
    ev['unloadAfter'] = back
    wait(3)
    d_unload = invariants('unload', before)
    check('unload: every entity came back', back == ev['unloadBefore'] and ev['unloadBefore'] >= 16,
          {k: ev[k] for k in ('unloadBefore', 'unloadAfter')})

    # ------------------------------------------------------------------------------------------------ end
    bot.stdin.write('quit\n')
    bot.stdin.flush()
    try:
        bot.wait(15)
    except subprocess.TimeoutExpired:
        bot.kill()
    # g18 (D30, 2026-10-10): one kill was not enough. Chunks finishing generation/loading after it added a village and
    # its animals (saved world: 2 sheep + 2 cows = 4 live A3 memberships in 2 tiles, self-check orphans/stale 0), so the
    # zero check below measured that race, not the release path. Kill until nothing but players is alive (bounded,
    # every late arrival recorded), then require nothing alive on both sides of the final reading: a membership left
    # with no live entity is still a FAIL.
    end_kills = []
    alive_before = None
    seen_uuids = {}
    for _ in range(10):
        seen_uuids.update(uuids_now())                                       # r3: who is about to be killed
        killed = command('kill @e[type=!minecraft:player]', 'Killed|No entity')
        wait(3)
        alive_before = count('@e[type=!minecraft:player]')
        end_kills.append({'reply': killed[-60:] if killed else None, 'aliveAfter3s': alive_before})
        if alive_before == 0:
            break
    ev['endKills'] = end_kills
    d_end = invariants('end', d_unload)
    alive_after = count('@e[type=!minecraft:player]')
    check('end: nothing but players alive around the final reading', alive_before == 0 and alive_after == 0,
          {'before': alive_before, 'after': alive_after, 'kills': len(end_kills)})
    # r2 (2026-10-14, d42p-transitions-3A44): the reading 3 s after the last kill showed 2 A3 memberships with nothing alive
    # and orphans 0, and 0 at the stop line. `@e` skips dead entities (EntitySelectorParser adds Entity::isAlive) while
    # the self-check retires only removed or missing entities, and the bot quits in the same second as the first kill, so
    # mobs killed in chunks that stop entity-ticking stay dead-but-not-removed until their chunk unloads. The judged
    # reading is now the one after the memberships drain: poll up to 60 s; PASS only if every membership and cleanup
    # reaches 0 through the release path (orphans unchanged, nothing alive). A leaked membership never drains, and one
    # whose entity disappears is counted as an orphan. The immediate reading stays in evidence (endImmediate).
    ev['endImmediate'] = {k: d_end.get(k) for k in ('mobDecisionMemberships', 'projectileMemberships', 'pearlMemberships',
                                                     'pendingProjectileCleanups', 'pendingPearlCleanups', 'mobDecisionOrphans')}
    drained, series = d_end, []
    t_drain = time.time()
    keys = ('mobDecisionMemberships', 'projectileMemberships', 'pearlMemberships', 'pendingProjectileCleanups', 'pendingPearlCleanups')
    while any(drained.get(k) != 0 for k in keys) and time.time() - t_drain < 60:
        wait(3)
        drained = diag('end-drain')
        series.append({'sec': round(time.time() - t_drain, 1), **{k: drained.get(k) for k in keys + ('mobDecisionOrphans',)}})
    ev['endDrain'] = series
    alive_drained = count('@e[type=!minecraft:player]')
    check('end: all memberships and pending cleanups drain to 0 through the release path (orphans unchanged, nothing alive)',
          all(drained.get(k) == 0 for k in keys) and drained.get('mobDecisionOrphans') == d_end.get('mobDecisionOrphans')
          and alive_drained == 0,
          {'immediate': ev['endImmediate'], 'drained': {k: drained.get(k) for k in keys + ('mobDecisionOrphans',)},
           'seconds': round(time.time() - t_drain, 1), 'aliveAfterDrain': alive_drained})
    # r3 diagnostics (no check): which recorded entities the world still holds after the drain, and in what state
    ev['endUuidsRecorded'] = len(seen_uuids)
    ALL_UUIDS.update(seen_uuids)
    ev['allUuidsRecorded'] = len(ALL_UUIDS)                              # r4: every kill of the run
    if any(drained.get(k) != 0 for k in keys):
        stuck = [p for p in (probe(u, n) for u, n in sorted(ALL_UUIDS.items())) if p]
        ev['endStuck'] = stuck
        ev['endStuckMemberships'] = diag('end-stuck').get('mobDecisionMemberships')
        print('END-STUCK', json.dumps(stuck)[:3000], flush=True)
    res['totals'] = {k: d_end.get(k) for k in ('mobDecisionRelocations', 'projectileRelocations', 'protectionChecks', 'mobDecisionAllowed',
                                               'mobDecisionDeferred', 'mobDecisionDeadlines', 'trackerIndexChecks', 'spawnMemoChecks')}
except Exception as e:
    res['failure'] = repr(e)
    print('FAILURE', repr(e), flush=True)
finally:
    if bot is not None and bot.poll() is None:
        bot.kill()
    res['exit'] = s.stop()
lines = list(s.lines)
(run / 'bot.log').write_text('\n'.join(bot_lines) + '\n')
stop = next((l for l in reversed(lines) if 'PHOENIUM_STAGE2 stopped' in l), None)
res['engineStop'] = stop
res['stopFaults'] = {k: int(m.group(1)) for k in MUST_BE_ZERO for m in [re.search(rf'\b{k}=(\d+)', stop or '')] if m}
res['faultLines'] = [l for l in lines if 'PHOENIUM_ENGINE protection' in l][:20]
crash = sorted((run / 'server/crash-reports').glob('*')) if (run / 'server/crash-reports').exists() else []
res['crashReports'] = [str(c) for c in crash]
res['scan'] = L.scan(lines)
res['pass'] = (not res.get('failure') and all(c['ok'] for c in res['checks']) and len(res["checks"]) >= 40 and res["exit"] == 0
               and not crash and not res['scan']['badPatternLines'] and len(res['stopFaults']) == len(MUST_BE_ZERO)
               and sum(res['stopFaults'].values()) == 0 and not res['faultLines'])
res['endUtc'] = L.now()
(run / 'transitions-result.json').write_text(json.dumps(res, indent=1))
print(json.dumps({k: res[k] for k in ('name', 'pass', 'exit', 'crashReports', 'stopFaults', 'evidence')}, indent=1),
      sum(c['ok'] for c in res['checks']), '/', len(res['checks']), 'checks')
