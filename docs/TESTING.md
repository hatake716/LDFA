# テスト方針

## Ubuntuホストコントローラー

```bash
bash ./scripts/check-host-script.sh
bash ./scripts/test-host-controller.sh
```

確認対象:

- Bash構文
- Ubuntuを指定するPRoot Distroコマンド
- XFCEセッション定義とGNOMEコードの不在
- Fcitx5／Mozc／日本語ロケール
- `sudo`、sudoグループ、`visudo`検証
- Android共有フォルダと`--shared-tmp`
- tmuxインストール／実行ワーカー
- Ubuntuメタデータ、XFCE固定値、日本語名転送
- 削除時の共有ファイル保持／削除

## X11コントローラー

```bash
bash ./scripts/check-x11-controller.sh
```

CIで次を必須条件として確認します。

- `ldfa-x11.sh`のBash構文
- v0.4.0プロセスモデル
- `/system/bin/app_process`
- `com.termux.x11.CmdEntryPoint`
- APKを使用する`CLASSPATH`
- `XKB_CONFIG_ROOT`
- `-legacy-drawing`
- `proot-distro --shared-tmp`
- Ubuntu内部の`/usr/bin/xset q`実接続プローブ
- tmux X11 worker
- X11専用ログ`x11-server.log`
- 旧`EmbeddedX11ServerService`の不在
- Androidアプリコードから`new CmdEntryPoint()`を直接呼んでいないこと

## Kotlin単体テスト

```bash
bash ./gradlew testDebugUnitTest
```

確認対象:

- 日本語を含む環境状態の解析
- XFCE固定メタデータ
- 旧形式・未知形式をXFCEとして安全に扱う互換処理
- 壊れた行の除外と進捗値のクランプ
- ホストスクリプトの0.4.0互換変換
- リアルタイムログのANSI制御文字除去
- `\r`出力の改行正規化
- 最新行だけを保持するログ末尾処理

## 統合Androidビルド

```bash
git submodule update --init --recursive
bash ./gradlew \
  testDebugUnitTest \
  :app:lintDebug \
  :termux-runtime:lintDebug \
  :embedded-x11:lintDebug \
  assembleDebug
```

ビルドでは次を含めて検証します。

- 固定コミットのTermuxソース
- Termuxネイティブ実行ランタイム
- 固定コミットのTermux:X11ソース
- `libXlorie`ネイティブビルド
- `CmdEntryPoint`のapp_process向けnativeLibraryDirロード
- AIDLとX11表示Activity
- Compose管理UI
- Manifestマージ
- 単一Debug APK生成

APK生成後は次もCIで確認します。

- APKに`libXlorie.so`が存在
- APKに`assets/ldfa-x11.sh`が存在
- Manifestに旧`EmbeddedX11ServerService`が存在しない

## 実機で必要な確認

- 内蔵Termuxブートストラップの初回展開
- `/system/bin/app_process`からX serverが起動
- X11 tmuxセッションが生存
- `$PREFIX/tmp/.X11-unix/X1`が生成
- X11 ActivityがBinder/FD接続
- Ubuntu内部から`DISPLAY=:1 xset q`が成功
- Ubuntu PRoot環境の新規インストール
- XFCE表示、タッチ、ソフトウェアキーボード、物理キーボード
- 日本語表示とMozc入力
- `sudo apt update`
- X11停止／再起動後の再接続
- 画面OFF、履歴除去、RAM圧迫後のtmux／XFCE／X11復旧
- 複数Ubuntu環境の作成・切替・削除
- 日本語ファイル名と大容量ファイルの共有
- 縦・横画面、外部ディスプレイ、DeX系

自動テストだけではAndroidの`app_process`、Binder、ネイティブX11、PRoot、端末メーカー独自の省電力挙動を完全には再現できません。配布前には実機で最終確認します。
