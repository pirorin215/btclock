package com.pirorin215.btclockmob.viewModel.transcription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TranscriptionStateManagerの単体テスト
 *
 * StateFlowの動作と状態更新ロジックをテストします
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionStateManagerTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var stateManager: TranscriptionStateManager

    @Before
    fun setup() {
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        stateManager = TranscriptionStateManager(testScope)
    }

    @Test
    fun `初期状態はIdleである`() {
        // Then
        assertEquals("Idle", stateManager.transcriptionState.value)
    }

    @Test
    fun `初期状態では結果はnullである`() {
        // Then
        assertNull(stateManager.transcriptionResult.value)
    }

    @Test
    fun `初期状態ではオーディオファイル数は0である`() {
        // Then
        assertEquals(0, stateManager.audioFileCount.value)
    }

    @Test
    fun `updateTranscriptionStateで状態を更新できる`() = runTest {
        // When
        stateManager.updateTranscriptionState("Transcribing test.wav")

        // Then
        assertEquals("Transcribing test.wav", stateManager.transcriptionState.value)
    }

    @Test
    fun `updateTranscriptionResultで結果を更新できる`() = runTest {
        // When
        stateManager.updateTranscriptionResult("テスト結果")

        // Then
        assertEquals("テスト結果", stateManager.transcriptionResult.value)
    }

    @Test
    fun `updateTranscriptionResultでnullを設定できる`() = runTest {
        // Given
        stateManager.updateTranscriptionResult("テスト結果")

        // When
        stateManager.updateTranscriptionResult(null)

        // Then
        assertNull(stateManager.transcriptionResult.value)
    }

    @Test
    fun `updateAudioFileCountでファイル数を更新できる`() = runTest {
        // When
        stateManager.updateAudioFileCount(42)

        // Then
        assertEquals(42, stateManager.audioFileCount.value)
    }

    @Test
    fun `resetStateで状態をリセットできる`() = runTest {
        // Given
        stateManager.updateTranscriptionState("Transcribing")
        stateManager.updateTranscriptionResult("結果")
        stateManager.updateAudioFileCount(10)

        // When
        stateManager.resetState()

        // Then
        assertEquals("Idle", stateManager.transcriptionState.value)
        assertNull(stateManager.transcriptionResult.value)
        // audioFileCountはリセットされないことを確認
        assertEquals(10, stateManager.audioFileCount.value)
    }

    @Test
    fun `StateFlowは複数回更新できる`() = runTest {
        // When
        stateManager.updateTranscriptionState("状態1")
        stateManager.updateTranscriptionState("状態2")
        stateManager.updateTranscriptionState("状態3")

        // Then
        assertEquals("状態3", stateManager.transcriptionState.value)
    }

    @Test
    fun `TranscriptionResultは複数回更新できる`() = runTest {
        // When
        stateManager.updateTranscriptionResult("結果1")
        stateManager.updateTranscriptionResult("結果2")
        stateManager.updateTranscriptionResult("結果3")

        // Then
        assertEquals("結果3", stateManager.transcriptionResult.value)
    }

    @Test
    fun `AudioFileCountは複数回更新できる`() = runTest {
        // When
        stateManager.updateAudioFileCount(10)
        stateManager.updateAudioFileCount(20)
        stateManager.updateAudioFileCount(30)

        // Then
        assertEquals(30, stateManager.audioFileCount.value)
    }
}
