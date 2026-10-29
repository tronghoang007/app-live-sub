package vn.livesub.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceBoundaryTest {

    @Test
    fun `tach tran thoai nhieu nguoi noi lien thanh tung cau`() {
        assertEquals(
            listOf("はい。", "そうです。", "じゃあ行こう。", "え？", "待って"),
            SentenceBoundary.split("はい。そうです。じゃあ行こう。え？待って"),
        )
        assertEquals(listOf("「行こう。」", "うん！"), SentenceBoundary.split("「行こう。」うん！"))
        assertEquals(listOf("Really?!", "Yes.", "I told you."), SentenceBoundary.split("Really?! Yes. I told you."))
    }

    @Test
    fun `cau chot bo mat chu tam cua doan dai thi giu ca hai`() {
        // Log trên máy thật: hai câu nói gần như cùng lúc, câu chốt chỉ giữ một.
        assertTrue(SentenceBoundary.lostInFinal("そこに出してください", 4.5f, "つぐなんだろ", 5.5f))
        assertTrue(SentenceBoundary.lostInFinal("今日はダメな日だ", 5.2f, "あいつ", 6.4f))
        // Câu chốt bị lọc hết nhưng chữ tạm có nghĩa.
        assertTrue(SentenceBoundary.lostInFinal("そこに出してください", 4.5f, "", 5.5f))
        // Câu chốt chứa chữ tạm, hoặc chữ tạm là phần đầu câu chốt: không lặp.
        assertFalse(SentenceBoundary.lostInFinal("外に出して", 4.5f, "外に出してください", 5.5f))
        assertFalse(SentenceBoundary.lostInFinal("ほら行くぞ", 4.0f, "ほら行くぞ", 4.4f))
        // Đoạn ngắn, chữ tạm ngắn, hoặc chữ tạm chỉ nghe một phần nhỏ đoạn: chữ tạm hay sai, bỏ qua.
        assertFalse(SentenceBoundary.lostInFinal("こんにちは", 1.0f, "あれ", 1.5f))
        assertFalse(SentenceBoundary.lostInFinal("お母さん", 3.0f, "おかしかった", 3.9f))
        assertFalse(SentenceBoundary.lostInFinal("そこに出してください", 2.0f, "つぐなんだろ", 5.5f))
    }

    @Test
    fun `chu tam ngan di hoac mat thi khong tinh la them chu`() {
        // Log trên máy thật: chữ tạm nhảy "ごめんね" → "ごめ" vì tiếng rên xung quanh.
        assertTrue(SentenceBoundary.addsWords("", "ごめんね"))
        assertFalse(SentenceBoundary.addsWords("ごめんね", "ごめ"))
        assertFalse(SentenceBoundary.addsWords("ごめんね", "ごめんね"))
        assertFalse(SentenceBoundary.addsWords("ごめんね", ""))
        assertTrue(SentenceBoundary.addsWords("ごめんね", "ごめんね本当に"))
        assertTrue(SentenceBoundary.addsWords("はい", "いいよ"))
    }

    @Test
    fun `cau dap ngan dung rieng thi chot luon`() {
        assertTrue(SentenceBoundary.isShortReply("はい", 0.4f))
        assertTrue(SentenceBoundary.isShortReply("うん", 0.3f))
        assertTrue(SentenceBoundary.isShortReply("Yeah", 0.5f))
        // Dừng ở dấu phẩy hoặc trợ từ: người nói còn tiếp.
        assertFalse(SentenceBoundary.isShortReply("あのね、", 0.6f))
        assertFalse(SentenceBoundary.isShortReply("私は", 0.5f))
        // Đoạn dài không phải câu đáp ngắn.
        assertFalse(SentenceBoundary.isShortReply("昨日の夜ね", 1.4f))
        assertFalse(SentenceBoundary.isShortReply("", 0.3f))
    }

    @Test
    fun `khong tach so thap phan, dau ba cham va mau khong co chu`() {
        assertEquals(listOf("score 3.5 points"), SentenceBoundary.split("score 3.5 points"))
        assertEquals(listOf("そうだね…行こうか"), SentenceBoundary.split("そうだね…行こうか"))
        assertEquals(listOf("何？！"), SentenceBoundary.split("何？ ！"))
        assertEquals(emptyList<String>(), SentenceBoundary.split("  "))
    }

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
