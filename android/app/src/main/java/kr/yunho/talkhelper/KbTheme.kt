package kr.yunho.talkhelper

import android.graphics.Color

/** 말로 답장 키보드 색 (첫 화면 '키보드 색'에서 고름, 신부님 요청 2026-11-12) */
data class KbTheme(
    val id: String,
    val label: String,
    val bg: Int,       // 키보드 바탕
    val key: Int,      // 글자 키
    val special: Int,  // ⇧·⌫·기호 같은 기능 키
    val edge: Int,     // 키 테두리
    val text: Int,     // 키 글자
    val muted: Int,    // 흐린 글자
) {
    companion object {
        private fun c(s: String) = Color.parseColor(s)
        val ALL = listOf(
            KbTheme("warm", "베이지", c("#E9E4DC"), c("#FFFFFF"), c("#D6CFC4"), c("#CDBFAF"), c("#432725"), c("#77706A")),
            KbTheme("gray", "밝은 회색", c("#E4E6EA"), c("#FFFFFF"), c("#C9CDD3"), c("#BCC0C6"), c("#202124"), c("#5F6368")),
            KbTheme("dark", "어두운색", c("#1F2023"), c("#3A3C40"), c("#2B2D30"), c("#141517"), c("#F1F3F4"), c("#A6ABB1")),
            KbTheme("sky", "하늘색", c("#DCE7F3"), c("#FFFFFF"), c("#BBD0E8"), c("#AABFD8"), c("#1E3450"), c("#5A6E86")),
            KbTheme("pink", "연분홍", c("#F5E3E7"), c("#FFFFFF"), c("#E8C5CE"), c("#DBB3BE"), c("#4A2630"), c("#86646E")),
        )
        fun of(id: String?): KbTheme = ALL.firstOrNull { it.id == id } ?: ALL[0]
    }
}
