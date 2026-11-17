package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VerbatimTest {
    @Test
    fun keepsWordsOnlyTidiesSpaces() {
        assertEquals("알았어 고마워 내일 보자", Verbatim.clean("  알았어  고마워   내일 보자 "))
        assertEquals("첫 줄\n둘째 줄", Verbatim.clean("첫 줄 \n  둘째 줄"))
    }
}
