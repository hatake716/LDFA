# LDFA Linux GUI 起動修正 引き継ぎ

最終更新: 2026-08-22 (JST)

## 1. この文書の目的

この文書は、Android 内蔵の X11 viewer を通して Debian PRoot 上の XFCE を表示できず、黒画面、起動失敗、ANR、またはアプリ全体の native crash に至る問題について、ここまでに行った調査・実装・検証と、次に行うべき作業を引き継ぐためのものです。

重要な点は次の三つです。

- ソース上で複数の確定した不具合を発見し、今回の P0 修正は canonical tree に統合済みです。
- pinned pristine submodule からの canonical clean full build、155 unit tests、3 module lint、4 ABI native package gate、zipalign、APK 署名まで成功しています。
- その後、API 35 x86_64・4 KB page の一時 AVD で Debian 12 rootfs の実インストールから native X11、XFCE、Mozc、共有ストレージ、Surface 再作成、停止／再起動、強制 VNC fallback まで動的検証しました。物理 ARM64 と 16 KB page の最終受け入れは引き続き未完了です。

### 1.1 2026-08-22 継続デバッグの要約

前回の source/build/package 検証後、`emulator-5556` の一時 AVD と保存済みコンテナ `debian-xfce-75b6458a` を使って E2E を続行しました。今回追加で確定・修正した root cause は次です。

1. `proot-distro` の現在の `debian:latest` は Trixie になり、PRoot 下の apt 3 rename 系処理が `ENOSYS` で失敗しました。製品の再現可能な基準を Debian 12 Bookworm へ固定し、`LINUX_IMAGE="debian:12"` と modern install の `--name` を使うよう修正しました。
2. `PROOT_NO_SECCOMP=1` は PRoot の syscall emulation を無効にして rename failure を悪化させていました。明示的に unset し、install login だけ `--shared-tmp` を外して apt の一時ファイル消失を防ぎました。
3. upstream `TermuxService.onDestroy()` は `$PREFIX/tmp` 全体を消すため、RUN_COMMAND ごとの service 終了で X1/X2 socket が消えていました。desktop lifetime 中は app が service binding lease を保持し、display/viewer teardown 後だけ release するようにしました。
4. 生成した `MainActivity` の公開を `onCreate()` 完了後へ遅らせた結果、upstream `TouchInputHandler` constructor が null singleton を参照していました。constructor／listener は所有中の Activity を直接使い、後続 callback の no-arg API だけ singleton を使うよう generator を修正しました。
5. Home/Recents で Surface が外れた正常な background 状態を heartbeat が renderer failure と誤認して Xorg/XFCE を再起動していました。foreground のときだけ Surface/present を検査し、background では service/socket/Debian probe を authority とするよう修正しました。
6. VNC runner 内の `pkill -f "Xtigervnc :2"` は、完全な heredoc を argv に持つ自分自身の `bash -lc` に一致して runner を終了させていました。runner 内の broad `pkill -f` を削除しました。
7. 強制 native viewer failure テストで、非同期 `ServiceConnection.onServiceConnected()` 内の `ActivityNotFoundException` が main thread へ逃げ、fallback 前に `com.termux` を crash させる追加 P0 を再現しました。controller が例外を捕捉して repository の ready 待機へ公開し、native teardown から VNC `:2` へ進めるよう修正しました。

4 KB AVD で確認した最終結果は次です。

```text
native normal X11 / XCB / EGL / successful present       PASS
Debian 12 XFCE desktop / terminal / panel / Dock          PASS
Fcitx5 + Mozc: nihongo -> 日本語 candidate -> commit      PASS
Android shared directory guest <-> Android round trip     PASS
portrait <-> landscape Surface recreation                 PASS (same X11/XFCE PID)
Home 50 seconds -> foreground                             PASS (same X11/XFCE PID)
UI stop -> X1/process/lease cleanup -> restart             PASS
forced viewer failure -> VNC :2 -> noVNC Activity          PASS
VNC XFCE DISPLAY=:2 / X2 socket / HTTP marker             PASS
VNC UI stop -> X2/process cleanup                          PASS
```

16 KB page の API 37.2 x86_64 AVD では、APK 自身の 16 KB package/alignment gate より前ではなく、Debian x86_64 loader/rootfs の page-alignment 制約で PRoot login が失敗しました。この x86_64 エミュレーション結果を ARM64 16 KB 実機の否定材料にはせず、物理 ARM64 16 KB device を最終受け入れ条件として残します。

### 1.2 2026-08-22 起動確認後の製品調整

ユーザーから Linux GUI が無事に起動したとの確認を受けた後、次の三点を追加しました。

1. 「Debian XFCEを開く」を押してから viewer へ切り替わるまで、`デスクトップを起動しています` と、初回や更新直後は数分かかる場合がある旨を管理画面中央へ表示します。通常の作成・停止・削除などは従来どおり短い `処理しています` 表示です。
2. Debian XFCE の作成時と既存環境の次回起動時に、guest の `dpkg --print-architecture` を検査し、Google 公式の `google-chrome-stable_current_amd64.deb` または `google-chrome-stable_current_arm64.deb` を自動導入します。DEB の Package/Architecture field を検証し、専用 desktop entry と PRoot 用 launcher を作ります。32-bit guest では代替ブラウザへ黙って置換せず、Chrome を未対応として Debian の構築自体は継続します。
3. 設定画面の「X11ディスプレイを開く」は実動作しない死に項目だったため、row と callback を設定画面から削除しました。ツール画面側の機能する表示導線は残しています。

Chrome は APK 内へ約 130–140 MB の版固定 DEB を格納せず、Debian provisioning 中に公式 current package を取得します。このため初回はネットワーク接続が必要ですが、APK の肥大化と古いブラウザの固定を避け、Chrome 自身が追加する署名済み APT source から更新できます。Android PRoot 内では Chromium の通常の namespace/setuid sandbox を確立できないため、launcher は `--no-sandbox` を使用します。この制約は `SECURITY.md` に明記済みであり、通常の Debian/Chrome と同等の隔離を保証してはいけません。

最終 debug APK を API 35 x86_64 AVD へ上書き導入し、次を動的に確認しました。

```text
起動待機のタイトルと注意書き                            PASS
設定画面から死んだ X11 row/callback が消失              PASS
Google Chrome stable 151.0.7922.173-1 (amd64)           PASS
Chrome で https://example.com を実表示                   PASS
二回目の ensure-apps は再取得せず google_chrome=1       PASS
停止後の X11/XFCE/Chrome/PRoot/tmux/socket cleanup      PASS
```

## 2. 正本と作業境界

今後の作業に使う正本は次です。

```text
/home/takeshi/StudioProjects/LDFA-fix
branch: fix/linux-gui-startup
base HEAD: 63b4ab1012de8d4c3cc6e2119fb4ae28dc5966cb
origin: https://github.com/hatake716/LDFA.git
```

submodule の基準は次です。

```text
vendor/termux-app  3df69d1da197dd9bd71a3bafd902dffd720576b4
vendor/termux-x11  50ac80fb2d4a475e323e752d17fcc0483c3c99fc
```

現在、両 submodule の作業ツリーは clean です。Termux:X11 の upstream ソースを直接変更せず、`embedded-x11/scripts/prepare_embedded_java.py` と `prepare_embedded_native.py` が pinned submodule から hardened overlay を生成する構成にしています。

次の二つは参照用であり、最終正本ではありません。

```text
/home/takeshi/StudioProjects/LDFA
/home/takeshi/StudioProjects/Linux-Desktop-for-Android
```

- `LDFA` は Debian 化した旧作業ツリーですが、公開版 UI、versionCode、CI、追跡状態に不整合がありました。
- `Linux-Desktop-for-Android` はビルド可能な Ubuntu 系参照実装ですが、Debian を使う今回の製品仕様そのものではありません。
- どちらかのディレクトリを `LDFA-fix` へ丸ごとコピーしないでください。

2026-08-22、ユーザーから今回の変更を GitHub の `main` へ反映し、README を現状へ合わせて書き直す明示指示を受けました。v0.9.0 の GitHub プレリリース作成は物理実機テストの結果待ちであり、main 更新と同時には行いません。

## 3. 公開版から保持したもの

`LDFA-fix` は公開 `origin/main` を基準にしています。X11 修正と無関係な公開機能を旧ツリーで上書きしないよう、以下を保持しました。

- `versionCode = 16`
- `versionName = 0.9.0`
- アプリ名 `LDFA`
- ホーム／ツール／設定の 3 タブ
- `ToolsScreen.kt`
- Dynamic Color、night resources、mipmap v33
- 公開版の `.github/workflows/android.yml`
- `docs/INSTALLATION.md`、`docs/OVERVIEW.md`、`docs/TESTING.md`

`app/build.gradle.kts` は `HOST_SCRIPT_VERSION` だけを `0.9.0` に合わせ、公開版の versionCode を維持しています。

## 4. 調査で確定した主要問題

### 4.1 起動直後の renderer mutex 未所有操作

最も直接的な native crash／hang 候補です。upstream の `Renderer::threadLoop()` は、最初に `stateLock` を取得しないまま `pthread_cond_wait(stateCond, &stateLock)` または `pthread_mutex_unlock(&stateLock)` を実行していました。

これは pthread の未定義動作であり、通常の viewer 起動直後に発生し得ます。生成する hardened renderer では、ループ入口から mutex 所有状態を不変条件にするよう修正しています。

### 4.2 JNI ABI の不一致

vendored Java は `@CriticalNative`／`@FastNative` を付けた native method を持っていましたが、C++ 側には通常 JNI ABI の引数を持つ登録関数が混在していました。また connection request は I/O／poll を含み、CriticalNative の用途としても不適切でした。

特に Activity が focus を得た直後に呼ばれる native method が ABI 不一致になり得たため、表示 Activity 起動直後の crash 候補です。生成 Java/C++ では注釈を除去し、通常 JNI ABI に統一しました。

### 4.3 EGL 初期化失敗と renderer lifecycle の不備

upstream には次の問題がありました。

- EGL display/config/context/window surface/current の失敗を十分検査しない
- shader 作成失敗後も renderer を継続する
- 初期化途中の return で waiter を起こさない
- `setWindow()`／`setSharedState()` が無期限待ちになり得る
- `destroy()` が context 未作成時に thread を join せず返る
- renderer thread と UI thread が condition/mapped state を同時に破棄し得る
- pending `ANativeWindow`／shared state、AImageReader、global ref の解放漏れ
- `ANativeWindow_fromSurface()` が取得した ref に加えて余計に acquire し、detach ごとに leak する

hardened renderer では明示的な `STARTING / READY / FAILED / STOPPING / STOPPED` 状態、全初期化検査、waiter 通知、join 後の一括解放、pending resource の回収を導入しています。

### 4.4 誤った「表示成功」判定

従来は shared state の `renderedFrames > 0` を表示成功とみなしていました。しかしこの値は次の理由で health signal になりません。

- FPS 計測のため 5 秒ごとに 0 へ戻る
- `eglSwapBuffers()` が失敗しても加算される
- 静止画面では heartbeat が正常表示を故障扱いし得る
- viewer の Surface/EGL attach 前に `xsetroot` を実行しており、damage を取り逃がす

修正後は viewer connection ごとの monotonic な `successfulPresentSerial` を持ち、`eglSwapBuffers() == EGL_TRUE` の場合だけ増加させます。起動検証は次の順です。

```text
X11 service ready
  -> real socket + xset probe
  -> viewer Activity / Surface / EGL READY
  -> successfulPresentSerial baseline
  -> xrefresh で damage を発生
  -> serial > baseline
  -> XFCE start
  -> xfce session / WM ready probe
  -> 再度 baseline -> xrefresh -> serial delta
  -> RUNNING
```

通常 heartbeat は静止画面に recent frame を要求しません。viewer が開いていて描画確認が必要な場合だけ、damage を誘発して serial delta を確認します。

### 4.5 Binder、FD、Activity の世代競合

旧 Binder／旧 socket FD の death/HUP callback が、新しい接続を無条件に切断できる競合がありました。また `MainActivity.instance` が constructor から公開され、layout や LorieView 初期化前の Activity を repository が観測できました。

修正には次を含みます。

- Binder identity／generation が現在値と一致する場合だけ切断
- death callback を main thread に marshal
- 古い death recipient を安全に unlink
- X server 側／viewer 側とも callback FD と現在 FD が一致する場合だけ current connection を clear
- 新接続前に旧 FD を unregister／close
- `MainActivity.instance` は view と native context の準備後に公開
- lifecycle bridge API は null／generation／state を検査
- launch token を Intent に渡し、close 後に遅延到着した native viewer Activity を即 finish
- VNC Activity にも同じ launch token を導入

### 4.6 Activity 所有の起動 transaction

従来は管理 Activity の `ViewModel.viewModelScope` が X11 viewer を別 task で開く処理全体を所有していました。Developer option の “Don't keep activities” や低メモリ状態では、viewer を開いた瞬間に管理 Activity が破棄され、XFCE 起動前に transaction が cancel されます。

セッション開始処理を `LinuxDesktopApplication` の application-owned scope へ移し、Activity は request／observe のみに近づけました。`CancellationException` は通常障害へ変換せず再送出し、必要な rollback だけ bounded `NonCancellable` で行います。

完全な process-death 復旧用 persistent orchestrator までは未実装です。これは将来の強化項目です。

### 4.7 KeepAlive と stale operation の競合

古い container A の heartbeat が lifecycle mutex を待っている間に、新しい container B が起動すると、A の回復処理が B の viewer/server を停止できる競合がありました。通知の STOP も container ID を受け取るだけで照合していませんでした。

現在は mutex 取得後に active container を再照合し、stale heartbeat／stale stop が新しい active generation の display pipeline を破壊しないようにしています。stop は owner の場合だけ viewer-first teardown を実行し、host stop が失敗／cancel されても bounded cleanup を通します。

### 4.8 remote process での TermuxApplication 二重初期化

X11 server は `:x11` process ですが、Android は各 process で Application を生成します。元の構成では `:x11` でも Termux の main-runtime 初期化が走り、同一 `termux-am` Unix socket を unlink／再 bind して main process から奪う可能性がありました。

Termux runtime 側に process guard を設け、remote X11 process では main process 専用初期化を行わないようにしています。

### 4.9 native と VNC の DISPLAY 伝播

VNC fallback は X server／preflight を `:2` で起動しても、実際の `/usr/local/bin/ldfa-session` に `DISPLAY=:2` を渡さず、XFCE だけ `:1` に起動し得ました。また compatibility normalizer が worker の display metadata を既定値 `1` へ戻す置換を行う問題もありました。

現在は host worker、metadata、PROot env、session のすべてで display number を一貫して渡します。

```text
native X11: :1
VNC fallback: :2
```

VNC runner の unquoted heredoc により、Termux 側で `XDG_RUNTIME_DIR`／`HOME` が早期展開され、`set -u` で即終了する問題も修正しています。

### 4.10 Debian host setup の不足

Debian host script には日本語入力／デスクトップ品質に関する公開版機能の一部が抜けていました。現在は次を復元しています。

- `x11-utils` を明示インストールし、`xset`／`xrefresh`／`xprop` を検査
- `.profile` から `.xprofile` を読む
- `.xinputrc` に `run_im fcitx5`
- `.config/user-dirs.locale`
- `~/Desktop/Android共有 -> /mnt/android`
- session へ `GTK_IM_MODULE=fcitx`、`QT_IM_MODULE=fcitx`、`XMODIFIERS=@im=fcitx`

当初の `debian:bookworm` は compatibility normalizer により `proot-distro install debian` へ変換され、release を固定できていませんでした。E2E 時点の `debian:latest` は Trixie で、PRoot 下の apt 3 rename が `Function not implemented` となったため、現在は `LINUX_IMAGE="debian:12"` を実際に `proot-distro install debian:12 --name <id>` へ渡して Bookworm を固定しています。UI の一般名は `Debian` のままですが、新規 rootfs の再現可能な runtime baseline は Debian 12 です。

## 5. 現在の実装構成

主な変更ファイルは次のとおりです。

### Android orchestration

- `app/src/main/java/com/hatake716/linuxdesktop/LinuxDesktopApplication.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/data/LinuxDesktopRepository.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/data/TermuxCommandClient.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/data/HostScriptCompatibility.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/service/DesktopKeepAliveService.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/x11/EmbeddedX11ServerService.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/x11/EmbeddedX11ServiceController.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/display/VncFallbackActivity.kt`

### Host/controller assets

- `app/src/main/assets/ldfa-host.sh`
- `app/src/main/assets/ldfa-x11.sh`
- `app/src/main/assets/ldfa-vnc.sh`

### Embedded Termux:X11 overlay

- `embedded-x11/scripts/prepare_embedded_java.py`
- `embedded-x11/scripts/prepare_embedded_native.py`
- `embedded-x11/src/main/cpp/CMakeLists.txt`
- `embedded-x11/src/main/java/com/termux/x11/EmbeddedX11Display.java`
- `embedded-x11/build.gradle`

### Tests and CI

- `scripts/check-host-script.sh`
- `scripts/test-host-controller.sh`
- `scripts/check-x11-controller.sh`
- `app/src/test/java/com/hatake716/linuxdesktop/data/HostScriptCompatibilityTest.kt`
- `.github/workflows/android.yml`

### Documentation/UI integration

- `app/src/main/java/com/hatake716/linuxdesktop/ui/MainViewModel.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/ui/LinuxDesktopRoot.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/ui/Dialogs.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/ui/DesktopsScreen.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/ui/MainShell.kt`
- `app/src/main/java/com/hatake716/linuxdesktop/ui/SettingsScreen.kt`
- `README.md`
- `docs/ARCHITECTURE.md`
- `docs/INSTALLATION.md`
- `docs/OVERVIEW.md`
- `docs/TESTING.md`
- `SECURITY.md`
- `THIRD_PARTY_NOTICES.md`
- 公開 UI 各画面の Ubuntu 表記を Debian 表記へ更新

## 6. 検証済みの範囲

### 6.1 canonical clean full build

2026-08-22、継続修正後の `LDFA-fix` canonical worktree と pinned pristine `termux-x11@50ac80f` を使い、JDK 17／Gradle 8.13／writable Android SDK で次を clean から再実行しました。

```text
clean
testDebugUnitTest
:app:lintDebug
:termux-runtime:lintDebug
:embedded-x11:lintDebug
assembleDebug
```

結果は次のとおりです。

```text
BUILD SUCCESSFUL                                         PASS
Gradle tasks                                             377 (348 executed, 29 up-to-date)
unit tests                                               155 / 155 PASS
test failures / errors / skipped                         0 / 0 / 0
lint errors                                              0
lint warnings                                            app 34 / termux-runtime 26 / embedded-x11 41
arm64-v8a / armeabi-v7a / x86 / x86_64 native build      PASS
```

build 前には次の static/integration gate もすべて成功しました。

```text
bash scripts/check-host-script.sh                         PASS
bash scripts/test-host-controller.sh                      PASS
bash scripts/check-x11-controller.sh                      PASS
shell assets/scripts bash -n                              PASS
```

wrapper JAR を置かない状態から Gradle 8.13 wrapper を取得し、契約 SHA-256 と一致することも確認済みです。pinned pristine vendor から generator を直接実行した Java overlay と、build が実際に使った Java overlay は同一です。主要 native 生成物 4 点も直接生成結果と全 ABI の build input が一致しました。

### 6.2 APK package gate

検証済み APK を canonical project へ次の名前で配置しました。

```text
/home/takeshi/StudioProjects/LDFA-fix/app/build/outputs/apk/debug/LDFA-v0.9.0-debug.apk
size: 157044263 bytes
SHA-256: 32aaa493e347b7194b9e7514b3c47e1e74f1f2d23b3905af9bdfd8ae6764c5f6
```

package gate の結果は次のとおりです。

```text
application id                                           com.termux
version                                                   code 16 / name 0.9.0
application label                                         LDFA
minSdk / targetSdk                                        26 / 28
ToolsScreen / VNC fallback / X11 controller               present
EmbeddedX11ServerService android:process=":x11"           present
obsolete x11-loader / app_process / external launch       absent
Chrome provisioning asset / startup wait text             present
dead Settings X11 label in DEX                            absent
zipalign -c -P 16 4                                       PASS
arm64-v8a / x86_64全10 .so PT_LOAD >= 0x4000             PASS
APK signature                                             PASS (debug certificate, v2, 1 signer)
```

各 ABI の APK 内 `libXlorie.so` は Gradle の stripped native intermediate と SHA-256 が一致し、必須 JNI export は 9/9 です。

```text
arm64-v8a    d3181bb10ee0b862784bbb1d743dcefd739e61cc6ad403f0201419eb7f88509b    9/9
armeabi-v7a  da1545d9b12e5fdd1f80da44e1896da2ac7c2ce8b4fa7a3e59bb045356993ec3    9/9
x86          23f5d370e75183efddd40442928729e5dcf55e5523f9b84f6304d87d72db9a0b    9/9
x86_64       997029eefb7d2f7cc8cfe2d88c1dacf45eaacfd9eb7d505f7a3877004fdf09bf    9/9
```

Android 公式の ELF alignment 判定対象である `arm64-v8a` と `x86_64` は、`libXlorie.so`、`libandroidx.graphics.path.so`、`liblocal-socket.so`、`libtermux-bootstrap.so`、`libtermux.so` の全 10 files が minimum LOAD alignment `0x4000` でした。`armeabi-v7a`／`x86` は 4 KB alignment ですが、16 KB page kernel の公式判定対象ではありません。

### 6.3 実機状態

2026-08-22、API 35 x86_64・4 KB page の一時 AVD `emulator-5556` で upgrade install と実データを保持した E2E を実施しました。コンテナは `debian-xfce-75b6458a`、rootfs は Debian 12 Bookworm です。

native normal では `com.termux:x11`、X1 socket、XCB connection、EGL renderer、shared buffer、successful present、host worker、`xfce4-session` probe を確認し、XFCE の実画面を screenshot で確認しました。stop 後の二回目起動と、fallback 修正後の native 正常系再起動も成功しています。

Fcitx5 と Mozc は process/package 存在だけでなく、実 XFCE terminal 内で `fcitx5-remote -n` が `keyboard-jp` を返すことを確認後、Mozc を選択しました。パネルがオレンジの「あ」になり、`nihongo` から候補「日本語」を表示して Enter で terminal へ確定できました。

共有ストレージは guest から Android へ `guest-to-android`、Android から guest へ `android-to-guest` を読み戻しました。worker の bind は次と一致しています。

```text
/data/data/com.termux/files/home/storage/shared/LinuxDesktop/debian-xfce-75b6458a:/mnt/android
```

Surface lifecycle は portrait/landscape 往復で X11 PID と `xfce4-session` PID が変わらず、各 orientation で新 Surface/shared buffer size を確認しました。Home へ 50 秒置いた間も heartbeat が二回走りましたが、同じ PID の Xorg/XFCE を保持し、Recents 復帰時に新 Surface へ再 attach できました。

UI stop は native と VNC の両 backend で確認しました。native stop は 4 秒以内、VNC stop は 2 秒以内に display server、XFCE、PRoot、socket/lock/marker を消し、native stop では `Termux service lifetime lease released` も確認しました。保存環境は `READY`（UI 表示は「停止中」）へ戻り、再起動できます。

VNC fallback は `com.termux.x11.MainActivity` component だけを app UID から一時無効化して native viewer failure を強制しました。修正前は main process が `ActivityNotFoundException` で crash しましたが、修正後は main PID `8572` を維持し、native X11 を停止して約 14 秒で `VncFallbackActivity` まで到達しました。動的証拠は次です。

```text
active_display_backend=compatibility-vnc
Xtigervnc :2 PID=8973
xfce4-session PID=9213
XFCE DISPLAY=:2
X2 socket / .X2-lock / noVNC HTTP ready marker present
GTK_IM_MODULE=fcitx / QT_IM_MODULE=fcitx / XMODIFIERS=@im=fcitx
```

起動確認後の最終 APK でも、管理画面の待機注意から native X11 Activity へ遷移し、同じ保存済み Debian XFCE を起動できました。設定画面の内蔵ツールは「ターミナルを開く」と「共有フォルダ」だけになっています。Chrome は guest package status、wrapper、desktop entry、APT source、実プロセス、実ウィンドウ、HTTPS ページ描画を確認しました。x86_64 AVD 上の導入後 package size は `Installed-Size: 441164 KiB` で、依存関係を含む追加使用量はバージョンにより変動します。arm64 は公式 package/repository と静的分岐までは確認済みですが、物理 ARM64 guest での実インストールは未検証です。

引き続き未検証なのは次です。

- 物理 ARM64 端末での clean install と長時間操作
- ARM64 16 KB page device での Debian rootfs／XFCE E2E
- Developer options の “Don't keep activities” を有効にした transaction 完走
- low-memory kill／main process recreation を越える復旧（persistent orchestrator 自体が未実装）
- vendor EGL driver 内部の永久 hang と native SIGSEGV の process isolation

## 7. renderer shutdown 修正（完了）

`embedded-x11/scripts/prepare_embedded_native.py` の renderer shutdown hardening は、実装、generator 実行、static gate、4 ABI compile まで完了しました。非同期 destroy にはせず、旧 renderer cleanup と新 viewer generation init が重なる UAF を避けるため、「join 後だけ free」を維持しながら、コード上判明していた待機を停止可能にしています。

実装済みの内容は次のとおりです。

1. 生成する `lorie.h` の C++ 領域へ renderer 専用 helper を追加しました。

   ```cpp
   bool lorie_mutex_lock_interruptible(
       pthread_mutex_t *, pid_t *, const std::atomic_bool *cancel);
   ```

   loop 入口、`pthread_mutex_timedlock()` の取得直後、失敗直後で `cancel->load(std::memory_order_acquire)` を検査します。取得直後に cancel された場合は `lockingPid = 0` にして unlock し、`false` を返します。通常の `lorie_mutex_lock()` は X server 用に残し、その挙動を変えていません。

2. `Renderer::destroy()` は `stateLock` を取得する前に `stopping.store(true, std::memory_order_release)` と renderer state `STOPPING` を公開します。renderer が shared mutex 待機中なら、destroy 自身が `stateLock` を待っている間にも interruptible helper が停止を検出できます。

3. renderer の次の 3 箇所だけ helper を使います。

   - `applyPendingGpuCopies()` の root lock
   - `Renderer::draw()` の root lock
   - cursor update の cursor lock

4. root lock を取得できなければ return します。cursor lock を取得できなければ、作成済み EGL fence を destroy し、保持中の root lock を unlock して return します。

5. `findBufferWithRetry()` の 20 回 retry 条件へ `!stopping.load(std::memory_order_acquire)` を加えました。

6. shared-state／window acknowledgement と GPU fence timeout を 1 秒へ短縮しました。

7. swap 前、swap 後、swap failure の各停止 guard により、停止後に追加の fence／buffer 処理へ入りません。

8. `destroy()` の `pthread_join -> resource unmap/free` の順序を維持しています。

生成後の構造検査は、helper 呼出しが正確に 3 箇所であること、destroy publish ordering、取得直後 cancel cleanup、cursor fence/root cleanup、pre/post-swap guard ordering まで確認します。pinned pristine `termux-x11@50ac80f` からの direct generation と clean full build の生成物一致も確認済みです。

`stateLock` 保持中の `eglMakeCurrent()`、`eglSwapBuffers()`、`eglDestroySurface()` などで vendor EGL driver 自体が永久停止するケースまでは、この方式でも完全には隔離できません。`pthread_cond_timedwait()` も timeout 後に mutex を再取得してから戻るため、相手が `stateLock` を永久保持すると時間上限を保証できません。長期的な完全隔離は viewer Activity を `:x11-viewer` process へ分離し、AIDL health/control を介す設計です。

## 8. 完了済み P0 と残作業の優先順

### P0: 完了済み

- renderer interruptible lock と有限 timeout を実装
- generator の replacement drift、生成構造、pristine pinned vendor 再現性を検査
- `scripts/check-x11-controller.sh` に atomic stopping、helper 3 箇所、lock/fence cleanup、shutdown guard の構造回帰検査を追加
- stale notification STOP が新しい active session／KeepAlive service を停止しないよう `startId` ownership を修正
- canonical clean full build、155 unit tests、3 module lint、4 ABI native build を実行
- APK package、CRC、branding、manifest、DEX、4 ABI、JNI export、stripped hash、zipalign、signature を検証
- `.github/workflows/android.yml` を同じ package gate に更新
- `.gitignore` に module build directory、`__pycache__`、Python bytecode を追加
- Debian 12 pin、PRoot seccomp/shared-tmp、TermuxService lifetime lease を実 E2E で修正
- generated TouchInputHandler の初期化時 singleton NPE を修正
- background heartbeat が Surface detach を故障扱いしないよう修正
- VNC runner の self-matching `pkill -f` を削除
- 非同期 viewer Activity launch failure を main-process crash にせず VNC fallback へ伝播
- 4 KB AVD で native、Mozc、共有 storage、回転、background、stop/restart、VNC `:2` を動的確認

各 ABI で次の 9 symbol を確認済みです。

```text
JNI_OnLoad
CmdEntryPoint_start
CmdEntryPoint_getXConnection
CmdEntryPoint_connected
EmbeddedX11ServerBridge_start
EmbeddedX11ServerBridge_getXConnection
EmbeddedX11ServerBridge_connected
EmbeddedX11Display_nativeSuccessfulPresentSerial
EmbeddedX11Display_nativeRendererReady
```

APK 内の各 `libXlorie.so` と Gradle の stripped native intermediate の SHA-256 も一致しています。

`.github/workflows/android.yml` は streaming `... | grep -q` をやめ、一度 file へ materialize してから検査するよう修正済みです。これは `set -o pipefail` 下の SIGPIPE 141 と、negative gate の誤 pass を防ぎます。4 ABI ごとの 9 JNI export と APK／stripped intermediate の SHA-256 一致も workflow gate に含めました。

### P1: lifecycle 回帰 test

今回、stale heartbeat ownership、notification STOP の `startId`、native/VNC launch token、主要な `CancellationException` 再送出、VNC `DISPLAY=:2` 伝播について static／host-script regression を追加しました。ただし Android singleton と Context への直接依存が強く、実際に coroutine の mutex 順序を制御する JVM unit test にはなっていません。

将来 display operations、host command operations、active-session store を interface 化し、`kotlinx-coroutines-test` 等で少なくとも次を動的に検証する価値があります。

- heartbeat(A) が mutex 待ち中に B が active になった場合、B を teardown しない
- stale notification STOP(A) が active B を停止しない
- native viewer の古い launch token を reject
- VNC viewer の古い launch token を reject
- CancellationException が通常 failure/retry へ変換されない
- VNC `DISPLAY=:2` が worker、metadata、session の最終 exec まで一貫する

### P1: persistent orchestration

現在は application-owned coroutine により Activity recreation を越えますが、main process death を越える完全な transaction journal はありません。将来的には foreground `SessionOrchestrator` が `opId / generation / phase / container / backend / display` を永続化し、世代一致する resource だけ rollback／resume する構成が望まれます。

### P1: viewer process isolation

X server は `:x11` へ隔離済みですが、表示側 `MainActivity`／LorieView native renderer は管理 UI と同じ main process にあります。native SIGSEGV は管理 UI ごと終了させます。長期的には viewer を `:x11-viewer` へ分離し、binder generation、Surface/EGL READY、successful present serial、close acknowledgement を AIDL で返す設計が最も堅牢です。

## 9. 推奨検証コマンド

必ず canonical tree で実行します。

```bash
cd /home/takeshi/StudioProjects/LDFA-fix

export ANDROID_HOME=/home/takeshi/Android/Sdk
export ANDROID_SDK_ROOT=/home/takeshi/Android/Sdk
export ANDROID_NDK_ROOT=/home/takeshi/Android/Sdk/ndk/29.0.14206865
export GRADLE_OPTS=-Dorg.gradle.project.android.aapt2FromMavenOverride=/home/takeshi/Android/Sdk/build-tools/36.0.0/aapt2

java -version
bash scripts/check-host-script.sh
bash scripts/test-host-controller.sh
bash scripts/check-x11-controller.sh

./gradlew --no-daemon --console=plain \
  clean \
  testDebugUnitTest \
  :app:lintDebug \
  :termux-runtime:lintDebug \
  :embedded-x11:lintDebug \
  assembleDebug \
  --stacktrace
```

想定 toolchain は次です。

```text
JDK 17
Gradle 8.13
Android SDK platform 36
Android build-tools 35.0.0 and 36.0.0
NDK 29.0.14206865
CMake 3.22.1
Python 3
git, curl, sha256 utility
recursive submodules
```

この環境では writable SDK は `/home/takeshi/Android/Sdk` です。SDK 環境変数を設定しないと read-only Nix SDK を拾い、AGP が build-tools 35.0.0 を自動 install しようとして失敗することがあります。これはソースコンパイル不良とは別問題です。

wrapper bootstrap は Gradle 8.13 に統一し、GitHub raw tag を `v8.13.0` に修正しています。Unix/Windows の両 wrapper を確認してください。`gradlew.bat` は CRLF を維持しています。

現在の checksum contract は次です。

```text
Gradle distribution SHA-256:
20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78

Gradle 8.13 wrapper JAR SHA-256:
81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
```

## 10. 実機 E2E 手順

4 KB x86_64 AVD では upgrade install と保存済み rootfs を使って 2〜7、10〜12 の主要経路を確認済みです。次回は物理 ARM64 の clean install を主対象とし、特に 8、9 と 16 KB page を追加確認します。

1. APK を install し、初回 Debian install を最後まで完了する。
2. native normal mode で X11 service、viewer、XFCE が起動し、デスクトップが表示されることを確認する。
3. terminal、パネル、ウィンドウ移動、マウス／タッチ／キーボードを確認する。
4. Fcitx5 + Mozc で日本語入力を確認する。
5. `~/Desktop/Android共有` が `/mnt/android` を指し、想定範囲で読み書きできることを確認する。
6. 画面回転、Surface 再作成、viewer close/reopen を繰り返す。
7. background／foreground、画面消灯復帰を確認する。
8. Developer options の “Don't keep activities” を有効にし、管理 Activity が破棄されても起動 transaction が完了することを確認する。
9. low-memory／main process recreation 後に stale viewer や stale heartbeat が新 session を停止しないことを確認する。
10. native normal のみを意図的に失敗させ、分類可能な buffer-import 系 failure なら legacy、その他なら clean teardown 後に VNC `:2` へ fallback することを確認する。
11. VNC fallback で XFCE 本体も `DISPLAY=:2` にいることを確認する。
12. stop／restart を連打しても同名 tmux worker、socket、metadata、viewer が世代混在しないことを確認する。

ログを取る場合は、テスト開始前に logcat を clear し、main process と `:x11` process の PID、native crash tombstone、service generation、renderer state、present serial を一緒に保存してください。

```bash
adb devices -l
adb logcat -c
adb shell pidof com.termux
adb shell pidof com.termux:x11
adb logcat
```

native crash が起きた場合は「画面が出なかった」だけでなく、signal、fault address、ABI、backtrace、直前の renderer state／generation／EGL error を記録してください。

## 11. Git／公開時の注意

今回の main 更新対象は、意図した多数の source/doc/workflow 変更を含みます。公開後の追加修正でも、広い `git add -A` は使わないでください。

特に次を区別します。

### 今回の公開に必須だった新規ファイル

```text
embedded-x11/scripts/prepare_embedded_java.py
HANDOVER.md
```

`prepare_embedded_java.py` は clean checkout の build に必須です。公開時に必ず明示的に含めます。

### 生成物なので commit しない

```text
embedded-x11/build/
termux-runtime/build/
app/build/
.gradle/
.cxx/
```

`.gitignore` には `embedded-x11/build/`、`termux-runtime/build/`、`__pycache__/`、`*.py[cod]` を追加済みです。今後も必須 generator と生成物を混同して削除しないでください。

### 直接変更しない

```text
vendor/termux-app/
vendor/termux-x11/
```

公開前には次を行います。

1. `git status --short` で scope を確認する。
2. source/doc/workflow を明示 path で stage する。
3. `git diff --cached --stat` と `git diff --cached` を読む。
4. submodule gitlink が上記 pinned hash のままか確認する。
5. build directory、wrapper JAR、bootstrap zip、daemon JVM のローカル生成物が stage されていないことを確認する。
6. canonical full clean build と package gate を最終 commit 候補そのものに対して実行する。
7. 今回は main への直接反映が明示承認済みです。今後の追加 commit／push／PR は、その都度ユーザーの明示指示を確認する。

## 12. 次担当者向けの再開チェックリスト

1. `/home/takeshi/StudioProjects/LDFA-fix/HANDOVER.md` と `git status --short` を読む。
2. `vendor/termux-x11` が `50ac80f` かつ clean であることを確認する。
3. `prepare_embedded_java.py` と `HANDOVER.md` が追跡済みで存在することを確認する。前者が欠けると clean checkout の X11 static gate／build が成立しない。
4. 検証済み APK の SHA-256 が `32aaa493e347b7194b9e7514b3c47e1e74f1f2d23b3905af9bdfd8ae6764c5f6` であることを確認する。
5. 物理 ARM64 実機で clean-install E2E を native normal から実施する。
6. ARM64 16 KB page device で APK install、native viewer、Debian PRoot/XFCE を確認する。
7. “Don't keep activities”、low-memory／main process recreation を追加検証する。
8. failure があれば PID、generation、renderer state、present serial、EGL error、tombstone を保存する。
9. source を追加変更した場合は static gates、canonical clean full build、APK package gate を再実行する。
10. commit／push／PR はユーザーの明示指示を得た後だけ行う。

## 13. 低優先度の既知事項

- vendor EGL call 自体が driver 内で永久停止した場合の完全隔離には、viewer の別 process 化が必要です。今回の同期 shutdown hardening はコード上判明した mutex／fence 待機を停止可能にしましたが、driver 内部 hang の時間上限までは保証しません。
- `ldfa-host.sh` の `exec startxfce4 || { ... }` は、`exec` 成功時点で shell が置換されるため、`startxfce4` が後から非ゼロ終了しても fallback block は実行されません。fallback を実際に使うなら `startxfce4 || { ... }` とし、正常終了時に worker をどう扱うかも明示する必要があります。
- `LINUX_IMAGE="debian:12"` は今回の PRoot/apt E2E を成立させた Bookworm baseline です。UI の一般名は「Debian」ですが、release pin を外すと Trixie/apt 3 の syscall 要件で再び壊れ得るため、変更時は新規 rootfs install から再検証してください。

## 14. 現時点の結論

元の設計全体が成立不能だったわけではありません。Android 内 Xorg service、直接 Binder FD、LorieView renderer、Debian PRoot XFCE という構成は維持できます。ただし、元の実装は renderer の mutex／EGL／JNI lifecycle、非単調な frame 判定、Activity 所有 transaction、世代のない Binder/FD callback、VNC DISPLAY 伝播にまたがる複数の不具合が重なっていました。

今回の P0 修正は canonical tree に統合され、renderer shutdown hardening、static/integration gates、unit tests、3 module lint、4 ABI native build、APK package/JNI/hash/align/signature gate に加え、4 KB API 35 AVD の Debian 12/XFCE E2E まで進みました。native 正常系、Mozc の日本語確定、共有 storage、Surface 再作成、background、stop/restart、強制 VNC `:2` fallback は実画面と process/socket/env の両方で確認済みです。さらに、起動待機注意、Google Chrome の自動導入、設定画面の死んだ X11 項目削除を最終 APK 上で確認済みです。

したがって「エミュレーター上で Linux GUI が表示できる」ことは確認済みです。一方、物理 ARM64、ARM64 16 KB page、low-memory/process-death、vendor EGL hang の受け入れは残っています。APK の 16 KB alignment 成功だけを Debian userland を含む 16 KB E2E 成功とは扱わないでください。
