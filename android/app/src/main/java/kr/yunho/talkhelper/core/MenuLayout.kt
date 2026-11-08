package kr.yunho.talkhelper.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 도우미 버튼을 꾹 누르면 펼쳐지는 동그라미들의 자리.
 * 모든 동그라미는 같은 크기, 같은 간격으로 반원 위에 놓임. 화면에 다 안 들어가면
 * 하나만 밀어 넣지 않고(그러면 간격이 어긋남) 반원 전체를 옮기거나 다 같이 작게 함.
 * 안내 띠·아래 설명 알약 같은 가로 띠([avoid], 위~아래 좌표)와 겹치면 반원 전체를 띠 위나 아래로 옮김.
 */
object MenuLayout {
    data class Placement(val size: Float, val centers: List<Pair<Float, Float>>, val clearOfBands: Boolean = true)

    fun place(
        n: Int, cx: Float, cy: Float, w: Float, h: Float,
        maxRadius: Float, maxSize: Float, gap: Float, margin: Float, minSize: Float,
        avoid: List<Pair<Float, Float>> = emptyList(),
    ): Placement {
        val left = cx > w / 2   // 버튼이 오른쪽에 있으면 왼쪽으로 펼침
        val step = if (n > 1) PI / (n - 1) else PI
        var size = maxSize
        var r = maxRadius
        var maxR = maxRadius
        var acy = cy
        var clear = true
        // 띠를 피할 자리가 없으면 반원을 조금씩 줄여 가며 다시 (많아야 네 번)
        for (shrink in 0..4) {
            size = maxSize
            r = maxR
            repeat(3) {
                // 반원 중심(가로): 끝 동그라미가 화면 밖으로 나가지 않게 반원 전체를 안쪽으로
                val acx = cx.coerceIn(margin + size / 2, w - margin - size / 2)
                val far = if (left) acx - margin - size / 2 else w - margin - size / 2 - acx
                r = minOf(maxR, far, (h - 2 * margin - size) / 2)
                // 이웃한 동그라미 중심 사이 거리가 크기+간격보다 작으면 다 같이 줄임
                val chord = (2 * r * sin(step / 2)).toFloat()
                if (chord >= size + gap) return@repeat
                size = (chord - gap).coerceAtLeast(minSize)
            }
            val lo = margin + r + size / 2
            val hi = h - margin - r - size / 2
            acy = if (lo <= hi) cy.coerceIn(lo, hi) else h / 2
            // 반원이 세로로 차지하는 범위가 띠와 겹치는지
            fun clearOf(y: Float) = avoid.none { (bt, bb) -> y + r + size / 2 > bt - margin && y - r - size / 2 < bb + margin }
            clear = clearOf(acy)
            if (!clear) {
                // 띠 바로 위 또는 바로 아래 가운데, 화면 안에 들어가고 다른 띠와도 안 겹치며 버튼에서 가장 가까운 자리
                val options = avoid.flatMap { (bt, bb) -> listOf(bt - margin - r - size / 2, bb + margin + r + size / 2) }
                    .filter { it >= lo && it <= hi && clearOf(it) }
                val best = options.minByOrNull { abs(it - cy) }
                if (best != null) { acy = best; clear = true }
            }
            if (clear || shrink == 4) break
            maxR *= 0.82f
        }
        val acx = cx.coerceIn(margin + size / 2, w - margin - size / 2)
        val centers = (0 until n).map { i ->
            val t = if (n > 1) i.toDouble() / (n - 1) else 0.5
            val a = if (left) PI + PI / 2 - PI * t else -PI / 2 + PI * t   // 위에서 아래로
            (acx + r * cos(a)).toFloat() to (acy + r * sin(a)).toFloat()
        }
        return Placement(min(size, maxSize), centers, clear)
    }
}
