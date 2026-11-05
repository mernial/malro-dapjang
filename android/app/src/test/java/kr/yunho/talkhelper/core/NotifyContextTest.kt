package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyContextTest {
    private val msgs = listOf(
        NotifyContext.Msg("김은미 로사", "신부님 내일 회의\n몇 시예요?"),
        NotifyContext.Msg(null, "열 시요"),
        NotifyContext.Msg("김은미 로사", ""),
        NotifyContext.Msg("김은미 로사", "네 알겠습니다"),
    )

    @Test
    fun formatsLikeScreenText() {
        assertEquals(
            "김은미 로사\n김은미 로사: 신부님 내일 회의 몇 시예요?\n나: 열 시요\n김은미 로사: 네 알겠습니다",
            NotifyContext.format("김은미 로사", msgs),
        )
    }

    @Test
    fun keepsOnlyRecentMessages() {
        assertEquals("나: 열 시요\n김은미 로사: 네 알겠습니다", NotifyContext.format(null, msgs, max = 2))
    }

    @Test
    fun previewIsShortLastMessage() {
        assertEquals("김은미 로사: 네 알겠습니다", NotifyContext.preview(msgs))
        assertEquals("김은미 로사: 네 알…", NotifyContext.preview(msgs, maxLen = 12))
        assertEquals("", NotifyContext.preview(emptyList()))
    }
}
