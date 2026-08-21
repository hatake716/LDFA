# インストール手順

## 1. 事前確認

統合APKは公式Termuxの固定プレフィックスを使用するため、Android上のアプリIDは`com.termux`です。端末に公式Termuxがインストールされている場合、署名が異なるため同時にインストールできません。

公式Termuxを使用中の場合は、必要なファイルを`/sdcard`などの共有ストレージへ退避したうえでアンインストールします。公式Termuxの内部データ、既存PRoot環境、旧版Linux Desktopが作成したコンテナは、新しい統合APKへ自動移行されません。

外部のTermux:X11も不要です。混同を避けるため削除を推奨します。

## 2. APKをインストール

GitHub Actionsの`linux-desktop-ubuntu-xfce-unified-debug-apk`成果物、またはローカルビルドしたAPKをインストールします。

```bash
git clone --recurse-submodules https://github.com/hatake716/Linux-Desktop-for-Android.git
cd Linux-Desktop-for-Android
bash ./gradlew assembleDebug
```

生成先:

```text
app/build/outputs/apk/debug/app-debug.apk
```

ADBからインストールする場合:

```bash
adb install -r app-debug.apk
```

## 3. 初回セットアップ

アプリの案内に従い、次の3段階を実行します。

1. **内蔵ターミナルを展開**
   - 公式Termuxブートストラップをアプリ内部へ展開します。
2. **共有ストレージを許可**
   - Android側ファイルをUbuntuの`/mnt/android`から利用できるようにします。
3. **Ubuntu実行基盤を自動準備**
   - tmux、PRoot Distro、PulseAudioなどを導入します。
   - X11 repositoryと`xkeyboard-config`を準備します。
   - `/system/bin/app_process`とインストール済みAPKを確認します。

外部アプリへの切り替えや手動コマンド貼り付けは不要です。

## 4. Ubuntu XFCE環境を作成

1. デスクトップ画面右下の`+`を選択
2. 環境名を入力
3. `インストール`を選択

Ubuntu 24.04、XFCE、日本語環境、Fcitx5/Mozc、sudo、X11接続確認用`xset`を自動設定します。3〜5GB以上の空き容量を推奨します。

インストール処理はtmux内で継続し、進捗バーの下にリアルタイムログが表示されます。

## 5. Ubuntu XFCEを起動

環境カードの`起動`を選択します。

v0.4.0では次の順番で起動します。

1. `ldfa-x11`がX11依存関係を確認
2. `/system/bin/app_process`から`com.termux.x11.CmdEntryPoint`を起動
3. `libXlorie`がX server`:1`を開始
4. X11表示Activityを開いてBinder接続を確認
5. Ubuntuを`proot-distro --shared-tmp`で接続
6. Ubuntu内部から`DISPLAY=:1 xset q`を実行
7. 接続成功後にXFCEを起動

ソケットファイルが存在するだけでは成功扱いしません。

## 6. 日本語環境

Ubuntu XFCEへ次を自動設定します。

- `ja_JP.UTF-8`
- Noto CJK / Noto Color Emoji
- Fcitx5
- Mozc
- 日本語キーボードレイアウト
- GTK／Qt向けFcitx環境変数

入力切替はFcitx5の設定とAndroidのIME・物理キーボード構成に依存します。

## 7. sudo

`sudo`を標準インストールし、既定の`desktop`ユーザーへパスワードなしのsudo権限を設定します。

```bash
sudo apt update
sudo apt install <package>
```

## 8. Android共有フォルダ

```text
Android: /sdcard/LinuxDesktop/<environment-id>
Ubuntu:  /mnt/android
XFCE:    /home/desktop/Desktop/Android共有
```

環境を削除するときは、Android共有フォルダを残すか同時に削除するかを選択できます。

## 9. ログ確認

アプリの環境メニューからログを開くと、Ubuntu/XFCEログとX11ログの両方を確認できます。

X11専用ログ:

```text
~/.local/share/linux-desktop-for-android/logs/x11-server.log
```

ADBからAndroid側を確認する場合:

```bash
adb logcat -s CmdEntryPoint LorieNative MainActivity LorieBroadcastReceiver
```

## 10. 安定動作

Androidのアプリ設定で、Linux Desktopのバッテリー使用を`制限なし`に設定することを推奨します。処理が中断した場合は、設定画面の`中断した処理を修復`を選択し、環境カードのメニューからログを確認します。
