# アーキテクチャ

## 全体構成

```text
Single Android APK (applicationId: com.termux)
  ├─ Jetpack Compose management UI
  ├─ embedded Termux terminal/runtime
  ├─ internal RunCommandService / TermuxService
  ├─ embedded Termux:X11 Activity + libXlorie
  ├─ foreground watchdog + WakeLock
  └─ Android shared storage integration
             │
             ▼
Termux prefix: /data/data/com.termux/files/usr
  ├─ tmux install worker
  ├─ tmux desktop worker
  ├─ tmux X11 worker
  ├─ /system/bin/app_process
  ├─ proot-distro
  ├─ ldfa-host
  └─ ldfa-x11
             │
             ├───────────────┐
             ▼               ▼
Ubuntu PRoot            CmdEntryPoint
  ├─ XFCE                   │
  ├─ Fcitx5 / Mozc          ▼
  ├─ sudo + desktop      libXlorie
  └─ /mnt/android            │
             │               ▼
             └──── DISPLAY=:1 X server
                             │
                             ▼
                      X11 display Activity
```

## 単一APK化

公式Termuxのブートストラップは`/data/data/com.termux/files/usr`を前提にビルドされています。このパスを維持するため、統合APKの`applicationId`は`com.termux`です。

`termux-runtime`モジュールは固定コミットの`termux/termux-app`ソースをAndroid Libraryとしてビルドし、TermuxApplication、TermuxActivity、TermuxService、RunCommandService、ターミナル表示、ネイティブ実行ランタイム、公式ブートストラップを同じAPKへ取り込みます。

`embedded-x11`モジュールは固定コミットの`termux/termux-x11`ソース、AIDL、リソース、`libXlorie`ネイティブコードをAndroid Libraryとしてビルドします。

## X11プロセスモデル（v0.4.0以降）

v0.3.xまではAndroid `Service` の中で`new CmdEntryPoint()`を実行していました。v0.4.0ではこの方式を廃止し、公式Termux:X11と同様にTermux側からAndroidの`app_process`を起動します。

```text
ldfa-x11
  │
  ├─ resolve installed com.termux APK
  ├─ set TMPDIR / XKB_CONFIG_ROOT / CLASSPATH
  └─ /system/bin/app_process
         │
         ▼
    com.termux.x11.CmdEntryPoint
         │
         ▼
      libXlorie
         │
         ▼
      X server :1
```

`CmdEntryPoint`はインストール済みAPKの`nativeLibraryDir`から`libXlorie.so`を絶対パスでロードします。X11サーバーのプロセスはtmuxセッション`ldfa-x11`で管理します。

Android側のX11 Activityは`CmdEntryPoint`から送られるBinderを受け取り、X serverとの表示接続を確立します。

## X11接続検証

X11ソケット`X1`の存在だけでは起動成功と判定しません。

起動時は次の3段階を確認します。

1. `app_process`のtmuxセッションが生存
2. `$PREFIX/tmp/.X11-unix/X1`が生成
3. Ubuntu PRoot内部から`DISPLAY=:1 xset q`が成功

さらにAndroid側では`com.termux.x11.MainActivity.isConnected()`を確認して、表示ActivityがX serverへ接続済みであることを検証します。

これにより、古いソケットだけが残った状態や、PROotから実際には接続できない状態を成功扱いしません。

## 内蔵コマンド経路

```text
Compose UI / Repository
        │
        ▼
TermuxCommandClient
        │ explicit component + PendingIntent result
        ▼
RunCommandService (not exported)
        │
        ├─ ldfa-host
        │     └─ Ubuntu / XFCE
        │
        └─ ldfa-x11
              └─ app_process / X server
```

上流RunCommandServiceは同一パッケージ内の呼び出しにも`allow-external-apps`ポリシーを適用するため、統合ランタイムが内部プロパティを自動設定します。サービス自体は非公開で、RUN_COMMAND権限もsignatureです。

## Ubuntu XFCE環境

Ubuntu内へ次を導入します。

- XFCE、XFWM、XFCE Panel、Thunar、XFCE Terminal
- `ja_JP.UTF-8`
- Noto CJK / Noto Color Emoji
- Fcitx5 / Mozc
- Mesaソフトウェアレンダリング
- `x11-xserver-utils` (`xset`接続プローブ用)
- `sudo`
- 非rootの`desktop`ユーザー

Ubuntuは`proot-distro login --shared-tmp`で起動します。これによりTermux側のX11 Unix socketとUbuntu側の`/tmp`を共有します。

## 状態とログ

```text
~/.local/share/linux-desktop-for-android/containers/<id>/
~/.local/share/linux-desktop-for-android/logs/<id>.log
~/.local/share/linux-desktop-for-android/logs/x11-server.log
~/.local/share/linux-desktop-for-android/run/
```

Ubuntu/XFCEとX11のログは分離します。X11ログにはAPKパス、`TMPDIR`、`XKB_CONFIG_ROOT`、`DISPLAY`、`app_process`起動情報を記録します。

## 共有フォルダ

```text
Android: /storage/emulated/0/LinuxDesktop/<id>
Termux:  ~/storage/shared/LinuxDesktop/<id>
Ubuntu:  /mnt/android
XFCE:    ~/Desktop/Android共有
```

## 復旧

- `queued` / `installing`でUbuntuインストールworkerが消えた場合は再作成
- `starting` / `running`でXFCE workerが消えた場合は再作成
- X11 tmuxセッションまたはX11ソケットが消えた場合は`ldfa-x11 heartbeat`がX serverを再起動
- X11再起動後はUbuntu内部の`xset q`で接続を再確認
- XFCEセッションだけが終了した場合は4秒後に再起動
- フォアグラウンドサービスが30秒間隔でheartbeatを実行
- 同時表示するX11デスクトップは1環境に限定

## 制約

- 公式Termuxと同じアプリIDを使用するため共存できません。
- 旧外部Termux内のコンテナは自動移行しません。
- PRootにはsystemd、完全なLinuxカーネル機能、ネイティブGPU権限がありません。
- X11表示は互換性を優先してMesaのソフトウェアレンダリングを利用します。
- Androidによるプロセス終了を完全には禁止できません。
