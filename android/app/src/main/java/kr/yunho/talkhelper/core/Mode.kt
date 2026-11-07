package kr.yunho.talkhelper.core

/**
 * 답장 말투 모드. 화면에 보이는 이름만 여기 둠.
 * 말투 규칙과 예시(AI 지시문)는 [Prompts] 에 (플레이판에선 서버에만 있고 앱에서는 빠짐)
 */
enum class Mode(val label: String, val emoji: String) {
    POLITE("정중하게", "🙏"),
    FRIENDLY("친구처럼", "😊"),
    BUSINESS("업무 연락", "💼"),
    PARENT("자녀에게", "🤗"),
    MINE("내 말투", "🪞");
}
