package kr.yunho.talkhelper.core

/**
 * 한글 글자 조합 (키보드용). 두벌식 자판과 천지인 자판의 누름을 받아
 * 지금 조합 중인 글자([composing])와 다 만들어진 글자([takeCommitted])를 내 줌.
 *
 *  - 두벌식: [consonant] (자음), [vowel] (모음). ㅗ+ㅏ=ㅘ 처럼 겹모음, ㄱ+ㅅ=ㄳ 처럼 겹받침도 만듦.
 *    받침 뒤에 모음이 오면 받침을 다음 글자 첫소리로 넘김 (갑+ㅏ → 가바).
 *  - 천지인: [cjiVowel] 에 'I'(ㅣ)·'D'(ㆍ)·'E'(ㅡ) 를 차례로 넣으면 모음이 됨 (ㅣㆍ=ㅏ, ㆍㅣ=ㅓ …).
 *    자음 키를 거듭 누르면 [cycleConsonant] 로 방금 넣은 자음을 바꿈 (ㄱ→ㅋ→ㄲ).
 *  - [backspace] 는 자모를 하나씩 지움.
 */
class Hangul {
    private data class S(val cho: Char? = null, val jung: Char? = null, val jong: Char? = null, val strokes: String = "")

    private var cur = S()
    private val committed = StringBuilder()

    /**
     * 받침이 있는 글자 뒤에 겹받침이 안 되는 자음을 넣으면 그 글자를 바로 확정하지 않고 잠깐 붙잡아 둠.
     * 천지인에서 같은 키를 거듭 눌러 자음이 바뀌면(ㅅ→ㅎ) 그때 겹받침으로 합침 (만+ㅅ+ㅅ → 많, 루비로사 2026-11-10)
     */
    private var hold: S? = null

    /** 지금 조합 중인 글자 (없으면 빈 글) */
    val composing: String get() = (hold?.let { render(it) } ?: "") + render(cur)
    val isComposing: Boolean get() = hold != null || cur != S()

    private fun flushHold() { hold?.let { committed.append(render(it)) }; hold = null }

    /** 다 만들어져 확정된 글자들 (가져가면 비워짐) */
    fun takeCommitted(): String = committed.toString().also { committed.clear() }

    /** 조합을 그만둠 (지금 글자는 화면에 이미 있으므로 버림) */
    fun clear() { cur = S(); hold = null; committed.clear() }

    private fun newSyllable(s: S) { flushHold(); committed.append(render(cur)); cur = s }

    /** 지금 글자([s])를 붙잡아 두고 [c] 로 새 글자를 시작 */
    private fun holdAndStart(s: S, c: Char) { flushHold(); hold = s; cur = S(cho = c) }

    fun consonant(c: Char) {
        flushHold()
        val s = cur
        when {
            s.cho == null && s.jung == null -> cur = S(cho = c)
            s.jung == null -> newSyllable(S(cho = c))                     // 자음만 있었음
            isPending(s.jung) || s.cho == null -> newSyllable(S(cho = c)) // 모음만·천지인 ㆍ 기다리는 중
            s.jong == null -> if (c in JONG_OK) cur = s.copy(jong = c, strokes = "") else newSyllable(S(cho = c))
            else -> {
                val comb = JONG_COMB["${s.jong}$c"]
                if (comb != null) cur = s.copy(jong = comb) else holdAndStart(s, c)
            }
        }
    }

    fun vowel(v: Char) = vowelInternal(v, "")

    private fun vowelInternal(v: Char, strokes: String) {
        flushHold()
        val s = cur
        when {
            s.jung == null -> cur = s.copy(jung = v, strokes = strokes)
            s.jong == null -> {
                val comb = if (strokes.isEmpty()) JUNG_COMB["${s.jung}$v"] else null
                if (comb != null) cur = s.copy(jung = comb) else newSyllable(S(jung = v, strokes = strokes))
            }
            else -> {
                // 받침을 다음 글자 첫소리로 (겹받침이면 뒤쪽만)
                val split = JONG_SPLIT[s.jong]
                val keep = split?.first
                val move = split?.second ?: s.jong
                cur = s.copy(jong = keep)
                newSyllable(S(cho = move, jung = v, strokes = strokes))
            }
        }
    }

    /** 천지인 모음 키: 'I' = ㅣ, 'D' = ㆍ, 'E' = ㅡ */
    fun cjiVowel(k: Char) {
        flushHold()
        val s = cur
        if (s.jung != null && s.jong == null && s.strokes.isNotEmpty()) {
            val ns = s.strokes + k
            val v = CJI[ns]
            if (v != null) { cur = s.copy(jung = v, strokes = ns); return }
        }
        val v1 = CJI["$k"] ?: return
        if (s.jung != null && s.jong == null && !isPending(v1)) {
            // 두벌식으로 넣은 모음 뒤 등: 이어서 겹모음이 되면 잇고, 아니면 새 글자
            val comb = JUNG_COMB["${s.jung}$v1"]
            if (comb != null && s.strokes.isEmpty()) { cur = s.copy(jung = comb); return }
        }
        if (s.jung != null && s.jong == null) { newSyllable(S(jung = v1, strokes = "$k")); return }
        vowelInternal(v1, "$k")
    }

    /**
     * 천지인: 같은 자음 키를 거듭 눌렀을 때 방금 넣은 자음 [from] 을 [to] 로 바꿈.
     * 바꿀 수 없으면 false (그때는 새 자음으로 넣으면 됨)
     */
    fun cycleConsonant(from: Char, to: Char): Boolean {
        val s = cur
        when {
            s.jong == from -> {
                if (to in JONG_OK) cur = s.copy(jong = to)
                else holdAndStart(s.copy(jong = null), to)
                return true
            }
            s.jong != null && JONG_SPLIT[s.jong]?.second == from -> {
                val first = JONG_SPLIT[s.jong]!!.first
                val comb = JONG_COMB["$first$to"]
                if (comb != null) cur = s.copy(jong = comb)
                else holdAndStart(s.copy(jong = first), to)
                return true
            }
            s.cho == from && s.jung == null -> {
                // 붙잡아 둔 앞 글자에 받침(겹받침)으로 들어갈 수 있으면 합침
                val h = hold
                if (h != null) {
                    val merged = when {
                        h.jong == null -> if (to in JONG_OK) h.copy(jong = to) else null
                        else -> JONG_COMB["${h.jong}$to"]?.let { h.copy(jong = it) }
                    }
                    if (merged != null) { hold = null; cur = merged; return true }
                }
                cur = s.copy(cho = to); return true
            }
        }
        return false
    }

    /** 자모 하나 지우기. 조합 중인 글자가 없었으면 false (그때는 앞 글자를 지우면 됨) */
    fun backspace(): Boolean {
        val s = cur
        if (!isComposing) return false
        val h = hold
        if (h != null && cur.jung == null && cur.jong == null) { cur = h; hold = null; return true }
        if (h != null && cur == S()) { cur = h; hold = null }
        cur = when {
            s.jong != null -> s.copy(jong = JONG_SPLIT[s.jong]?.first)
            s.jung != null && s.strokes.length > 1 -> {
                val ns = s.strokes.dropLast(1)
                s.copy(jung = CJI[ns], strokes = ns)
            }
            s.jung != null && JUNG_SPLIT[s.jung] != null && s.strokes.isEmpty() -> s.copy(jung = JUNG_SPLIT[s.jung])
            s.jung != null -> s.copy(jung = null, strokes = "")
            else -> S()
        }
        return true
    }

    companion object {
        private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
        /** 받침이 될 수 없는 자음 */
        private val JONG_OK = CHO.toSet() - setOf('ㄸ', 'ㅃ', 'ㅉ')
        private const val PENDING1 = 'ㆍ'
        private const val PENDING2 = 'ᆢ'
        private fun isPending(c: Char?) = c == PENDING1 || c == PENDING2

        private val JONG_COMB = mapOf(
            "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ',
            "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ',
        )
        private val JONG_SPLIT: Map<Char, Pair<Char, Char>> = JONG_COMB.entries.associate { (k, v) -> v to (k[0] to k[1]) }
        private val JUNG_COMB = mapOf(
            "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ',
        )
        private val JUNG_SPLIT: Map<Char, Char> = JUNG_COMB.entries.associate { (k, v) -> v to k[0] }

        /** 천지인 모음: I=ㅣ D=ㆍ E=ㅡ */
        private val CJI = mapOf(
            "I" to 'ㅣ', "D" to PENDING1, "E" to 'ㅡ', "DD" to PENDING2,
            "ID" to 'ㅏ', "IDD" to 'ㅑ', "DI" to 'ㅓ', "DDI" to 'ㅕ',
            "DE" to 'ㅗ', "DDE" to 'ㅛ', "ED" to 'ㅜ', "EDD" to 'ㅠ',
            "IDI" to 'ㅐ', "IDDI" to 'ㅒ', "DII" to 'ㅔ', "DDII" to 'ㅖ',
            "DEID" to 'ㅘ', "DEIDI" to 'ㅙ', "DEI" to 'ㅚ',
            "EDDI" to 'ㅝ', "EDDII" to 'ㅞ', "EDI" to 'ㅟ', "EI" to 'ㅢ',
        )

        private fun render(s: S): String {
            val cho = s.cho; val jung = s.jung; val jong = s.jong
            if (cho == null && jung == null) return ""
            if (jung == null) return "$cho"
            if (isPending(jung)) return (cho?.toString() ?: "") + jung
            if (cho == null) return "$jung" + (jong?.toString() ?: "")
            val ci = CHO.indexOf(cho); val ji = JUNG.indexOf(jung); val ki = if (jong == null) 0 else JONG.indexOf(jong)
            if (ci < 0 || ji < 0 || ki < 0) return "$cho$jung${jong ?: ""}"
            return (0xAC00 + (ci * 21 + ji) * 28 + ki).toChar().toString()
        }
    }
}
