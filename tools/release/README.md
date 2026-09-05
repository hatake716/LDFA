# LDFAの署名・リリース手順

現行ソースは既定ブランチ`main`です。独立した開発フォルダの例：

```bash
git clone --recurse-submodules --branch main https://github.com/hatake716/LDFA.git LDFA-google-play
cd LDFA-google-play
```

SDKの場所は`local.properties`、署名設定は`keystore.properties`に置きます。いずれもGit管理外です。秘密鍵本体はリポジトリ外で保管してください。

## 1.2.1の成果物

```text
release-assets/v1.2.1/
  LDFA-v1.2.1-release.apk       インストール用APK
  LDFA-v1.2.1-play.aab          Google Play提出用（ARM64）
  SHA256SUMS
  materials/                  日本語・英語の提出資料
  screenshots/                新しい画面のスクリーンショット
  verification/               検証結果・署名とパッケージの記録
  icon-512.png
  feature-graphic-1024x500.png
  ldfa-fgs-demo.mp4
```

`release-assets/`全体はGit管理外です。旧1.1.0の成果物は上書きせず、バージョン別のフォルダで区別します。

## ビルド

JDK 17、SDK 36、NDK 29.0.14206865を使用します。全submoduleを初期化してください。

```bash
./gradlew testDebugUnitTest :app:lintDebug :termux-runtime:lintDebug :embedded-x11:lintDebug
bash scripts/check-host-script.sh
bash scripts/test-host-controller.sh
bash scripts/check-x11-controller.sh
```

Google Play向けのAABは既定設定でARM64だけを含みます。

```bash
./gradlew :app:bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

インストール用の署名済みAPK：

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

エミュレーターと実機で同じAPKを検証する場合は、専用prefixのx86_64 bootstrapとPRootライブラリを用意し、次のABI指定で作成します。

```bash
./gradlew :app:assembleRelease -Pldfa.releaseAbis=arm64-v8a,x86_64
```

AABはその後にABI指定なしで作成します。実行基盤を同梱していないABIを指定しないでください。
bootstrapはアプリIDごとの絶対パスを含むため、公式Termuxの`com.termux`用zipを代用品にはできません。再ビルド方法は[bootstrap手順](../bootstrap/README.md)を参照してください。

## 署名

`keystore.properties.example`を参考に、ローカルの`keystore.properties`へ既存のアップロード鍵を設定します。設定がない環境ではreleaseは未署名の出力になります。

- アップロード鍵の例：`~/keystores/ldfa-upload-key.jks`
- キーストア・パスワードはGitHubへアップロードしません。
- デバッグ鍵とリリース鍵は異なります。
- Play App Signingの配信鍵がGitHub APKの鍵と異なる場合、相互の上書き更新はできません。データ移行はバックアップ・復元を使用します。

## パッケージ検証

APKとAABのマニフェストをデコードし、両方で次の検査を行います。

```bash
python3 scripts/check-play-permissions.py /path/to/apk-manifest.xml /path/to/aab-manifest.xml
```

`REQUEST_INSTALL_PACKAGES`、ユーザー補助サービス、共有ストレージ権限がないことと、必要な前景サービス権限が残っていることを検査します。ソースXMLだけでなく完成した成果物を使用してください。


```bash
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
java -jar /path/to/bundletool.jar validate --bundle=app/build/outputs/bundle/release/app-release.aab
bash /path/to/Android/Sdk/build-tools/36.0.0/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

同梱ファイルの照合とELF検査は次のコマンドでも実行できます。

```bash
python3 scripts/verify-release-contents.py --apk path/to/release.apk \
  --bundle path/to/release.aab --report path/to/contents-report.json
```

APKとAABのアプリID、バージョン、targetSdk、ABIを確認します。全ネイティブELFのLOAD segment alignmentと、対応するAPK / AAB内のARM64ライブラリ・ホストスクリプトが一致することも確認します。

`extractNativeLibs=true`（`jniLibs.useLegacyPackaging = true`）を維持してください。ネイティブPRootをAPKから展開された実行可能領域で起動するために必要です。

署名検証の自己署名証明書・タイムスタンプの警告と、実際の署名検証失敗を区別します。Androidアプリの署名は一般のWeb PKI証明書とは異なります。

## 公開

検証済みAPK、SHA256SUMSとリリースノートをGitHub Releaseに添付します。Google Play提出用AABとストア素材はローカルのバージョン別フォルダへ保存します。

Google Play掲載文面・前景サービスの説明・動画の用途は[play-console.md](play-console.md)を参照してください。AAB・資料作成、Google Playへのアップロード、審査通過、公開はそれぞれ別の状態として記録します。
