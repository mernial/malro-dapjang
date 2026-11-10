package kr.yunho.talkhelper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.yunho.talkhelper.Ui.add
import kr.yunho.talkhelper.core.ChatSearch
import kr.yunho.talkhelper.core.EndSignal
import kr.yunho.talkhelper.core.Hangul
import kr.yunho.talkhelper.core.HelperException
import kr.yunho.talkhelper.core.Mode

/**
 * '말로 답장 키보드' (안드로이드 표준 입력 방법).
 *  - 평소처럼 글을 치는 한글 자판: 두벌식 / 천지인 (단축키 줄의 ⇄ 로 바꿈), 영어·숫자 자판
 *  - 맨 위 작은 단축키 줄: 🎤 말로 답장 · 🔎 찾기 · 말투 · 자판 바꾸기
 *  - 🎤: 말하면 AI 가 고른 말투로 다듬어 **입력칸에 글자로 넣음**. 보내기는 사용자가 직접
 *  - 🔎: 카톡 '대화 내보내기'로 저장해 둔 대화에서 찾아 키보드 안에 답을 보여 줌
 * 다른 앱의 화면을 읽거나 버튼을 누르지 않음. 자판으로 친 글은 어디에도 보내지 않음.
 * 폴드처럼 넓은 화면(600dp 이상)에서는 두벌식을 양손 자판(가운데 비움)으로, 천지인은 가운데에 알맞은 폭으로.
 * 폴드를 펴고 접으면 안드로이드가 자판을 새로 만들게 하므로 그때 크기를 다시 맞춤.
 */
class KeyboardService : InputMethodService() {

    private lateinit var prefs: Prefs
    private var mode = Mode.POLITE
    private val hangul = Hangul()

    // 화면 조각
    private lateinit var toneButton: TextView
    private lateinit var infoRow: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var content: FrameLayout

    /** 자판 종류: 한글(두벌식/천지인) · 영어 · 숫자·기호 */
    private enum class Layer { KO, EN, SYM }
    private var layer = Layer.KO
    private var shift = false

    // 천지인 자음·문장부호 거듭 누르기
    private var lastCjiKey: String? = null
    private var lastCjiAt = 0L
    private var lastCjiChar: Char? = null
    private var punctIndex = 0

    // 말로 답장·찾기
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var searchMode = false
    private val heard = StringBuilder()
    private var partial = ""
    private var lastSpoken = ""
    /** 방금 입력칸에 넣은 글 (말투를 바꾸면 이것을 지우고 새로 넣음) */
    private var inserted: String? = null
    private val cache = HashMap<Mode, String>()
    private var job = 0

    private val pulse = object : Runnable {
        override fun run() {
            if (!listening) return
            Sounds.buzz(this@KeyboardService, 35, 120)
            Bg.main.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Sounds.init(this)
    }

    // ───────── 크기 (폴드·일반 폰) ─────────

    private fun dp(v: Int) = Ui.dp(this, v)
    private val screenWdp get() = resources.configuration.screenWidthDp
    private val screenHdp get() = resources.configuration.screenHeightDp
    /** 넓은 화면 (폴드 펼침·태블릿·가로) */
    private val wide get() = screenWdp >= 600
    /** 키 높이: 화면 높이에 맞춰 (너무 작거나 크지 않게) */
    private val keyH get() = dp((screenHdp * 0.062f).toInt().coerceIn(42, 56))
    private val keyTextSp get() = if (screenHdp < 640) 18f else 21f

    // ───────── 화면 만들기 ─────────

    override fun onCreateInputView(): View {
        val ctx = this
        mode = prefs.mode
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KB_BG)
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        // 1) 단축키 줄
        val toolbar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun tool(label: String, weight: Float, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Ui.BROWN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Ui.rounded(ctx, Ui.CARD, 12, Ui.LINE)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
            Ui.iconize(this)
        }.also { toolbar.addView(it, LinearLayout.LayoutParams(0, dp(42), weight).apply { marginEnd = dp(4) }) }
        tool("🎤 말로 답장", 1.6f) { startVoice(search = false) }.apply {
            setTextColor(Ui.YELLOW); background = Ui.rounded(ctx, Ui.BROWN, 12); text = "🎤 말로 답장"; Ui.iconize(this)
        }
        tool("🔎 찾기", 1f) { startVoice(search = true) }
        toneButton = tool("", 1.3f) { toggleToneRow() }
        tool(if (prefs.keyboardLayout == "cji") "⇄ 두벌식" else "⇄ 천지인", 1.1f) { switchLayout() }
        r.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 2) 알림 줄 (넣었어요·말투 고르기·오류)
        infoRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        infoText = TextView(ctx).apply {
            setTextColor(Ui.BROWN); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(6), dp(6), dp(6), dp(2))
        }
        infoRow.addView(infoText)
        r.addView(infoRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        // 3) 자판 / 듣기 / 찾은 답
        content = FrameLayout(ctx)
        r.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // 안드로이드 15+ 는 키보드도 내비게이션 막대 밑까지 그림 → 막대 높이만큼 띄움
        val base = r.paddingBottom
        r.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30)
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + bottom)
            insets
        }
        paintTone()
        showKeys()
        return r
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        hangul.clear()
        lastCjiKey = null
        if (!restarting) { inserted = null; cache.clear(); lastSpoken = ""; hideInfo() }
        mode = prefs.mode
        if (::toneButton.isInitialized) paintTone()
        // 숫자만 받는 칸이면 숫자 자판, 아니면 한글 자판부터
        val cls = (info?.inputType ?: 0) and EditorInfo.TYPE_MASK_CLASS
        val want = if (cls == EditorInfo.TYPE_CLASS_NUMBER || cls == EditorInfo.TYPE_CLASS_PHONE) Layer.SYM else if (layer == Layer.SYM) Layer.KO else layer
        if (want != layer || !restarting) { layer = want; shift = false; if (::content.isInitialized && !listening) showKeys() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRecognizer()
        hangul.clear()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopRecognizer()
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }

    /** 사용자가 손가락으로 커서를 옮기면 조합을 끝냄 */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (hangul.isComposing && candidatesEnd != -1 && newSelEnd != candidatesEnd) {
            hangul.clear()
            currentInputConnection?.finishComposingText()
        }
    }

    // ───────── 자판 ─────────

    private fun showKeys() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val keys = when (layer) {
            Layer.KO -> if (prefs.keyboardLayout == "cji") cheonjiin() else qwerty(KO_ROWS, KO_SHIFT)
            Layer.EN -> qwerty(EN_ROWS, null)
            Layer.SYM -> symbols()
        }
        content.addView(keys)
    }

    /** 키 하나 */
    @SuppressLint("ClickableViewAccessibility")
    private fun key(label: String, special: Boolean = false, repeat: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(Ui.BROWN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 2) keyTextSp - 5 else keyTextSp)
        background = Ui.rounded(this@KeyboardService, if (special) KEY_SPECIAL else Ui.CARD, 8)
        elevation = dp(1).toFloat()
        Ui.iconize(this)
        if (repeat) {
            // 지우기: 꾹 누르고 있으면 계속 지움
            val rep = object : Runnable { override fun run() { onClick(); Bg.main.postDelayed(this, 70) } }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; Sounds.buzz(this@KeyboardService, 8, 40); onClick(); Bg.main.postDelayed(rep, 450) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; Bg.main.removeCallbacks(rep) }
                }
                true
            }
        } else setOnClickListener { Sounds.buzz(this@KeyboardService, 8, 40); onClick() }
    }

    private fun keyLp(w: Float) = LinearLayout.LayoutParams(0, keyH, w).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
    private fun gap(w: Float): Pair<View, Float> = View(this) to w

    private fun row(items: List<Pair<View, Float>>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEach { (v, w) -> addView(v, keyLp(w)) }
    }

    /** 넓은 화면: 키 줄을 왼쪽·오른쪽 반으로 나눠 가운데를 비움 (폴드에서 양손 엄지로) */
    private fun splitRow(left: List<Pair<View, Float>>, right: List<Pair<View, Float>>, half: Float): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val lw = left.sumOf { it.second.toDouble() }.toFloat()
        val rw = right.sumOf { it.second.toDouble() }.toFloat()
        left.forEach { (v, w) -> addView(v, keyLp(w)) }
        if (lw < half) addView(View(context), keyLp(half - lw))
        addView(View(context), keyLp(half * 0.5f))   // 가운데 빈 곳
        if (rw < half) addView(View(context), keyLp(half - rw))
        right.forEach { (v, w) -> addView(v, keyLp(w)) }
    }

    private fun column(rows: List<View>, maxWidthDp: Int? = null): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        if (maxWidthDp == null || screenWdp <= maxWidthDp + 16) return col
        // 넓은 화면에선 가운데에 알맞은 폭으로
        return FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(dp(maxWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun toKorean() { layer = Layer.KO; shift = false; showKeys() }
    private fun toEnglish() { finishComposing(); layer = Layer.EN; shift = false; showKeys() }
    private fun toSymbols() { finishComposing(); layer = Layer.SYM; shift = false; showKeys() }

    /** 두벌식(한글 쿼티)·영어 쿼티 */
    private fun qwerty(rows: List<String>, shifted: Map<Char, Char>?): View {
        val ko = shifted != null
        fun letter(c: Char): Pair<View, Float> {
            val shown = if (!shift) c else if (ko) shifted!![c] ?: c else c.uppercaseChar()
            return key("$shown") { typeChar(shown, ko) } to 1f
        }
        val r1 = rows[0].map { letter(it) }
        val r2 = rows[1].map { letter(it) }
        val r3 = rows[2].map { letter(it) }
        val shiftKey = key(if (shift) "⬆" else "⇧", special = true) { shift = !shift; showKeys() } to 1.5f
        val back = key("⌫", special = true, repeat = true) { backspace() } to 1.5f
        val bottom = listOf(
            key("!#1", special = true) { toSymbols() } to 1.3f,
            key(if (ko) "영" else "한", special = true) { if (ko) toEnglish() else toKorean() } to 1.1f,
            key("⌨", special = true) { otherKeyboard() } to 1f,
            key(",", special = true) { commitPlain(",") } to 0.9f,
            key("띄어쓰기") { commitPlain(" ") } to 3.6f,
            key(".", special = true) { commitPlain(".") } to 0.9f,
            key("↵", special = true) { enter() } to 1.6f,
        )
        if (wide) {
            // 폴드 펼침: 양손 자판 (글자 키는 반씩 나누고, 맨 아래 줄은 가운데 넓게)
            val h1 = r1.size / 2; val h2 = (r2.size + 1) / 2; val h3 = r3.size / 2
            val half = 5.5f
            return column(listOf(
                splitRow(r1.take(h1), r1.drop(h1), half),
                splitRow(listOf(gap(0.5f)) + r2.take(h2), r2.drop(h2), half),
                splitRow(listOf(shiftKey) + r3.take(h3), r3.drop(h3) + listOf(back), half),
                row(bottom),
            ))
        }
        return column(listOf(
            row(r1),
            row(listOf(gap(0.5f)) + r2 + listOf(gap(0.5f))),
            row(listOf(shiftKey) + r3 + listOf(back)),
            row(bottom),
        ))
    }

    /** 천지인: ㅣ ㆍ ㅡ 와 자음 묶음 키 (같은 키를 거듭 누르면 ㄱ→ㅋ→ㄲ 처럼 바뀜) */
    private fun cheonjiin(): View {
        fun v(label: String, k: Char) = key(label) { lastCjiKey = null; hangul.cjiVowel(k); render() } to 1f
        fun c(label: String, cycle: String) = key(label) { cjiConsonant(label, cycle) } to 1f
        val rows = listOf(
            row(listOf(v("ㅣ", 'I'), v("ㆍ", 'D'), v("ㅡ", 'E'), key("⌫", special = true, repeat = true) { backspace() } to 1f)),
            row(listOf(c("ㄱㅋ", "ㄱㅋㄲ"), c("ㄴㄹ", "ㄴㄹ"), c("ㄷㅌ", "ㄷㅌㄸ"), key("↵", special = true) { enter() } to 1f)),
            row(listOf(c("ㅂㅍ", "ㅂㅍㅃ"), c("ㅅㅎ", "ㅅㅎㅆ"), c("ㅈㅊ", "ㅈㅊㅉ"), key(".,?!", special = true) { cyclePunct() } to 1f)),
            row(listOf(
                key("!#1", special = true) { toSymbols() } to 0.75f,
                key("영", special = true) { toEnglish() } to 0.6f,
                key("⌨", special = true) { otherKeyboard() } to 0.65f,
                c("ㅇㅁ", "ㅇㅁ"),
                key("띄어쓰기") { commitPlain(" ") } to 1f,
            )),
        )
        return column(rows, maxWidthDp = 460)
    }

    private fun symbols(): View {
        fun line(s: String) = row(s.map { ch -> key("$ch") { commitPlain("$ch") } to 1f })
        val bottom = row(listOf(
            key("가", special = true) { toKorean() } to 1.4f,
            key("%", special = true) { commitPlain("%") } to 0.9f,
            key("#", special = true) { commitPlain("#") } to 0.9f,
            key("띄어쓰기") { commitPlain(" ") } to 3f,
            key("⌫", special = true, repeat = true) { backspace() } to 1.4f,
            key("↵", special = true) { enter() } to 1.4f,
        ))
        return column(listOf(line("1234567890"), line("-/:;()₩&@\""), line(".,?!'~^*+="), bottom), maxWidthDp = if (wide) 640 else null)
    }

    // ───────── 글자 넣기 ─────────

    /** 조합 중인 글자를 입력칸에 반영 (한 번에 묶어 보내 커서 알림이 뒤섞이지 않게) */
    private fun render() {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val done = hangul.takeCommitted()
        if (done.isNotEmpty()) ic.commitText(done, 1)
        ic.setComposingText(hangul.composing, 1)
        ic.endBatchEdit()
        inserted = null
    }

    private fun typeChar(c: Char, korean: Boolean) {
        when {
            korean && c in VOWELS -> { hangul.vowel(c); render() }
            korean && c in CONSONANTS -> { hangul.consonant(c); render() }
            else -> commitPlain("$c")
        }
        if (shift) { shift = false; showKeys() }
    }

    private fun cjiConsonant(label: String, cycle: String) {
        val now = System.currentTimeMillis()
        val last = lastCjiChar
        if (lastCjiKey == label && now - lastCjiAt < 1200 && last != null) {
            val next = cycle[(cycle.indexOf(last) + 1) % cycle.length]
            if (hangul.cycleConsonant(last, next)) { lastCjiChar = next; lastCjiAt = now; render(); return }
        }
        hangul.consonant(cycle[0])
        lastCjiKey = label; lastCjiAt = now; lastCjiChar = cycle[0]
        render()
    }

    private fun cyclePunct() {
        val ic = currentInputConnection ?: return
        val marks = ".,?!"
        val now = System.currentTimeMillis()
        if (lastCjiKey == "punct" && now - lastCjiAt < 1200) {
            ic.deleteSurroundingText(1, 0)
            punctIndex = (punctIndex + 1) % marks.length
        } else { finishComposing(); punctIndex = 0 }
        ic.commitText("${marks[punctIndex]}", 1)
        lastCjiKey = "punct"; lastCjiAt = now
        inserted = null
    }

    private fun finishComposing() {
        if (hangul.isComposing) { hangul.clear(); currentInputConnection?.finishComposingText() }
    }

    private fun commitPlain(s: String) {
        finishComposing()
        currentInputConnection?.commitText(s, 1)
        lastCjiKey = null
        inserted = null
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        lastCjiKey = null
        inserted = null
        if (hangul.backspace()) { ic.setComposingText(hangul.composing, 1); return }
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }

    private fun enter() {
        finishComposing()
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        lastCjiKey = null
        inserted = null
    }

    private fun otherKeyboard() {
        stopRecognizer()
        finishComposing()
        // 바로 전에 쓰던 키보드로 (안 되면 키보드 고르기 창)
        val switched = runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
        if (!switched) (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
    }

    private fun switchLayout() {
        finishComposing()
        prefs.keyboardLayout = if (prefs.keyboardLayout == "cji") "dubeol" else "cji"
        layer = Layer.KO
        shift = false
        setInputView(onCreateInputView())
    }

    // ───────── 말투 ─────────

    private fun paintTone() {
        toneButton.text = "${mode.emoji} ${mode.label} ▾"
        Ui.iconize(toneButton)
    }

    /** 말투 고르기 줄 (단축키 줄 아래에 펼침, 다시 누르면 닫힘) */
    private fun toggleToneRow() {
        if (infoRow.visibility == View.VISIBLE && infoRow.tag == "tones") { hideInfo(); return }
        showInfo(if (inserted != null) "말투를 누르면 방금 넣은 글이 그 말투로 바뀌어요" else "🎤로 말할 때 쓸 말투를 고르세요", tones = true)
    }

    private fun toneChips(): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in Mode.values()) {
            val on = m == mode
            line.addView(TextView(this).apply {
                text = "${m.emoji} ${m.label}"
                setTextColor(if (on) Ui.YELLOW else Ui.BROWN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = Ui.rounded(this@KeyboardService, if (on) Ui.BROWN else Ui.CARD, 16, if (on) Ui.BROWN else Ui.LINE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { switchTone(m) }
                Ui.iconize(this)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        scroll.addView(line)
        return scroll
    }

    private fun showInfo(text: String, tones: Boolean = false, color: Int = Ui.BROWN) {
        infoText.text = text
        infoText.setTextColor(color)
        Ui.iconize(infoText)
        while (infoRow.childCount > 1) infoRow.removeViewAt(1)
        if (tones) infoRow.addView(toneChips(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        infoRow.tag = if (tones) "tones" else null
        infoRow.visibility = View.VISIBLE
    }

    private fun hideInfo() { if (::infoRow.isInitialized) infoRow.visibility = View.GONE }

    private fun switchTone(m: Mode) {
        mode = m
        prefs.mode = m
        paintTone()
        if (lastSpoken.isNotBlank() && inserted != null) rewrite(m)
        else showInfo("🎤로 말하면 '${m.label}' 말투로 다듬어요", tones = true)
    }

    // ───────── 🎤 말로 답장 · 🔎 찾기 ─────────

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 듣는 동안 자판 자리에 보이는 판 */
    private fun showListeningPanel(search: Boolean) {
        content.removeAllViews()
        val box = Ui.vbox(this, 14, Ui.CARD)
        box.add(Ui.text(this, if (search) "🔎 무엇을 찾을까요?" else "🎤 편하게 말하세요", 19f, bold = true).apply { gravity = Gravity.CENTER; Ui.iconize(this) }, 0)
        box.add(Ui.text(this, if (search) "예) 모임 날짜 언제로 정했지?" else "다 말하고 잠시 멈추면 다듬어서 입력칸에 넣어요", 15f, color = Ui.MUTED).apply { gravity = Gravity.CENTER; tag = "heard" }, 6)
        box.add(Ui.text(this, "🎙️ 듣는 중 · 누르면 끝", 18f, bold = true, color = Ui.CARD).apply {
            gravity = Gravity.CENTER
            minHeight = dp(56)
            background = Ui.rounded(this@KeyboardService, Ui.GREEN, 16)
            setOnClickListener { finishSpeech() }
            Ui.iconize(this)
        }, 12)
        box.add(Ui.text(this, "취소", 15f, bold = true, color = Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { stopRecognizer(); showKeys() }
        }, 4)
        content.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun heardView(): TextView? = content.findViewWithTag("heard")

    private fun startVoice(search: Boolean) {
        if (listening) { finishSpeech(); return }
        finishComposing()
        if (!hasMic()) {
            showInfo("마이크 허용이 필요해요. '${getString(R.string.app_name)}' 앱을 열어 마이크를 허용해 주세요.", color = Ui.RED)
            runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { showInfo("이 휴대폰에서 음성 인식을 쓸 수 없어요.", color = Ui.RED); return }
        if (ClaudeProvider.get(this) == null) { showInfo("아직 준비가 안 됐어요. 앱을 열어 설정을 마쳐 주세요.", color = Ui.RED); return }
        heard.clear(); partial = ""
        listening = true
        searchMode = search
        hideInfo()
        showListeningPanel(search)
        Sounds.listenStart(this)
        Sounds.buzz(this, 70)
        Bg.main.removeCallbacks(pulse); Bg.main.postDelayed(pulse, 3000)
        Bg.main.postDelayed({ if (listening) listen() }, 250)
    }

    private fun newRecognizer(): SpeechRecognizer {
        val google = android.content.ComponentName(
            "com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        val hasGoogle = runCatching {
            packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
                .any { it.serviceInfo.packageName == google.packageName && it.serviceInfo.name == google.className }
        }.getOrDefault(false)
        return if (hasGoogle) SpeechRecognizer.createSpeechRecognizer(this, google) else SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun listen() {
        val r = recognizer ?: newRecognizer().also { it.setRecognitionListener(listener); recognizer = it }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        runCatching { r.startListening(i) }.onFailure { stopRecognizer(); showKeys(); showInfo("음성 인식을 시작하지 못했어요.", color = Ui.RED) }
    }

    private fun stopRecognizer() {
        listening = false
        Bg.main.removeCallbacks(pulse)
        runCatching { recognizer?.cancel() }
    }

    private fun finishSpeech() {
        if (!listening) return
        if (partial.isNotBlank()) { heard.append(' ').append(partial); partial = "" }
        stopRecognizer()
        showKeys()
        val spoken = EndSignal.strip(heard.toString())
        if (spoken.isBlank()) { showInfo("말씀을 듣지 못했어요. 다시 눌러 주세요."); return }
        Sounds.listenDone(this)
        Sounds.buzz(this, 90)
        if (searchMode) { searchMode = false; searchAll(spoken); return }
        lastSpoken = spoken
        cache.clear()
        inserted = null
        rewrite(mode)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(b: Bundle?) {
            if (!listening) return
            partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val now = (heard.toString() + " " + partial).trim()
            if (now.isNotBlank()) heardView()?.let { it.text = "“$now”"; it.setTextColor(Ui.BROWN) }
        }
        override fun onResults(b: Bundle?) {
            if (!listening) return
            val best = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (best.isNotBlank()) heard.append(' ').append(best)
            partial = ""
            // 말이 끝나(잠시 조용해지)면 바로 다듬음. "~라고 보내 줘"는 떼어 냄
            finishSpeech()
        }
        override fun onError(error: Int) {
            if (!listening) return
            if (heard.isNotBlank() || partial.isNotBlank()) { finishSpeech(); return }
            stopRecognizer()
            showKeys()
            showInfo(when (error) {
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "인터넷 연결을 확인해 주세요."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 허용이 필요해요."
                else -> "말씀을 듣지 못했어요. 다시 눌러 주세요."
            }, color = Ui.RED)
        }
    }

    /** [m] 말투로 다듬어 입력칸에 넣음. 방금 넣은 글이 있으면 지우고 바꿔 넣음 */
    private fun rewrite(m: Mode) {
        cache[m]?.let { put(it); return }
        val claude = ClaudeProvider.get(this) ?: return
        val spoken = lastSpoken
        val id = ++job
        showInfo("${m.emoji} '${m.label}' 말투로 다듬는 중…")
        Bg.run({ claude.rewrite(m, spoken, null, prefs.myName, prefs.myRole, emptyList()) }) { r ->
            if (id != job) return@run
            r.onSuccess { text -> cache[m] = text; if (mode == m) put(text) }
                .onFailure { showInfo((it as? HelperException)?.message ?: "문제가 생겼어요. 다시 해 주세요.", color = Ui.RED) }
        }
    }

    /** 입력칸에 넣기 (방금 넣은 글이 커서 바로 앞에 그대로 있으면 지우고 바꿈) */
    private fun put(text: String) {
        finishComposing()
        val ic = currentInputConnection ?: return
        inserted?.let { old ->
            val before = ic.getTextBeforeCursor(old.length, 0)?.toString()
            if (before == old) ic.deleteSurroundingText(old.length, 0)
        }
        ic.commitText(text, 1)
        inserted = text
        showInfo("✅ 넣었어요. 확인하고 직접 보내세요. 다른 말투로 바꾸려면:", tones = true)
    }

    /** 🔎 카톡 '대화 내보내기'로 저장해 둔 대화에서 찾기 (화면은 읽지 않음) */
    private fun searchAll(question: String) {
        val claude = ClaudeProvider.get(this) ?: return
        val id = ++job
        showInfo("🔎 \"$question\" 저장해 둔 대화에서 찾는 중…")
        Bg.run({
            val ids = Rooms.ids(this)
            if (ids.isEmpty()) null
            else {
                val rooms = ids.map { r -> (Rooms.name(this, r) ?: r) to ChatStore.chatText(this, r).orEmpty() }.filter { it.second.isNotBlank() }
                claude.answer(question, ChatSearch.excerptMany(rooms, question), null, manyRooms = true)
            }
        }) { r ->
            if (id != job) return@run
            r.onSuccess { full ->
                if (full == null) {
                    showInfo("아직 저장해 둔 대화가 없어요. 카톡 대화방 ☰ → 설정 → '대화 내용 내보내기' → '말로 답장'으로 보내 두면 찾을 수 있어요.", color = Ui.RED)
                    return@onSuccess
                }
                hideInfo()
                showAnswer(question, ChatSearch.splitAnswer(full).first)
            }.onFailure { showInfo((it as? HelperException)?.message ?: "찾지 못했어요. 다시 해 주세요.", color = Ui.RED) }
        }
    }

    private fun showAnswer(question: String, text: String) {
        content.removeAllViews()
        val box = Ui.vbox(this, 12, Ui.THEM)
        box.add(Ui.text(this, "🔎 $question", 14f, bold = true, color = Ui.MUTED).apply { Ui.iconize(this) }, 0)
        val t = TextView(this).apply {
            setTextColor(Ui.BROWN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            this.text = Ui.answer(text)
        }
        box.addView(ScrollView(this).apply { addView(t) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyH * 3))
        box.add(Ui.text(this, "닫고 자판으로", 16f, bold = true, color = Ui.YELLOW).apply {
            gravity = Gravity.CENTER; minHeight = dp(46)
            background = Ui.rounded(this@KeyboardService, Ui.BROWN, 14)
            setOnClickListener { showKeys() }
        }, 8)
        content.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    companion object {
        private val KB_BG = android.graphics.Color.parseColor("#E9E4DC")
        private val KEY_SPECIAL = android.graphics.Color.parseColor("#D6CFC4")
        private val KO_ROWS = listOf("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ")
        private val KO_SHIFT = mapOf('ㅂ' to 'ㅃ', 'ㅈ' to 'ㅉ', 'ㄷ' to 'ㄸ', 'ㄱ' to 'ㄲ', 'ㅅ' to 'ㅆ', 'ㅐ' to 'ㅒ', 'ㅔ' to 'ㅖ')
        private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ"
        private const val CONSONANTS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    }
}
