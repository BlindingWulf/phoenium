"""Bounded installed client run. Only this script's child processes are stopped."""
import pathlib,subprocess,hashlib,json,shutil,socket,struct,time,sys,datetime,os,re
script_path=pathlib.Path(__file__).resolve()
work=(script_path.parents[2] if script_path.parent.name=='stage7' else
      script_path.parents[2]/'.candidate-worktrees/stage7-final-sol-ultra')
sys.path.insert(0,str(script_path.parent))
from campaign_paths import AUDIT as audit, BASE_AUDIT as base_audit
root=work.parent.parent
pinned_path=root/'.audit/container-close-admission-20260922/PINNED_PROFILE_MANIFEST.json'
pinned=json.loads(pinned_path.read_text(encoding='utf-8-sig'));template=pathlib.Path(pinned['template'])
name=sys.argv[1];workers=int(sys.argv[2]);assert workers in (1,4)
restart='--restart' in sys.argv
nether_only='--nether-only' in sys.argv
snapshot_probe='--snapshot-probe' in sys.argv
group=next((arg.partition('=')[2] for arg in sys.argv if arg.startswith('--group=')),'legacy')
cards_arg=next((arg.partition('=')[2] for arg in sys.argv if arg.startswith('--cards=')),None)
cards=cards_arg.split(',') if cards_arg else []
negative_unretained='--negative-unretained-fireball' in sys.argv
explicit_topology='--explicit-topology' in sys.argv  # A/B regions inside a dimension (dev fixture)
explicit_separate_b=next((arg.partition('=')[2] for arg in sys.argv if arg.startswith('--explicit-separate-b=')),None)
explicit_b_width=next((arg.partition('=')[2] for arg in sys.argv if arg.startswith('--explicit-b-width=')),None)
if negative_unretained and (group!='projectile-fireball' or cards!=['P0-fireball-nether-return'] or restart):
    raise RuntimeError('negative unretained control requires first-run exact P0 fireball selection')
assert not cards or (len(set(cards))==len(cards) and all(cards))
client_source=(work/'src/clientGameTest/java/dev/phoenium/clienttest/Stage7RepairClientTest.java').read_text()
known_groups={'legacy'}|set(re.findall(r'group\.equals\("([^"]+)"\)',client_source))
if group not in known_groups:raise RuntimeError('unbound client group: '+group)
exact_pairs=set(re.findall(r'group\.equals\("([^"]+)"\)\s*&&\s*selectedCards\.equals\("([^"]+)"\)',client_source))
no_entry_plan=base_audit/'NO_NATIVE_ENTRY_MATRIX_PLAN.json'
no_entry_allowed=(no_entry_plan.is_file() and
                  {item['card'] for item in json.loads(no_entry_plan.read_text(encoding='utf-8')).get('cards',[])
                   if item.get('fixtureState')=='PLANNED'} or set())
dynamic_no_entry=(group=='no-native-entry' and len(cards)==1 and cards[0] in no_entry_allowed
                  and 'noNativeEntryMatrix(selectedCards)' in client_source
                  and 'selectedCards.matches("N0-' in client_source)
dynamic_arrow=(group=='arrow-trident' and len(cards)==1
               and re.fullmatch(r'P2-(arrow|spectral_arrow|trident)-(NETHER_ENTRY|NETHER_RETURN)',cards[0])
               and 'arrowTridentMatrix(selectedCards)' in client_source
               and 'selectedCards.matches("P2-' in client_source)
dynamic_vehicle=(group=='vehicle-empty' and len(cards)==1
                 and re.fullmatch(r'V0-(acacia_boat|birch_boat|cherry_boat|dark_oak_boat|jungle_boat|mangrove_boat|oak_boat|pale_oak_boat|spruce_boat|bamboo_raft|minecart)-(NETHER_ENTRY|NETHER_RETURN)',cards[0])
                 and 'emptyVehicleMatrix(selectedCards)' in client_source
                 and 'selectedCards.matches("V0-' in client_source)
dynamic_chest_vehicle=(group=='vehicle-chest' and len(cards)==1
                       and re.fullmatch(r'V1-(acacia_chest_boat|birch_chest_boat|cherry_chest_boat|dark_oak_chest_boat|jungle_chest_boat|mangrove_chest_boat|oak_chest_boat|pale_oak_chest_boat|spruce_chest_boat|bamboo_chest_raft)-items-(NETHER_ENTRY|NETHER_RETURN)|V1-(oak_chest_boat|bamboo_chest_raft)-loot-(NETHER_ENTRY|NETHER_RETURN)',cards[0])
                       and 'chestVehicleMatrix(selectedCards)' in client_source
                       and 'selectedCards.matches("V1-' in client_source)
dynamic_chest_minecart=(group=='vehicle-minecart-chest' and len(cards)==1
                        and re.fullmatch(r'V2-chest_minecart-(items|loot)-(NETHER_ENTRY|NETHER_RETURN)',cards[0])
                        and 'chestMinecartMatrix(selectedCards)' in client_source
                        and 'selectedCards.matches("V2-chest_minecart-' in client_source)
if cards and ('phoenium.repair.cards' not in client_source or len(cards)!=1
              or ((group,cards[0]) not in exact_pairs and not dynamic_no_entry and not dynamic_arrow
                  and not dynamic_vehicle and not dynamic_chest_vehicle and not dynamic_chest_minecart)):
    raise RuntimeError('--cards unavailable: client has no matching exact selection branch')
seed=int(next((arg.partition('=')[2] for arg in sys.argv if arg.startswith('--seed=')),'0'))
candidate_override=next((arg.partition('=')[2] for arg in sys.argv if arg.startswith('--candidate=')),None)
run=audit/'installed'/name;server=run/'server'
java=pathlib.Path(r'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe')
ports=dict(java=25684,bedrock=19184,rcon=25784)
def sha(p):
    with p.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest().upper()
def rcon(command):
    with socket.create_connection(('127.0.0.1',ports['rcon']),timeout=2) as s:
        s.settimeout(60)
        def packet(i,t,body):
            b=body.encode();s.sendall(struct.pack('<iii',len(b)+10,i,t)+b+b'\0\0')
        def read():
            def n(count):
                b=b''
                while len(b)<count:
                    part=s.recv(count-len(b))
                    if not part:raise EOFError('RCON closed')
                    b+=part
                return b
            length=struct.unpack('<i',n(4))[0];b=n(length)
            if struct.unpack('<i',b[:4])[0]<0:raise RuntimeError('RCON authentication failed')
            return b[8:-2].decode(errors='replace')
        packet(1,3,'phoenium-stage7-local-test');read();packet(2,2,command);return read()
def copytree_immutable(source,target):
    shutil.copytree(source,target,copy_function=lambda s,d:os.link(s,d))
if not restart:
    run.mkdir(parents=True,exist_ok=False);server.mkdir()
    for n,h in pinned['providers'].items():assert sha(template/'mods'/n)==h,n
    for n,h in pinned['configFilesSha256'].items():assert sha(template/'config'/n)==h,n
    for n in ['libraries','versions']:copytree_immutable(template/n,server/n)
    shutil.copytree(template/'config',server/'config')
    for n in ['fabric-server-launch.jar','eula.txt']:shutil.copy2(template/n,server/n)
    (server/'mods').mkdir()
    for n in pinned['providers']:os.link(template/'mods'/n,server/'mods'/n)
    candidate=pathlib.Path(candidate_override) if candidate_override else work/'build/libs/phoenium-0.2.0-stage2.jar'
    digest=sha(candidate)
    immutable=run/('phoenium-stage7-'+digest[:12].lower()+'.jar');shutil.copy2(candidate,immutable)
    shutil.copy2(immutable,server/'mods'/immutable.name)
    probe=work/'build/test-harness/stage7-repair-probe.jar';shutil.copy2(probe,server/'mods'/probe.name)
    source_world=(root/'.audit/astra-max-mob-continuation-20260922-110554/manual-ready/server/STAGE7-LUNA-FINAL'
        if snapshot_probe else root/'.audit/astra-max-stage7-20260922/manual-ready/server/STAGE7-LUNA-FINAL')
    assert source_world.is_dir()
    source_hashes={str(p.relative_to(source_world)):sha(p) for p in source_world.rglob('*') if p.is_file() and p.name!='session.lock'}
    target_world=server/('STAGE7-POSTCRASH-COPY' if snapshot_probe else 'STAGE7-REPAIR-W'+str(workers))
    shutil.copytree(source_world,target_world)
    assert all(sha(target_world/n)==h for n,h in source_hashes.items())
    (run/'world-copy.json').write_text(json.dumps({'source':str(source_world),'destination':str(target_world),'hashes':source_hashes},indent=2))
    properties=template.joinpath('server.properties').read_text().splitlines()
    overrides={'server-port':str(ports['java']),'level-name':target_world.name,
        'enable-rcon':'true','rcon.port':str(ports['rcon']),'rcon.password':'phoenium-stage7-local-test',
        'motd':'Phoenium Stage7 automated isolated test'}
    properties=[line.split('=')[0]+'='+overrides.pop(line.split('=')[0]) if '=' in line and line.split('=')[0] in overrides else line for line in properties]
    properties += [k+'='+v for k,v in overrides.items()]
    (server/'server.properties').write_text('\n'.join(properties)+'\n')
    geyser=server/'config/Geyser-Fabric/config.yml';data=geyser.read_text();assert '19172' in data
    geyser.write_text(data.replace('19172',str(ports['bedrock'])))
    manifest=dict(head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=work,text=True).strip(),
        sourceStatus=subprocess.check_output(['git','status','--short'],cwd=work,text=True),candidateSource=str(candidate),
        candidate=str(immutable),candidateSha256=digest,candidateBytes=immutable.stat().st_size,
        probeSha256=sha(probe),providers=pinned['providers'],pinnedManifest=str(pinned_path),ports=ports,workers=workers,
        configHashes={n:sha(server/'config'/n) for n in pinned['configFilesSha256']},
        propertySha256=sha(server/'server.properties'),
        overrides=['isolated TCP/UDP/RCON ports','RCON enabled for fixture setup and read-only observations','fresh level-name and test motd','separate test-only observer JAR'],
        javaVersion=subprocess.check_output([str(java),'-version'],stderr=subprocess.STDOUT,text=True),
        group=group,cards=cards,seed=seed,negativeUnretainedFireballControl=negative_unretained,
        explicitTopology=explicit_topology,explicitBWidth=explicit_b_width,explicitSeparateB=explicit_separate_b)
    manifest['testSourceHashes']={str(p.relative_to(work)):sha(p) for base in [work/'src/clientGameTest',work/'src/test/java/dev/phoenium/stage7repair',work/'src/stage7RepairHarness'] for p in base.rglob('*') if p.is_file()}
    (run/'inputs.json').write_text(json.dumps(manifest,indent=2))
else:
    manifest=json.loads((run/'inputs.json').read_text());digest=manifest['candidateSha256']
    assert group==manifest.get('group','legacy') and cards==manifest.get('cards',[]) and seed==manifest.get('seed',0)
    assert not manifest.get('negativeUnretainedFireballControl',False)
    assert explicit_topology==manifest.get('explicitTopology',False),'restart topology mode must match the first phase'
    assert workers==manifest['workers'];assert all(sha(server/'mods'/n)==h for n,h in pinned['providers'].items())
    assert sha(server/'mods'/pathlib.Path(manifest['candidate']).name)==digest
    assert sha(server/'mods'/'stage7-repair-probe.jar')==manifest['probeSha256']
for port,kind in [(ports['java'],socket.SOCK_STREAM),(ports['bedrock'],socket.SOCK_DGRAM),(ports['rcon'],socket.SOCK_STREAM)]:
    with socket.socket(socket.AF_INET,kind) as s:s.bind(('127.0.0.1',port))
phase='restart' if restart else 'first';console=run/(phase+'-server-console.log');clientlog=run/(phase+'-client.log')
assert not console.exists(), 'preserved phase must not be overwritten'
command=[str(java),'-Xmx2G','-Dphoenium.test.diagnostics=true','-Dphoenium.gameplay.workers='+str(workers),'-Dmixin.debug.export=true',
    '-Djava.net.useSystemProxies=false','-Dminecraft.api.session.host=http://127.0.0.1:9','-Dminecraft.api.services.host=http://127.0.0.1:9','-jar','fabric-server-launch.jar','nogui']
if negative_unretained:
    command.insert(2,'-Dphoenium.stage7.negativeUnretainedFireball=true')
if explicit_topology:
    command[2:2]=['-Dphoenium.stage3.explicitTopology=true','-Dphoenium.test.chunkFixtureSupport=true']
    if explicit_b_width:command.insert(2,'-Dphoenium.stage3.explicitBWidth='+explicit_b_width)
    if explicit_separate_b:command.insert(2,'-Dphoenium.stage3.explicitSeparateBX='+explicit_separate_b)
result={'command':command,'explicitTopology':explicit_topology,'phase':phase,'startUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'candidateSha256':digest}
process=None;client=None
def logtext():return console.read_text(errors='replace') if console.exists() else ''
def failed_log(text):
    return any(x in text for x in ['scheduler=FAILED','Encountered an unexpected exception','STAGE7_OBSERVER_FAILED','STAGE7_PORTAL_TX_VIOLATION','Mixin apply for mod','Error executing task on Server','Failed to handle packet','Exception ticking world','Could not execute entrypoint'])
class SnapshotComplete(Exception):pass
try:
    with console.open('wb') as output:
        process=subprocess.Popen(command,cwd=server,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        result['pid']=process.pid;(run/(phase+'-process.json')).write_text(json.dumps(result,indent=2));print('SERVER',process.pid,name,phase,flush=True)
        deadline=time.monotonic()+240
        while time.monotonic()<deadline:
            if process.poll() is not None or failed_log(logtext()):raise RuntimeError('server bootstrap failed')
            if 'Done (' in logtext() and 'Started Geyser on 127.0.0.1:'+str(ports['bedrock']) in logtext():break
            time.sleep(.3)
        else:raise RuntimeError('bootstrap timeout')
        print('SERVER READY',flush=True)
        setup=[]
        if not snapshot_probe:
            for dimension in ['minecraft:overworld','minecraft:the_nether']:
                command='execute in '+dimension+' run gamerule minecraft:keep_inventory'
                setup.append([command,rcon(command)])
        if not restart and not snapshot_probe and group in ('legacy','selection-holes','merchant-holes','armor-stand','projectile-fireball','hurting-projectiles','arrow-trident','throwable-projectiles','passenger-graph','vehicle-empty','vehicle-chest','vehicle-minecart-chest','mob-prefix','no-native-entry'):
            for world in ['minecraft:overworld','minecraft:the_nether']:
                prefix='execute in '+world+' run '
                for c in ['forceload add -16 -16 16 16','fill -12 64 -12 12 64 12 minecraft:sea_lantern',
                    'fill -12 65 -12 12 76 12 minecraft:air','fill -12 76 -12 12 76 12 minecraft:glass',
                    'fill -12 65 -12 -12 75 12 minecraft:glass','fill 12 65 -12 12 75 12 minecraft:glass',
                    'fill -12 65 -12 12 75 -12 minecraft:glass','fill -12 65 12 12 75 12 minecraft:glass',
                    'fill -2 64 0 2 69 0 minecraft:obsidian','fill -1 65 0 1 68 0 minecraft:air',
                    'setblock -1 65 0 minecraft:fire','fill -2 65 -10 2 69 -10 minecraft:stone',
                    'execute if block 0 66 0 minecraft:nether_portal']:
                    response=rcon(prefix+c);setup.append([prefix+c,response])
                    (run/'setup.json').write_text(json.dumps(setup,indent=2))
                    if 'execute if block' in c:assert 'Test passed' in response,repr([prefix+c,response])
            for c in ['fill 12 64 3 42 68 8 minecraft:glass hollow','fill 12 64 3 42 64 8 minecraft:sea_lantern',
                'fill 12 65 4 12 67 7 minecraft:air','fill -10 64 4 -6 68 8 minecraft:glass hollow']:
                setup.append([c,rcon('execute in minecraft:overworld run '+c)])
            setup.append(['setworldspawn',rcon('setworldspawn 8 65 5')]);(run/'setup.json').write_text(json.dumps(setup,indent=2))
        if snapshot_probe:
            deadline=time.monotonic()+30
            while time.monotonic()<deadline:
                if process.poll() is not None or failed_log(logtext()):raise RuntimeError('post-crash copy failed during observation')
                time.sleep(.2)
            result['snapshotDiagnostics']=rcon('phoenium diagnostics')
            result['snapshotPass']='lifecycle=RUNNING' in result['snapshotDiagnostics'] and 'scheduler=RUNNING' in result['snapshotDiagnostics']
            raise SnapshotComplete()
        if nether_only:
            command='execute in minecraft:the_nether run summon minecraft:magma_cube 0.5 65 2.5 {Tags:["baseline_nether_cube"],PersistenceRequired:1b,Size:0,Motion:[0.0d,0.0d,-0.8d]}'
            result['netherSummon']=[command,rcon(command)]
            deadline=time.monotonic()+90
            while time.monotonic()<deadline:
                if failed_log(logtext()):
                    result['netherCrashObserved']='regional service producer reached foreign owner' in logtext()
                    raise RuntimeError('baseline Nether cube reached installed owner failure')
                if process.poll() is not None:raise RuntimeError('baseline server exited')
                time.sleep(.2)
            raise RuntimeError('Nether cube did not reach owner failure in 90 seconds')
        clientcmd=[str(work/'gradlew.bat'),'-I',str(work/'scripts/stage7-repair-client.gradle'),'runClientGameTest','--console=plain',
            '-PrepairAddress=127.0.0.1:'+str(ports['java']),'-PrepairRconPort='+str(ports['rcon']),
            '-PrepairEvidence='+str(run/(phase+'-client-receipt.json')),'-PrepairSha256='+digest,'-PrepairRestart='+str(restart).lower()]
        clientcmd.append('-PrepairGroup='+group)
        if cards:clientcmd.append('-PrepairCards='+','.join(cards))
        result['clientCommand']=clientcmd
        with clientlog.open('wb') as clientout:
            client=subprocess.Popen(clientcmd,cwd=work,stdout=clientout,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
            print('CLIENT',client.pid,flush=True);deadline=time.monotonic()+1500;seen=set()
            while client.poll() is None and time.monotonic()<deadline:
                for line in clientlog.read_text(errors='replace').splitlines():
                    if 'STAGE7_CARD_PASS' in line and line not in seen:print(line,flush=True);seen.add(line)
                if process.poll() is not None or failed_log(logtext()):raise RuntimeError('installed server lifecycle failed; stopping this run at first cause')
                time.sleep(.5)
            if client.poll() is None:raise RuntimeError('client phase timed out')
            result['clientExitCode']=client.returncode
            if client.returncode:raise RuntimeError('client card failed; see preserved client receipt/log')
        result['clientPass']=json.loads((run/(phase+'-client-receipt.json')).read_text())['pass']
        if cards:
            completed_cards=[card['card'] for card in json.loads((run/(phase+'-client-receipt.json')).read_text())['cards']]
            if set(completed_cards)!=set(cards) or len(completed_cards)!=len(cards):
                raise RuntimeError('selected cards were not exactly completed: '+repr((cards,completed_cards)))
        tx_checks=[]
        for _ in range(40):
            raw=rcon('stage7-tx status')
            try:tx=json.loads(raw[raw.index('{'):])
            except (ValueError,json.JSONDecodeError) as invalid:
                raise RuntimeError('unreadable stage7-tx status: '+repr(raw)) from invalid
            tx_checks.append(tx)
            if tx.get('candidateSha256')!=digest or tx.get('testSourceSha256')!=manifest['probeSha256']:
                raise RuntimeError('stage7-tx status product/probe hash mismatch: '+repr(tx))
            if tx.get('violations',0)>0:break
            if tx.get('pending',0)==0:break
            time.sleep(.25)
        result['txStatusRaw']=raw;result['txStatus']=tx;result['txStatusChecks']=tx_checks
        result['txPass']=tx.get('violations')==0 and tx.get('pending')==0
        if negative_unretained and tx.get('violations',0)==0:
            result['txPass']=False
            raise RuntimeError('unretained fireball negative control did not trigger an observer violation')
        # Same-dimension gateways keep the native object and do not enter the
        # cross-dimension replacement observer. Their exact contact, ticket,
        # tracking and owner witnesses are checked by the G1 card/importer.
        # Restart cards verify the saved world and client/session health. Some (including
        # legacy I8) intentionally make no new portal contact after the server restarts.
        # Still reject any violation or pending transaction observed in this phase.
        if not restart and group not in ('bed-anchor','no-native-entry','same-dimension-gateway','same-dimension-gateway-ab') and tx.get('events',0)==0:
            result['txPass']=False
            raise RuntimeError('stage7-tx observed no portal transaction events')
        if not result['txPass']:
            raise RuntimeError('stage7-tx violation or pending transaction: '+repr(tx))
        if group=='same-dimension-gateway-ab':
            # Ordered owner witnesses for the foreign-owned gateway ticket (see PortalTicketBoundary).
            lines=logtext().splitlines()
            def first(pattern):
                return next((i for i,line in enumerate(lines) if re.search(pattern,line)),None)
            deferred=first(r'PHOENIUM_PORTAL_TICKET phase=DEFERRED .*requestedUnder=.*stage3-fixture-a\] destinationOwner=.*stage3-fixture-b')
            placed=first(r'PHOENIUM_PORTAL_TICKET phase=PLACED .*destinationOwner=.*stage3-fixture-b')
            replay=first(r'PHOENIUM_PORTAL_TICKET phase=INLINE .*callerOwner=.*stage3-fixture-b\] chunkOwner=.*stage3-fixture-b')
            moved=first(r'PHOENIUM_ENTITY_RELOCATION .*from=.*stage3-fixture-a\] to=.*stage3-fixture-b')
            result['gatewayAbSequence']=dict(deferredUnderA=deferred,replayUnderB=replay,placed=placed,relocatedToB=moved)
            ordered=None not in (deferred,replay,placed,moved) and deferred<replay<=placed
            result['gatewayAbOrdered']=ordered
            if not ordered:
                result['txPass']=False
                raise RuntimeError('A/B gateway owner sequence not observed: '+repr(result['gatewayAbSequence']))
except SnapshotComplete:pass
except BaseException as error:
    result['failure']=str(error);print('FAILED',error,flush=True)
finally:
    if process and process.poll() is None:
        try:process.stdin.write(b'save-all\nstop\n');process.stdin.flush();result['normalStopRequested']=True
        except (BrokenPipeError,OSError):pass
        try:process.wait(timeout=60)
        except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=15);result['forcedOwnServerStop']=True
    if client and client.poll() is None:
        # Disconnect by stopping our server; give the test its chance to record failure/exit.
        try:client.wait(timeout=45)
        except subprocess.TimeoutExpired:result['clientStillRunningPid']=client.pid
    result['serverExitCode']=None if process is None else process.poll()
    result['logFailure']=failed_log(logtext());result['normalSave']='All dimensions are saved' in logtext()
    tx_lines=[line for line in logtext().splitlines() if 'STAGE7_PORTAL_TX' in line]
    tx_trace=run/(phase+'-portal-transactions.log');tx_trace.write_text('\n'.join(tx_lines)+('\n' if tx_lines else ''))
    result['txTracePath']=str(tx_trace);result['txTraceSha256']=sha(tx_trace);result['txTraceLineCount']=len(tx_lines)
    world_copy=json.loads((run/'world-copy.json').read_text()); result['sourceWorldChanges']=[n for n,h in world_copy['hashes'].items() if sha(pathlib.Path(world_copy['source'])/n)!=h]
    result['providerHashes']={n:sha(server/'mods'/n) for n in pinned['providers']}
    result['installedCandidateSha256']=sha(server/'mods'/pathlib.Path(manifest['candidate']).name)
    result['testSourceChanged']=[n for n,h in manifest.get('testSourceHashes',{}).items() if sha(work/n)!=h]
    result['configChanges']=[n for n,h in manifest['configHashes'].items() if sha(server/'config'/n)!=h]
    result['pass']=bool(result.get('clientPass') or result.get('snapshotPass')) and result.get('txPass',snapshot_probe) and not result.get('failure') and not result['logFailure'] and result['normalSave'] and not result.get('forcedOwnServerStop') and result['installedCandidateSha256']==digest and not result['testSourceChanged'] and not result['sourceWorldChanges'] and not result['configChanges']
    (run/(phase+'-result.json')).write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2),flush=True)
sys.exit(0 if result['pass'] else 1)
