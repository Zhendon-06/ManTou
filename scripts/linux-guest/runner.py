"""Run one task using a memory-only transport and a private guest directory."""

import base64
import json
import os
import resource
import signal
import stat
import sys
import time
import traceback
from pathlib import Path
from types import ModuleType

MAX_OUTPUT = 8 * 1024 * 1024
MAX_LOG = 8192
TRANSFER = Path("/mnt")
WORKSPACE = Path("/workspace")


def restrict_child():
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_OUTPUT, MAX_OUTPUT))
    resource.setrlimit(resource.RLIMIT_NPROC, (32, 32))
    os.setgroups([])
    os.setgid(65534)
    os.setuid(65534)


def child_main(language, log):
    os.setsid()
    os.chdir(WORKSPACE)
    os.environ.clear()
    os.environ.update(PATH="/usr/bin:/bin", HOME=str(WORKSPACE), LANG="C.UTF-8")
    with open("/dev/null", "rb") as devnull:
        os.dup2(devnull.fileno(), 0)
    os.dup2(log.fileno(), 1)
    os.dup2(log.fileno(), 2)
    log.close()
    restrict_child()
    if language == "shell":
        os.execve("/bin/sh", ["/bin/sh", "/task"], os.environ)

    # Fork the initialized interpreter instead of loading Python again in the emulator.
    sys.argv = ["/task"]
    sys.path.insert(0, str(WORKSPACE))
    main = ModuleType("__main__")
    main.__file__ = "/task"
    sys.modules["__main__"] = main
    exec(compile(Path("/task").read_bytes(), "/task", "exec"), vars(main))


def execute(language, log):
    pid = os.fork()
    if pid == 0:
        try:
            child_main(language, log)
            code = 0
        except SystemExit as error:
            code = error.code if isinstance(error.code, int) else int(error.code is not None)
            if error.code is not None and not isinstance(error.code, int):
                print(error.code, file=sys.stderr)
        except BaseException:
            traceback.print_exc()
            code = 1
        sys.stdout.flush()
        sys.stderr.flush()
        os._exit(code & 255)

    def timed_out(*_):
        raise TimeoutError

    previous = signal.signal(signal.SIGALRM, timed_out)
    signal.alarm(45)
    try:
        return os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1])
    except TimeoutError:
        try:
            os.killpg(pid, signal.SIGKILL)
        except ProcessLookupError:
            os.kill(pid, signal.SIGKILL)
        os.waitpid(pid, 0)
        return 124
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, previous)


def run(job):
    for item in job["inputs"]:
        target = WORKSPACE / item["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(item["data"], validate=True))
    for parent, _, files in os.walk(WORKSPACE):
        os.chown(parent, 65534, 65534)
        for name in files:
            os.chown(Path(parent) / name, 65534, 65534)
    Path("/task").write_text(job["script"], encoding="utf-8")
    with open("/run.log", "w+b") as log:
        exit_code = execute(job["language"], log)
        log.seek(0)
        message = log.read(MAX_LOG + 1)
    clipped = len(message) > MAX_LOG
    return {
        "exitCode": exit_code,
        "log": message[:MAX_LOG].decode("utf-8", errors="replace")
        + ("\n[日志已截断]" if clipped else ""),
        "output": collect_output(job["output"]) if exit_code == 0 and job["output"] else None,
    }


def collect_output(path):
    # Traverse under the guest workspace without following links created by the script.
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC
    descriptor = os.open(WORKSPACE, flags | os.O_DIRECTORY)
    try:
        parts = path.split("/")
        for component in parts[:-1]:
            child = os.open(component, flags | os.O_DIRECTORY, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        with os.fdopen(os.open(parts[-1], flags | os.O_NONBLOCK, dir_fd=descriptor), "rb") as result:
            info = os.fstat(result.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_OUTPUT:
                raise ValueError("输出必须是 8 MiB 以内的普通文件")
            data = result.read(MAX_OUTPUT + 1)
            if len(data) > MAX_OUTPUT:
                raise ValueError("输出超过 8 MiB")
            return base64.b64encode(data).decode("ascii")
    finally:
        os.close(descriptor)


try:
    for module in ("csv", "sqlite3"):
        __import__(module)
    # The build snapshots Linux while this runner is waiting for a host request.
    with open("/dev/ttyS0", "r+b", buffering=0) as control:
        control.write(b"\nMANTOU_GUEST_READY\n")
        if control.readline() != b"run\n":
            raise ValueError("无效的 Linux 启动信号")
        control.write(b"\nMANTOU_JOB_STARTED\n")
    job = json.loads((TRANSFER / "job.json").read_bytes())
    time.clock_settime(time.CLOCK_REALTIME, job["now"])
    with open("/dev/urandom", "wb", buffering=0) as random_device:
        random_device.write(base64.b64decode(job["entropy"], validate=True))
    response = run(job)
except Exception as error:
    response = {"exitCode": 1, "log": str(error)[:MAX_LOG], "output": None}

(TRANSFER / "result.json").write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
