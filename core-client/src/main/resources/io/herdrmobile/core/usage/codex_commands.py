"""Read the public app-server skills catalog; never create a thread or send a prompt."""
import json
import os
import selectors
import signal
import subprocess
import sys
import time


def skill_rows(result):
    """Validate the entire pinned skills/list response before accepting any enabled row."""
    if not isinstance(result, dict) or not isinstance(result.get("data"), list) or not result["data"]:
        raise ValueError("invalid_response")
    rows = []
    seen = set()
    for entry in result["data"]:
        if (not isinstance(entry, dict) or not isinstance(entry.get("cwd"), str)
                or not isinstance(entry.get("skills"), list) or not isinstance(entry.get("errors"), list)
                or entry["errors"]):
            raise ValueError("invalid_response")
        for skill in entry["skills"]:
            if (not isinstance(skill, dict) or not isinstance(skill.get("name"), str) or not skill["name"]
                    or not isinstance(skill.get("description"), str) or not isinstance(skill.get("path"), str)
                    or not isinstance(skill.get("enabled"), bool) or not isinstance(skill.get("scope"), str)
                    or skill["scope"] not in ("user", "repo", "system", "admin")
                    or (skill.get("pluginId") is not None and not isinstance(skill["pluginId"], str))):
                raise ValueError("invalid_response")
            if not skill["enabled"] or skill["path"] in seen:
                continue
            seen.add(skill["path"])
            rows.append({"name": skill["name"], "description": " ".join(skill["description"].split())})
    return rows


def exchange(cwd):
    child = None
    lookup = None
    deadline = time.monotonic() + 19

    def terminate(process):
        if process is not None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=1)

    def send(message):
        child.stdin.write((json.dumps(message) + "\n").encode())
        child.stdin.flush()

    try:
        lookup = subprocess.Popen(["sh", "-lc", "command -v codex"],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  cwd=os.path.expanduser("~"), start_new_session=True)
        output, _ = lookup.communicate(timeout=3)
        executable = output.decode().strip().splitlines()[-1] if output.strip() else ""
        if lookup.returncode != 0 or not executable.startswith("/") or not os.access(executable, os.X_OK):
            return {"commands": [], "error": "program_missing"}
        child = subprocess.Popen([executable, "-s", "read-only", "-a", "never", "app-server"],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                 cwd=cwd, start_new_session=True)
        stage = 1
        send({"id": stage, "method": "initialize", "params": {
            "clientInfo": {"name": "herdr-mobile", "version": "1"},
            "capabilities": {"experimentalApi": False}}})
        buffer = b""
        size = 0
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not selector.select(max(0, remaining)):
                    return {"commands": [], "error": "timeout"}
                data = os.read(child.stdout.fileno(), 65536)
                size += len(data)
                if not data or size > 1024 * 1024:
                    return {"commands": [], "error": "invalid_response"}
                buffer += data
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    try:
                        message = json.loads(line)
                    except (ValueError, UnicodeError):
                        continue
                    if not isinstance(message, dict) or type(message.get("id")) is not int or message["id"] != stage:
                        continue
                    if "error" in message:
                        return {"commands": [], "error": "request_failed"}
                    if not isinstance(message.get("result"), dict):
                        return {"commands": [], "error": "invalid_response"}
                    if stage == 1:
                        stage = 2
                        send({"method": "initialized"})
                        send({"id": stage, "method": "skills/list", "params": {"cwds": [cwd]}})
                    else:
                        return {"commands": skill_rows(message["result"])}
    except (OSError, ValueError, UnicodeError, subprocess.SubprocessError):
        return {"commands": [], "error": "request_failed"}
    finally:
        terminate(child)
        terminate(lookup)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(2)
    print(json.dumps(exchange(sys.argv[1]), allow_nan=False))
