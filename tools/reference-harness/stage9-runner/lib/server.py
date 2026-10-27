"""Minecraft server process for Stage 9 runs: JFR + GC logging, telemetry dir, readiness gates, clean stop."""
from __future__ import annotations

import pathlib
import re
import subprocess
import threading
import time

JAVA = r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe"
JCMD = r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\jcmd.exe"

# Log lines that always mean a broken run (Stage 8 BAD_PATTERNS, kept identical so verdicts are comparable).
BAD_RE = re.compile("|".join([
    "Mixin apply failed", "InvalidMixinException", "InjectionError", "MixinTransformerError", "NoSuchMethodError",
    "NoSuchFieldError", "VerifyError", "OutOfMemoryError", "Watchdog", "ownership violation", r"transaction.*violation",
    "ClassNotFoundException", "NoClassDefFoundError", "IncompatibleClassChangeError", "AbstractMethodError",
    "Critical injection failure", "injection failure", "Mixin transformation of", "Unable to apply mixin"]), re.I)
# Known environmental noise (also present on vanilla control servers on this PC).
NOISE_RE = re.compile(r"Perflib|oshi\.|OSProcess|Fernflower could not be loaded", re.I)


class Server:
    def __init__(self, directory: pathlib.Path, run_dir: pathlib.Path, *, heap: str = "4G", xms: str | None = None,
                 sysprops: dict | None = None, extra_jvm: list[str] | None = None, jfr: bool = True,
                 jfr_settings: str = "default"):
        self.dir, self.run_dir = directory, run_dir
        (run_dir / "jvm").mkdir(parents=True, exist_ok=True)
        self.log_path = run_dir / "server.log"
        jvm = [f"-Xmx{heap}"] + ([f"-Xms{xms}"] if xms else [])
        if jfr:
            jvm.append(f"-XX:StartFlightRecording=filename={run_dir / 'jvm' / 'server.jfr'},settings={jfr_settings},"
                       "dumponexit=true,name=stage9")
        jvm.append(f"-Xlog:gc*,safepoint:file={run_dir / 'jvm' / 'gc.log'}:time,uptime,level,tags:filecount=0")
        props = {"stage9.telemetry.dir": str(run_dir / "telemetry-raw"), "java.net.useSystemProxies": "false",
                 "minecraft.api.session.host": "http://127.0.0.1:9", "minecraft.api.services.host": "http://127.0.0.1:9"}
        props.update(sysprops or {})
        self.cmd = [JAVA, *jvm, *(extra_jvm or []), *[f"-D{k}={v}" for k, v in props.items()],
                    "-jar", "fabric-server-launch.jar", "nogui"]
        self.proc: subprocess.Popen | None = None
        self.lines: list[str] = []
        self.bad: list[str] = []
        self._pump_thread: threading.Thread | None = None

    @property
    def pid(self) -> int:
        assert self.proc is not None
        return self.proc.pid

    def start(self) -> "Server":
        self._log = open(self.log_path, "w", encoding="utf-8", errors="replace")
        self._log.write("# cmd: " + " ".join(self.cmd) + "\n")
        self.proc = subprocess.Popen(self.cmd, cwd=self.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
        self._pump_thread = threading.Thread(target=self._pump, daemon=True)
        self._pump_thread.start()
        return self

    def _pump(self) -> None:
        assert self.proc is not None and self.proc.stdout is not None
        for line in self.proc.stdout:
            line = line.rstrip("\n")
            self.lines.append(line)
            if BAD_RE.search(line) and not NOISE_RE.search(line):
                self.bad.append(line)
            self._log.write(line + "\n")
            self._log.flush()

    def wait_for(self, pattern: str, timeout: float = 600, start: int = 0) -> tuple[int, str]:
        rx = re.compile(pattern)
        deadline = time.time() + timeout
        index = start
        while time.time() < deadline:
            while index < len(self.lines):
                if rx.search(self.lines[index]):
                    return index, self.lines[index]
                index += 1
            if self.proc is not None and self.proc.poll() is not None:
                raise RuntimeError(f"server exited ({self.proc.returncode}) while waiting for /{pattern}/")
            time.sleep(0.2)
        raise TimeoutError(f"/{pattern}/ not seen within {timeout}s")

    def console(self, command: str) -> None:
        assert self.proc is not None and self.proc.stdin is not None
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()

    def jcmd(self, *args: str, timeout: float = 60) -> str:
        result = subprocess.run([JCMD, str(self.pid), *args], capture_output=True, text=True, timeout=timeout,
                                encoding="utf-8", errors="replace")
        return result.stdout + result.stderr

    def stop(self, timeout: float = 300) -> dict:
        """Console `stop`; returns exit code and whether the world save completed normally."""
        assert self.proc is not None
        t0 = time.time()
        try:
            self.console("stop")
        except OSError:
            pass
        try:
            code = self.proc.wait(timeout)
            killed = False
        except subprocess.TimeoutExpired:
            self.proc.kill()
            code, killed = self.proc.wait(30), True
        if self._pump_thread:
            self._pump_thread.join(15)
        self._log.close()
        saved = any("ThreadedAnvilChunkStorage: All dimensions are saved" in l or "All dimensions are saved" in l
                    for l in self.lines[-400:])
        return {"exitCode": code, "killed": killed, "stopSeconds": round(time.time() - t0, 1), "allDimensionsSaved": saved}
