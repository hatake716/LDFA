# セキュリティ方針

## 統合構成

ターミナル実行基盤、RunCommandサービス、X11表示機能、管理UIは同じAPK・同じAndroidアプリID`com.termux`に含まれます。外部のTermux／Termux:X11アプリへコマンドを送信する構成ではありません。

## Android権限

- `INTERNET` / `ACCESS_NETWORK_STATE`: TermuxパッケージとDebian環境の取得
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`: ユーザーが許可したAndroid共有フォルダとの連携
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`: Debianインストール、X11サーバー、tmux監視、XFCEセッション維持
- `POST_NOTIFICATIONS`: バックグラウンド実行状態の通知
- `WAKE_LOCK`: インストール中・実行中の意図しない停止を減らす
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: ユーザーが安定動作設定を開くために使用

root権限は要求しません。

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

Google ChromeはGoogle公式のstableパッケージをDebian環境へ実行時に取得します。Android PRootではChromeの通常のnamespace／setuid sandboxが成立しないため、専用ランチャーは一般ユーザー`desktop`から`--no-sandbox`で起動します。Chromeのrenderer sandboxに依存した隔離は提供されません。信頼できないWebサイト、拡張機能、ダウンロードファイルを扱う場合は、この制約を前提にしてください。

## 既知のリスク

- Android共有フォルダ内のファイルはAndroidとDebianの双方から変更・削除できます。
- APKは公式Termuxと同じアプリIDを使用するため、公式Termuxと共存できません。
- PRoot、ソフトウェアレンダリング、メーカー独自の省電力制御には端末差があります。
- PRoot互換Chromeランチャーは`--no-sandbox`を使用するため、通常のLinux ChromeよりWebコンテンツの隔離が弱くなります。
- X11サーバーは専用processですが同一アプリUIDで実行されるため、アプリのTermuxデータ領域へアクセスできます。
- フォアグラウンドサービスとWakeLockは終了耐性を高めますが、Androidによるプロセス終了を完全には防げません。
