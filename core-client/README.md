# core-client

Pure Kotlin/JVM generation-1 herdr-core client, without Android dependencies.

- `wire/Frame.kt`: little-endian framing, partial reads, strict 16 MiB cap.
- `rpc/CoreConnection.kt`: plain hello, framed multiplexing, request IDs,
  reply-time channel registration, CREDIT/CLOSE, pending-call failure on disconnect.
- `model/Models.kt`: OpenRPC models with unknown-field tolerance.
  The wire identifier is `address`; `Terminal.id` aliases it.
- `HcClient.kt`: typed RPC and attach. Output permits exactly one lifetime
  collection; a second collection throws. CREDIT follows downstream consumption.
- `TerminalStore.kt`: snapshots, sequenced events, missed replacement,
  and fresh subscriptions on reconnect.
- `SshTransportFactory.kt`: SHA256 fingerprint verification **before authentication**,
  password/OpenSSH private-key auth, XDG_STATE_HOME-aware core.lock/runtime path resolution,
  streamlocal with shell-quoted socat fallback. It never starts a core.
- `SshKeys.kt`: BC Ed25519 generation, unencrypted OpenSSH private PEM bytes,
  and one authorized_keys line. Encrypt private PEM in app storage.

The caller owns the scope and must close the client before cancelling it.
Automatic reconnect is enabled by default. Transport failures retry at exponential
1, 2, 4, 8, 16, 30s bases with ±20% jitter and an actual 30s ceiling. Successful
connections reset the backoff; auth/host_key/unsupported failures stop retries.

Use `client.autoReconnect = false` to stop pending retries while preserving an
established connection, or `client.reconnectNow()` to bypass the wait (including
after a permanent error). Explicit `close()` prevents retries until a subsequent
`connect()` or `reconnectNow()`. The first connect attempt reports its error to
the caller even when background retry is enabled. Failed RPC/input is never replayed.

`ConnectionState.Reconnecting(attempt, nextDelayMs)` describes the current retry.
TerminalStore always resubscribes to a new connection, even with unchanged core
identity/features. Its `terminals` is empty during disconnect/snapshot loading;
`staleTerminals` retains the last list until a fresh snapshot arrives. The app
chooses whether to display the stale list, also after explicit close.

Attach sessions are not reopened automatically: on a lost connection, `closed`
completes normally with `CloseReason("disconnected")`. Use `Event.attachRecovery(id)`
or `Terminal.attachRecovery(previous)` to distinguish WAIT_FOR_RESTART (lost),
REATTACH (restarted/new program), FINISHED, and NONE.

## App / codex-b integration

The public API is fixed in docs/PLAN.md:

```kotlin
val key = SshKeys.generateEd25519("herdr-mobile")
val auth = SshAuth.PrivateKey(key.privatePem) // or SshAuth.Password(password)
val factory = SshTransportFactory(
    SshConfig(host = host, user = user, auth = auth,
        coreSession = session, hostKeyFingerprint = savedFingerprint),
) { fingerprint ->
    // Called before authentication, on an IO thread. Switch to Main for a UI prompt.
    // Persist this exact SHA256:... string in host settings only when accepted.
    confirmAndSaveFingerprint(fingerprint)
}
val client = HcClient(factory, scope)
```

`GeneratedKey.authorizedKey` includes the comment and no newline. A saved
fingerprint mismatch never prompts again and fails with `HcError.kind == "host_key"`.
Other transport kinds: `auth`, `not_running`, `unsupported`, `io`.
The accepted key is pinned in memory for the factory lifetime, including reconnects.
The app must persist the fingerprint inside the callback before returning true;
new factory instances must receive that saved fingerprint.

Dependencies are local to this module: mwiede/jsch 2.28.8 and bcprov-jdk18on 1.86.
JSch uses explicit lightweight BC adapters for EdDSA host verification / XDH,
so do not replace Android's built-in security provider globally.

`consumer-rules.pro` keeps reflective JSch/BC code. Gradle embeds it in
`META-INF/proguard/herdr-core-client.pro` in the JVM jar for automatic R8 discovery.
No app changes are required for this path. If the consuming toolchain does not
consume jar rules, codex-b can explicitly add this in app's release build type:

```kotlin
proguardFiles(rootProject.file("core-client/consumer-rules.pro"))
```

These conservative rules keep JSch/BC classes, including optional integrations.
Minified API-26 device testing remains required before narrowing them.

## Tests

`./gradlew :core-client:test` runs unit tests; both integration tests are
disabled unless `HC_IT=1`:

```sh
HC_IT=1 ./gradlew :core-client:test --rerun-tasks
```

Integration tests exclusively use **HC_SESSION=herdr-mobile-it**. They read
`~/.local/state/herdr-core/herdr-mobile-it/core.lock`, and start the dedicated
core with `hc server start` only when absent. Startup pins HC_SOCKET to the
dedicated socket, overriding inherited/global configuration. A core.lock pointing
at another session is rejected before connecting. No default-session connection
or process-starting production transport is used.

The local test uses Java NIO UnixDomainSocketAddress. The SSH test generates a
temporary key (comment `herdr-mobile-test`), appends just that key to
`~/.ssh/authorized_keys`, runs through localhost SSH over both streamlocal and an enforced socat fallback, verifies run/attach/events
and host-key/authentication errors, and removes exactly its own key line.
Other keys and edits are preserved. Both tests label their terminals
`herdr-mobile-test=1` and kill/remove only those terminals in cleanup.
The isolated test core remains running for reuse.

## Phase 4: workspaces, transcripts and repository reads

The exact public signatures are in `docs/PLAN.md` under “Phase 4 公開 API”.

- `workspace.WorkspaceStore(client, scope)` exposes `spaces` and `error`. It
  preserves desktop document order, recursively flattens terminal panes, qualifies
  remote IDs, adopts `herdr.space` / `herdr.tab`, groups unlabelled terminals by
  startup cwd, and places unlabelled children in their parent's tab. Subscription
  installation precedes `doc.get`; doc events, missed snapshots, new machines and
  reconnects refetch documents. It never calls `doc.set` or changes labels.
- `workspace.AgentDetector` unwraps recognized shell/env/interpreter/npm launch
  forms without executing them. A shell foreground overrides stale Program Status.
- `transcript.TranscriptSource(client, terminalId, scope)` owns its subscription
  and polls append reads while alive; call `close()` when leaving the detail. Its
  `items` are native `ChatItem` rows and `state` distinguishes loading, verified
  binding, unavailable evidence and RPC failure. Parsing/projection runs away from
  the UI thread. It never sends input or changes a transcript/label/document.
- Session discovery requires Python 3 and readable Linux `/proc` on the remote
  machine. It follows only processes in the terminal's HC_SCOPE, compares process
  start identity, reads Claude's matching PID registry (or a current-process
  explicit session launch flag), and verifies Codex's open root rollout headers.
  Shared Codex daemons or multiple root sessions cannot be auto-bound. Only
  whitelisted evidence leaves the host; neither full environment nor discovery
  argv nor real transcript content is copied into tests.
- Recent files are **candidates**, never automatic cwd/newest-file matches. The
  UI may call `candidates()`, display `preview(candidate)`, then ask the user to
  confirm before `select(candidate)`. A selection is local to the source and
  invalidated by a changed process. Accepted exact bindings can remain readable
  under an unavailable banner, while following pauses until evidence returns.
- `file.read` offsets are one-based **line numbers**, not bytes. Following
  rereads one checkpoint line, retries unfinished tails and resets after
  truncation/reconnect. Claude UUID branches, parallel tool results and compact
  boundaries are projected; Codex legacy rollback and paginated snapshots are
  supported. A paginated revert's explicit history base is loaded only after its
  byte boundary and final ordinal match. Missing earlier segments are marked,
  never silently presented as complete history.
- Display memory is bounded at 32 MiB / 100,000 current-file lines. Core additionally
  limits text files to 64 MiB and RPC reads to 8 MiB. Oversized content reports a
  failure/partial history rather than claiming completeness. Compressed `.zst`
  rollouts are not automatically bound. Unknown future JSON records are ignored;
  complete malformed lines are skipped, but incomplete final lines are retried.
- Questions/approval cards are displayed only while Blocked. An option with empty
  `keys` has no verified automatic key mapping: use the manual keypad. Do not infer
  an approval action from old transcript text. The source performs no sending;
  Composer continues to use `terminal.send` / `terminal.keys` per PLAN.
- `HcClient.changes(cwd, on)`, `listFiles(cwd, path, on)` and
  `readFile(path, cwd, offset, limit, on)` are read-only. Git reads disable optional
  locks, fsmonitor, external diff and textconv; porcelain v2 is NUL-parsed, including
  renames and names containing spaces/newlines. Directory listings include dotfiles
  (except `.git`), directory/symlink flags, and stop at 10,000 entries.

Phase 4 integration tests use only `herdr-mobile-it`, label their own terminals,
create synthetic agent process evidence/JSONL and a throwaway git index under
`/tmp`, and clean up those terminals/files. They never commit, write the desktop
layout document, or copy the user's actual `~/.claude` / `~/.codex` logs.
