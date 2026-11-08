# AGENTS.md

Android ネイティブの herdr-core クライアント。計画・API 契約・担当範囲はすべて `docs/PLAN.md` が正。作業前に必ず読むこと。

- 2 台の codex（codex-a: `core-client/**`, codex-b: それ以外）が並行で作業する。担当外のファイルは編集しない。
- 公開 API（PLAN.md の契約）を変える場合は先に PLAN.md を更新する。
- git commit / push はしない。
- herdr-core は `/usr/bin/herdr-core`（`hc`）0.1.18。default セッション（`/run/user/1000/herdr-core/default.sock`）はユーザーの本番なので一切触らない。テストは `HC_SESSION=herdr-mobile-it` の専用 core を使う（PLAN.md「テストの注意」）。
- sudo が必要な操作はしない。必要なら作業を止めて報告する。
- 区切りごとに「やったこと / 変更ファイル / ビルド・テスト結果 / 未解決事項」を短く報告する。
- Android SDK: `ANDROID_HOME=$HOME/Android/Sdk`。JDK: mise の temurin-21。
