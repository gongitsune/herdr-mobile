# Phase 2 SSH library decision

Prefer **com.github.mwiede:jsch** for the first implementation.

- Its [ChannelDirectStreamLocal implementation](https://github.com/mwiede/jsch/blob/master/src/main/java/com/jcraft/jsch/ChannelDirectStreamLocal.java)
  explicitly opens `direct-streamlocal@openssh.com`, with a public
  `setSocketPath(String)`. Open that channel on the authenticated session,
  set the remote socket path, obtain its streams, then connect the channel.
  No local Unix-domain-socket API is required on Android.
- The [README](https://github.com/mwiede/jsch/blob/master/Readme.md)
  specifies Java 8 as the baseline. The
  [upstream changelog](https://github.com/mwiede/jsch/blob/master/ChangeLog.md)
  includes Android compatibility fixes (e.g. #397).
  Android API 26 is a viable target, but actual device verification remains
  a Phase 2 task; a successful desktop JVM run alone does not prove it.
- For API 26, bundle and explicitly use the appropriate Bouncy Castle support
  for Ed25519/X25519. Upstream documents that Ed25519 requires Java 15
  or Bouncy Castle and X25519 requires Java 11 or Bouncy Castle.
  Building with JDK 21 does not supply these providers to an Android device.
  Check R8/reflection rules and provider coexistence on the target device.
- Apache MINA sshd's reviewed
  [ClientSession API](https://github.com/apache/mina-sshd/blob/master/sshd-core/src/main/java/org/apache/sshd/client/session/ClientSession.java)
  exposes direct TCP/IP channels; no ready-made streamlocal channel was found
  in the reviewed API. A custom channel extension would need verification.
  Its [Android document](https://github.com/apache/mina-sshd/blob/master/docs/android.md)
  explicitly says Android compatibility is not tested or an active support
  goal. Therefore it offers less certainty for this use case.

Implemented in Phase 2: TOFU fingerprint verification before authentication, one
SSH session and one streamlocal byte stream, remote core.lock/runtime socket
resolution, and shell-quoted socat exec fallback only if streamlocal is unavailable.
Neither path may invoke `hc connect` or start/stop the core.
Phase 2 uses mwiede/jsch 2.28.8 and bcprov-jdk18on 1.86. See README.md for consumer rules and app integration.
