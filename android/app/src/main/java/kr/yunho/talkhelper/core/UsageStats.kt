package kr.yunho.talkhelper.core

/**
 * 이번 달 사용량 (휴대폰 안에만 저장, 밖으로 보내지 않음).
 * 유료 요금을 정할 때 '한 사람이 실제로 얼마나 쓰는지' 알기 위한 기록.
 */
data class UsageStats(
    val rewrites: Int = 0,
    val searches: Int = 0,
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val output: Long = 0,
) {
    enum class Kind { REWRITE, SEARCH }

    fun add(kind: Kind, u: Usage?): UsageStats = copy(
        rewrites = rewrites + if (kind == Kind.REWRITE) 1 else 0,
        searches = searches + if (kind == Kind.SEARCH) 1 else 0,
        input = input + (u?.input ?: 0),
        cacheRead = cacheRead + (u?.cacheRead ?: 0),
        cacheWrite = cacheWrite + (u?.cacheWrite ?: 0),
        output = output + (u?.output ?: 0),
    )

    /** AI 요금 (달러). Haiku 5.5 정가 기준 추정 */
    fun usd(): Double =
        (input * Price.INPUT + cacheRead * Price.CACHE_READ + cacheWrite * Price.CACHE_WRITE + output * Price.OUTPUT) / 1_000_000.0

    fun won(rate: Double = Price.WON_PER_USD): Double = usd() * rate

    fun describe(): String =
        "답장 ${rewrites}번 · 찾기 ${searches}번 · AI 요금 약 ${"%,d".format(Math.round(won()))}원"

    /** 저장용 한 줄 */
    fun encode() = listOf(rewrites, searches, input, cacheRead, cacheWrite, output).joinToString(",")

    companion object {
        fun decode(s: String?): UsageStats {
            val p = s?.split(',')?.map { it.trim().toLongOrNull() ?: 0L }?.takeIf { it.size == 6 } ?: return UsageStats()
            return UsageStats(p[0].toInt(), p[1].toInt(), p[2], p[3], p[4], p[5])
        }
    }

    /** 1백만 토큰당 달러 (claude-haiku-5-5, 100K 토큰 이하 요청). 캐시 저장 1.25배, 캐시 읽기 0.1배 */
    object Price {
        const val INPUT = 0.10
        const val OUTPUT = 0.50
        const val CACHE_WRITE = 0.125
        const val CACHE_READ = 0.01
        const val WON_PER_USD = 1400.0
    }
}
