# LDFAの概要

LDFAは、Debian 12とXFCEのデスクトップ環境をAndroidアプリの中で導入・管理するプロジェクトです。
アプリIDは`com.hatake716.linuxdesktop`で、公式Termuxとは共存できます。

## ユーザーの導線

初回は名前と必要な容量・通信を確認してLinuxを導入するか、既存の`.ldfa`を復元します。
導入済みの環境はホームに並び、同じカードから起動・再表示・停止・修復できます。

設定では表示倍率、特殊キー、キーボード配列を変更します。ツールにはターミナル、バックアップ・復元、実行基盤の状態確認があります。

## 基盤

Composeによる管理画面、内蔵Termux実行環境、専用プロセスのX11サービス、PRoot内のDebianを組み合わせます。
Android向けのネイティブPRootをAPK内のライブラリ領域から実行し、アプリ専用prefix向けにソースビルドしたbootstrapを使用します。

画面の再作成をまたぐ導入・起動、実行中サービス、起動の世代確認、実プロセスと実描画の検査、子プロセスの復旧を実装しています。Androidがプロセスを終了する可能性は残ります。

## 機能・資料

- [現行の機能と動作環境](../README.md)
- [導入・データ移行](INSTALLATION.md)
- [内部構成](ARCHITECTURE.md)
- [検証](TESTING.md)
- [署名・リリース](../tools/release/README.md)
- [Google Play提出資料](../tools/release/play-console.md)
