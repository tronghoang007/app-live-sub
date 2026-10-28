package vn.livesub.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceBoundaryTest {

    @Test
    fun `co dau ket cau thi la cau tron`() {
        assertTrue(SentenceBoundary.isComplete("どこへ行きますか。"))
        assertTrue(SentenceBoundary.isComplete("あの人は誰ですか？"))
        assertTrue(SentenceBoundary.isComplete("Hello there."))
        assertTrue(SentenceBoundary.isComplete("Really?!"))
        assertTrue(SentenceBoundary.isComplete("そうだね…"))
        assertTrue(SentenceBoundary.isComplete("wait..."))
    }

    @Test
    fun `dau phay hoac khong dau thi chua het cau`() {
        assertFalse(SentenceBoundary.isComplete("昨日ね、"))
        assertFalse(SentenceBoundary.isComplete("昨日ね"))
        assertFalse(SentenceBoundary.isComplete("Hello there,"))
        assertFalse(SentenceBoundary.isComplete("score 3.5"))
        assertFalse(SentenceBoundary.isComplete(""))
        assertFalse(SentenceBoundary.isComplete("  "))
    }

    @Test
    fun `dau dong ngoac khong che mat dau ket cau`() {
        assertTrue(SentenceBoundary.isComplete("「行こう。」"))
        assertTrue(SentenceBoundary.isComplete("He said \"go home.\""))
        assertTrue(SentenceBoundary.isComplete("（本当？）"))
        assertFalse(SentenceBoundary.isComplete("「はい」"))
    }

    @Test
    fun `engine co dau cau thi dua vao dau ket cau`() {
        assertTrue(SentenceBoundary.hasEnded("今日はいい天気ですね。", hasPunctuation = true))
        assertFalse(SentenceBoundary.hasEnded("今日はいい天気ですね、", hasPunctuation = true))
        assertFalse(SentenceBoundary.hasEnded("今日は", hasPunctuation = true))
    }

    @Test
    fun `engine khong co dau cau thi khoang lang la het cau`() {
        assertTrue(SentenceBoundary.hasEnded("今日はいい天気ですね", hasPunctuation = false))
        // Các câu thật sự trong log chạy máy (ReazonSpeech): đều là câu trọn.
        listOf("初めまして", "Mです", "紗奈さん", "お国はどちらですか", "会社員です", "会社員ですか", "いいえ", "会社員じゃありません", "学校の学生です", "靴下はどこにありますか", "はい")
            .forEach { assertTrue(it, SentenceBoundary.hasEnded(it, hasPunctuation = false)) }
    }

    @Test
    fun `engine khong co dau cau ma chu dung o tro tu thi chua het cau`() {
        // Hai mảnh bị VAD tách ở chỗ ngập ngừng trong log chạy máy.
        assertFalse(SentenceBoundary.hasEnded("私は", hasPunctuation = false))
        assertFalse(SentenceBoundary.hasEnded("さんも", hasPunctuation = false))
        assertFalse(SentenceBoundary.hasEnded("今日は学校に ", hasPunctuation = false))
        assertFalse(SentenceBoundary.hasEnded("えっと", hasPunctuation = false))
    }

    @Test
    fun `cau chao ket thuc bang tro tu van la cau tron`() {
        assertTrue(SentenceBoundary.hasEnded("こんにちは", hasPunctuation = false))
        assertTrue(SentenceBoundary.hasEnded("皆さんこんばんは", hasPunctuation = false))
        assertTrue(SentenceBoundary.hasEnded("どうも", hasPunctuation = false))
    }

    @Test
    fun `engine co dau cau thi khong xet tro tu`() {
        assertTrue(SentenceBoundary.hasEnded("私は。", hasPunctuation = true))
    }

    @Test
    fun `chi gop khi con vua khung phu de`() {
        assertTrue(SentenceBoundary.canMerge(3f, 4f))
        assertTrue(SentenceBoundary.canMerge(6f, 2f))
        assertFalse(SentenceBoundary.canMerge(6f, 2.1f))
        assertEquals(8f, SentenceBoundary.MAX_SENTENCE_SEC)
    }
}
