package com.pirorin215.btclockmob.viewModel.transcription

import com.pirorin215.btclockmob.data.TranscriptionResult
import com.pirorin215.btclockmob.viewModel.LogManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 文字起こしのキュー管理を担当するマネージャークラス
 * pendingQueue, queueMutex, キュー操作を担当
 */
class TranscriptionQueueManager(
    private val logManager: LogManager
) {
    /**
     * In-memory queue for immediate processing (no need to wait for DataStore Flow propagation)
     *
     * USAGE RULES:
     * - queueMutex protects pendingQueue operations
     * - Keep lock time minimal (add/remove operations only)
     * - Independent from BLE operations (no interaction with bleMutex)
     */
    private val pendingQueue = mutableListOf<TranscriptionResult>()
    private val queueMutex = Mutex()

    /**
     * キューのサイズを取得
     */
    fun getQueueSize(): Int {
        return pendingQueue.size
    }

    /**
     * キューが空かどうかを判定
     */
    fun isQueueEmpty(): Boolean {
        return pendingQueue.isEmpty()
    }

    /**
     * キューにアイテムを追加
     */
    suspend fun addToQueue(result: TranscriptionResult) {
        queueMutex.withLock {
            pendingQueue.add(result)
            logManager.addDebugLog("Queued: ${result.fileName} (size: ${pendingQueue.size})")
        }
    }

    /**
     * キューから次のアイテムを取得（キューから削除）
     */
    suspend fun getNextFromQueue(): TranscriptionResult? {
        return queueMutex.withLock {
            if (pendingQueue.isNotEmpty()) {
                val result = pendingQueue.removeAt(0)
                logManager.addDebugLog("Queue: ${result.fileName} (${pendingQueue.size} remaining)")
                result
            } else {
                null
            }
        }
    }

    /**
     * キューから全アイテムをクリア
     */
    suspend fun clearQueue() {
        queueMutex.withLock {
            pendingQueue.clear()
            logManager.addDebugLog("Queue cleared")
        }
    }

    /**
     * キューの内容をコピー取得
     */
    suspend fun getQueueSnapshot(): List<TranscriptionResult> {
        return queueMutex.withLock {
            pendingQueue.toList()
        }
    }
}
