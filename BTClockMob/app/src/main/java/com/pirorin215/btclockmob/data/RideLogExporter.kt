package com.pirorin215.btclockmob.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日記(Mac mini)向けの乗降ログ書き出し。
 * ユーザーが指定したSAFフォルダ(Syncthing共有の diary-loc を想定)配下の
 *   cycleclock/ride-YYYY-MM-DD.jsonl
 * に1行1イベント {"t":epochMillis, "lat", "lon", "adr", "ev"} を追記する。
 * ev: "conn"=接続(乗車), "disc"=切断(降車), "periodic"=乗車中定期記録。
 * 住所は device_history 記録時に取得済みのものを流用し、ここではgeocodeしない。
 *
 * 書き出し条件は AppSettingsRepository の RIDE_LOG_ENABLED / RIDE_LOG_TREE_URI。
 * 失敗しても例外を投げない(乗降ログの失敗でBLE常駐機能を妨げない)。
 */
class RideLogExporter(
    private val context: Context,
    private val appSettingsRepository: AppSettingsRepository
) {
    companion object {
        private const val TAG = "RideLogExporter"
        private const val DIR_NAME = "cycleclock"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val JST = ZoneId.of("Asia/Tokyo")
    }

    /** 記録が有効(スイッチON + 書き出し先フォルダ指定済み)か */
    suspend fun isEnabled(): Boolean {
        if (!appSettingsRepository.getFlow(Settings.RIDE_LOG_ENABLED).first()) return false
        return appSettingsRepository.getFlow(Settings.RIDE_LOG_TREE_URI).first().isNotEmpty()
    }

    private var cachedDayFile: Pair<String, DocumentFile>? = null

    /**
     * その日のJSONLファイルを返す(無ければ作成)。SAFはMIMEと拡張子が合わないと
     * 作成名を勝手に書き換える(.jsonl→…jsonl.json等)ため、完全一致でなく
     * 「ride-YYYY-MM-DD」前方一致で探し、ヒットした実際のファイルに追記する
     * (作成は拡張子を書き換えられないapplication/octet-streamで行う)。
     */
    private fun findOrCreateDayFile(dir: DocumentFile, date: String): DocumentFile? {
        cachedDayFile?.let { (d, f) -> if (d == date && f.exists()) return f }
        val found = dir.listFiles().firstOrNull {
            it.name?.startsWith("ride-$date") == true && it.isFile
        }
        val file = found ?: dir.createFile("application/octet-stream", "ride-$date.jsonl")
        if (file != null) cachedDayFile = date to file
        return file
    }

    /** エントリ→JSONオブジェクト。位置が無いイベントは null */
    private fun entryJson(entry: DeviceHistoryEntry): JSONObject? {
        val lat = entry.latitude ?: return null
        val lon = entry.longitude ?: return null
        val ev = when {
            entry.isPeriodic -> "periodic"
            entry.isDisconnection -> "disc"
            else -> "conn"
        }
        val o = JSONObject()
            .put("t", entry.timestamp)
            .put("lat", lat)
            .put("lon", lon)
            .put("ev", ev)
        entry.address?.let { o.put("adr", it) }
        return o
    }

    /**
     * 過去のデバイス履歴を日次ファイルへ一括書き出す。アドホック運用:
     * 同じエントリを再書き出してもMac側でレコード単位の重複排除を行う想定。
     * 戻り値=書き込んだ件数。
     */
    suspend fun exportHistory(entries: List<DeviceHistoryEntry>): Int {
        var written = 0
        try {
            val uriStr = appSettingsRepository.getFlow(Settings.RIDE_LOG_TREE_URI).first()
            if (uriStr.isEmpty()) return 0
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return 0
            val dir = tree.findFile(DIR_NAME) ?: tree.createDirectory(DIR_NAME) ?: return 0
            entries.filter { it.latitude != null && it.longitude != null }
                .groupBy { DATE_FMT.format(Instant.ofEpochMilli(it.timestamp).atZone(JST)) }
                .forEach { (date, dayEntries) ->
                    val file = findOrCreateDayFile(dir, date) ?: return@forEach
                    val lines = dayEntries.mapNotNull(::entryJson)
                        .joinToString("") { it.toString() + "\n" }
                    if (lines.isEmpty()) return@forEach
                    context.contentResolver.openOutputStream(file.uri, "wa")?.use { os ->
                        os.write(lines.toByteArray())
                    }
                    written += dayEntries.size
                }
            Log.d(TAG, "Ride history exported: $written entries")
        } catch (e: Exception) {
            Log.w(TAG, "Ride history export failed: ${e.message}")
        }
        return written
    }

    /** 乗降イベント1件を日次JSONLへ追記 */
    suspend fun appendRide(entry: DeviceHistoryEntry) {
        try {
            if (!isEnabled()) return
            val json = entryJson(entry) ?: return
            val uriStr = appSettingsRepository.getFlow(Settings.RIDE_LOG_TREE_URI).first()
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return
            val dir = tree.findFile(DIR_NAME) ?: tree.createDirectory(DIR_NAME) ?: return
            val date = DATE_FMT.format(Instant.ofEpochMilli(entry.timestamp).atZone(JST))
            val file = findOrCreateDayFile(dir, date) ?: return
            context.contentResolver.openOutputStream(file.uri, "wa")?.use { os ->
                os.write((json.toString() + "\n").toByteArray())
            }
            Log.d(TAG, "Ride logged: $date ev=${json.optString("ev")}")
        } catch (e: Exception) {
            Log.w(TAG, "Ride log failed: ${e.message}")
        }
    }
}
