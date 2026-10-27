"""Build the Stage 9 telemetry mod with plain javac (MC 26.x is unobfuscated: no Loom, no refmap)."""
import hashlib
import pathlib
import shutil
import subprocess
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
JDK = pathlib.Path(r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin")
TEMPLATE = HERE.parents[2] / ".audit" / "stage9-20261009" / "sandbox" / "template-fabric-offline"
CLASSPATH = [TEMPLATE / "versions" / "26.2" / "server-26.2.jar",
             *sorted((TEMPLATE / "libraries").rglob("*.jar"))]  # mixin, slf4j, jspecify, ...
FIXED_TIME = (2026, 10, 9, 0, 0, 0)  # deterministic zip entries -> reproducible jar hash


def main() -> None:
    build = HERE / "build"
    classes = build / "classes"
    shutil.rmtree(build, ignore_errors=True)
    classes.mkdir(parents=True)
    sources = sorted(str(p) for p in (HERE / "src").rglob("*.java"))
    subprocess.run([str(JDK / "javac.exe"), "--release", "25", "-encoding", "UTF-8",
                    "-cp", ";".join(str(p) for p in CLASSPATH), "-d", str(classes), *sources], check=True)
    entries = [(p.relative_to(classes).as_posix(), p) for p in classes.rglob("*") if p.is_file()]
    entries += [(p.name, p) for p in (HERE / "resources").iterdir() if p.is_file()]
    out = build / "stage9-telemetry-1.1.0.jar"  # 1.1.0: tick-frame column (processPacketsAndTick)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as jar:
        for name, path in sorted(entries):
            info = zipfile.ZipInfo(name, FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(info, path.read_bytes())
    digest = hashlib.sha256(out.read_bytes()).hexdigest().upper()
    print(f"{out} {out.stat().st_size} bytes SHA-256 {digest}")


if __name__ == "__main__":
    main()
