# セキュリティ方針

## 統合構成

ターミナル実行基盤、RunCommandサービス、X11表示機能、管理UIは同じAPK・同じAndroidアプリID`com.hatake716.linuxdesktop`に含まれます。外部のTermux／Termux:X11アプリへコマンドを送信する構成ではありません。内蔵するTermuxユーザーランド（bootstrap）は、このアプリ専用のprefix `/data/data/com.hatake716.linuxdesktop/files/usr` 向けに再ビルドしたものです。

## Android権限

- `INTERNET` / `ACCESS_NETWORK_STATE`: TermuxパッケージとDebian環境の取得
- ストレージ権限は一切宣言しません（`READ/WRITE_EXTERNAL_STORAGE`も全ファイルアクセス`MANAGE_EXTERNAL_STORAGE`も不使用）。Android共有フォルダ連携機能は廃止済みで、Debian環境のファイルはアプリ専用領域にのみ保存されます
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`: Debianインストール、X11サーバー、XFCEセッション維持
- `FOREGROUND_SERVICE_DATA_SYNC`: バックアップ／復元処理
- `POST_NOTIFICATIONS`: バックグラウンド実行状態の通知
- `WAKE_LOCK`: インストール中・実行中の意図しない停止を減らす
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: ユーザーが安定動作設定を開くために使用

平文通信（cleartext）は一切使用しません（Androidプラットフォームの既定どおりブロックされます。かつて唯一の利用箇所だったローカルnoVNCフォールバック表示は廃止済み）。root権限は要求しません。

## 内蔵コマンド実行

- `RunCommandService`と`TermuxService`は`android:exported="false"`です。
- `com.termux.permission.RUN_COMMAND`はsignature権限です。
- Termux upstreamの内部ポリシー確認を満たすため、アプリ自身が`allow-external-apps=true`を内部プロパティへ設定しますが、サービスは外部公開しません。
- コマンド送信先は同一APK内の明示的コンポーネントに固定します。
- 結果受信用PendingIntentは同一APK内の非公開サービスを宛先とします。
- コンテナIDは英数字、ピリオド、アンダースコア、ハイフンに制限します。
- 表示名はシェル文字列へ連結せず、独立した引数として渡します。
- コントローラスクリプトは一時ファイルへ書き込んでからatomic renameし、権限`700`で保存します。
- コマンド結果はランダムUUID nonceと照合し、タイムアウト済み・旧世代のPendingIntent結果を破棄します。

## X11

Xorgは非公開Foreground Service `EmbeddedX11ServerService` の専用 `com.termux:x11` プロセスで起動します。旧 `/system/bin/app_process`、loader APK、shell所有のAndroidライフサイクルは使用しません。

表示Activityは同一アプリ内でServiceへ直接bindし、非公開BinderからX接続FDを受け取ります。X11 TCPは無効で、Unix socketはTermuxプレフィックス内の`$PREFIX/tmp/.X11-unix`へ作成します。Debianは`proot-distro --shared-tmp`で同じtmpを共有します。

Service世代UUID、PIDと`/proc/<pid>/cmdline`を照合し、所有を確認できないプロセスへsignalを送りません。表示側rendererにはJNI ABI、mutex、EGL初期化、FD再接続、停止処理のhardeningを適用します。

## Debian環境

PRootは仮想マシンや強いセキュリティ境界ではありません。Debian環境のrootはAndroid端末のrootではありませんが、同じアプリのファイルや明示的にバインドした共有フォルダへアクセスできます。Debianの`desktop`ユーザーには利便性のためパスワードなしの`sudo`を設定します。

Google ChromeはGoogle公式のstableパッケージをDebian環境へ実行時に取得します。Android PRootではChromeの通常のnamespace／setuid sandboxが成立しないため、専用ランチャーは一般ユーザー`desktop`から`--no-sandbox`で起動します。省メモリ化のrenderer上限はこのsandboxを復元するものではなく、Chromeのrenderer sandboxに依存した隔離は提供されません。信頼できないWebサイト、拡張機能、ダウンロードファイルを扱う場合は、この制約を前提にしてください。

## 実行コードの出所

LDFAは「ユーザーが自分で構築するLinux環境」を提供するアプリです。APKには起点となるTermux bootstrap（bash・apt・dpkg・tar等）とPRootエンジン（`libpdrt.so`）を同梱します。それ以外の実行コードは、ユーザーの操作を起点に実行時取得され、**すべてPRootサンドボックス内で非特権のアプリUIDとして動作します**。取得したコードが署名済みAPK・そのdex／ネイティブコード・Google Playが配信したバイナリを書き換えることはできません。

実行時に取得される実行コードと、その完全性検証は以下の通りです。

| 取得物 | 取得元 | 完全性検証 |
| --- | --- | --- |
| `proot` / `proot-distro` 等のTermuxパッケージ | Termux公式aptリポジトリ（署名済み） | apt署名（リポジトリ鍵） |
| Debian 12 rootfs | `proot-distro`（distroプラグイン） | プラグイン同梱のSHA-256 |
| Debianの各aptパッケージ（XFCE等） | Debian公式ミラー（署名済み） | apt署名（Debianアーカイブ鍵） |
| Google Chrome | Google公式aptリポジトリ（署名済み）※ | apt署名（Google Linux鍵） |
| Node.js（静的ビルド） | nodejs.org | ビルド埋め込みのSHA-256を検証 |
| ユーザーが導入するCLI（Claude Code等） | ユーザーが指定 | ユーザー責任 |

※ Chromeはユーザーが選んで導入するゲストソフトウェアであり、Googleの署名付きaptリポジトリと鍵を追加してインストールします。

すべての実行時取得はHTTPS経由です。取得したコードはPRoot内のゲスト環境でのみ実行され、Androidアプリの挙動やAPKを変更しません。これはGoogle Playの「Device and Network Abuse」ポリシーにおける、インタプリタ／仮想マシン／サンドボックス環境の例外に該当します。

## 既知のリスク

- 内蔵Termuxユーザーランドは本アプリ専用prefix向けに再ビルドしたものです。公式Termuxとはアプリ IDもデータ領域も異なり、独立して共存できます。
- PRoot、ソフトウェアレンダリング、メーカー独自の省電力制御には端末差があります。
- PRoot互換Chromeランチャーは`--no-sandbox`を使用するため、通常のLinux ChromeよりWebコンテンツの隔離が弱くなります。
- X11サーバーは専用processですが同一アプリUIDで実行されるため、アプリのTermuxデータ領域へアクセスできます。
- フォアグラウンドサービスとWakeLockは終了耐性を高めますが、Androidによるプロセス終了を完全には防げません。
