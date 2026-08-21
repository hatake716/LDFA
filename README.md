# LDFA — Linux Desktop for Android

**LDFA** は、Androidスマートフォン／タブレットをLinux PCとして使うための入口となるAndroidアプリです。

Android上のUbuntu 24.04 PRootコンテナへ **XFCE、日本語ロケール、日本語フォント、Fcitx5 + Mozc、sudo** を構築し、アプリに内蔵したターミナルとX11サーバーを通してLinuxデスクトップをGUI操作できます。通常は外部Termux、外部Termux:X11、外部VNCクライアントを必要としません。

現在の開発版は **v0.9.0** です。

## できること

- Android上にUbuntu 24.04環境を作成・保存
- XFCEデスクトップをアプリ内で表示
- タッチ、マウス、物理キーボードでLinux GUIを操作
- 複数のUbuntu環境を作成・切り替え
- 日本語ロケール、Noto CJK、Fcitx5 + Mozcを自動設定
- 一般ユーザー `desktop` とパスワードなし `sudo` を設定
- Ubuntuの `apt` でARM64対応Linuxアプリを追加
- Android共有ストレージをUbuntuの `/mnt/android` へ接続
- 内蔵ターミナルからUbuntuを保守
- Ubuntu／XFCE／X11のインストール・診断ログをアプリ内表示
- native X11が使えない端末では互換VNC表示へ自動切り替え
- Foreground Service、WakeLock、heartbeatでセッションを監視・復旧

## 初めて使うとき

LDFAの初回画面では、1つの主ボタンが次に必要な操作を順番に案内します。

1. 内蔵ターミナルランタイムを展開
2. 内蔵X11サーバーを確認
3. Android共有ストレージへのアクセスを許可
4. Ubuntu、XFCE、日本語環境を自動構築

準備が完了したら「Ubuntu環境を作成」を押します。インストール後は「Ubuntu XFCEを開く」だけでLinuxデスクトップを起動できます。

Ubuntu環境1つにつき、最低3〜5GB程度の空き容量を推奨します。初回セットアップにはインターネット接続が必要です。

## UI

管理画面はJetpack ComposeとMaterial 3で構築しています。

- Android 12以降では端末のDynamic Colorへ対応
- ライト／ダークテーマへ対応
- セットアップの進捗を4段階で表示
- ホーム画面からUbuntu作成・起動・停止・修復・削除
- 設定画面で内蔵ターミナル、X11、共有ストレージ、Ubuntu基盤の状態を確認
- インストール中のリアルタイムログを表示

## 動作要件

- Android 8.0以降
- ARM64端末を推奨
- Ubuntu + XFCE用に数GB以上の空き容量
- 初回セットアップ時のインターネット接続
- Android共有ストレージへのアクセス許可
- 互換VNC表示を使う場合はAndroid System WebView

## インストール前の重要事項

内蔵Termuxランタイムは次の固定パスを使います。

```text
/data/data/com.termux/files/usr
```

この互換性を維持するため、現在のapplicationIdは `com.termux` です。署名が異なる公式Termuxとは同時インストールできません。既存Termuxに必要なデータがある場合は、LDFAをインストールする前にバックアップしてください。

## APKのインストール

GitHub Actionsまたはローカルビルドで生成したAPKを使用します。

```bash
adb install -r app-debug.apk
```

署名の異なる既存 `com.termux` がある場合は上書きできません。

## 基本操作

### Ubuntu環境を作成

ホーム画面の「Ubuntu環境を作成」または右下の追加ボタンを押し、表示名を入力します。Ubuntu 24.04、XFCE、日本語環境が自動で構築され、処理中はリアルタイムログを確認できます。

### Linuxデスクトップを開く

環境カードの「Ubuntu XFCEを開く」を押します。

起動時は次を順に検証します。

1. 内蔵X11 Foreground Service
2. X11 Unix socket
3. Ubuntu内部からの `xset q`
4. Binder接続
5. Android Surface
6. 実描画フレーム
7. XFCEセッション

native X11が正常に描画できない場合のみ、legacy描画、互換VNCの順に自動で切り替えます。

### 停止・再表示

実行中の環境はカードから停止できます。画面だけ閉じた場合は、同じ環境を再度開くと現在のセッションへ再接続します。

### ターミナル

設定画面の「ターミナルを開く」から、内蔵ターミナルを起動できます。

UbuntuへLinuxアプリを追加する例:

```bash
sudo apt update
sudo apt install <package>
```

## 日本語入力

Ubuntu側では次の環境変数を設定し、XFCEセッション開始時にFcitx5を自動起動します。

```text
GTK_IM_MODULE=fcitx
QT_IM_MODULE=fcitx
XMODIFIERS=@im=fcitx
```

日本語入力エンジンはMozcです。

## Androidとのファイル共有

UbuntuからAndroid共有ストレージへ次のパスでアクセスできます。

```text
/mnt/android
```

## X11アーキテクチャ

旧 `app_process` / loader APK / custom `PathClassLoader` 経路は使用しません。

```text
Android UI
   |
   v
LinuxDesktopRepository
   |
   v
EmbeddedX11ServiceController
   |
   +--> EmbeddedX11ServerService (com.termux:x11)
   |       |
   |       v
   |    libXlorie / Xorg :1
   |       |
   |       +---- Unix socket ---- Ubuntu PRoot / XFCE
   |       |
   |       +---- ICmdEntryInterface Binder
   |                         |
   +-------------------------+
                             v
                    Termux:X11 MainActivity
                             |
                             v
                          LorieView
                             |
                             v
                         Android Surface
```

X11サーバーは管理UIとは別の `com.termux:x11` プロセスでForeground Serviceとして動作します。native側のクラッシュが管理UIを巻き込まない構成です。

表示ActivityはServiceへ直接 `bindService()` し、`ICmdEntryInterface` BinderからX接続FDを受け取ります。TCP 7892や通常接続用broadcastには依存しません。

## 描画フォールバック

```text
native :1 / 通常描画
        |
        | failure
        v
native :1 / legacy描画
        |
        | failure
        v
互換VNC :2 / noVNC
```

native Termux:X11は `DISPLAY=:1`、互換VNCは `DISPLAY=:2` / RFB 5902を使います。表示番号を分離し、fallback時にX11 socketやlockが競合しないようにしています。

互換表示は次のloopbackアドレスだけで待ち受けます。

```text
RFB    127.0.0.1:5902
noVNC  127.0.0.1:6080
```

X11 TCPは `-nolisten tcp` で無効化します。

## 停止処理の安全性

native X11を停止するときは、最初に通常の `stopService()` を使います。

停止できない場合も、`.X1-lock` のPIDに対応する `/proc/<pid>/cmdline` が **`com.termux:x11` と完全一致すると確認できた場合だけ** SIGTERM／SIGKILLを使います。識別できないプロセスは停止しません。

## ログ

```text
Ubuntu / XFCE
~/.local/share/linux-desktop-for-android/logs/<環境ID>.log

Native X11
~/.local/share/linux-desktop-for-android/logs/x11-server.log

Compatibility VNC
~/.local/share/linux-desktop-for-android/logs/vnc-server.log
```

ADBでnative X11プロセスを確認する例:

```bash
adb shell pidof com.termux:x11
adb logcat -s LorieNative gles-renderer MainActivity
```

## ソースからビルド

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

生成先:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`gradlew` はGradle Wrapper JARがない場合、Gradle公開のSHA-256と照合した上でGradle 8.13用JARを取得します。

## CIで検証する内容

- Ubuntu host controllerの構文・統合テスト
- native X11 `:1` / 互換VNC `:2` の分離
- Android-owned X11 lifecycle
- `app_process` / loader APKが復活していないこと
- `:x11` Foreground Serviceのmanifest設定
- direct Binder接続
- normal / legacy / VNC fallback
- Surfaceとrendered-frame probe
- Kotlin unit tests
- Android lint
- `libXlorie.so` を含むDebug APK生成

CIだけではAndroid実機のSurface表示、タッチ、マウス、物理キーボード、日本語入力を完全には検証できません。実機検証が完了するまでは開発PRをDraftとして扱います。

## ライセンス

LDFAは **GNU GPL version 3 only** で提供され、明示・黙示を問わず無保証です。詳細は `LICENSE` と `THIRD_PARTY_NOTICES.md` を確認してください。

主な上流プロジェクト:

- Termux App
- Termux:X11
- Ubuntu / proot-distro
- XFCE
- TigerVNC / noVNC
