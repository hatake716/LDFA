# インストール手順

## 1. 事前確認

LDFAはTermuxの固定プレフィックスを使うためapplication IDが`com.termux`です。署名が異なる公式Termuxとは同時にインストールできません。既存Termuxに必要なデータがある場合は、共有ストレージへバックアップしてからLDFAをインストールしてください。外部Termux:X11と外部VNCクライアントは通常不要です。

Debian環境1つにつき3〜5GB以上の空き容量と、初回セットアップ時のインターネット接続を推奨します。

## 2. APKを用意

2026-08-23時点の音声修正版は、ローカルの
`app/build/outputs/apk/debug/LDFA-v0.9.0-audio-fix-debug.apk`（SHA-256
`561907b3ad13158f43c78057061715c16d4ed5ceedb6d5f3044e9228b8132fe2`）を使用します。
音声修正がcommit／pushされる前のGitHub Actions artifactは旧版です。公開後は、音声修正
commitを含む成功runの`LDFA-v0.9.0-debug-apk`だけを使用してください。

ソースから作る場合は、音声修正を含むworking treeのrootで実行します。修正commitが
公開されるまでは`origin/main`を新しくcloneしても今回のAPKにはなりません。

```bash
cd /home/takeshi/StudioProjects/LDFA-fix
bash ./gradlew assembleDebug
```

生成先は`app/build/outputs/apk/debug/app-debug.apk`です。ADBからインストールする場合:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. 初回セットアップ

アプリの案内に従って次を準備します。

1. 内蔵Termux runtimeを展開
2. 内蔵X11機能を確認
3. Android共有ストレージへのアクセスを許可
4. tmux、proot-distroなどDebian実行基盤を準備

外部アプリへの切り替えや手動コマンド貼り付けは不要です。

## 4. Debian XFCE環境を作成

ホーム画面の追加ボタンから環境名を入力します。proot-distroのDebian 12（Bookworm）rootfs、XFCE、日本語locale、Noto CJK、Fcitx5/Mozc、公式Google Chrome stable、Pulse／ALSA音声client、sudo、X11診断ツールを自動設定します。rootfsはPRoot互換性と再現性のため`debian:12`へ固定しています。インストールはtmux内で継続し、画面にはリアルタイムログを表示します。

Google ChromeはGoogle公式の`amd64`／`arm64`パッケージを環境作成時に取得します。既存環境では、アプリ更新後の最初のデスクトップ起動前にChromeと音声clientを確認し、必要な場合だけ追加します。そのため初回起動や更新直後はデスクトップ表示まで数分かかることがあります。実行中の旧sessionがある状態でAPKを上書きした場合は、環境を一度停止してから開き直してください。

Chrome本体と依存パッケージの追加使用量は約460MBです。

## 5. Debian XFCEを起動

環境カードの「Debian XFCEを開く」を選びます。LDFAは次を順に確認します。

起動中は「デスクトップが表示されるまで少し時間がかかる」旨を管理画面に表示します。表示Activityへ切り替わるまで画面を閉じずに待ってください。

1. `com.termux:x11` Foreground Serviceと世代UUID
2. Xorg PID、`:1` Unix socket、`xset q`
3. 表示Activityへのdirect Binder接続とX connection FD
4. Android SurfaceとEGL rendererのREADY状態
5. `xrefresh`後のsuccessful presentation serial増分
6. （best-effort）app-private PulseAudio socketとAndroid実出力sink。失敗時は記録してGUIを継続
7. Debian worker、`xfsettingsd`、`xfwm4`、`xfce4-panel`、`xfdesktop`と実際のEWMHウィンドウ
8. XFCE起動後の2回目のpresentation serial増分

native通常描画が利用できない場合はlegacy描画を試し、対応可能なnative経路がなければ`DISPLAY=:2`のloopback VNCへ自動で切り替えます。

Google ChromeはAndroid PRootの制約に合わせ、一般ユーザー`desktop`から`--no-sandbox`付きの専用ランチャーで起動します。Android、ソフトウェアキーボード、XFCEの余裕を残すため、Webコンテンツ用rendererを最大2個に制限し、拡張機能とbackground modeを無効化し、glibcのarena数を抑えます。Chrome UI用rendererが別に1個動く場合があります。既存環境の古いlauncherもデスクトップ起動前に自動更新します。PRootとChromeを強いセキュリティ境界として扱わないでください。初回起動時にはGoogleの利用規約確認が表示されます。

Electron／Chromium製のGUIアプリ（Claude Desktopなど）は、PRoot内でsetuidの`chrome-sandbox`もuser namespaceも成立せず、そのままでは起動時のsandbox初期化で異常終了します。デスクトップsessionと`desktop`ユーザーのシェル設定に`ELECTRON_DISABLE_SANDBOX=1`を設定し、Electronが自動で`--no-sandbox`を付けて起動できるようにします。

Debianの音声は`PULSE_SERVER=unix:/tmp/ldfa-pulse/native`から内蔵runtimeへ送る構成です。
起動時に専用socketとAndroid実sinkを確認しますが、音声はGUIの必須gateではありません。
失敗時は`audio_ready=0`とhost logを残し、GUIを無音で継続します。Unix bridgeの実装と
APK package検査は完了していますが、本体speaker、Bluetooth、イヤホンの可聴出力と
XFCE panel操作は修正版APKで実機再確認が必要です。再生専用機能のためマイク権限は
要求しません。

PRootでは`xfce4-session`のICE lockが安定しないため、LDFAは`xfsettingsd`、`xfwm4`、Panel、Desktopを直接起動します。常駐supervisorは外部コマンドを定期実行せず、子プロセスの終了イベントを待ちます。AndroidがChromeまたはいずれかのXFCE要素だけを終了した場合は、XFCEを再構成し、異常終了したChromeの前回セッションを自動復元します。

履歴やGmailから戻る通常経路では、現在のcontainerとXFCE／ChromeをAndroidの`/proc`だけで照合し、PRootを追加起動しません。Chrome本体までAndroidに終了された場合は自動再起動して前回sessionを復元しますが、これは既存processを再表示する通常復帰とは異なり、Chrome内容が戻るまで数秒かかることがあります。

## 6. 日本語入力とsudo

Debian側では次を自動設定します。

- `ja_JP.UTF-8`
- Noto CJK / Noto Color Emoji
- Fcitx5 + Mozc
- `GTK_IM_MODULE=fcitx`
- `QT_IM_MODULE=fcitx`
- `XMODIFIERS=@im=fcitx`
- 日本語キーボードlayout
- `desktop`ユーザーのパスワードなしsudo

Linuxパッケージを追加する例:

```bash
sudo apt update
sudo apt install <package>
```

## 7. Android共有フォルダ

```text
Android: /sdcard/LinuxDesktop/<environment-id>
Debian:  /mnt/android
XFCE:    /home/desktop/Desktop/Android共有
```

環境削除時に、Android共有フォルダを残すか同時に削除するかを選択できます。

## 8. ログと復旧

環境メニューからAndroid process/memory診断、Debian/XFCEログ、表示serverログを確認できます。Android 11以降では、前回のmain／`:x11` processがlow-memory、Java crash、native crash、signalのどれで終了したかを`ApplicationExitInfo`から保存します。同一アプリUIDで動くChrome／Debian／XFCEを含む現在のRSSとswapも集計します。ADBでは次が有用です。

```bash
adb shell pidof com.termux:x11
adb logcat -s LorieNative gles-renderer MainActivity
```

Androidのバッテリー設定は「制限なし」を推奨します。処理が中断した場合は設定またはツール画面の自動修復を実行してください。保存済み共有ファイルは自動修復では削除しません。

Gmailなど別アプリから戻る通常操作では、履歴画面でLDFAを選んでから1〜2秒以内にChrome／XFCE全体が表示されることを確認してください。Androidが子プロセスを終了していた場合は再起動分だけ長くなりますが、マウスポインタだけの黒画面に留まらず自動復旧します。
