"""Sequential, bounded Phoenium-only benchmark. Run without another live campaign."""
import argparse
import json
from pathlib import Path
import statistics
import subprocess
import sys
from compare_stage5_gameplay import compare, require
from run_stage4_acceptance import sha256
from validate_stage5_persistence import validate

def distribution(values):
    ordered=sorted(values)
    def percentile(q):
        index=(len(ordered)-1)*q; low=int(index); high=min(low+1,len(ordered)-1)
        return ordered[low]+(ordered[high]-ordered[low])*(index-low)
    return dict(count=len(values),mean=statistics.mean(values),median=statistics.median(values),
        p95=percentile(.95),p99=percentile(.99),minimum=min(values),maximum=max(values),
        standardDeviation=statistics.pstdev(values))

def summarize(root):
    root=Path(root); evidence=root/"evidence"; samples=[]
    for path in sorted(evidence.glob("sample-*.json")):
        if path.stem.endswith("-before"):continue
        after=json.loads(path.read_text());before=json.loads(path.with_stem(path.stem+"-before").read_text())
        elapsed=after["elapsedNanos"]/1e9; cpu=(after["resources"]["processCpuNanos"]-before["resources"]["processCpuNanos"])/1e9
        completed=sum(row["output"] for row in after["furnaces"])-sum(row["output"] for row in before["furnaces"])
        require(completed>0,"benchmark did not finish offered work")
        row=dict(label=path.stem,ticks=after["ticks"],furnaces=len(after["furnaces"]),completedSmelts=completed,
            elapsedSeconds=elapsed,smeltsPerSecond=completed/elapsed,tickMillis=distribution([n/1e6 for n in after["tickNanos"]]),
            cpuSeconds=cpu,averageCpuCores=cpu/elapsed,heapUsedBytes=after["resources"]["heapUsedBytes"],
            heapCommittedBytes=after["resources"]["heapCommittedBytes"],nonHeapUsedBytes=after["resources"]["nonHeapUsedBytes"])
        if "allocatedBytes" in after["resources"] and "allocatedBytes" in before["resources"]:
            row["allocatedBytes"]=after["resources"]["allocatedBytes"]-before["resources"]["allocatedBytes"]
            row["allocatedBytesPerTick"]=row["allocatedBytes"]/row["ticks"]
        else: row["allocationUnavailable"]=True
        row["gc"]={name:{metric:values[metric]-before["resources"]["gc"][name][metric] for metric in ("count","millis")}
            for name,values in after["resources"]["gc"].items()}
        current=after["gameplay"];prior=before["gameplay"]
        if isinstance(current,dict):
            claims=current["claims"]-prior["claims"]
            bodies=current["bodies"]-prior["bodies"]
            row.update(localClaims=claims,localOriginalBodies=bodies,
                orderedNativeCallbacks=row["ticks"]*row["furnaces"]-bodies,
                meanLocalQueueMicros=(current["queueNanos"]-prior["queueNanos"])/max(1,claims)/1000,
                maximumQueueMicrosSinceStartup=current["maxQueueNanos"]/1000,
                localOriginalBodyMillis=(current["bodyNanos"]-prior["bodyNanos"])/1e6,
                maximumSimultaneousOriginalBodies=current["maxSimultaneousBodies"],retainedBodyIntervals=len(current["intervals"]))
        samples.append(row)
    process=json.loads((root/"process-metrics.json").read_text())
    commands=json.loads((root/"report.json").read_text())["commands"]
    first=next(item["atSeconds"] for item in commands if item["command"].startswith("stage5 measure sample-1 "))
    last=next(item["atSeconds"] for item in commands if item["command"]=="stage5 dirty")
    measured=[row for row in process["samples"] if first<=row["atSeconds"]<=last]
    require(measured,"no process memory samples during offered work")
    return dict(samples=samples,measuredWorkingSetBytes=distribution([r["rssBytes"] for r in measured]),
        processSampleIntervalMillis=process["sampleIntervalMillis"],hostPhysicalMemoryBytes=process["hostPhysicalMemoryBytes"],
        physicalCpuCount=process["physicalCpuCount"],logicalCpuCount=process["logicalCpuCount"],
        meanTickMillis=statistics.mean(s["tickMillis"]["mean"] for s in samples),
        meanSmeltsPerSecond=statistics.mean(s["smeltsPerSecond"] for s in samples))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--output",type=Path,required=True)
    p.add_argument("--repeats",type=int,choices=range(2,5),default=3)
    p.add_argument("--warmup",type=int,default=1200);p.add_argument("--samples",type=int,default=5)
    p.add_argument("--ticks",type=int,default=1200);p.add_argument("--furnaces-per-region",type=int,default=256)
    args=p.parse_args();root=args.output.resolve();require(not root.exists(),"benchmark output already exists");root.mkdir(parents=True)
    production=Path("build/libs/phoenium-0.2.0-stage2.jar");probe=Path("build/test-harness/stage4-acceptance-probe.jar")
    production_sha=sha256(production);probe_sha=sha256(probe);results=[]
    configurations=[("frozen",1),("candidate",1),("candidate",2),("candidate",4)]
    for repeat in range(args.repeats):
        order=configurations[repeat:]+configurations[:repeat]
        for layout in ("distributed","concentrated"):
            runs={}
            for kind,workers in order:
                require(sha256(production)==production_sha and sha256(probe)==probe_sha,"artifact changed during benchmark")
                name=f"{layout}-r{repeat+1}-{kind}-{workers}";output=root/name;runs[(kind,workers)]=output
                command=[sys.executable,"scripts/run_stage5_gameplay.py","--output",str(output),"--workers",str(workers),
                    "--layout",layout,"--callbacks-per-region","256","--furnaces-per-region",str(args.furnaces_per_region),
                    "--warmup",str(args.warmup),"--samples",str(args.samples),"--ticks",str(args.ticks)]
                if kind=="frozen":command += ["--reference","--production","run/stage4/final-artifacts/phoenium-0.2.0-stage2.jar"]
                print("MEASURING "+name,flush=True)
                with (root/(name+".log")).open("w") as log: subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True)
                record=dict(name=name,layout=layout,repeat=repeat+1,kind=kind,workers=workers,**summarize(output));results.append(record)
                disk=validate(output/"evidence/final.json",output/"evidence/mca-before-restart")
                (output/"furnace-persistence.json").write_text(json.dumps(disk,indent=2))
                print(json.dumps({k:record[k] for k in ("name","meanTickMillis","meanSmeltsPerSecond")}),flush=True)
                (root/"samples.json").write_text(json.dumps(results,indent=2))
            for workers in (1,2,4):
                comparison=compare(runs[("frozen",1)],runs[("candidate",workers)])
                (root/f"compare-{layout}-r{repeat+1}-{workers}.json").write_text(json.dumps(comparison,indent=2))
                if workers > 1:
                    serial_control=compare(runs[("candidate",1)],runs[("candidate",workers)])
                    (root/f"serial-control-{layout}-r{repeat+1}-{workers}.json").write_text(json.dumps(serial_control,indent=2))
    (root/"report.json").write_text(json.dumps(dict(status="PASS_BENCHMARK_CAPTURE",productionSha256=production_sha,
        probeSha256=probe_sha,settings=vars(args)|{"output":str(root)},runs=results),indent=2))
    print("PASS_BENCHMARK_CAPTURE",flush=True)

if __name__=="__main__":main()
