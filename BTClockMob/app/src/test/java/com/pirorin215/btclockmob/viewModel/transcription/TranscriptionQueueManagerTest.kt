package com.pirorin215.btclockmob.viewModel.transcription

import com.pirorin215.btclockmob.data.TranscriptionResult
import com.pirorin215.btclockmob.viewModel.LogManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TranscriptionQueueManagerの単体テスト
 *
 * キュー操作と排他制御のテスト
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionQueueManagerTest {

    private lateinit var mockLogManager: LogManager
    private lateinit var queueManager: TranscriptionQueueManager

    @Before
    fun setup() {
        mockLogManager = mockk()
        every { mockLogManager.addDebugLog(any()) } just Runs
        queueManager = TranscriptionQueueManager(mockLogManager)
    }

    @Test
    fun `初期状態ではキューは空である`() {
        // Then
        assertEquals(0, queueManager.getQueueSize())
        assertTrue(queueManager.isQueueEmpty())
    }

    @Test
    fun `addToQueueでキューにアイテムを追加できる`() = runTest {
        // Given
        val result = TranscriptionResult(
            fileName = "test.wav",
            transcription = "",
            transcriptionStatus = "PENDING"
        )

        // When
        queueManager.addToQueue(result)

        // Then
        assertEquals(1, queueManager.getQueueSize())
        assertFalse(queueManager.isQueueEmpty())
        verify(exactly = 1) { mockLogManager.addDebugLog("Queued: test.wav (size: 1)") }
    }

    @Test
    fun `addToQueueで複数アイテムを追加できる`() = runTest {
        // Given
        val result1 = TranscriptionResult(fileName = "file1.wav", transcription = "", transcriptionStatus = "PENDING")
        val result2 = TranscriptionResult(fileName = "file2.wav", transcription = "", transcriptionStatus = "PENDING")
        val result3 = TranscriptionResult(fileName = "file3.wav", transcription = "", transcriptionStatus = "PENDING")

        // When
        queueManager.addToQueue(result1)
        queueManager.addToQueue(result2)
        queueManager.addToQueue(result3)

        // Then
        assertEquals(3, queueManager.getQueueSize())
        verify(exactly = 1) { mockLogManager.addDebugLog("Queued: file1.wav (size: 1)") }
        verify(exactly = 1) { mockLogManager.addDebugLog("Queued: file2.wav (size: 2)") }
        verify(exactly = 1) { mockLogManager.addDebugLog("Queued: file3.wav (size: 3)") }
    }

    @Test
    fun `getNextFromQueueでキューからアイテムを取得できる`() = runTest {
        // Given
        val result = TranscriptionResult(fileName = "test.wav", transcription = "", transcriptionStatus = "PENDING")
        queueManager.addToQueue(result)

        // When
        val retrieved = queueManager.getNextFromQueue()

        // Then
        assertNotNull(retrieved)
        assertEquals("test.wav", retrieved!!.fileName)
        assertEquals(0, queueManager.getQueueSize())
        verify(exactly = 1) { mockLogManager.addDebugLog("Queue: test.wav (0 remaining)") }
    }

    @Test
    fun `getNextFromQueueはFIFO順でアイテムを取得する`() = runTest {
        // Given
        val result1 = TranscriptionResult(fileName = "file1.wav", transcription = "", transcriptionStatus = "PENDING")
        val result2 = TranscriptionResult(fileName = "file2.wav", transcription = "", transcriptionStatus = "PENDING")
        val result3 = TranscriptionResult(fileName = "file3.wav", transcription = "", transcriptionStatus = "PENDING")

        queueManager.addToQueue(result1)
        queueManager.addToQueue(result2)
        queueManager.addToQueue(result3)

        // When
        val item1 = queueManager.getNextFromQueue()
        val item2 = queueManager.getNextFromQueue()
        val item3 = queueManager.getNextFromQueue()

        // Then
        assertEquals("file1.wav", item1?.fileName)
        assertEquals("file2.wav", item2?.fileName)
        assertEquals("file3.wav", item3?.fileName)
        assertEquals(0, queueManager.getQueueSize())
    }

    @Test
    fun `getNextFromQueueは空キューでnullを返す`() = runTest {
        // When
        val retrieved = queueManager.getNextFromQueue()

        // Then
        assertNull(retrieved)
    }

    @Test
    fun `clearQueueでキューをクリアできる`() = runTest {
        // Given
        val result1 = TranscriptionResult(fileName = "file1.wav", transcription = "", transcriptionStatus = "PENDING")
        val result2 = TranscriptionResult(fileName = "file2.wav", transcription = "", transcriptionStatus = "PENDING")
        queueManager.addToQueue(result1)
        queueManager.addToQueue(result2)

        // When
        queueManager.clearQueue()

        // Then
        assertEquals(0, queueManager.getQueueSize())
        assertTrue(queueManager.isQueueEmpty())
        verify(exactly = 1) { mockLogManager.addDebugLog("Queue cleared") }
    }

    @Test
    fun `getQueueSnapshotでキューのコピーを取得できる`() = runTest {
        // Given
        val result1 = TranscriptionResult(fileName = "file1.wav", transcription = "", transcriptionStatus = "PENDING")
        val result2 = TranscriptionResult(fileName = "file2.wav", transcription = "", transcriptionStatus = "PENDING")
        queueManager.addToQueue(result1)
        queueManager.addToQueue(result2)

        // When
        val snapshot = queueManager.getQueueSnapshot()

        // Then
        assertEquals(2, snapshot.size)
        assertEquals("file1.wav", snapshot[0].fileName)
        assertEquals("file2.wav", snapshot[1].fileName)
    }

    @Test
    fun `getQueueSnapshotは変更しても影響を受けない`() = runTest {
        // Given
        val result = TranscriptionResult(fileName = "test.wav", transcription = "", transcriptionStatus = "PENDING")
        queueManager.addToQueue(result)

        // When
        val snapshot = queueManager.getQueueSnapshot()
        queueManager.clearQueue()

        // Then
        assertEquals(1, snapshot.size)
        assertEquals("test.wav", snapshot[0].fileName)
    }

    @Test
    fun `追加して取得するとキューが空になる`() = runTest {
        // Given
        val result = TranscriptionResult(fileName = "test.wav", transcription = "", transcriptionStatus = "PENDING")
        queueManager.addToQueue(result)

        // When
        val retrieved = queueManager.getNextFromQueue()

        // Then
        assertNotNull(retrieved)
        assertTrue(queueManager.isQueueEmpty())
    }

    @Test
    fun `複数回の追加と取得で順序が維持される`() = runTest {
        // Given
        val items = (1..10).map { i ->
            TranscriptionResult(fileName = "file$i.wav", transcription = "", transcriptionStatus = "PENDING")
        }

        // When - 全て追加
        items.forEach { queueManager.addToQueue(it) }
        assertEquals(10, queueManager.getQueueSize())

        // Then - 順番取得
        for (i in 1..10) {
            val retrieved = queueManager.getNextFromQueue()
            assertEquals("file$i.wav", retrieved?.fileName)
            assertEquals(10 - i, queueManager.getQueueSize())
        }

        assertTrue(queueManager.isQueueEmpty())
    }

    @Test
    fun `空のキューでクリアしても問題ない`() = runTest {
        // When
        queueManager.clearQueue()

        // Then
        assertEquals(0, queueManager.getQueueSize())
        verify(atLeast = 0) { mockLogManager.addDebugLog("Queue cleared") }
    }

    @Test
    fun `空のキューでスナップショットを取得すると空のリストが返る`() = runTest {
        // When
        val snapshot = queueManager.getQueueSnapshot()

        // Then
        assertTrue(snapshot.isEmpty())
    }
}
