# Read-only Linux host evidence. Output deliberately excludes argv, arbitrary environment,
# transcript messages and account metadata. No files or labels are modified.
import os, sys, json, re, glob, collections
kind, foreground, terminal, cwd, mode = sys.argv[1:6]
title = sys.argv[6] if len(sys.argv) > 6 else ""
foreground, terminal = int(foreground), int(terminal)
MAX_PROCESSES = 4096

def read(path, limit=65536):
    with open(path, 'rb') as f:
        data = f.read(limit + 1)
    if len(data) > limit:
        raise ValueError('evidence too large')
    return data

def stat(pid):
    fields = read('/proc/%d/stat' % pid).decode().rsplit(')', 1)[1].split()
    return int(fields[1]), fields[19]

def environment(pid):
    allowed = {b'HC_SCOPE', b'HOME', b'CLAUDE_CONFIG_DIR', b'CODEX_HOME', b'CLAUDE_CODE_CHILD_SESSION', b'CLAUDE_CODE_SKIP_PROMPT_HISTORY'}
    result = {}
    for entry in read('/proc/%d/environ' % pid, 1048576).split(b'\0'):
        key, sep, value = entry.partition(b'=')
        if sep and key in allowed:
            result[key.decode()] = value.decode('utf-8', 'replace')
    return result

def report(**fields):
    print(json.dumps(fields, separators=(',', ':')))

def claude_session_files(root, session):
    # Unlike glob, scandir propagates permission errors: unreadable is not absent.
    try:
        with os.scandir(os.path.join(root, 'projects')) as projects:
            directories = [p.path for p in projects if p.is_dir()]
    except FileNotFoundError:
        return []
    matches = []
    for directory in directories:
        path = os.path.join(directory, session + '.jsonl')
        try:
            os.stat(path)
            matches.append(path)
        except FileNotFoundError:
            pass
    return matches

def claude_has_no_messages(path):
    total = 0
    with open(path, 'rb') as transcript:
        for index in range(100001):
            line = transcript.readline(32 * 1024 * 1024 - total + 1)
            if not line:
                return True
            total += len(line)
            if total > 32 * 1024 * 1024 or index >= 100000 or not line.endswith(b'\n'):
                return False
            row = json.loads(line)
            if not isinstance(row, dict) or row.get('type') in ('user', 'assistant'):
                return False
    return False

def main():
    root_start = stat(terminal)[1]
    scope = environment(terminal).get('HC_SCOPE')
    if not scope:
        report(reason='No terminal process scope is available'); return
    entries = [int(e.name) for e in os.scandir('/proc') if e.name.isdigit()]
    if len(entries) > MAX_PROCESSES:
        report(reason='Process inventory exceeds safe limit'); return
    children = collections.defaultdict(list)
    for pid in entries:
        try:
            children[stat(pid)[0]].append(pid)
        except (OSError, ValueError, IndexError):
            pass
    queue = [(foreground, 0)]
    choices = []
    visited = set()
    while queue:
        pid, depth = queue.pop(0)
        if pid in visited:
            continue
        visited.add(pid)
        if len(visited) > 128:
            report(reason='Agent process walk exceeds safe limit'); return
        try:
            env = environment(pid)
            if env.get('HC_SCOPE') != scope:
                continue
            args = read('/proc/%d/cmdline' % pid, 262144).decode('utf-8', 'replace').split('\0')
            if ('app-server' in args and '--managed-daemon' in args) or ('pid-update-loop' in args):
                continue
            name = read('/proc/%d/comm' % pid).decode().strip()
            expected = name == ('claude' if kind == 'CLAUDE_CODE' else 'codex')
            if kind == 'CLAUDE_CODE' and re.fullmatch(r'\d+\.\d+\.\d+(?:[-.].*)?', name):
                expected = True
            # A launcher is followed to its real child; a leaf foreground is allowed
            # only because core's own argv has already been classified by AgentDetector.
            if expected or (pid == foreground and not children[pid]):
                if kind != 'CLAUDE_CODE' or env.get('CLAUDE_CODE_CHILD_SESSION') not in ('1', 'true'):
                    choices.append((depth, pid, env))
            queue.extend((child, depth + 1) for child in children[pid])
        except (OSError, ValueError, IndexError):
            # Unreadable descendants could hide the actual agent. Never guess a launcher.
            report(reason='Cannot inspect the foreground process tree'); return
    if not choices:
        report(reason='No active agent process is visible'); return
    distance = min(c[0] for c in choices)
    choices = [c for c in choices if c[0] == distance]
    if len(choices) != 1:
        report(reason='More than one agent process matches'); return
    _, pid, env = choices[0]
    start = stat(pid)[1]
    process_key = '%d:%s:%d:%s' % (terminal, root_start, pid, start)
    home = env.get('HOME')
    if not home:
        report(process_key=process_key, reason='Agent HOME is unavailable'); return
    root = env.get('CLAUDE_CONFIG_DIR' if kind == 'CLAUDE_CODE' else 'CODEX_HOME') or os.path.join(home, '.claude' if kind == 'CLAUDE_CODE' else '.codex')
    candidates, exact, open_files, lock_ids = [], None, [], []
    stored, process_started_ms = [], None
    not_started, no_new_threads = False, False
    if kind == 'CLAUDE_CODE':
        if env.get('CLAUDE_CODE_SKIP_PROMPT_HISTORY') not in (None, '', '0'):
            report(process_key=process_key, reason='Claude transcript recording is disabled'); return
        registry_exists = os.path.exists(os.path.join(root, 'sessions', '%d.json' % pid))
        try:
            registry = json.loads(read(os.path.join(root, 'sessions', '%d.json' % pid)))
            proc_start = str(registry.get('procStart', ''))
            if registry.get('pid') == pid and proc_start in (start, 'linux:' + start):
                session = registry.get('sessionId', '')
                if re.fullmatch(r'[A-Za-z0-9_-]{1,128}', session):
                    # Registry cwd names the active project, so an older relocated copy
                    # elsewhere never wins merely because glob returned it first.
                    folder = registry.get('cwd') or os.readlink('/proc/%d/cwd' % pid)
                    units = list(int.from_bytes(folder.encode('utf-16-le')[i:i+2], 'little') for i in range(0, len(folder.encode('utf-16-le')), 2))
                    key = ''.join(chr(n) if (48 <= n <= 57 or 65 <= n <= 90 or 97 <= n <= 122) else '-' for n in units)
                    if len(units) > 200:
                        hashed = 0
                        for n in units:
                            hashed = ((hashed * 31 + n + 2**31) % 2**32) - 2**31
                        value, encoded = abs(hashed), ''
                        while value:
                            encoded = '0123456789abcdefghijklmnopqrstuvwxyz'[value % 36] + encoded
                            value //= 36
                        key = key[:200] + '-' + (encoded or '0')
                    preferred = os.path.join(root, 'projects', key, session + '.jsonl')
                    matches = claude_session_files(root, session)
                    if preferred in matches or len(matches) == 1:
                        exact = {'session_id': session, 'path': preferred if preferred in matches else matches[0]}
                        not_started = claude_has_no_messages(exact['path'])
                    elif not matches:
                        not_started = True
        except (OSError, ValueError):
            pass
        if exact is None and not registry_exists:
            # Explicit launch identity is tied to the current, scoped process, never merely cwd.
            args = read('/proc/%d/cmdline' % pid, 262144).decode('utf-8', 'replace').split('\0')
            session = next((a.split('=', 1)[1] for a in args if a.startswith('--session-id=')), '')
            if '--session-id' in args and args.index('--session-id') + 1 < len(args):
                session = args[args.index('--session-id') + 1]
            if re.fullmatch(r'[A-Za-z0-9_-]{1,128}', session):
                matches = list(glob.iglob(os.path.join(root, 'projects', '*', session + '.jsonl')))
                if len(matches) == 1:
                    exact = {'session_id': session, 'path': matches[0]}
        # Recent files are candidates for explicit confirmation only, never an exact binding.
        if mode == 'candidates':
            found = []
            for directory in glob.iglob(os.path.join(root, 'projects', '*')):
                if len(found) > 4096:
                    break
                for entry in glob.iglob(os.path.join(directory, '*.jsonl')):
                    if len(found) >= 4096:
                        break
                    try:
                        found.append((os.stat(entry).st_mtime, entry))
                    except OSError:
                        pass
            for _, entry in sorted(found, reverse=True)[:24]:
                candidates.append({'session_id': os.path.basename(entry)[:-6], 'path': entry})
    else:
        try:
            for fd in os.scandir('/proc/%d/fd' % pid):
                try:
                    path = os.readlink(fd.path)
                    if path.endswith('.jsonl') and os.path.basename(path).startswith('rollout-'):
                        open_files.append(path)
                    if 'thread' in path and path.endswith('.lock'):
                        lock_ids.extend(re.findall(r'[0-9a-f]{8}-[0-9a-f-]{27,}', path))
                except OSError:
                    pass
        except OSError:
            report(process_key=process_key, reason='Cannot inspect agent open files'); return
        if len(open_files) > 8:
            report(process_key=process_key, reason='More than eight open rollouts; refusing ambiguous binding'); return
        try:
            stored = codex_candidates(root, cwd, title)
            boot = next(int(line.split()[1]) for line in read('/proc/stat').decode().splitlines() if line.startswith('btime '))
            process_started_ms = boot * 1000 + int(start) * 1000 // os.sysconf('SC_CLK_TCK') + 1000
            # Use the lower bound of the process start second when proving absence.
            no_new_threads = codex_has_no_new_threads(root, cwd, process_started_ms - 1000)
        except (OSError, ValueError, sqlite3.Error):
            pass
        if mode == 'candidates' and not glob.glob(os.path.join(root, 'state_*.sqlite')):
            paths = []
            for parent in ('sessions', 'archived_sessions'):
                for directory, dirs, files in os.walk(os.path.join(root, parent)):
                    if len(paths) > 4096:
                        break
                    for filename in files:
                        if len(paths) >= 4096:
                            break
                        if filename.startswith('rollout-') and filename.endswith('.jsonl'):
                            path = os.path.join(directory, filename)
                            try:
                                paths.append((os.stat(path).st_mtime, path))
                            except OSError:
                                pass
            for _, path in sorted(paths, reverse=True)[:24]:
                candidates.append({'session_id': '', 'path': path})
    if stat(pid)[1] != start or stat(terminal)[1] != root_start:
        report(reason='Agent process changed during evidence read'); return
    report(process_key=process_key, binding=exact, open_files=sorted(set(open_files)), lock_ids=lock_ids,
           candidates=candidates, stored=stored, process_started_ms=process_started_ms,
           not_started=not_started, no_new_threads=no_new_threads, reason='No unique exact session evidence')

try:
    main()
except (OSError, ValueError, IndexError, json.JSONDecodeError):
    report(reason='Session evidence is not available on this host')
