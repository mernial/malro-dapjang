package kr.yunho.talkhelper.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuLayoutTest {
    // 휴대폰 화면 (px): 1080×2340, 버튼 지름 186(62dp), 동그라미 234(78dp), 틈 24, 여백 24
    private val w = 1080f; private val h = 2340f
    private val bubbleR = 93f; private val size = 234f; private val gap = 24f; private val margin = 24f

    private fun check(p: MenuLayout.Placement, cx: Float, cy: Float, avoid: List<MenuLayout.Box> = emptyList(), ww: Float = w, hh: Float = h) {
        val half = p.size / 2
        // 서로 겹치지 않음
        for (i in p.centers.indices) for (j in i + 1 until p.centers.size)
            assertTrue("동그라미끼리 겹침", MenuLayout.dist(p.centers[i], p.centers[j]) >= p.size + gap - 1f)
        // 버튼을 가리지 않음
        p.centers.forEach { assertTrue("버튼을 가림", MenuLayout.dist(it, cx to cy) >= bubbleR + half + gap - 1f) }
        // 화면 안
        p.centers.forEach { (x, y) -> assertTrue(x - half >= margin - 0.5f && x + half <= ww - margin + 0.5f && y - half >= margin - 0.5f && y + half <= hh - margin + 0.5f) }
        // 피할 칸과 안 겹침
        if (p.clearOfBands) p.centers.forEach { (x, y) ->
            avoid.forEach { b -> assertFalse("안내 칸과 겹침", x + half > b.left && x - half < b.right && y + half > b.top && y - half < b.bottom) }
        }
    }

    /** 버튼에서 가장 먼 동그라미까지 (오밀조밀한지) */
    private fun reach(p: MenuLayout.Placement, cx: Float, cy: Float) = p.centers.maxOf { MenuLayout.dist(it, cx to cy) }

    @Test
    fun hugsTheButtonInTheMiddle() {
        val p = MenuLayout.place(3, 900f, 1300f, w, h, bubbleR, size, gap, margin, 160f)
        check(p, 900f, 1300f)
        assertTrue(p.size == size)
        // 버튼 테두리 + 동그라미 하나 정도 거리 안에 모두
        assertTrue(reach(p, 900f, 1300f) <= bubbleR + gap + size / 2 + 60f)
    }

    @Test
    fun rightEdgeBottomButtonDoesNotGetCovered() {
        // 사진처럼 버튼이 오른쪽 아래, 그 아래 설명 알약이 있을 때
        val pill = MenuLayout.Box(240f, 2140f, 840f, 2240f)
        for (n in listOf(3, 5)) {
            val p = MenuLayout.place(n, 990f, 2080f, w, h, bubbleR, size, gap, margin, 160f, listOf(pill))
            check(p, 990f, 2080f, listOf(pill))
            assertTrue(p.clearOfBands)
        }
    }

    @Test
    fun avoidsGuideBandAtTop() {
        val band = MenuLayout.Box(0f, 300f, w, 700f)
        val p = MenuLayout.place(5, 990f, 600f, w, h, bubbleR, size, gap, margin, 160f, listOf(band))
        check(p, 990f, 600f, listOf(band))
        assertTrue(p.clearOfBands)
    }

    @Test
    fun cornersWork() {
        for ((cx, cy) in listOf(100f to 100f, 980f to 100f, 100f to 2240f, 980f to 2240f)) {
            val p = MenuLayout.place(5, cx, cy, w, h, bubbleR, size, gap, margin, 160f)
            check(p, cx, cy)
        }
    }

    @Test
    fun shrinksOnSmallScreen() {
        val p = MenuLayout.place(5, 650f, 450f, 720f, 900f, bubbleR, size, gap, margin, 120f)
        check(p, 650f, 450f, ww = 720f, hh = 900f)
    }

    @Test
    fun saysWhenNoClearSpotExists() {
        val p = MenuLayout.place(5, 990f, 1200f, w, h, bubbleR, size, gap, margin, 160f, listOf(MenuLayout.Box(0f, 0f, w, 1080f), MenuLayout.Box(0f, 1320f, w, h)))
        assertFalse(p.clearOfBands)
    }
}
