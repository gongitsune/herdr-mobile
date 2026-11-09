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
- ~~ラベルの無い端末は cwd ごとの「派生 Space」に入れる。~~ → **Phase 6 で廃止**。Space の出し方は herdr-app に合わせる（下の「Phase 6」節）。
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

### Phase 4 公開 API（codex-a / 確定）

4a は読み取り専用。document の名前・順序・pane 配置を優先し、ラベルだけの space/tab、cwd 派生 Space、子 Pane を補完する。
ID は machine ごとに修飾される。document の未知フィールドは無視する。doc.set は呼ばない。

```kotlin
package io.herdrmobile.core.workspace

enum class AgentKind { CLAUDE_CODE, CODEX, PI, OH_MY_PI, OPENCODE, GEMINI, COPILOT, CURSOR, AMP, GROK, OTHER }
data class AgentInfo(val kind: AgentKind, val program: String, val argv: List<String>, val pid: Int? = null)
object AgentDetector {
    fun detect(terminal: Terminal): AgentInfo?
    fun unwrap(argv: List<String>): List<String>
}
data class Pane(val id: String, val terminal: Terminal, val agent: AgentInfo? = null)
data class Tab(val id: String, val name: String, val panes: List<Pane>)
data class Space(val id: String, val name: String, val cwd: String, val tabs: List<Tab>,
                 val machine: String? = null, val derived: Boolean = false)
class WorkspaceStore(client: HcClient, scope: CoroutineScope) : java.io.Closeable {
    val spaces: StateFlow<List<Space>>
    val error: StateFlow<HcError?>
    suspend fun refresh()
    override fun close()
}
```

4b は Source の存続中だけ追記をポーリング（既定 1500ms）。binding の再評価は初回、端末の foreground/status/turn/labels の変化、再接続、明示 refresh に行う。
ADR 0034 に従い **cwd／最新ファイルだけでは自動 bind しない**。Claude registry の pid＋process start、Codex が開く唯一の root rollout 等の証拠を優先。
確定できないときは Unavailable と候補一覧を提示し、ユーザーが読んで確認した候補だけ select() で採用する（端末プロセスが替われば解除）。
file.read は **1 始まりの行 offset**。未完の末尾行は再読し、切詰め・再接続・ファイル変更時は必要に応じて投影を再構築する。
秘密を含む全環境や argv 全体を machine.exec で持ち出さず、指定した証拠のフィールドだけを返す。

```kotlin
package io.herdrmobile.core.transcript

enum class ToolState { RUNNING, SUCCEEDED, FAILED }
data class ChatOption(val label: String, val description: String? = null, val keys: List<String> = emptyList())
sealed interface ChatItem {
    val id: String
    val atEpochMs: Long?
    data class UserMessage(override val id: String, val text: String, override val atEpochMs: Long? = null) : ChatItem
    data class AssistantText(override val id: String, val markdown: String, override val atEpochMs: Long? = null) : ChatItem
    data class Thinking(override val id: String, val text: String, override val atEpochMs: Long? = null) : ChatItem
    data class ToolCall(override val id: String, val name: String, val inputSummary: String,
                        val result: String? = null, val state: ToolState = ToolState.RUNNING,
                        val verb: String? = null, val target: String? = null, override val atEpochMs: Long? = null) : ChatItem
    data class Question(override val id: String, val prompt: String, val options: List<ChatOption>,
                        val approval: Boolean = false, override val atEpochMs: Long? = null) : ChatItem
    data class TurnMarker(override val id: String, val label: String, override val atEpochMs: Long? = null) : ChatItem
}
data class TurnActivity(val running: Boolean, val startedAtEpochMs: Long?, val lastStep: String?)
data class TranscriptBinding(val kind: AgentKind, val sessionId: String, val path: String,
                             val processKey: String, val machine: String? = null, val confirmed: Boolean = false)
data class TranscriptCandidate(val kind: AgentKind, val sessionId: String, val path: String,
                               val processKey: String, val machine: String? = null)
sealed interface TranscriptState {
    data object Loading : TranscriptState
    data class Ready(val binding: TranscriptBinding, val complete: Boolean) : TranscriptState
    data class Unavailable(val reason: String, val candidates: List<TranscriptCandidate> = emptyList()) : TranscriptState
    data class Failed(val error: HcError) : TranscriptState
}
class TranscriptSource(client: HcClient, terminalId: String, scope: CoroutineScope,
                       pollIntervalMs: Long = 1500) : java.io.Closeable {
    val state: StateFlow<TranscriptState>
    val items: StateFlow<List<ChatItem>>
    val activity: StateFlow<TurnActivity?>
    fun nudge()
    suspend fun refresh()
    suspend fun candidates(): List<TranscriptCandidate>
    suspend fun preview(candidate: TranscriptCandidate): List<ChatItem>
    suspend fun select(candidate: TranscriptCandidate) // preview 後のユーザーによる確認が前提
    override fun close()
}
```

Question は blocked 中のみ表示候補とする。keys が空の選択肢は未検証のため自動入力しない（手動キーパッドを使う）。
Source は送信しない。Composer は PLAN の terminal.send / terminal.keys を使う。

4c も読み取り専用。package io.herdrmobile.core.files の戻り値を用いる。

```kotlin
data class FileChunk(val text: String, val first: Long, val last: Long, val lines: Long)
data class FileEntry(val name: String, val path: String, val directory: Boolean, val symlink: Boolean = false)
data class ChangeEntry(val path: String, val oldPath: String? = null, val indexStatus: String,
                       val worktreeStatus: String, val untracked: Boolean = false)
data class ChangeSet(val cwd: String, val entries: List<ChangeEntry>, val diff: String,
                     val stagedDiff: String, val truncated: Boolean = false)
// HcClient のメンバー:
suspend fun changes(cwd: String, on: String? = null): ChangeSet
suspend fun listFiles(cwd: String, path: String = ".", on: String? = null): List<FileEntry>
suspend fun readFile(path: String, cwd: String? = null, offset: Long = 1,
                     limit: Int = 2000, on: String? = null): FileChunk
```

## Phase 5: ライブなチャット ＋ 全画面活用の Material 3 Expressive UI

### 背景（ユーザー指摘と調査結果）
- 「チャットは回答が完全に終わらないと表示されない」。
- herdr-app のチャット風 UI は herdrdev/herdr-layout-examples の `chat`（Apache-2.0）。
  - これは会話ログを読まない。agent を「チームメイト」として扱い、feed に状態文を一行ずつ並べる（Working on tab / Needs you in tab / Finished tab / Standing by in tab）。
  - thread 欄にはその tab のライブのターミナルを並べる。
  - `threads` レイアウトも同様で、ライブのターミナルの下に composer を置く。
- 会話ログは「ブロック単位」でしか追記されない。Claude Code は text/tool_use ごと、Codex は item ごと。トークン単位のストリーミングは原理的に不可能（Heeler も同じ）。
  - したがってライブ感は「herdr-app と同じくライブのターミナルを見せる」ことで補う。
- 既知の不具合：`TranscriptSource.readAppend` は 1 回のポーリングで 2000 行しか読まない。
  - 長い会話ログでは最新に追いつくまで何周も（1.5 秒 × N）かかる。
  - その間 `complete=false` のままになり、表示が遅れる。

### codex-a（core-client）
1. **追いつき読み**：1 回の update で末尾まで読み切る（チャンクを繰り返す）。
   - 初回は Heeler と同様に「末尾 1 MiB 相当」から開始してもよい。その場合、ターンの途中から始まる扱いを正しくする。
   - 上限 32 MiB / 100k 行は維持。
2. **適応ポーリング**（Heeler の間隔に合わせる）：
   - Terminal の status が working/blocked のとき、または変化・送信・status 変化から 15 秒間は 1 秒間隔。
   - それ以外は 4 秒間隔。
   - `fun nudge()`（送信直後に呼ぶ。即時読み＋ 15 秒の高速ウィンドウ）を公開する。
3. 新しい行が無いポーリングでは再プロジェクションしない（items を再発行しない）。
4. **進行中ターン情報**を公開する：
   - `val activity: StateFlow<TurnActivity?>`
   - `data class TurnActivity(val running: Boolean, val startedAtEpochMs: Long?, val lastStep: String?)`
   - 開始時刻は記録の `timestamp`（ホスト時計）。
   - `lastStep` は最後の tool 名や「Thinking」などの短い文字列。
5. **テスト**：
   - フェイククライアントで行を 1 行ずつ追記していき、各ポーリングで items が逐次増えることを確認する（Claude・Codex 両方）。
   - 3000 行以上の会話ログを 1 回の update で読み切ることも確認する。

### Phase 5 公開 API 補足（codex-a）
- Phase 4 API 節の ChatItem 全型に atEpochMs: Long? = null を末尾追加（ホスト記録の timestamp、欠落・不正値は null）。
- ToolCall は verb: String? = null / target: String? = null も追加。完全一致の既知 ID のみを意味付けし、未知 ID は両方 null。
- TranscriptSource.activity: StateFlow<TurnActivity?> と fun nudge() を追加。nudge は読みを即時予約し、15 秒の高速ウィンドウを延長する。
- 適応間隔は working/blocked または変化後15秒に1秒、それ以外4秒。既存 pollIntervalMs 引数は互換のため維持し、1000未満の指定では検証用に間隔を短縮できる。
- TurnActivity.startedAtEpochMs と ChatItem.atEpochMs は端末ローカル時計で補完しない。activity は新規行がない場合でも terminal status に追従する。

### codex-b（app / Gradle / CI）
- **依存**：
  - Compose BOM を Material3 Expressive API（`MaterialExpressiveTheme`、`MotionScheme.expressive()`、`ButtonGroup`/`ToggleButton`、`LoadingIndicator`、`FlexibleTopAppBar`、`ShortNavigationBar`/`WideNavigationRail`、`FloatingToolbar` など）を含む版に上げる。
    - 原則は安定版の最新。alpha が必須なら版を明記して報告する。
  - compileSdk 36 が必要なら `~/Android/Sdk` に `platforms;android-36` と `build-tools;36.0.0` を sdkmanager で入れる（ユーザー承認済みの SDK 範囲内、sudo 不要）。
    - CI の setup-android の `packages` も同じく更新する。
  - Kotlin/AGP の更新が必要なら最小限にとどめる。
- **テーマ**：`MaterialExpressiveTheme`（atom-one-dark の配色は維持）、expressive の motionScheme と shapes を使う。edge-to-edge。
- **エージェント詳細（全画面を使う）**：
  - 上部：独立した行（戻る行・エラー行・セグメント行）を廃止する。
    - 1 本のコンパクトなトップバーにまとめる：戻る、agent 名＋状態、Terminal/Chat の connected `ButtonGroup`（アイコンのみ可）、overflow。
    - Chat ではスクロールで隠れる（enterAlways）。
    - エラー・再接続は必要なときだけ細いバナーで出す。
  - 下部：
    - composer は丸いピル型の入力欄＋送信ボタンにする（threads の composer 風）。
    - キー行（Esc/Tab/Ctrl/矢印/^C/Enter）は Terminal 表示時のみ出す。小さい `HorizontalFloatingToolbar` かコンパクトな行にする。
    - Chat 表示では隠す。
  - Chat 本文：
    - 横の余白は 12dp 程度。assistant のテキストは全幅（カードにしない）。user は最大幅 85% のバブル。
    - tool 呼び出しは 1 行のコンパクトな行にする。連続する tool はまとめて折りたたむ（「n 件のツール」）。
    - 完了済みターンの途中経過は折りたたみ、プロンプトと最終回答だけを常に表示する（Heeler 方式）。
  - **ライブ活動**（herdr-app chat の「live terminals」をモバイル化）：
    - agent が working のとき、タイムライン末尾に「Working · m:ss」と最後のステップを出す。`TurnActivity` と expressive の `LoadingIndicator` を使う。
    - その下に、既存の observe attach（ObservePreview 相当、読み取り専用）でターミナル下端の数行をライブ表示する。
    - タップで Terminal に切り替える。blocked のときは Approval/Question カードを優先する。
  - 送信後に `transcript.nudge()` を呼ぶ。
- **Agents/Spaces/Hosts**：
  - 背の高いカードをやめ、密度の高いリスト行にする。
    - 行の中身：プログラムアイコン、タイトル、状態文、ホスト。状態文は herdr-app chat 風（「〜で作業中」「対応が必要」など、en/ja）。
    - グループは expressive の角丸セグメントリストにする。
  - タイトルは折りたたまれる大きなトップバー（`LargeFlexibleTopAppBar` 等）。
  - ナビは `ShortNavigationBar`。幅 600dp 以上では `WideNavigationRail` に切り替える（折りたたみ端末やタブレット）。
  - FAB は expressive の FAB（または FAB メニュー）にする。
- 既存のテスト・lint を通す。必要なら UI ロジックの単体テストを追加する（ツールのグルーピングとターンの折りたたみ）。

### Phase 5 追補：Ovis（devswha/ovis, MIT）の考え方を取り入れる
参考にするのは考え方だけで、コードはコピーしない。読むなら次の 2 か所。
- `scratchpad/ref/ovis/DESIGN.md` の「Chat turn」と「Work block」節
- `docs/guide.md`

**codex-a（core-client）**
- `ChatItem.ToolCall` に `verb: String?` と `target: String?` を追加する（デフォルト null、後方互換）。
  - verb はツール ID の完全一致で決める。
    - Claude: Bash→Ran（command）、Read→Read（file_path）、Edit/MultiEdit→Edited、Write→Wrote、Grep/Glob→Searched（pattern）、WebFetch→Fetched（url）
    - Codex: exec_command/shell/local_shell_call→Ran（cmd）、apply_patch→Edited（パッチ内の最初のファイル＋件数）
  - 未知のツールは null にする（UI はツール名を出す）。
- `UserMessage`、`AssistantText`、`ToolCall`、`TurnMarker` に `atEpochMs: Long? = null`（記録の timestamp）を追加する。UI が「Worked for 7s」を計算するのに使う。
- 余力があれば、パースを差分化する（新しい行だけを状態に畳み込む）。Ovis では全体を再パースした場合に比べて約 21 倍速かった。結果は全体再パースと一致させ、テストで同値性を確認すること。

**codex-b（app）**
- 表示の作法（Ovis 準拠）
  - user は右寄せの控えめなカード（最大幅 80%、アバターや名前は付けない）。assistant は見出しなしの地の文（Markdown）。
  - 「Turn started / Turn complete」の区切り線は出さない。やりとりの間は余白で区切る。compaction と「履歴なし」の目印だけは残す。
- **Work block**：ターン内の連続したツール群を 1 ブロックにまとめる。
  - ヘッダーは `▸ Worked for 7s · 2 edits · 3 commands` の形にする。
  - 各行は caret＋動詞＋対象（`▸ Ran ./gradlew test`、`▸ Edited app/…/Chat.kt`）。対象は等幅フォント。失敗は赤字で「failed」を付ける。
  - 行をタップすると入力と出力を展開する。
  - 合間の地の文（ナレーション）は、走行中は通常の文、終わったら控えめな小さめの文にする。
  - 終わったターンは work をヘッダー 1 行に畳む。最終回答は畳みの外に常に出す。
- **走行中のヘッダー**
  - working のときは、ドットが点滅する「Working…」に経過時間を添える（TurnActivity）。blocked のときは止まった黄色のドットで「対応が必要」。
  - ちらつき対策を入れる。回答の直後に status が遅れて変わるので、新しいヘッダーを出すのは idle を確認してから 800ms 待ってから。逆に回答が読めるまでは最大 2 秒、ターンを走行中のまま保つ。
- **送信の結果表示**：送ったメッセージのバブルの下に、控えめな 1 行を出す。
  - 状態は「送信済み」「回答待ち（Approval/Question カードへ飛ぶ）」「会話ログで確認」「未配信（入力欄に文字が残っている場合）」の 4 つ。
  - 根拠がなければ「完了」とは言わない。
- **ターミナルの覗き見**：作業中のライブ表示をタップしたとき、またはメニューから開いたとき、チャットの上にボトムシートでライブのターミナルを出す（Ovis の terminal peek）。全画面の Terminal 切替とは別にする。

## Phase 6: Space と Agents の出し方を herdr-app に合わせる

### 背景（ユーザー指摘 2026-11-09）
「モバイルから見えるスペースがホストの herdr-app からは見えない」。原因は、モバイルだけが `herdr.space` の無い端末から cwd ごとの派生 Space（`derived:<cwd>`）を作っていたこと。
ユーザー決定：**基本的に herdr-app の挙動に合わせる**。

herdr-app の挙動（`ref/herdr-app/src/window.rs` の `reconcile` / `adoptable` / `adopt` / `sync` / `world`）：
- Space は document `herdr-app` にある Space だけ。それに加えて、document に載っていない live な端末のうち **`herdr.space` が空でないもの** だけを取り込む（adopt）。
  - 取り込むのは、その machine の document を読めたときだけ（doc.get が成功した。値が null でもよい）。読めなかった machine の端末は取り込まない。
  - `herdr.tab` があればその Tab に入れる。無ければ端末ごとに新しい Tab。知らない Space/Tab はラベルの ID で名前なしに作る（Space 名は folder の basename。folder は foreground の cwd → cwd の順）。
- `herdr.space` が無い・空の端末（park した端末、`hc run` で起動した端末など）は **Space に出さない**。
- サブエージェント（`parent` を持ち、`herdr.space` が無い端末）は自分の Pane を持たない。親の Pane に属する（`world()` の `sub_agents`）。`herdr.space` を持つサブエージェントは「kept」として普通に取り込む。
- Agents 一覧（`agents()`）は **Space の Pane にいるエージェントだけ**。

### Phase 6 公開 API 変更（codex-a）
```kotlin
// 変更: derived を削除、subAgents を追加
data class Pane(val id: String, val terminal: Terminal, val agent: AgentInfo? = null,
                val subAgents: List<Pane> = emptyList())   // 親チェーンをたどってこの Pane に属する、herdr.space の無い子孫（作成順、平坦）
data class Space(val id: String, val name: String, val cwd: String, val tabs: List<Tab>,
                 val machine: String? = null)
```
- `projectWorkspace` は上の規則に従う。`derived:` の Space は作らない。
- 親が Space に無い（親も未配置）サブエージェントは出さない。親チェーンは最大 32 段、循環しても止まること。
- doc.get が失敗した machine では、ラベルによる取り込みをしない（document 由来のものも無いので、その machine の Space は空）。`error` は従来どおり出す。
- document の pane にある端末は、`parent` があって `herdr.space` が無くても、その pane に出す（document が優先。herdr-app も model の pane をそのまま出す）。サブエージェント扱いにするのは document に載っていない端末だけ。
- **再接続中**（Phase 3 の stale 表示）：切断しても直前の documents を捨てない。接続が無いあいだは、TerminalStore の `staleTerminals` と直前の documents から投影した spaces を出し続ける。新しい snapshot が来たら通常の投影に戻す。doc.get の成否は直前のものを使う。
- WorkspaceTest を更新：派生 Space が出ないこと、park（`herdr.space=""`）が出ないこと、サブエージェントが親の `subAgents` に入ること、kept サブエージェントが独立 Pane になること、doc.get 失敗 machine の取り込み抑止。

### Phase 6 app 変更（codex-b）
- Spaces 画面：`Space.derived` の参照があれば消す。Pane の `subAgents` は親 Pane の下に小さくまとめて出し、タップでその端末を開ける（attach / Chat はこれまでどおり）。
- Agents（Console）画面：全端末からではなく、**WorkspaceStore の Space の Pane にいるエージェント** から作る（herdr-app の `agents()` と同じ）。サブエージェントは独立した行にせず、親の行の下にぶら下げる（件数と状態だけでもよい）。Blocked / Working / Done / Idle のグループ分けはそのまま。
- 再接続中：WorkspaceStore は stale な spaces を出し続ける（codex-a）。Agents の行は、その端末が host の `staleTerminals` にあれば `stale=true` にする（Phase 3 と同じく薄く表示・無効化、グループの末尾）。stale な行は通知の遷移判定に使わない。
- 通知（foreground 通知）の対象も Agents 一覧と同じ集合にする。サブエージェントは親の場所（Space / Tab）で知らせる。
- codex-a の API が入るまでは、`Pane.subAgents` を Fake で使って進めてよい。

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
