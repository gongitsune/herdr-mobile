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

- リポジトリ: `gongitsune/herdr-mobile`（現在は public）。作成・初回 push・Secrets 登録は Claude が Phase 2 レビュー後に行う。
- `.github/workflows/android.yml`（codex-b 担当）:
  - トリガ: push（main）, pull_request, workflow_dispatch。`ubuntu-latest`、`actions/setup-java`（temurin 21）、`android-actions/setup-android` または runner 同梱 SDK、`gradle/actions/setup-gradle`。checkout は `fetch-depth: 0`。
  - `./gradlew :core-client:test :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest`（結合テストは CI では実行しない。HC_IT 未設定でスキップされること）。
  - APK を `actions/upload-artifact` で保存（名前に short SHA）。
  - main への push 時は `nightly` pre-release を作り直して `herdr-mobile-<versionName>-<versionCode>-<sha>.apk` を添付する（`gh release`、`permissions: contents: write`）。
- `.github/workflows/release-please.yml` が main への push でリリース PR・タグ・GitHub Release を作り、`workflow_dispatch` で `release.yml` を起動する。`release.yml` は指定タグを full checkout し、APK をビルド・テストして `herdr-mobile-<tag>.apk` を添付する（Phase 14 追補）。
- 署名: 上書きインストールできるよう固定の debug keystore を使う。
  - `app/build.gradle.kts` の debug signingConfig は環境変数 `HERDR_DEBUG_KEYSTORE`（パス）, `HERDR_DEBUG_KEYSTORE_PASSWORD`, `HERDR_DEBUG_KEY_ALIAS`, `HERDR_DEBUG_KEY_PASSWORD` があればそれを使い、無ければ Android 既定の debug 鍵。
  - CI では Secret `DEBUG_KEYSTORE_BASE64` などをデコードして上記環境変数に渡す。Secret が無い（fork PR 等）場合は既定鍵でビルドを続行。
  - keystore はリポジトリに入れない。ローカルの正本は `~/.config/herdr-mobile/debug.keystore`。
- versionCode は CI・ローカルとも `git rev-list --count HEAD`（main のコミット数）。`HERDR_VERSION_CODE` があれば優先し、git が使えなければ 1。`CI=true` の shallow clone は `fetch-depth: 0` を案内して失敗させる。
- ローカル用の `GITHUB_RUN_NUMBER>=1000` は不要。1000 以上の versionCode が入っている端末は一度アンインストールが必要。

## Phase 4: herdr-app 風 UI ＋ ターミナル ⇔ チャット切替

ユーザー決定（2026-11-08）: アプリ全体を herdr-app の構成に合わせてモバイル向けにする。さらに各エージェントで「ターミナル ⇔ チャット」を切り替えられるようにする。チャットのデータ源は **エージェント自身が書き出す会話ログ**（ACP は使わない）。

参考資料（scratchpad の `ref/` にクローン済み、どちらも Apache-2.0）:
- `ref/herdr-app`: `README.md`, `defaults/mods/{agents,spaces,tabs,changes,files,peek,prompt,workspace-status}/main.js`, `defaults/themes/atom-one-dark.json`, `defaults/layouts/main.json`, `docs/labels.md`, `src/layout_sync.rs`
- `ref/Heeler/docs/adr/0024`（会話ログからのチャット）, `0032`（herdr-core を herdr-app の構成で見せる）, `0034`（チャットとセッションの対応付け）, `0035`（送信を attach 経由で行う）, `docs/research/claude-code-transcript-format.md`, `codex-rollout-format.md`

### 構成モデル（読み取り専用）
- Space / Tab / Pane は、core の document `herdr-app`（`doc.get`、`doc` イベントで読み直す）と端末ラベル `herdr.*` から組み立てる。`docs/labels.md` の規約に従う。
- **`herdr-app` document には絶対に書き込まない**（デスクトップの配置が壊れる。Heeler ADR 0032）。例外は Phase 8 追補の「Space とタブの名前変更」だけ。新しい端末を Space に入れるときは、herdr-app の規約どおりのラベルを付けて取り込ませる。
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
data class Tab(val id: String, val name: String, val panes: List<Pane>, val explicitName: String = "") // Phase 8
data class Space(val id: String, val name: String, val cwd: String, val tabs: List<Tab>,
                 val machine: String? = null, val explicitName: String = "") // Phase 6: derived 削除 / Phase 8: explicitName
class WorkspaceStore(client: HcClient, scope: CoroutineScope) : java.io.Closeable {
    val spaces: StateFlow<List<Space>>
    val error: StateFlow<HcError?>
    suspend fun refresh()
    fun canRename(spaceId: String): Boolean                              // Phase 8 追補
    suspend fun renameSpace(spaceId: String, name: String)               // Phase 8 追補（document の name のみ書く）
    suspend fun renameTab(spaceId: String, tabId: String, name: String)  // Phase 8 追補
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
suspend fun gitBranch(cwd: String, on: String? = null): String?
suspend fun changes(cwd: String, on: String? = null): ChangeSet
suspend fun listFiles(cwd: String, path: String = ".", on: String? = null): List<FileEntry>
suspend fun readFile(path: String, cwd: String? = null, offset: Long = 1,
                     limit: Int = 2000, on: String? = null): FileChunk
```
`changes` は git status が「not a git repository」で失敗したとき `HcError(kind = "not_repository")` を投げる（それ以外の失敗は従来どおり）。

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

- **タブ名**（実機確認で発覚 2026-11-09）：名前の無いタブ（document の `name` が空、またはラベルで取り込んだタブ）は ID ではなく、herdr-app の `Model::tab_name` と同じく **Space 内の 1 始まりの位置**（"1", "2", …）を名前にする。projection の最後、Space のタブ順が確定してから付ける。

### Phase 6 app 変更（codex-b）
- Spaces 画面：`Space.derived` の参照があれば消す。Pane の `subAgents` は親 Pane の下に小さくまとめて出し、タップでその端末を開ける（attach / Chat はこれまでどおり）。
- Agents（Console）画面：全端末からではなく、**WorkspaceStore の Space の Pane にいるエージェント** から作る（herdr-app の `agents()` と同じ）。サブエージェントは独立した行にせず、親の行の下にぶら下げる（件数と状態だけでもよい）。Blocked / Working / Done / Idle のグループ分けはそのまま。
- 再接続中：WorkspaceStore は stale な spaces を出し続ける（codex-a）。Agents の行は、その端末が host の `staleTerminals` にあれば `stale=true` にする（Phase 3 と同じく薄く表示・無効化、グループの末尾）。stale な行は通知の遷移判定に使わない。
- 通知（foreground 通知）の対象も Agents 一覧と同じ集合にする。サブエージェントは親の場所（Space / Tab）で知らせる。
- codex-a の API が入るまでは、`Pane.subAgents` を Fake で使って進めてよい。

## Phase 7: Ovis モバイル版に倣ったシンプルな UI

### 背景（ユーザー指摘 2026-11-09）
「チャットに『会話ログで確認』『送信済み』などの補足が出ているが要らない。シンプルで動線に迷いにくい UI にする。https://ovis.sh のモバイル版を参考に」。
参考スクリーンショット（ovis.sh/assets/demo/*-phone-light.webp）を Claude が確認済み。考え方だけ取り入れ、コードや画像はコピーしない。

### 方針：画面は「エージェント 1 枚」＋「左ドロワー」だけ
- **下部ナビ（Agents / Spaces / Hosts）と NavigationRail を廃止する。** アプリのメイン画面は常に 1 つのエージェント画面。
- 起動時は最後に開いたエージェントを開く。無ければドロワーを開いた状態にし、本文には「左のメニューからエージェントを選ぶ」だけを出す。
- **左ドロワー**（☰ で開く。幅 600dp 以上では常時表示）：
  - ホストごとの見出し：PC アイコン、ホスト名、接続の色ドット、右に折りたたみと「＋」（ホスト追加）。
  - その下に Space の行：フォルダアイコン、Space 名、2 行目にブランチ（あれば）。
  - Space の下に、ツリーの罫線つきでその Space のエージェント（Pane のエージェント）：プログラムアイコン、名前、ブランチやタブ名を薄く、右端に状態の印。
    - working＝青ドット、blocked＝赤の「?」、done（未読）＝ドット、idle＝なし。
  - サブエージェントはドロワーに出さない（親を開いてから辿る）。
  - 下端：「Agents n」の折りたたみ（全エージェントの平たい一覧。Blocked が先頭）と、設定（歯車）。ホストの編集・削除・鍵は設定の中に入れる。
  - 行をタップすると、そのエージェントを開いてドロワーを閉じる。今開いているものを強調表示する。
- Phase 6 の「Space / Agents の出し方」（herdr-app と同じ集合）はそのまま使う。

### エージェント画面
- **トップバー**（1 段、スクロールで隠さない）：☰ ／ プログラムアイコン＋タイトル（1 行、省略）／ Chat・Terminal のアイコンだけのセグメント ／ ⋯。
  - タイトルの下の状態行（Working… など）は消す。状態はチャット内とタブのドットで分かる。
- **タブ行**：トップバーの下に、今のエージェントがいる Space のタブを横スクロールで出す。各タブに状態ドットと名前。選択中は下線。タブをタップすると、そのタブの最初のエージェント Pane を開く（エージェントが無ければシェルの端末を開く）。
- ⋯ メニューは「中断（Ctrl-C 相当）」「終了（確認あり）」「閲覧専用 ⇔ 操作」だけにする。「ライブターミナル」「テキストを読む」は削除。

### チャット
- **削除するもの**：
  - 送信のレシート（「送信済み」「回答待ち」「会話ログで確認」「未配信」）と `SendReceipts` 一式。
  - チャット内のライブターミナル表示（`LiveActivity` の observe プレビュー）と Peek ボトムシート。ターミナルはトップバーのセグメント 1 タップで見る。
  - 「Turn started / complete」系の区切り（残っていれば）。
- **残すもの**（Ovis と同じ形）：
  - user は右寄せの控えめな角丸カード。assistant は地の文の Markdown（コードブロックは言語名とコピーボタン付き）。
  - Work block のヘッダー：`⌄ ● Working… · 1 edit · 1 file read`。blocked は赤ドットで `Needs you`。終わったら `Worked for 7s · …`。行は `› Read src/metrics.ts` 形式（動詞は通常、対象は等幅）。
- **会話ログが見つからないとき**：説明文やボタンを並べない。チャットの代わりにターミナルを出し、セグメントの Chat は押せるが「会話ログが見つかりません」と 1 行と「候補から選ぶ」ボタン 1 つだけにする。
- **質問・承認カード**：タイムラインの中ではなく、**入力欄のすぐ上に固定**して出す。
  - 見出し（「コマンドを許可しますか？」などを赤字）、折りたたみ ⌄、対象（コマンドなど）を等幅の枠で、選択肢は番号付き（①Yes ②… ）。タップで選ぶ。
  - カードが出ている間、入力欄のプレースホルダーは「1–n を入力して選択…」にし、数字を送るとその選択肢を選ぶ。
- **入力欄**：角丸の大きなカード 1 つ。プレースホルダーは「<エージェント名> にメッセージ…」。右下の丸ボタンは、送信（↑）／working 中で入力が空なら停止（■＝中断）。
  - 送信後の補足表示は出さない。送ったメッセージは会話ログに現れた時点で表示される（それまでは user カードを薄く仮表示してよいが、文言は付けない）。

### ターミナル
- 入力欄は出さない。ターミナルをタップするとソフトキーボードで直接打つ。
- 下端にキーバー：`Esc` `Tab` `^C` `Ctrl` `Alt` `Shift` `Enter` と、右端の `^` で矢印キーなどを展開。

### 担当
- すべて codex-b（app）。core-client の変更は不要。必要になったら PLAN.md に書いてから codex-a に依頼する。
- 不要になったコード・文字列リソース・テストは削除する。既存テスト・lint を通す。

### Phase 7 レビュー修正（Claude レビュー 2026-11-09）
**codex-a（core-client）**
- `HcClient` に `suspend fun gitBranch(cwd: String, on: String? = null): String?` を追加する（`git --no-optional-locks symbolic-ref --short HEAD`、timeout 3 秒、リポジトリでない・detached・失敗時は null）。app から生の `machine.exec` を呼ばないようにするため。

**codex-b（app）**
- 指示していない機能の削除を戻す（シンプルさは「入口を減らす」で実現し、機能は消さない）：
  - **Changes / Files**：ドロワーの Space 行の右端「⋯」から、以前のボトムシート（Changes / Files）を開く。`git show HEAD:` から復元し、新しい構成に結線する。テストも戻す。
  - **新しい端末**：タブ行右端の「＋」で、今の Space に新しいタブとして端末を作る。ボトムシートで「シェル / Claude Code / Codex / コマンド入力」を選ぶ。cwd は Space の folder。ラベルは herdr-app の規約どおり `herdr.space=<Space のローカル ID>`、`herdr.tab=<新しい tb ID>`、`herdr.seen=0`（デスクトップが取り込む）。作ったらその端末を開く。
  - **Demo**：入口が無いので、Demo 関連の死にコードと文字列を削除する。
- 正しさの修正：
  1. 保存した最後のエージェントが存在しない（接続済みで terminals の初回ロード後に見つからない）ときは、選択を消してドロワーを開く。「復旧中」のままにしない。
  2. 停止ボタン（■）は `blocked` でなく、質問カードも出ていないときだけにする。承認待ちに Ctrl-C を送らない。
  3. 送信直後 1 秒は停止に切り替えない（ダブルタップで中断しない）。
  4. 番号入力は全角数字も受け付ける（NFKC か `Character.digit`）。数字でない入力や範囲外の番号は捨てずに、入力欄の下に短く「1–n の番号を入力」と出す（入力は残す）。keys の無い選択肢は押せない理由が分かる見た目にする（薄く表示）。
  5. Ctrl / Alt / Shift は次の 1 キーで自動解除（Termux の extra keys と同じ）。ラベルは文字列リソースにする。
  6. 戻るボタン：設定・ホスト編集・ボトムシートは閉じるだけにする。メイン画面でドロワーが開いていれば閉じる。
  7. 幅 600dp 以上（ドロワー常時表示）では ☰ を出さない。
  8. 開いたエージェントを切り替えたら古い TerminalViewModel を破棄する（ViewModelStore に溜めない）。
  9. 送信・選択中も入力欄を disabled にしない（キーボードが閉じるため）。二重送信は送信ボタン側だけで防ぐ。
- 見た目：
  - 質問カードの見出しは汎用文言ではなく `Question.prompt` を出す（approval は赤字）。
  - ドロワーのツリーは最後の子を `└` にする。
  - 会話ログの読み込み中は「メッセージなし」ではなく、何も出さないか小さなローディングにする。
  - ブランチは `HcClient.gitBranch` を使う。取得はドロワーを開いたときと Space 一覧が変わったときだけにする（30 秒ごとのポーリングはやめる）。codex-a の API が入るまでは今の実装のままでよい。

### Phase 7 レビュー修正 2（Claude レビュー 2026-11-09）
codex-b（app）:
1. 新規端末の起動を herdr-app に合わせる：エージェントは `unix_agent_argv` と同じ `sh -ic '"$@"; exec "$0" -i' <shell> <agent argv…>` 形式（ログインシェルの PATH で探し、終了後はシェルが残る）、`keep=true`、`env TERM=xterm-256color`。シェルはそのまま `$SHELL -i` 相当。参照：ref/herdr-app/src/window.rs の `unix_agent_argv`（111 行付近）と hc.rs の `run`（438 行付近）。
2. 新しいタブ ID は herdr-app と同じ `tb` + 16 進 32 桁。
3. 質問カードの等幅枠は、`prompt` の重複表示になるので消す。
4. 送信後 1 秒のクールダウンは送信 RPC 完了（finally）から数える。
5. keys の無い選択肢の番号を打ったときは「この選択肢はターミナルで選んでください」と出す（範囲外とは別の文言）。
6. 設定を開いても今のエージェントの ViewModel を破棄しない（下書きと attach を保つ）。
7. 作成中のボトムシートは `sheetState.confirmValueChange` で閉じられないようにする。
8. ドロワーのツリーで最後に実際に表示される行を `└` にする。
9. リモート machine で `terminal.run` が返す address と terminals 一覧の ID 形式（`mac/tN`）が一致するか、`HC_SESSION=herdr-mobile-it` のテストか既存の結合テストの知見で確認し、ずれるなら正規化する（default セッションは使わない）。
   - 確認結果（codex-b）: 参照 core の `crates/herdr-core/src/remote.rs` では `forward_once` が run 応答を `localize` し、一覧用の mirror 取り込みも同じ `localize_in` で address を修飾する。既存 `crates/herdr-core/tests/remote.rs` の `two_machines_that_added_each_other_list_at_once` は一覧の `rohan/tN` を検証し、リモート起動テストも run 応答の `rohan/` prefix を検証する。app の `Terminal.id` は address そのものなので追加の正規化は不要。core の起動・操作はしていない。

### Phase 7 実機確認の修正（2026-11-09）
- **codex-a**：`changes(cwd)` は、cwd が git リポジトリでないとき（`git status` が「not a git repository」で失敗）に `HcError(kind = "not_repository")` を投げる。それ以外の失敗は今までどおり。テストを追加。
- **codex-b**：
  1. Changes タブで `not_repository` のときは、エラーの赤字ではなく、中央に控えめな「Git リポジトリではありません」を出す（Files タブはそのまま使える）。codex-a が入るまでは、kind を見る分岐だけ先に作ってよい。
  2. 「＋」の新規端末シートは、縦に並んだチップをやめ、アイコン付きの 1 行リスト（シェル / Claude Code / Codex / コマンド…）にする。行をタップしたらすぐ作成する（「コマンド…」だけは入力欄を出してから作成）。「作成」ボタンは不要。作成中はその行にローディングを出す。
  3. ドロワーのツリー線：子が 1 つでも `└` が見えるようにする（今は表示されていない。罫線の色・太さ・位置を見直す）。

### Phase 7 エミュレータ確認の修正（2026-11-09）
- codex-b：ドロワーの **Space 行（名前の部分）をタップしたら、その Space を開く**（herdr-app でスペースをクリックしたときと同じ）。開くのは、最初のタブの最初のエージェント Pane。エージェントが無ければ最初の Pane（シェル）。Pane が 1 つも無い Space では、その Space を選んだ状態にして本文に「＋ で端末を作成」と出し、タブ行の「＋」を使えるようにする。今はシェルだけの Space をタップしても何も起きず、行き止まりになっている。

## Phase 8: タブと Space の操作を Ovis のモバイル版に合わせる

### 背景（ユーザー指摘 2026-11-09）
「Ovis のデモ（https://ovis.sh/demo/）か GitHub を見て、タブの操作方法やスペースなどの UI を使いやすくする」。
Claude がエミュレータの Chrome でデモを操作し、ref/ovis の DESIGN.md（Shell 節、Menu 節の phone のタブ、Sidebar roster row）を読んで確認した。要点：
- 電話幅ではタブの行を出さない。ヘッダーのタイトル（エージェントのマーク＋名前＋ `⌄`）全体が 1 つのボタンで、押すと「<Space> のタブ」ボトムシートが開く。
- ドロワーは Space（workspace）の行だけを並べ、各行に `⋯`。エージェントは下端の「Agents」節に別にまとめ、電話では折りたたんだ状態で始める。
- 考え方だけを取り入れ、コードやブランドはコピーしない。**herdr-app の document には書き込まない**ので、document を書き換える操作（タブの移動・名前変更、Space の名前変更・ピン留め）は入れない。

### ヘッダーとタブ（codex-b）
- タブ行（`PrimaryScrollableTabRow` と右端の ＋）を削除する。
- トップバー：☰ ／ **タイトルボタン**（今のタブのエージェントのマーク、なければ端末アイコン＋タブ名またはエージェント名、その後ろに `⌄`、2 行目に小さく `Space 名`）／ Chat・Terminal のセグメント ／ ⋯。
- タイトルボタンで「<Space 名> のタブ」ボトムシートを開く：
  - 各タブの行：状態ドット（working・blocked・done は色付き、それ以外は中抜き）、エージェントのマーク（なければ端末アイコン）、タブ名、右に状態語（作業中／対応が必要／完了。idle は出さない）、`×`（閉じる）。今のタブは背景を強調。行をタップするとそのタブを開く（タブ内の最初のエージェント Pane、なければ最初の Pane）。
  - `×`：確認ダイアログの後、そのタブの端末をすべて `terminal.kill`（サブエージェントは含めない）。開いていたタブなら、同じ Space の別のタブへ移る。なければ Space を選んだ状態にする。
  - 区切り線の後に「新しいターミナル」（シェルを即作成）と「エージェントで新しいタブ…」（Claude Code / Codex / コマンド… を選ぶ。今の NewTerminalSheet の中身）。
  - 一番下に「キャンセル」。
- ⋯ メニュー：中断 ／ 閲覧専用 ⇔ 操作 ／ 変更とファイル（今の Space の Changes / Files シート）／ このタブを閉じる（確認あり）。

### ドロワー（codex-b）
- ホストの見出し：PC アイコン、ホスト名、接続ドット、折りたたみ `⌄`。見出しの「＋」は廃止する（ホストの追加は設定から）。
- **Space の行**：フォルダアイコン、Space 名、2 行目にブランチ（あれば）。右端に状態の印（中の誰かが blocked なら赤い「?」、working なら青いドット、未読の done ならドット）と `⋯`。
  - 行をタップ：その Space を開く（前回その Space で開いていたタブ、なければ最初のエージェント、なければ最初の Pane、なければ空の Space として選ぶ）。
  - `⋯`：ボトムシート。見出しに Space 名と「ブランチ · フォルダ」を小さく出す。項目は「変更とファイル」「新しいターミナル」「エージェントで新しいタブ…」「キャンセル」。
  - **Space の下にエージェントの子行は出さない**（Agents 節にまとめる）。
- ホストの最後に「＋ 新しい Space」の行：ボトムシートでフォルダ（既定はホーム、テキスト入力）と起動するもの（シェル / Claude Code / Codex）を選んで作成。新しい Space ID（herdr-app と同じ `sp` + 16 進 32 桁。window.rs 2428 行付近）と新しいタブ ID を `herdr.space` / `herdr.tab` ラベルに付けて端末を作る（デスクトップの herdr-app が Space として取り込む）。作ったら開く。
- 下端の **Agents 節**：見出しは折りたたみの `›`、「エージェント」、対応待ちがあれば赤字で「n 件の対応待ち」。電話では折りたたみ状態で始め、開閉は端末に保存する。
  - 開くと、blocked・working・未読 done のエージェントを行で出す（マーク、タイトル、薄く Space 名）。それ以外は「+n 件 待機中」の折りたたみ行の中にまとめる。
  - 今開いているエージェントは背景を強調。タップで開く。
- 歯車（設定）はそのまま。

### その他
- 単体テスト：タブの状態の集約、「Space を開く」ときに選ぶ Pane、Agents 節の振り分け、新しい Space のラベル。
- 不要になったコードと文字列（values と values-ja）は削除する。
- default セッションには触らない。動作確認は、エミュレータからテスト用 core `herdr-mobile-it` に接続して Claude が行う。

### Phase 8 追補：名前の変更（ユーザー決定 2026-11-09）
ユーザーの指示で、**Space とタブの名前変更だけ** document `herdr-app` への書き込みを許可する（それ以外の書き込み禁止は変えない）。
根拠：herdr-app 自身が `doc.set` を version 付きの CAS で書き、他のクライアントによる変更は `doc` イベントで読み直して反映する（ref/herdr-app/src/mirror_sync.rs、window.rs `documents_updated` の「Layout changed elsewhere」）。

**codex-a（core-client）**
- `WorkspaceStore` に次を追加する：
  ```kotlin
  suspend fun renameSpace(spaceId: String, name: String)              // spaceId は Space.id（machine 修飾付き）
  suspend fun renameTab(spaceId: String, tabId: String, name: String) // tabId は Tab.id（machine 修飾付き）
  fun canRename(spaceId: String): Boolean                             // その Space が machine の document にあるときだけ true
  ```
- 手順：対象 machine の `doc.get` で値と `version` を取得 → **生の JSON のまま**、該当 Space（`spaces[].id`）またはタブ（`tabs[].id`）の `name` だけを置き換える（ID は machine 修飾を外して照合する。未知のフィールドや順序は一切変えない）→ `doc.set {name:"herdr-app", on, version, value}`。
- `conflict` エラーなら読み直してやり直す（最大 3 回）。
- **document にまだ無い Space／タブ（デスクトップで一度も開いていない、ラベルで取り込んだもの）も名前を変えられる**（ユーザー決定 2026-11-09）。その場合は herdr-app の `adopt`（ref/herdr-app/src/window.rs の `start_adoption` / `adopt`、model.rs の `Tab::new` / `fresh_id`）と同じ形で document に追加してから名前を付ける：
  - 対象の端末に `terminal.keep {keep:true}`（herdr-app と同じ）。
  - Space が無ければ `{id: ラベルの Space ID（machine 修飾なし）, name, folder: foreground の cwd → cwd → ホーム, tabs: [...]}` を末尾に追加。
  - タブ：`herdr.tab` があればその ID、無ければ `tb`+16 進 32 桁。`root` は herdr-app の `Node::container(<タブ ID>, Row, [pane])`、pane は `p`+16 進 32 桁 の ID で terminal（machine 修飾なしのアドレス）を持つ block。既存のタブに入れる場合は herdr-app の `adopt` と同じく最後の pane の横に分割する。
  - 1 回の `doc.set` でまとめて書く（CAS とやり直しは同じ）。
  - JSON の形は herdr-app の serde と完全に一致させる。ref/herdr-app の `mirror::Document` / `layout::Node` の serde 定義を読み、**ref/herdr-app で一時的な Rust テスト（cargo test、コミットしない・終わったら消す）を書いて、モバイルが作る JSON が `Document` として読めることを確認する**。
  - `canRename` は「その machine の document を読めている」なら true（document に無くても追加できるため）。名前は前後の空白を削り、空なら `""`（herdr-app の既定の呼び方に戻る）。
- 書き込み後は `refresh()` で投影を更新する。
- テスト：未知のフィールドがそのまま残ること、名前以外が変わらないこと、conflict でのやり直し、machine 修飾の扱い、document に無いときのエラー。結合テスト（`herdr-mobile-it`）で doc.set → doc.get の往復も確認する。
- PLAN.md の Phase 4 公開 API の WorkspaceStore にこの 3 つを追記する。

**codex-b（app）**（Phase 8 の後で）
- タブのシートで、各タブの行を長押しするか今のタブの「名前を変更」から、ダイアログで名前を変える。Space の `⋯` シートに「名前を変更」を追加する。
- `canRename` が false（その machine の document を読めていない）のときだけ項目を薄く出す。
- エラー時は短いメッセージを出す。

### Phase 8 レビュー修正（Claude レビュー＋エミュレータ確認 2026-11-09）
codex-b（app）:
1. 新しい Space の既定フォルダはホームにする（herdr-app と同じ）。フォルダ欄は空なら、その machine のホームに解決して `cwd` に入れる（`machine.exec` で `$HOME` を取得するなど）。先頭の `~` も展開する。誤った前提のテスト（cwd が null）は直す。
2. タブを閉じる：先に次の行き先（同じ Space の別のタブ、なければ空の Space を選んだ状態）へ移ってから kill する。全端末に kill を試し、`not_found` は成功扱い、エラーはまとめて出す。別のタブを閉じたときはシートを閉じない。
3. タイトル：タブ名が既定の番号（名前が無い）ときは、エージェントのタイトル（なければ端末のタイトル）を大きく出し、番号は出さない。タブのシートの行も同じ。
4. 空の Space の案内は「タイトルの ⌄ から新しいターミナル」のように今の UI に合わせるか、本文に「新しいターミナル」ボタンを置く。
5. 「ワークスペース」の文言は「変更とファイル」にする（Space の ⋯ シート、⋯ メニュー）。Space 行の ⋯ のアクセシビリティラベルは「<Space 名> の操作」。アイコンは横の ⋯（MoreHoriz）。
6. 見た目（Ovis に合わせる）：ドロワーの Space 行は白いカードの塗りをやめ、選択中の行だけ薄い塗り。ボトムシートの項目（Space の ⋯、タブのシートの作成 2 項目）は中央寄せのボタンではなく、アイコン付きで左寄せの 1 行リスト。タブのシートの各行もカードにせず平らな行で、今のタブだけ塗る。
7. タブのシートと Space の ⋯ シートは最新の Space を参照し、Space が消えたら閉じる。
8. 今のタブの行を押したときはシートを閉じるだけにする（見ていた Pane から移らない）。
9. 作成に失敗したら、エラーと一緒にやり直せる状態に戻す。
10. ホストが 0 件のとき、ドロワーに「ホストを追加」の行を出す（設定の追加ダイアログを開く）。
11. 新しい端末の env に herdr-app と同じ `COLORTERM` / `TERM_PROGRAM` を付ける（ref/herdr-app/src/hc.rs の run）。
12. 「Phase 8 追補：名前の変更」の codex-b の項（タブのシートの長押し／今のタブの「名前を変更」、Space の ⋯ の「名前を変更」、`canRename` が false のときだけ薄く）。

### Phase 8 レビュー修正 2（Claude レビュー 2026-11-09）
**公開 API 変更（codex-a が先に入れる）**
```kotlin
data class Tab(val id: String, val name: String, val panes: List<Pane>, val explicitName: String = "")
data class Space(..., val machine: String? = null, val explicitName: String = "")
// name は表示用（空なら番号やフォルダ名から作る）。explicitName は document の生の name（無ければ ""）。
```
**codex-a（core-client）**
1. `Space.explicitName` / `Tab.explicitName` を追加（document の name をそのまま。document に無いものは ""）。
2. `renameSpace` / `renameTab`：前後の空白を削った新しい名前が今の explicitName と同じなら何も書かない（doc.set しない。デスクトップに「Layout changed elsewhere」を出さないため）。
3. `WorkspaceAdoption`：既存タブの root に pane が無いとき、タブ全体ではなく root だけを差し替える（タブの name と未知のフィールドを残す）。`herdr.tab` のタブ ID は herdr-app と同じく全 Space から探す（別の Space にあればそちらに入れる）。
4. テストを追加。PLAN.md の Phase 4 公開 API のデータクラスにも反映。

**codex-b（app）**
1. 名前変更ダイアログの初期値は `explicitName`（空なら空欄で、プレースホルダーに今の表示名を薄く出す）。変更が無ければ書かずに閉じる。数字だけのタブ名を既定とみなす判定（`toIntOrNull`）は `explicitName.isEmpty()` に置き換える（タイトル表示も同じ）。
2. 名前変更のエラーは専用の短い文言にする：`conflict`（「他の画面で変更されました。もう一度お試しください」）、`not_in_layout`、接続なし。
3. `canRename` は Space 一覧の更新に合わせて再評価する（composition 中の `.value` 読みだけにしない）。
4. タブを閉じる：`not_live` など「すでに終わっている」系のエラーも成功扱い。kill 対象は確定時点の最新のタブから取る。
5. シートで pane の無いタブを押したら、空の Space を選んだ状態にする（何も起きないのを避ける）。
6. 新しい Space のホームは、そのホスト（SSH 先）の `$HOME`。herdr-app はデスクトップのホームを使うが、モバイルの接続先はそのホストなのでこれで正しい（意図的な違い）。

## Phase 9: 入力欄の下にモデル切り替え・コンテキスト使用量・セッション使用制限

### 背景（ユーザー指摘 2026-11-09）
「チャット UI の下部でモデル切り替え（Claude Code の Android アプリの UI を参考に）、コンテキスト使用量（%）、セッションの使用制限（%）を表示する」。

### 調査結果（Claude 確認済み、claude 2.1.319 / codex-cli 0.167.0）
- **Claude Code の会話ログ**：`type:"assistant"` の `message.model`（例 `claude-opus-5-5`。`<synthetic>` は無視）と `message.usage`
  （`input_tokens` + `cache_creation_input_tokens` + `cache_read_input_tokens` + `output_tokens` ＝ 今のコンテキストのトークン数）。
  コンテキストの上限は会話ログに無い。
- **Codex の rollout**：`turn_context.payload.model`（例 `gpt-6.1-sol`）。`event_msg` の `payload.type:"token_count"` に
  `info.last_token_usage.total_tokens`、`info.model_context_window`、`rate_limits{limit_id:"codex", primary/secondary{used_percent, window_minutes, resets_at(秒)}, plan_type}`。
  primary が 5 時間とは限らない（このホストでは 10080 分＝週のみ）。
- **Claude Code の使用制限とモデル一覧**（Heeler `plugin/src/usage/claude-limits.js` と同じ方式。サインイン情報は読まない）：
  `claude -p --input-format stream-json --output-format stream-json --verbose --no-session-persistence --strict-mcp-config --settings '{"disableAllHooks":true}'`
  を起動し、stdin に `{"type":"control_request","request_id":"init","request":{"subtype":"initialize"}}` →
  `control_response` の `response.response.models`（`value` / `resolvedModel` / `displayName` / `description`）→
  `{"type":"control_request","request_id":"usage","request":{"subtype":"get_usage","skip_behaviors":true}}` →
  `response.response` の `rate_limits_available` / `subscription_type` / `rate_limits.five_hour|seven_day|seven_day_opus|seven_day_sonnet{utilization(%), resets_at(ISO)}`。
  プロンプトは送らないので使用量は消費しない。終わったらプロセスグループごと SIGKILL。2.1.12 以前は get_usage に応答しないので 15 秒で打ち切る。

### 公開 API（codex-a が先に入れる）
```kotlin
package io.herdrmobile.core.transcript
data class ContextUsage(val usedTokens: Long, val windowTokens: Long, val usedPercent: Int)
data class SessionInfo(val model: String?, val context: ContextUsage?, val limits: io.herdrmobile.core.usage.AgentLimits? = null)
// TranscriptSource に追加
val session: StateFlow<SessionInfo?>

package io.herdrmobile.core.usage
data class LimitWindow(val id: String, val usedPercent: Double, val windowMinutes: Int?, val resetsAtEpochMs: Long?)
data class AgentLimits(val plan: String?, val windows: List<LimitWindow>, val observedAtEpochMs: Long) {
    /** 300 分の窓。無ければ windowMinutes が最小の窓。期限切れ（resetsAt <= now）は除く */
    fun session(nowMs: Long = System.currentTimeMillis()): LimitWindow?
}
data class AgentModel(val value: String, val resolvedModel: String?, val displayName: String, val description: String?)
data class ClaudeAccount(val models: List<AgentModel>, val limits: AgentLimits?, val limitsUnavailable: String? = null)
class ClaudeAccountStore(client: HcClient, scope: CoroutineScope) : java.io.Closeable {
    fun account(on: String?): StateFlow<ClaudeAccount?>   // machine ごと。初回参照で取得
    suspend fun refresh(on: String?, force: Boolean = false) // force でなければ 5 分以内の再取得はしない
    override fun close()
}
```

### codex-a（core-client）
1. `SessionInfo` の投影（`TranscriptSource.session`）。items と同じ読み取りで更新し、値が変わったときだけ発行する。サブエージェント（sidechain）の行は使わない。
   - Claude Code：最後の非 sidechain の assistant 行の model と usage。`windowTokens` は model が `[1m]` を含むか usedTokens が 200,000 を超えたら 1,000,000、それ以外 200,000。`usedPercent = round(used*100/window)`（0–100 に丸める）。`limits` は null（アカウント側で取る）。
   - Codex：最新の `turn_context` の model、最新の `token_count` の info。`usedPercent` は Codex TUI と同じく基準 12,000 トークンを引いて計算：`effective = window-12000`、`used = max(0,total-12000)`、`usedPercent = 100 - round(max(0,effective-used)*100/effective)`（window<=12000 なら 100）。
     `limits` は最新の `token_count.rate_limits`（`limit_id` が null か `"codex"` のものだけ）から作る。`resets_at` は秒→ms。
   - 情報が無ければ各フィールドは null（推測しない）。
2. `usage/ClaudeAccountStore`：上の方式を python3 の resource スクリプト（`discover.py` と同じ置き方）で `machine.exec` する。
   - `claude` はログインシェルの PATH で探す（`sh -lc 'command -v claude'` 相当。herdr-app の起動と同じ見つけ方）。見つからなければ `limitsUnavailable="program_missing"`、models は空。
   - スクリプトは **models と rate_limits の既知フィールドだけ**を JSON で返す（それ以外は持ち出さない）。cwd はホーム。全体のタイムアウト 20 秒。
   - `rate_limits_available=false` は `limitsUnavailable="no_plan_limits"`、get_usage が Unsupported なら `"program_too_old"`（models は返す）。
   - 結果は machine ごとに保持。5 分のスロットル（Heeler と同じ）。失敗しても前回の値を残す。
3. フィクスチャで単体テスト（Claude/Codex の SessionInfo、Codex の 12k 基準、limit_id 違いの除外、session() の選択と期限切れ、スクリプト出力のパース）。結合テストは `herdr-mobile-it` で `ClaudeAccountStore.refresh` が models を返すこと（claude が無い環境では skip）。

### codex-b（app）
**入力欄の下の 1 行**（チャット表示のときだけ。ターミナル表示では出さない）。Claude Code Android アプリの入力欄下部に倣う：
- 左：**モデルのチップ**（小さい tonal、`Opus 5.5 ⌄`）。表示名は ClaudeAccount.models の `resolvedModel` が一致すればその `displayName`、無ければ Ovis の `modelLabel` と同じ規則（`claude-<family>-<major>[-<minor>]` → `Opus 5.5`、`gpt-<ver>[-sol]` → `GPT-<ver>[-Sol]`、それ以外は id のまま等幅）。model が null ならチップに「モデル」。
- 右：**コンテキスト** `◔ 45%`（小さい円形インジケーター＋数字）と **セッション** `⏱ 75%`（同じ形、ラベルは窓の長さ：300 分＝「セッション」、10080 分＝「週」、それ以外は「n 時間」）。80% 以上はステータス blocked の色。値が無い方は出さない。
- 数字を押すと小さなボトムシート：コンテキスト「76k / 200k トークン（45%）」、各制限の窓ごとに「使用 75% · あと 1 時間 12 分でリセット」、プラン名。Claude Code は「更新」で `refresh(force=true)`。
**モデルの切り替え**（チップを押す → ボトムシート「モデル」）：
- Claude Code：ClaudeAccount.models を並べる（太字の displayName、下に薄く description、今のモデルにチェック。今のモデルは `resolvedModel == session.model` で判定、`default` 行より具体名の行を優先）。
  選ぶと `terminal.send("/model <value>")` → Enter（`terminal.keys ["Enter"]`）。送れるのは、操作モードで working でも blocked でもなく質問カードも無いときだけ（それ以外は行を薄くし、「作業中は切り替えられません」を 1 行）。送ったら会話ログの次の model が来るまでチップは選んだ名前を仮表示する。models が空（取得失敗・claude が無い）ならシートに理由を 1 行と「ターミナルで /model」ボタン。
- Codex：Codex の `/model` は引数を取らずピッカーを開くので、シートには今のモデルと「ターミナルで選ぶ」ボタンだけ（押すと `/model`＋Enter を送ってターミナル表示に切り替える。送れる条件は同上）。
- その他のエージェント：チップは出さない。
**取得のタイミング**：Claude Code のエージェント画面を開いたとき、およびモデルシート・使用量シートを開いたときに `ClaudeAccountStore.refresh(machine)`（スロットルは core 側）。画面を開いている間は 5 分ごと。ストアは接続（HostConnections）ごとに 1 つ。
- 文字列は values / values-ja の両方。既存テスト・lint を通す。表示名とラベルの規則・セッション窓の選択は単体テストを書く。

### Phase 9 エミュレータ確認の修正（Claude 2026-11-09）
- 分かったこと：Claude Code の `/model <x>` は「Set model to `Sonnet 5.5` and saved as your default for new sessions」— **ユーザーの既定（~/.claude/settings.json の model）も書き換える**。会話ログには
  `type:"user"` の `<command-name>/model</command-name><command-message>…</command-message><command-args>sonnet</command-args>`、
  `<local-command-stdout>…</local-command-stdout>`、`isMeta:true` の `<local-command-caveat>…` が入る。
- 公開 API 変更：`data class SessionInfo(val model: String?, val context: ContextUsage?, val limits: AgentLimits? = null, val switchedTo: String? = null)`
  （`switchedTo` は最後の assistant 行より後にある最新の「Set model to `X`」の X。ANSI を除く。無ければ null）。

**codex-a**
1. Claude Code の会話ログ投影で、ローカルコマンドを生の XML で出さない：`<command-name>` 行は `ChatItem.UserMessage("/model sonnet")` のようにコマンド名＋引数の 1 行に、`<local-command-stdout>` は ANSI を除いた本文の `ChatItem.TurnMarker`（**id は `local-command:` で始める**。app はこの接頭辞で薄い 1 行の Notice として出す）に、`<local-command-caveat>` と `isMeta:true` の行は出さない。空の stdout は出さない。テストを追加。
2. `SessionInfo.switchedTo`。テストを追加。

**codex-b**
1. チップの表示：pending → `switchedTo`（models の displayName と一致すればそれ）→ 今の model の順。pending は「base が非 null で、会話ログの model が base と違う値になった」か「switchedTo が pending の displayName と一致」か 2 分で消す（base が null のまま最初の model が来ただけでは消さない）。
2. モデルのシートの見出しの下に 1 行「新しいセッションの既定にもなります（Claude Code の仕様）」。
3. TurnMarker が local-command-stdout 由来の行（例「Set model to Sonnet 5.5 …」）を薄い 1 行で出すのを確認。

## Phase 9b: Codex 0.157 以降でもチャットを出す（ユーザー指摘 2026-11-09）

### 原因（Claude 確認、codex-cli 0.167.0）
- 「Codex ではチャット画面が機能しない」。`discover.py` は Codex の証拠として **TUI が開いている `rollout-*.jsonl` の fd** と thread の `.lock` を見るが、
  Codex 0.157 以降は全 TUI が共有の **app-server デーモン**（`$CODEX_HOME/app-server-control/`）を使い、rollout を開くのはデーモン。TUI プロセスには rollout の fd も lock も無い
  → 常に「No conversation log found」。`logs_2.sqlite` の TUI 行にも thread_id は無い。
- 参考：Ovis `server/codex.ts`（`freshReportedThread`、`matchCodexTranscript`、state_5 の threads 読み）。考え方だけ取り入れる。

### 方針（codex-a、core-client のみ。app の変更は不要の見込み）
従来の open-fd 証拠はそのまま残し、それで決まらないときだけ次を使う。どれも読み取り専用。
1. **候補**：`$CODEX_HOME/state_5.sqlite`（`state_*.sqlite` の最大番号。`file:…?mode=ro` で開く、無ければこの経路は使わない）の `threads` から、
   `cwd` = 端末の foreground cwd（realpath で比較）、`archived = 0`、`agent_role IS NULL`、`thread_source` が null か `user`、`source` が対話（`cli` / `vscode`、列が無ければ不問）、
   `updated_at` の新しい順に 32 件。rollout_path は `$CODEX_HOME/sessions` 配下の実在ファイルだけ。rollout 1 行目の session_meta が子スレッド（`parent_thread_id` / `agent_role` / `thread_source != "user"`）なら除く。
2. **画面照合（最優先、Ovis の `matchCodexTranscript` に倣う）**：`HcClient.read(terminalId)` の画面テキストのうち、最後の `OpenAI Codex (v` 見出しより後だけを使う（`/new` や再起動より前の表示を混ぜない）。
   - **長い回答の一致**：候補 rollout 末尾（最大 1 MiB）の assistant の回答（`agent_message` / `response_item` の assistant 出力テキスト）のうち、正規化（NFKC、空白を 1 つ、記号の揺れを無視）して **64 文字以上かつ異なる文字 12 種以上** のものが画面にまるごと出ていて、**他の候補の回答には同じ文が無い** 候補がちょうど 1 つ → exact。
   - **最後のターンの一致（短い回答用）**：画面の最後の「`› <プロンプト>` → `• <回答>` → `Worked for …`」の組（Codex のフッター付きで終わった最新のターン）と、候補の **最後に完了したターンのプロンプトと回答** が一致する候補がちょうど 1 つ、かつ他の候補の最新ターンが同じプロンプトでない → exact。`ok` のような短い回答はこれでだけ決める。
   - 一致が複数・0 なら次へ。照合は Kotlin 側。スクリプトは候補の id/path だけ返し、本文は既存の file.read で読む。
   - 端末タイトルに thread id（またはその先頭）が出ていれば、それも候補に加える（タイトルだけでは決めない。照合の候補にするだけ）。
3. **新しいスレッド**：TUI プロセスの起動時刻以降に作られた（`created_at_ms`、無ければ `created_at*1000`）候補がちょうど 1 つで、その後にこの cwd で新しいスレッドが始まっておらず、
   **そのスレッドの作成時に同じ machine で他の Codex 端末（terminals 一覧で AgentDetector が CODEX、別の端末 ID）が動いていなかった** なら exact。
4. 決まった binding は processKey が同じ間は保持。ただしこの cwd でそれより新しい対話スレッドが現れたら（`/new`）再評価する。
5. 決まらなければ従来どおり Unavailable と候補（1 の一覧）を出す（ユーザーが選べる）。
- ADR 0034 の「cwd／最新ファイルだけでは自動 bind しない」は守る：2 は画面の内容、3 はプロセス起動時刻と他端末の不在という追加の証拠があるときだけ。
- テスト：state_5 のフィクスチャ（一時 sqlite）で 1〜4、子スレッド除外、他の Codex 端末があるときは 3 で決めないこと、画面照合の曖昧さ。結合テスト（`herdr-mobile-it`）は実 Codex が無ければ skip。
- 実例：`HC_SESSION=herdr-mobile-it` の `t23`（名前 `p9codex`、cwd は Claude の scratchpad/p9）が実際の Codex 0.167 で 1 往復済み。rollout は `~/.codex/sessions/2026/11/09/rollout-2026-11-09T18-16-10-01a1bf97-…jsonl`。default セッションの端末には触らない。

## Phase 10: 会話が無くてもチャットを開く／エージェントでない端末はターミナルだけ（ユーザー指摘 2026-11-09）

### 背景
- 起動直後のエージェント（まだ何も話していない）は会話ログが無く `Unavailable` になり、ターミナルに落ちる。Ovis のように空のチャット（入力欄つき）で始めたい。
- シェルなどエージェントでない端末でも Chat/Terminal の切り替えが出ている。出さずにターミナルだけにする。

### 公開 API 変更（codex-a が先に入れる）
```kotlin
sealed interface TranscriptState {
    ...
    /** エージェントは確定しているが、会話はまだ始まっていない（空のチャットを出してよい） */
    data class NotStarted(val kind: AgentKind) : TranscriptState
}
```

### codex-a（core-client）
1. `NotStarted` を返す条件（どちらも「確かにまだ何も話していない」ときだけ。曖昧なら従来どおり `Unavailable`）：
   - Claude Code：`sessions/<pid>.json` の pid と procStart が一致し sessionId が取れるが、`projects/*/<sessionId>.jsonl` がまだ無い（または user / assistant の行が 1 つも無い）。
   - Codex：open-fd でも Phase 9b でも決まらず、この cwd に TUI の起動以降に作られた対話スレッドが無く、画面（最後の `OpenAI Codex (v` 以降）に送信済みのプロンプト行（入力欄の `›` 以外の `› …`）も `Worked for` も無い。
2. `NotStarted` の間も再評価を続ける（`nudge()` 直後、status / turn の変化、ポーリング）。最初のメッセージで会話ログができたら `Ready` に移る。
3. テストを追加（Claude：registry あり・ファイル無し／Codex：開始画面／送信済み画面は NotStarted にしない）。

### codex-b（app）
1. **チャットに対応するのは Claude Code と Codex だけ**（`AgentDetector.detect(record)?.kind`）。それ以外（シェル、他のプログラム、エージェント終了後）は：
   - トップバーの Chat/Terminal セグメントを出さない。ターミナルとキーバーだけ。入力欄・SessionControls も出さない。
   - TranscriptSource を作らない（端末の foreground が変わってエージェントが起動したら作る。終了したら閉じる）。
2. **既定の表示**：対応エージェントで `Ready`・`NotStarted`・`Loading` はチャット。`Unavailable` / `Failed` は今までどおりターミナル（セグメントの Chat には「会話ログが見つかりません」＋「候補から選ぶ」）。`detailSurface` の「items が空ならターミナル」はやめる。
3. **空のチャット（NotStarted）**：Ovis の空チャットに倣い、入力欄の上に 1 行「<エージェント名> に何をしてもらいますか？」と、その下に薄くフォルダー名。入力欄・送信・停止・SessionControls は通常どおり使える。
   送ったメッセージは会話ログに出るまで薄い user カードで仮表示する（文言は付けない）。送信後は `nudge()`。
4. 文字列は values / values-ja。単体テスト（表示の決定：エージェント種別 × TranscriptState → セグメントの有無と既定の表示）。

## Phase 11: チャットの入力欄で「/」を打つとコマンド一覧（ユーザー決定 2026-11-09）

### 方針（Claude 確認済み）
- **Claude Code**：Phase 9 の制御プロトコル（`claude -p --input-format stream-json …` → `initialize`）の応答 `response.response.commands`
  （`name` / `description` / `argumentHint` / `builtin`）に、その cwd で使えるコマンド・スキル・カスタムコマンドが入っている（このリポジトリで 69 件）。**端末の cwd で** initialize する。
  ただし `-p` のため対話 TUI 専用のコマンド（`/permissions` `/resume` `/config` など）は入らないので、Ovis の `server/skills/claude.ts` の `terminalBound` を静的に足し「ターミナル専用」とする。
- **Codex**：一覧を返す API が無い。組み込みは Ovis `server/skills/codex.ts` の `builtins` / `descriptions`（codex-rs `slash_command.rs` 由来）を静的に持ち、`terminalBound` は「ターミナル専用」、`agentHidden` は出さない。
  スキルは Heeler と同じく `codex -s read-only -a never app-server` を stdio で起動し `initialize` → `initialized` → `skills/list`（`{cwd}`）で取る（Ovis `server/codex-daemon.ts` の `skillRows` の検証に倣う。enabled のみ。トリガーは `$`）。取れなければ組み込みだけ。
- 説明文はそのまま英語で出してよい（エージェント側の文言）。

### 公開 API（codex-a が先に入れる）
```kotlin
package io.herdrmobile.core.usage
enum class CommandRun { CHAT, TERMINAL }   // TERMINAL: 引数なしで送るとターミナルの選択画面が開く
data class AgentCommand(val name: String, val description: String, val argumentHint: String = "",
                        val trigger: Char = '/', val run: CommandRun = CommandRun.CHAT, val source: String = "builtin") // builtin / skill / custom
class AgentCommandStore(client: HcClient, scope: CoroutineScope) : java.io.Closeable {
    fun commands(kind: AgentKind, on: String?, cwd: String): StateFlow<List<AgentCommand>?>  // 初回参照で取得
    suspend fun refresh(kind: AgentKind, on: String?, cwd: String, force: Boolean = false)  // (kind, machine, cwd) ごと 5 分スロットル
    override fun close()
}
```

### codex-a（core-client）
1. Claude：Phase 9 のスクリプトに「commands モード」（cwd 指定で initialize だけ、`commands` の name/description/argumentHint/builtin だけ返す）を足す。ClaudeAccountStore のモデル一覧取得と同じプロセス起動の作法（ログインシェルの PATH、15 秒、プロセスグループ kill）。
   静的な terminalBound を足し、同名があれば run=TERMINAL に上書き。`agentHidden`（agents, heapdump, setup-bedrock, setup-vertex）は出さない。
2. Codex：静的な組み込み＋ app-server の skills/list。スクリプトは `codex` をログインシェルの PATH で探す。全体 20 秒。
3. 並びは「組み込み → カスタム → スキル」、同名は後勝ちにしない（スキルが組み込みと同名なら組み込みを残す）。
4. 単体テスト（スクリプト出力のパース、terminalBound の適用、Codex の skills/list 検証）。結合テストは herdr-mobile-it（claude / codex が無ければ skip）。

### codex-b（app）
1. 入力欄の先頭が `/`（Codex は `$` も）で、まだ空白を打っていない間、入力欄の真上にコマンド候補のリスト（Claude Code アプリ風）：
   - 各行：`/名前`（等幅・太字）＋薄い argumentHint、下に説明 1 行（省略）。TERMINAL のものは右端に小さなターミナルのアイコン。
   - 打った文字で前方一致→部分一致の順に絞り込み。最大高さは画面の 40%、スクロール可。該当なしなら出さない。
   - 行をタップ：`/名前 ` を入力欄に入れる（argumentHint が空で CHAT のものは、そのまま送信してよい）。TERMINAL のものはタップで `/名前` を送り、ターミナル表示に切り替える。
   - 質問カードが出ている間（番号入力モード）は出さない。
2. 取得のタイミング：対応エージェントの画面を開いたとき（cwd が変わったときも）と、入力欄で `/` を打ったとき（スロットルは core）。取得中は「読み込み中…」1 行。
3. ストアは接続ごとに 1 つ（HostConnections）。文字列は values / values-ja。絞り込みは純粋関数にしてテスト。

### Phase 11 エミュレータ確認の修正（Claude 2026-11-09、codex-b）
1. 候補のリストがキーボード表示中にトップバーの上まで広がって重なる。リストの高さは「トップバーの下端から入力欄の上端まで」の範囲に収め、その中で最大 40%（キーボード表示中は残りの高さ基準）。トップバーには絶対に重ねない。
2. 引数の書き方（argumentHint）が長い行でコマンド名が `/col…` のように潰れ、1 行目は名前が消えていた。コマンド名は常に全部出し（縮めない）、argumentHint の方を 1 行で省略する。
3. 候補を選んだあと、カーソルが元の位置（`/co|ntext`）に残る。入力欄は `TextFieldValue` で扱い、カーソルを末尾に置く。argumentHint があれば `/名前 `（末尾に空白）、無ければ `/名前`。CHAT の候補は自動送信しない（入れるだけ）。
4. 並び順を確認：空の絞り込みでは「組み込み（builtin=true）→ カスタム → スキル」。今はスキル（dataviz など）が組み込みの間に混ざっている。絞り込み時は前方一致を先、その中で同じ並び順。
5. 単体テスト（並び順、選択後のテキストとカーソル位置）。

## Phase 12: エージェントのアイコンを Claude Code / Codex の実物に（ユーザー指摘 2026-11-09）

### codex-b（app）
1. Ovis `src/components/AgentMark.tsx`（MIT、chatmux の llm-logo-provider 由来）の `ClaudeMark` と `CodexMark` の SVG パスを、そのまま Android の VectorDrawable にする：
   - `res/drawable/ic_agent_claude.xml`：viewBox 512×509.64。タイル `#D77655`、記号 `#FCF2EE`（ブランド色のまま。テーマで着色しない）。
   - `res/drawable/ic_agent_codex.xml`：viewBox は `100 100 520 520` 相当（translate で原点合わせ）。単色。表示時に `LocalContentColor`（onSurface 系）で着色する。
2. `ProgramAvatar`（ui/theme/DenseRows.kt）で CLAUDE_CODE / CODEX はこの画像を使う。
   - Claude はタイル自体が角丸の背景なので、secondaryContainer の Surface で囲まず、同じ外寸（36dp 相当）で画像だけ出す。
   - Codex は今の Surface（角丸の背景）の中に記号を出す。
   - それ以外の種別・シェルは今のアイコンのまま。ドロワーの行、エージェント一覧、トップバー、タブのシートなど ProgramAvatar を使っている所すべてに効くこと。別のアイコン描画をしている所があればそれも揃える。特に `NewTerminalSheet.kt` の「Claude Code」「Codex」の項目（今は AutoAwesome / Code）も同じ画像にする。
3. `assets/third_party_notices.txt` に Ovis（MIT、devswha/ovis）の著作権表示とライセンス本文を追記し、ロゴの商標は各社に帰属する旨を 1 行。
4. ライト／ダーク両方で確認できるよう、Compose の @Preview を 1 つ置く（任意）。ビルド・テスト・lint を通す。

## Phase 13: チャットが出るまでの待ちとチラつきをなくす（ユーザー指摘 2026-11-09）

### 背景（Claude 調査）
- 「Codex はチャットに切り替わるまで時間がかかり、その間ターミナルが出る」「タブを切り替えるとターミナルとチャットがチラつく」。
- 原因：
  1. `SessionResolver`（と Phase 9b の binding 保持 `retained`）は `TranscriptSource` ごとに作られる。タブを切り替えると ViewModel が破棄される（Phase 7）ので、開くたびに Codex の判定（候補 rollout 最大 32 件の末尾読み＋画面照合）をやり直す。
  2. 開いた直後、接続が無い間に `Unavailable("Terminal is not connected")` を出す。app はそれでターミナルを選び、`Ready` でチャットに戻す。
  3. app は record（端末情報）が来るまで種別が null → 「エージェントでない」扱いでターミナルを出し、来たらチャットに変える。

### codex-a（core-client）
1. **接続ごとの binding キャッシュ**：binding（と Phase 9b の保持情報）を `TranscriptSource` ではなく HcClient（接続）単位で共有する（例：internal な `TranscriptCache`。HcClient をキーにした WeakHashMap か、TranscriptSource の任意引数）。キーは端末 ID ＋ processKey。同じ processKey で開き直したら判定をやり直さず即 `Ready`（裏で通常どおり検証し、`/new` などで変われば更新）。
2. **投影結果のキャッシュ**：binding ごとに直近の items / activity / session と読み終えた位置を最大 16 件（LRU）保持し、開き直したら即座に発行してから追記分だけ読む。
3. **途中の Unavailable を出さない**：初回の判定が終わるまで（接続待ち・record 待ちを含む）は `Loading` のまま。`Unavailable` は判定が最後まで終わって決まらなかったときだけ。いったん `Ready` になった後の一時的な切断では `Ready` を保つ（再接続後に再検証）。
4. **Codex の判定を速く**：安い判定（open-fd、新しいスレッド）を先に。画面照合の候補 rollout は updated_at の新しい順に 8 件をまず並列（同時 4）で読み、決まらなければ残りを読む。照合用の末尾読みは最大 256 KiB。
5. テスト：開き直しで resolver を呼ばないこと、初回判定中は Loading のままであること、キャッシュの LRU、Codex の段階的な候補読み。

### codex-b（app）
1. **record が来るまでターミナルを出さない**：開いた直後で種別が未確定の間は、ターミナルもチャットも出さず背景＋小さな読み込み表示。端末ごとの「最後に分かった種別」と「最後に表示した面（チャット／ターミナル）」を接続単位（HostConnections）で覚え、開き直したときはそれで即座に描く。
2. **Loading 中はチャット面にアニメーション**：対応エージェントで `Loading` の間はチャット面を出し、会話の場所に読み込みアニメーション（メッセージ形のスケルトンを薄く明滅させる、または中央に小さな進捗＋「会話を読み込み中…」）。入力欄は使える。ターミナルには切り替えない。
3. **自動の面切り替えは一度だけ**：自動で面を変えるのは「Loading → Ready/NotStarted（チャット）」か「Loading → Unavailable/Failed（ターミナル）」のときだけ。ユーザーが選んだ面は、その端末を開いている間は自動で変えない。面の切り替えは 150ms 程度のクロスフェード。
4. 単体テスト（種別未確定・Loading・キャッシュ済みの面での表示決定）。

## Phase 14: release-please でリリース＋アプリ内アップデート（ユーザー指摘 2026-11-10）

### 背景
- 「Release please を使ってリリースできるようにして」「Morphe（ReVanced 系マネージャ）のようにアプリ側からアップデートできるようにして」。
- リポジトリは **public**（`gongitsune/herdr-mobile`）。GitHub API は認証なしで読める（60 回/時。アプリにトークンは入れない）。
- 今の配布は main への push ごとの `nightly` pre-release（固定 debug 鍵で署名、versionCode = `GITHUB_RUN_NUMBER`）。

### 決定事項（Claude）
- **署名はリリースも固定 debug 鍵のまま**（今入っている nightly から上書きできることを優先。正式な release 鍵は今回は作らない）。
- **versionCode の連番を保つため、release-please は別ワークフローにせず `android.yml` の 1 ジョブにする**（run number はワークフローごとの連番なので、別ワークフローで作ると versionCode が巻き戻る）。
- versionName の正本はリポジトリ直下の `version.txt`（release-please の `simple` 型が更新する）。
- コミットは今後 Conventional Commits（`feat:` / `fix:` / `chore:` …）。コミットは Claude が行う。初回は `Release-As: 0.1.0` で v0.1.0 を出す。
- リポジトリ設定「Actions が PR を作れる」は Claude が有効にする（`can_approve_pull_request_reviews=true`）。GITHUB_TOKEN が作った PR／タグでは他のワークフローが動かない点は許容（リリースの APK は同じ run で作るので問題ない）。

### codex-b（CI / Gradle）
1. `release-please-config.json`（`release-type: simple`、`package-name: herdr-mobile`、`include-component-in-tag: false`（タグは `v0.1.0`）、`bump-minor-pre-major: true`、`changelog-sections` は feat/fix/perf を表示、他は hidden）、`.release-please-manifest.json`（`{".": "0.1.0"}`）、`version.txt`（`0.1.0`）を追加。`bootstrap-sha` は現在の HEAD（`c044793`）。
2. `app/build.gradle.kts`：versionName を `rootProject.file("version.txt")` から読む（前後の空白除去、無ければ `0.0.0`）。versionCode の扱いは今のまま。
3. `android.yml`：
   - `release-please` ジョブ（`googleapis/release-please-action@v4`、`if: github.event_name == 'push' && github.ref == 'refs/heads/main'`、`permissions: contents: write, pull-requests: write`、config/manifest のパス指定）。outputs に `release_created` と `tag_name`。
   - 既存の `build` ジョブはそのまま（release-please を待たない）。APK 名は main への push で `herdr-mobile-<sha>.apk` のままでよいが、**versionCode を名前に入れる**：`herdr-mobile-<versionName>-<run_number>-<sha>.apk`。
   - `release` ジョブ：`needs: [build, release-please]`、`if: needs.release-please.outputs.release_created == 'true'`。artifact を落として `herdr-mobile-<tag>.apk` という名前でそのリリースに `gh release upload`。
   - `nightly` ジョブ：リリースが作られた run でも従来どおり作り直してよい。ノートに versionCode（run number）と versionName を書く。
4. `docs/RELEASING.md`（短く）：Conventional Commits の書き方、release PR をマージするとタグ・Release・APK ができる流れ、`Release-As:` の使い方。

### codex-b（app：アプリ内アップデート）
Morphe のように「アプリから新しい版を確認 → 変更点を見る → ダウンロード → インストール」まで完結させる。
1. **確認**：`https://api.github.com/repos/gongitsune/herdr-mobile/releases`（`Accept: application/vnd.github+json`、`User-Agent: herdr-mobile/<versionName>`）。
   - チャンネル：**安定版**（既定。`prerelease=false` の最新 = `/releases/latest`）と **Nightly**（`nightly` タグ）。設定で切り替え、DataStore に保存。
   - 新しいかの判定は **versionCode** で行う：APK アセット名 `herdr-mobile-…-<versionCode>-…apk` から数字を取る（安定版は `herdr-mobile-v0.1.0.apk` なので、安定版は semver を `BuildConfig.VERSION_NAME` と比べ、同じなら更新なし）。判定は純粋関数にして単体テスト。
   - いつ確認するか：アプリ起動時に 1 回（前回確認から 6 時間以上経っていれば）と、手動の「アップデートを確認」。失敗（オフライン、レート制限 403/429）は静かに無視し、手動のときだけ短いメッセージ。
2. **知らせる**：新しい版があればドロワーの下部（または設定の入口付近）に目立たない「アップデートがあります（v0.2.0）」の行。タップで下からのシート：版、公開日、リリースノート（`body` を既存の markdown-m3 で表示）、「ダウンロードしてインストール」ボタン、Nightly のときは commit の短い SHA。
3. **ダウンロード**：OkHttp 等は足さず `HttpURLConnection` でよい（リダイレクト追従）。`cacheDir/updates/` に保存、進捗バーを出す、キャンセル可、サイズが `asset.size` と一致することを確認。古い APK は消す。
4. **インストール**：`PackageInstaller` のセッション方式（`ACTION_INSTALL_PACKAGE` は使わない）。`REQUEST_INSTALL_PACKAGES` 権限を追加。`canRequestPackageInstalls()` が false なら「不明なアプリのインストール」設定（`ACTION_MANAGE_UNKNOWN_APP_SOURCES`、package URI 付き）へ案内し、戻ってきたら続行。結果は `PendingIntent`（BroadcastReceiver、`FLAG_MUTABLE`）で受け、`STATUS_PENDING_USER_ACTION` なら同梱の Intent で確認画面を出す。失敗（署名不一致＝ `STATUS_FAILURE_CONFLICT` など）は理由を日本語で表示（署名不一致なら「一度アンインストールが必要」）。
5. **設定**：既存の設定の場所（なければドロワーの下部）に「アップデート」セクション：現在の版（versionName ＋ versionCode）、チャンネル（安定版／Nightly）、「起動時に確認」のオン／オフ（既定オン）、「今すぐ確認」。
6. 文字列は values / values-ja。ネットワーク・PackageInstaller はインタフェースで包んで ViewModel を単体テスト（判定、チャンネル、6 時間の間引き、エラー時の扱い）。ビルド・テスト・lint を通す。

### Phase 14 追補：ワークフローを分ける（ユーザー決定 2026-11-10、codex-b）
上の「release-please は android.yml の 1 ジョブ」「versionCode = GITHUB_RUN_NUMBER」を次で置き換える。
1. **versionCode = main のコミット数**（`git rev-list --count HEAD`）。ワークフローに依存せず単調増加、CI とローカルで同じ値。
   - `app/build.gradle.kts`：`providers.exec` で取得。環境変数 `HERDR_VERSION_CODE` があればそれを優先（手動の逃げ道）。git が無いときは 1。
   - **shallow clone では誤った小さい値になるので、`CI=true` かつ `git rev-parse --is-shallow-repository` が true ならビルドを失敗させる**（メッセージで `fetch-depth: 0` を案内）。
   - `GITHUB_RUN_NUMBER` は versionCode にもう使わない。
2. **ワークフロー 3 本**（すべての checkout は `fetch-depth: 0`）：
   - `android.yml`：今のテスト・ビルド・lint・artifact・nightly のまま。release-please / release ジョブを削除。APK 名は `herdr-mobile-<versionName>-<versionCode>-<sha>.apk`（versionCode はコミット数。シェルでも同じコマンドで出す）。nightly のノートも versionCode をコミット数に。
   - `release-please.yml`：`on: push: branches: [main]`、`permissions: contents: write, pull-requests: write, actions: write`、`concurrency` で直列化。release-please-action@v4 の後、`release_created == 'true'` なら `gh workflow run release.yml --ref "$TAG" -f tag="$TAG"`（`workflow_dispatch` は GITHUB_TOKEN からでも起動できる）。
   - `release.yml`：`on: workflow_dispatch: inputs: tag (required)`。tag を checkout（`ref: ${{ inputs.tag }}`、fetch-depth 0）、tag が `v*` 形式かつ GitHub Release が存在することを確認、固定 debug 鍵の準備（android.yml と同じ手順）、`./gradlew :app:assembleDebug :app:testDebugUnitTest`、`herdr-mobile-<tag>.apk` を `gh release upload --clobber`、鍵の削除。`permissions: contents: write`。手動で再実行・再添付できること。
3. アプリ：`findUpdate` の versionCode の読み方は名前の形式が同じなので変えない（テストの例の数字だけ見直す）。表示の「versionCode」はそのまま。
4. `docs/RELEASING.md` と上の「CI / 配布」節の versionCode の記述を更新。ローカルビルドも同じ versionCode になる（以前のローカル用 `GITHUB_RUN_NUMBER>=1000` は不要。1000 以上の版が入っている端末は一度アンインストールが必要）旨を 1 行。

## Phase 15: 設定を階層に分ける／サイドバーからアップデートを外す（ユーザー指摘 2026-11-10、codex-b）

### 背景
- 「設定にアップデートの設定があるならサイドバーには表示しない」「設定の UI を階層分けしてわかりやすく」。
- 今の設定画面は HostsScreen そのもので、アップデートと通知の権限はトップバーのアイコンに置かれていて分かりにくい。

### ドロワー
1. `UpdateDrawerEntry` をドロワーから外す（ファイルごと不要なら削除）。
2. 新しい版があるときは、ドロワー下部の設定（歯車）アイコンに小さなバッジ（点）だけを出す。文字は出さない。

### 設定のトップ（一覧）
Material 3 の `ListItem` で、左にアイコン、タイトル、1 行の説明、右に `ChevronRight`。グループの見出しは小さな labelLarge（primary 色）。
- **接続**
  - ホスト：説明「N 件のホスト」（0 件なら「ホストを追加」）→ ホストの画面（今の HostsScreen をそのまま。追加ボタンもここ）
- **通知**
  - 通知：説明は権限の状態（「許可されています」／「オフ」）→ 通知の画面（権限の状態、「許可する」（Android 13+ の権限リクエスト）、システムの通知設定を開くボタン）。今のトップバーの NotificationPermissionAction はここへ移して消す。
- **アプリ**
  - アップデート：説明は「v0.1.0（安定版）」、新しい版があれば「アップデートがあります（v0.2.0）」＋バッジ → アップデートの画面
  - このアプリについて：説明は版 → 画面：アプリ名・版（versionName ＋ versionCode）・GitHub のリポジトリを開く・サードパーティのライセンス（`assets/third_party_notices.txt` をスクロール表示する下位画面）

### アップデートの画面（今のボトムシートを全画面の設定ページに作り直す）
- 上から：「現在の版」カード → 新しい版があればそのカード（版・公開日・Nightly なら SHA・「ダウンロードしてインストール」・進捗・キャンセル、その下にリリースノート）→ 見出し「設定」：チャンネル（安定版／Nightly の SegmentedButton）、「起動時に確認」スイッチ（ListItem の trailing）→「今すぐ確認」ボタン。
- 不明なアプリのインストール許可のダイアログは今のまま。
- ボトムシート（`UpdateDialogs` の ModalBottomSheet）は削除。

### 画面遷移
- 設定の中は小さなスタック（`enum`／sealed の画面名を `rememberSaveable` の List で保持）。トップバーのタイトルは今の画面名、戻る矢印とシステムの戻る（`BackHandler`）で 1 階層ずつ戻る。トップから戻ると設定を閉じる。
- ドロワーの「ホストを追加」（ホストが 0 件のとき）は設定 → ホストの画面を直接開いて追加ダイアログを出す（今の動作を保つ）。
- 画面の切り替えは横スライドの短いアニメーション（任意）。

### その他
- 文字列は values / values-ja。ViewModel・保存の仕組みは変えない。
- 単体テスト：設定内のスタック（push/pop、トップから戻ると閉じる）、トップの説明文（ホスト件数、アップデートあり／なし）の純粋関数。
- ビルド・テスト・lint を通す。

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
