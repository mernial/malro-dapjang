package kr.yunho.talkhelper.core

/**
 * '알림으로 답장' 예비 기능: 카톡 알림에 담긴 최근 메시지를 답장 다듬기용 대화 맥락 글로 바꿈.
 * (접근성으로 화면을 읽는 대신, 알림에 이미 보이는 글만 씀)
 */
object NotifyContext {
    /** 한 메시지: 보낸 사람(나면 null)과 글 */
    data class Msg(val sender: String?, val text: String)

    /** 맥락 글로 만들 때 쓰는 최근 메시지 수 */
    const val MAX_MESSAGES = 15

    /**
     * 화면 글과 같은 모양("방 이름" 다음 줄부터 "보낸 사람: 글", 내가 보낸 것은 "나: 글").
     * 사진·이모티콘처럼 글이 없는 것은 그대로 두고, 빈 글은 뺌. 너무 길면 최근 것만.
     */
    fun format(title: String?, messages: List<Msg>, max: Int = MAX_MESSAGES): String {
        val lines = messages.filter { it.text.isNotBlank() }.takeLast(max).map { m ->
            val who = m.sender?.takeIf { it.isNotBlank() } ?: "나"
            "$who: ${m.text.trim().replace('\n', ' ')}"
        }
        return (listOfNotNull(title?.takeIf { it.isNotBlank() }) + lines).joinToString("\n")
    }

    /** 우리 알림에 보일 한 줄 미리보기 */
    fun preview(messages: List<Msg>, maxLen: Int = 60): String {
        val last = messages.lastOrNull { it.text.isNotBlank() } ?: return ""
        val s = (last.sender?.let { "$it: " } ?: "") + last.text.trim().replace('\n', ' ')
        return if (s.length > maxLen) s.take(maxLen - 1) + "…" else s
    }
}
