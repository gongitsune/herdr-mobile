# herdr mobile (Android native) — 実装計画

ホスト上で動く **herdr-core (`hc`)** に SSH 経由で接続する Android ネイティブクライアント。
UI/機能は iOS の Heeler (ZingerLittleBee/Heeler, Apache-2.0) の herdr-core Host 対応を参考にする。
サーバー側は一切実装しない（herdr-core をそのまま使う）。

## 決定事項

| 項目 | 決定 |
|---|---|
| 言語/UI | Kotlin, Jetpack Compose + Material 3 |
| minSdk / target | 26 / 35 |
| パッケージ | `io.herdrmobile.android`（app）, `io.herdrmobile.core`（client lib） |
| ツールチェーン | JDK 21 は mise (`mise use -g java@temurin-21`)、Android SDK は `~/Android/Sdk` に公式 cmdline-tools + sdkmanager。Gradle は wrapper (8.x) |
| 接続 | SSH。1 本の SSH セッション上に 1 本のバイトストリームを張り、herdr-core の framed モードで RPC / events / attach を多重化 |
| 端末描画 | Termux の `terminal-emulator` / `terminal-view`（Apache-2.0）を `third_party/` にベンダリングし、ローカルプロセスではなく attach ストリームで駆動 |
| 動作確認 | 実機（adb） |

## herdr-core プロトコル要点（v0.1.18, generation 1）

参考資料: `herdr-core/schema/README.md`, `schema/hc.openrpc.json`（クローン済み:
`/tmp/claude-1000/-home-student-develop-herdr-mobile/c54711d3-09e1-4e74-82af-0e0adc5a2a48/scratchpad/ref/herdr-core`、
Heeler: 同 `ref/Heeler`）。実機ホストでも `rpc.discover` で同じスキーマが取れる。

- ソケット: Linux は `$XDG_RUNTIME_DIR/herdr-core/<session>.sock`（例 `/run/user/1000/herdr-core/default.sock`）。
  正確なパスは `~/.local/state/herdr-core/<session>/core.lock` の中身（パス文字列）。
- plain モード: JSON-RPC 2.0、1 行 1 メッセージ。エラーは `error.data.kind` で区別。
- `hello {"generation":1,"framed":true}` の **応答後** から framed モード:
  `[channel u32 LE][kind u8][len u32 LE][payload ≤16MiB]`
  kind: 0 DATA, 1 CREDIT(u32), 2 CLOSE, 3 RESIZE(u16 cols,u16 rows), 4 FOCUS(u8), 5 FACTS(JSON), 6 AUTOMATIC_INPUT
- channel 0 = JSON-RPC（双方向・並行）。`terminal.attach` / `events.subscribe` は `channel` を返す。
- attach: 初回ペイント→生出力。キー入力は同 channel に DATA。初期クレジット 256KiB、消費したバイト数を CREDIT で返す。
  CLOSE 受信で終了（JSON reason: `{"reason":"exited","exit_code":0,...}`）。
- 必須 feature: `framed`, `attach`, `events`, `status`。足りなければ「herdr-core を更新して」と表示。
- events: スナップショット(`seq`, terminals) → `event`(created/lost/restarted/keep/finished/removed/status/turn/title/labels/size/foreground), `heartbeat`(15s), `missed`(新スナップショットで置換)。再接続時は必ずスナップショット取り直し。
- **接続で core を起動しない**（`herdr-core connect` は自動起動するので使わない）。core が居なければ「not running」を表示。

## アーキテクチャ

```
:app (Android, Compose)  ──depends──▶  :core-client (pure Kotlin/JVM, Android 依存なし)
   │                                        ├─ transport/   ByteTransport, SshTransport
   │                                        ├─ wire/        Frame codec
   │                                        ├─ rpc/         CoreConnection (多重化, JSON-RPC)
   │                                        ├─ model/       Terminal, Status, Event ... (kotlinx.serialization)
   │                                        └─ HcClient, TerminalStore, AttachSession
   └─ third_party/termux-terminal (vendored, Apache-2.0)
```

### :core-client 公開 API（契約。変更時は PLAN.md を更新し、もう一方に知らせる）

```kotlin
package io.herdrmobile.core

interface ByteTransport : java.io.Closeable {
    val input: java.io.InputStream
    val output: java.io.OutputStream
}
fun interface TransportFactory { suspend fun open(): ByteTransport }

data class SshConfig(
    val host: String, val port: Int = 22, val user: String,
    val auth: SshAuth,
    val coreSession: String = "default",
    val socketPath: String? = null,          // null なら core.lock → 既定パスで解決
    val hostKeyFingerprint: String? = null,  // SHA256:... TOFU で保存
)
sealed interface SshAuth {
    data class Password(val password: String) : SshAuth
    data class PrivateKey(val pem: ByteArray, val passphrase: String? = null) : SshAuth
}
class SshTransportFactory(config: SshConfig, onUnknownHostKey: suspend (fingerprint: String) -> Boolean) : TransportFactory
// 秘密鍵は暗号化なしの OpenSSH PEM (UTF-8 bytes)。保存時の暗号化は app 側の責務。
data class GeneratedKey(val privatePem: ByteArray, val authorizedKey: String)
object SshKeys { fun generateEd25519(comment: String): GeneratedKey }

sealed interface ConnectionState {
    data object Disconnected; data object Connecting
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long)
    data class Connected(val core: CoreInfo, val features: Set<String>)
    data class Failed(val error: HcError)   // kind: not_running / auth / host_key / unsupported / io ...
}

class HcClient(factory: TransportFactory, scope: CoroutineScope, autoReconnect: Boolean = true) {
    val state: StateFlow<ConnectionState>
    var autoReconnect: Boolean
    suspend fun connect(); fun close()
    suspend fun reconnectNow()  // 自動再接続 off／恒久エラー後でも手動で即時試行
    suspend fun list(all: Boolean = false): List<Terminal>
    suspend fun get(id: String): Terminal
    suspend fun run(argv: List<String>? = null, line: String? = null, cwd: String? = null,
                    name: String? = null, keep: Boolean = false, labels: Map<String,String> = emptyMap()): Terminal
    suspend fun templates(): List<Template>
    suspend fun send(id: String, text: String, force: Boolean = false)
    suspend fun keys(id: String, keys: List<String>)
    suspend fun interrupt(id: String)
    suspend fun kill(id: String); suspend fun rm(id: String)
    suspend fun read(id: String, lines: Int = 200): String
    suspend fun attach(id: String, cols: Int, rows: Int, observe: Boolean = false, history: Int? = null): AttachSession
    suspend fun call(method: String, params: JsonObject): JsonElement   // 生 RPC
}

class TerminalStore(client: HcClient, scope: CoroutineScope) {
    val terminals: StateFlow<List<Terminal>>   // 有効な接続の snapshot + events。切断・snapshot 待ちは空
    val staleTerminals: StateFlow<List<Terminal>> // 切断前の一覧。新 snapshot 成功時に空へ（表示するかは app 側）
}

interface AttachSession {
    val output: kotlinx.coroutines.flow.Flow<ByteArray>  // collect 後に自動で CREDIT を返す
    val closed: kotlinx.coroutines.Deferred<CloseReason> // 接続喪失では CloseReason("disconnected") で正常完了
    suspend fun write(bytes: ByteArray)                  // DATA
    suspend fun writeAutomatic(bytes: ByteArray)         // AUTOMATIC_INPUT (negotiate 済みのみ, それ以外は DATA)
    suspend fun resize(cols: Int, rows: Int)
    suspend fun focus(gained: Boolean)
    suspend fun detach()
}

// lost では待機、restarted では再 attach（ライブラリ側は自動 attach しない）
enum class AttachRecovery { NONE, WAIT_FOR_RESTART, REATTACH, FINISHED }
fun Event.attachRecovery(terminalId: String): AttachRecovery
fun Terminal.attachRecovery(previous: Terminal?): AttachRecovery
```

`Terminal` は schema の Terminal レコードを写す（`id, name, state, title, status, foreground{pid,name,argv,cwd}, cwd, labels, size, turn, exit_code, reason, ...`、`ignoreUnknownKeys = true`）。
並び順の優先度ヘルパー `Terminal.priority`: blocked/error → working → done → idle → status なし → finished。

SSH 公開 API は上記シグネチャで確定（codex-a Phase 2）。
`GeneratedKey.authorizedKey` はコメントを含む改行なしの authorized_keys 1 行。
`SshAuth.PrivateKey.pem` に `GeneratedKey.privatePem` をそのまま渡せる。
TOFU callback は認証前に呼ぶ suspend callback（呼出 dispatcher は IO、UI は app 側で切替）。
**app は受け入れた fingerprint を onUnknownHostKey 内で永続保存してから true を返す**。
factory 自体は永続保存しないため、新しい factory には保存値を SshConfig.hostKeyFingerprint に渡す。
同じ factory は受け入れた値をメモリ上に保持し、自動再接続時も固定する（同じ鍵では callback を繰り返さない）。
既知 fingerprint 不一致／初回拒否は `HcError("host_key", ...)`。
接続エラーの kind は `auth / host_key / not_running / unsupported / io`。
`socketPath` 指定時はそれを優先し、未指定なら remote `${XDG_STATE_HOME:-$HOME/.local/state}/herdr-core/<session>/core.lock` → XDG_RUNTIME_DIR を使う。
SSH transport は core を起動しない（専用 core の自動起動は結合テスト側のみ）。

再接続は既定 on。connect() の初回試行は呼出側へ成功／失敗を返し、再試行可能な失敗はバックグラウンドで継続する。
切断／接続失敗の再試行は 1, 2, 4, 8, 16, 30 秒（以降 30 秒）の基準に ±20% ジッター、実際の待機上限も 30 秒。
Reconnecting.attempt は 1 始まり、nextDelayMs はジッター適用後の待機時間。
成功でバックオフをリセット。auth / host_key / unsupported は Failed として停止する。
autoReconnect = false は待機中の再試行を止め、接続中の通信は維持する。true に戻すと再試行可能な Failed のみ再開する。
close() は進行中接続・待機も止め、以後 connect()/reconnectNow() まで再試行しない。
reconnectNow() はバックオフを破棄して即時再接続する。RPC／attach 入力は自動再送しない。
TerminalStore は接続の世代ごとに必ず再 subscribe し、seq がリセットされた新 snapshot で置換する。
staleTerminals は明示 close 後も新 snapshot まで保持される。app は ConnectionState と合わせて表示を判断する。

### SSH トランスポート方針
1. 第一候補: `direct-streamlocal@openssh.com` チャネルでソケットに直結（Heeler と同じ。core を起動しない）。
   ライブラリは **mwiede/jsch + BouncyCastle** を使用（Android API 26 対応、consumer rules は core-client/ 内）。
2. フォールバック: exec チャネルで `socat STDIO UNIX-CONNECT:<path>`（パスは `cat core.lock` で取得）。
3. ホスト鍵は初回 TOFU で fingerprint を保存、以後不一致なら拒否。

## 画面（:app）— Heeler 参考

1. **Hosts**: 一覧（接続状態・レイテンシ）、追加/編集（host, port, user, 認証=端末内生成 Ed25519 鍵 or パスワード, core session）。
   鍵生成後に `authorized_keys` 行を表示・コピー。秘密情報は Android Keystore で暗号化して保存。
2. **Console**（ホーム）: 全ホストの Terminal をライブ一覧、Blocked/Error 先頭。ホストでフィルタ。
   行: ステータスチップ、title / name / foreground 名、cwd、ホスト名。FAB で新規 Terminal（テンプレート or コマンド + cwd）。
3. **Terminal 詳細**: Termux TerminalView で attach 表示（タッチスクロール, 選択コピー）。
   追加キー行（Esc, Tab, Ctrl, ↑↓←→, Ctrl+C, Enter）。下部に Composer（ネイティブ IME で下書き → `terminal.send`）。
   メニュー: Interrupt, Kill, Observe（読み取り専用）切替, Read（テキスト表示）。
4. **通知**（Phase 3）: フォアグラウンド中は events で blocked/done/error をローカル通知。

## フェーズとアサイン

| Phase | codex-a (`w3:p2`) | codex-b (`w3:p3`) |
|---|---|---|
| 0 | `core-client/` に wire codec と JSON-RPC 多重化を実装（JDK 待ちの間はコード作成のみ） | ツールチェーン導入（mise JDK21, ~/Android/Sdk）、Gradle ルート・wrapper・`:app` `:core-client` 空モジュール、`./gradlew assembleDebug` が通る状態 |
| 1 | HcClient / TerminalStore / AttachSession、JVM 結合テスト（ローカル core の Unix socket に直結） | Compose ナビゲーション、Hosts 画面 + 保存、Console 画面（Fake の TerminalStore で） |
| 2 | SshTransport（streamlocal / socat フォールバック）、鍵生成、ssh localhost での結合テスト | Termux ベンダリング＋RemoteTerminalSession、Terminal 詳細画面、実クライアント結線 |
| 2.5 | — | GitHub Actions CI（下記「CI / 配布」） |
| 3 | 再接続・バックオフ、エラー分類 | 通知、仕上げ、実機テスト |

## CI / 配布（実機 adb が使えないため）

- リポジトリ: `gongitsune/herdr-mobile`（private）。作成・初回 push・Secrets 登録は Claude が Phase 2 レビュー後に行う。
- `.github/workflows/android.yml`（codex-b 担当）:
  - トリガ: push（main）, pull_request, workflow_dispatch。`ubuntu-latest`、`actions/setup-java`（temurin 21）、`android-actions/setup-android` または runner 同梱 SDK、`gradle/actions/setup-gradle`。
  - `./gradlew :core-client:test :app:assembleDebug :app:lintDebug`（結合テストは CI では実行しない。HC_IT 未設定でスキップされること）。
  - APK を `actions/upload-artifact` で保存（名前に short SHA）。
  - main への push 時は `nightly` pre-release を作り直して `herdr-mobile-<sha>.apk` を添付する（`softprops/action-gh-release` か `gh release`、`permissions: contents: write`）。
- 署名: 上書きインストールできるよう固定の debug keystore を使う。
  - `app/build.gradle.kts` の debug signingConfig は環境変数 `HERDR_DEBUG_KEYSTORE`（パス）, `HERDR_DEBUG_KEYSTORE_PASSWORD`, `HERDR_DEBUG_KEY_ALIAS`, `HERDR_DEBUG_KEY_PASSWORD` があればそれを使い、無ければ Android 既定の debug 鍵。
  - CI では Secret `DEBUG_KEYSTORE_BASE64` などをデコードして上記環境変数に渡す。Secret が無い（fork PR 等）場合は既定鍵でビルドを続行。
  - keystore はリポジトリに入れない。ローカルの正本は `~/.config/herdr-mobile/debug.keystore`。
- versionCode は CI で `GITHUB_RUN_NUMBER` を使う（無ければ 1）。

## Phase 4: herdr-app 風 UI ＋ ターミナル ⇔ チャット切替

ユーザー決定（2026-11-08）: アプリ全体を herdr-app の構成に合わせてモバイル向けにする。さらに各エージェントで「ターミナル ⇔ チャット」を切り替えられるようにする。チャットのデータ源は **エージェント自身が書き出す会話ログ**（ACP は使わない）。

参考資料（scratchpad の `ref/` にクローン済み、どちらも Apache-2.0）:
- `ref/herdr-app`: `README.md`, `defaults/mods/{agents,spaces,tabs,changes,files,peek,prompt,workspace-status}/main.js`, `defaults/themes/atom-one-dark.json`, `defaults/layouts/main.json`, `docs/labels.md`, `src/layout_sync.rs`
- `ref/Heeler/docs/adr/0024`（会話ログからのチャット）, `0032`（herdr-core を herdr-app の構成で見せる）, `0034`（チャットとセッションの対応付け）, `0035`（送信を attach 経由で行う）, `docs/research/claude-code-transcript-format.md`, `codex-rollout-format.md`

### 構成モデル（読み取り専用）
- Space / Tab / Pane は、core の document `herdr-app`（`doc.get`、`doc` イベントで読み直す）と端末ラベル `herdr.*` から組み立てる。`docs/labels.md` の規約に従う。
- **`herdr-app` document には絶対に書き込まない**（デスクトップの配置が壊れる。Heeler ADR 0032）。新しい端末を Space に入れるときは、herdr-app の規約どおりのラベルを付けて取り込ませる。
- ラベルの無い端末は cwd ごとの「派生 Space」に入れる。別の端末の子（サブエージェント）は親と同じ Tab の Pane にする。
- Agent 判定: foreground プログラムを unwrap して（`sh -c`、`npx`、herdr-app のラッパー）、claude / codex などの既知エージェントか、Program Status を報告しているかで決める。

### チャット（会話ログ）
- 対応は Claude Code と Codex（最初はこの2つだけ）。ログは `file.read`（offset/limit で追記分だけ読む）と `machine.exec`（ファイルの場所を探す）で取得する。追加の SFTP は使わない。
- 端末とセッションの対応付けは Heeler ADR 0034 に従う（foreground の argv や cwd、セッション ID を指定する起動フラグ、最新ファイルなど）。
- 共通モデル `ChatItem`: UserMessage, AssistantText(markdown), Thinking(折りたたみ), ToolCall(name, 入力の要約, 結果, 状態), Question/Approval（Blocked のときの選択肢）, TurnMarker。
- 送信は Composer → `terminal.send`。Blocked のときの選択肢ボタンは `terminal.keys`。チャットとターミナルで Composer を共有する。

### 画面（モバイル化した herdr-app）
- 下部ナビゲーション: **Agents**（今の Console。Blocked / Working / Done / Idle でグループ化）, **Spaces**, **Hosts**。
- Spaces: Space 一覧（ホストごと）→ Space 詳細（上部に横スクロールのタブ列、Pane が複数あれば縦に並べるかスワイプで切り替え）。Space 詳細のボトムシートに **Changes**（`git status --porcelain=v2` と diff）と **Files**（ツリーと読み取り専用の表示）。
- エージェント詳細: 上部のセグメントで **Terminal ⇔ Chat** を切り替え（既定はチャット。会話ログが無いエージェントはターミナル）。切り替えても attach は維持し、表示だけを替える。
- Peek: Agents 一覧で長押しすると、observe で attach した小さなプレビューをボトムシートで出す。
- テーマ: herdr-app の atom-one-dark とステータスの色に合わせる（ライト/ダークの両方）。端末の ANSI 16色も同じテーマにする。

### 担当
| | codex-a (`core-client/**`) | codex-b（それ以外） |
|---|---|---|
| 4a | `workspace/`: herdr-app document とラベルから Space/Tab/Pane を組み立てる `WorkspaceStore`、Agent 判定 | ナビゲーションの再構成（Agents / Spaces / Hosts）、テーマ、Space 一覧と詳細（Fake データでよい） |
| 4b | `transcript/`: `TranscriptSource`（file.read で追記分を読む）, Claude Code / Codex のアダプタ → `ChatItem`、セッションの対応付け。フィクスチャを使った単体テスト | チャット画面（Markdown、ツール呼び出しのカード、思考の折りたたみ、Approval のボタン）、Terminal ⇔ Chat の切り替え、Peek |
| 4c | `HcClient` に `changes(cwd)` / `listFiles(cwd, path)` / `readFile` のヘルパー | Changes / Files のボトムシート、仕上げ |

公開 API は codex-a が先に PLAN.md に追記してから実装する。codex-b はそれまで Fake で進める。

## 担当範囲（衝突防止）
- codex-a: `core-client/**` のみ。
- codex-b: それ以外すべて（ルート Gradle 設定、`gradle/libs.versions.toml`、`app/**`、`third_party/**`）。
  codex-a が依存追加を必要とする場合は `core-client/build.gradle.kts` 内で完結させる（バージョンカタログに入れたい場合は codex-b に依頼）。
- 公開 API の変更は PLAN.md を更新してから。
- git commit はしない（Claude がレビュー後にまとめる）。

## テストの注意
- 結合テストは **専用セッション `herdr-mobile-it`** の core を使う（`HC_SESSION=herdr-mobile-it hc server start` で起動、ソケットは `/run/user/1000/herdr-core/herdr-mobile-it.sock`、正確には `~/.local/state/herdr-core/herdr-mobile-it/core.lock`）。本番の default セッションには触らない。テスト用 core の stop/restart は可。作成 Terminal には label `herdr-mobile-test=1` を付け、終了時に kill + rm する。
- SSH 結合テスト（Phase 2）は `ssh localhost`。テスト用に生成した公開鍵を `~/.ssh/authorized_keys` に追記してよい（ユーザー承認済み）。コメントは `herdr-mobile-test` にして識別可能にすること。
- 結合テストは `-PintegrationTests` または環境変数 `HC_IT=1` のときだけ実行。
