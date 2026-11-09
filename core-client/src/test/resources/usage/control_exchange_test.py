import json
import pathlib
import subprocess
import sys
import tempfile
import unittest.mock

namespace = {"__name__": "fixture"}
exec(sys.argv[1], namespace)
real_popen = subprocess.Popen

with tempfile.TemporaryDirectory(prefix="herdr-mobile-usage-") as directory:
    fake = pathlib.Path(directory) / "claude"
    fake.write_text("""#!/usr/bin/env python3
import json,sys,time
for line in sys.stdin:
    request=json.loads(line)
    stage=request["request_id"]
    if stage=="init":
        assert request["request"]=={"subtype":"initialize"}
        body={"models":[{"value":"opus","resolvedModel":"claude-opus","displayName":"Opus","description":"Smart","secret":"DO_NOT_EXPORT"}],"secret":"DO_NOT_EXPORT"}
        response={"subtype":"success","request_id":stage,"response":body}
    else:
        assert request["request"]=={"subtype":"get_usage","skip_behaviors":True}
        mode=sys.argv[1]
        if mode=="timeout":
            time.sleep(60)
        if mode=="error":
            response={"subtype":"error","request_id":stage,"error":"usage failed"}
        elif mode=="old":
            response={"subtype":"error","request_id":stage,"error":"Unsupported control request subtype: get_usage"}
        else:
            body={"rate_limits_available":mode!="no_plan","subscription_type":"max","secret":"DO_NOT_EXPORT",
                  "rate_limits":{"five_hour":{"utilization":12.5,"resets_at":None,"secret":"DO_NOT_EXPORT"},"secret":{"utilization":100}}}
            response={"subtype":"success","request_id":stage,"response":body}
    print(json.dumps({"type":"control_response","response":response}),flush=True)
    if stage=="usage": time.sleep(60)
""")
    fake.chmod(0o700)
    for mode in ("ok", "old", "no_plan", "missing", "error", "timeout"):
        children = []

        def popen(argv, **kwargs):
            if argv[:2] == ["sh", "-lc"]:
                return real_popen([sys.executable, "-c", "import sys; print(sys.argv[1])",
                                   str(fake) if mode != "missing" else ""], **kwargs)
            assert argv[0] == str(fake)
            assert "--no-session-persistence" in argv
            assert "--strict-mcp-config" in argv
            assert json.loads(argv[argv.index("--settings") + 1]) == {"disableAllHooks": True}
            assert kwargs["cwd"] == str(pathlib.Path.home())
            process = real_popen([sys.executable, str(fake), mode], **kwargs)
            children.append(process)
            return process

        real_select = namespace["selectors"].DefaultSelector

        def selector():
            instance = real_select()
            if mode == "timeout":
                select = instance.select
                calls = [0]

                def select_until_usage(timeout=None):
                    calls[0] += 1
                    return select(timeout) if calls[0] == 1 else []

                instance.select = select_until_usage
            return instance

        with unittest.mock.patch.object(subprocess, "Popen", side_effect=popen), \
                unittest.mock.patch.object(namespace["selectors"], "DefaultSelector", side_effect=selector):
            result = namespace["exchange"]()
        assert "DO_NOT_EXPORT" not in json.dumps(result), result
        if mode == "missing":
            assert result == {"models": [], "limitsUnavailable": "program_missing"}
            continue
        assert result["models"] == [{"value": "opus", "resolvedModel": "claude-opus",
                                     "displayName": "Opus", "description": "Smart"}], result
        assert children[0].returncode == -9, "Claude process group must be killed and reaped"
        if mode == "ok":
            assert result["rate_limits"] == {"five_hour": {"utilization": 12.5, "resets_at": None}}, result
        elif mode in ("error", "timeout"):
            assert result["error"] == {"error": "request_failed", "timeout": "timeout"}[mode], result
            assert "limitsUnavailable" not in result, result
        else:
            assert result["limitsUnavailable"] == {"old": "program_too_old", "no_plan": "no_plan_limits"}[mode], result
print("control exchange fixtures passed")
