# LDFA 1.2.3 保存済みデスクトップの起動ログの検証

対象：`com.hatake716.linuxdesktop` / versionName `1.2.3` / versionCode `24`。2026-09-05〜06に検証しました。
成果物と記録はローカルの`release-assets/v1.2.3/`に保存しています。

- 保存済み環境を開くと、準備段階と今回の起動で追加されたLinux・X11・XFCE・アプリ設定のログを表示。管理画面とX11画面で同じ状態を参照し、描画確認の成功後に自動で閉じます。
- 単体テスト201件成功（app 56件、terminal-emulator 145件）、失敗・エラー・スキップなし。今回追加した7件では、前回ログの除外、ファイル置換・切り詰め、日本語UTF-8の途中書き込み、大量出力、失敗・キャンセル・成功後の状態を検証しました。
- app / termux-runtime / embedded-x11のLint、ホスト構文・統合テスト、X11コントローラー検査が成功。
- 最終APKとARM64 AABの署名、bundletool validation、不要な権限・AccessibilityService・HiddenApiBypass SDKの不在を確認。APK内18本・AAB内9本のネイティブライブラリの16KB ELF配置と、両成果物の全DEX・ARM64ライブラリ・ホストスクリプト一致を検査しました。
- 独立したAPI 36 / x86_64 / 4KB AVDに最終APKを上書きし、保存済み2環境を保持。保存済み環境の起動ログ、縦横回転後も同じ開始時刻のログが残ること、X11画面への移行中の表示、XFCE描画後の自動終了、停止後のXFCE終了を確認しました。
- 停止中の検証専用rootfsを一時的に退避して起動失敗を再現。失敗後のログと「閉じる」、横画面・文字倍率1.3、Activityを破棄して開き直した後のログ保持を確認しました。検証後はrootfsを元へ戻し、inodeの一致を確認しています。
- 最終APKで約32秒の起動実演動画を撮影。保存済み環境の起動、画面回転、実際のログ出力、XFCE表示までを収録しています。
- Pixel 10a / Android 17 / API 37 / ARM64 / 4KBへ、同じ最終APKを`install -r`で1.2.2から更新。versionCode 24、MainActivityの起動成功、保存済み環境の管理画面と起動ログ画面、端末内APKのSHA-256一致を確認しました。データ消去と実機への入力注入は行っていません。
- 取得可能なAPI 36 AVDとPixelの対象アプリUIDのlogcatに、FATAL EXCEPTION、Fatal signal、NoClassDefFoundError、NoSuchMethodErrorはありませんでした。

最終APKのSHA-256は`8bdb651d1572cff6ed701cb7410f2fc3ed3748fd2b9aa570936cd1c1cdc07234`、ARM64 AABは`ddaa1fd0ed6a1ab852402b1135be90f0cb50f424d358a543c97fbe351d994ef2`です。エミュレーターと実機へ、この最終APKをインストールしています。

## 今回の検証範囲

API 36は公式Google APIs x86_64イメージrevision 7、セキュリティパッチ2025-07-05、ARTモジュール360527520、SELinux Enforcingです。診断時はadb rootを使用しますが、アプリは通常の非root UIDで動作します。2026年時点のすべてのART Mainline更新を検証したものではありません。

起動ログの保持はアプリプロセスが生存する間の機能です。Activity再作成は検証しましたが、Androidがアプリプロセス自体を終了した後に同じ表示を復元する永続化は実装していません。

実機では上書き更新・アプリ起動・管理画面・起動ログ画面・APK照合を確認しました。Linuxの起動操作は自動実行していません。その後、2026-09-06に利用者から「実機にて起動確認できました」と報告を受け、同じ最終APKでのLinuxデスクトップ起動成功を確認済みとして記録しました。実機での起動成功は利用者による確認で、こちらの自動入力による検証ではありません。ARM64 16KB環境、音声試聴、長時間負荷は未確認です。Linuxの新規導入・バックアップ・復元の前回の結果は下記1.2.2の記録を参照してください。今回、同じ検証を再実施したとは扱いません。

Google Play更新用AABと提出資料を作成しています。Consoleへのアップロード・申告送信・審査完了・公開は未実施です。

---

# LDFA 1.2.2 APIバイパスSDK削除の検証（過去の記録）

対象：`com.hatake716.linuxdesktop` / versionName `1.2.2` / versionCode `23`。
成果物と記録はローカルの`release-assets/v1.2.2/`に保存しています。

- 組み込みTermuxのHiddenApiBypass依存と制限解除の呼び出しを削除。取得できない非公開の診断値を不明として扱い、バックグラウンドコマンドの停止を公開`Process.destroyForcibly()`へ移行。
- 組み込みライブラリのminSdkをアプリ本体と同じ26へ統一。LDFAの対応Androidバージョンは従来どおり8.0以降。
- 単体テスト194件成功（app 49件、terminal-emulator 145件）、失敗・スキップなし。新しい回帰テストではPIDを取得せず対象の子プロセスを停止し、別の子プロセスが生存することを実測。
- app / termux-runtime / embedded-x11のLint、ホスト・X11コントローラー検査が成功。termux-sharedの追加Lintでは新たなNewApi指摘を解消。上流由来のMissingSuperCall 2件とCoreLibDesugaringV1 1件は既存の非阻止対象として残るため、全モジュールのLintがゼロとはしていない。
- APK/AABの全DEXと依存メタデータを検査し、対象SDK・制限解除メソッドの不在を確認。Gradleの依存解決でも対象SDKなし。依存報告は無効化していない。検査スクリプトが旧1.2.1 AABを拒否することも確認。
- 既存アップロード鍵による署名、bundletool validation、16KB ELF配置、APKとAABの全DEX・ARM64ライブラリ・ホストスクリプト一致、不要権限とAccessibilityServiceの不在を確認。
- API 35 / x86_64 / 4KB AVDで1.2.1から更新し、既存4環境を保持。XFCEの表示、通常キーとソフトウェアキーボードからMousepadへの入力、Linuxファイルへの保存とハッシュ、Androidホームからの復帰、停止後のLinuxプロセス終了を確認。
- API 35で準備処理を明示的に再開・停止。rootfs inodeは106824で一致し、停止後にLinuxのworkerは残らず、APK上書き・アプリ再起動後も準備停止の状態を維持。
- 最終APKで停止中の環境を736,702,530 bytesの.ldfaへバックアップ。進捗とdataSync通知、成功後のBackupService終了を確認。そのファイルをAPI 36へ転送して別IDへ復元し、入力確認用ファイルのSHA-256が一致。既存の復元済み環境も保持。
- API 36 / x86_64 / 4KBの新規AVDで最終APKを導入し、1.2.1の736,702,007 bytesのバックアップから別IDへ復元。保存済みテキストのSHA-256が一致。アプリ自身が実行環境を準備し、復元済みXFCEを表示。通常キーとソフトウェアキーボードによる保存、ホームからの復帰、停止を確認。
- 両AVDとPixel 10aの取得可能な対象アプリUIDのlogcatに、FATAL EXCEPTION、Fatal signal、NoClassDefFoundError、NoSuchMethodErrorの記録なし。
- Pixel 10a / Android 17 / ARM64 / 4KBへ最終APKを`install -r`で更新。versionCode 23、MainActivityの起動成功、端末内APKと最終成果物のSHA-256一致を確認。データ消去と実機への入力注入は実施していない。実機でのLinux起動・入力は未確認。

最終APKのSHA-256は`b7730edea3532aeefde47d9f0825be667e86e179691fbcc1c665f667cefdb4b0`、ARM64 AABは`4ba7f7e42d276854fca874bc6ca3e61d851cb80de5b74a854aae6626b92f0b71`です。最低API設定の整合後に再ビルドし、両AVDへ最終APKを入れ直しています。前段の検証APKと最終APKの全DEXは同一です。

## ARTと実機検証の範囲

API 35のARTモジュールは350820300、API 36は360527520です。API 36は公式Google APIs x86_64イメージrevision 7、セキュリティパッチ2025-07-05を使用しました。2026年時点のすべてのART Mainline更新を実行検証した結果ではありません。SDKの除去は実際の提出用AABの検査で確認しています。

両AVDはSELinux Enforcing、アプリは通常の非root UIDです。診断用のadb rootを使用しましたが、アプリにroot権限を付与せず、hidden_api_policyも変更していません。Android 16の検証は新しいAndroidアプリ領域へのバックアップ復元であり、Linux全パッケージの新規ダウンロードを再検証したものではありません。

ARM64実機でのLinux実行、ARM64 16KB環境、音声の試聴、長時間負荷は未確認です。16KBプレビューでの過去のゲスト起動問題は下記1.2.0の記録を参照してください。

Google Play Consoleへのアップロード・申告送信・審査完了は未実施です。バージョン23への差し替え後、Console側の解析結果を確認する必要があります。

---

# LDFA 1.2.1 権限修正の検証（過去の記録）

対象：`com.hatake716.linuxdesktop` / versionName `1.2.1` / versionCode `22`。
成果物と記録はローカルの`release-assets/v1.2.1/`に保存します。

- APKとARM64 AABのデコード済みマニフェストで、APKインストール権限、ユーザー補助サービス、共有ストレージ権限がないことを検査。
- X11のKeyInterceptorクラスとユーザー補助メタデータもAPKから除外。
- 既存アップロード鍵による署名、bundletool validation、16KB ELF配置、APKとAABのDEX・ARM64ライブラリ・スクリプトの一致を検査。
- 単体テスト193件（失敗・スキップなし）、app / termux-runtime / embedded-x11のLint、ホスト・X11コントローラーの検査が成功。
- 独立したAPI 35 / x86_64 / 4KB AVDへ1.2.0から上書き更新し、既存2環境を保持。
- 通常のキーイベントとソフトウェアキーボードのタップからMousepadへ入力し、Linuxファイルへ保存した内容を照合。
- Linux準備のサービスがdataSync、デスクトップ実行のサービスがspecialUseで動くことをdumpsysで確認。
- 通知からの準備停止、カードからの停止、明示的な再開、アプリ再起動後の停止維持を確認。停止前後・再開後のrootfs inodeは106824で一致。
- 停止した環境を736,702,007 bytesの.ldfaへバックアップし、別IDへ復元。入力確認用ファイルのSHA-256が一致し、完了後にBackupServiceが停止。バックアップ・復元中のdataSync通知と進捗を確認。
- 最終APKのLinux起動、通知、Androidホームからの復帰、停止を動画で記録。
- Pixel 10aへ保存済み1環境を保持して上書き更新。管理画面の表示、versionCode 22、端末内APKと検証済みAPKのSHA-256一致を確認（`e57c44fcd6bc707389b0ceb5c432487d3a6c2eb78c2ca2dbee0b633d951b8e3f`）。実機ではLinuxを起動せず、入力注入も行っていません。

Google Play Consoleへのアップロード・申告・審査完了は、ローカルのパッケージ検証とは別です。ARM64実機でのLinux実行、ARM64 16KBでの実行、音声の試聴はこの修正の検証対象に含めていません。

---

# LDFA 1.2.0 検証資料（過去の記録）

対象：versionName `1.2.0`、versionCode `21`、`com.hatake716.linuxdesktop`。
ローカルの検証ログ・スクリーンショットは`release-assets/v1.2.0/verification/`にまとめます。

## 自動検証

```bash
bash scripts/check-host-script.sh
bash scripts/test-host-controller.sh
bash scripts/check-x11-controller.sh
./gradlew testDebugUnitTest :app:lintDebug :termux-runtime:lintDebug :embedded-x11:lintDebug
```

- ホストの導入・停止・音声制御と、生成X11 overlayの契約を検査します。共有ストレージへ書き込めない場合の環境作成、メタデータ書き込み失敗時の後始末、明示的exit時の失敗状態、PIDのパス別名、生成ゲストスクリプトの標準入力・失敗伝達も確認します。
- 単体テストではメタデータ・設定・ログ・PRoot・バックアップの往復を確認します。
- 1.2.0ではバックアップの保存先維持、同名ファイル保護、未導入状態の拒否、XDG rootfsの検出、バックアップと起動の直列化、破損・途中で切れたアーカイブの拒否、復元先IDへのPRoot内部リンクの付け替えを追加しています。

ビルド成功と、Linuxの導入・表示成功は別々に確認します。

## エミュレーターでの確認手順

検証用の独立したAVDを使用し、利用者のLinuxデータや別アプリのTermux環境には触れません。
他のAndroid作業と共有しないADBサーバー（ローカル検証ではポート5047）と明示的なエミュレーターserialを使用します。
対象ABI用に、同じアプリIDのbootstrapとネイティブPRootを収録したAPKを使用します。

1. 未導入状態で初回画面・容量説明・復元の入口を確認。
2. Linuxの導入を開始し、通知の表示と実行段階の進捗を確認。
3. 準備・導入中の回転、ホームへの移動、管理画面の再作成を確認。
4. 通信障害やプロセス終了後、展開済みのファイルを残して再開できることを確認。
5. 実際のXFCE画面、Chrome、日本語入力、画面移動と復帰を確認。
6. 停止・再起動を繰り返し、古いworkerやサービスが次のセッションに干渉しないことを確認。
7. 停止した環境をバックアップし、新しいIDへ復元して保存したファイルを照合。
8. ダークテーマ、横画面、大きい文字で操作入口が切れないことを確認。

X11のプロセスが存在するだけで成功とはしません。Surfaceへの描画と、実際のデスクトップ画面を確認します。

## パッケージ検証

- APKの署名証明書・アプリID・バージョン・targetSdk・同梱ABI
- AABの署名、bundletool validation、同梱ABI
- すべてのネイティブELFのLOAD segment alignment
- APK内のARM64ライブラリ・スクリプト・DEXコードとAAB内の対応ファイルの一致
- merged manifestのサービス型、非公開コンポーネント、不要な共有ストレージ権限の不在
- 署名鍵、ローカル設定、検証用一時ファイルのGit混入がないこと

16KB対応はELF検査と16KB環境の動作を区別します。x86_64エミュレーターでの合格はARM64 16KB端末での実測を代替しません。

## 実機への最終インストール

エミュレーターで検証した署名済みAPKを最終成果物として保存した後、明示した実機serialへインストールします。既存アプリの署名とデータを確認し、上書き更新で保持します。物理端末への入力注入は行いません。

インストール後はパッケージ名、versionCode、署名、インストール済みAPKのハッシュを照合し、安全な起動を確認します。音声を人が聴いたか、実機でLinuxを新規導入したかなど、実施していない項目は完了として報告しません。

## 検証結果

2026-09-05の検証です。署名済みAPKはARM64 / x86_64、Play提出用AABはARM64です。

| 確認項目 | 結果 |
| --- | --- |
| 単体テスト | 193件成功（app 48件、terminal-emulator 145件）、失敗・スキップなし |
| Lint | app / termux-runtime / embedded-x11成功 |
| ホスト・X11コントローラー | 構文、契約、ホスト統合テスト成功。Bash 5.1の明示的exit時にローカル変数が失われる問題を再現・修正し、5.1 / 5.3の両方で検証 |
| 初回導入 | Android 15 / API 35 / x86_64 / 4KBでDebian 12、XFCE、日本語環境、Chrome、Node.jsの構築完了 |
| 中断と再開 | APK更新によるプロセス終了後も同じrootfs inodeと確認用ファイルのSHA-256を維持して再開 |
| XFCE | 実際のSurface表示、縦横回転、Androidホームから管理画面・デスクトップへの復帰を確認 |
| 日本語 | MousepadでMozcを選択し、`nihongonyuuryoku`から「日本語入力」へ変換して保存 |
| Chrome | 152.0.7977.82の画面表示とHTTPSでGitHubリポジトリを表示。ログイン・同期・使用統計送信は使用せず |
| Node.js | Linux構築ログで22.23.2 / npm 10.9.8を確認 |
| 起動・停止 | 3回連続でXFCEが1組だけ起動。停止後は同じアプリUIDにLinux/PRoot/入力サービスの残留なし |
| X11異常終了からの復旧 | 検証用AVDのX11専用プロセスだけを終了させ、新しいX11とXFCEの1組への自動復旧・日本語の画面表示を確認 |
| バックアップ | 停止した環境をMediaStoreのDownloads/LinuxDesktopへ保存。693,419,242 bytes |
| 復元 | 同じバックアップを別IDへ復元し、確認用ファイルと日本語入りテキストのSHA-256が一致。日本語のXFCE表示とMousepadで保存済み日本語テキストの再表示を確認。全1,861シンボリックリンクのうち20件を復元先へ付け替え、元のrootfsへのリンクは0件 |
| 最終APK | 復元・X11復旧後も起動、通知、Androidホームからの復帰、停止を動画で記録（録画時間の指定68秒、符号化済み動画約65秒）。停止後はAndroidアプリ本体以外の同UIDプロセスが0件 |
| 実機インストール | Pixel 10a / Android 17 / API 37 / ARM64 / 4KBへ最終APKを新規インストール。versionName 1.2.0 / versionCode 21、端末内APKのSHA-256と検証済みAPKの一致、署名を確認 |
| 実機アプリ起動 | MainActivityの起動成功、前面表示、初回導入画面の描画を確認。起動直後の対象プロセスに致命的エラーと終了記録なし。実機のLinux導入は実施せず |
| 公開ページ | デスクトップ1440px・スマホ390pxのブラウザー表示で画像読み込み、横はみ出し、JavaScriptエラーを検査 |
| ダークテーマ・文字拡大 | 初回画面の文字色を修正し、横画面・文字倍率1.3でスクロールして導入/復元の操作を確認 |

エミュレーターはSELinux Enforcingで、アプリ自身は非rootのUIDです。診断用にAVDのadb rootを有効にしましたが、Linuxを起動するアプリへroot権限は付与していません。phantom processの監視を無効にする設定も行っていません。

実機へインストールしたAPKはソースコミット `fe4553d7219a1a8b42de996ab794bd2ac1237116` から作成した最終成果物で、再ビルドしていません。SHA-256は `0d51b069972bed41b1ef34bf39a4a4ece49fde4c99ab4ba1c3b2ab1b7b57b397`、署名証明書のSHA-256は `7549204fc23f96dc4ce3844d30a4ba4626950cedc38400c4d17128b38b1ca74c` です。2026-09-05 14:14 JSTに初回画面を確認し、ローカルの `physical-install.json` と `physical-launch.png` に記録しました。対象アプリIDは未導入で、旧アプリの削除やデータ消去、端末への入力注入は行っていません。

### 16KB環境の結果と未確認項目

APKの18ライブラリ、ARM64 AABの9ライブラリの全LOAD segmentについて16KB alignmentを検証し、双方のARM64ライブラリ、ホストスクリプト、全DEXが一致します。専用bootstrapもARM64 669個、x86_64 668個の実行形式/共有ライブラリの配置を検証しています。

Android 17のx86_64 16KBプレビューAVDでは、アプリ・bootstrap・Debianの展開は成功しましたが、ゲストの`/usr/bin/env`起動時にSIGBUS（signal 7）が発生しました。導入は失敗状態となり、ログと修復の入口を表示します。再試行でも再現しました。原因を特定したとはしておらず、Linuxデスクトップが16KBで動作したという結果には含めません。

ARM64実機でのLinuxの導入・実行（4KB / 16KBとも）、音声の試聴、実機のタッチ・物理キーボード操作、長時間負荷やGoogleアカウントへのログインは、この検証では未確認です。実機のアプリ起動確認はLinux実行の検証を代替しません。Google Playの審査・配信の承認もパッケージ検証とは別です。

[以前の検証履歴](history/testing-before-1.2.md)は、旧バージョンの参考資料です。
