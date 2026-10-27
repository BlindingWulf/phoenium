"""1 Hz process/host sampler (psutil) -> cpu.jsonl. Process CPU is in 'cores' (1.0 = one logical CPU fully busy)."""
from __future__ import annotations

import json
import pathlib
import threading
import time

import psutil


def tcp_state_counts() -> dict:
    counts: dict[str, int] = {}
    try:
        for conn in psutil.net_connections(kind="tcp"):
            counts[conn.status] = counts.get(conn.status, 0) + 1
    except (psutil.AccessDenied, OSError):
        return {"error": "net_connections unavailable"}
    return counts


def port_in_use(port: int, udp: bool = False) -> bool:
    kind = "udp" if udp else "tcp"
    try:
        for conn in psutil.net_connections(kind=kind):
            if conn.laddr and conn.laddr.port == port and (udp or conn.status == psutil.CONN_LISTEN):
                return True
    except (psutil.AccessDenied, OSError):
        pass
    return False


class _Tree:
    """CPU/RSS of a process and its children (bot controllers spawn node children)."""

    def __init__(self, pid: int):
        self.root = psutil.Process(pid)
        self.known: dict[int, psutil.Process] = {}

    def sample(self) -> dict | None:
        try:
            procs = [self.root] + self.root.children(recursive=True)
        except psutil.NoSuchProcess:
            return None
        cpu = rss = threads = 0.0
        for p in procs:
            proc = self.known.setdefault(p.pid, p)
            try:
                with proc.oneshot():
                    cpu += proc.cpu_percent(None)
                    rss += proc.memory_info().rss
                    threads += proc.num_threads()
            except psutil.NoSuchProcess:
                self.known.pop(p.pid, None)
        return {"cores": round(cpu / 100.0, 3), "rssMB": round(rss / 2**20, 1), "threads": int(threads), "procs": len(procs)}


class Sampler(threading.Thread):
    def __init__(self, out: pathlib.Path, interval: float = 1.0):
        super().__init__(daemon=True, name="stage9-sampler")
        self.out, self.interval = out, interval
        self.targets: dict[str, _Tree] = {}
        self._halt = threading.Event()
        psutil.cpu_percent(None, percpu=True)

    def watch(self, name: str, pid: int) -> None:
        self.targets[name] = _Tree(pid)

    def run(self) -> None:
        with open(self.out, "a", encoding="utf-8") as f:
            while not self._halt.wait(self.interval):
                per_core = psutil.cpu_percent(None, percpu=True)
                vm = psutil.virtual_memory()
                row = {"wallMs": int(time.time() * 1000), "hostCpuPct": round(sum(per_core) / len(per_core), 1),
                       "hostCoreMaxPct": max(per_core), "hostAvailMB": round(vm.available / 2**20)}
                for name, tree in list(self.targets.items()):
                    row[name] = tree.sample()
                f.write(json.dumps(row) + "\n")
                f.flush()

    def stop(self) -> None:
        self._halt.set()
        self.join(5)
