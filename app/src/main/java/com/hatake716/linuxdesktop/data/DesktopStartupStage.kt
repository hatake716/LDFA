package com.hatake716.linuxdesktop.data

/** Completed milestones, not a timer or a prediction of remaining seconds. */
enum class DesktopStartupStage(val percent: Int, val message: String) {
    PREPARING(0, "起動準備を始めています"),
    CHECKING_SESSION(5, "既存のセッションを確認しています"),
    PREPARING_APPS(15, "Linuxアプリの設定を確認しています"),
    STARTING_X11(30, "X11表示サーバーを起動しています"),
    CONNECTING_VIEWER(45, "Androidの表示画面へ接続しています"),
    CHECKING_X11_FRAME(55, "X11の描画を確認しています"),
    STARTING_LINUX(65, "Linuxの起動処理を開始しています"),
    PREPARING_SESSION(72, "音声とLinuxセッションを準備しています"),
    STARTING_XFCE(82, "XFCEデスクトップの起動を待っています"),
    VERIFYING_DESKTOP(92, "デスクトップの描画を確認しています"),
    READY(100, "デスクトップを表示しました"),
    CLEANING_UP(0, "起動を停止し、後処理を行っています"),
}
