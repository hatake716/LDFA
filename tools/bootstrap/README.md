# LDFA専用bootstrapのビルド

`vendor/termux-app/app/src/main/cpp/bootstrap-{aarch64,x86_64}.zip`は、専用prefix
`/data/data/com.hatake716.linuxdesktop/files/usr`向けにソースから構築します。
公式Termuxの配布物は`com.termux`がバイナリに埋め込まれているため流用できません。

両ZIPをリポジトリに収録し、[termux-runtime/build.gradle](../../termux-runtime/build.gradle)の
`verifyBootstraps`でSHA-256を検証します。欠落や不一致を検出してもローカルファイルを削除せず、
別のprefixのZIPをダウンロードしません。ARM64はPlay版、x86_64はエミュレーターでの動作検証に使います。
32bitのARM / x86は配布対象に含めません。

## ソースとビルダー

- [termux-packages](https://github.com/termux/termux-packages)のベースコミット：`0223902ddb42a5572812044e64310ada0f658ff2`
- 適用するパッチ：[ldfa-termux-packages.patch](ldfa-termux-packages.patch)
- 1.2.0のx86_64ビルドに使ったイメージ：`ghcr.io/termux/package-builder@sha256:374fedda8d2ce7a8ab499735d39329301c4f2f18ea4411b3cf7c93d4668768ab`

既存の作業ツリーを流用せず、独立したクローンを用意します。以下の`/path/to`は実際の場所へ変更します。

```bash
git clone https://github.com/termux/termux-packages.git termux-packages-ldfa
cd termux-packages-ldfa
git checkout 0223902ddb42a5572812044e64310ada0f658ff2
git apply /path/to/LDFA/tools/bootstrap/ldfa-termux-packages.patch
mkdir -p output
```

コンテナの`builder`（uid/gid 1001）が作業用クローンと`output`へ書き込めるようにします。
依存パッケージのビルドがFUSEを使うため、`/dev/fuse`と`SYS_ADMIN`をコンテナに渡します。
ホストのSDKや既存のAndroidプロジェクトはマウントしません。

```bash
docker run -d --name ldfa-bootstrap-builder --user builder \
  --device /dev/fuse --cap-add SYS_ADMIN --security-opt seccomp=unconfined \
  -v "$PWD:/home/builder/termux-packages" \
  ghcr.io/termux/package-builder@sha256:374fedda8d2ce7a8ab499735d39329301c4f2f18ea4411b3cf7c93d4668768ab \
  sleep infinity

docker exec -w /home/builder/termux-packages ldfa-bootstrap-builder \
  bash ./scripts/build-bootstraps.sh --architectures x86_64 \
  --add proot --add proot-distro --add tmux --add pulseaudio --add xkeyboard-config
```

ARM64を再構築するときは`--architectures aarch64`に変更し、別のビルド用クローンとコンテナを使います。
依存もソースビルドになるため、完了まで長時間かかります。`--android10`は指定しません。
これはapt / dpkgを除外するオプションであり、このアプリのネイティブPRoot方式とは異なります。

## 成果物の組み込み

1. 出力された`bootstrap-<arch>.zip`を`vendor/termux-app/app/src/main/cpp/`へコピーします。
2. ZIPのSHA-256を計算し、`termux-runtime/build.gradle`の対応する値を更新します。
3. 同じビルドのdebからネイティブPRootを取り出します。ホスト側に`ar`、`tar`、`patchelf`が必要です。

```bash
bash tools/bootstrap/package-native-runtime.sh \
  /path/to/termux-packages-ldfa/output x86_64 app/src/main/jniLibs/x86_64
```

ARM64では引数を`aarch64 app/src/main/jniLibs/arm64-v8a`に変更します。
スクリプトはPRoot、loader、talloc、android-shmemを配置し、SONAME・RUNPATHを補正します。
16KBのELF alignmentは、最終APK / AABに対して[verify-release-contents.py](../../scripts/verify-release-contents.py)で検査します。

ZIPを変更した後は、古い埋め込みバイナリを残さないようにクリーンビルドします。

```bash
./gradlew :termux-runtime:clean :app:clean
./gradlew :app:assembleDebug
```

実行環境のZIP自体に含まれる実行ファイルと共有ライブラリも検査し、対象ABIのエミュレーターまたは端末で
新規展開、Debian導入、XFCE表示まで確認します。アプリが起動するだけではbootstrapの検証になりません。

## パッチの内容

- アプリIDとprefixを`com.hatake716.linuxdesktop`に変更。
- `build-bootstraps.sh`で実行時に必要なパッケージの集合を指定し、ビルドだけに必要な依存をZIPから除外。
- bzip2をlibbz2のサブパッケージとして取得。
- prefix関連変数のexportとSavannahミラーの補正。
- 一部パッケージのライセンスファイル配置の違いへの対応。
- 従来の`run-docker.sh`用のuid/gid補正。上記の独立コンテナ手順ではこのラッパーを使用しません。
