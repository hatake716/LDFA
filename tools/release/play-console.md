# Google Play 提出資料 — LDFA 1.2.0

対象：`com.hatake716.linuxdesktop` / versionCode `21` / targetSdk `36`。
新しい成果物・画像・提出文面は、ローカルの`release-assets/v1.2.0/`にまとめます。
旧`release-assets/LDFA-v1.1.0-release.aab`とは区別してください。

## ストア掲載情報（日本語）

**アプリ名**

```text
LDFA - Linuxデスクトップ
```

**簡単な説明**

```text
AndroidにDebianとXFCEの作業環境を。日本語入力、ブラウザー、バックアップに対応。root化不要。
```

**詳しい説明**

```text
Androidに、Linuxの作業場所を。

LDFA（Linux Desktop for Android）は、Debian 12とXFCEデスクトップをAndroidアプリ内に導入するアプリです。root化や外部のTermux・X11アプリは必要ありません。

■ はじめやすい導入
必要な容量と通信を確認し、名前を入力して「Linuxをインストール」を押すだけ。実行環境、Debian、XFCE、日本語入力を順に準備します。構築中の段階と詳細ログを確認でき、中断した処理は保存済みの環境から再開できます。

■ Linuxで作業
・Debian 12とXFCEのデスクトップ
・Google ChromeとNode.js 22 LTS
・日本語ロケール、Noto CJK、Fcitx5 / Mozc
・ターミナルとコマンド操作
・タッチ、マウス、ソフトウェア・物理キーボード入力
・表示倍率100〜250%、特殊キーの表示切り替え、JIS / US配列
・Androidスピーカーへの音声出力

■ データを持ち運ぶ
複数のLinux環境を作成できます。停止した環境は.ldfaファイルへバックアップし、新しい環境として復元できます。初回画面からバックアップの取り込みを始めることもできます。

■ 必要な環境
Android 8.0以降のARM64端末。新規導入には空き容量5GB以上とインターネット接続が必要です。Wi-Fiと充電をおすすめします。所要時間は端末と回線によって異なります。

■ ご利用にあたって
LinuxはAndroidカーネル上のPRootで動作します。PCの仮想マシンと同じ機能を提供するものではなく、systemdや一部の低レベル機能には制約があります。Chromeは互換性のためsandboxを無効にして動作します。Androidによるメモリ管理・省電力で終了する場合があります。

アプリをアンインストールするとアプリ内のLinuxデータも削除されます。必要なファイルは事前にバックアップしてください。バックアップは暗号化されません。Android 10以降ではダウンロード/LinuxDesktopへ保存し、Android 8〜9では表示されるアプリ専用の保存先から端末外へコピーできます。

LDFAは独立したオープンソースプロジェクトです。Termux、Debian、XFCE、Googleの公式アプリではありません。
```

## Store listing (English)

**App name**

```text
LDFA - Linux Desktop
```

**Short description**

```text
Debian and XFCE on Android, with Japanese input and backups. No root required.
```

**Full description**

```text
A Linux workspace on your Android device.

LDFA (Linux Desktop for Android) installs Debian 12 and the XFCE desktop inside one Android app. No root access or separate Termux or X11 app is required.

GET STARTED
Check storage and connectivity, name your desktop, and tap the install button. LDFA prepares the runtime, Debian, XFCE and Japanese input in sequence. Follow the installation stages and open detailed logs when needed. Interrupted setup can resume using the saved environment.

WORK IN LINUX
• Debian 12 and XFCE
• Google Chrome and Node.js 22 LTS
• Japanese locale, Noto CJK fonts and Fcitx5 / Mozc
• Terminal and command-line tools
• Touch, mouse, software and physical keyboard input
• Desktop scaling from 100% to 250%, optional extra keys, JIS / US layout
• Audio output through Android speakers

KEEP YOUR ENVIRONMENT
Create multiple Linux environments. Back up a stopped environment to an .ldfa file and restore it as a new environment. You can also start from a backup on the welcome screen.

REQUIREMENTS
Android 8.0 or later on an ARM64 device, at least 5 GB of free space for a new installation, and an internet connection. Wi-Fi and charging are recommended. Installation time depends on your device and connection.

LIMITATIONS
Linux runs through PRoot on the Android kernel. It is not a full PC virtual machine; systemd and some low-level features are unavailable. Chrome runs without its browser sandbox for compatibility. Android memory and battery management may stop running processes.

Uninstalling the app deletes its Linux data. Back up important files first. Backups are not encrypted. Android 10 and later save backups in Downloads/LinuxDesktop. On Android 8–9, copy the archive out of the app-specific location shown after completion before uninstalling.

LDFA is an independent open-source project, not an official app from Termux, Debian, XFCE or Google.
```

## リリースノート

```text
<ja-JP>
Linuxの導入画面を刷新し、準備からデスクトップ起動までの流れを分かりやすくしました。画面の再作成や中断に強い導入処理、構築段階に応じた進捗、起動・停止の安定性、バックアップの保存・復元処理とChromeの起動を改善しました。アプリアイコンと配色を更新し、Android 16を対象とした設定に対応しました。
</ja-JP>
<en-US>
Redesigned Linux onboarding and the app icon. Setup now survives screen recreation, reports installation stages, and resumes using saved data. Improved desktop process cleanup, Chrome startup, backup storage and restoration of internal Linux links. Updated the Android target to Android 16.
</en-US>
```

## App access / reviewer notes

```text
No account or login is required to use LDFA. Test on an ARM64 device with at least 5 GB of free storage and an internet connection. On the welcome screen, tap “Linuxをインストール” (Install Linux), wait for setup to finish, then tap “デスクトップを開く” (Open desktop). Installation includes a large download; timing depends on the device and connection.

LDFA runs Debian Linux programs through PRoot as its unprivileged Android application UID. Its bundled runtime is built for com.hatake716.linuxdesktop. The app downloads the Linux rootfs and software at the user's request. These files do not replace the signed APK, Android dex files, or packaged native libraries. PRoot is not a separate Android application sandbox, and Chrome uses --no-sandbox for compatibility. Please review this architecture and the declared foreground service uses; we do not claim automatic eligibility for a policy exception.

Source and security documentation:
https://github.com/hatake716/LDFA
https://github.com/hatake716/LDFA/blob/main/SECURITY.md
```

## 前景サービスの説明

**specialUse**

```text
LDFA provides a local interactive Linux desktop. A user starts installation, opens the terminal, or opens the desktop from the visible app. The app keeps the local Linux processes, X11 display service and session monitor running while the user temporarily switches screens. Interrupting them during an active task can terminate Linux applications or interrupt installation.

TermuxService owns terminal command sessions; RunCommandService dispatches management operations; EmbeddedX11ServerService owns Xorg in a dedicated process; DesktopKeepAliveService monitors the active desktop or installation. These are local execution and display tasks. The app shows ongoing notifications and provides a desktop stop action. The monitor stops after detecting inactivity. Interrupted installation is resumed when the user returns to the app, with bounded retries.

The demonstration shows the user action, desktop display, ongoing notifications, a return from the Android home screen, and explicit stopping of the session.
```

**dataSync（バックアップ・復元）**

```text
The user explicitly starts a backup or restore from LDFA. BackupService compresses a stopped Linux environment into a local .ldfa archive or verifies and extracts a selected archive into a new environment. It reports progress through a notification and the app, supports cancellation, and stops when the operation completes or fails. The service handles the Android dataSync timeout by cancelling work and stopping. No boot receiver starts this operation.
```

[前景サービスの申告要件](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)と[サービス型](https://developer.android.com/develop/background-work/services/fgs/service-types)に沿って、Consoleで用途とデモ動画URLを登録します。specialUseの適否はGoogle Playの審査対象です。

## データセーフティの入力根拠

LDFA本体には広告・解析SDK、開発者向けのデータ送信・アカウント登録を実装していません。Linuxのファイルと設定は端末内に保存します。ファイル選択・バックアップはユーザー操作によるものです。

「開発者がデータを収集・共有するか」は、この実装を根拠に回答します。Linuxのダウンロード先サーバーへの接続情報や、Linux内でユーザーが利用するWebサイト・CLIサービスへの通信は別途発生します。「インターネットへの送信が一切ない」とは説明しません。提出時にはGoogle Playが表示する最新版の設問・定義に照合してください。

プライバシーポリシー：<https://hatake716.github.io/LDFA/privacy.html>

## 素材と提出順

1. `release-assets/v1.2.0/`のAAB、SHA256SUMS、検証結果を確認します。
2. ストアアイコン512×512、フィーチャー画像1024×500、新しい画面のスクリーンショットを登録します。
3. デモ動画を限定公開でアップロードし、閲覧可能なURLをサービス申告に登録します。ローカルの動画作成と、外部サービスへの動画公開は別の作業です。
4. 上記の掲載文面、アクセス説明、サービス型、データセーフティ、プライバシーポリシーを入力します。
5. AABをテストトラックへアップロードし、Console側の検査と審査結果を確認して公開を進めます。

Google Playへのアップロード・審査申請は、この資料やAABを生成しただけでは完了しません。

## 提出前の実測状況

2026-09-05時点で、API 35 / x86_64 / 4KBの署名済みAPKによる新規導入、日本語入力、Chrome表示、起動・停止、バックアップ復元を確認しています。ARM64実機での最終確認は未完了です。APKとAABのARM64コードの一致・署名・16KB ELF配置は検査済みですが、ARM64 16KBでのLinux実行を実測した結果ではありません。x86_64のAndroid 17 / 16KBプレビューではゲスト起動時にSIGBUSが再現します。テストトラックで対象端末の導入・復帰・停止を確認してから本番配信を判断してください。

資料に記載したデモ動画はローカルファイルです。URLの登録とPlay Consoleでの審査申請は未実施です。
