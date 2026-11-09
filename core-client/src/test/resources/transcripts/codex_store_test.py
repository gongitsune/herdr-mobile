import unittest, tempfile, sqlite3, os, json, glob, re
from pathlib import Path

class StoreTest(unittest.TestCase):
    def test_no_new_threads_requires_complete_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.assertTrue(codex_has_no_new_threads(temp, '/project', 10000))
            (root/'sessions').mkdir()
            self.assertTrue(codex_has_no_new_threads(temp, '/project', 10000))
            (root/'sessions'/'rollout-unknown.jsonl').write_text('{}\n')
            self.assertFalse(codex_has_no_new_threads(temp, '/project', 10000))
            db = sqlite3.connect(root/'state_5.sqlite')
            db.execute('CREATE TABLE threads(id TEXT, rollout_path TEXT, cwd TEXT, archived INTEGER, agent_role TEXT, thread_source TEXT, source TEXT, created_at INTEGER, created_at_ms INTEGER, updated_at INTEGER)')
            # No usable rollout is needed to disprove an empty conversation.
            def add(id, cwd='/project', created=10000, role=None, source='cli'):
                db.execute('INSERT INTO threads VALUES(?,?,?,0,?,NULL,?,0,?,10)',
                           (id, str(root/'sessions'/('rollout-'+id+'.jsonl')), cwd, role, source, created))
                db.commit()
            add('old', created=9999)
            add('peer', cwd='/elsewhere')
            add('worker', role='worker')
            add('exec', source='exec')
            self.assertTrue(codex_has_no_new_threads(temp, '/project', 10000))
            add('new-at-boundary')
            self.assertEqual([], codex_candidates(temp, '/project'))
            before = {p.name:p.read_bytes() for p in root.glob('*.sqlite*')}
            self.assertFalse(codex_has_no_new_threads(temp, '/project', 10000))
            self.assertEqual(before, {p.name:p.read_bytes() for p in root.glob('*.sqlite*')})
            db.close()

    def test_read_only_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/'sessions').mkdir()
            cwd = root/'project'
            cwd.mkdir()
            (root/'link').symlink_to(cwd)
            db = sqlite3.connect(root/'state_5.sqlite')
            db.execute('CREATE TABLE threads(id TEXT, rollout_path TEXT, cwd TEXT, archived INTEGER, agent_role TEXT, thread_source TEXT, source TEXT, created_at INTEGER, created_at_ms INTEGER, updated_at INTEGER)')
            def add(id, **kw):
                payload = {'id':id, **kw.pop('meta', {})}
                path = root/'sessions'/('rollout-'+id+'.jsonl')
                path.write_text(json.dumps({'type':'session_meta','payload':payload})+'\n')
                values = dict(id=id, rollout_path=str(path), cwd=str(cwd), archived=0, agent_role=None, thread_source=None, source='cli', created_at=10, created_at_ms=None, updated_at=10)
                values.update(kw)
                db.execute('INSERT INTO threads VALUES (:id,:rollout_path,:cwd,:archived,:agent_role,:thread_source,:source,:created_at,:created_at_ms,:updated_at)',values)
            add('root', created_at_ms=10001)
            add('child', meta={'parent_thread_id':'root'})
            add('role', meta={'agent_role':'worker'})
            add('thread', meta={'thread_source':'worker'})
            add('subagent', meta={'source':{'subagent':{}}})
            add('archived', archived=1)
            add('worker', agent_role='worker')
            add('source', source='exec')
            add('other', cwd=str(root/'other'))
            add('abcdef01-1111-2222-3333-444444444444', cwd=str(root/'other'))
            add('escape', rollout_path=str(root/'outside.jsonl'))
            db.commit(); db.close()
            before = {p.name:p.read_bytes() for p in root.glob('*.sqlite*')}
            found = codex_candidates(str(root),str(root/'link'))
            self.assertEqual(['root'],[r['session_id'] for r in found])
            self.assertEqual(10001, found[0]['created_ms'])
            titled = codex_candidates(str(root),str(cwd),'Codex · abcdef01')
            self.assertEqual(['root','abcdef01-1111-2222-3333-444444444444'],[r['session_id'] for r in titled])
            self.assertFalse(titled[1]['same_cwd'])
            self.assertEqual(before,{p.name:p.read_bytes() for p in root.glob('*.sqlite*')})
            # Highest version wins; missing optional columns retain old schema support.
            db=sqlite3.connect(root/'state_12.sqlite')
            db.execute('CREATE TABLE threads(id TEXT, rollout_path TEXT, cwd TEXT, archived INTEGER, agent_role TEXT, created_at INTEGER, updated_at INTEGER)')
            db.execute('INSERT INTO threads VALUES(?,?,?,0,NULL,20,20)',('root',str(root/'sessions'/'rollout-root.jsonl'),str(cwd)))
            db.commit(); db.close()
            self.assertEqual(20000,codex_candidates(str(root),str(cwd))[0]['created_ms'])

if __name__ == '__main__':
    import sys
    namespace=globals()
    exec(Path(sys.argv.pop(1)).read_text(),namespace)
    unittest.main()
