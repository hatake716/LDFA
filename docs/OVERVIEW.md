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
- app-private Unix socketによるDebian→Android PulseAudio再生出力
- インストール・XFCE・X11・VNCの分離ログ
- Foreground Service、WakeLock、heartbeatによる監視と復旧
- AndroidによるChrome／XFCE子プロセス個別終了を検知する、定期pollingなしのイベント駆動supervisor

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
  -> Debian worker / xfsettingsd / xfwm4 / Panel / Desktop
  -> xset + process + visible EWMH window probe
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
- `pulseaudio-utils`、ALSA Pulse plugin、XFCE panelの音量／mute UI
- Google公式Chrome stable（amd64／arm64）とPRoot互換ランチャー
- Node.js 22 LTS（公式静的ビルド、SHA-256検証付き）を`/opt/nodejs`へ導入し、`node`／`npm`／`npx`を`/usr/local/bin`へリンク

一般ユーザー`desktop`にはパスワードなしsudoを設定します。`.profile`、`.xprofile`、`.xinputrc`へ日本語・Fcitx設定を保存し、`~/Desktop/Android共有`を`/mnt/android`へ接続します。

Google ChromeはDebian環境の作成時にGoogle公式パッケージから導入します。既存環境もデスクトップ起動前にChrome本体とlauncher世代を検査し、不足または古いlauncherを補います。PRoot内では通常のChrome sandboxを確立できないため、専用ランチャーが`--no-sandbox`、`--disable-dev-shm-usage`、X11 backendを明示します。さらにWebコンテンツ用rendererを2個へ制限し、拡張機能、background mode、過剰なglibc arenaを抑えます。Chrome UI用rendererが別に1個動く場合があります。ログイン互換性を損ねるsingle-process modeは使用しません。

Electron／Chromium製のGUIアプリ（Claude Desktop、ChatGPT、VS Codeなど）も、PRoot内ではsetuidの`chrome-sandbox`やuser namespaceが成立せず、起動時のsandbox初期化（zygote）で異常終了します。デスクトップsessionは`ELECTRON_DISABLE_SANDBOX=1`を設定し、Electronが`--no-sandbox`を付与するようにします。ただし`app.enableSandbox()`を呼ぶ硬化ビルド（ChatGPTアプリなど）はこの環境変数を無効化するため、確実な対処にはコマンドラインの`--no-sandbox`が必要です。そこでdesktop session起動のたびに`scan_and_fix_electron`が、インストール済みElectronアプリ（`.pak`＋asarで判定）を検出し、各`.desktop`の`Exec`に`--no-sandbox`を付けたユーザーレベルの上書きエントリを`~/.local/share/applications`へ生成します。手動で後から入れたアプリも次回起動で自動対応し、field code（`%U`等）は保持、LDFA同梱のChromeは除外します。PRootとこれらのアプリを強いセキュリティ境界として扱わないでください。

vendor curlインストーラ（Claude Codeの`install.sh`）は`~/.local/bin`へlauncherを置きます。LDFAは`~/.local/bin`と`~/.npm-global/bin`を`.profile`／`.bashrc`のPATHへ追加し、ゲスト自身の`curl`も導入します。`.profile`も`.bashrc`も読まない**fish**をログインシェルにしている場合に備え、同じPATHと`ELECTRON_DISABLE_SANDBOX`を`/etc/fish/conf.d/00-ldfa.fish`にも書き出します。これによりbash・fishのいずれでも、npm版・curl版のCLIとElectronアプリが動作します。

音声は表示backendと独立しており、app-privateなbridge socketからTermux側
PulseAudioのOpenSL ES／AAudio sinkへ送ります。socketは`$PREFIX/var/run/ldfa-pulse-bridge`
（`0700`）に置き、各guest loginへ明示的な`--bind`でguestの`/tmp/ldfa-pulse/native`へ
mapします。`--shared-tmp`が共有する`$PREFIX/tmp`のPRoot teardown churnからsocketを
隔離するためで、匿名TCP listenerは作りません。PRootはSHM／memfdをguest境界越しに
渡せないため、client／daemon両方でshared memoryを無効化し、再生streamがsocket transportで
確実にsinkへ届くようにします。Debianの設定は`/etc/pulse/client.conf.d`と
`/etc/alsa/conf.d`のLDFA専用drop-inへ置き、ユーザー固有のPulse／ALSA設定を上書き
しません。bridge失敗時は`audio_ready=0`を記録してGUIを無音で継続します。エミュレータ
（API 35）では実Debian 12環境でDebian→OpenSL ES sinkへの再生streamがRUNNINGになることを
確認済みです。本体speaker／Bluetoothの可聴出力とpanel操作は実機で最終確認します。

Androidのソフトウェアキーボードを開いたときは、X11画面をキーボード上の可視領域へ一時的にリサイズします。描画viewportだけを切り取る経路で一部GPUが黒いframeを表示する問題を避けるためで、既存インストールにも一度だけ安全側の設定を移行します。キーボードを閉じると元の画面サイズへ戻ります。

別アプリから復帰してAndroid Surfaceが交換された場合は、X11 root pixmap全体へdamageを発行し、新しいSurfaceへ既存画面を再presentします。Androidがmain processだけを回収した場合は、別processのX11 serviceについて世代UUID、PID、Unix socket、lock ownerを再検証し、残っているXorgやChromeを破棄せずviewerのBinder transportを再接続します。

Androidは`system_low_memory=false`でも、同一UID配下のPRoot子プロセスを個別に整理する場合があります。LDFAはPRootで不安定なICE lockを必要とする`xfce4-session`の代わりに、`xfsettingsd`、`xfwm4`、`xfce4-panel`、`xfdesktop`をsupervisorの直接の子として管理します。supervisorはBashのjob終了イベントを待ち、定常時に`ps`、`xset`、`sleep`を生成しません。終了イベントが来たときは4要素を一度に再確認して不足分を並列起動し、Chromeの異常終了markerがあれば前回セッションを復元します。

viewerが前面にある間の定期heartbeatは、確認済みX11 serviceの状態だけを軽量に返し、PRoot commandを増やしません。Activityが履歴や別アプリから復帰した時は、まず同一UIDの`/proc`を子processを作らず検査します。現在のcontainerに属するsupervisor、その直接の子であるXFCE 4要素、Chrome markerとbrowser本体が正常なら即時に終了します。要素の交換中は`/proc`だけで最大3秒待ち、欠落が継続する場合だけinstalled controllerで実際のPanel／Desktop windowとChrome／XFCEを復旧します。Surface再作成はこのprocess検査と独立してX11 root pixmap全体を再描画します。

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

Debug APKは`app/build/outputs/apk/debug/app-debug.apk`に生成され、GitHub Actionsでは
`LDFA-v0.9.0-debug-apk`として保存されます。2026-08-23の音声修正はまだ未commitのため、
その修正を含むcommitがpushされてCIに成功するまでは、既存の`main` artifactを音声修正版と
みなさないでください。

## ライセンス

統合版全体は`GPL-3.0-only`です。Termux、Termux:X11、Debianおよび個別コンポーネントの通知は`THIRD_PARTY_NOTICES.md`と各vendorサブモジュール内のライセンスファイルを参照してください。
