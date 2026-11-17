package kr.yunho.talkhelper.core

/** '그대로' 말투: 음성 인식 글을 다듬지 않고 입력칸에 넣을 때 빈칸만 정리 */
object Verbatim {
    fun clean(spoken: String): String =
        spoken.trim().replace(Regex("[ \\t]+"), " ").replace(Regex(" *\\n *"), "\n")
}
