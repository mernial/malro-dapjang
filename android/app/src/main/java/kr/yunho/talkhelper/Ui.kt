package kr.yunho.talkhelper

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 어르신용 큰 글씨 화면을 코드로 만드는 도우미 */
object Ui {
    val YELLOW = Color.parseColor("#FEE500")
    val BROWN = Color.parseColor("#3B1E1E")
    val BG = Color.parseColor("#F7F4EE")
    val CARD = Color.WHITE
    val MUTED = Color.parseColor("#6B625A")
    val LINE = Color.parseColor("#E2D9CC")
    val MINE = Color.parseColor("#FDE68A")
    val THEM = Color.parseColor("#F1ECE4")
    val RED = Color.parseColor("#B4441F")
    val GREEN = Color.parseColor("#2F7A4B")

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun scale(ctx: Context) = Prefs(ctx).textScale

    fun rounded(ctx: Context, color: Int, radiusDp: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(ctx, 2), stroke)
    }

    /** 글 속 이모티콘을 같은 모양의 단순한 선 아이콘으로 바꿈 (앱 전체 아이콘을 하나로 맞춤) */
    private val ICONS: List<Pair<String, Int>> = listOf(
        "🎤" to R.drawable.ic_mic, "🎙" to R.drawable.ic_mic,
        "🔎" to R.drawable.ic_search, "🔍" to R.drawable.ic_search,
        "⚙" to R.drawable.ic_settings, "🚩" to R.drawable.ic_flag, "📤" to R.drawable.ic_upload,
        "✅" to R.drawable.ic_check, "❌" to R.drawable.ic_x, "✕" to R.drawable.ic_x, "💬" to R.drawable.ic_message,
        "🔔" to R.drawable.ic_bell, "👉" to R.drawable.ic_arrow, "📷" to R.drawable.ic_camera,
        "📁" to R.drawable.ic_folder, "⚠" to R.drawable.ic_alert, "✋" to R.drawable.ic_move, "👆" to R.drawable.ic_tap,
        "📍" to R.drawable.ic_pin, "📜" to R.drawable.ic_history, "✨" to R.drawable.ic_sparkle, "🎉" to R.drawable.ic_sparkle,
        "🗑" to R.drawable.ic_trash, "📱" to R.drawable.ic_phone, "🔄" to R.drawable.ic_refresh, "👀" to R.drawable.ic_eye,
        "🔊" to R.drawable.ic_volume, "☰" to R.drawable.ic_menu,
        // 말투
        "🙏" to R.drawable.ic_star, "😊" to R.drawable.ic_smile, "💼" to R.drawable.ic_briefcase,
        "🤗" to R.drawable.ic_heart, "🪞" to R.drawable.ic_user,
    )

    fun iconize(ctx: Context, s: CharSequence, color: Int, sizePx: Int): CharSequence {
        val plain = s.toString()
        if (ICONS.none { plain.contains(it.first) }) return s
        val sb = android.text.SpannableStringBuilder(s)
        for ((emo, res) in ICONS) {
            var i = sb.indexOf(emo)
            while (i >= 0) {
                var end = i + emo.length
                if (end < sb.length && sb[end] == '\uFE0F') end++
                val d = (if (res == R.drawable.ic_mic) micBadge(ctx, sizePx) else ctx.getDrawable(res)?.mutate()?.apply { setTint(color) }) ?: break
                d.setBounds(0, 0, sizePx, sizePx)
                sb.replace(i, end, "\uFFFC")
                val align = if (android.os.Build.VERSION.SDK_INT >= 29) android.text.style.DynamicDrawableSpan.ALIGN_CENTER
                    else android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM
                sb.setSpan(android.text.style.ImageSpan(d, align), i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = sb.indexOf(emo, i + 1)
            }
        }
        return sb
    }

    /** 카톡 위 떠 있는 🎤 버튼과 똑같은 모양의 작은 그림: 짙은 동그라미 + 금색 테두리 + 흰 마이크 */
    fun micBadge(ctx: Context, sizePx: Int): android.graphics.drawable.Drawable {
        val circle = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#3A3A40"), Color.parseColor("#17171A"))).apply {
            shape = GradientDrawable.OVAL
            setStroke(maxOf(1, sizePx / 18), Color.parseColor("#C9A96E"))
        }
        val mic = ctx.getDrawable(R.drawable.ic_bubble_mic)!!.mutate()
        return android.graphics.drawable.LayerDrawable(arrayOf(circle, mic)).apply {
            val inset = (sizePx * 0.22f).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    /** 이 글자 칸의 글자 크기에 맞춰 이모티콘을 아이콘으로 */
    fun iconize(v: TextView) { v.text = iconize(v.context, v.text, v.currentTextColor, (v.textSize * 1.15f).toInt()) }

    fun text(ctx: Context, s: CharSequence, sp: Float = 18f, bold: Boolean = false, color: Int = BROWN) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale(ctx))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.2f)
        iconize(this)
    }

    fun button(ctx: Context, s: CharSequence, bg: Int = YELLOW, fg: Int = BROWN, sp: Float = 20f, onClick: (View) -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale(ctx))
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(ctx, bg, 18, if (bg == CARD) LINE else null)
        minHeight = dp(ctx, 64)
        val p = dp(ctx, 12)
        setPadding(p, p, p, p)
        stateListAnimator = null
        setOnClickListener(onClick)
        iconize(this)
    }

    /** 크고 깔끔한 켜기/끄기 스위치 (켜짐: 갈색 바탕·노란 동그라미, 꺼짐: 회색 바탕·흰 동그라미) */
    fun toggle(ctx: Context, on: Boolean, onChange: (Boolean) -> Unit): View {
        val w = dp(ctx, 68); val h = dp(ctx, 38); val pad = dp(ctx, 4); val knob = h - pad * 2
        var state = on
        val track = android.widget.FrameLayout(ctx).apply {
            background = rounded(ctx, if (on) BROWN else LINE, 19)
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) YELLOW else Color.WHITE) }
            elevation = dp(ctx, 2).toFloat()
            translationX = if (on) (w - knob - pad * 2).toFloat() else 0f
        }
        track.addView(dot, android.widget.FrameLayout.LayoutParams(knob, knob).apply { leftMargin = pad; topMargin = pad })
        track.layoutParams = ViewGroup.LayoutParams(w, h)
        track.minimumWidth = w; track.minimumHeight = h
        track.setOnClickListener {
            state = !state
            (track.background as GradientDrawable).setColor(if (state) BROWN else LINE)
            (dot.background as GradientDrawable).setColor(if (state) YELLOW else Color.WHITE)
            dot.animate().translationX(if (state) (w - knob - pad * 2).toFloat() else 0f).setDuration(150)
                .withEndAction { onChange(state) }.start()
        }
        return track
    }

    /**
     * 보내는 방식 고르기: 한 줄에 알약 버튼들(고른 것은 갈색), 그 아래 고른 방식 설명 한 줄.
     * 누르면 바로 저장하고 [onPick] 을 부름
     */
    fun sendModePicker(ctx: Context, prefs: Prefs, onPick: (SendMode) -> Unit = {}): View {
        val box = vbox(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(ctx, THEM, 18)
            val p = dp(ctx, 4); setPadding(p, p, p, p)
        }
        val desc = text(ctx, "", 14f, color = MUTED).apply { setPadding(dp(ctx, 6), dp(ctx, 8), 0, 0) }
        val pills = mutableListOf<Pair<SendMode, TextView>>()
        fun paint() {
            pills.forEach { (m, t) ->
                val on = m == prefs.sendMode
                t.background = if (on) rounded(ctx, BROWN, 14) else null
                t.setTextColor(if (on) YELLOW else BROWN)
            }
            desc.text = prefs.sendMode.desc
        }
        SendMode.available().forEach { m ->
            val t = text(ctx, m.label, 16f, bold = true).apply {
                gravity = Gravity.CENTER
                val pv = dp(ctx, 10); setPadding(dp(ctx, 4), pv, dp(ctx, 4), pv)
                contentDescription = "${m.label} 보내기"
                setOnClickListener { prefs.sendMode = m; paint(); onPick(m) }
            }
            pills += m to t
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(row)
        box.addView(desc)
        paint()
        return box
    }

    fun input(ctx: Context, hint: String, password: Boolean = false, multiline: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        setTextColor(BROWN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale(ctx))
        background = rounded(ctx, CARD, 12, LINE)
        val p = dp(ctx, 12)
        setPadding(p, p, p, p)
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) { minLines = 2; gravity = Gravity.TOP or Gravity.START }
    }

    fun vbox(ctx: Context, padDp: Int = 0, bg: Int? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
        if (bg != null) background = rounded(ctx, bg, 18)
    }

    fun hbox(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 세로 목록에 위아래 간격을 두고 넣기 */
    fun LinearLayout.add(v: View, topDp: Int = 10, weight: Float = 0f): View {
        val horizontal = orientation == LinearLayout.HORIZONTAL
        val lp = if (horizontal) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (weight == 0f) 1f else weight)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (horizontal) lp.marginStart = if (childCount == 0) 0 else dp(context, 8) else lp.topMargin = dp(context, topDp)
        addView(v, lp)
        return v
    }

    /** 찾은 내용: 결론·진행·결정·근거 제목은 굵게 한 줄, 내용은 그 아래 들여 써서 */
    fun answer(text: String): CharSequence {
        val formatted = kr.yunho.talkhelper.core.AnswerFormat.format(text)
        val sb = android.text.SpannableStringBuilder()
        formatted.lines().forEachIndexed { i, line ->
            if (i > 0) sb.append('\n')
            val start = sb.length
            sb.append(line)
            if (kr.yunho.talkhelper.core.AnswerFormat.isLabelLine(line)) {
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }
}
