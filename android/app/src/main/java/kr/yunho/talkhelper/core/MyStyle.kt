package kr.yunho.talkhelper.core

/**
 * '내 말투': 내보낸 카톡 대화에서 내가 그 방에서 실제로 보낸 말을 골라
 * AI 에게 "이 사람처럼 써 줘" 예시로 보여 줌. 고르는 일은 휴대폰 안에서만 함.
 */
object MyStyle {
    const val MAX_EXAMPLES = 30

    /** '내 말투'를 처음 고를 때 보여 주는 동의 안내 (구글 플레이 사용자 데이터 정책: 눈에 띄는 고지·동의) */
    const val CONSENT_TEXT = "🪞 '내 말투'는 저장해 둔 카톡 대화에서 내가 보낸 말 가운데 최근 짧은 문장(최대 30개)을 " +
        "말투 예시로 AI(Anthropic Claude)에 함께 보내요. 다른 사람이 보낸 말은 보내지 않고, 고르는 일은 휴대폰 안에서 해요. " +
        "보낸 내용은 저장되지 않아요. 설정에서 언제든 끌 수 있어요."
    private const val MAX_LEN = 120

    // 안드로이드: "2024. 3. 5. 오후 3:12, 김미카엘 : 메시지" / "2024년 3월 5일 오후 3:12, 김미카엘 : 메시지"
    // 아이폰 등: "2024. 3. 5. 15:12, 김미카엘 : 메시지"
    private val MOBILE = Regex("""^\d{4}[.년]\s*\d{1,2}[.월]\s*\d{1,2}[.일]?\s*(?:[오전후AaPpMm]{2}\s*)?\d{1,2}:\d{2}(?:\s*[AaPp][Mm])?\s*,\s*(.+?)\s*:\s(.*)$""")
    // PC: "[김미카엘] [오후 3:12] 메시지"
    private val PC = Regex("""^\[(.+?)]\s*\[(?:오전|오후)?\s*\d{1,2}:\d{2}]\s?(.*)$""")
    private val SKIP = Regex("""^(사진|사진 \d+장|동영상|이모티콘|음성메시지|파일:.*|삭제된 메시지입니다\.?|https?://\S+|\(이모티콘\)|<사진 읽지 않음>|샵검색:.*)$""")

    /** 한 줄 → (보낸 사람, 메시지). 메시지 줄이 아니면 null */
    fun parse(line: String): Pair<String, String>? {
        val l = line.trim()
        MOBILE.find(l)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
        PC.find(l)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
        return null
    }

    /** 대화에 나오는 사람들, 많이 말한 순서 */
    fun senders(chat: String): List<String> =
        chat.lineSequence().mapNotNull { parse(it)?.first }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }

    /** [me] 가 보낸 최근 글 메시지 (사진·이모티콘·링크·아주 긴 글 제외, 중복 제외) */
    fun examples(chat: String, me: String, max: Int = MAX_EXAMPLES): List<String> =
        chat.lines().asReversed().asSequence()
            .mapNotNull { parse(it) }
            .filter { it.first == me }
            .map { it.second }
            .filter { it.length in 2..MAX_LEN && !SKIP.matches(it) }
            .distinct()
            .take(max)
            .toList()
            .reversed()

    /** 카톡 화면 글에서 "나: " 로 표시된 줄 (내보낸 대화가 없을 때) */
    fun fromScreen(screen: String?, max: Int = 15): List<String> =
        screen.orEmpty().lines().filter { it.startsWith("나: ") }.map { it.removePrefix("나: ").trim() }
            .filter { it.length in 2..MAX_LEN && !SKIP.matches(it) }.distinct().takeLast(max)

    /** 내 이름 짐작: 설정의 이름과 맞는 사람, 아니면 저장한 여러 방에 모두 나오는 단 한 사람 */
    /**
     * 여러 방의 '내가 보낸 말'(방마다 최근 것부터)을 번갈아 하나씩 모아 [max]개까지.
     * 한 방의 말투만 몰리지 않게 하고, 같은 문장은 한 번만.
     */
    fun mergeExamples(perRoom: List<List<String>>, max: Int = MAX_EXAMPLES): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (out.size < max && perRoom.any { i < it.size }) {
            for (list in perRoom) if (i < list.size && out.size < max) out.add(list[i])
            i++
        }
        return out.toList()
    }

    fun guessMe(myName: String, sendersByRoom: List<List<String>>): String? {
        val all = sendersByRoom.flatten().toSet()
        if (myName.isNotBlank()) all.firstOrNull { it == myName || it.contains(myName) || myName.contains(it) }?.let { return it }
        if (sendersByRoom.size < 2) return null
        val common = sendersByRoom.map { it.toSet() }.reduce { a, b -> a intersect b }
        return common.singleOrNull()
    }
}
