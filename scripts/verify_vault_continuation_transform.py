"""Transform-only Fabric probe; exits in prelaunch before world/server/client startup.

Copies launcher/libraries/providers/config to a NEW directory. Never touches an
accepted server or packages the temporary probe into the candidate artifact.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from zipfile import ZipFile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--template", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    for name in ("libraries", "versions", "config"):
        shutil.copytree(args.template / name, root / name)
    # Loader's generated launcher is a runtime dependency, not the old processed mod cache.
    shutil.copytree(args.template / ".fabric/server", root / ".fabric/server")
    shutil.copy2(args.template / "fabric-server-launch.jar", root)
    (root / "mods").mkdir()
    providers = {}
    for path in (args.template / "mods").glob("*.jar"):
        if path.name.startswith("phoenium-"):
            continue
        providers[path.name] = digest(path)
        shutil.copy2(path, root / "mods" / path.name)
    shutil.copy2(args.jar, root / "mods" / args.jar.name)
    source = root / "probe-src/VaultTransformProbe.java"
    source.parent.mkdir()
    source.write_text('''package dev.phoenium.testmixin;
public final class VaultTransformProbe implements net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint {
  public void onPreLaunch() {
    try {
      ClassLoader loader = getClass().getClassLoader();
      for (String name : new String[]{
        "net.minecraft.world.level.Level", "net.minecraft.world.level.block.entity.BlockEntity",
        "net.minecraft.world.level.block.entity.vault.VaultBlockEntity$Server",
        "net.minecraft.world.level.block.entity.vault.VaultServerData",
        "net.minecraft.world.level.block.entity.vault.VaultSharedData"})
        Class.forName(name, false, loader);
      System.out.println("PHOENIUM_VAULT_TRANSFORM_PASS_NO_WORLD_STARTED");
      System.exit(0);
    } catch (Throwable failure) { failure.printStackTrace(); System.exit(2); }
  }
}
''')
    classes = root / "probe-classes"
    classes.mkdir()
    loader = root / "libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar"
    subprocess.run([shutil.which("javac"), "-cp", str(loader), "-d", str(classes), str(source)], check=True)
    probe = root / "mods/vault-transform-probe.jar"
    with ZipFile(probe, "w") as archive:
        for path in classes.rglob("*.class"):
            archive.write(path, path.relative_to(classes).as_posix())
        archive.writestr("fabric.mod.json", json.dumps({"schemaVersion": 1, "id": "vault-transform-probe",
            "version": "1", "environment": "server", "entrypoints": {"preLaunch": ["dev.phoenium.testmixin.VaultTransformProbe"]}}))
    command = [shutil.which("java"), "-Xmx1G", "-Dmixin.debug.export=true", "-Dmixin.debug.countInjections=true",
               "-jar", "fabric-server-launch.jar", "nogui"]
    with (root / "process.log").open("w") as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=90,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    output = (root / "process.log").read_text(errors="replace")
    passed = result.returncode == 0 and "PHOENIUM_VAULT_TRANSFORM_PASS_NO_WORLD_STARTED" in output
    exported = root / ".mixin.out/class/net/minecraft/world/level"
    classes = {"Level": exported / "Level.class", "VaultServer": exported / "block/entity/vault/VaultBlockEntity$Server.class",
               "BlockEntity": exported / "block/entity/BlockEntity.class"}
    checks = {"Level": "VaultBlockEntityTickContinuation.output", "VaultServer": "VaultBlockEntityTickContinuation.setChanged",
              "BlockEntity": "setChanged"}
    for name, path in classes.items():
        if not path.exists():
            passed = False
            continue
        bytecode = subprocess.run([shutil.which("javap"), "-p", "-c", str(path)], capture_output=True, text=True, check=True).stdout
        (root / (name + ".javap.txt")).write_text(bytecode)
        passed &= checks[name] in bytecode
    manifest = {"result": "PASS" if passed else "FAIL", "kind": "Fabric transform only; no world or clients started",
                "candidate": str(args.jar.resolve()), "sha256": digest(args.jar), "providers": providers,
                "probeSha256": digest(probe), "command": command, "exitCode": result.returncode}
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))
    if not passed:
        print(output[-12000:])
        raise SystemExit(1)


if __name__ == "__main__":
    main()
