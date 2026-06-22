package com.pirorin215.btclockmob.viewModel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.BleEvent
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 推論ログ取得 ViewModel（駐車検知の精度チューニング用）。
 *
 * マイコンへ INFER_LOG:1/0 でログ送信を ON/OFF 制御し、毎推論（1Hz）で届く
 *   INFER:<ms>,<candidate>,<dist>,<f0>..<f8>
 * を受信・蓄積し、CSV でダウンロードへ保存する。
 * PC をバイクに持っていけない環境で、スマホだけで dist/特徴量の時系列を記録する目的。
 *
 * マイコン側（bikeclock_esp32_motion.ino updateMotionInference）とプロトコル一致:
 *   candidate: 最近傍候補ラベル（距離>3.0 で "-"）
 *   dist: 正規化空間の最近傍距離（閾値 3.0）
 *   f0..f8: 正規化前の特徴量（accRms,accPeak,gyroRms,gyroPeak,tilt,jerkPeak,gravAx,gravAy,gravAz）
 */
class InferenceLogViewModel(
    private val repository: BleRepository,
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "InferenceLogVM"
        private const val MAX_ENTRIES = 2000      // 蓄積上限（古いものから破棄・メモリ保護）
        private const val FEAT_DIM = 9           // 特徴量次元（マイコン MOTION_FEAT_DIM と一致）
    }

    /** 受信ログ1件 */
    data class InferenceLogEntry(
        val ms: Long,
        val candidate: String,
        val dist: Float,
        val features: List<Float>     // 正規化前の特徴量（最大9次元）
    )

    /** 画面状態 */
    sealed class LogState {
        object Idle : LogState()                                  // 送信OFF
        data class Logging(val count: Int) : LogState()           // 送信ON・蓄積中
        data class Error(val message: String) : LogState()
    }

    private val _state = MutableStateFlow<LogState>(LogState.Idle)
    val state: StateFlow<LogState> = _state.asStateFlow()

    private val _entries = MutableStateFlow<List<InferenceLogEntry>>(emptyList())
    val entries: StateFlow<List<InferenceLogEntry>> = _entries.asStateFlow()

    /** 接続状態（UI のボタン有効化用） */
    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    /** 最後に保存した CSV の共有 Uri（API<29 フォールバック用・設定されないこともある） */
    var savedShareUri: Uri? = null
        private set

    init {
        // InferenceLog イベントの監視（GATT コールバックスレッド → SharedFlow → ここで消費）
        viewModelScope.launch {
            repository.events.collect { event ->
                if (event is BleEvent.InferenceLog) handleLine(event.line)
            }
        }
    }

    /** "INFER:<ms>,<candidate>,<dist>,<f0>..<f8>" をパースして蓄積 */
    private fun handleLine(line: String) {
        val fields = line.removePrefix("INFER:").split(",")
        if (fields.size < 3) return
        val ms = fields[0].toLongOrNull() ?: return
        val candidate = fields[1]
        val dist = fields[2].toFloatOrNull() ?: return
        val features = fields.drop(3).mapNotNull { it.toFloatOrNull() }
        val entry = InferenceLogEntry(ms, candidate, dist, features)

        val list = ArrayList(_entries.value).apply {
            add(entry)
            while (size > MAX_ENTRIES) removeAt(0)
        }
        _entries.value = list
        _state.value = LogState.Logging(list.size)
    }

    /** ログ送信開始（マイコンへ INFER_LOG:1）。蓄積ログはクリアして新規セッションへ */
    fun start() {
        val conn = repository.connectionState.value
        if (conn !is ConnectionState.Connected) {
            _state.value = LogState.Error("デバイスに接続されていません")
            return
        }
        _entries.value = emptyList()
        savedShareUri = null
        val ok = repository.sendCommand("INFER_LOG:1")
        if (!ok) {
            _state.value = LogState.Error("INFER_LOG:1 コマンドの送信に失敗しました")
            return
        }
        _state.value = LogState.Logging(0)
        Log.d(TAG, "INFER_LOG started")
    }

    /** ログ送信停止（マイコンへ INFER_LOG:0）。蓄積ログは保持（CSV 保存のため） */
    fun stop() {
        repository.sendCommand("INFER_LOG:0")
        val count = _entries.value.size
        _state.value = LogState.Idle
        Log.d(TAG, "INFER_LOG stopped (entries=$count)")
    }

    /** 蓄積ログをクリア（送信状態は維持） */
    fun clear() {
        _entries.value = emptyList()
        if (_state.value is LogState.Logging) _state.value = LogState.Logging(0)
    }

    /** 蓄積ログから CSV 文字列を生成 */
    fun generateCsv(): String {
        val entries = _entries.value
        if (entries.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("# BikeClock Inference Log\n")
        sb.append("# firmware: ").append(repository.deviceVersion.value ?: "unknown").append('\n')
        sb.append("# candidate: 最近傍候補（- は距離>閾値で不明）\n")
        sb.append("# dist: 正規化空間の最近傍距離（閾値 3.0）\n")
        sb.append("# f0..f8: accRms,accPeak,gyroRms,gyroPeak,tilt,jerkPeak,gravAx,gravAy,gravAz（正規化前）\n")
        sb.append("ms,candidate,dist,f0,f1,f2,f3,f4,f5,f6,f7,f8\n")
        for (e in entries) {
            sb.append(e.ms).append(',').append(e.candidate).append(',')
              .append(String.format(Locale.US, "%.2f", e.dist))
            for (i in 0 until FEAT_DIM) {
                sb.append(',')
                val f = e.features.getOrNull(i)
                if (f != null) sb.append(String.format(Locale.US, "%.3f", f))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** CSV をダウンロードへ保存。保存ファイル名（失敗時 null）を返す */
    fun saveToDownloads(): String? {
        val csv = generateCsv()
        if (csv.isEmpty()) return null
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "inferlog_$ts.csv"
        val bytes = csv.toByteArray(Charsets.UTF_8)
        savedShareUri = null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(fileName, bytes)
            } else {
                saveViaExternalFiles(fileName, bytes)
            }
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "saveToDownloads failed", e)
            null
        }
    }

    /** API29+: MediaStore.Downloads へ保存。ファイルアプリの「ダウンロード」に現れる */
    private fun saveViaMediaStore(fileName: String, bytes: ByteArray) {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("openOutputStream failed")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        savedShareUri = uri
    }

    /** API<29 フォールバック: アプリ固有外部ストレージの Download/inferlog へ保存 */
    private fun saveViaExternalFiles(fileName: String, bytes: ByteArray) {
        val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "inferlog").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        // 共有用 Uri（file_paths.xml に未設定でも保存自体は成功。try-catch で無視）
        savedShareUri = try {
            FileProvider.getUriForFile(appContext, "com.pirorin215.btclockmob.provider", file)
        } catch (e: Exception) {
            null
        }
    }
}
