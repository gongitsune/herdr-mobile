# Read-only Codex store inventory. Never returns conversation bodies.
import sqlite3
from pathlib import Path

def codex_root_meta(payload):
    return (not payload.get('parent_thread_id') and not payload.get('agent_role')
            and payload.get('thread_source') in (None, 'user')
            and 'subagent' not in str(payload.get('source', '')).lower())

def codex_stores(root):
    try:
        with os.scandir(root) as entries:
            return [(int(m.group(1)), p.path) for p in entries
                    if (m := re.fullmatch(r'state_(\d+)\.sqlite', p.name))]
    except FileNotFoundError:
        return []

def codex_thread_query(db):
    cols = {r[1] for r in db.execute('pragma table_info(threads)')}
    where = ['archived = 0', 'agent_role IS NULL']
    if 'thread_source' in cols:
        where.append("(thread_source IS NULL OR thread_source = 'user')")
    if 'source' in cols:
        where.append("source IN ('cli', 'vscode')")
    created = 'COALESCE(created_at_ms, created_at*1000)' if 'created_at_ms' in cols else 'created_at*1000'
    return 'SELECT id, rollout_path, cwd, updated_at, ' + created + ' AS created_ms FROM threads WHERE ' + ' AND '.join(where)

def codex_has_no_new_threads(root, cwd, started_ms):
    stores = codex_stores(root)
    if not stores:
        # Older versions have no SQLite inventory. Only a missing/empty sessions
        # tree proves absence; an unreadable tree or any rollout stays ambiguous.
        def failed(error):
            raise error
        sessions = os.path.join(root, 'sessions')
        try:
            os.stat(sessions)
        except FileNotFoundError:
            return True
        for _, _, files in os.walk(sessions, onerror=failed):
            if any(p.startswith('rollout-') and p.endswith('.jsonl') for p in files):
                return False
        return True
    db = sqlite3.connect(Path(max(stores)[1]).resolve().as_uri() + '?mode=ro', uri=True, timeout=1)
    try:
        db.create_function('realpath', 1, lambda p: os.path.realpath(p) if p else '')
        # Do not infer absence from the filtered 32-candidate list: a new thread
        # may not have written its rollout yet, or its header may be incomplete.
        query = codex_thread_query(db)
        return db.execute(query + ' AND realpath(cwd) = ? AND (created_ms >= ? OR created_ms IS NULL) LIMIT 1',
                          (os.path.realpath(cwd), started_ms)).fetchone() is None
    finally:
        db.close()

def codex_candidates(root, cwd, title=''):
    stores = codex_stores(root)
    if not stores:
        return []
    db = sqlite3.connect(Path(max(stores)[1]).resolve().as_uri() + '?mode=ro', uri=True, timeout=1)
    try:
        db.row_factory = sqlite3.Row
        db.create_function('realpath', 1, lambda p: os.path.realpath(p) if p else '')
        select = codex_thread_query(db)
        rows = list(db.execute(select + ' AND realpath(cwd) = ? ORDER BY updated_at DESC LIMIT 32', (os.path.realpath(cwd),)))
        # A title adds possibilities, never ownership. Ambiguous prefixes add nothing.
        for prefix in re.findall(r'(?<![0-9a-f])[0-9a-f]{8}(?:-[0-9a-f-]{1,28})?', title.lower()):
            titled = list(db.execute(select + ' AND id LIKE ? LIMIT 2', (prefix + '%',)))
            if len(titled) == 1 and all(r['id'] != titled[0]['id'] for r in rows):
                rows.append(titled[0])
        result = []
        sessions = os.path.realpath(os.path.join(root, 'sessions'))
        for row in rows:
            path = os.path.realpath(row['rollout_path'])
            if not path.startswith(sessions + os.sep) or not os.path.isfile(path):
                continue
            try:
                with open(path, 'rb') as f:
                    head = json.loads(f.readline(1048577))
                payload = head.get('payload', {})
                if head.get('type') != 'session_meta' or payload.get('id') != row['id'] or not codex_root_meta(payload):
                    continue
            except (OSError, ValueError):
                continue
            result.append({'session_id': row['id'], 'path': path, 'created_ms': row['created_ms'], 'updated_at': row['updated_at'],
                           'same_cwd': os.path.realpath(row['cwd']) == os.path.realpath(cwd)})
        return result
    finally:
        db.close()
