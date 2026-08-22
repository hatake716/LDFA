# インストール手順

## 1. 事前確認

LDFAはTermuxの固定プレフィックスを使うためapplication IDが`com.termux`です。署名が異なる公式Termuxとは同時にインストールできません。既存Termuxに必要なデータがある場合は、共有ストレージへバックアップしてからLDFAをインストールしてください。外部Termux:X11と外部VNCクライアントは通常不要です。

Debian環境1つにつき3〜5GB以上の空き容量と、初回セットアップ時のインターネット接続を推奨します。

## 2. APKを用意

GitHub Actionsの`LDFA-v0.9.0-debug-apk`成果物、またはローカルビルドしたAPKを使用します。

```bash
git clone --recurse-submodules https://github.com/hatake716/LDFA.git
cd LDFA
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

ホーム画面の追加ボタンから環境名を入力します。proot-distroのDebian 12（Bookworm）rootfs、XFCE、日本語locale、Noto CJK、Fcitx5/Mozc、公式Google Chrome stable、sudo、X11診断ツールを自動設定します。rootfsはPRoot互換性と再現性のため`debian:12`へ固定しています。インストールはtmux内で継続し、画面にはリアルタイムログを表示します。

Google ChromeはGoogle公式の`amd64`／`arm64`パッケージを環境作成時に取得します。既存環境では、アプリ更新後の最初のデスクトップ起動前に未導入かどうかを確認し、必要な場合だけ追加します。そのため初回起動や更新直後はデスクトップ表示まで数分かかることがあります。

Chrome本体と依存パッケージの追加使用量は約460MBです。

## 5. Debian XFCEを起動

環境カードの「Debian XFCEを開く」を選びます。LDFAは次を順に確認します。

起動中は「デスクトップが表示されるまで少し時間がかかる」旨を管理画面に表示します。表示Activityへ切り替わるまで画面を閉じずに待ってください。

1. `com.termux:x11` Foreground Serviceと世代UUID
2. Xorg PID、`:1` Unix socket、`xset q`
3. 表示Activityへのdirect Binder接続とX connection FD
4. Android SurfaceとEGL rendererのREADY状態
5. `xrefresh`後のsuccessful presentation serial増分
6. Debian worker、`xfce4-session`、`xfwm4`、EWMH root property
7. XFCE起動後の2回目のpresentation serial増分

native通常描画が利用できない場合はlegacy描画を試し、対応可能なnative経路がなければ`DISPLAY=:2`のloopback VNCへ自動で切り替えます。

Google ChromeはAndroid PRootの制約に合わせ、一般ユーザー`desktop`から`--no-sandbox`付きの専用ランチャーで起動します。PRootとChromeを強いセキュリティ境界として扱わないでください。初回起動時にはGoogleの利用規約確認が表示されます。

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

環境メニューからDebian/XFCEログと表示serverログを確認できます。ADBでは次が有用です。

```bash
adb shell pidof com.termux:x11
adb logcat -s LorieNative gles-renderer MainActivity
```

Androidのバッテリー設定は「制限なし」を推奨します。処理が中断した場合は設定またはツール画面の自動修復を実行してください。保存済み共有ファイルは自動修復では削除しません。
