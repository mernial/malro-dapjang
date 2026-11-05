package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageStatsTest {
    @Test
    fun countsAndCosts() {
        val s = UsageStats()
            .add(UsageStats.Kind.REWRITE, Usage(input = 1_000_000, cacheRead = 0, cacheWrite = 0, output = 0))
            .add(UsageStats.Kind.SEARCH, Usage(input = 0, cacheRead = 0, cacheWrite = 0, output = 1_000_000))
            .add(UsageStats.Kind.REWRITE, null)
        assertEquals(2, s.rewrites)
        assertEquals(1, s.searches)
        assertEquals(0.60, s.usd(), 1e-9)
        assertEquals(840.0, s.won(), 1e-6)
    }

    @Test
    fun savesAndLoads() {
        val s = UsageStats(3, 1, 100, 20, 30, 40)
        assertEquals(s, UsageStats.decode(s.encode()))
        assertEquals(UsageStats(), UsageStats.decode(null))
        assertEquals(UsageStats(), UsageStats.decode("망가진 값"))
    }
}
