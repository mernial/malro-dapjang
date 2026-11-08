package kr.yunho.talkhelper.core

/**
 * 지금 보고 있는 카톡 방(화면 맨 위 이름)과 내보내 저장해 둔 대화를 짝지음.
 * 카톡 화면 이름과 내보낸 파일 속 이름은 띄어쓰기·인원수 표시 등이 조금씩 다를 수 있음.
 *
 * 알아보는 규칙 (위에서부터 차례로, 하나라도 맞으면 그 방):
 *  1. 이름이 같음 — 화면 이름(인원수·'…'·띄어쓰기 뺀 것)이 저장할 때 기억한 이름들 중 하나와 같음
 *  2. 화면에서 끝이 잘린 이름("…")이면 앞부분이 같은 방 (네 글자 이상)
 *  3. 화면에 보이는 '보낸 사람 이름'들이 그 대화에 나오는 사람들 — 단체방은 메시지마다 이름이 붙으므로,
 *     내보낸 뒤에 새 메시지만 보여도 사람 이름으로 알아봄 (두 사람 이상, 가장 많이 맞는 방 하나)
 *  4. 화면에 보이는 메시지 두 줄 이상이 그 대화에 들어 있음 (가장 많이 맞는 방 하나)
 * 그래도 모르면 null → 저장된 모든 대화에서 찾음 (다시 내보내라고 하지 않음)
 */
object RoomMatch {
    /** 화면에서 끝이 잘린 제목인지 ("봉담성당 주일학교 교…") */
    fun isTruncated(title: String?): Boolean = title != null && (title.contains('…') || title.trimEnd().endsWith("..."))

    /** 비교용 이름: 띄어쓰기·끝의 인원수·'님과 카카오톡 대화' 등을 뺌 */
    fun norm(name: String): String = name
        .replace(Regex("[…]|\\.\\.\\.$"), "")            // 화면에서 잘린 제목의 "…"
        .replace(Regex("님과\\s*카카오톡\\s*대화.*$"), "")
        .replace(Regex("카카오톡\\s*대화.*$"), "")
        .replace(Regex("\\s*\\(\\d+\\)$"), "")           // "가족 (4)" → "가족"
        .replace(Regex("\\s+\\d+\\s*명?$"), "")          // "청년부 12", "청년부 12명" → "청년부"
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

    /** 화면 줄 가운데 어느 방에나 있을 법한 줄을 뺀, 비교에 쓸 만한 줄들 */
    fun probes(screenLines: List<String>, names: List<String> = emptyList()): List<String> {
        val skip = names.map { norm(it) }.filter { it.isNotEmpty() }.toSet()
        return screenLines.map { it.removePrefix("나: ").trim() }
            .filter { it.length >= 6 && COMMON.none { r -> r.containsMatchIn(it) } && norm(it) !in skip }
            .distinct()
    }

    /** 화면 줄 가운데 [text] 대화에 들어 있는 줄 수 */
    fun matches(screenLines: List<String>, text: String): Int = probes(screenLines).count { text.contains(it) }

    /**
     * 대화 내용으로 방 알아보기: 지금 카톡 화면에 보이는 메시지들이 저장해 둔 어느 대화에 들어 있는지 셈.
     * 날짜·알림 줄, 방 이름·사람 이름 줄([names])은 어느 방에나 있을 수 있어 빼고,
     * [atLeast] 줄 이상(기본 두 줄: 여섯 글자 넘는 메시지 두 줄이 같은 대화에 있으면 그 방으로 봄) 들어 있는 방 가운데
     * 가장 많이 맞는 방. 두 방이 똑같이 맞으면(같은 공지를 여러 방에 보낸 경우 등) null
     */
    fun byContent(screenLines: List<String>, chats: Map<String, String>, names: List<String> = emptyList(), atLeast: Int = 2): String? {
        val probes = probes(screenLines, names)
        if (probes.isEmpty()) return null
        val need = atLeast
        val scored = chats.map { (room, text) -> room to probes.count { text.contains(it) } }
            .filter { it.second >= need }
            .sortedByDescending { it.second }
        if (scored.size >= 2 && scored[0].second == scored[1].second) return null
        return scored.firstOrNull()?.first
    }

    // ───────── 방 번호(앱이 붙인 고유 번호)로 저장한 대화 ─────────

    /**
     * 저장해 둔 대화 하나. [id] 는 앱이 붙인 고유 번호(카톡 방 이름이 바뀌거나 같은 이름 방이 여럿이어도 안 섞임),
     * [name] 은 보여 줄 이름, [titles] 는 이 대화를 내보낸 카톡 방의 화면 이름들(정규화한 것),
     * [senders] 는 이 대화에서 말한 사람들 (화면의 이름으로 방을 알아볼 때 씀)
     */
    data class Entry(val id: String, val name: String, val titles: Set<String>, val senders: Set<String> = emptySet())

    /**
     * 화면에 보이는 '보낸 사람 이름' 줄로 방 알아보기 (규칙 3).
     * 단체방은 다른 사람 메시지마다 이름이 따로 보이므로, 그 이름 두 개 이상이 어느 대화의 사람들인지 셈.
     * 방 이름 자체([title])는 세지 않음. 가장 많이 맞는 방 하나; 똑같이 맞는 방이 여럿이면 그 방들 모두(내용으로 가림)
     */
    fun bySenders(title: String?, entries: List<Entry>, screenLines: List<String>): List<Entry> {
        val t = title?.let { norm(it) }.orEmpty()
        val onScreen = screenLines.asSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("나: ") && it.length <= 30 }
            .map { norm(it) }.filter { it.isNotEmpty() && it != t }.toSet()
        if (onScreen.isEmpty()) return emptyList()
        val scored = entries.filter { it.senders.isNotEmpty() }
            .map { e -> e to e.senders.map { norm(it) }.count { it in onScreen } }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return emptyList()
        val top = scored[0].second
        return scored.filter { it.second == top }.map { it.first }
    }

    /**
     * 지금 카톡 방([title] = 화면 맨 위 이름)에 해당하는 저장 대화 번호. 위 규칙 1→4 차례로.
     * 같은 이름 방이 여럿이면 화면 메시지로 가림, 그래도 모르면 null
     */
    fun lookup(title: String?, entries: List<Entry>, screenLines: List<String>, text: (String) -> String): String? {
        val t = title?.let { norm(it) }?.takeIf { it.isNotEmpty() }
        fun keys(e: Entry) = e.titles + norm(e.name)
        // 1) 이름이 그대로 같은 방
        var cands = if (t != null) entries.filter { t in keys(it) } else emptyList()
        // 2) 화면에서 제목이 "…"로 잘린 경우만: 앞부분이 같은 방 (네 글자 이상). 잘리지 않은 비슷한 이름은 다른 방으로 봄
        if (cands.isEmpty() && t != null && isTruncated(title) && t.length >= 4) {
            cands = entries.filter { e -> keys(e).any { k -> k.startsWith(t) } }
        }
        // 3) 화면에 보이는 사람 이름들이 들어 있는 방
        if (cands.isEmpty()) cands = bySenders(title, entries, screenLines)
        // 4) 화면 메시지가 들어 있는 방
        if (cands.isEmpty()) return byContent(screenLines, entries.associate { it.id to text(it.id) }, listOfNotNull(title))
        if (cands.size == 1) return cands[0].id
        // 같은 방을 여러 번 내보내 번호가 둘 이상 생긴 경우(첫 메시지가 같음): 가장 긴(최근) 것
        val firsts = cands.map { firstMessage(text(it.id)) }
        if (firsts.all { it != null && it == firsts[0] }) return cands.maxByOrNull { text(it.id).length }?.id
        // 이름이 같은 다른 방들: 화면 메시지 한 줄만 맞아도 가림 (똑같이 맞으면 모름)
        return byContent(screenLines, cands.associate { it.id to text(it.id) }, listOfNotNull(title), atLeast = 1)
    }

    /**
     * 카톡 화면 이름 [screen] 과 내보낸 파일 속 방 이름 [file] 이 같은 방으로 보이는지.
     * 같거나 한쪽이 다른 쪽을 품으면(두 글자 이상) 같은 방. 내보내기를 시작한 방과 실제 내보낸 방이 다르면 묶지 않으려고 씀
     */
    fun sameRoomName(screen: String?, file: String): Boolean {
        val a = screen?.let { norm(it) }.orEmpty(); val b = norm(file)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (minOf(a.length, b.length) >= 2 && (a.contains(b) || b.contains(a)))
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
        val cands = if (t != null) entries.filter { t in it.titles || norm(it.name) == t } else entries.filter { norm(it.name) == norm(headerName) }
        return when {
            cands.isEmpty() -> null
            t != null && cands.size == 1 -> cands[0].id
            else -> cands.firstOrNull { sameHistory(it) }?.id
        }
    }
}
