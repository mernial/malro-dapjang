package kr.yunho.talkhelper.core

/**
 * 앱이 AI에게 시키는 일. 지금은 사용자 폰의 API 키로 Claude 를 바로 부르는 [Claude] 가 맡음.
 * 나중에 유료 판매용 중계 서버가 생기면, 이 약속만 지키는 '서버판' 하나를 만들어
 * ClaudeProvider 에서 바꿔 끼우면 됨 (화면 코드는 그대로).
 * 모두 블로킹 함수이므로 반드시 백그라운드 스레드에서 부를 것.
 */
interface Assistant {
    /** 내가 대충 말한 것을 [mode] 말투의 카톡 메시지로 */
    fun rewrite(
        mode: Mode, spoken: String, screenText: String?, myName: String, myRole: String,
        myStyle: List<String> = emptyList(),
    ): String

    /** 카톡 대화에서 [question] 에 대한 답 찾기. [room] 은 보여 줄 방 이름 */
    fun answer(question: String, chat: String, room: String?): String

    /** 마지막 요청의 토큰 사용량 (모르면 null) */
    val lastUsage: Usage?
}

/** 한 번 요청의 토큰 사용량 */
data class Usage(val input: Long, val cacheRead: Long, val cacheWrite: Long, val output: Long) {
    fun describe() = "입력 ${input + cacheRead + cacheWrite}토큰 (캐시에서 읽음 $cacheRead, 캐시에 저장 $cacheWrite) · 출력 ${output}토큰"
}
