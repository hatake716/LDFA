# LDFA — Linux Desktop for Android

[![LDFA Android CI](https://github.com/hatake716/LDFA/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/hatake716/LDFA/actions/workflows/android.yml)
[![License: GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-blue.svg)](LICENSE)

**LDFA** は、Android端末の中にDebian 12（Bookworm）とXFCEデスクトップを構築し、1つのAndroidアプリ内で操作するためのプロジェクトです。

Termux互換ランタイム、X11サーバー、X11 viewer、ターミナル、Debian PRoot、XFCE、日本語入力環境をアプリに統合しています。通常利用では、外部Termux、外部Termux:X11、外部VNCクライアントを別々にインストールする必要はありません。

## 現在のステータス

| 項目 | 状態 |
| --- | --- |
| バージョン | `0.9.0` / versionCode `16` |
| リリース段階 | **プレリリース候補**。正式なGitHubプレリリースは実機受け入れ後に作成予定 |
| Linux環境 | Debian 12（Bookworm）+ XFCE |
| 通常表示 | 内蔵native X11、`DISPLAY=:1` |
| 最終フォールバック | TigerVNC + noVNC、`DISPLAY=:2` |
| ローカル／AVD検証 | clean build、155 unit tests、3 module lint、4 ABI APK、API 35 x86_64・4 KB page E2Eが成功 |
| 実機検証 | 物理ARM64端末で受け入れテスト中。ARM64 16 KB pageは未完了 |

現在の`main`はv0.9.0のソース候補です。実機検証が完了するまでは、日常データを置く唯一のLinux環境としてではなく、バックアップを取ったテスト環境として使用してください。

## 主な機能

- Android上に複数のDebian 12環境を作成、保存、切り替え
- XFCEデスクトップを内蔵X11 viewerへ直接表示
- タッチ、マウス、物理キーボード、ソフトウェアキーボードで操作
- 日本語ロケール、Noto CJK、日本語キーボード、Fcitx5 + Mozcを自動設定
- Google公式のGoogle Chrome stableを64-bit Debianへ自動導入
- 一般ユーザー`desktop`とパスワードなし`sudo`を構成
- Android共有ストレージをDebianの`/mnt/android`へ接続
- 内蔵ターミナルからDebianを保守
- 作成、起動、停止、修復、削除とログ表示をMaterial 3 UIへ統合
- native X11が利用できない場合にlegacy描画、互換VNCへ段階的にフォールバック
- Foreground Service、WakeLock、heartbeat、世代IDで実行中セッションを監視
- Surface再作成、バックグラウンド復帰、停止／再起動を考慮したX11 lifecycle

## 動作要件

- Android 8.0（API 26）以降
- 64-bit ARM端末を推奨
- Debian環境1つにつき、最低3〜5 GB程度の空き容量を推奨
- 初回セットアップ時の安定したインターネット接続
- Android共有ストレージへのアクセス許可
- 互換VNC表示を使用する場合はAndroid System WebView

APKには`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`のnativeライブラリを収録しています。ただし、Google Chrome公式Linuxパッケージの自動導入対象は`arm64`と`amd64`だけです。32-bit環境ではChromeを別ブラウザへ無断で置換せず、Debian／XFCEの構築だけを継続します。

## インストール前に必ず確認してください

### 公式Termuxとは同時インストールできません

内蔵Termuxランタイムは、互換性のため次の固定パスを使用します。

```text
/data/data/com.termux/files/usr
```

そのためLDFAのapplication IDは`com.termux`です。署名が異なる公式Termuxや別ビルドの`com.termux`とは同時インストールできません。

既存Termuxに必要なスクリプト、SSH鍵、パッケージ、ホームディレクトリがある場合は、LDFAをインストールする前に必ずバックアップしてください。署名の異なるアプリへ`adb install -r`で上書きすることはできません。

### PRootは完全なLinux仮想マシンではありません

DebianはAndroidカーネル上のPRootとして動作します。systemd、カーネル機能、デバイスアクセス、sandbox、低レベルsyscallの挙動は、通常のDebian PCや仮想マシンと異なります。

### Google Chromeのsandbox制約

Android PRoot内ではChrome本来のnamespace／setuid sandboxを確立できません。LDFAの専用ランチャーは、一般ユーザー`desktop`から次の互換オプションでChromeを起動します。

```text
--no-sandbox
--disable-dev-shm-usage
--ozone-platform=x11
--password-store=basic
```

通常のLinux版ChromeよりWebコンテンツの隔離が弱くなります。信頼できないサイト、拡張機能、ダウンロードファイルを扱う場合は、この制約を前提にしてください。詳細は[SECURITY.md](SECURITY.md)を参照してください。

## APKの入手方法

実機受け入れが完了するまでは正式なプレリリースを作成していません。

現在の候補APKは、[LDFA Android CI](https://github.com/hatake716/LDFA/actions/workflows/android.yml)の成功した`main` runから、`LDFA-v0.9.0-debug-apk` artifactとして取得できます。Actions artifactには保存期限があります。

実機検証完了後は、[GitHub Releases](https://github.com/hatake716/LDFA/releases)へv0.9.0プレリリースとしてAPKと検証情報を掲載する予定です。

ローカルにAPKがある場合のインストール例:

```bash
adb install -r LDFA-v0.9.0-debug.apk
```

端末やADBの設定によってtest APKとしての許可を求められる場合は、`-t`を追加します。

```bash
adb install -r -t LDFA-v0.9.0-debug.apk
```

## 初回セットアップ

LDFAの初回画面では、1つの主ボタンが次に必要な操作を順番に案内します。

1. 内蔵ターミナルランタイムを展開
2. 内蔵X11サーバーを確認
3. Android共有ストレージへのアクセスを許可
4. Debian環境の表示名を入力
5. Debian 12、XFCE、日本語環境、Google Chromeを自動構築

Debian、デスクトップパッケージ、ロケール、フォント、Mozc、Chromeを取得するため、初回構築には時間がかかります。処理中はアプリ内で進捗とログを確認できます。AndroidがLDFAをバックグラウンド制限しないよう、可能であればバッテリー設定を「制限なし」にしてください。

構築が完了すると、ホーム画面の環境カードに「Debian XFCEを開く」が表示されます。

## デスクトップを起動する

環境カードの「Debian XFCEを開く」を押します。

X11 service、Unix socket、Binder、Android Surface、EGL renderer、XFCEを順に準備するため、ボタンを押してからデスクトップが表示されるまで少し時間がかかります。起動中は次の注意を管理画面に表示します。

> デスクトップが表示されるまで少し時間がかかります。初回や更新直後は数分かかる場合があります。そのままお待ちください。

既存のDebian環境にGoogle Chromeがまだない場合は、アプリ更新後の最初の起動前にChromeを追加します。このときもネットワーク速度により数分かかる場合があります。

起動時はおおむね次の順でhealth checkを行います。

1. Android所有のX11 Foreground Service
2. X11 Unix socketとDebianからの`xset q`
3. viewer ActivityとBinder FD接続
4. Android SurfaceとEGL renderer
5. 成功したEGL presentationの増分
6. XFCE sessionとwindow manager
7. XFCE起動後の再描画

画面だけを閉じた場合、同じ環境を再度開くと実行中セッションへ再接続します。環境カードの「停止」を押すと、viewer、X11 server、XFCE、PRootの順で停止します。

## Google Chrome

ChromeのDEBをAPKへ固定収録するのではなく、Debian構築時にguest architectureを確認してGoogle公式のcurrent stable packageを取得します。

- `amd64`: `google-chrome-stable_current_amd64.deb`
- `arm64`: `google-chrome-stable_current_arm64.deb`

インストール前にDEBのPackage fieldが`google-chrome-stable`であり、Architecture fieldがguestと一致することを検証します。Chromeが作成する署名済みAPT sourceは保持されるため、Debian側のAPTから更新できます。

既存環境ではデスクトップ起動前に、Chrome本体、LDFA専用launcher、XFCE desktop entryを確認します。すべて揃っていればダウンロードは行いません。

Chrome本体と依存パッケージによる追加使用量はバージョンによって変わります。x86_64の動的検証では、Chrome packageの`Installed-Size`は約431 MiBでした。

Chromeの初回起動時には、Google Chromeの利用規約確認が表示されます。

## 日本語入力

Debian側では次の環境変数を設定し、XFCE session開始時にFcitx5を自動起動します。

```text
GTK_IM_MODULE=fcitx
QT_IM_MODULE=fcitx
XMODIFIERS=@im=fcitx
```

日本語入力エンジンはMozcです。API 35 x86_64 AVDでは、`nihongo`から候補「日本語」を選び、XFCE terminalへ確定できることまで確認しています。

## Androidとのファイル共有

環境ごとのAndroid共有ディレクトリを、Debian内の次のパスへ接続します。

```text
/mnt/android
```

XFCEのデスクトップには「Android共有」へのショートカットを作成します。共有ストレージはバックアップやAndroidアプリとの受け渡しに利用できますが、重要データは別の場所にも保存してください。

## 表示アーキテクチャ

通常表示はAndroid所有のX11 serviceと、Termux:X11由来のviewerをアプリへ埋め込んだ構成です。旧`app_process`、loader APK、custom `PathClassLoader`、TCP 7892による通常接続は使用しません。

```text
LDFA管理UI
   |
   v
LinuxDesktopRepository
   |
   +--> EmbeddedX11ServerService (process: com.termux:x11)
   |          |
   |          +--> libXlorie / Xorg :1
   |          |          |
   |          |          +---- Unix socket ---- Debian PRoot / XFCE
   |          |
   |          +---- ICmdEntryInterface Binder / X connection FD
   |                                      |
   +--------------------------------------+
                                          v
                                Termux:X11 MainActivity
                                          |
                                          v
                                      LorieView
                                          |
                                          v
                                    Android Surface
```

Xorg serverは管理UIとは別の`com.termux:x11`プロセスで動作します。service起動ごとにUUID世代とPID markerを照合し、古い世代が完全に終了してから次の世代を開始します。

表示Activityとnative EGL rendererは現在main processに含まれます。mutex、EGL初期化、JNI ABI、Binder/FD世代競合、Surface再作成、teardownに対するhardeningは実装済みですが、vendor EGL driver内部の永久hangやnative SIGSEGVを管理UIから完全隔離するには、viewerの別process化が今後も必要です。

詳細は[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)を参照してください。

## 描画フォールバック

```text
native X11 :1 / 通常描画
        |
        | classified failure
        v
native X11 :1 / legacy描画
        |
        | failure after clean teardown
        v
TigerVNC :2 / noVNC viewer
```

native X11は`DISPLAY=:1`、互換VNCは`DISPLAY=:2`、RFBは`127.0.0.1:5902`、noVNCは`127.0.0.1:6080`を使用します。X11 TCPは`-nolisten tcp`で無効化しています。

normal、legacy、VNCを同時起動せず、切り替え前に前のviewer、server、socket、lock、workerを停止します。

## 内蔵ターミナルとログ

設定画面の「ターミナルを開く」から内蔵ターミナルを起動できます。設定画面の死んでいた「X11ディスプレイを開く」は削除済みで、実行中デスクトップを表示する機能はツール画面側に残しています。

Debianへパッケージを追加する例:

```bash
sudo apt update
sudo apt install <package>
```

主なログ:

```text
Debian / XFCE
~/.local/share/linux-desktop-for-android/logs/<環境ID>.log

Native X11
~/.local/share/linux-desktop-for-android/logs/x11-server.log

Compatibility VNC
~/.local/share/linux-desktop-for-android/logs/vnc-server.log
```

ADBから確認する例:

```bash
adb shell pidof com.termux
adb shell pidof com.termux:x11
adb logcat -s LorieNative gles-renderer MainActivity
```

## 現在の検証状況

2026-08-22時点のv0.9.0候補に対する結果です。

| 検証項目 | 結果 |
| --- | --- |
| host controller static/integration gates | PASS |
| clean Gradle build | PASS、377 tasks |
| unit tests | 155 / 155 PASS |
| app / termux-runtime / embedded-x11 lint | PASS、error 0 |
| `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64` build | PASS |
| APK v2 signature | PASS、debug certificate |
| APK zipalign 16 KB check | PASS |
| arm64-v8a / x86_64 `.so` PT_LOAD alignment | 全対象`0x4000`以上 |
| API 35 x86_64・4 KB AVDでDebian 12 clean install | PASS |
| native X11、XFCE、Surface再作成、background復帰 | PASS |
| Fcitx5 + Mozc日本語確定 | PASS |
| Android共有ストレージ往復 | PASS |
| Google Chromeの起動とHTTPSページ描画 | PASS |
| native failureからVNC `:2`へのfallback | PASS |
| stop後のX11/XFCE/PRoot/socket cleanup | PASS |
| 物理ARM64端末 | **受け入れテスト中** |
| ARM64 16 KB page端末 | **未検証** |
| “Don't keep activities”とlow-memory process recreation | **未完了** |

APKの16 KB alignment成功は、Debian userlandとXFCEを含むARM64 16 KB実機E2Eの成功を意味しません。この二つは別の受け入れ条件です。

## v0.9.0プレリリース前の実機確認項目

物理ARM64端末で少なくとも次を確認してから、GitHubプレリリースを作成します。

1. 既存の必要なTermuxデータをバックアップする。
2. LDFAをclean installし、Debian 12の構築を完了する。
3. 「Debian XFCEを開く」からnative X11でデスクトップを表示する。
4. タッチ、スクロール、ソフトウェアキーボード、可能なら物理マウス／キーボードを確認する。
5. Fcitx5 + Mozcで日本語を入力・確定する。
6. Google Chromeを起動し、HTTPSページを表示する。
7. `/mnt/android`でAndroidとのファイル往復を確認する。
8. 縦横回転、Homeからの復帰、画面消灯復帰を確認する。
9. 停止、再起動、画面の再表示を複数回行う。
10. 停止後にデスクトップやChromeのプロセスが残らないことを確認する。
11. 16 KB page端末の場合は、Debian loginとXFCE表示まで別途確認する。

問題が発生した場合は、端末機種、Androidバージョン、ABI、page size、操作手順、画面、LDFAログ、`adb logcat`を添えてください。

## ソースからビルド

### 必要なtoolchain

- JDK 17
- Gradle 8.13
- Android SDK platform 36
- Android build-tools 35.0.0 / 36.0.0
- Android NDK 29.0.14206865
- CMake 3.22.1
- Python 3
- Git submodules

### cloneと検証

```bash
git clone --recurse-submodules https://github.com/hatake716/LDFA.git
cd LDFA

bash ./scripts/check-host-script.sh
bash ./scripts/test-host-controller.sh
bash ./scripts/check-x11-controller.sh

bash ./gradlew --no-daemon --console=plain \
  clean \
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

`gradlew`はGradle Wrapper JARがない場合、取得したJARをリポジトリ内のSHA-256 contractと照合します。CIはさらに、全ABIの`libXlorie.so`、必須JNI symbol、manifest、obsolete loader不在、16 KB zipalign、APK署名を検証します。

## ドキュメント

- [インストールと初回セットアップ](docs/INSTALLATION.md)
- [機能と構成の概要](docs/OVERVIEW.md)
- [X11／lifecycleアーキテクチャ](docs/ARCHITECTURE.md)
- [テスト手順](docs/TESTING.md)
- [セキュリティ上の注意](SECURITY.md)
- [第三者ソフトウェアとライセンス](THIRD_PARTY_NOTICES.md)
- [今回の起動修正と残課題の引き継ぎ](HANDOVER.md)

## ライセンス

LDFAは**GNU GPL version 3 only**（`GPL-3.0-only`）で提供され、明示・黙示を問わず無保証です。詳細は[LICENSE](LICENSE)と[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)を確認してください。

主な上流プロジェクト:

- Termux App
- Termux:X11
- Debian / proot-distro
- XFCE
- Fcitx5 / Mozc
- TigerVNC / noVNC
- Google Chrome（実行時にGoogle公式パッケージを取得）
