# Find only a named rollout segment, never a newest-file heuristic.
import os, sys, json, re
current, rollout = sys.argv[1:3]
if not re.fullmatch(r'[A-Za-z0-9-]{1,128}', rollout):
    print('null'); sys.exit(0)
root = current.split('/sessions/', 1)[0] if '/sessions/' in current else current.split('/archived_sessions/', 1)[0]
found, scanned = [], 0
for folder in ('sessions', 'archived_sessions'):
    for directory, dirs, files in os.walk(os.path.join(root, folder)):
        scanned += len(files) + len(dirs)
        if scanned > 10000:
            print('null'); sys.exit(0)
        for name in files:
            if name.startswith('rollout-') and (name.endswith('_' + rollout + '.jsonl') or name.endswith('-' + rollout + '.jsonl')):
                found.append(os.path.join(directory, name))
print(json.dumps(found[0] if len(found) == 1 else None))
