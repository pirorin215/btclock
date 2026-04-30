package com.pirorin215.btclockmob.bluetooth.constants

/**
 * BLE関連の定数を統合管理するクラス
 *
 * 役割:
 * - UUID定数の統一管理
 * - デバイス定数の定義
 * - 通知チャンネルIDの管理
 */
object BleConstants {
    // --- UUID定数 ---
    /**
     * BLEサービスUUID
     * BikeClockファームウェアのUUID
     */
    const val SERVICE_UUID_STRING = "4fafc201-1fb5-459e-8fcc-c5c9c331914c"

    /**
     * コマンドキャラクタリスティックUUID（双方向: READ | WRITE | NOTIFY）
     * 時刻同期コマンドの送信とレスポンス通知の受信に使用
     */
    const val COMMAND_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26a0"

    // --- OTA DFU UUID定数 ---
    /**
     * Nordic DFUサービスUUID
     * Adafruit nRF52のDFUモードで使用される標準UUID
     */
    const val OTA_SERVICE_UUID_STRING = "00001530-1212-efde-1523-785feabcd123"

    /**
     * Nordic DFU Control Point UUID
     * DFU制御コマンドの送信に使用
     */
    const val OTA_CONTROL_UUID_STRING = "00001531-1212-efde-1523-785feabcd123"

    /**
     * Nordic DFU Packet UUID
     * ファームウェアデータの転送に使用
     */
    const val OTA_PACKET_UUID_STRING = "00001532-1212-efde-1523-785feabcd123"

    // Note: RESPONSE_UUID_STRING is no longer needed
    // We use a single bidirectional characteristic for both commands and responses

    /**
     * CCCD (Client Characteristic Configuration Descriptor) UUID
     * 通知の有効/無効を設定するためのディスクリプタUUID
     */
    const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"

    // --- デバイス定数 ---
    /**
     * サービス遅延時間（ミリ秒）
     * 接続後にサービスディスカバリを開始するまでの待機時間
     */
    const val SERVICE_DISCOVERY_DELAY_MS = 1000L

    /**
     * DFUモードのデバイス名
     * Adafruit nRF52がDFUモード時に使用するデバイス名
     */
    const val DFU_DEVICE_NAME = "AdaDFU"

    // --- コマンド定数 ---
    /**
     * 時刻同期コマンドプレフィックス
     */
    const val CMD_TIME_SYNC = "SET:time"

    /**
     * バージョン取得コマンド
     * デバイスのファームウェアバージョンを取得する
     */
    const val CMD_GET_VERSION = "GET:version"

    // --- レスポンス定数 ---
    /**
     * 成功レスポンスプレフィックス
     */
    const val RESPONSE_OK = "OK"

    /**
     * エラーレスポンスプレフィックス
     */
    const val RESPONSE_ERROR = "ERROR"

    /**
     * 時刻同期成功レスポンスプレフィックス
     */
    const val RESPONSE_OK_TIME = "OK: Time"

    // --- プロトコルフォーマット定数 ---
    /**
     * コマンドセパレータ
     */
    const val COMMAND_SEPARATOR = ":"

    /**
     * レスポンスセパレータ（スペース）
     */
    const val RESPONSE_SEPARATOR = ": "
}
