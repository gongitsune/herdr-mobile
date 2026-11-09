# Remote terminal adaptation

Upstream: https://github.com/termux/termux-app, v0.118.3,
commit `5b657c6adf4304e5198951ce815fe0205dcac29c`.

Only terminal-emulator and terminal-view Java/resources are vendored.

- Replaced Gradle publishing/native configuration with Android library Kotlin DSL,
  compile SDK 35, minimum SDK 26, and AndroidX annotations.
- Replaced the process-specific implementation of TerminalSession with a remote-only
  RemoteIO adapter. Kept its UTF-8 codepoint encoder and TerminalView-facing API.
  Removed subprocess creation, PTY/FileDescriptor reflection, process queues,
  wait/read/write threads, signals and JNI calls. The class remains concrete;
  app-side RemoteTerminalSession supplies RemoteIO callbacks.
- Omitted JNI.java and native sources/build files entirely. No libtermux is loaded.
- Added appendRemote (main-thread emulator append + text callback), markExited,
  and RemoteIO write/resize/close hooks. finishIfRunning detaches; it never kills
  a local or remote process. getPid returns -1 and getCwd returns null.
- Added host-JVM RemoteSessionTest coverage for ANSI/streamed UTF-8, resize,
  UTF-8 input encoding, remote close and exit callbacks without native loading.
- Removed `final` from TerminalView so the app's TailTerminalView can override
  local sizing/drawing for a read-only bottom-row crop. The renderer, selection
  code and emulator parsing remain unchanged. Tail previews never resize the PTY.
- App RemoteTerminalSession serializes remote writes/resizes, collects AttachSession
  output on the main thread, returns via the flow collector before credit, and
  detaches on screen/lifecycle disposal. Observe mode suppresses writes and resize.

The original repository license exception is retained in UPSTREAM-LICENSE.md;
Apache-2.0 text and attribution are in LICENSE and NOTICE.
