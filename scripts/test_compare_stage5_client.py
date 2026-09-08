"""Unit gate/ordering negatives; mocked restart receipts are never installed evidence."""
import copy,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from test_compare_stage4_trace import BASE
from compare_stage5_client import compare

class ClientGateTest(unittest.TestCase):
    def inputs(self,root):
        report=copy.deepcopy(BASE);report['missing']=[]
        report['events']+=['topology.merge current=true','topology.split current=true','block.deferred pending=true']
        report['events']+=[f'{kind} contextRegion=stage3-fixture-{owner}' for kind in ('topology.body','tracking.body') for owner in ('a','b')]
        for name in ('reference','candidate'):
            p=root/name/'evidence';p.mkdir(parents=True)
            (p/'STAGE4-CLIENT.json').write_text(json.dumps(dict(realTcpConnection=True,microsoftAuthentication=False,serverExitCode=0,
                viewDistance=5,simulationDistance=5,probeSha256='B'*64,productionSha256='A'*64)))
            for phase in ('first','restart'):(p/f'production-identity-{phase}.json').write_text(json.dumps(dict(**{'pass':True},expectedSha256='A'*64,installedSha256='A'*64)))
            (p/'STAGE4-CLIENT-before-restart-trace.json').write_text(json.dumps(report))
        return root/'reference',root/'candidate'

    def test_native_restart_gate_and_exact_saved_delay_are_mandatory(self):
        with tempfile.TemporaryDirectory() as temp:
            paths=self.inputs(Path(temp))
            for receipts in ([{'result':'PASS','savedDelay':369}]*2,
                [{'result':'PARTIAL','savedDelay':369}]*2,
                [{'result':'PASS','savedDelay':369},{'result':'PASS','savedDelay':368}]):
                with patch('compare_stage5_client.restart',side_effect=receipts):
                    if receipts[0]['result']=='PASS' and receipts[0]==receipts[1]:self.assertEqual(compare(*paths)['status'],'PASS')
                    else:
                        with self.assertRaises(ValueError):compare(*paths)

    def test_original_packet_payload_order_missing_owners_and_swapped_artifact_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            paths=self.inputs(Path(temp));p=paths[1]/'evidence/STAGE4-CLIENT-before-restart-trace.json';base=json.loads(p.read_text())
            changes=[lambda d:d['events'].__setitem__(6,d['events'][6].replace('xa=1','xa=2')),
                lambda d:d['events'].__setitem__(slice(5,7),list(reversed(d['events'][5:7]))),
                lambda d:d['events'].pop(),lambda d:d.update(result='PARTIAL'),
                lambda d:d.update(events=[e for e in d['events'] if not e.startswith('tick.native.unpack ')])]
            for change in changes:
                d=copy.deepcopy(base);change(d);p.write_text(json.dumps(d))
                with patch('compare_stage5_client.restart',return_value={'result':'PASS','savedDelay':369}):
                    try:result=compare(*paths);self.assertEqual(result['status'],'FAIL')
                    except ValueError:pass
            p.write_text(json.dumps(base))
            identity=paths[1]/'evidence/production-identity-restart.json';d=json.loads(identity.read_text());d['installedSha256']='C'*64;identity.write_text(json.dumps(d))
            with patch('compare_stage5_client.restart',return_value={'result':'PASS','savedDelay':369}):
                with self.assertRaises(ValueError):compare(*paths)

if __name__=='__main__':unittest.main()
