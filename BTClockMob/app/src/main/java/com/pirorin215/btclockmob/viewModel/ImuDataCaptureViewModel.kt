package com.pirorin215.btclockmob.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.BleEvent
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val repository: BleRepository
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

    /** 換算済みサンプル（g / deg/s） */
    data class ImuSample(
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float
    )

    /** 採取状態 */
    sealed class CaptureState {
        object Idle : CaptureState()
        object Requesting : CaptureState()
        data class Receiving(val receivedChunks: Int, val totalChunks: Int) : CaptureState()
        data class Complete(val sampleCount: Int, val missingChunks: List<Int>) : CaptureState()
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
    fun requestDump() {
        val conn = repository.connectionState.value
        if (conn !is ConnectionState.Paired && conn !is ConnectionState.Connected) {
            _state.value = CaptureState.Error("デバイスに接続されていません")
            return
        }
        chunks.clear()
        expectedTotal = 0
        finalized = false
        lastSamples = emptyList()
        requestTimeMs = System.currentTimeMillis()
        lastChunkTimeMs = requestTimeMs
        _state.value = CaptureState.Requesting
        val ok = repository.sendCommand("IMU_DUMP")
        if (!ok) {
            _state.value = CaptureState.Error("IMU_DUMP コマンドの送信に失敗しました")
        }
        Log.d(TAG, "IMU_DUMP requested (sendOk=$ok)")
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
            _state.value = CaptureState.Complete(samples.size, missing)
            Log.d(TAG, "capture complete: ${samples.size} samples, ${missing.size} chunks missing=$missing")
        }
    }

    /** 状態を Idle へ戻す（再取得用） */
    fun reset() {
        finalized = false
        lastSamples = emptyList()
        chunks.clear()
        _state.value = CaptureState.Idle
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
        sb.append("# duration_s: 10\n")
        sb.append("# samples: ").append(samples.size).append('\n')
        sb.append("# axis: ax,ay,az=g  gx,gy,gz=deg/s (BMI160 ±2g/±250dps)\n")
        sb.append("timestamp_ms,ax,ay,az,gx,gy,gz\n")
        samples.forEachIndexed { i, s ->
            sb.append(String.format(Locale.US, "%d,%.5f,%.5f,%.5f,%.4f,%.4f,%.4f\n",
                i * 20, s.ax, s.ay, s.az, s.gx, s.gy, s.gz))
        }
        return sb.toString()
    }
}
