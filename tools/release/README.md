# LDFA リリースビルド（Google Play 提出用）

## 成果物

Play へ提出するのは **AAB**（App Bundle）:

```bash
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

リリースは **arm64-v8a のみ**（app/build.gradle.kts の release ブロックで固定）。
理由: native-proot の prebuilt（jniLibs/libpdrt.so ほか）は arm64 のみ存在し、
残り 3 アーキの bootstrap zip は upstream com.termux ビルドのままで
このアプリの prefix では動作しない（同梱してはならない）。
デバッグビルドは全 ABI のままなので x86_64 エミュレータでの開発は従来どおり。

## 署名（アップロード鍵）

- キーストア: `~/keystores/ldfa-upload-key.jks`（**リポジトリ外**・コミット禁止）
  - alias `ldfa-upload`, RSA 4096, 有効期間 30 年
- 資格情報: repo ルートの `keystore.properties`（gitignore 済み）。
  雛形は `keystore.properties.example`
- `keystore.properties` が無い環境（CI・新規 clone）では release は**未署名**で
  ビルドされる（失敗はしない）

新しい鍵を作る場合:

```bash
keytool -genkeypair -keystore ~/keystores/ldfa-upload-key.jks \
  -alias ldfa-upload -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=LDFA, OU=Play Upload, O=hatake716"
```

**キーストアとパスワードは必ずリポジトリ外にバックアップすること**
（パスワードマネージャ等）。Play App Signing に登録すればアップロード鍵は
紛失時に Google 経由でリセット可能だが、バックアップが第一。

## 検証

```bash
# AAB の署名
jarsigner -verify app/build/outputs/bundle/release/app-release.aab

# 同梱 ABI が arm64-v8a のみであること
unzip -l app/build/outputs/bundle/release/app-release.aab | grep '\.so$' | awk '{print $4}' | cut -d/ -f1-2 | sort -u

# APK 側の確認（applicationId / native-code / extractNativeLibs=true）
./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging \
  app/build/outputs/apk/release/app-release.apk | grep -E "package:|native-code"
```

`extractNativeLibs=true`（packaging の useLegacyPackaging）は必須 —
libpdrt.so を nativeLibraryDir から execve する W^X 回避の前提条件。
