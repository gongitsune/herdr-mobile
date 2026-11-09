"""Exercise real /proc evidence with a synthetic leaf agent and temporary state only."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = sys.argv.pop(1)


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='herdr-mobile-discovery-')
        self.root = Path(self.temp.name)
        (self.root/'sessions').mkdir()
        (self.root/'projects'/'fixture').mkdir(parents=True)
        env = dict(os.environ, HOME=str(self.root), CLAUDE_CONFIG_DIR=str(self.root),
                   CODEX_HOME=str(self.root), HC_SCOPE='herdr-mobile-test')
        env.pop('CLAUDE_CODE_CHILD_SESSION', None)
        env.pop('CLAUDE_CODE_SKIP_PROMPT_HISTORY', None)
        self.agent = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], env=env)
        start = Path('/proc/%d/stat' % self.agent.pid).read_text().rsplit(')', 1)[1].split()[19]
        self.registry = dict(pid=self.agent.pid, procStart='linux:'+start,
                             cwd=str(self.root), sessionId='fixture-session')
        self.registry_path = self.root/'sessions'/('%d.json' % self.agent.pid)
        self.log = self.root/'projects'/'fixture'/'fixture-session.jsonl'

    def tearDown(self):
        self.agent.kill()
        self.agent.wait(timeout=5)
        self.temp.cleanup()

    def probe(self, registry=None, kind='CLAUDE_CODE'):
        self.registry_path.write_text(json.dumps(self.registry if registry is None else registry))
        # Snapshot every file to ensure discovery cannot modify either registry or log.
        before = {str(p):p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        result = subprocess.run([sys.executable, '-c', SCRIPT, kind, str(self.agent.pid),
                                 str(self.agent.pid), str(self.root), 'probe', ''],
                                check=True, capture_output=True, text=True, timeout=5)
        self.assertEqual(before, {str(p):p.read_bytes() for p in self.root.rglob('*') if p.is_file()})
        return json.loads(result.stdout)

    def test_registry_without_log_and_metadata_only_log(self):
        evidence = self.probe()
        self.assertTrue(evidence['not_started'])
        self.assertIsNone(evidence['binding'])
        self.log.write_text('')
        self.assertTrue(self.probe()['not_started'])
        self.log.write_text(json.dumps(dict(type='system', sessionId='fixture-session'))+'\n')
        self.assertTrue(self.probe()['not_started'])

    def test_messages_even_after_first_page_are_not_empty(self):
        for kind in ('user', 'assistant'):
            self.log.write_text('{}\n'*40 + json.dumps(dict(type=kind, sessionId='fixture-session'))+'\n')
            evidence = self.probe()
            self.assertFalse(evidence['not_started'])
            self.assertEqual('fixture-session', evidence['binding']['session_id'])

    def test_stale_missing_and_ambiguous_registry_never_prove_not_started(self):
        for change in (dict(pid=self.agent.pid+1), dict(procStart='linux:0'), dict(sessionId='')):
            self.assertFalse(self.probe(dict(self.registry, **change))['not_started'])
        self.registry_path.unlink()
        result = subprocess.run([sys.executable, '-c', SCRIPT, 'CLAUDE_CODE', str(self.agent.pid),
                                 str(self.agent.pid), str(self.root), 'probe', ''],
                                check=True, capture_output=True, text=True, timeout=5)
        self.assertFalse(json.loads(result.stdout)['not_started'])
        self.log.write_text('{}\n')
        (self.root/'projects'/'other').mkdir()
        (self.root/'projects'/'other'/self.log.name).write_text('{}\n')
        self.assertFalse(self.probe()['not_started'])

    def test_partial_and_malformed_logs_are_ambiguous(self):
        for text in ('{', '{}', '{invalid}\n'):
            self.log.write_text(text)
            self.assertFalse(self.probe()['not_started'])

    def test_codex_empty_store_proves_absence_but_uninventoried_rollout_does_not(self):
        evidence = self.probe(kind='CODEX')
        self.assertTrue(evidence['no_new_threads'])
        self.assertIsNotNone(evidence['process_started_ms'])
        (self.root/'sessions'/'rollout-unknown.jsonl').write_text('{}\n')
        self.assertFalse(self.probe(kind='CODEX')['no_new_threads'])


unittest.main()
