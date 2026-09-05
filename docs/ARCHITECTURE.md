# LDFAの内部構成

対象は1.2.0です。アプリIDは`com.hatake716.linuxdesktop`、実行環境のprefixは`/data/data/com.hatake716.linuxdesktop/files/usr`です。

## モジュール

| モジュール | 責務 |
| --- | --- |
| `app` | Compose UI、導入とセッションの状態、PRootの所有、バックアップ |
| `termux-runtime` | TermuxのActivity / Service、内蔵コマンド実行、bootstrap展開 |
| `embedded-x11` | X11ビューアー、Binderによる接続、JNIと描画の補強 |
| `vendor/termux-app` | 固定prefixとAndroid向けのローカルパッチを含むTermuxソース |
| `vendor/termux-x11` | 固定コミットのX11ソースと再帰submodule |

管理UIとビューアーはメインプロセス、Xorgは非公開サービスの`:x11`プロセスで動作します。
`:x11`ではTermuxのコマンドソケットを初期化せず、メインプロセスの接続を奪わないようにします。

## Linuxの導入

```text
ユーザーの導入操作
  → Foreground Serviceを開始
  → Application所有の導入ジョブ
  → APK内bootstrapをusr-stagingに展開・検査
  → 空のprefixに原子的に配置（既存の非空prefixを破壊しない）
  → 非公開RunCommandの設定とホストツールを確認
  → 環境のメタデータを一時領域で作成して確定（共有ストレージへの書き込みは任意）
  → native PRootのインストールworkerを開始
  → 非公開の一時環境名でDebianのrootfsを取得・展開
  → 展開完了後に本来のrootfsへ原子的に移動
  → 原子的にprovision requestを公開
  → 別の単層PRootでapt・XFCE・日本語環境・Chrome・Node.js・セッション設定を構築
  → ready
```

`LinuxDesktopApplication`のジョブとStateFlowはActivityの再作成では失われません。メインプロセスが終了した場合は、ディスク上のメタデータとworkerの生存状態を照合し、次回の管理画面表示時に一度だけ自動再開を試みます。明示的な修復は再試行の制限を解除します。

展開済みrootfsは削除しません。`dpkg --configure -a`、検証付きAPT更新、依存関係の修復を経て構築を続けます。失敗したゲストプロセスが終了マーカーを残せない場合も、Android側の所有Processが終了を監督プロセスへ通知します。要求ファイル待ちには上限があり、期限切れでプロセスを残し続けません。

パッケージを書き換える処理は、ChromeやNode.jsの追加導入も含めて単層PRootで実行します。二重のPRootでAPTのrename処理がENOSYSになる実行経路を避けます。`guest_apps_script`は既存の設定関数からゲスト用スクリプトを生成し、初回導入と既存環境の更新で共有します。起動時の更新は`prepare-apps`で要求を確定し、アプリがゲストを実行してから`finish-apps`で結果を照合します。失敗・キャンセル・期限切れ時は所有Processを回収します。

workerの明示的なexitも失敗状態へ反映し、ローダーのエラーはゲストのシェルが起動する前からログへ保存します。PIDの照合ではAndroidの`/data/data`と`/data/user/0`の別名も解決します。

進捗はゲストが原子的に書くphaseファイルから取得し、ホストの状態ファイルへ反映します。パーセンテージは構築段階の目安であり、ダウンロード済みバイト数や残り時間ではありません。

## AndroidとPRootの境界

Android向けPRootとloaderをAPK内のネイティブライブラリとして収録し、`nativeLibraryDir`から実行します。`extractNativeLibs=true`が必要です。実行環境は専用prefix向けにソースから作ったbootstrapを使い、公式Termuxの既成バイナリを流用しません。

通常の短い管理コマンドは非公開`RunCommandService`経由です。結果はリクエスト固有のPendingIntentで照合します。長時間の導入workerとLinuxセッションはApplication側がProcessを所有し、画面側のCoroutineだけに寿命を依存させません。

## 起動と停止

起動要求はApplication内でまとめます。同じ環境への重複要求は同じ結果を待ち、別環境の同時起動は拒否します。表示の切り替えはプロセス共通のMutexで直列化します。

```text
旧workerを停止・所有Processを回収
  → viewerと旧display serverを閉じる
  → 必要なセッション設定を確認
  → 専用サービスでXorg :1を開始
  → 実ソケットとDebianからのxset接続を検査
  → Binder経由でviewerを接続
  → Surface / EGL準備と実際の描画更新を確認
  → XFCEの実プロセスとウィンドウを確認
  → 新しい描画更新を確認
  → active sessionを確定
```

起動失敗時はホスト停止に加え、所有するネイティブPRootを回収します。停止ではviewerの終了応答を待ってからdisplay serverを停止します。世代IDを使い、古い通知・接続・停止要求が新しいセッションを終了しないようにします。

`OwnedProotProcess`はAndroidの`Process.destroyForcibly()`がSIGTERMに留まる問題を補います。保持したProcessのPID候補をアプリUID・親PID・起動時刻で照合し、PRootを停止させてから同じ`TracerPid`を持つゲストだけを終了します。最後にPRootを終了して待機します。孤立したD-Busや入力サービスも追跡対象に含みます。起動要求は前回のファイルを破棄し、一時ファイルへの書き込み後に確定します。Linuxへ渡す`TMPDIR`は共有マウントの`/tmp`です。

通常表示でバッファ転送に失敗した場合はlegacy描画を試します。Surface / EGL自体を作れない場合は同じ経路の再試行を避け、診断付きの起動失敗とします。VNCフォールバックはありません。

## 継続と復旧

`DesktopKeepAliveService`はspecialUseの前景サービス、通知、WakeLockとheartbeatを使用します。Linuxの準備中も待機状態と誤認して終了しません。

ビューアーの復帰時は、同じ世代のサービス・接続・実プロセスを確認します。正常な復帰は`/proc`の軽い検査を使い、欠落があるときだけホストの詳細検査へ進みます。XFCEセッション内ではウィンドウマネージャーなどの子プロセス終了を監視し、必要に応じて再構築します。

ネイティブ描画側は、停止フラグ、EGL失敗時の待機解除、GPU fenceの待機上限、正しいcond clock、再接続の世代確認、成功したEGL swapの通し番号を使用します。生成パッチは`embedded-x11/scripts/`で管理し、vendorを直接書き換えません。

## 音声

DebianのPulse clientは`/tmp/ldfa-pulse/native`を使います。ホスト側の`$PREFIX/var/run/ldfa-pulse-bridge/native`を明示的にbindし、共有tmpの掃除からソケットを分離します。SHMとmemfdの転送を無効にし、Unixソケット経由でAndroidの音声sinkへ渡します。

音声準備は時間を制限し、失敗してもGUI起動を継続して診断情報を残します。ユーザーのPulse / ALSA設定を一律に上書きせず、アプリ所有のdrop-inを使います。

## データとバックアップ

管理情報は`files/home/.local/share/linux-desktop-for-android`以下です。rootfsの解決はPRoot-DistroのXDG配置、新しいprefix内配置、従来のinstalled-rootfs配置に対応します。

`ContainerOperationLocks`は環境単位で起動・停止・削除とバックアップを直列化します。切り替え時は、終了する旧環境と起動する新環境の両方を一定の順序でロックし、旧環境のバックアップとも競合させません。バックアップは導入済みかつ停止状態で、workerのPIDが生存していない環境だけを対象とします。出力を`.part`に書き、flush・sync・close後に確定します。既存アーカイブを失敗時に削除しません。

Android 10以降はMediaStoreのpending状態でダウンロードへ書き出します。Android 8〜9は掃除対象のstagingから永続保存先へ移動します。dataSyncサービスの時間制限通知では処理をキャンセルして前景状態を解除します。

`.ldfa`はJSON manifest、gzip圧縮tar、SHA-256 trailerからなります。復元はアーキテクチャ、ハッシュ、展開先パスを確認し、新しいIDへ展開します。既存環境には上書きしません。共有ファイル、仮想ファイルシステム、キャッシュ等は保存範囲外です。暗号化は実装していません。

復元時はマニフェストに記録されたアプリ領域・コンテナIDを基準に、PRootのハードリンク代替として作成された絶対シンボリックリンクを復元先rootfsへ付け替えます。通常のLinuxの相対リンクや別コンテナへのリンクは変えず、復元先から外れるパスは拒否します。これにより日本語ロケールなどが元のコンテナに依存せず使えます。
