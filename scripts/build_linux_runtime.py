"""Assemble the optional, offline ManTou Linux runtime from upstream assets."""

import gzip
import hashlib
import shutil
import stat
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "tmp/linux-lab"
ALPINE = "https://dl-cdn.alpinelinux.org/alpine"
MAIN = f"{ALPINE}/v3.23/main/x86"
MINIROOTFS = "alpine-minirootfs-3.23.8-x86.tar.gz"
V86 = "v86-0.5.473.tgz"
BIOS = "https://raw.githubusercontent.com/copy/v86/315b2b0/bios"


def fetch(url, name=None, sha256=None):
    path = CACHE / (name or url.rsplit("/", 1)[-1])
    if not path.is_file():
        temporary = path.with_suffix(path.suffix + ".download")
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open("wb") as dest:
            shutil.copyfileobj(source, dest)
        temporary.replace(path)
    if sha256 and hashlib.sha256(path.read_bytes()).hexdigest() != sha256:
        raise ValueError(f"Unexpected SHA-256 for {path.name}")
    return path


def tar_nodes(path):
    with tarfile.open(path, ignore_zeros=True) as archive:
        for entry in archive:
            key = PurePosixPath(entry.name).as_posix()
            if key in (".", "TRAILER!!!") or key.startswith("."):
                continue
            if key.startswith("/") or ".." in PurePosixPath(key).parts:
                raise ValueError(f"Unsafe archive entry {key}")
            if entry.isdir():
                yield key, stat.S_IFDIR | entry.mode, b""
            elif entry.issym():
                yield key, stat.S_IFLNK | entry.mode, entry.linkname.encode()
            elif entry.isreg():
                yield key, stat.S_IFREG | entry.mode, archive.extractfile(entry).read()
            elif entry.islnk():
                yield key, stat.S_IFREG | entry.mode, archive.extractfile(entry).read()


def write_cpio(nodes, destination):
    with destination.open("wb") as output, gzip.GzipFile(fileobj=output, mode="wb", mtime=0) as archive:
        position = 0

        def emit(data):
            nonlocal position
            archive.write(data)
            position += len(data)

        def pad():
            emit(bytes(-position % 4))

        items = sorted(nodes.items())
        items.append(("TRAILER!!!", (0, b"")))
        for inode, (key, (mode, data)) in enumerate(items, 1):
            name = key.encode() + b"\0"
            major, minor = (5, 1) if key == "dev/console" else (0, 0)
            fields = [inode, mode, 0, 0, 2 if stat.S_ISDIR(mode) else 1, 0, len(data),
                      0, 0, major, minor, len(name), 0]
            emit(("070701" + "".join(f"{field:08x}" for field in fields)).encode())
            emit(name)
            pad()
            emit(data)
            pad()


def main(assets, node):
    CACHE.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    boot = CACHE / "boot"
    boot.mkdir(exist_ok=True)
    v86 = fetch(f"https://registry.npmjs.org/v86/-/{V86}", sha256=V86_SHA256)
    with tarfile.open(v86) as archive:
        for name in ("libv86.js", "v86.wasm", "v86-fallback.wasm"):
            (assets / name).write_bytes(archive.extractfile(f"package/build/{name}").read())
        (boot / "libv86.mjs").write_bytes(archive.extractfile("package/build/libv86.mjs").read())
        (assets / "v86-LICENSE.txt").write_bytes(archive.extractfile("package/LICENSE").read())
    for name, digest in BIOS_HASHES.items():
        path = fetch(f"{BIOS}/{name}", name=f"pinned-{name}", sha256=digest)
        shutil.copyfile(path, assets / name)

    rootfs = fetch(f"{ALPINE}/v3.23/releases/x86/{MINIROOTFS}", sha256=ROOTFS_SHA256)
    nodes = {name: (mode, data) for name, mode, data in tar_nodes(rootfs)}
    guest = ROOT / "scripts/linux-guest"
    packages = [line.split() for line in (guest / "packages.sha256").read_text().splitlines() if line.strip()]
    module_names = {"9p.ko.gz", "9pnet.ko.gz", "9pnet_virtio.ko.gz", "fscache.ko.gz",
                    "netfs.ko.gz", "modules.dep", "modules.alias", "modules.builtin"}
    for digest, package in packages:
        print(f"Adding {package}", flush=True)
        path = fetch(f"{MAIN}/{package}", sha256=digest)
        for name, mode, data in tar_nodes(path):
            if package.startswith("linux-virt-"):
                if name == "boot/vmlinuz-virt":
                    (boot / "kernel.bin").write_bytes(data)
                elif name.startswith("lib/modules/") and PurePosixPath(name).name in module_names:
                    nodes[name] = (mode, data)
            else:
                nodes[name] = (mode, data)

    nodes["dev/console"] = (stat.S_IFCHR | 0o600, b"")
    for name in ["mnt", "tmp", "workspace"]:
        mode = 0o1777 if name == "tmp" else 0o755
        nodes[name] = (stat.S_IFDIR | mode, b"")
    for name in ["init", "runner.py"]:
        data = (guest / name).read_bytes().replace(b"\r\n", b"\n")
        nodes[name] = (stat.S_IFREG | 0o755, data)
    for name in list(nodes):
        for parent in PurePosixPath(name).parents:
            if parent.as_posix() != ".":
                nodes.setdefault(parent.as_posix(), (stat.S_IFDIR | 0o755, b""))
    write_cpio(nodes, boot / "initramfs.bin")
    subprocess.run([node, str(guest / "snapshot.mjs"), str(boot), str(assets), str(boot / "libv86.mjs")],
                   check=True, timeout=150)
    print(f"Created {assets}")


ROOTFS_SHA256 = "2e8ba8a8801fb74cd0d32ab8ba8bae3f6ba9f271ed6a086f5ce4cb0226ee4ed4"
V86_SHA256 = "364ab67a68c1d83646e2ddf7caf73c24574694a132b2b43689ad0f72e7242636"
BIOS_HASHES = {
    "seabios.bin": "73e3f359102e3a9982c35fce98eb7cd08f18303ac7f1ba6ebfbe6cdc1c244d98",
    "vgabios.bin": "a4bc0d80cc3ca028c73dafa8fee396b8d054ce87ebd8abfbd31b06b437607880",
}

if __name__ == "__main__":
    main(Path(sys.argv[1]), sys.argv[2])
