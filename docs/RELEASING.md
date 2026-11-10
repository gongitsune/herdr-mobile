# リリース

コミットは Conventional Commits にする（Claude が commit/push を担当）。
`feat: 新機能`、`fix: 修正`、`perf: 高速化` はリリースノートに表示され、
`chore:`、`docs:`、`test:` などは表示されない。破壊的変更は `feat!:` または
フッターの `BREAKING CHANGE:` で示す。

main への push で release-please が `version.txt` と CHANGELOG を更新する
リリース PR を作る。PR をマージすると同じ Android ワークフローでタグ
（例 `v0.1.0`）、GitHub Release、固定 debug 鍵で署名した APK を作る。
APK は `herdr-mobile-v0.1.0.apk`。nightly も引き続き更新する。
versionCode は両方とも同じワークフローの `GITHUB_RUN_NUMBER` を使う。
署名 Secret がないビルドは Android の既定 debug 鍵になる。

版を指定する場合はコミット本文の末尾に空行を挟んで `Release-As: 0.1.0`
を記載する（初回もこの指定を使う）。versionName の正本は `version.txt`。
GITHUB_TOKEN が作った PR やタグから別のワークフローは起動しないため、
APK のビルドと添付はリリースを作った run 内で完結させる。
Actions の PR 作成許可と署名 Secrets の登録は Claude が設定する。
