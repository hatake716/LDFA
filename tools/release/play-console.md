# Google Play 提出資料 — LDFA 1.2.3

対象：`com.hatake716.linuxdesktop` / versionCode `24` / targetSdk `36`。
新しい成果物・画像・提出文面は、ローカルの`release-assets/v1.2.3/`にまとめます。
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
複数のLinux環境を作成できます。停止した環境は.ldfaファイルへバックアップし、新しい環境として復元できます。初回画面からバックアップの取り込みを始めることもできます。保存済み環境の起動中は、進行状況と起動ログを画面で確認できます。

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
Create multiple Linux environments. Back up a stopped environment to an .ldfa file and restore it as a new environment. You can also start from a backup on the welcome screen. Startup progress and logs are displayed when opening a saved desktop.

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
保存済みLinux環境の起動中に、処理の進行状況とログを表示するようにしました。画面が切り替わってもログを確認でき、起動完了後は自動でデスクトップへ移ります。失敗時はログを読み返せます。
</ja-JP>
<en-US>
Startup progress and logs are now shown when opening a saved Linux desktop. Logs remain visible as the display opens and close automatically when the desktop is ready. Failed starts keep their logs available for review.
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

## サポートされていないAPIバイパスSDKへの対応

1.2.2（versionCode 23）では、`org.lsposed.hiddenapibypass:hiddenapibypass:6.1`と非公開APIの制限解除処理を削除しました。診断情報の取得と内蔵Termuxのプロセス停止を修正しています。DEX・依存関係・依存情報を検査した新しいAABへ差し替え、Consoleが警告対象としているversionCodeを確認してください。旧1.2.1（22）にはこのSDKが残っています。

今回の変更で新しい権限は追加していません。dataSyncとspecialUseの機能・申告内容は1.2.1と同じです。リンク先の実演動画は1.2.1で撮影したもので、バージョン表示を改変していません。

## 権限の未申告エラーへの対応

versionCode 21に残っていた`REQUEST_INSTALL_PACKAGES`と、`BIND_ACCESSIBILITY_SERVICE`で登録する`KeyInterceptor`は1.2.1で削除しました。X11の有効化設定・自動有効化処理・サービスクラス・メタデータも同梱しません。Linuxのパッケージ導入にAndroidのAPKインストール権限は使用しません。

新しいAABに差し替え、Consoleが警告対象としているversionCodeを確認してください。旧トラック・旧リリースが対象の場合は、そちらの配信中バージョンも確認します。削除したAPIの使用理由を作って申告する対応は行いません。

## 前景サービスの申告

### dataSync

選択肢：**「ネットワーク処理 → その他」**と**「ローカル処理 → インポート、エクスポート」**。

1.2.1では、Linux準備中のダウンロード・展開もdataSyncとして扱います。前の回答資料でバックアップのみを対象にした選択肢から追加しています。「ネットワーク処理 → バックアップ、復元」はクラウドバックアップを行わないため選びません。

用途名：ユーザーが開始するLinux環境のダウンロード・展開、および端末内バックアップの書き出し・取り込み。

> ユーザーが画面から開始するLinux環境の準備に使用します。Linuxのファイルをダウンロードし、アプリ専用領域へ展開・構築します。準備中は進捗を画面と通知で示し、通知の「準備を停止」で中断できます。保存済みデータは保持し、ユーザー操作で再開します。また、ユーザーが選ぶ停止済みLinux環境を端末内の.ldfaファイルへ書き出し、選択したファイルを検証・展開して新しい環境へ復元します。バックアップ・復元も進捗表示とキャンセルに対応し、終了時にサービスを停止します。クラウドへの自動バックアップは行いません。

延期・中断の影響：

> 初回準備が延期されると、ユーザーが開始したLinux環境を利用できません。バックアップや復元が延期されると、予定している保存・移行を進められません。途中で処理が終了すると、導入は再開、バックアップ・復元は再実行が必要になります。ユーザーが開始した処理を画面移動中も継続するために使用します。ユーザー自身による停止やAndroidの時間制限には従います。

動画：[dataSyncの実演](https://github.com/hatake716/LDFA/releases/download/v1.2.1/ldfa-data-sync-demo.mp4)。準備の再開・進捗・カードからの停止、バックアップの開始・通知・完了、復元の開始・進捗・完了を示します。キャンセルの入口も画面に表示されます。バックアップの待ち時間の一部を省略し、動画内に明記しています。

### specialUse

選択肢：**「特殊用途 → その他」**。

用途名：ユーザーが開始した端末内Linuxデスクトップ・ターミナルの継続実行。

> ユーザーが画面から起動するLinuxデスクトップとターミナルを実行するために使用します。端末内のLinuxプロセス、X11表示サーバー、セッション監視を維持し、別のAndroidアプリへ一時的に切り替えた後も、開始した作業へ戻れるようにします。実行状態を通知に表示し、デスクトップはアプリ内または通知から停止できます。ターミナルは終了操作を備えています。初回準備のダウンロード・展開とバックアップ・復元にはdataSyncを使用します。

延期・中断の影響：

> ユーザーが操作する対話的なLinux環境のため、実行を延期すると要求された作業画面を利用できません。途中でプロセスや表示サーバーが終了すると、実行中のコマンド・アプリが終了し、未保存の作業が失われる可能性があります。操作中のセッションを継続する必要があり、後で時刻を決めて実行する処理では代替できません。

他のサービス型に該当しない理由：

> 対話的なLinuxプロセスの実行と端末内X11描画が目的です。Android画面の録画・投影や、外部機器との通信を目的とする機能ではありません。既存の型に該当するデータ転送・展開・バックアップ処理はdataSyncとして扱います。

動画：[specialUseの実演](https://github.com/hatake716/LDFA/releases/download/v1.2.1/ldfa-special-use-demo.mp4)。デスクトップ起動、Linux画面表示、実行中通知、Androidホームへ移動、同じセッションへ復帰、明示的な停止を示します。

[前景サービスの申告要件](https://support.google.com/googleplay/android-developer/answer/13392821?hl=ja)と[サービス型](https://developer.android.com/develop/background-work/services/fgs/service-types)に沿って、Consoleで説明と閲覧可能な動画URLを登録します。specialUseの適否はGoogle Playの審査対象です。

## データセーフティの入力根拠

LDFA本体には広告・解析SDK、開発者向けのデータ送信・アカウント登録を実装していません。Linuxのファイルと設定は端末内に保存します。ファイル選択・バックアップはユーザー操作によるものです。

「開発者がデータを収集・共有するか」は、この実装を根拠に回答します。Linuxのダウンロード先サーバーへの接続情報や、Linux内でユーザーが利用するWebサイト・CLIサービスへの通信は別途発生します。「インターネットへの送信が一切ない」とは説明しません。提出時にはGoogle Playが表示する最新版の設問・定義に照合してください。

プライバシーポリシー：<https://hatake716.github.io/LDFA/privacy.html>

## 素材と提出順

1. `release-assets/v1.2.3/`のAAB、SHA256SUMS、検証結果を確認します。
2. ストアアイコン512×512、フィーチャー画像1024×500、新しい画面のスクリーンショットを登録します。
3. 上記のGitHubリリースに添付したMP4のURLをサービス申告に登録します。Consoleが別の共有形式を求める場合は、同じ動画を限定公開YouTubeなどへアップロードしてURLを使用します。
4. 上記の掲載文面、アクセス説明、サービス型、データセーフティ、プライバシーポリシーを入力します。
5. AABをテストトラックへアップロードし、Console側の検査と審査結果を確認して公開を進めます。

Google Playへのアップロード・審査申請は、この資料やAABを生成しただけでは完了しません。

## 1.2.0での実測状況（1.2.1の結果は検証資料を参照）

2026-09-05時点で、API 35 / x86_64 / 4KBの署名済みAPKによる新規導入、日本語入力、Chrome表示、起動・停止、バックアップ復元を確認しています。Pixel 10a（Android 17 / API 37 / ARM64 / 4KB）へ同一APKをインストールし、バージョン・署名・端末内APKのハッシュ一致と初回画面の表示を確認しました。ARM64実機でのLinuxの導入・実行は未確認です。APKとAABのARM64コードの一致・署名・16KB ELF配置は検査済みですが、ARM64 16KBでのLinux実行を実測した結果ではありません。x86_64のAndroid 17 / 16KBプレビューではゲスト起動時にSIGBUSが再現します。テストトラックで対象端末の導入・復帰・停止を確認してから本番配信を判断してください。

デモ動画はGitHubリリースのMP4として提供します。Play ConsoleへのURL登録と審査申請は未実施です。

## 1.2.3の起動ログ表示

起動時のログは端末内だけで表示します。開発者への送信や解析SDKは追加していません。ユーザー操作でテキストを選択・コピーできます。新しい権限は追加しておらず、dataSyncとspecialUseの用途は従来どおりです。1.2.1で撮影したFGS動画は過去版の機能実演資料です。現行版の起動ログUIは[1.2.3の実演動画](https://github.com/hatake716/LDFA/releases/download/v1.2.3/ldfa-startup-logs-demo.mp4)を参照してください。

1.2.3の最終APKはPixel 10aへ上書き更新し、端末内APKのハッシュ一致を確認しています。2026-09-06には利用者から実機でLinuxデスクトップが起動したとの確認報告を受けました。詳細な自動検証は[検証資料](../../docs/TESTING.md)を参照してください。
