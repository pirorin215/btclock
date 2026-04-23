package com.pirorin215.btclockmob.service

import org.junit.Test
import org.junit.Assert.*

class GroqSpeechServiceTest {

    @Test
    fun testIsHallucination_Japanese() {
        assertTrue(GroqSpeechService.isHallucination("ご視聴ありがとうございました"))
        assertTrue(GroqSpeechService.isHallucination("ご視聴ありがとうございました。"))
        assertTrue(GroqSpeechService.isHallucination("ご視聴ありがとうございました！"))
        assertTrue(GroqSpeechService.isHallucination("視聴ありがとうございました"))
        assertTrue(GroqSpeechService.isHallucination("チャンネル登録"))
        assertTrue(GroqSpeechService.isHallucination("チャンネル登録よろしくお願いします"))
        assertTrue(GroqSpeechService.isHallucination("高評価お願いします"))
    }

    @Test
    fun testIsHallucination_English() {
        assertTrue(GroqSpeechService.isHallucination("Thanks for watching"))
        assertTrue(GroqSpeechService.isHallucination("Thanks for watching."))
        assertTrue(GroqSpeechService.isHallucination("Thank you for watching!"))
        assertTrue(GroqSpeechService.isHallucination("Please subscribe"))
        assertTrue(GroqSpeechService.isHallucination("Subtitles by some company"))
        assertTrue(GroqSpeechService.isHallucination("Transcription"))
        assertTrue(GroqSpeechService.isHallucination("transcription"))
        assertTrue(GroqSpeechService.isHallucination("Caption"))
        assertTrue(GroqSpeechService.isHallucination("caption"))
    }

    @Test
    fun testIsHallucination_ValidSpeech() {
        assertFalse(GroqSpeechService.isHallucination("今日はいい天気ですね。"))
        assertFalse(GroqSpeechService.isHallucination("明日の会議は10時からです。"))
        assertFalse(GroqSpeechService.isHallucination("これは文字起こしのテストです。"))
    }

    @Test
    fun testIsHallucination_LongText() {
        // Even if it contains the phrase, it might be valid if it's long enough.
        // But our current implementation has a 60 char limit.
        val longText = "本日はお越しいただき誠にありがとうございます。最後にチャンネル登録と高評価をお願いします。それではまた次回。"
        assertFalse(GroqSpeechService.isHallucination(longText))
    }
}
