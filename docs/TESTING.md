# テスト方針

## ホストコントローラー

```bash
bash ./scripts/check-host-script.sh
bash ./scripts/test-host-controller.sh
```

確認対象:

- Debian、XFCE、Fcitx5/Mozc、日本語locale、Google Chrome、sudo
- Chrome公式amd64／arm64 package URL、package metadata検査、PRoot互換ランチャー
- 既存環境のChrome launcher世代更新、コンテンツrenderer上限2、background mode停止
- XFCE 4要素のdirect／event-driven supervisorと、定常時に外部polling processを生成しないこと
- `xset`、`xrefresh`、`xprop`の明示依存
- `.profile`、`.xprofile`、`.xinputrc`、Android共有symlink
- metadataのatomic更新、controller lock、tmux worker
- native`:1`／VNC`:2`のDISPLAY伝播
- app-private PulseAudio Unix socket、daemon再利用／stale復旧、AAudio fallback成功経路
- Debian Pulse／ALSA system drop-in、user pathへ直接書かず旧v1完全一致時だけ削除する静的契約
- XFCE panelのPulseAudio音量項目を保持し、旧`panel-mobile-v1`から一度だけ復元
- 日本語表示名と共有ファイルの保持／削除

両方のAndroid sink loadが失敗した実workerのdegraded継続と、任意内容のuser Pulse／ALSA
設定が前後でbyte一致することは、現行shell gateの動的実行範囲には含みません。下記の
実機回帰で別に記録します。

## X11 controllerと生成overlay

```bash
bash ./scripts/check-x11-controller.sh
```

確認対象:

- Android所有の`:x11` Service、direct Binder、世代UUID／PID barrier
- 旧`app_process`、loader APK、TCP 7892経路が復活していないこと
- viewer-first teardownとpending bind cancellation
- normal／legacy／VNC fallback
- Surface/EGL READY、successful-presentation serial probe、Surface再生成後のroot全画面damage
- JNI annotation/ABI、renderer mutex、EGL error、FD reconnect hardening
- 固定Termux:X11入力からJava/native overlayを再生成できること

## Kotlin単体テスト

```bash
bash ./gradlew testDebugUnitTest
```

環境状態解析、ホストスクリプト互換変換、DISPLAY metadata、strict X11 preflight、ログ整形などを検証します。

## 完全Androidビルド

```bash
git submodule update --init --recursive
bash ./gradlew --no-daemon \
  clean \
  testDebugUnitTest \
  :app:lintDebug \
  :termux-runtime:lintDebug \
  :embedded-x11:lintDebug \
  assembleDebug
```

CIはさらに次を検証します。

- Gradle 8.13 wrapper JARとdistributionのSHA-256
- `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`の`libXlorie.so`
- Manifestの非公開`:x11` Foreground Service
- `com.termux` application ID、versionCode 16、versionName 0.9.0、LDFA label
- `ToolsScreen`、X11/VNC assets
- APK内`ldfa-host.sh`とsourceのbyte一致、同じ音声static gateの再実行
- 再生専用buildに不要な`RECORD_AUDIO`／microphone foreground permissionがないこと
- 旧loader/app_process commandの不在
- APKのzip alignmentと署名

## 実機で必要な確認

- 初回Termux bootstrapとDebian PRootインストール
- native通常／legacyおよびVNC fallbackの実画面
- XFCE表示、回転、background復帰、viewer再接続
- タッチ、マウス、software/physical keyboard（Gboardのcomposition開始を含む）
- 日本語表示、Fcitx5/Mozc入力、`sudo apt update`
- Google Chromeの初回起動、利用規約確認、Webページ描画
- Chrome動画と既知のWAVを再生し、本体speaker／Bluetoothから音が出ること
- Google本人確認で別アプリへ移動し、履歴から戻って1〜2秒以内にChrome／XFCE全体が再表示されること
- 画面OFF、Activity再生成、RAM圧迫、X11 process死後の復旧
- 複数環境の作成・切替・削除
- Android共有フォルダと日本語／大容量ファイル
- 外部display、DeX系

自動テストとAPK検査だけではAndroid実機のSurface、GPU driver、入力、PRoot、端末固有の省電力制御を完全には再現できません。配布前には実機で最終確認します。

## 音声出力の実機回帰試験

APKを既存環境へ上書きした場合は、実行中の旧sessionを一度停止してから起動します。
これによりhost controller、session runtime v18、Pulse／ALSA drop-in、panel-mobile-v2が
適用されます。対象環境IDを`ID`へ設定します。最初に、startupが残した状態を変更しない
read-only snapshotを採取してください。

```bash
ID=対象のcontainer-id
TERMUX_BASH=/data/data/com.termux/files/usr/bin/bash

adb exec-out run-as com.termux "$TERMUX_BASH" -lc '
  export HOME=/data/data/com.termux/files/home
  export PREFIX=/data/data/com.termux/files/usr
  export PATH="$PREFIX/bin:/system/bin"
  STATE="$HOME/.local/share/linux-desktop-for-android"
  READY="$(cat "$STATE/containers/$1/audio_ready" 2>/dev/null || true)"
  printf "recorded_audio_ready=%s\n" "${READY:-missing}"
  if test -S "$PREFIX/var/run/ldfa-pulse-bridge/native"; then
    printf "audio_socket=present\n"
  else
    printf "audio_socket=missing\n"
  fi
  env -u PULSE_SERVER pactl list short modules 2>&1
  PULSE_SERVER="unix:$PREFIX/var/run/ldfa-pulse-bridge/native" pactl list short sinks 2>&1
  proot-distro login "$1" --shared-tmp \
    --bind "$PREFIX/var/run/ldfa-pulse-bridge:/tmp/ldfa-pulse" --user desktop -- \
    /usr/bin/env PULSE_SERVER=unix:/tmp/ldfa-pulse/native \
    /usr/bin/pactl info 2>&1
  tail -n 80 "$STATE/logs/$1.log" 2>/dev/null || true
' ldfa "$ID" > "audio-startup-$ID.txt"
```

startup合格には、修復前から`recorded_audio_ready=1`、専用socket、専用
`module-native-protocol-unix`が1個、非`auto_null` sink、guestの`pactl info`成功が必要です。
そのsnapshotを保存した後で、次のrepair-capable probeを実行します。

```bash
adb exec-out run-as com.termux "$TERMUX_BASH" -lc '
  export HOME=/data/data/com.termux/files/home
  export PREFIX=/data/data/com.termux/files/usr
  export PATH="$PREFIX/bin:/system/bin"
  "$HOME/.local/share/linux-desktop-for-android/bin/ldfa-host" audio-probe "$1"
' ldfa "$ID"
```

合格時は`audio_server=1`、`audio_guest=1`、専用socket path、`auto_null`ではない
`audio_sink`が出力されます。ただし`audio-probe`はdaemon／module／socketを修復し、
`audio_ready` metadataも更新するため、startup成功の証拠には使用しません。再生中のstreamは
Termux側で次のように確認します。

```bash
adb exec-out run-as com.termux "$TERMUX_BASH" -lc '
  export PREFIX=/data/data/com.termux/files/usr
  export PATH="$PREFIX/bin:/system/bin"
  PULSE_SERVER="unix:$PREFIX/var/run/ldfa-pulse-bridge/native" pactl list short sink-inputs
'
```

次のシナリオをnative X11とVNC fallbackの両方で確認します。

- Chromeで音声付き動画を再生し、再生中だけsink-inputが現れる
- XFCE panelに音量項目があり、mute／unmuteと音量変更が反映される
- 本体speaker、Bluetoothまたは有線出力へAndroid側routeを切り替えられる
- stop→startを3回繰り返して専用`module-native-protocol-unix`が重複しない
- PulseAudioを停止した後の次回startでdaemon、socket、実sinkが自動復旧する
- 既存のユーザー`~/.config/pulse/client.conf`と`~/.asoundrc`が変更されない

再現可能な証跡には次も保存します。これらは内蔵ターミナルで実行できます。

```bash
# stop→start各回で、専用moduleが常に1個であることを記録
env -u PULSE_SERVER pactl list short modules | awk \
  -v wanted="socket=$PREFIX/var/run/ldfa-pulse-bridge/native" '
    $2 == "module-native-protocol-unix" && index($0, wanted) { count++ }
    END { printf "ldfa_pulse_modules=%d\n", count }
  '

# 約444 Hzのsquare waveを3秒再生。pulseaudio-utilsのpacatだけを使用。
# bridge socketは$PREFIX/tmp外にあるため、guestの/tmp/ldfa-pulseへ明示bindする。
proot-distro login "$ID" --shared-tmp \
  --bind "$PREFIX/var/run/ldfa-pulse-bridge:/tmp/ldfa-pulse" --user desktop -- /bin/bash -c '
  export LC_ALL=C
  export PULSE_SERVER=unix:/tmp/ldfa-pulse/native
  for ((i=0; i<24000; i++)); do
    if (( (i / 9) % 2 )); then printf "\340"; else printf "\040"; fi
  done | pacat --raw --format=u8 --rate=8000 --channels=1
'

# user設定の更新前後を別ファイルへ保存し、diffが空であることを確認
proot-distro login "$ID" -- /bin/bash -c '
  for file in /home/desktop/.config/pulse/client.conf /home/desktop/.asoundrc; do
    if [[ -f "$file" ]]; then sha256sum "$file"; else printf "MISSING  %s\n" "$file"; fi
  done
' > audio-user-config-before.txt
# APK更新、stop→start、音声試験の後に同じcommandを
# audio-user-config-after.txtへ保存し、diff -uで比較する。
```

stale daemon復旧は、テスト対象の環境をUIで停止した後に次を実行し、その後UIから再度
起動して、修復前snapshotを採取します。

```bash
env -u PULSE_SERVER pulseaudio --kill
```

Android側のroute証跡は、再生前にlogcatをclearし、再生中／直後に保存します。

```bash
adb logcat -c
adb shell dumpsys audio > audio-route.txt
adb shell dumpsys media.audio_flinger > audio-flinger.txt
adb logcat -d -v threadtime > audio-logcat.txt
grep -Ei 'AudioTrack|AudioFlinger|AudioPolicy|AAudio|OpenSL|pulse|com\.termux' \
  audio-logcat.txt > audio-logcat-filtered.txt
```

clean installと既存環境upgradeを分け、本体speaker／Bluetooth／有線の各routeについて、
`recorded_audio_ready`、module個数、sink名、再生中sink-input、実際に聞こえたかを記録します。

`pactl info`やsink-inputの成功はtransportの証明であり、端末固有のAndroid audio route
から実際に可聴出力されたことの代用にはなりません。最後は本体speakerと利用予定の
Bluetooth／イヤホンで人が音を確認します。

## Chrome／Gboard回帰試験

2026-08-22のv0.9.0候補は、API 35 x86_64・4 GB RAM AVDの既存Debian環境へ更新インストールして次を確認しています。

- Chrome launcher v8とXFCE session runtime v17への自動移行
- Googleログイン画面のメール入力欄でGboardを表示
- Gboard表示中にX11画面が可視領域へ縮小し、閉じると元の解像度へ戻る
- Gboard表示後にGmailへ移動し、履歴画面からLDFAへ戻る操作を10回連続で実行して、各回1秒後にChrome／XFCEが黒画面でないことを画像輝度と目視で確認
- `test`をGboardからcomposition入力し、候補を確定
- LDFA main process、`:x11` process、Chromeが入力後も生存
- Chromeは`--renderer-process-limit=2`で起動し、コンテンツ用2個と`--top-chrome-webui`用1個の計3 renderer processで安定
- `FATAL EXCEPTION`、low-memory終了なし
- 通常の履歴復帰を8秒動画で記録し、LDFA card選択後約0.25秒でChrome／XFCE全体を再表示。要求する1〜2秒以内を満たす
- 通常復帰中の同一UID process数を約15 ms間隔で180回sampleし、全sampleで28個（Android main／`:x11`を除くapp childは26個）に固定。RunCommand、PRoot、診断shellの一時増加なし。修正前に観測した最大34個（app child 32個）を回避
- 履歴表示中にChrome launcher、本体、全helper／rendererと、`xfsettingsd`、`xfwm4`、Panel、Desktop、通知／tray helperの計16 processをSIGKILLし、80 ms後にLDFAへ復帰する最悪ケースを16秒動画で記録
- runtime v17で同じ16 process同時終了を3回実行。XFCE 4要素は動画記録回で約0.28秒、Chrome主processを含む必要要素は1.24／1.42／2.03秒で再生成し、約3〜4秒でChrome内容を自動復元。マウスポインタだけの永久黒画面にならないことを確認。Chromeの実process再作成が必要な場合は内容描画が1〜2秒を超えるため、通常復帰の基準と区別する
- 復旧した`xfsettingsd`、`xfwm4`、Panel、Desktopの全PIDが新しくなり、Chromeは`--restore-last-session`で再起動
- 3回とも再欠落sampleは0、復旧中の同一UID process最大値は30〜31（Android main／`:x11`を除くapp childは28〜29）で既定32未満。`ldfa-session` PIDは全回不変、`desktop health failed`件数は増えず、二度目のsession再構築、Foreground Service再起動、heartbeat repair、関連SELinux拒否ログは0件
- Chrome launcherと全Chrome processだけを同時終了する試験では、`xfsettingsd`だけを監視wake用に交換してChromeを復元し、`xfwm4`、Panel、DesktopのPIDを維持
- Gmailを前面にしてLDFA main processだけを強制終了する試験でも、`:x11`とChromeを維持したまま再生成viewerが既存セッションへ再接続

Pixel 10aではnative起動とGoogleログインのパスワード入力までは確認済みです。このAVD結果は、Gmail本人確認から戻る物理端末での受け入れを置き換えません。プレリリース前に本修正版で同じ復帰手順を複数回実行します。
