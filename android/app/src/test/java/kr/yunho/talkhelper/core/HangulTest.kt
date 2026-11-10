package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HangulTest {
    /** 두벌식으로 자모를 차례로 넣고 화면에 보일 전체 글 */
    private fun dubeol(keys: String): String {
        val h = Hangul()
        val out = StringBuilder()
        for (k in keys) {
            if (k in "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ") h.vowel(k) else h.consonant(k)
            out.append(h.takeCommitted())
        }
        return out.toString() + h.composing
    }

    @Test
    fun dubeolBasic() {
        assertEquals("안녕하세요", dubeol("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ"))
        assertEquals("가바", dubeol("ㄱㅏㅂㅏ"))           // 받침이 다음 글자로
        assertEquals("값", dubeol("ㄱㅏㅂㅅ"))            // 겹받침
        assertEquals("갑사", dubeol("ㄱㅏㅂㅅㅏ"))         // 겹받침 뒤 모음
        assertEquals("과자", dubeol("ㄱㅗㅏㅈㅏ"))         // 겹모음
        assertEquals("의사", dubeol("ㅇㅡㅣㅅㅏ"))
        assertEquals("ㅋㅋ", dubeol("ㅋㅋ"))
        assertEquals("떡", dubeol("ㄸㅓㄱ"))
        assertEquals("아ㄸ", dubeol("ㅇㅏㄸ"))             // ㄸ 은 받침이 안 됨
    }

    @Test
    fun backspaceRemovesOneJamo() {
        val h = Hangul()
        "ㄱㅗㅏㄴ".forEach { if (it in "ㅗㅏ") h.vowel(it) else h.consonant(it) }
        assertEquals("관", h.composing)
        h.backspace(); assertEquals("과", h.composing)
        h.backspace(); assertEquals("고", h.composing)
        h.backspace(); assertEquals("ㄱ", h.composing)
        h.backspace(); assertEquals("", h.composing)
        assertFalse(h.backspace())
    }

    /** 천지인: 'I','D','E' 는 모음, 그 밖은 자음 (같은 자음 거듭은 [cycle] 로) */
    private fun cji(vararg keys: String): String {
        val h = Hangul()
        val out = StringBuilder()
        for (k in keys) {
            when {
                k.length == 1 && k[0] in "IDE" -> h.cjiVowel(k[0])
                k.startsWith(">") -> h.cycleConsonant(k[1], k[2])
                else -> h.consonant(k[0])
            }
            out.append(h.takeCommitted())
        }
        return out.toString() + h.composing
    }

    @Test
    fun cheonjiinVowels() {
        assertEquals("가", cji("ㄱ", "I", "D"))
        assertEquals("거", cji("ㄱ", "D", "I"))
        assertEquals("고", cji("ㄱ", "D", "E"))
        assertEquals("구", cji("ㄱ", "E", "D"))
        assertEquals("갸", cji("ㄱ", "I", "D", "D"))
        assertEquals("겨", cji("ㄱ", "D", "D", "I"))
        assertEquals("개", cji("ㄱ", "I", "D", "I"))
        assertEquals("게", cji("ㄱ", "D", "I", "I"))
        assertEquals("과", cji("ㄱ", "D", "E", "I", "D"))
        assertEquals("귀", cji("ㄱ", "E", "D", "I"))
        assertEquals("궈", cji("ㄱ", "E", "D", "D", "I"))
        assertEquals("의", cji("ㅇ", "E", "I"))
    }

    @Test
    fun cheonjiinWords() {
        // 한글: ㅎ ㅣㆍ ㄴ ㄱ ㅡ ㄹ(ㄴ→ㄹ 거듭)
        assertEquals("한글", cji("ㅎ", "I", "D", "ㄴ", "ㄱ", "E", "ㄴ", ">ㄴㄹ"))
        // 받침 뒤 모음: 각 + ㅓ → 가거
        assertEquals("가거", cji("ㄱ", "I", "D", "ㄱ", "D", "I"))
        // 거듭 누르기: ㄱ→ㅋ (첫소리), ㅅ→ㅎ (받침)
        assertEquals("카", cji("ㄱ", ">ㄱㅋ", "I", "D"))
        assertEquals("앟", cji("ㅇ", "I", "D", "ㅅ", ">ㅅㅎ"))
        assertEquals("감사", cji("ㄱ", "I", "D", "ㅇ", ">ㅇㅁ", "ㅅ", "I", "D"))
    }
}
