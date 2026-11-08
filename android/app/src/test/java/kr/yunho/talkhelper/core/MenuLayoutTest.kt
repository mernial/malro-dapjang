package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MenuLayoutTest {
    private fun check(p: MenuLayout.Placement, w: Float, h: Float) {
        val d = p.centers.zipWithNext { a, b -> hypot(a.first - b.first, a.second - b.second) }
        // 이웃 간격이 모두 같음
        d.forEach { assertEquals(d.first(), it, 0.5f) }
        // 서로 겹치지 않음
        assertTrue(d.first() >= p.size)
        // 모두 화면 안
        p.centers.forEach { (x, y) ->
            assertTrue(x - p.size / 2 >= -0.5f && x + p.size / 2 <= w + 0.5f)
            assertTrue(y - p.size / 2 >= -0.5f && y + p.size / 2 <= h + 0.5f)
        }
    }

    @Test
    fun evenAtRightEdgeMiddle() {
        val p = MenuLayout.place(7, 1040f, 1300f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f)
        check(p, 1080f, 2340f)
        assertEquals(234f, p.size, 0.1f)
    }

    @Test
    fun evenNearTopAndBottomAndCenter() {
        for (cy in listOf(40f, 2300f)) check(MenuLayout.place(7, 1040f, cy, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f), 1080f, 2340f)
        check(MenuLayout.place(7, 560f, 1200f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f), 1080f, 2340f)
    }

    @Test
    fun shrinksTogetherOnSmallScreen() {
        val p = MenuLayout.place(7, 700f, 400f, 720f, 900f, 516f, 234f, 24f, 12f, 120f)
        check(p, 720f, 900f)
        assertTrue(p.size < 234f)
    }

    @Test
    fun threeAndFiveItemsAlsoEven() {
        check(MenuLayout.place(3, 1040f, 1300f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f), 1080f, 2340f)
        check(MenuLayout.place(5, 1040f, 1300f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f), 1080f, 2340f)
    }

    private fun overlaps(p: MenuLayout.Placement, band: Pair<Float, Float>) =
        p.centers.any { (_, y) -> y + p.size / 2 > band.first && y - p.size / 2 < band.second }

    @Test
    fun movesWholeFanAboveBottomHint() {
        // 버튼이 아래쪽에 있고, 화면 아래 알약 안내(2140~2240)가 있으면 반원 전체가 그 위로
        val band = 2140f to 2240f
        val p = MenuLayout.place(5, 1040f, 2100f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f, avoid = listOf(band))
        check(p, 1080f, 2340f)
        assertTrue(p.clearOfBands)
        assertFalse(overlaps(p, band))
    }

    @Test
    fun movesBelowGuideBandAtTop() {
        // 위쪽 안내 띠(300~700)와 겹치면 띠 아래로
        val band = 300f to 700f
        val p = MenuLayout.place(3, 1040f, 600f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f, avoid = listOf(band))
        check(p, 1080f, 2340f)
        assertTrue(p.clearOfBands)
        assertFalse(overlaps(p, band))
    }

    @Test
    fun avoidsBothBandsBetweenThem() {
        val bands = listOf(200f to 500f, 2100f to 2300f)
        val p = MenuLayout.place(5, 1040f, 450f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f, avoid = bands)
        check(p, 1080f, 2340f)
        assertTrue(p.clearOfBands)
        bands.forEach { assertFalse(overlaps(p, it)) }
    }

    @Test
    fun saysWhenNoClearSpotExists() {
        // 띠가 화면을 거의 다 차지하면 피할 수 없음 → 알려 주기만 (겹침)
        val p = MenuLayout.place(5, 1040f, 1200f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f, avoid = listOf(100f to 2240f))
        check(p, 1080f, 2340f)
        assertFalse(p.clearOfBands)
    }
}
