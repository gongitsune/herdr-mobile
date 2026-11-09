"""Protocol fixtures use local fake CLIs; never start the installed agents."""
import copy
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest.mock

claude = {"__name__": "fixture"}
codex = {"__name__": "fixture"}
exec(sys.argv[1], claude)
exec(sys.argv[2], codex)
real_popen = subprocess.Popen

valid_skill = dict(name="fixture", description="  A\n skill  ", path="/skills/fixture/SKILL.md", scope="repo", enabled=True)
valid = dict(data=[dict(cwd="/project", skills=[valid_skill], errors=[])])
assert codex["skill_rows"](valid) == [dict(name="fixture", description="A skill")]
for scope in ("user", "repo", "system", "admin"):
    value = copy.deepcopy(valid)
    value["data"][0]["skills"][0]["scope"] = scope
    assert len(codex["skill_rows"](value)) == 1
value = copy.deepcopy(valid)
value["data"][0]["skills"] += [dict(valid_skill), dict(valid_skill, name="disabled", path="/disabled", enabled=False)]
assert len(codex["skill_rows"](value)) == 1
value = copy.deepcopy(valid)
value["data"][0]["skills"] = []
assert codex["skill_rows"](value) == []
invalid = [None, [], {}, dict(data=[]), dict(data=[None])]
for field in ("name", "description", "path", "scope", "enabled"):
    value = copy.deepcopy(valid)
    del value["data"][0]["skills"][0][field]
    invalid.append(value)
for field, bad in (("name", ""), ("name", 1), ("description", None), ("path", []), ("enabled", 1),
                   ("enabled", "true"), ("scope", "plugin"), ("pluginId", {})):
    value = copy.deepcopy(valid)
    value["data"][0]["skills"].append(dict(valid_skill, **{field: bad}))
    invalid.append(value)
for field, bad in (("cwd", None), ("skills", {}), ("errors", {}), ("errors", [dict(message="error")])):
    value = copy.deepcopy(valid)
    value["data"][0][field] = bad
    invalid.append(value)
for value in invalid:
    try:
        codex["skill_rows"](value)
        raise AssertionError("malformed skills/list must be rejected")
    except ValueError:
        pass

with tempfile.TemporaryDirectory(prefix="herdr-mobile-commands-") as directory:
    cwd = pathlib.Path(directory) / "project with spaces"
    cwd.mkdir()
    fake = pathlib.Path(directory) / "agent"
    fake.write_text('''#!/usr/bin/env python3
import json,sys,time,os
kind,mode,cwd=sys.argv[1:]
assert os.getcwd()==cwd
def emit(value): print(json.dumps(value),flush=True)
if mode=="eof": sys.exit(0)
if mode=="oversize":
    print("x"*(1024*1024+1),flush=True)
    time.sleep(60)
for line in sys.stdin:
    request=json.loads(line)
    if kind=="claude":
        assert request=={"type":"control_request","request_id":"init","request":{"subtype":"initialize"}}
        print("not json",flush=True); emit([])
        emit({"type":"control_response","response":{"request_id":"wrong","subtype":"success","response":{}}})
        body={"commands":[{"name":"fixture","description":"Project command","argumentHint":"<path>","builtin":False,"secret":"DO_NOT_EXPORT"},
                          {"name":"skill","description":"No builtin field"},{"name":"model","description":"Model","builtin":True},False],
              "models":[{"secret":"DO_NOT_EXPORT"}],"secret":"DO_NOT_EXPORT"}
        response={"request_id":"init","subtype":"success","response":body}
        if mode=="error": response={"request_id":"init","subtype":"error","error":"DO_NOT_EXPORT"}
        if mode=="invalid": response["response"]={"commands":{}}
        if mode=="timeout": time.sleep(60)
        emit({"type":"control_response","response":response})
        # Commands mode must stop after initialize, without get_usage or a prompt.
        if mode=="ok":
            assert not sys.stdin.readline()
        time.sleep(60)
    else:
        if request.get("method")=="initialize":
            assert request["params"]["clientInfo"]["name"]=="herdr-mobile"
            assert request["params"]["capabilities"]=={"experimentalApi":False}
            print("not json",flush=True); emit([])
            emit({"id":999,"result":{}})
            emit({"method":"notification","params":{"secret":"DO_NOT_EXPORT"}})
            emit({"id":1,"result":{"secret":"DO_NOT_EXPORT"}})
        elif request.get("method")=="initialized":
            assert request=={"method":"initialized"}
        else:
            assert request=={"id":2,"method":"skills/list","params":{"cwds":[cwd]}}
            skills=[dict(name="fixture",description=" A\\n skill ",path="/fixture",scope="repo",enabled=True,secret="DO_NOT_EXPORT"),
                    dict(name="disabled",description="Disabled",path="/disabled",scope="user",enabled=False)]
            result={"data":[{"cwd":cwd,"skills":skills,"errors":[]}]}
            if mode=="invalid": result["data"][0]["errors"]=[{"message":"DO_NOT_EXPORT"}]
            if mode=="timeout": time.sleep(60)
            emit({"id":2,"error":{"message":"DO_NOT_EXPORT"}} if mode=="error" else {"id":2,"result":result})
            time.sleep(60)
''')
    fake.chmod(0o700)
    for kind, namespace in (("claude", claude), ("codex", codex)):
        for mode in ("ok", "missing", "error", "invalid", "timeout", "oversize", "eof", "lookup_timeout"):
            children = []
            lookups = []

            def popen(argv, **kwargs):
                assert kwargs["start_new_session"] is True
                if argv[:2] == ["sh", "-lc"]:
                    assert argv == ["sh", "-lc", "command -v " + kind]
                    assert kwargs["cwd"] == str(pathlib.Path.home())
                    process = real_popen([sys.executable, "-c", "import sys,time; print(sys.argv[1],flush=True); "
                                          + ("time.sleep(60)" if mode == "lookup_timeout" else ""),
                                          str(fake) if mode != "missing" else ""], **kwargs)
                    lookups.append(process)
                    if mode == "lookup_timeout":
                        process.communicate = unittest.mock.Mock(side_effect=subprocess.TimeoutExpired(argv, 3))
                    return process
                assert argv[0] == str(fake)
                assert kwargs["cwd"] == str(cwd)
                if kind == "claude":
                    assert "--no-session-persistence" in argv
                    assert "--strict-mcp-config" in argv
                    assert json.loads(argv[argv.index("--settings") + 1]) == {"disableAllHooks": True}
                else:
                    assert argv[1:] == ["-s", "read-only", "-a", "never", "app-server"]
                process = real_popen([sys.executable, str(fake), kind, mode, str(cwd)], **kwargs)
                children.append(process)
                return process

            real_selector = namespace["selectors"].DefaultSelector

            def selector():
                instance = real_selector()
                select = instance.select
                calls = [0]

                def select_until_request(timeout=None):
                    calls[0] += 1
                    # Claude waits at initialize; Codex first completes the initialize handshake.
                    if mode == "timeout" and (kind == "claude" or calls[0] > 1):
                        return []
                    return select(timeout)

                instance.select = select_until_request
                return instance

            with unittest.mock.patch.object(subprocess, "Popen", side_effect=popen), \
                    unittest.mock.patch.object(namespace["selectors"], "DefaultSelector", side_effect=selector):
                result = namespace["exchange"]("commands", str(cwd)) if kind == "claude" else namespace["exchange"](str(cwd))
            assert "DO_NOT_EXPORT" not in json.dumps(result), result
            assert all(process.poll() is not None for process in children + lookups)
            assert all(process.returncode == -9 or mode == "eof" for process in children), (kind, mode)
            if mode == "ok":
                if kind == "claude":
                    assert result == {"commands": [dict(name="fixture", description="Project command", argumentHint="<path>", builtin=False),
                                                   dict(name="skill", description="No builtin field"),
                                                   dict(name="model", description="Model", builtin=True)]}, result
                else:
                    assert result == {"commands": [dict(name="fixture", description="A skill")]}, result
            elif mode == "missing":
                assert result.get("error") == "program_missing" or result.get("limitsUnavailable") == "program_missing", result
            else:
                assert "error" in result, (kind, mode, result)
                assert result["commands"] == [], result
print("command exchange fixtures passed")
