package kr.yunho.talkhelper.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 도우미 버튼을 꾹 누르면 펼쳐지는 동그라미들의 자리.
 *  - 버튼 둘레에 바짝 붙여 오밀조밀하게 (버튼은 가리지 않음, 동그라미끼리 [gap] 만큼만 띄움)
 *  - 화면 가장자리나 피할 칸([avoid]: 안내 띠·설명 알약 등)에 걸리면, 버튼 둘레를 돌며 걸리지 않는 방향을 찾고
 *    그래도 안 되면 조금씩 넓혀 감 (화면 가운데 쪽 방향을 먼저)
 *  - 끝내 자리가 없으면 다 같이 작게, 그래도 없으면 가장 덜 겹치는 자리 ([Placement.clearOfBands] = false)
 */
object MenuLayout {
    data class Placement(val size: Float, val centers: List<Pair<Float, Float>>, val clearOfBands: Boolean = true)

    /** 피할 네모 칸 (화면 좌표) */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * [n] 개를 버튼 중심([cx],[cy], 반지름 [bubbleR]) 둘레에 놓음. 화면 [w]×[h], 가장자리 여백 [margin].
     * 동그라미 지름 [maxSize] (자리가 없으면 [minSize] 까지 줄임), 동그라미 사이·버튼과의 틈 [gap]
     */
    fun place(
        n: Int, cx: Float, cy: Float, w: Float, h: Float,
        bubbleR: Float, maxSize: Float, gap: Float, margin: Float, minSize: Float,
        avoid: List<Box> = emptyList(),
    ): Placement {
        var size = maxSize
        while (true) {
            tryPlace(n, cx, cy, w, h, bubbleR, size, gap, margin, avoid, strict = true)?.let { return Placement(size, it, true) }
            if (size <= minSize) break
            size = max(minSize, size * 0.88f)
        }
        // 피할 칸을 다 피할 수는 없음: 화면 안에만 들게, 가장 덜 겹치는 자리
        val loose = tryPlace(n, cx, cy, w, h, bubbleR, minSize, gap, margin, avoid, strict = false)
            ?: fallbackColumn(n, w, h, minSize, gap, margin)
        return Placement(minSize, loose, false)
    }

    private fun tryPlace(
        n: Int, cx: Float, cy: Float, w: Float, h: Float,
        bubbleR: Float, size: Float, gap: Float, margin: Float, avoid: List<Box>, strict: Boolean,
    ): List<Pair<Float, Float>>? {
        val half = size / 2
        // 버튼에 바짝: 버튼 테두리에서 gap 만큼 떨어진 원 위
        val rMin = bubbleR + gap + half
        // 화면 가운데 쪽 방향 (먼저 시도)
        val toCenter = atan2((h / 2 - cy).toDouble(), (w / 2 - cx).toDouble())
        var r = rMin
        val rMax = max(w, h).toFloat()
        var best: List<Pair<Float, Float>>? = null
        var bestBad = Int.MAX_VALUE
        while (r <= rMax) {
            // 이웃 동그라미 중심 사이가 지름+gap 이 되는 각도
            val ratio = ((size + gap) / (2 * r)).toDouble()
            if (ratio < 1.0) {
                val step = 2 * asin(ratio)
                if (step * (n - 1) <= 2 * PI - step) {   // 한 바퀴 안에 들어감 (겹치지 않게)
                    // 가운데 방향에서 시작해 양옆으로 5도씩 돌려 보며 찾음
                    for (k in 0..72) {
                        val turn = (if (k % 2 == 0) 1 else -1) * ((k + 1) / 2) * (PI / 36)
                        val mid = toCenter + turn
                        val pts = (0 until n).map { i ->
                            val a = mid + (i - (n - 1) / 2.0) * step
                            (cx + r * cos(a)).toFloat() to (cy + r * sin(a)).toFloat()
                        }
                        // 화면 위쪽부터 차례로 (순서가 늘 위→아래)
                        val ordered = pts.sortedBy { it.second }
                        val inScreen = ordered.all { (x, y) -> x - half >= margin && x + half <= w - margin && y - half >= margin && y + half <= h - margin }
                        if (!inScreen) continue
                        val bad = ordered.count { (x, y) -> avoid.any { b -> x + half > b.left - gap && x - half < b.right + gap && y + half > b.top - gap && y - half < b.bottom + gap } }
                        if (bad == 0) return ordered
                        if (!strict && bad < bestBad) { bestBad = bad; best = ordered }
                    }
                }
            }
            r += max(4f, size * 0.12f)
        }
        return if (strict) null else best
    }

    /** 어떤 원 위에도 못 놓을 때(아주 작은 화면): 화면 가운데 세로 한 줄 */
    private fun fallbackColumn(n: Int, w: Float, h: Float, size: Float, gap: Float, margin: Float): List<Pair<Float, Float>> {
        val total = n * size + (n - 1) * gap
        val top = max(margin, (h - total) / 2)
        return (0 until n).map { i -> w / 2 to min(h - margin - size / 2, top + size / 2 + i * (size + gap)) }
    }

    /** 두 점 사이 거리 (시험용) */
    fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
}
