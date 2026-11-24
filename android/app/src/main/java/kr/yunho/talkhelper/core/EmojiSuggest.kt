package kr.yunho.talkhelper.core

/**
 * 낱말을 치면 어울리는 이모티콘을 위 줄에 추천 (루비로사 건의 2026-11-24, 삼성 키보드처럼).
 * 휴대폰 안에서만 봄 — 친 글을 어디에도 보내지 않음.
 * 마지막 낱말(띄어쓰기 뒤)에 아래 말이 들어 있으면 그 이모티콘들. 긴 말이 먼저.
 */
object EmojiSuggest {
    private val TABLE: List<Pair<String, String>> = listOf(
        "사랑" to "❤️ 😍 🥰 💕 😘",
        "좋아" to "👍 😊 😆 ❤️",
        "좋다" to "👍 😊",
        "최고" to "👍 🏆 💯 😎",
        "고마" to "🙏 😊 💕",
        "고맙" to "🙏 😊 💕",
        "감사" to "🙏 😊 💐",
        "축하" to "🎉 🎂 👏 💐",
        "생일" to "🎂 🎉 🎁 🥳",
        "미안" to "😢 🙏 😥",
        "죄송" to "🙏 😢 🙇",
        "슬퍼" to "😢 😭 💧",
        "슬프" to "😢 😭",
        "울어" to "😭 😢",
        "눈물" to "😭 💧",
        "웃겨" to "🤣 😂 😆",
        "웃기" to "🤣 😂",
        "ㅋㅋ" to "🤣 😂 😆",
        "ㅎㅎ" to "😊 😄",
        "ㅠㅠ" to "😭 😢",
        "화나" to "😠 😡 💢",
        "화가" to "😠 😡",
        "짜증" to "😤 😠",
        "피곤" to "😪 😴 🥱",
        "졸려" to "😴 🥱 💤",
        "잘자" to "😴 🌙 💤 ⭐",
        "주무" to "😴 🌙 💤",
        "안녕" to "👋 😊 🙋",
        "반가" to "😊 👋 🤗",
        "보고싶" to "🥺 💕 😢",
        "그래" to "👌 👍 😊",
        "알았" to "👌 👍",
        "알겠" to "👌 👍",
        "오케이" to "👌 👍",
        "응원" to "💪 📣 👍",
        "화이팅" to "💪 🔥 👊",
        "파이팅" to "💪 🔥 👊",
        "힘내" to "💪 🤗 🍀",
        "기도" to "🙏 ✝️ 📿",
        "성당" to "⛪ ✝️ 🙏",
        "미사" to "⛪ 🙏 ✝️",
        "주님" to "✝️ 🙏 ✨",
        "하느님" to "✝️ 🙏 ✨",
        "성모" to "🙏 💙 🌹",
        "묵주" to "📿 🙏",
        "축복" to "✨ 🙏 🕊️",
        "평화" to "🕊️ ☮️ 🙏",
        "아멘" to "🙏 ✝️",
        "성탄" to "🎄 ⭐ 👼",
        "크리스마스" to "🎄 🎅 ⭐",
        "부활" to "✝️ 🐣 🌷",
        "밥" to "🍚 😋 🥄",
        "식사" to "🍚 🍽️ 😋",
        "맛있" to "😋 👍 🍽️",
        "배고" to "😋 🍚",
        "커피" to "☕ 😊",
        "술" to "🍶 🍺 🍷",
        "맥주" to "🍺 🍻",
        "케이크" to "🎂 🍰",
        "빵" to "🍞 🥐",
        "과일" to "🍎 🍓 🍇",
        "꽃" to "🌸 💐 🌷",
        "비가" to "☔ 🌧️",
        "비와" to "☔ 🌧️",
        "눈이" to "❄️ ⛄",
        "날씨" to "☀️ 🌤️",
        "더워" to "🥵 ☀️",
        "덥다" to "🥵 ☀️",
        "추워" to "🥶 ❄️",
        "춥다" to "🥶 ❄️",
        "아파" to "🤒 😷 💊",
        "아프" to "🤒 😷",
        "병원" to "🏥 💊",
        "약" to "💊",
        "건강" to "💪 🍀 😊",
        "조심" to "⚠️ 🙏",
        "축구" to "⚽",
        "여행" to "✈️ 🧳 🏖️",
        "집에" to "🏠",
        "운전" to "🚗",
        "전화" to "📞 ☎️",
        "사진" to "📷 📸",
        "선물" to "🎁 💝",
        "돈" to "💰 💵",
        "시간" to "⏰ ⌛",
        "아침" to "🌅 ☀️",
        "점심" to "🍱 🍚",
        "저녁" to "🌆 🍽️",
        "강아지" to "🐶 🐕",
        "고양이" to "🐱 🐈",
        "아기" to "👶 🍼",
        "엄마" to "👩 💕",
        "아빠" to "👨 💕",
        "할머니" to "👵 💕",
        "할아버지" to "👴 💕",
        "대박" to "😲 👍 🎉",
        "깜짝" to "😲 😮",
        "놀라" to "😲 😮",
        "부끄" to "😳 ☺️",
        "행복" to "😊 🥰 🍀",
        "기뻐" to "😄 🥳",
        "기쁘" to "😄 🥳",
        "웃음" to "😄 😊",
        "멋져" to "😎 👍",
        "멋지" to "😎 👍",
        "예뻐" to "😍 🌸",
        "예쁘" to "😍 🌸",
        "귀여" to "🥰 😍",
        "수고" to "👏 😊 🙏",
        "고생" to "👏 🙏",
        "괜찮" to "👌 😊",
        "love" to "❤️ 😍",
        "thanks" to "🙏 😊",
        "thank" to "🙏 😊",
        "ok" to "👌 👍",
        "good" to "👍 😊",
        "lol" to "😂 🤣",
    ).sortedByDescending { it.first.length }

    private val ONE_TAIL = setOf("", "이", "은", "을", "도", "는", "가", "에", "좀", "이야", "이요", "먹자", "먹어", "먹었어", "먹었어요", "먹으러", "먹었니", "먹어요", "드세요", "챙겨", "사줘")

    /** 커서 앞 글에서 마지막 낱말을 보고 어울리는 이모티콘 (없으면 빈 목록) */
    fun suggest(before: String, max: Int = 5): List<String> {
        val word = before.takeLastWhile { !it.isWhitespace() }
        if (word.isEmpty() || word.length > 20) return emptyList()
        // 이미 이모티콘을 붙였으면 다시 추천하지 않음
        if (word.any { Character.getType(it) == Character.SURROGATE.toInt() || it == '❤' || it == '️' }) return emptyList()
        val w = word.lowercase()
        val out = LinkedHashSet<String>()
        for ((k, list) in TABLE) {
            val hit = when {
                k.all { it in 'a'..'z' } -> w.trimEnd('!', '.', '~', '?') == k || w.startsWith(k) && k.length >= 4
                // 한 글자 말(밥·약·술…)은 '약속'·'수술'처럼 다른 낱말에 들어 있을 때가 많아, 그 글자로 시작하고 뒤가 토씨일 때만
                k.length == 1 -> w.startsWith(k) && w.removePrefix(k).trimEnd('!', '.', '~', '?') in ONE_TAIL
                else -> w.contains(k)
            }
            if (hit) list.split(' ').forEach { out.add(it) }
            if (out.size >= max) break
        }
        return out.take(max)
    }
}
