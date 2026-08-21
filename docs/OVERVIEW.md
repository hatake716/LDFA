# Linux Desktop for Android

Androidスマートフォン上に、日本語入力対応のUbuntu 24.04 + XFCE環境を構築・管理する単一APKです。ターミナル実行基盤にはTermux、表示サーバーにはTermux:X11のソースを統合しています。

## 実装済み機能

- Ubuntuベースの複数PRoot環境
- デスクトップ環境をXFCEに限定
- Termuxターミナル、サービス、公式ブートストラップの内蔵
- Termux:X11表示Activityと`libXlorie`の内蔵
- `/system/bin/app_process`による公式Termux:X11に近いX server起動
- Ubuntu内部から`xset q`を使ったX11実接続検証
- `ja_JP.UTF-8`、Noto CJK、Fcitx5、Mozcの初期導入
- `sudo`と非rootの`desktop`ユーザー
- Android共有ストレージとUbuntu間の共有ディレクトリ
- インストール進捗バー直下のリアルタイムログ
- Ubuntu/XFCEとX11の分離ログ
- tmuxによるインストール・XFCE・X11セッション監視
- Androidフォアグラウンドサービス、部分WakeLock、Termux WakeLock
- 中断したインストール、XFCE、X11の自動復旧

## 単一APK

```text
Linux Desktop管理UI
+ Termuxランタイム／ターミナル
+ RunCommandService／TermuxService
+ Termux:X11表示Activity
+ Termux:X11 libXlorie
+ ldfa-x11 app_process controller
+ Ubuntu XFCE管理スクリプト
```

別途TermuxまたはTermux:X11をインストールする必要はありません。

公式Termuxの実行ファイルが固定パス`/data/data/com.termux/files/usr`を前提にするため、APKのアプリIDは`com.termux`です。そのため、公式Termuxとは共存できません。

## v0.4.0 X11

X serverはAndroid Service内で直接生成せず、内蔵Termuxから次の経路で起動します。

```text
ldfa-x11
  -> /system/bin/app_process
  -> com.termux.x11.CmdEntryPoint
  -> libXlorie
  -> DISPLAY=:1
```

Ubuntuは`proot-distro --shared-tmp`でX11 Unix socketを共有します。

X11起動時は次を検証します。

1. X11 tmuxセッションの生存
2. X11 Unix socket生成
3. Android X11 ActivityのBinder接続
4. Ubuntu内部から`DISPLAY=:1 xset q`が成功

これらを確認した後にXFCEを起動します。

## Ubuntu XFCE

Ubuntu環境へ次を導入します。

- XFCE、XFWM、XFCE Panel
- Thunar、XFCE Terminal、Mousepad、Ristretto
- `ja_JP.UTF-8`
- Noto CJK / Noto Color Emoji
- Fcitx5 / Mozc
- Mesaソフトウェアレンダリング
- `x11-xserver-utils`
- `sudo`

`desktop`ユーザーにはパスワードなしのsudo権限を設定します。

## ログ

```text
Ubuntu/XFCE:
~/.local/share/linux-desktop-for-android/logs/<id>.log

X11:
~/.local/share/linux-desktop-for-android/logs/x11-server.log
```

アプリのログ画面では両方をまとめて確認できます。

## 必要条件

- Android 8.0以上
- 3〜5GB以上の空き容量／Ubuntu環境
- インターネット接続
- 公式Termuxをアンインストールできること

## 共有フォルダ

- Android: `/sdcard/LinuxDesktop/<environment-id>`
- 内蔵Termux: `~/storage/shared/LinuxDesktop/<environment-id>`
- Ubuntu: `/mnt/android`
- XFCE: `/home/desktop/Desktop/Android共有`

## ビルド

```bash
git clone --recurse-submodules https://github.com/hatake716/Linux-Desktop-for-Android.git
cd Linux-Desktop-for-Android
bash ./gradlew testDebugUnitTest :app:lintDebug :termux-runtime:lintDebug :embedded-x11:lintDebug assembleDebug
```

Debug APKは`app/build/outputs/apk/debug/app-debug.apk`に生成され、GitHub Actionsでは`linux-desktop-ubuntu-xfce-unified-debug-apk`として保存されます。

## ライセンス

統合版全体は`GPL-3.0-only`です。Termux、Termux:X11、および個別コンポーネントの通知は`THIRD_PARTY_NOTICES.md`と各vendorサブモジュール内のライセンスファイルを参照してください。
