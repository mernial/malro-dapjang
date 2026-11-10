package kr.yunho.talkhelper

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kr.yunho.talkhelper.Ui.add

/**
 * 첫 화면용 '따뜻한 미니멀' 부품 (흰 카드 · 얇은 선 · 한 가지 아이콘 모양).
 * 색은 [Ui] 의 팔레트를 씀: 배경 BG, 카드 CARD, 글·브랜드 BROWN, 강조 YELLOW(고른 것·중요한 동작만), 선 LINE, 설명 MUTED.
 */
object Kit {
    /** 상태 표시: 사용 가능(초록 체크) · 설정 필요(노란 안내) · 꺼짐(회색) */
    enum class State { OK, NEED, OFF }

    private fun dp(ctx: Context, v: Int) = Ui.dp(ctx, v)

    /** 흰 카드 (모서리 18dp, 얇은 선) */
    fun card(ctx: Context, padDp: Int = 18): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp); setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(Ui.CARD); cornerRadius = dp(ctx, 18).toFloat(); setStroke(dp(ctx, 1), Ui.LINE)
        }
    }

    /** 카드 안 가는 구분선 */
    fun divider(ctx: Context): View = View(ctx).apply { setBackgroundColor(Ui.LINE) }

    fun LinearLayout.addDivider(topDp: Int = 14) {
        addView(divider(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
            topMargin = dp(context, topDp); bottomMargin = dp(context, topDp)
        })
    }

    /** 단색 선 아이콘 */
    fun icon(ctx: Context, res: Int, color: Int = Ui.BROWN, sizeDp: Int = 22): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 섹션 제목 (아이콘 + 17sp) */
    fun section(ctx: Context, res: Int, title: String): View = Ui.hbox(ctx).apply {
        addView(icon(ctx, res, Ui.BROWN, 20))
        addView(Ui.text(ctx, title, 17f, bold = true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 8) })
    }

    /** 상태 점: 초록 체크 / 노란 느낌표 / 회색 빈 동그라미 */
    fun stateBadge(ctx: Context, s: State): View {
        val size = dp(ctx, 26)
        val bg = when (s) { State.OK -> Ui.GREEN; State.NEED -> Ui.YELLOW; State.OFF -> Ui.LINE }
        return android.widget.FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            layoutParams = LinearLayout.LayoutParams(size, size)
            if (s != State.OFF) addView(ImageView(ctx).apply {
                setImageResource(if (s == State.OK) R.drawable.ic_check else R.drawable.ic_alert)
                setColorFilter(if (s == State.OK) Ui.CARD else Ui.BROWN)
            }, android.widget.FrameLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16), Gravity.CENTER))
            contentDescription = when (s) { State.OK -> "사용 가능"; State.NEED -> "설정 필요"; State.OFF -> "꺼짐" }
        }
    }

    /**
     * 상태 한 줄: [아이콘] 제목 / 상태 글(색) / 설명 + 오른쪽 [trailing] (스위치·작은 단추)
     */
    fun statusRow(ctx: Context, res: Int, title: String, value: String, state: State, desc: String?, trailing: View? = null): LinearLayout {
        val row = Ui.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(ctx, 48) }
        row.addView(icon(ctx, res, Ui.BROWN, 24))
        val col = Ui.vbox(ctx)
        col.addView(Ui.text(ctx, title, 16f, bold = true))
        col.add(Ui.hbox(ctx).apply {
            addView(stateBadge(ctx, state).apply { layoutParams = LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)) })
            addView(Ui.text(ctx, value, 14f, bold = true, color = when (state) {
                State.OK -> Ui.GREEN; State.NEED -> Ui.BROWN; State.OFF -> Ui.MUTED
            }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 6) })
        }, 4)
        if (!desc.isNullOrBlank()) col.add(Ui.text(ctx, desc, 13f, color = Ui.MUTED), 4)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, 14) })
        if (trailing != null) row.addView(trailing, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 10) })
        return row
    }

    /** 작은 테두리 단추 (보조 동작: '키보드 변경' 등), 높이 48dp */
    fun smallButton(ctx: Context, label: String, onClick: () -> Unit): TextView = Ui.text(ctx, label, 14f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(ctx, 48)
        val ph = dp(ctx, 14); setPadding(ph, 0, ph, 0)
        background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = dp(ctx, 14).toFloat(); setStroke(dp(ctx, 1), Ui.LINE) }
        setOnClickListener { onClick() }
    }

    /** 주요 동작 단추 (노랑, 높이 56dp) */
    fun primaryButton(ctx: Context, label: String, onClick: () -> Unit): TextView = Ui.text(ctx, label, 17f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(ctx, 56)
        background = GradientDrawable().apply { setColor(Ui.YELLOW); cornerRadius = dp(ctx, 16).toFloat() }
        setOnClickListener { onClick() }
    }

    /** 누르면 다른 화면으로 가는 한 줄: [아이콘] 제목 / 설명  › */
    fun navRow(ctx: Context, res: Int, title: String, desc: String?, onClick: () -> Unit): LinearLayout {
        val row = Ui.hbox(ctx).apply { minimumHeight = dp(ctx, 52); isClickable = true; setOnClickListener { onClick() } }
        row.addView(icon(ctx, res, Ui.BROWN, 22))
        val col = Ui.vbox(ctx)
        col.addView(Ui.text(ctx, title, 16f, bold = true))
        if (!desc.isNullOrBlank()) col.add(Ui.text(ctx, desc, 13f, color = Ui.MUTED), 3)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, 14) })
        row.addView(icon(ctx, R.drawable.ic_chevron, Ui.MUTED, 20))
        return row
    }

    /** 스위치 한 줄: [아이콘] 제목 / 설명  [스위치] */
    fun toggleRow(ctx: Context, res: Int, title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = Ui.hbox(ctx).apply { minimumHeight = dp(ctx, 52) }
        row.addView(icon(ctx, res, Ui.BROWN, 22))
        val col = Ui.vbox(ctx)
        col.addView(Ui.text(ctx, title, 16f, bold = true))
        col.add(Ui.text(ctx, desc, 13f, color = Ui.MUTED), 3)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, 14) })
        val t = Ui.toggle(ctx, on, onChange).apply { contentDescription = title }
        row.addView(t)
        row.setOnClickListener { t.performClick() }
        return row
    }

    /** 고르기 칩: 고른 것만 노랑 바탕, 나머지는 흰 바탕 + 얇은 선 */
    fun paintChip(ctx: Context, v: TextView, selected: Boolean) {
        v.background = GradientDrawable().apply {
            setColor(if (selected) Ui.YELLOW else Ui.CARD)
            cornerRadius = dp(ctx, 14).toFloat()
            setStroke(dp(ctx, 1), if (selected) Ui.YELLOW else Ui.LINE)
        }
        v.setTextColor(Ui.BROWN)
        v.typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        Ui.iconize(v)
    }

    fun chip(ctx: Context, label: String): TextView = Ui.text(ctx, label, 15f).apply {
        gravity = Gravity.CENTER
        minHeight = dp(ctx, 48)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * Prefs(ctx).textScale)
        val ph = dp(ctx, 6); setPadding(ph, 0, ph, 0)
        maxLines = 1
    }
}
