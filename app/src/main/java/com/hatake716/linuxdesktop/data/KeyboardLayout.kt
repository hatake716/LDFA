package com.hatake716.linuxdesktop.data

/**
 * Debian 環境で使用する物理キーボードの配列。
 *
 * 配列を決めるのは Xorg :1 の XKB 設定なので、[id] は
 * `ldfa-host set-keymap <id> <layout>` と `LDFA_KEYBOARD_LAYOUT` 経由で
 * ゲスト側スクリプトへそのまま渡り、`setxkbmap` / xfconf keyboard-layout /
 * Fcitx5 profile の値に変換される。将来 gb / de 等を足す場合もここへ
 * 1 エントリ追加すれば済む。
 */
enum class KeyboardLayout(
    /** metadata・スクリプト引数に使う安定した識別子。永続化される値。 */
    val id: String,
) {
    /** JIS 配列（日本語 106/109 キー）。半角/全角キーあり。従来の既定値。 */
    JIS("jis"),

    /** US 配列（英語 101/104 キー）。半角/全角キーなし。日本語切替は Ctrl+Space。 */
    US("us");

    companion object {
        /** 既定値。metadata 欠損（v1.0 系以前に作成された環境）はこれになる。 */
        val DEFAULT: KeyboardLayout = JIS

        /** metadata の文字列から復元する。未知・欠損は [DEFAULT]（JIS）へフォールバック。 */
        fun fromId(id: String?): KeyboardLayout =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
