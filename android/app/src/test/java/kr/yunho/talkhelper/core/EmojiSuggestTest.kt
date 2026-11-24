package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSuggestTest {
    @Test fun love() {
        assertEquals("❤️", EmojiSuggest.suggest("사랑").first())
        assertTrue(EmojiSuggest.suggest("엄마 사랑해요").contains("❤️"))
    }
    @Test fun lastWordOnly() {
        assertTrue(EmojiSuggest.suggest("사랑 ").isEmpty())
        assertTrue(EmojiSuggest.suggest("사랑해 오늘").isEmpty())
    }
    @Test fun ok() { assertEquals("👌", EmojiSuggest.suggest("그래").first()) }
    @Test fun noneAfterEmoji() { assertTrue(EmojiSuggest.suggest("사랑해❤️").isEmpty()) }
    @Test fun english() {
        assertTrue(EmojiSuggest.suggest("ok").isNotEmpty())
        assertTrue(EmojiSuggest.suggest("book").isEmpty())   // 'ok' 가 들어 있어도 다른 말이면 안 함
    }
    @Test fun oneLetter() {
        assertTrue(EmojiSuggest.suggest("밥먹자").contains("🍚"))
        assertTrue(EmojiSuggest.suggest("약속").isEmpty())
        assertTrue(EmojiSuggest.suggest("수술").isEmpty())
    }
    @Test fun max() { assertTrue(EmojiSuggest.suggest("사랑해요").size <= 5) }
}
