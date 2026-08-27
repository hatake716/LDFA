# LDFA bootstrap ビルド手順（com.hatake716.linuxdesktop prefix 版）

アプリに同梱する Termux bootstrap（`vendor/termux-app/app/src/main/cpp/bootstrap-<arch>.zip`）を、
このアプリ専用の prefix `/data/data/com.hatake716.linuxdesktop/files/usr` 向けに
[termux-packages](https://github.com/termux/termux-packages) からフルソースビルドする手順。

upstream の配布 bootstrap は `com.termux` prefix がバイナリに焼き込まれているため流用できない。
aarch64 の zip はこの手順の成果物であり、**本体リポジトリにコミット済み**
（vendor/termux-app/.gitignore の否定ルール参照。URL からは取得できないため
リポジトリが唯一のソース）。チェックサムは
[termux-runtime/build.gradle](../../termux-runtime/build.gradle) に固定してある。

## 手順

```bash
git clone https://github.com/termux/termux-packages.git
cd termux-packages
git checkout 0223902ddb42a5572812044e64310ada0f658ff2   # 検証済みベース
git apply /path/to/ldfa-termux-packages.patch

# Docker が必要（ghcr.io/termux/package-builder）。NixOS では bash 明示起動。
bash ./scripts/run-docker.sh ./scripts/build-bootstraps.sh \
    --architectures aarch64 \
    --add proot --add proot-distro --add tmux --add pulseaudio --add xkeyboard-config
```

成果物 `bootstrap-aarch64.zip` を `vendor/termux-app/app/src/main/cpp/` に置き、
`termux-runtime/build.gradle` の SHA-256 を更新する。zip を差し替えたら
`./gradlew :termux-runtime:clean :app:clean` してから assemble しないと
NDK が古い zip を埋め込んだままになる。

## パッチの内容（ldfa-termux-packages.patch）

- `scripts/properties.sh`: `TERMUX_APP__PACKAGE_NAME=com.hatake716.linuxdesktop`
- `scripts/run-docker.sh`: builder の uid/gid 同期を無効化（NixOS で groupmod/cp が失敗するため。
  builder は 1001:1001 のまま、バインドマウント先を `chmod o+w`）+ seccomp unconfined
- `scripts/build-bootstraps.sh`: bzip2 は libbz2 のサブパッケージとして取得。
  `LDFA_BOOTSTRAP_WHITELIST`（実行時に必要な 120 パッケージ）で extract_debs を絞り込み
  — apt のビルド依存クロージャ（python/tcl/doxygen 等）が bundle に混入するのを防ぐ
- `scripts/build/termux_step_setup_variables.sh`: `TERMUX_PREFIX`/`TERMUX_APP_PACKAGE` を
  環境変数として export（bin/pkg の configure が読む）+ savannah ミラー書き換え
- `scripts/build/termux_step_install_license.sh`: ライセンスファイル欠落を warn+スタブに緩和
- `packages/*/build.sh`: savannah.gnu.org → download-mirror.savannah.gnu.org

## 注意

- `--android10` は付けない（apt/dpkg が除外されてしまう。LDFA は W^X を native proot
  （libpdrt.so, extractNativeLibs=true）で回避するので data-dir 実行の制約は問題にならない）
- リポジトリ名 ≠ アプリパッケージ名のため全依存がソースビルドになる（aarch64 で数時間）
- 残り 3 アーキ（arm/i686/x86_64）は未ビルド。同じパッチ・同じコマンドの
  `--architectures` 差し替えでビルドし、build.gradle のチェックサムを更新する
