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
