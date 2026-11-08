package kr.yunho.talkhelper.core

/**
 * 앱이 AI에게 시키는 일. 플레이판은 중계 서버를 거치는 RelayAssistant, 직접 설치판은 폰의 키로 바로 부르는 [Claude] 가 맡음.
 * ClaudeProvider 에서 바꿔 끼움 (화면 코드는 그대로).
 * 모두 블로킹 함수이므로 반드시 백그라운드 스레드에서 부를 것.
 */
interface Assistant {
    /** 내가 대충 말한 것을 [mode] 말투의 카톡 메시지로 */
    fun rewrite(
        mode: Mode, spoken: String, screenText: String?, myName: String, myRole: String,
        myStyle: List<String> = emptyList(),
    ): String

    /**
     * 카톡 대화에서 [question] 에 대한 답 찾기. [room] 은 보여 줄 방 이름.
     * [manyRooms] 면 [chat] 은 ChatSearch.excerptMany 로 여러 방을 "[대화방: 이름]" 머리글로 묶어 둔 것이고,
     * 답의 첫 줄에 "대화방: 이름"이 옴
     */
    fun answer(question: String, chat: String, room: String?, manyRooms: Boolean = false): String

    /** 마지막 요청의 토큰 사용량 (모르면 null) */
    val lastUsage: Usage?
}

/** 한 번 요청의 토큰 사용량 */
data class Usage(val input: Long, val cacheRead: Long, val cacheWrite: Long, val output: Long) {
    fun describe() = "입력 ${input + cacheRead + cacheWrite}토큰 (캐시에서 읽음 $cacheRead, 캐시에 저장 $cacheWrite) · 출력 ${output}토큰"
}
