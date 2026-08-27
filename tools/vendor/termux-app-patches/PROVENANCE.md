# vendor/termux-app の来歴

`vendor/termux-app` は元々 git submodule(https://github.com/termux/termux-app.git)
だったが、LDFA 独自コミットを積んだ状態では upstream から submodule コミットを
取得できず `git clone --recursive` も CI も成立しないため、**本体リポジトリへ
ベンダリング(取り込み)**した。

- ベース: upstream termux-app コミット
  `3df69d1d` ("Revert: Add Warp sponsors logo", v0.117-438 相当)
- 適用パッチ: このディレクトリの 0001〜0004(ベンダリング時点の LDFA 独自変更)。
  ベンダリング後の変更は本体リポジトリの履歴で `vendor/termux-app/` を参照
- ライセンス: upstream のまま(GPLv3 ほか、vendor/termux-app/LICENSE.md 参照)

upstream を取り込みたい場合は、上記ベースからの diff を意識しつつ
vendor/termux-app/ へ手動でマージする。

`bootstrap-aarch64.zip`(app/src/main/cpp/)は LDFA が termux-packages から
再ビルドした成果物で、本体リポジトリにコミットしてある(.gitignore の否定
ルール)。再生成手順は tools/bootstrap/README.md を参照。他 3 アーキの zip は
upstream 配布物のままビルド時にダウンロードされる(ignore 継続)。
