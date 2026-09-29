<p align="center"><img src="docs/brand/ldfa-icon.svg" width="96" alt="LDFA アプリアイコン"></p>

# LDFA — Linux Desktop for Android

[![Android CI](https://github.com/hatake716/LDFA/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/hatake716/LDFA/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/hatake716/LDFA)](https://github.com/hatake716/LDFA/releases)

Androidに、DebianとXFCEの作業環境を。
LDFAは、Linuxの導入・起動・日本語入力・バックアップをひとつのAndroidアプリにまとめています。
root化や、別のTermux・X11アプリのインストールは不要です。

**最新リリース：1.2.6 / versionCode 27**

1.2.6では、Linuxデスクトップの音声がAndroidから出ない不具合を修正しました。Pixel 10a（Android 17）でデスクトップを開き、音声が再生されることを利用者が確認しています。

[リリースとAPK](https://github.com/hatake716/LDFA/releases) · [導入手順](docs/INSTALLATION.md) · [プライバシーポリシー](https://hatake716.github.io/LDFA/privacy.html)

## はじめる

1. [GitHub Releases](https://github.com/hatake716/LDFA/releases)の現行APKをインストールします。AABはGoogle Playへの提出用で、直接インストールするファイルではありません。
2. アプリを開き、空き容量5GB以上と通信環境を確認します。
3. デスクトップの名前を入力し、**「Linuxをインストール」**を押します。
4. Debian、XFCE、日本語入力などの構築が終わったら、ホームの**「デスクトップを開く」**を押します。

初回は大きなダウンロードがあるため、Wi-Fiと充電をおすすめします。所要時間は端末と回線によって変わります。
画面を移動しても導入は継続します。Androidによるプロセス終了や通信障害があった場合はアプリを開き直し、必要に応じてカードの修復操作から再開してください。

既存の`.ldfa`バックアップがある場合は、初回画面の**「バックアップから復元する」**から、新しい環境として取り込めます。

## 画面

<p><img src="docs/screenshots/onboarding.png" width="240" alt="初回の導入画面"> <img src="docs/screenshots/home.png" width="240" alt="デスクトップの管理画面"> <img src="docs/screenshots/desktop.png" width="240" alt="日本語テキストを表示するXFCE"></p>

画面は署名済み1.2.0を検証用エミュレーターで動かして撮影しています。

## 1.2.6の変更

1.2.5の修正後も、実機では音声が出ていませんでした。実機のPulseAudioのログから、次の原因を特定して修正しました。

- 以前のPulseAudioは、強制終了されたときに記録ファイル（pidファイル）を残していました。その番号がほかのアプリのプロセスに再利用されると、Androidの制限で中身を確認できません。そのためPulseAudioは「すでに起動している」と判断し、起動のたびに終了していました。Pixel 10aでは9月18日のファイルが残っており、それ以降、音声は一度も作られていませんでした。
- LDFAのPulseAudioが動いていないことを確認したうえで、古い記録ファイルを削除してから起動するようにしました。
- 停止処理は、名前を確認できたPulseAudioか、自分が起動したプロセスにだけ信号を送ります。

Pixel 10a / Android 17で、古い記録ファイルを意図的に残した状態でもPulseAudioは1秒で応答し、6秒で音声の準備が完了しました。Androidの音声プレイヤーの作成と、利用者による再生の確認が取れています。1.2.5の変更（起動の待機、自動起動の停止、自動復旧）も含みます。

## 1.2.5の変更

1.2.5は実機では音声が出ず、Google Playには提出していません。変更内容は1.2.6に含まれます。


LinuxデスクトップのPulseAudio音声が、Androidのスピーカーやイヤホンへ出力されるようになりました。

- 原因：アプリ内PRoot経由の実行方式では、ARM端末でPulseAudioの起動に数秒かかります。従来は1〜2秒で応答がないと起動途中のデーモンを停止していたため、Android側の音声出力（OpenSL ES）が作られませんでした。Pixel 10a（Android 17）の記録でも、デスクトップを何度起動してもLDFAの音声プレイヤーは一度も作られていませんでした。
- PulseAudioの準備完了を制御ソケットの応答で判定し、PRoot越しの起動を待つようにしました。上限は起動60秒、全体90秒です。
- 確認用コマンドがPulseAudioを自動起動しないようにしました。自動起動されたデーモンはアイドル20秒で終了し、Debianとの接続口も消えていました。LDFAが起動するデーモンは、アイドル時も終了しません。
- 音声の準備はデスクトップの起動と並行して行います。デスクトップは最大10秒だけ待ってから開き、音声はソケットができた時点で使えるようになります。
- 実行中にPulseAudioが終了した場合は約10秒以内に検知して作り直します（1回の起動につき最大5回）。
- PulseAudioのログを保存し、起動ログに音声の準備時間を記録します。
- `.ldfa`から復元した環境の初回起動で、後片付けの処理が構文エラーで実行されていなかった問題も修正しました。既存環境のデスクトップ用スクリプトは、次回起動時に自動で更新されます。

起動の遅い端末を模擬した検証用エミュレーター（Android 15 / x86_64）で確認しました。1.2.4では音声出力が作られません。1.2.5では、DebianのテストトーンとChromeのWeb AudioがAndroidの音声トラックとして再生されます。ただし実機では、上記の古い記録ファイルの問題が残っていました。[検証条件](docs/TESTING.md)を参照してください。

## 1.2.4の変更

<p><img src="docs/screenshots/startup-progress.png" width="280" alt="LDFA 1.2.4の起動ログとパーセント付き進捗バー"></p>

- 保存済み環境の設定とD-Bus識別子を直接確認し、正常な場合はLinuxへ入り直す処理を省きます。設定不足・不整合がある場合は従来の検査・修復を行います。
- X11への接続と描画確認をまとめ、描画成功後の固定待ち時間をなくしました。LinuxデスクトップがAndroidの画面に描画されたことは引き続き確認します。
- 起動の処理段階に連動するバーと0〜100%の表示を追加しました。割合は工程の進捗の目安で、残り時間やダウンロード量を表すものではありません。描画確認の成功で100%になります。
- 起動ログには各段階のパーセントと開始からの経過時間も表示します。失敗時は最後の進捗とログを残し、画面回転・再作成でも保持します。

同じAPI 36エミュレーターで、停止済み環境を開いてから描画確認完了までの中央値は **15.7秒 → 7.9秒（約50%短縮）** でした。各版の最初の1回を除いた3回で比較しています。初回導入や実機での短縮率を示す値ではありません。[検証条件](docs/TESTING.md)と[起動の実演動画](https://github.com/hatake716/LDFA/releases/download/v1.2.4/ldfa-startup-progress-demo.mp4)を参照してください。

## 1.2.3の変更

<p><img src="docs/screenshots/startup-logs.png" width="280" alt="LDFA 1.2.3の起動ログ表示"></p>

1.2.3の最終APKで撮影した画面です。[起動ログの実演動画](https://github.com/hatake716/LDFA/releases/download/v1.2.3/ldfa-startup-logs-demo.mp4)で、保存済み環境を開いてからデスクトップが表示されるまで確認できます。

保存済み環境の「デスクトップを開く」を押すと、起動処理の段階とログを画面に表示します。管理画面からX11画面へ切り替わってもログは継続し、デスクトップの描画確認が終わると自動で閉じます。

今回の起動で追加されたLinux・X11・XFCEのログを表示します。自動スクロールを止めて読み返したり、テキストを選択してコピーできます。起動に失敗した場合は、その起動のログを閉じるまで残します。画面回転によって起動処理をやり直すことはありません。

## できること

- 複数のDebian 12 / XFCE環境を作成・切り替え
- 内蔵X11によるLinuxデスクトップ表示
- Google ChromeとNode.js 22 LTSの導入
- 日本語ロケール、Noto CJK、Fcitx5 / Mozc
- タッチ・マウス・ソフトウェアキーボード・物理キーボードによる操作
- デスクトップ全体の表示倍率100〜250%、特殊キーバーの表示切り替え、JIS / US配列
- PulseAudio経由のAndroid音声出力
- 停止したLinux環境を`.ldfa`ファイルへバックアップし、新しい環境へ復元
- ターミナル、ログ、導入・起動の修復

## 1.2.2の変更

- Google Playで指摘された`org.lsposed.hiddenapibypass:hiddenapibypass`を削除しました。非公開APIの制限解除処理も除外しています。
- 内蔵Termuxのプロセス停止を公開APIの`Process.destroyForcibly()`へ移行しました。プロセスIDを取得できない場合も、対象のプロセスだけを停止します。
- SELinuxの診断情報は、通常のアクセス権で読めるファイルと公開APIから取得します。非公開の内部属性・機能フラグ・UID名が取得できない場合は不明として扱い、診断のために制限を解除しません。
- 依存関係の再混入をビルド時に拒否し、完成したAPK・AABのDEXと依存情報も検査します。

## 1.2.1の変更

- 不要なAndroid APKインストール権限と、X11由来のユーザー補助サービス・有効化設定を削除しました。通常のソフトウェア・物理キーボード入力は引き続き使用できます。Androidが予約するシステムキーをユーザー補助サービス経由で取り込む機能は提供しません。
- Linuxの準備中も通知の「準備を停止」から中断できます。保存済みデータを残し、ユーザーが再開するまで自動再開を抑止します。
- 準備時のダウンロード・展開はdataSync、対話的なLinux実行はspecialUseとして扱い、Google Playの申告資料を更新しました。
- 完成したパッケージから不要な権限が消えていることを継続的に検査します。

## 1.2.0の変更

- 初回の説明からLinuxの導入までを、一続きの操作に整理しました。
- 実行環境の準備と導入処理をアプリ側で保持し、画面の再作成で取り消されないようにしました。
- 展開済みのDebianを再利用し、中断したパッケージ構築を再開します。失敗時に環境を自動削除しません。
- 導入中は実際の構築段階を表示し、詳細ログは必要なときに開けます。
- 起動済みの環境からデスクトップへ戻る操作と、停止操作を分けました。
- 起動失敗・環境切り替え時のネイティブプロセスの終了処理を補強しました。
- Androidのプロセス終了APIだけでは残るPRootを、所有者と起動時刻を確認して停止し、そのPRootが管理するLinuxプロセスも終了します。
- ChromeやNode.jsの準備も単層PRootへまとめ、二重実行によりAPTのファイル操作が失敗する経路を修正しました。
- デスクトップの一時保存先をLinux側の`/tmp`へ設定し、Chromeがプロファイルのソケットを作成できない問題を修正しました。
- バックアップと起動・削除の競合、Android 8〜9でバックアップが一時領域の削除に巻き込まれる問題を修正しました。
- バックアップ復元時に、PRootが作成する内部リンクを復元先へ付け替え、日本語ロケールなどが元の環境を参照する問題を修正しました。
- 新しいアダプティブアイコンと配色、Android 16を対象とした設定に更新しました。

検証条件・結果は[検証資料](docs/TESTING.md)に記録します。過去の実機確認を、新しいバージョンの確認結果として扱いません。

## 動作環境とデータ

| 項目 | 内容 |
| --- | --- |
| Android | 8.0（API 26）以降。targetSdk 36 |
| Google Play提出用AAB | ARM64（arm64-v8a） |
| 空き容量 | 新規導入時5GB以上。追加ファイルやバックアップには別途容量が必要 |
| 通信 | Linux初回導入・パッケージ更新時に必要 |
| アプリID | `com.hatake716.linuxdesktop` |
| Linuxの保存先 | Androidが管理するLDFA専用のアプリ領域 |

アプリのアンインストールや「ストレージを消去」は、保存したLinux環境も削除します。
必要なデータは、事前に「ツール → バックアップ」でアプリ外へ保存してください。
Android 10以降の保存先は**ダウンロード / LinuxDesktop**です。Android 8〜9はアプリ専用の外部ファイル領域に保存されるため、表示された保存先から端末外へコピーしてください。

公式TermuxとはアプリIDと保存領域が異なるため共存できます。
旧GitHub配布版v1.1.0以前は`com.termux`でした。旧版からの移行は、旧版でバックアップを作成して新版で復元します。別の署名のAPKに上書きできない場合も、データを保存する前にアンインストールしないでください。
Google Play版のアプリ署名とGitHub APKの署名は、Play App Signingの設定によって異なる場合があります。

## Linuxとしての制約

LDFAはAndroidカーネル上でPRootを使います。PC向けLinuxの仮想マシンではなく、systemd、デバイスアクセス、カーネル機能を必要とするソフトウェアには制約があります。

ChromeはPRootとの互換性のため`--no-sandbox`などのオプションを使用します。Linux PCのChromeと同じブラウザー内の隔離は提供しません。
Linuxの`desktop`ユーザーにはパスワードなしの`sudo`を設定しますが、Androidのroot権限を得るものではありません。

Androidの省電力・メモリ管理による終了を完全には防げません。LDFAは前景サービス、実行中の通知、プロセス監視、再接続処理を使って継続と復旧を支えます。

ネイティブライブラリの16KB配置を検査していますが、ARM64の16KB端末でLinuxを導入して動作させる検証は未実施です。開発用x86_64 APKでは、Android 17の16KBプレビュー環境でDebianゲストの起動時にSIGBUSを確認しています。実行確認に使用したAndroid 15の4KB環境とは区別してください。

## 開発・ビルド

```bash
git clone --recurse-submodules https://github.com/hatake716/LDFA.git LDFA-google-play
cd LDFA-google-play
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

JDK 17、Android SDK 36、NDK 29.0.14206865を使用します。
`local.properties`にSDKの場所を設定してください。署名設定はGit管理外の`keystore.properties`に置き、秘密鍵本体はリポジトリ外で保管します。

```bash
bash scripts/check-host-script.sh
bash scripts/test-host-controller.sh
bash scripts/test-startup-controller.sh
bash scripts/check-x11-controller.sh
./gradlew testDebugUnitTest :app:lintDebug :termux-runtime:lintDebug :embedded-x11:lintDebug
./gradlew :app:assembleRelease :app:bundleRelease
```

署名付きのAPK・AAB、Google Playの提出文面・素材については[リリース手順](tools/release/README.md)と[Play Console資料](tools/release/play-console.md)を参照してください。
署名設定がない環境のrelease出力は未署名です。

| ディレクトリ | 役割 |
| --- | --- |
| `app/` | Compose画面、導入・セッション管理、バックアップ、Linux用スクリプト |
| `termux-runtime/` | 内蔵ターミナル、APK内の実行環境の展開 |
| `embedded-x11/` | X11表示・サービス・ライフサイクル補強 |
| `vendor/` | Termux、Termux:X11と依存ソース |
| `scripts/` | Linux / X11コントローラーの検証 |
| `tools/bootstrap/` | 専用アプリID向けbootstrapの再ビルド手順 |
| `release-assets/` | ローカルの署名済み成果物・提出素材・検証記録（Git管理外） |

内部構成は[アーキテクチャ](docs/ARCHITECTURE.md)を参照してください。

## ライセンスと関連プロジェクト

LDFAはGPL-3.0で公開しています。詳細は[LICENSE](LICENSE)を参照してください。
同梱する各コンポーネントのライセンス・著作権表示も保持しています。

- [Termux](https://github.com/termux/termux-app)
- [Termux:X11](https://github.com/termux/termux-x11)
- [PRoot-Distro](https://github.com/termux/proot-distro)
- [Debian](https://www.debian.org/)
- [XFCE](https://www.xfce.org/)
