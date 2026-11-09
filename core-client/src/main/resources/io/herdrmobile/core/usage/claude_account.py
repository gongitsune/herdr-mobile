"""Read only the public Claude control protocol. Never send a prompt or export credentials."""
import json
import os
import selectors
import signal
import subprocess
import sys
import time

WINDOWS = ("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet")


def exchange(mode="account", cwd=None):
    commands_mode = mode == "commands"
    result = {"commands": []} if commands_mode else {"models": []}
    child = None
    lookup = None
    deadline = time.monotonic() + (15 if commands_mode else 19)

    def terminate(process):
        if process is not None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=1)

    def send(stage, request):
        child.stdin.write((json.dumps({"type": "control_request", "request_id": stage,
                                      "request": request}) + "\n").encode())
        child.stdin.flush()

    try:
        lookup = subprocess.Popen(["sh", "-lc", "command -v claude"],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  cwd=os.path.expanduser("~"), start_new_session=True)
        output, _ = lookup.communicate(timeout=3)
        executable = output.decode().strip().splitlines()[-1] if output.strip() else ""
        if lookup.returncode != 0 or not executable.startswith("/") or not os.access(executable, os.X_OK):
            result["limitsUnavailable"] = "program_missing"
            return result
        child = subprocess.Popen(
            [executable, "-p", "--input-format", "stream-json", "--output-format", "stream-json",
             "--verbose", "--no-session-persistence", "--strict-mcp-config",
             "--settings", '{"disableAllHooks":true}'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            cwd=cwd if commands_mode else os.path.expanduser("~"), start_new_session=True)
        stage = "init"
        send(stage, {"subtype": "initialize"})
        request_deadline = min(deadline, time.monotonic() + 15)
        buffer = b""
        size = 0
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ)
            while True:
                remaining = request_deadline - time.monotonic()
                if remaining <= 0 or not selector.select(max(0, remaining)):
                    result["error"] = "timeout"
                    return result
                data = os.read(child.stdout.fileno(), 65536)
                size += len(data)
                if not data or size > 1024 * 1024:
                    result["error"] = "invalid_response"
                    return result
                buffer += data
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    try:
                        message = json.loads(line)
                    except (ValueError, UnicodeError):
                        continue
                    if not isinstance(message, dict) or message.get("type") != "control_response":
                        continue
                    response = message.get("response")
                    if not isinstance(response, dict) or response.get("request_id") != stage:
                        continue
                    if response.get("subtype") == "error":
                        if stage == "usage" and str(response.get("error", "")).startswith("Unsupported control request subtype"):
                            result["limitsUnavailable"] = "program_too_old"
                        else:
                            result["error"] = "request_failed"
                        return result
                    body = response.get("response")
                    if response.get("subtype") != "success" or not isinstance(body, dict):
                        result["error"] = "invalid_response"
                        return result
                    if stage == "init":
                        if commands_mode:
                            commands = body.get("commands")
                            if not isinstance(commands, list):
                                result["error"] = "invalid_response"
                            else:
                                result["commands"] = [
                                    {key: command[key] for key in ("name", "description", "argumentHint", "builtin")
                                     if key in command and isinstance(command[key], bool if key == "builtin" else str)}
                                    for command in commands if isinstance(command, dict)]
                            return result
                        models = body.get("models")
                        if isinstance(models, list):
                            result["models"] = [
                                {key: model[key] for key in ("value", "resolvedModel", "displayName", "description")
                                 if isinstance(model.get(key), str)}
                                for model in models if isinstance(model, dict)]
                        stage = "usage"
                        send(stage, {"subtype": "get_usage", "skip_behaviors": True})
                    else:
                        if body.get("rate_limits_available") is False:
                            result["limitsUnavailable"] = "no_plan_limits"
                        elif body.get("rate_limits_available") is True and isinstance(body.get("rate_limits"), dict):
                            result["rate_limits_available"] = True
                            if isinstance(body.get("subscription_type"), str):
                                result["subscription_type"] = body["subscription_type"]
                            result["rate_limits"] = {
                                key: {field: window.get(field) for field in ("utilization", "resets_at")}
                                for key in WINDOWS
                                for window in [body["rate_limits"].get(key)] if isinstance(window, dict)}
                        else:
                            result["error"] = "invalid_response"
                        return result
    except (OSError, ValueError, subprocess.SubprocessError):
        result["error"] = "request_failed"
        return result
    finally:
        terminate(child)
        terminate(lookup)


if __name__ == "__main__":
    if len(sys.argv) > 1:
        if len(sys.argv) != 3 or sys.argv[1] != "commands":
            raise SystemExit(2)
        print(json.dumps(exchange("commands", sys.argv[2]), allow_nan=False))
    else:
        print(json.dumps(exchange(), allow_nan=False))
