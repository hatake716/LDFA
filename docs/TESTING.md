# テスト方針

## ホストコントローラー

```bash
bash ./scripts/check-host-script.sh
bash ./scripts/test-host-controller.sh
```

確認対象:

- Debian、XFCE、Fcitx5/Mozc、日本語locale、Google Chrome、sudo
- Chrome公式amd64／arm64 package URL、package metadata検査、PRoot互換ランチャー
- `xset`、`xrefresh`、`xprop`の明示依存
- `.profile`、`.xprofile`、`.xinputrc`、Android共有symlink
- metadataのatomic更新、controller lock、tmux worker
- native`:1`／VNC`:2`のDISPLAY伝播
- 日本語表示名と共有ファイルの保持／削除

## X11 controllerと生成overlay

```bash
bash ./scripts/check-x11-controller.sh
```

確認対象:

- Android所有の`:x11` Service、direct Binder、世代UUID／PID barrier
- 旧`app_process`、loader APK、TCP 7892経路が復活していないこと
- viewer-first teardownとpending bind cancellation
- normal／legacy／VNC fallback
- Surface/EGL READYとsuccessful-presentation serial probe
- JNI annotation/ABI、renderer mutex、EGL error、FD reconnect hardening
- 固定Termux:X11入力からJava/native overlayを再生成できること

## Kotlin単体テスト

```bash
bash ./gradlew testDebugUnitTest
```

環境状態解析、ホストスクリプト互換変換、DISPLAY metadata、strict X11 preflight、ログ整形などを検証します。

## 完全Androidビルド

```bash
git submodule update --init --recursive
bash ./gradlew --no-daemon \
  clean \
  testDebugUnitTest \
  :app:lintDebug \
  :termux-runtime:lintDebug \
  :embedded-x11:lintDebug \
  assembleDebug
```

CIはさらに次を検証します。

- Gradle 8.13 wrapper JARとdistributionのSHA-256
- `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`の`libXlorie.so`
- Manifestの非公開`:x11` Foreground Service
- `com.termux` application ID、versionCode 16、versionName 0.9.0、LDFA label
- `ToolsScreen`、X11/VNC assets
- 旧loader/app_process commandの不在
- APKのzip alignmentと署名

## 実機で必要な確認

- 初回Termux bootstrapとDebian PRootインストール
- native通常／legacyおよびVNC fallbackの実画面
- XFCE表示、回転、background復帰、viewer再接続
- タッチ、マウス、software/physical keyboard
- 日本語表示、Fcitx5/Mozc入力、`sudo apt update`
- Google Chromeの初回起動、利用規約確認、Webページ描画
- 画面OFF、Activity再生成、RAM圧迫、X11 process死後の復旧
- 複数環境の作成・切替・削除
- Android共有フォルダと日本語／大容量ファイル
- 外部display、DeX系

自動テストとAPK検査だけではAndroid実機のSurface、GPU driver、入力、PRoot、端末固有の省電力制御を完全には再現できません。配布前には実機で最終確認します。
