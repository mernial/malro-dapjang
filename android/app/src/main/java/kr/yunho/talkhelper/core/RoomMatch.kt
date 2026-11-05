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
    fun byContent(screenLines: List<String>, chats: Map<String, String>, names: List<String> = emptyList(), atLeast: Int? = null): String? {
        val skip = names.map { norm(it) }.filter { it.isNotEmpty() }.toSet()
        val probes = screenLines.map { it.removePrefix("나: ").trim() }
            .filter { it.length >= 6 && COMMON.none { r -> r.containsMatchIn(it) } && norm(it) !in skip }
            .distinct()
        if (probes.isEmpty()) return null
        val need = atLeast ?: maxOf(2, (probes.size + 1) / 2)
        val scored = chats.map { (room, text) -> room to probes.count { text.contains(it) } }
            .filter { it.second >= need }
            .sortedByDescending { it.second }
        // 두 방이 똑같이 맞으면 어느 방인지 모름
        if (scored.size >= 2 && scored[0].second == scored[1].second) return null
        return scored.firstOrNull()?.first
    }

    // ───────── 방 번호(앱이 붙인 고유 번호)로 저장한 대화 ─────────

    /**
     * 저장해 둔 대화 하나. [id] 는 앱이 붙인 고유 번호(카톡 방 이름이 바뀌거나 같은 이름 방이 여럿이어도 안 섞임),
     * [name] 은 보여 줄 이름, [titles] 는 이 대화를 내보낸 카톡 방의 화면 이름들(정규화한 것)
     */
    data class Entry(val id: String, val name: String, val titles: Set<String>)

    /**
     * 지금 카톡 방([title] = 화면 맨 위 이름)에 해당하는 저장 대화 번호.
     *  - 방 이름이 이 대화를 내보낸 방 이름과 같아야 함 (비슷한 이름·다른 방은 고르지 않음)
     *  - 같은 이름 방이 여럿이면 화면 메시지로 가림, 그래도 모르면 null (사용자에게 다시 내보내기를 물음)
     *  - 방 이름을 아예 못 읽었을 때만 화면 메시지로 짐작
     */
    fun lookup(title: String?, entries: List<Entry>, screenLines: List<String>, text: (String) -> String): String? {
        val t = title?.let { norm(it) }?.takeIf { it.isNotEmpty() }
            ?: return byContent(screenLines, entries.associate { it.id to text(it.id) })
        val same = entries.filter { t in it.titles }
        return when (same.size) {
            0 -> null
            1 -> same[0].id
            // 이름이 같은 방끼리는 이미 후보가 좁혀졌으니 화면 메시지 한 줄만 맞아도 가림 (똑같이 맞으면 모름)
            else -> byContent(screenLines, same.associate { it.id to text(it.id) }, listOfNotNull(title), atLeast = 1)
        }
    }

    /** 대화의 첫 메시지 줄 (같은 방을 다시 내보냈는지 알아볼 때 씀. 내보내기는 처음부터 다 들어 있음) */
    fun firstMessage(chat: String): String? =
        chat.lineSequence().map { it.trim() }.firstOrNull { MyStyle.parse(it) != null }

    /**
     * 새로 내보낸 대화를 어느 번호에 저장할지. 같은 방을 다시 내보낸 것이면 그 번호, 아니면 null(새 번호).
     *  - [bindTitle]: 앱에서 내보내기를 시작한 카톡 방 화면 이름 (있으면 그 방과 짝)
     *  - 없으면(카톡에서 직접 공유) 파일 속 방 이름이 같고, 예전 대화의 첫 메시지가 새 대화에도 있어야 같은 방
     */
    fun reuseTarget(bindTitle: String?, headerName: String, newText: String, entries: List<Entry>, text: (String) -> String): String? {
        fun sameHistory(e: Entry) = firstMessage(text(e.id))?.let { newText.contains(it) } ?: false
        val t = bindTitle?.let { norm(it) }?.takeIf { it.isNotEmpty() }
        val cands = if (t != null) entries.filter { t in it.titles } else entries.filter { norm(it.name) == norm(headerName) }
        return when {
            cands.isEmpty() -> null
            t != null && cands.size == 1 -> cands[0].id
            else -> cands.firstOrNull { sameHistory(it) }?.id
        }
    }
}
