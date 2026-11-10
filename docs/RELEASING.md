# リリース

コミットは Conventional Commits にする（Claude が commit/push を担当）。
`feat: 新機能`、`fix: 修正`、`perf: 高速化` はリリースノートに表示され、
`chore:`、`docs:`、`test:` などは表示されない。破壊的変更は `feat!:` または
フッターの `BREAKING CHANGE:` で示す。

main への push で `release-please.yml` が `version.txt` と CHANGELOG を更新する
リリース PR を作る。PR をマージするとタグ（例 `v0.1.0`）と GitHub Release を
作り、`workflow_dispatch` で `release.yml` を起動する。指定タグを full checkout し、
ビルドと単体テストの成功後に固定 debug 鍵で署名した `herdr-mobile-v0.1.0.apk` を添付する。
`android.yml` はテスト・ビルド・lint・artifact と nightly の更新を担当し、
APK 名は `herdr-mobile-<versionName>-<versionCode>-<sha>.apk`。
署名 Secret がないビルドは、どちらも Android の既定 debug 鍵になる。

versionCode は CI・ローカルとも `git rev-list --count HEAD`（main のコミット数）。
手動で上書きする場合は正の整数を `HERDR_VERSION_CODE` に指定する。git が使えなければ 1。
すべての checkout は `fetch-depth: 0` とし、`CI=true` の shallow clone はビルドを失敗させる。
ローカル用の `GITHUB_RUN_NUMBER>=1000` は不要。1000 以上の versionCode が入っている端末は一度アンインストールが必要。

APK の再ビルド・再添付は Actions の「Release APK」を手動実行し、既存の `v*` タグを
`tag` に指定する（CLI: `gh workflow run release.yml --ref v0.1.0 -f tag=v0.1.0`）。
タグと GitHub Release の存在を確認してからビルドし、同名 APK は `--clobber` で置き換える。

版を指定する場合はコミット本文の末尾に空行を挟んで `Release-As: 0.1.0`
を記載する（初回もこの指定を使う）。versionName の正本は `version.txt`。
GITHUB_TOKEN が作ったタグでは自動起動しないため、APK 用ワークフローは明示的な
`workflow_dispatch` で起動する（[GitHub の仕様](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)）。
Actions の PR 作成許可と署名 Secrets の登録は Claude が設定する。
