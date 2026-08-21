# セキュリティ方針

## 統合構成

ターミナル実行基盤、RunCommandサービス、X11表示機能、管理UIは同じAPK・同じAndroidアプリID`com.termux`に含まれます。外部のTermux／Termux:X11アプリへコマンドを送信する構成ではありません。

## Android権限

- `INTERNET` / `ACCESS_NETWORK_STATE`: TermuxパッケージとUbuntu環境の取得
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`: ユーザーが許可したAndroid共有フォルダとの連携
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`: Ubuntuインストール、tmux監視、XFCEセッション維持
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
- コントローラスクリプトはアプリ同梱版からTermuxホームへ書き込み、権限`700`で保存します。

## X11

v0.4.0以降、X11サーバーはAndroid `Service` 内で直接生成しません。

内蔵Termux側の`ldfa-x11`コントローラが、インストール済み`com.termux` APKを`CLASSPATH`へ設定し、`/system/bin/app_process`から`com.termux.x11.CmdEntryPoint`を起動します。`CmdEntryPoint`は同じAPKの`nativeLibraryDir`から`libXlorie.so`をロードします。

X11プロセスはアプリと同一UIDで動作し、tmuxセッションで管理します。X11 Unix socketはTermuxプレフィックス内の`$PREFIX/tmp/.X11-unix`へ作成します。Ubuntuは`proot-distro --shared-tmp`で同じtmpを共有します。

X11表示Activityと制御BroadcastReceiverは外部公開せず、同一アプリからのみ利用します。

## Ubuntu環境

PRootは仮想マシンや強いセキュリティ境界ではありません。Ubuntu環境のrootはAndroid端末のrootではありませんが、同じアプリのファイルや明示的にバインドした共有フォルダへアクセスできます。Ubuntuの`desktop`ユーザーには利便性のためパスワードなしの`sudo`を設定します。

## 既知のリスク

- Android共有フォルダ内のファイルはAndroidとUbuntuの双方から変更・削除できます。
- APKは公式Termuxと同じアプリIDを使用するため、公式Termuxと共存できません。
- PRoot、ソフトウェアレンダリング、メーカー独自の省電力制御には端末差があります。
- `app_process`で起動するX11サーバーは同一アプリUIDで実行されるため、アプリのTermuxデータ領域へアクセスできます。
- フォアグラウンドサービスとWakeLockは終了耐性を高めますが、Androidによるプロセス終了を完全には防げません。
