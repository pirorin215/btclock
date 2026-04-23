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

    // Note: RESPONSE_UUID_STRING is no longer needed
    // We use a single bidirectional characteristic for both commands and responses

    /**
     * CCCD (Client Characteristic Configuration Descriptor) UUID
     * 通知の有効/無効を設定するためのディスクリプタUUID
     */
    const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"

    // --- 通知チャンネル定数 ---
    /**
     * 低電圧通知チャンネルID
     */
    const val LOW_VOLTAGE_CHANNEL_ID = "LowVoltageChannel"

    /**
     * 低電圧通知ID
     */
    const val LOW_VOLTAGE_NOTIFICATION_ID = 2001

    // --- デバイス定数 ---
    /**
     * サービス遅延時間（ミリ秒）
     * 接続後にサービスディスカバリを開始するまでの待機時間
     */
    const val SERVICE_DISCOVERY_DELAY_MS = 1000L

    /**
     * 最大MTUサイズ
     * BLEパケットの最大転送サイズ
     */
    const val MAX_MTU = 517

    /**
     * デフォルトMTUサイズ
     */
    const val DEFAULT_MTU = 23

    // --- コマンド定数 ---
    /**
     * 時刻同期コマンドプレフィックス
     */
    const val CMD_TIME_SYNC = "SET:time"

    /**
     * デバイス情報取得コマンド
     */
    const val CMD_GET_INFO = "GET:info"

    /**
     * ファイルリスト取得コマンドプレフィックス
     */
    const val CMD_GET_FILE_LIST = "GET:ls"

    /**
     * 設定取得コマンド
     */
    const val CMD_GET_SETTINGS = "GET:setting_ini"

    /**
     * 設定送信コマンドプレフィックス
     */
    const val CMD_SEND_SETTINGS = "SET:setting_ini"

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
     * JSON開始マーカー
     */
    const val JSON_START_MARKER = "{"

    /**
     * JSON終了マーカー
     */
    const val JSON_END_MARKER = "}"

    /**
     * 配列開始マーカー
     */
    const val ARRAY_START_MARKER = "["

    /**
     * 配列終了マーカー
     */
    const val ARRAY_END_MARKER = "]"

    /**
     * 空配列表現
     */
    const val EMPTY_ARRAY = "[]"

    /**
     * コマンドセパレータ
     */
    const val COMMAND_SEPARATOR = ":"

    /**
     * レスポンスセパレータ（スペース）
     */
    const val RESPONSE_SEPARATOR = ": "
}
