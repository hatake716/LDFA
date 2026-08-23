# アーキテクチャ

## 全体構成

LDFA は外部 Termux、外部 Termux:X11、外部 VNC クライアントを前提にしない単一 APK です。公式 Termux bootstrap が固定パスを前提とするため、`applicationId` は `com.termux` のままです。

```text
Android main process (com.termux)
  ├─ Jetpack Compose 管理 UI
  ├─ embedded Termux terminal / RunCommandService
  ├─ Termux:X11 MainActivity / LorieView / EGL renderer
  └─ DesktopKeepAliveService
                    │
                    │ direct Binder + socketpair FD
                    ▼
Android X11 process (com.termux:x11)
  └─ EmbeddedX11ServerService / libXlorie / Xorg :1
                    │
                    │ $PREFIX/tmp/.X11-unix/X1
                    ▼
Debian PRoot
  ├─ XFCE / xfwm4
  ├─ ja_JP.UTF-8 / Noto fonts
  ├─ Fcitx5 + Mozc
  ├─ sudo / desktop user
  └─ /mnt/android

native が成立しない場合:
  Debian PRoot / XFCE ── DISPLAY=:2 ── Xtigervnc + noVNC
```

Xorg サーバーは専用 `:x11` process に隔離されています。表示 Activity と EGL renderer は現在 main process にあるため、renderer の native 不具合は管理 UI も巻き込み得ます。このため、既知の mutex、EGL 初期化、JNI ABI、再接続、teardown の不具合を build-time overlay で修正しています。将来さらに隔離する場合は、viewer 専用 process と AIDL health interface が必要です。

## 内蔵モジュール

- `termux-runtime`: 固定した `termux/termux-app` ソース、公式 bootstrap、terminal、RunCommandService を Android Library として内蔵します。
- `embedded-x11`: 固定した `termux/termux-x11` の AIDL、resources、Java viewer、`libXlorie.so` を内蔵します。
- `app`: Compose UI、Debian controller、display backend 選択、foreground watchdog を所有します。

`:x11` process では `TermuxApplication` の main-process 初期化を実行しません。同じ `termux-am` Unix socket を remote process が unlink / bind して main process から奪うことを防ぎます。

## X11 プロセスと接続

旧版の `/system/bin/app_process`、loader APK、localhost TCP 7892、通常接続用 broadcast は使用しません。

```text
LinuxDesktopRepository
  │
  ├─ startForegroundService
  ▼
EmbeddedX11ServerService (:x11)
  │  EmbeddedX11ServerBridge.start()
  │  Xorg :1
  │
  └─ ICmdEntryInterface Binder
          │ getXConnection() -> socketpair FD
          ▼
Termux:X11 MainActivity -> LorieView -> EGL -> Android Surface
```

Activity は launch Intent の Binder を最初の接続試行より先に取り込みます。古い Binder の death callback と古い socket FD の HUP は、現在の世代と一致する場合だけ接続状態を解除します。Service 起動ごとに UUID generation と PID marker を発行し、実 socket、X lock owner、service process が同じ世代に揃ってから ready とします。停止時は viewer を先に切断し、marker の PID が終了するまで待ってから次の世代を開始します。停止開始前の遅延 `bindService` callback は世代番号で破棄します。

上流 Java にあった `@CriticalNative` / `@FastNative` は生成時に除去し、登録する C/C++ 関数を通常 JNI ABI に統一します。Android 8〜13 での ABI 不一致と、blocking connection call を CriticalNative として実行する問題を避けます。

## Renderer の安全境界

`embedded-x11/scripts/prepare_embedded_native.py` は pinned submodule を直接変更せず、guard 付きで次を生成します。

- renderer loop は最初の `pthread_cond_wait` / unlock より前に必ず `stateLock` を取得する
- `STOPPED / STARTING / READY / FAILED / STOPPING` を明示する
- EGL display、config、context、surface、`eglMakeCurrent`、shader の全段階を検査する
- 初期化失敗時は全 waiter を起こし、部分的な EGL / Surface 資源を renderer thread 上で解放する
- shared cond allocation failureで `abort()` せず `FAILED` にする
- state / window acknowledgement と GPU fence に上限時間を設ける
- 作成済み thread は EGL context の成否にかかわらず join する
- reconnect 時の古い FD callback が新しい接続を閉じないよう世代を照合する
- `SurfaceView` の detach 後に再 attach された場合は native context、Surface、Binder FD を再構築する

CMake は同じ生成 overlay 内で upstream の `/usr/bin/gcc` 固定指定も置き換え、Android cross compiler ではなく build host の `cc` / `gcc` を検出して `makekeys` を作ります。

## 起動トランザクション

管理 Activity ではなく `LinuxDesktopApplication` 所有の coroutine が起動トランザクションを継続します。画面回転、low-memory recreation、開発者オプションの「アクティビティを保持しない」で管理 Activity が破棄されても、X11 viewer を開いた直後に処理がキャンセルされません。同じ環境への重複要求は同じ処理を待ち、別環境の同時起動は拒否します。

起動順は次のとおりです。

```text
stop old host worker and display backends
  -> start native Xorg :1
  -> verify real Unix socket and Debian `xset q`
  -> attach Binder viewer
  -> wait for Activity + Surface + EGL READY
  -> capture successful-present serial baseline
  -> issue an `xrefresh` repaint probe after Surface attachment
  -> require successful EGL swap serial > baseline
  -> start xfsettingsd + xfwm4 + Panel + Desktop with explicit DISPLAY=:1
  -> require worker + xset + all 4 processes + visible EWMH Panel/Desktop windows
  -> issue a fresh post-XFCE damage and require another successful-present delta
  -> commit active session
```

`renderedFrames` は FPS 用の5秒窓であり、失敗した `eglSwapBuffers` も数えていたため health 判定には使いません。viewer process 内の単調な `successfulPresentSerial` を、`eglSwapBuffers() == EGL_TRUE` の場合だけ増やします。

新規起動時は、Xorg process、Binder、real socket、`xset`、host worker、XFCEの実ウィンドウまで完全検査します。viewerが開いている場合は`xrefresh`を発行し、同じviewerのsuccessful-present serialが進むことも確認します。静止画面の自然なframe発生には依存しません。Activityの通常resumeは、後述する子processを増やさない`/proc`高速経路を使い、欠落を検出した場合だけ同じ厳密検査へ昇格します。

viewerが前面にあり、同じ世代の`:x11` serviceがreadyである通常運転中は、定期heartbeatからPRoot／RunCommandの完全probeを起動しません。確認済みserviceのbusy状態を軽量に返し、Androidのphantom child process枠とForeground Service起動回数を消費しないためです。viewerを閉じたheadless状態では表示serverとworkerの生存を検査します。XorgまたはVNCを復旧した場合は、死んだdisplayに接続していたXFCE workerも停止・再作成します。

通常描画で viewer / Surface / EGL 自体を準備できない場合、legacy 描画は同じ EGL 経路なので再試行せず VNC へ移ります。viewer が READY で presentation だけ失敗した場合は、buffer transport を変える legacy 描画を試します。

## VNC fallback

native X11 と互換表示は endpoint を分離します。

```text
native: DISPLAY=:1, Unix socket X1
VNC:    DISPLAY=:2, Unix socket X2, RFB 127.0.0.1:5902,
        noVNC 127.0.0.1:6080
```

fallback 時は host metadata、tmux worker、`ldfa-session` のすべてへ `DISPLAY=:2` を明示します。VNC runner の heredoc は Debian 内の `$HOME` と `$XDG_RUNTIME_DIR` を Termux 側で先に展開しません。X11 TCP は `-nolisten tcp`、RFB / noVNC は loopback 限定です。

## Debian / XFCE

Debian は `proot-distro login --shared-tmp` で起動し、Termux 側の X11 Unix socket を共有します。XFCE session には次を明示します。

```text
DISPLAY=:1 または :2
GTK_IM_MODULE=fcitx
QT_IM_MODULE=fcitx
XMODIFIERS=@im=fcitx
PULSE_SERVER=unix:/tmp/ldfa-pulse/native
```

音声はX11／VNCとは独立し、Debian clientからTermux PulseAudioのAndroid
sinkへ送ります。Termux側は`$PREFIX/var/run/ldfa-pulse-bridge/native`へ専用の
`module-native-protocol-unix`を公開し、親directoryを`0700`にします。socketを
`$PREFIX/tmp`の外へ置くのは、`--shared-tmp`が`$PREFIX/tmp`全体をguestの`/tmp`へ
bindし、PRootのsession teardown（link2symlink／kill-on-exit）がこのtmpを掃除する際、
daemonが生存したままでもsocket directoryを断続的に削除する競合を避けるためです。
このbridge directoryは各guest loginへ明示的な`--bind`でguestの`/tmp/ldfa-pulse`へ
mapされるため、guestからは従来どおり`/tmp/ldfa-pulse/native`として見え、`--shared-tmp`の
churnから独立します。PulseAudio自身のruntime dirも`$PREFIX/var/run/ldfa-pulse-rt`へ
`PULSE_RUNTIME_PATH`で固定し、`$PREFIX/tmp`を触らせません。匿名TCP 4713を
Android端末全体のloopbackへ公開しません。

PRootのsyscall emulationはSHM／memfdのfile descriptorをguest境界越しに渡せません。
daemonがshared memory transportを提示すると、Debian clientはUnix socket上で認証まで
成功しても再生streamがSHM／srbchannel handshakeで切断され、Android sinkがIDLEのまま
無音になります。これを防ぐため、client drop-inに`enable-shm = no`／`enable-memfd = no`を、
daemonには`daemon.conf.d`のdrop-inで同じ設定を書き、plain socket transportへ確実に
fallbackさせます。

workerは12秒のhost bridge deadlineを目安にPulseAudio daemon、専用module、socket、
非`auto_null` sinkを確認し、その後Debian側接続を別枠の最大4秒で確認します。deadline
直前に開始した1〜2秒の個別command timeout分だけ、実wall-clockは超過し得ます。
Termuxの一時領域だけが消えて古いdaemonが残った
場合は、二重起動を避けるためlocal controlを先に検査し、TERM／必要時KILLと終了待ちの
後に一度だけ再起動します。Android 8以降で既定のOpenSL ES sinkが作れない場合だけ
AAudio sinkを試します。音声bridgeが利用不能でも状態を`audio_ready=0`とhost logへ
残し、検証済みのGUI起動は継続します。

Debian側の既定値はapp-ownedな`/etc/pulse/client.conf.d/99-ldfa.conf`と
`/etc/alsa/conf.d/99-ldfa-pulse.conf`へ置き、ユーザーの`client.conf`と`.asoundrc`は
上書きしません。既存containerに`pulseaudio-utils`と`libasound2-plugins`がなければ、
起動前の`ensure-apps`でnetwork update／downloadだけを15秒に制限して移行します。
取得失敗時もsystem drop-inと明示`PULSE_SERVER`によりnative Pulse clientのGUI起動を
継続し、補助packageは次回起動で再試行します。

起動を速くするため、`ensure-apps`は音声client・desktop runtime・Chrome launcherの各
契約versionを連結したfingerprintを`apps_provisioned` metadataへ記録します。次回以降の
起動では、fingerprintが一致する間はこの3項目を1回のguest loginでまとめて検証するだけで
済ませ、通常必要な3〜4回のPRoot loginを省きます。PRoot loginが数秒かかる実機ほど効果が
大きくなります。fingerprintが一致しない（version bump、package削除、ユーザーによるrootfs
変更）場合だけ、従来どおり各componentを個別に再migrationします。

Debian Bookworm既定panelのplugin 8はPulseAudioの音量／mute UIです。旧
`panel-mobile-v1`がこのIDを誤って除去した既存環境には`panel-mobile-v2` migrationを
適用し、plugin 8の定義が実際に`pulseaudio`で、panel-1にIDがない場合だけ再挿入します。
パネル設定全体や既存backupは置換しません。

`xfce4-session`はICE authorizationのhard-link lockを使い、PRootでは起動が長時間停滞するため常駐経路に使用しません。`ldfa-session`は`xfsettingsd`、`xfwm4 --compositor=off`、`xfce4-panel`、`xfdesktop`を直接・並列に起動し、Panel／Desktopの実ウィンドウまでready条件に含めます。

4要素は同じBash supervisorの直接の子です。supervisorはPIDを限定しない`wait -n -p`で全子processの終了イベントを待ちます。イベント発生時だけ50 msの同時終了集約を行い、各PIDの`/proc/<pid>/stat`をBash builtinで読み、PPIDとzombie状態を確認して不足した要素をまとめて再起動します。PIDを`wait`へ列挙しないのは、複数SIGKILLの最初の通知でBashが複数jobを回収した場合に、次の`wait`が失効済みPIDを無視して唯一の生存要素だけを待ち続ける競合を避けるためです。定常時に`ps`、`cat`、`xset`、`sleep`を繰り返さないため、監視自体がAndroidのapp child process上限を圧迫しません。Chrome復元helperの正常終了はXFCE crash-loop回数に含めません。Chrome launcherは正常終了と異常終了をmarkerで区別し、launcher shellが残れば1回だけ自己再起動します。launcherも終了した場合はActivity復帰時にsupervisorを安全に起こし、window manager復旧後に前回sessionを再起動します。

viewerのActivity復帰時は、main process内から同一UIDの`/proc`を直接読みます。現在のcontainer rootを祖先command lineに持つ`ldfa-session`、そのPIDを親に持つXFCE 4要素、Chrome markerとbrowser本体がそろっていれば、RunCommand／PRootを生成しない高速経路で終了します。supervisorが一部要素を交換中なら`/proc`だけで最大3秒追跡し、正常化した時点で終了します。supervisor、Chrome本体、X11 serviceのいずれかが欠ける場合だけinstalled controllerの厳密なEWMH／socket検査へ進みます。これにより復旧途中のwindow mappingを故障と誤認して、戻ったdesktopを二度目の全session再構築で消す競合を避けます。

controller は lifecycle 操作を host lock で直列化し、metadata を一時ファイルから atomic rename で更新します。古い DISPLAY の tmux worker を再利用せず、起動前に対象 worker を停止して現在の backend から作り直します。

## 内蔵コマンド結果

```text
Repository -> TermuxCommandClient -> RunCommandService -> controller
                                      │
                                      └─ PendingIntent -> TermuxResultService
```

controller の配置は一時ファイル、`chmod`、atomic rename の順です。同時実行による「実行中 script の truncate」を防ぎます。結果は process-local 連番ではなく UUID action / nonce で照合するため、古い process の遅延 PendingIntent が新しい command を完了させません。coroutine cancellation は通常エラーへ変換せず上位へ伝播します。

## 停止と復旧

- 起動失敗は active 状態を commit せず、対象 host worker、viewer、X11 / VNC endpoint を bounded cleanup します。
- native X11 は viewer を閉じてから `stopService()` を使い、service PID marker の process death まで待ちます。残留時は marker または `.X1-lock` の PID と `/proc/<pid>/cmdline` が `com.termux:x11` に一致する場合だけ SIGTERM / SIGKILL を使います。
- `queued` / `installing` の install worker と `starting` / `running` の session worker は heartbeat が再作成します。
- XFCE supervisor はsession全体の終了だけでなく、4要素のいずれかが個別終了した場合もX11が生存する範囲で不足分を再起動します。
- viewer を閉じただけなら Xorg / XFCE session は維持し、再表示時に Binder で接続し直します。

## 状態、ログ、共有フォルダ

```text
~/.local/share/linux-desktop-for-android/containers/<id>/
~/.local/share/linux-desktop-for-android/logs/<id>.log
~/.local/share/linux-desktop-for-android/logs/x11-server.log
~/.local/share/linux-desktop-for-android/logs/vnc-server.log
~/.local/share/linux-desktop-for-android/run/

Android: /storage/emulated/0/LinuxDesktop/<id>
Termux:  ~/storage/shared/LinuxDesktop/<id>
Debian:  /mnt/android
XFCE:    ~/Desktop/Android共有
```

## 制約

- 公式 Termux と同じ `applicationId` のため同時インストールできません。
- 旧外部 Termux の container は自動移行しません。
- PRoot には systemd、完全な Linux kernel 機能、通常の native GPU 権限がありません。
- Xorg server は remote process ですが viewer renderer は現在 main process です。
- Android の process death 自体は完全には禁止できません。Chrome本体まで終了された場合は新processと前回sessionの再生成が必要なため、正常な履歴復帰より表示に時間がかかります。端末メーカー固有のbackground制御を含む実機確認は引き続き必要です。
