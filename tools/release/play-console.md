# Play Console 提出資料一式（LDFA v1.1.0 / com.hatake716.linuxdesktop）

Console の各画面へコピー&ペーストするための確定文面。英語欄は英語文面を、
日本語欄は日本語文面をそのまま使う。

---

## 1. specialUse フォアグラウンドサービス申告

場所: **ポリシー → アプリのコンテンツ → フォアグラウンド サービスの権限**

### 共通説明（申告フォームの本文。英語）

> LDFA runs a complete Debian Linux desktop (XFCE) locally on the user's
> device, inside a userspace sandbox (PRoot). When the user explicitly opens
> their Linux desktop, the app must keep long-lived local infrastructure
> running even while the screen is off or the user briefly switches apps:
> the Linux session itself, the X11 display server that renders it, and a
> lightweight health monitor. Stopping these on backgrounding would destroy
> the user's running Linux applications and unsaved work, which is the core
> product promise (a persistent desktop computer experience).
>
> No predefined foreground service type fits: this is not media playback,
> not location, not a connected device, and dataSync is both semantically
> wrong and time-limited (~6h) while a desktop session legitimately runs
> for many hours. All services start only after an explicit user action
> (opening the desktop / starting an installation), show persistent
> notifications, and stop automatically when the user stops the desktop or
> the app detects it is idle.
>
> The four declared services:
> 1. TermuxService — owns the Linux session processes (terminal runtime the
>    desktop runs in). Started when the user opens the desktop; stops with it.
> 2. RunCommandService — dispatches the user's management operations
>    (install / start / stop) into the Linux runtime. Short-lived per
>    operation.
> 3. EmbeddedX11ServerService — the X11 display server rendering the
>    desktop (separate process so a graphics crash cannot take down the
>    app). Alive exactly while a desktop is running.
> 4. DesktopKeepAliveService — a heartbeat that repairs the desktop session
>    (e.g. after process death) while it is supposed to be running; stops
>    itself after two idle checks.
>
> A demonstration video is provided showing the user starting the desktop,
> the foreground service notifications, use of the Linux desktop, and the
> services stopping when the user stops the desktop.

### 動画

- YouTube 限定公開でアップロードし、URL をフォームに貼る
- 素材（リポジトリ直下のローカル `release-assets/`）:
  `ldfa-fgs-demo-part1.mp4`、`ldfa-fgs-demo-part2.mp4`
- 内容: ホーム画面 → 「Debian XFCEを開く」→ 通知シェードにフォアグラウンド
  サービス通知 → XFCE デスクトップ操作 → 「停止」で終了

---

## 2. データ セーフティ（アプリのコンテンツ → データ セーフティ）

| 設問 | 回答 |
|---|---|
| ユーザーデータを収集しますか | **いいえ** |
| ユーザーデータを第三者と共有しますか | **いいえ** |
| データは転送中に暗号化されますか | （収集なしのため設問スキップ） |
| データの削除をリクエストできますか | （収集なしのため設問スキップ） |
| 独立したセキュリティ審査 | いいえ |

補足（もし「アプリがインターネットに接続するのに収集なし?」と確認された場合の考え方）:
通信はすべて Debian/Google/Node.js 公式サーバーからの**ダウンロード**であり、
ユーザーデータのアップロードは一切ない。解析・広告 SDK なし。
Linux 環境内でユーザーが自発的に行う通信（ブラウザ等）は「アプリによる収集」に該当しない。

---

## 3. レビュアーノート（アプリのコンテンツ → アプリへのアクセス の補足欄）

「すべての機能が制限なく利用可能」を選択した上で、メモ欄に:

> All functionality is available without login. Note for policy review:
> LDFA is a Linux environment app (comparable to Termux or UserLAnd). It
> downloads the Debian OS and Linux software from official repositories at
> the user's request and executes them ONLY inside a userspace PRoot
> sandbox as an unprivileged process. Downloaded code cannot modify the
> signed APK or the app's behavior — this falls under the interpreter /
> virtual-machine exception of the Device and Network Abuse policy. The
> security model is documented publicly:
> https://github.com/hatake716/LDFA/blob/main/SECURITY.md
> The bundled Linux userland is rebuilt from source for this app's own
> application ID; the app is unrelated to, and does not impersonate, the
> Termux project's Play listing.

---

## 4. ストア掲載情報

### 日本語

**アプリ名**（30 文字以内）:
`LDFA - Linuxデスクトップ`

**簡単な説明**（80 文字以内）:
`AndroidにDebian+XFCEのLinuxデスクトップを丸ごと構築。日本語入力・バックアップ対応。root不要。`

**詳しい説明**（4000 文字以内）:

```
LDFA（Linux Desktop for Android）は、Android スマートフォン・タブレットの中に本物の Debian Linux デスクトップを構築するアプリです。root 化は不要。ボタンひとつで、XFCE デスクトップ・日本語入力・ブラウザまで自動でセットアップされます。

■ 特長
・Debian 12 + XFCE デスクトップをワンタップで自動構築
・日本語 UI・日本語フォント・Fcitx5 + Mozc による日本語入力を最初から設定済み
・Google Chrome を自動インストール（本物のデスクトップ版ブラウザ）
・Node.js 22 を同梱セットアップ。npm で各種 CLI ツールを導入可能
・内蔵 X11 サーバーによるネイティブ描画。タッチ・マウス・物理キーボード対応
・JIS / US キーボード配列の切り替え、画面全体の表示スケール調整（100〜250%）
・環境まるごとを 1 つの .ldfa ファイルへバックアップ。機種変更時は新しい端末で復元
・すべて端末内で完結。アカウント登録・広告・解析なし

■ こんな方に
・スマホやタブレットを「もう一台の Linux PC」として使いたい
・外出先でデスクトップ版ブラウザや Linux の開発環境が必要
・Linux を勉強したいが、PC を用意せずに始めたい

■ 動作の仕組み
LDFA は Termux 由来のユーザーランドと PRoot 技術を使い、Android のセキュリティモデルの内側（アプリのサンドボックス内）で Linux を実行します。システム領域には一切変更を加えません。アプリを削除すれば、Linux 環境も一緒に消去されます。

■ 動作要件
・64bit ARM（arm64-v8a）端末
・空き容量: 1 環境につき 3〜5GB 以上を推奨
・メモリ 4GB 以上を推奨
・初回セットアップに安定したインターネット接続（数 GB のダウンロードが発生します）

■ 注意事項
・初回インストールには回線速度により 30 分〜1 時間程度かかります
・PRoot 上で動作するため、実 PC と比べて処理速度は低下します
・Google Chrome は Google 社の、Debian は Debian プロジェクトの商標・成果物です。本アプリはユーザーの操作により各公式配布元からこれらを取得します

■ オープンソース
本アプリは GPLv3 で公開されています: https://github.com/hatake716/LDFA
```

### English

**App name** (≤30 chars):
`LDFA - Linux Desktop`

**Short description** (≤80 chars):
`A full Debian + XFCE Linux desktop on your Android device. No root required.`

**Full description** (≤4000 chars):

```
LDFA (Linux Desktop for Android) builds a real Debian Linux desktop inside your Android phone or tablet. No root required — one tap sets up the XFCE desktop, input methods, and a desktop-class browser automatically.

FEATURES
• Debian 12 + XFCE desktop, fully automated one-tap setup
• Google Chrome installed automatically (the real desktop browser)
• Node.js 22 preconfigured — install CLI tools with npm
• Built-in X11 server with native rendering; touch, mouse and physical keyboards supported
• Japanese input (Fcitx5 + Mozc) preconfigured; JIS/US keyboard layouts; 100–250% display scaling
• Back up a whole environment to a single .ldfa file and restore it on a new device
• Everything stays on your device: no account, no ads, no analytics

HOW IT WORKS
LDFA runs Linux inside Android's own security model using a Termux-derived userland and PRoot — a userspace sandbox. It never modifies the system. Uninstalling the app removes the Linux environments with it.

REQUIREMENTS
• 64-bit ARM (arm64-v8a) device
• 3–5 GB of free storage per environment recommended
• 4 GB+ RAM recommended
• A stable Internet connection for the first setup (several GB will be downloaded)

NOTES
• The first installation takes roughly 30–60 minutes depending on your connection
• Running under PRoot is slower than a physical PC
• Google Chrome is a trademark of Google LLC; Debian is a product of the Debian Project. The app fetches them from their official distribution servers at the user's request

OPEN SOURCE
LDFA is published under GPLv3: https://github.com/hatake716/LDFA
```

---

## 5. コンテンツレーティング（IARC）回答案

- カテゴリ: ユーティリティ・生産性・通信・その他
- 暴力・性的内容・不適切な言葉・ギャンブル・薬物: すべて **なし**
- **ユーザー間の交流/コンテンツ共有**: なし（アプリ自体には SNS 機能なし）
- **無制限のインターネットアクセス**: **はい**（ブラウザを含む Linux 環境を提供するため）
- 個人情報の共有: なし / 位置情報の共有: なし / デジタル購入: なし

→ 想定レーティング: 3+/全年齢相当（無制限インターネットの注記付き）

---

## 6. その他の申告

- ターゲット年齢層: **18 歳以上**
- 広告の有無: **広告なし**
- ニュースアプリ: いいえ / 政府向け: いいえ / 健康アプリ: いいえ
- プライバシーポリシー URL: `https://hatake716.github.io/LDFA/privacy.html`
  （docs/privacy.html を main へマージ後に有効化）

---

## 7. 提出チェックリスト

1. [ ] デベロッパーアカウント登録（$25・本人確認）
2. [ ] アプリ作成（LDFA / 日本語 / 無料）
3. [ ] 内部テストへ app-release.aab をアップロード（Play App Signing 登録）
4. [ ] ストア掲載情報（上記 4 + アイコン 512x512 + フィーチャーグラフィック 1024x500 + スクリーンショット `release-assets/`）
5. [ ] プライバシーポリシー URL 設定（docs/privacy.html を main へ）
6. [ ] データセーフティ（上記 2）
7. [ ] コンテンツレーティング（上記 5）
8. [ ] specialUse FGS 申告（上記 1 + 動画 URL）
9. [ ] ターゲット年齢層ほか（上記 6）
10. [ ] クローズドテスト: テスター 12 人以上 × 14 日間（個人アカウント要件）
11. [ ] 本番申請
