package kr.yunho.talkhelper.core

/**
 * 지금 보고 있는 카톡 방(화면 맨 위 이름)과 내보내 저장해 둔 대화를 짝지음.
 * 카톡 화면 이름과 내보낸 파일 속 이름은 띄어쓰기·인원수 표시 등이 조금씩 다를 수 있음.
 */
object RoomMatch {
    /** 비교용 이름: 띄어쓰기·끝의 인원수·'님과 카카오톡 대화' 등을 뺌 */
    fun norm(name: String): String = name
        .replace(Regex("님과\\s*카카오톡\\s*대화.*$"), "")
        .replace(Regex("카카오톡\\s*대화.*$"), "")
        .replace(Regex("\\s+\\d+$"), "")      // "청년부 12" → "청년부"
        .replace(Regex("[\\s_]+"), "")
        .lowercase()

    /**
     * [title] 방에 해당하는 저장된 방 이름. [aliases] 는 (정규화한 화면 이름 → 저장된 방) 기억.
     * 못 찾으면 null
     */
    fun find(title: String?, saved: List<String>, aliases: Map<String, String>): String? {
        val t = title?.let { norm(it) }?.takeIf { it.isNotEmpty() } ?: return null
        aliases[t]?.takeIf { it in saved }?.let { return it }
        saved.firstOrNull { norm(it) == t }?.let { return it }
        return saved.firstOrNull { val r = norm(it); r.length >= 2 && (r.contains(t) || t.contains(r)) }
    }

    /** 어느 방에나 나오는 줄: 날짜 구분선, 시각, 들어옴·나감 같은 알림, 사진·이모티콘 자리 */
    private val COMMON = listOf(
        Regex("^(오전|오후)?\\s*\\d{1,2}:\\d{2}$"),
        Regex("\\d{4}\\s*년\\s*\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일"),
        Regex("^\\d{4}\\.\\s*\\d{1,2}\\.\\s*\\d{1,2}\\.?"),
        Regex("(들어왔습니다|나갔습니다|초대했습니다|내보냈습니다|삭제된 메시지|메시지를 가렸습니다)"),
        Regex("^(사진|동영상|이모티콘|파일|음성메시지)(\\s*\\d+장)?$"),
    )

    /**
     * 대화 내용으로 방 알아보기: 지금 카톡 화면에 보이는 메시지들이 저장해 둔 어느 대화에 들어 있는지 셈.
     * 방 이름을 못 읽었을 때만 씀. 날짜·알림 줄, 방 이름·사람 이름 줄([names])은 어느 방에나 있을 수 있어 빼고,
     * 화면 메시지의 절반 이상이 들어 있는 방만 (두 줄 이상) 고름. 애매하면 null → 사용자에게 물어봄
     */
    fun byContent(screenLines: List<String>, chats: Map<String, String>, names: List<String> = emptyList()): String? {
        val skip = names.map { norm(it) }.filter { it.isNotEmpty() }.toSet()
        val probes = screenLines.map { it.removePrefix("나: ").trim() }
            .filter { it.length >= 6 && COMMON.none { r -> r.containsMatchIn(it) } && norm(it) !in skip }
            .distinct()
        if (probes.isEmpty()) return null
        val need = maxOf(2, (probes.size + 1) / 2)
        val scored = chats.map { (room, text) -> room to probes.count { text.contains(it) } }
            .filter { it.second >= need }
            .sortedByDescending { it.second }
        // 두 방이 똑같이 맞으면 어느 방인지 모름
        if (scored.size >= 2 && scored[0].second == scored[1].second) return null
        return scored.firstOrNull()?.first
    }
}
