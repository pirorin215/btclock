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
import com.pirorin215.btclockmob.data.ImuSample
import com.pirorin215.btclockmob.data.MotionFeatures
import com.pirorin215.btclockmob.data.MotionTrainingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 14-B: IMU データ採取 ViewModel
 *
 * マイコンへ IMU_DUMP を要求 → バイナリチャンク（0xAA55 マジック）を順次受信 →
 * seq 順に再構築 → int16 LSB を g/deg/s へ換算 → ラベル/メモ付き CSV を生成。
 *
 * チャンク仕様（マイコン bikeclock_esp32 と一致）:
 *   [0xAA][0x55][seq][total][status] [int16×6×N リトルエンディアン]  (ax,ay,az,gx,gy,gz)
 *   status: 0x00=継続, 0xFF=最終
 */
class ImuDataCaptureViewModel(
    private val repository: BleRepository,
    private val appContext: Context,
    private val trainingRepository: MotionTrainingRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ImuCaptureVM"
        private const val CHUNK_HEADER = 5                 // magic(2)+seq(1)+total(1)+status(1)
        private const val BYTES_PER_SAMPLE = 12            // int16×6
        private const val ACC_LSB_PER_G = 16384.0f         // ±2g   （マイコン BMI160_ACC_LSB_PER_G と同一）
        private const val GYR_LSB_PER_DPS = 131.072f       // ±250dps（マイコン BMI160_GYR_LSB_PER_DPS と同一）
        private const val CAPTURE_TIMEOUT_MS = 4000L       // 要求後/最終受信後、この時間チャンクが来なければタイムアウト
        private const val STATUS_LAST = 0xFF
    }

    /** 採取状態 */
    sealed class CaptureState {
        object Idle : CaptureState()
        object Requesting : CaptureState()
        data class Recording(val progress: Float) : CaptureState()
        data class Receiving(val receivedChunks: Int, val totalChunks: Int) : CaptureState()
        data class Complete(
            val sampleCount: Int,
            val missingChunks: List<Int>,
            val savedFileName: String?,
            val saving: Boolean,
            val addedToTraining: Boolean = false
        ) : CaptureState()
        data class Error(val message: String) : CaptureState()
    }

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** 接続状態（UI の接続表示・ボタン有効化用） */
    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    // 受信チャンク（seq → ペイロード）。チャンク受信ごとに古い順（seq昇順）で再構築できるよう保持。
    private val chunks = sortedMapOf<Int, ByteArray>()
    private var expectedTotal = 0
    private var requestTimeMs = 0L
    private var lastChunkTimeMs = 0L
    private var finalized = false
    private var lastSamples: List<ImuSample> = emptyList()

    /** 採取時のラベル/メモ（取得完了時の自動保存で使用） */
    private var pendingLabel: String = ""
    private var pendingMemo: String = ""

    /** 自動保存したファイルの共有用 Uri（MediaStore Uri または FileProvider Uri） */
    var savedShareUri: Uri? = null
        private set

    init {
        // ImuChunk チャンク受信の監視（GATT コールバックスレッド → SharedFlow → ここで消費）
        viewModelScope.launch {
            repository.events.collect { event ->
                if (event is BleEvent.ImuChunk) handleChunk(event.data)
            }
        }
        // タイムアウト監視: 要求への無応答、または受信途中の中断を検知して完了/エラー化
        viewModelScope.launch {
            while (true) {
                delay(500)
                val now = System.currentTimeMillis()
                when (val s = _state.value) {
                    is CaptureState.Requesting -> {
                        if (now - requestTimeMs > CAPTURE_TIMEOUT_MS) {
                            _state.value = CaptureState.Error("タイムアウト: デバイスから応答がありません")
                        }
                    }
                    is CaptureState.Receiving -> {
                        if (now - lastChunkTimeMs > CAPTURE_TIMEOUT_MS) {
                            finalize()   // 受信中断 → 届いている分で完了（学習データなので部分でも可）
                        }
                    }
                    else -> { /* Idle/Complete/Error は監視不要 */ }
                }
            }
        }
    }

    /** 「データ取得」ボタン押下: IMU_DUMP 要求を送信 */
    fun requestDump(label: String, memo: String) {
        val conn = repository.connectionState.value
        if (conn !is ConnectionState.Connected) {
            _state.value = CaptureState.Error("デバイスに接続されていません")
            return
        }
        pendingLabel = label
        pendingMemo = memo
        savedShareUri = null
        chunks.clear()
        expectedTotal = 0
        finalized = false
        lastSamples = emptyList()
        requestTimeMs = System.currentTimeMillis()
        lastChunkTimeMs = requestTimeMs
        _state.value = CaptureState.Requesting
        val ok = repository.sendCommand("IMU_RECORD_START")
        if (!ok) {
            _state.value = CaptureState.Error("IMU_RECORD_START コマンドの送信に失敗しました")
            return
        }
        Log.d(TAG, "IMU_RECORD_START requested")
        // 4秒録音タイマー（進捗ゲージ用）。完了で Receiving（チャンク受信待ち）へ遷移。
        viewModelScope.launch {
            val durationMs = 4_000L
            val stepMs = 100L
            var elapsed = 0L
            while (elapsed < durationMs) {
                delay(stepMs)
                elapsed += stepMs
                val s = _state.value
                if (s !is CaptureState.Recording) break
                _state.value = CaptureState.Recording(elapsed.toFloat() / durationMs)
            }
            if (_state.value is CaptureState.Recording) {
                lastChunkTimeMs = System.currentTimeMillis()
                _state.value = CaptureState.Receiving(0, 0)
            }
        }
        _state.value = CaptureState.Recording(0f)
    }

    private fun handleChunk(data: ByteArray) {
        if (data.size < CHUNK_HEADER) return
        val seq = data[2].toInt() and 0xFF
        val total = data[3].toInt() and 0xFF
        val status = data[4].toInt() and 0xFF
        expectedTotal = total
        lastChunkTimeMs = System.currentTimeMillis()
        chunks[seq] = data
        _state.value = CaptureState.Receiving(chunks.size, total)
        Log.d(TAG, "chunk seq=$seq total=$total status=0x${status.toString(16)} (got ${chunks.size}/$total)")
        if (status == STATUS_LAST || seq == total - 1) {
            finalize()
        }
    }

    /** seq 昇順に結合してサンプル列へ換算。欠損チャンクがあれば記録するが届いた分で完了する */
    private fun finalize() {
        if (finalized) return
        finalized = true
        val samples = mutableListOf<ImuSample>()
        val missing = mutableListOf<Int>()
        val total = if (expectedTotal > 0) expectedTotal else chunks.size
        for (seq in 0 until total) {
            val d = chunks[seq]
            if (d == null) {
                missing.add(seq)
                continue
            }
            val n = (d.size - CHUNK_HEADER) / BYTES_PER_SAMPLE
            if (n <= 0) continue
            val bb = ByteBuffer.wrap(d, CHUNK_HEADER, n * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val ax = bb.getShort() / ACC_LSB_PER_G
                val ay = bb.getShort() / ACC_LSB_PER_G
                val az = bb.getShort() / ACC_LSB_PER_G
                val gx = bb.getShort() / GYR_LSB_PER_DPS
                val gy = bb.getShort() / GYR_LSB_PER_DPS
                val gz = bb.getShort() / GYR_LSB_PER_DPS
                samples.add(ImuSample(ax, ay, az, gx, gy, gz))
            }
        }
        lastSamples = samples
        if (samples.isEmpty()) {
            _state.value = CaptureState.Error("データを受信できませんでした")
        } else {
            _state.value = CaptureState.Complete(samples.size, missing, savedFileName = null, saving = true)
            Log.d(TAG, "capture complete: ${samples.size} samples, ${missing.size} chunks missing=$missing")
            // ダウンロードへ自動保存（IO スレッドで実行）
            viewModelScope.launch(Dispatchers.IO) {
                val name = saveToDownloads(pendingLabel, pendingMemo)
                val cur = _state.value
                if (cur is CaptureState.Complete) {
                    _state.value = cur.copy(savedFileName = name, saving = false)
                }
            }
            // 学習データへ自動追加（学習データに追加ボタン廃止に伴う）
            addToTraining()
        }
    }

    /** 状態を Idle へ戻す（再取得用） */
    fun reset() {
        finalized = false
        lastSamples = emptyList()
        chunks.clear()
        savedShareUri = null
        _state.value = CaptureState.Idle
    }

    /** 採取したデータからスライド窓で特徴量を抽出し学習データに追加（ラベル=pendingLabel）。
     *  採取4秒(200) を MotionFeatures.WINDOW_SAMPLES(100) 窓・step 50(50%重複) で分割し、
     *  各窓から1件ずつ学習サンプルを生成。推論時の「直近2秒の様々な局面」を学習に取り込む。 */
    fun addToTraining() {
        val label = pendingLabel
        val samples = lastSamples
        val window = MotionFeatures.WINDOW_SAMPLES
        val step = (window / 2).coerceAtLeast(1)
        viewModelScope.launch {
            if (samples.size < 10) return@launch
            // 窓長に満たない場合は全量で1件（短い採取のフォールバック）
            if (samples.size < window) {
                val feat = MotionFeatures.extract(samples)
                if (feat != null) trainingRepository.addSample(label, feat.toList())
            } else {
                var i = 0
                while (i + window <= samples.size) {
                    val feat = MotionFeatures.extract(samples.subList(i, i + window))
                    if (feat != null) trainingRepository.addSample(label, feat.toList())
                    i += step
                }
            }
        }
        val cur = _state.value
        if (cur is CaptureState.Complete) {
            _state.value = cur.copy(addedToTraining = true)
        }
    }

    /** 採取済みデータから CSV 文字列を生成（Complete 状態で呼ぶ） */
    fun generateCsv(label: String, memo: String): String {
        val samples = lastSamples
        if (samples.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("# BikeClock IMU Capture\n")
        sb.append("# label: ").append(label).append('\n')
        sb.append("# memo: ").append(memo).append('\n')
        sb.append("# firmware: ").append(repository.deviceVersion.value ?: "unknown").append('\n')
        sb.append("# sample_rate_hz: 50\n")
        sb.append("# duration_s: 4\n")
        sb.append("# samples: ").append(samples.size).append('\n')
        sb.append("# axis: ax,ay,az=g  gx,gy,gz=deg/s (BMI160 ±2g/±250dps)\n")
        sb.append("timestamp_ms,ax,ay,az,gx,gy,gz\n")
        samples.forEachIndexed { i, s ->
            sb.append(String.format(Locale.US, "%d,%.5f,%.5f,%.5f,%.4f,%.4f,%.4f\n",
                i * 20, s.ax, s.ay, s.az, s.gx, s.gy, s.gz))
        }
        return sb.toString()
    }

    /** 採取済みデータをダウンロードへ自動保存。保存ファイル名（失敗時 null）を返す */
    private fun saveToDownloads(label: String, memo: String): String? {
        val csv = generateCsv(label, memo)
        if (csv.isEmpty()) return null
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeLabel = label.ifEmpty { "capture" }
        val fileName = "imu_${safeLabel}_$ts.csv"
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

    /** API<29 フォールバック: アプリ固有外部ストレージの Download/imu へ保存 */
    private fun saveViaExternalFiles(fileName: String, bytes: ByteArray) {
        val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "imu").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        savedShareUri = FileProvider.getUriForFile(
            appContext, "com.pirorin215.btclockmob.provider", file
        )
    }
}
