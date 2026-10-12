package com.pirorin215.btclockmob.data

/**
 * 通知テキストを BLE 単一 Write に収まるフレーム列へ分割する（cycleclock v0.4.23+）。
 *
 * プロトコル（片方向・Ack なし・sendCommandSerial の逐次送信を前提）:
 *   1個目:     NOTIFY:app=<packageName>/<総数>\n<パート1>
 *   2個目以降: NOTIFYADD:<パートN>
 * 単一 Write に収まる場合・非対応機（firmwareChunkMaxBytes <= 0）は
 * 従来どおり "NOTIFY:app=<packageName>\n<text>" 1フレーム。
 *
 * 各フレームは MTU 247 の実効 244B から安全マージンを引いた 240B 以内に収め、
 * UTF-8 文字の途中では分割しない。text は呼び出し側でファームのバッファ上限
 * （GET:features の notifychunk 応答値・最大512B）へ切り詰めておくこと。
 */
object NotificationSplitter {

    /** 1 Write のバイト予算（MTU 247 - ATT ヘッダ 3B - 安全マージン） */
    private const val WRITE_BUDGET = 240

    private const val ADD_PREFIX = "NOTIFYADD:"

    /**
     * bytes を UTF-8 文字境界で maxLen バイト以内に切り詰めた長さを返す。
     * 切り目の直後が継続バイト(0b10xxxxxx)なら直前の文字境界まで戻る。
     */
    private fun utf8CutLen(bytes: ByteArray, maxLen: Int): Int {
        var len = minOf(maxLen, bytes.size)
        if (len >= bytes.size) return len   // 全体が収まる＝境界の心配なし
        while (len > 0 && (bytes[len].toInt() and 0xC0) == 0x80) len--
        return len
    }

    fun buildFrames(packageName: String, text: String, firmwareChunkMaxBytes: Int): List<String> {
        val single = "NOTIFY:app=$packageName\n$text"
        if (firmwareChunkMaxBytes <= 0 || single.toByteArray(Charsets.UTF_8).size <= WRITE_BUDGET) {
            return listOf(single)
        }

        val body = text.toByteArray(Charsets.UTF_8)
        val contBudget = WRITE_BUDGET - ADD_PREFIX.toByteArray(Charsets.UTF_8).size

        // 総数を仮置きしては本文を分割し、フレーム数が総数に収まる候補を探す。
        // ファーム側の総数上限は 8（アプリ側のテキスト上限512Bでは3分割程度に収まる）
        for (candidate in 2..8) {
            val headerBytes = "NOTIFY:app=$packageName/$candidate\n".toByteArray(Charsets.UTF_8).size
            val firstLen = utf8CutLen(body, WRITE_BUDGET - headerBytes)

            var off = firstLen
            var parts = 1
            while (off < body.size && parts < candidate) {
                off += utf8CutLen(body.copyOfRange(off, body.size), contBudget)
                parts++
            }
            if (off < body.size) continue   // この総数では収まらない

            val frames = mutableListOf<String>()
            frames.add("NOTIFY:app=$packageName/$candidate\n" +
                String(body, 0, firstLen, Charsets.UTF_8))
            off = firstLen
            while (off < body.size) {
                val n = utf8CutLen(body.copyOfRange(off, body.size), contBudget)
                frames.add(ADD_PREFIX + String(body, off, n, Charsets.UTF_8))
                off += n
            }
            return frames
        }

        // 8分割でも収まらない（想定外・テキスト上限の取りこぼし）:
        // 冒頭のみを従来形式で送る
        return listOf("NOTIFY:app=$packageName\n${text.take(60)}")
    }
}
