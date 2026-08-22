# LDFA — Linux Desktop for Android

Androidスマートフォン／タブレット上に、日本語入力対応のDebian + XFCE環境を構築・管理する単一APKです。ターミナル実行基盤にはTermux、表示サーバーにはTermux:X11の固定ソースを統合しています。

## 実装済み機能

- Debianベースの複数PRoot環境
- XFCE、Noto CJK、Fcitx5 + Mozc、Google Chrome、sudoの自動構築
- Termuxターミナル、サービス、公式bootstrapの内蔵
- Termux:X11表示Activity、Binder interface、`libXlorie.so`の内蔵
- Android所有の非公開`:x11` Foreground ServiceによるXorg起動
- native通常描画、native legacy描画、loopback VNCの段階的fallback
- Surface/EGL準備と、成功したpresentationだけを数える単調serialによる実描画検証
- Android共有ストレージをDebianの`/mnt/android`へ接続
- インストール・XFCE・X11・VNCの分離ログ
- Foreground Service、WakeLock、heartbeatによる監視と復旧

## 単一APK

```text
LDFA管理UI（Compose / Material 3）
+ Termuxランタイム／内蔵ターミナル
+ RunCommandService／TermuxService
+ Termux:X11 MainActivity／LorieView
+ EmbeddedX11ServerService（com.termux:x11）
+ libXlorie / Xorg
+ Debian XFCE管理スクリプト
+ TigerVNC / noVNC fallback controller
```

別途Termux、Termux:X11、VNCクライアントをインストールする必要はありません。Termuxの固定プレフィックス`/data/data/com.termux/files/usr`との互換性のためapplication IDは`com.termux`であり、署名が異なる公式Termuxとは共存できません。

## X11起動パイプライン

旧`/system/bin/app_process`、loader APK、TCP 7892、通常接続用broadcastは使用しません。

```text
EmbeddedX11ServerService (:x11)
  -> libXlorie / Xorg DISPLAY=:1
  -> Unix socket + xset probe
  -> direct Binder + X connection FD
  -> MainActivity / LorieView / Surface / EGL READY
  -> xrefresh damage
  -> successfulPresentSerial delta
  -> Debian worker / XFCE / xfwm4
  -> xset + process + EWMH probe
  -> second successfulPresentSerial delta
```

起動ごとにService世代UUIDとPIDを記録し、旧世代の停止完了を確認してから次を開始します。viewerを先に切断してnative teardownが完了した後でXorgを停止し、古いBinder・FD・callbackは世代不一致なら破棄します。

nativeが失敗した場合は、buffer import系の互換性問題に限ってlegacy描画を試し、それでも表示できなければ`DISPLAY=:2`のTigerVNC/noVNCへ切り替えます。表示番号はmetadata、tmux worker、XFCE sessionまで明示的に伝播します。

## Debian XFCE

proot-distroのDebian 12（Bookworm）rootfsへ次を導入します。rootfsはPRoot互換性と再現性のため`debian:12`へ固定します。

- XFCE、XFWM、Panel、Thunar、XFCE Terminal
- `ja_JP.UTF-8`
- Noto CJK / Noto Color Emoji
- Fcitx5 / Mozc
- Mesaソフトウェアrenderer
- `xset`、`xrefresh`、`xprop`
- `sudo`
- Google公式Chrome stable（amd64／arm64）とPRoot互換ランチャー

一般ユーザー`desktop`にはパスワードなしsudoを設定します。`.profile`、`.xprofile`、`.xinputrc`へ日本語・Fcitx設定を保存し、`~/Desktop/Android共有`を`/mnt/android`へ接続します。

Google ChromeはDebian環境の作成時にGoogle公式パッケージから導入します。既存環境もデスクトップ起動前に一度だけ不足を補います。PRoot内では通常のChrome sandboxを確立できないため、専用ランチャーが`--no-sandbox`、`--disable-dev-shm-usage`、X11 backendを明示します。

## ログと共有フォルダ

```text
Debian/XFCE: ~/.local/share/linux-desktop-for-android/logs/<id>.log
Native X11:  ~/.local/share/linux-desktop-for-android/logs/x11-server.log
VNC:         ~/.local/share/linux-desktop-for-android/logs/vnc-server.log

Android:     /sdcard/LinuxDesktop/<environment-id>
Termux:      ~/storage/shared/LinuxDesktop/<environment-id>
Debian:      /mnt/android
XFCE:        /home/desktop/Desktop/Android共有
```

## ビルド

```bash
git clone --recurse-submodules https://github.com/hatake716/LDFA.git
cd LDFA
bash ./scripts/check-host-script.sh
bash ./scripts/test-host-controller.sh
bash ./scripts/check-x11-controller.sh
bash ./gradlew --no-daemon \
  testDebugUnitTest \
  :app:lintDebug \
  :termux-runtime:lintDebug \
  :embedded-x11:lintDebug \
  assembleDebug
```

Debug APKは`app/build/outputs/apk/debug/app-debug.apk`に生成され、GitHub Actionsでは`LDFA-v0.9.0-debug-apk`として保存されます。

## ライセンス

統合版全体は`GPL-3.0-only`です。Termux、Termux:X11、Debianおよび個別コンポーネントの通知は`THIRD_PARTY_NOTICES.md`と各vendorサブモジュール内のライセンスファイルを参照してください。
