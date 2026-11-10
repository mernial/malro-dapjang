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
 * 자판 배열은 삼성 키보드와 같게 (넓은 폴드 화면에서도 가운데를 비우지 않음).
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
    private enum class Layer { KO, EN, SYM, EMOJI }
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
    /** 키 높이: 화면 높이에 맞춰 (너무 작거나 크지 않게) */
    /** 넓은 화면 (폴드 펼침·태블릿): 자음·모음 사이를 띄우고 키 간격을 넓힘 */
    private val wide get() = screenWdp >= 600
    private val keyH get() = dp((screenHdp * 0.062f).toInt().coerceIn(42, 56))
    private val keyTextSp get() = if (screenHdp < 640) 18f else 21f

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun sysDimen(name: String): Int = runCatching {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id != 0) resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

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
        // 단축키 넷: 꾹 눌러 끌어서 다른 단추 위에 놓으면 자리 바꾸기 (순서 기억 'toolOrder')
        fun tool(key: String, label: String, weight: Float, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            tag = key
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Ui.BROWN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Ui.rounded(ctx, Ui.CARD, 12, Ui.LINE)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
            setOnLongClickListener { v ->
                Sounds.buzz(ctx, 30)
                v.startDragAndDrop(android.content.ClipData.newPlainText("tool", key), View.DragShadowBuilder(v), key, 0)
                true
            }
            setOnDragListener { v, e ->
                when (e.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> e.localState is String && (e.localState as String) in TOOL_KEYS
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> { v.alpha = 0.45f; true }
                    android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
                    android.view.DragEvent.ACTION_DROP -> {
                        val from = e.localState as? String
                        if (from != null && from != key) {
                            val list = toolOrder().toMutableList()
                            val fi = list.indexOf(from); val ti = list.indexOf(key)
                            list.removeAt(fi); list.add(ti, from)
                            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("toolOrder", list.joinToString(",")).apply()
                            Bg.main.post { setInputView(onCreateInputView()) }
                        }
                        true
                    }
                    else -> true
                }
            }
            Ui.iconize(this)
        }
        val tools = mapOf(
            "voice" to { tool("voice", "🎤 말로 답장", 1.6f) { startVoice(search = false) }.apply {
                setTextColor(Ui.YELLOW); background = Ui.rounded(ctx, Ui.BROWN, 12); text = "🎤 말로 답장"; Ui.iconize(this) } to 1.6f },
            "search" to { tool("search", "🔎 찾기", 1f) { startVoice(search = true) } to 1f },
            "tone" to { tool("tone", "", 1.3f) { toggleToneRow() }.also { toneButton = it } to 1.3f },
            "layout" to { tool("layout", if (prefs.keyboardLayout == "cji") "⇄ 두벌식" else "⇄ 천지인", 1.1f) { switchLayout() } to 1.1f },
        )
        for (k in toolOrder()) {
            val (v, w) = tools.getValue(k)()
            toolbar.addView(v, LinearLayout.LayoutParams(0, dp(42), w).apply { marginEnd = dp(4) })
        }
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
        // 안드로이드 15+ 는 키보드 창이 화면 맨 아래까지 내려감. 처음 크기를 잴 때부터 아래 막대 자리를 비워 둬야
        // 창 높이가 모자라 맨 아래 줄이 잘리지 않음 (나중에 알려 주는 값만 쓰면 자판을 바꿀 때 잘렸음)
        // 삼성 폰은 키보드 창에 아래 막대 높이를 0으로 알려 주기도 해서, 그때도 최소 48dp 는 비워 둠
        val guess = if (android.os.Build.VERSION.SDK_INT >= 35)
            maxOf(sysDimen("navigation_bar_frame_height"), sysDimen("navigation_bar_height"), dp(48)) else 0
        r.setPadding(r.paddingLeft, r.paddingTop, r.paddingRight, base + guess)
        r.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                // 제스처 막대만 있는 폰(삼성 등)은 막대 높이가 작게 알려져도, 그 자리에 '키보드 내리기 ∨' 줄(약 48dp)이 그려짐 → 그만큼 띄움
                val imeBar = if (nav > 0) sysDimen("navigation_bar_frame_height") else 0
                maxOf(nav, imeBar,
                    insets.getInsets(android.view.WindowInsets.Type.tappableElement()).bottom,
                    insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()).bottom)
            } else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            android.util.Log.i("talkhelper-kb", "bottom inset=$bottom")
            val want = base + maxOf(bottom, guess)
            if (v.paddingBottom != want) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, want)
                v.post { window.window?.decorView?.requestLayout() }
            }
            insets
        }
        // 아래 내비게이션 막대를 어두운 띠 대신 키보드 바탕색으로
        window.window?.let { w ->
            if (android.os.Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION") w.navigationBarColor = KB_BG
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
        // 입력칸이 원하는 줄바꿈 칸 모양 (검색칸이면 🔍 검색, 보내기 칸이면 '보내기' …)
        val opts = info?.imeOptions ?: 0
        val multi = (info?.inputType ?: 0) and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val act = opts and EditorInfo.IME_MASK_ACTION
        val newAction = if (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 || multi) EditorInfo.IME_ACTION_NONE else act
        val actionChanged = newAction != editorAction
        editorAction = newAction
        val want = if (cls == EditorInfo.TYPE_CLASS_NUMBER || cls == EditorInfo.TYPE_CLASS_PHONE) Layer.SYM else if (layer == Layer.SYM) Layer.KO else layer
        if (want != layer || !restarting || actionChanged) { layer = want; shift = false; if (::content.isInitialized && !listening) showKeys() }
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
            Layer.EMOJI -> emojis()
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
        // 얇은 테두리로 키 경계를 또렷하게 (옆 키를 잘못 누르는 일 줄이기)
        background = Ui.rounded(this@KeyboardService, if (special) KEY_SPECIAL else Ui.CARD, 8, KEY_EDGE)
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

    private fun keyLp(w: Float) = LinearLayout.LayoutParams(0, keyH, w).apply {
        val h = if (wide) dp(5) else dp(2); val v = if (wide) dp(4) else dp(3)
        setMargins(h, v, h, v)
    }
    private fun gap(w: Float): Pair<View, Float> = View(this) to w
    /** 넓은 화면에서 자음·모음 사이 빈칸 (좁은 화면은 없음) */
    private fun mid(): List<Pair<View, Float>> = if (wide) listOf(gap(2.4f)) else emptyList()

    private fun row(items: List<Pair<View, Float>>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        // 글자 기준선 맞춤을 끔: 그림(😊·🔎) 키만 위로 올라가 줄이 어긋나던 것
        isBaselineAligned = false
        items.forEach { (v, w) -> addView(v, keyLp(w)) }
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
            key("!#1", special = true) { toSymbols() } to 1.5f,
            key("한/영", special = true) { if (ko) toEnglish() else toKorean() } to 1.2f,
            emojiKey() to 1f,
            key(",", special = true) { commitPlain(",") } to 0.9f,
            key("띄어쓰기") { space() } to 3.4f,
            key(".", special = true) { commitPlain(".") } to 1f,
            enterKey() to 1.7f,
        )
        return column(listOf(
            // 폴드처럼 넓으면 왼쪽(자음)·오른쪽(모음) 사이를 조금 띄움. 모든 줄에 같은 폭이라 키 줄이 어긋나지 않음
            row(r1.take(5) + mid() + r1.drop(5)),
            row(listOf(gap(0.5f)) + r2.take(5) + mid() + r2.drop(5) + listOf(gap(0.5f))),
            row(listOf(shiftKey) + r3.take(4) + mid() + r3.drop(4) + listOf(back)),
            row(bottom),
        ))
    }

    /** 천지인: ㅣ ㆍ ㅡ 와 자음 묶음 키 (같은 키를 거듭 누르면 ㄱ→ㅋ→ㄲ 처럼 바뀜) */
    private fun cheonjiin(): View {
        fun v(label: String, k: Char) = key(label) { lastCjiKey = null; hangul.cjiVowel(k); render() } to 1f
        fun c(label: String, cycle: String) = key(label) { cjiConsonant(label, cycle) } to 1f
        val rows = listOf(
            row(listOf(v("ㅣ", 'I'), v("•", 'D'), v("ㅡ", 'E'), key("⌫", special = true, repeat = true) { backspace() } to 1f)),
            row(listOf(c("ㄱㅋ", "ㄱㅋㄲ"), c("ㄴㄹ", "ㄴㄹ"), c("ㄷㅌ", "ㄷㅌㄸ"), enterKey() to 1f)),
            row(listOf(c("ㅂㅍ", "ㅂㅍㅃ"), c("ㅅㅎ", "ㅅㅎㅆ"), c("ㅈㅊ", "ㅈㅊㅉ"), key(".,?!", special = true) { cyclePunct() } to 1f)),
            // 휴대폰 숫자판처럼: ㅇㅁ 은 가운데(0 자리), 오른쪽 띄어쓰기
            row(listOf(
                // 문장부호는 위(ㅈㅊ 옆)로, 여기는 기호·한영 (신부님 요청 2026-11-10)
                key("!#1", special = true) { toSymbols() } to 0.5f, key("한/영", special = true) { toEnglish() } to 0.5f,
                c("ㅇㅁ", "ㅇㅁ"),
                key("띄어쓰기") { space() } to 1f,
                // 삼성 천지인처럼 오른쪽 맨 아래는 이모티콘
                emojiKey() to 1f,
            )),
        )
        if (!wide) return column(rows)
        // 폴드처럼 넓은 화면: 천지인을 왼쪽·가운데·오른쪽 중 한곳에 모음 (비어 있는 쪽의 화살표 단추로 한 칸씩 옮김, 위치 기억)
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val sides = listOf("left", "center", "right")
        val side = sp.getString("cjiSide", "center").takeIf { it in sides } ?: "center"
        val idx = sides.indexOf(side)
        fun pane(toLeft: Boolean): View = FrameLayout(this).apply {
            val target = sides[idx + if (toLeft) -1 else 1]
            addView(TextView(this@KeyboardService).apply {
                text = if (toLeft) "◀\n왼쪽으로" else "▶\n오른쪽으로"
                gravity = Gravity.CENTER
                setTextColor(Ui.BROWN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = Ui.rounded(this@KeyboardService, Ui.CARD, 20, KEY_EDGE)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                contentDescription = if (toLeft) "자판을 왼쪽으로 옮기기" else "자판을 오른쪽으로 옮기기"
                setOnClickListener { sp.edit().putString("cjiSide", target).apply(); showKeys() }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val keys = column(rows)
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val keysLp = LinearLayout.LayoutParams(dp(minOf(480, screenWdp / 2)), ViewGroup.LayoutParams.WRAP_CONTENT)
        fun paneLp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        if (idx > 0) box.addView(pane(toLeft = true), paneLp())
        box.addView(keys, keysLp)
        if (idx < 2) box.addView(pane(toLeft = false), paneLp())
        return box
    }

    // ───────── 이모티콘 ─────────

    private var emojiFrom = Layer.KO
    private fun emojiKey(): TextView = key("😊", special = true) { finishComposing(); emojiFrom = layer; layer = Layer.EMOJI; showKeys() }
        .apply { contentDescription = "이모티콘" }

    private fun recentEmojis(): List<String> =
        getSharedPreferences("settings", MODE_PRIVATE).getString("recentEmoji", "").orEmpty().split(' ').filter { it.isNotBlank() }

    private fun useEmoji(e: String) {
        commitPlain(e)
        val list = (listOf(e) + recentEmojis().filter { it != e }).take(16)
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("recentEmoji", list.joinToString(" ")).apply()
    }

    /** 이모티콘 판: 최근 쓴 것 + 무리별, 아래 줄은 돌아가기·지우기·띄어쓰기·줄바꿈 */
    private fun emojis(): View {
        // 칸 하나 약 56dp 로 (넓은 화면에서 이모티콘 사이가 너무 벌어지지 않게)
        val cols = ((screenWdp - 16) / 40).coerceAtLeast(8)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        fun group(title: String, list: List<String>) {
            if (list.isEmpty()) return
            grid.addView(TextView(this).apply {
                text = title; setTextColor(Ui.MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(4), dp(8), 0, dp(2))
            })
            list.chunked(cols).forEach { line ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                line.forEach { e ->
                    r.addView(TextView(this).apply {
                        text = e; gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                        setOnClickListener { Sounds.buzz(this@KeyboardService, 8, 40); useEmoji(e) }
                    }, LinearLayout.LayoutParams(0, dp(42), 1f))
                }
                repeat(cols - line.size) { r.addView(View(this), LinearLayout.LayoutParams(0, dp(42), 1f)) }
                grid.addView(r)
            }
        }
        group("최근", recentEmojis())
        EMOJI_GROUPS.forEach { (t, l) -> group(t, l.split(' ')) }
        val scroll = ScrollView(this).apply { addView(grid) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyH * 4 - dp(4)))
        // 아래 줄은 작게: 높이 44dp, 넓은 화면에선 가운데 520dp 안에
        val bottom = row(listOf(
            key(if (emojiFrom == Layer.EN) "ABC" else "가", special = true) { layer = if (emojiFrom == Layer.EMOJI) Layer.KO else emojiFrom; showKeys() } to 1.3f,
            key("띄어쓰기") { space() } to 3f,
            key("⌫", special = true, repeat = true) { backspace() } to 1.3f,
            enterKey() to 1.3f,
        ))
        for (i in 0 until bottom.childCount) bottom.getChildAt(i).layoutParams.height = dp(44)
        col.addView(column(listOf(bottom), maxWidthDp = 520))
        return col
    }

    private fun symbols(): View {
        // 넓은 화면에선 한글 자판처럼 가운데를 띄움
        fun line(s: String): LinearLayout {
            val ks = s.map { ch -> key("$ch") { commitPlain("$ch") } to 1f }
            return row(ks.take(5) + mid() + ks.drop(5))
        }
        val bottom = row(listOf(
            key("가", special = true) { toKorean() } to 1.4f,
            key("%", special = true) { commitPlain("%") } to 0.9f,
            key("#", special = true) { commitPlain("#") } to 0.9f,
            key("띄어쓰기") { space() } to 3f,
            key("⌫", special = true, repeat = true) { backspace() } to 1.4f,
            enterKey() to 1.4f,
        ))
        return column(listOf(line("1234567890"), line("-/:;()₩&@\""), line(".,?!'~^*+="), bottom), )
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

    /** 띄어쓰기. 글자 뒤에서 1초 안에 두 번 누르면 마침표 + 띄어쓰기 (삼성·아이폰 키보드처럼) */
    private var lastSpaceAt = 0L
    private fun space() {
        val now = System.currentTimeMillis()
        val ic = currentInputConnection
        if (ic != null && !hangul.isComposing && now - lastSpaceAt < 1000) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.length == 2 && before[1] == ' ' && (before[0].isLetterOrDigit())) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0L; lastCjiKey = null; inserted = null
                return
            }
        }
        commitPlain(" ")
        lastSpaceAt = now
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        lastCjiKey = null
        inserted = null
        if (hangul.backspace()) { ic.setComposingText(hangul.composing, 1); return }
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }

    /** 지금 입력칸의 줄바꿈 칸 동작 (IME_ACTION_*). NONE 이면 그냥 줄바꿈 */
    private var editorAction = EditorInfo.IME_ACTION_NONE

    /** 줄바꿈 칸: 검색칸이면 🔍, 보내기·이동·다음·완료 칸이면 그 글자, 아니면 ↵ */
    private fun enterKey(): TextView {
        val (label, desc) = when (editorAction) {
            EditorInfo.IME_ACTION_SEARCH -> "🔎" to "검색"
            EditorInfo.IME_ACTION_SEND -> "보내기" to "보내기"
            EditorInfo.IME_ACTION_GO -> "이동" to "이동"
            EditorInfo.IME_ACTION_NEXT -> "다음" to "다음 칸"
            EditorInfo.IME_ACTION_DONE -> "완료" to "완료"
            else -> "↵" to "줄바꿈"
        }
        return key(label, special = true) { enter() }.apply {
            contentDescription = desc
            if (editorAction == EditorInfo.IME_ACTION_SEARCH) background = Ui.rounded(this@KeyboardService, Ui.YELLOW, 8, KEY_EDGE)
        }
    }

    private fun enter() {
        finishComposing()
        val ic = currentInputConnection ?: return
        val act = editorAction
        if (act != EditorInfo.IME_ACTION_NONE && act != EditorInfo.IME_ACTION_UNSPECIFIED && ic.performEditorAction(act)) {
            lastCjiKey = null; inserted = null; return
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        lastCjiKey = null
        inserted = null
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
        showInfo(if (inserted != null) "말투를 누르면 방금 넣은 글이 그 말투로 바뀌어요" else "🎤로 말할 때 쓸 말투를 고르세요 · 꾹 눌러 끌면 자리 바꾸기", tones = true)
    }

    private fun toneChips(): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 말투 순서는 사용자가 정한 대로 (꾹 눌러 끌어서 옮김, 카톡 버튼 메뉴와 같은 저장값 toneOrder)
        val order = orderedModes()
        for (m in order) {
            val on = m == mode
            line.addView(TextView(this).apply {
                text = "${m.emoji} ${m.label}"
                // 꾹 누르면 끌어서 다른 칩 위에 놓아 자리 바꾸기
                setOnLongClickListener { v ->
                    Sounds.buzz(this@KeyboardService, 30)
                    v.startDragAndDrop(android.content.ClipData.newPlainText("tone", m.name), View.DragShadowBuilder(v), m, 0)
                    true
                }
                setOnDragListener { v, e ->
                    when (e.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> e.localState is Mode
                        android.view.DragEvent.ACTION_DRAG_ENTERED -> { v.alpha = 0.45f; true }
                        android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
                        android.view.DragEvent.ACTION_DROP -> {
                            val from = e.localState as? Mode
                            if (from != null && from != m) {
                                val list = orderedModes().toMutableList()
                                list.remove(from)
                                list.add(list.indexOf(m).let { if (order.indexOf(from) < order.indexOf(m)) it + 1 else it }, from)
                                prefs.toneOrder = list.map { it.name }
                                Bg.main.post { refreshToneChips() }
                            }
                            true
                        }
                        else -> true
                    }
                }
                setTextColor(if (on) Ui.YELLOW else Ui.BROWN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = Ui.rounded(this@KeyboardService, if (on) Ui.BROWN else Ui.CARD, 16, if (on) Ui.BROWN else Ui.LINE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { switchTone(m) }
                Ui.iconize(this)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        // 🚩 이상한 AI 결과 알리기 (구글 플레이 AI 생성 콘텐츠 정책)
        inserted?.let { ai ->
            line.addView(TextView(this).apply {
                text = "🚩 이상해요"
                setTextColor(Ui.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = Ui.rounded(this@KeyboardService, Ui.CARD, 16, Ui.LINE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                contentDescription = "이상한 결과 알리기"
                setOnClickListener { askReport(ai) }
                Ui.iconize(this)
            })
        }
        scroll.addView(line)
        return scroll
    }

    /** 🚩 누르면 키보드 안에서 한 번 더 묻고, '알리기'면 운영자에게 보냄 (앱이 몰래 보내지 않음) */
    private fun askReport(aiText: String, spoken: String = lastSpoken) {
        showInfo("🚩 이 글이 이상하거나 불쾌했나요? 말씀하신 내용과 AI가 만든 글을 운영자에게 알려요.")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER
            setTextColor(if (primary) Ui.CARD else Ui.BROWN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Ui.rounded(this@KeyboardService, if (primary) Ui.BROWN else Ui.CARD, 14, if (primary) null else Ui.LINE)
            setOnClickListener { onClick() }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }) }
        b("알리기", true) {
            prefs.addAiReport(spoken, aiText, "")
            showInfo("보내는 중…")
            Bg.run({ AiReport.send("", spoken, aiText, BuildConfig.VERSION_NAME) }) { r ->
                showInfo(if (r.getOrDefault(false)) "알려 주셔서 고마워요. 운영자에게 전달했어요." else "접수했어요. 지금은 전달하지 못해 이 휴대폰에 기록해 두었어요.")
            }
        }
        b("취소", false) { hideInfo() }
        infoRow.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun toolOrder(): List<String> {
        val saved = getSharedPreferences("settings", MODE_PRIVATE).getString("toolOrder", "").orEmpty().split(',').filter { it in TOOL_KEYS }
        return saved + TOOL_KEYS.filter { it !in saved }
    }

    private fun orderedModes(): List<Mode> {
        val all = Mode.values().toList()
        val saved = prefs.toneOrder.mapNotNull { k -> all.firstOrNull { it.name == k } }
        return saved + all.filter { it !in saved }
    }

    /** 말투 칩 줄만 새로 그림 (순서를 바꾼 뒤) */
    private fun refreshToneChips() {
        if (infoRow.tag != "tones" || infoRow.childCount < 2) return
        val idx = infoRow.indexOfChild(infoRow.getChildAt(1))
        infoRow.removeViewAt(idx)
        infoRow.addView(toneChips(), idx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
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
        box.add(Ui.text(this, if (search) "예) 모임 날짜 언제로 정했지?" else "쉬어 가며 말해도 돼요. 다 말하면 아래를 누르거나 3초쯤 기다리세요", 15f, color = Ui.MUTED).apply { gravity = Gravity.CENTER; tag = "heard" }, 6)
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
        listenStartedAt = System.currentTimeMillis(); lastHeardAt = listenStartedAt
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

    private var listenStartedAt = 0L
    private var lastHeardAt = 0L

    /** 한 토막이 끝난 뒤 이어서 듣기. 3.5초 넘게 조용하면 그때까지 한 말로 다듬고, 8초 동안 아무 말도 없으면 멈춤 */
    private fun keepListening() {
        val now = System.currentTimeMillis()
        val spoken = heard.isNotBlank()
        when {
            spoken && now - lastHeardAt >= 3500 -> finishSpeech()
            !spoken && now - listenStartedAt >= 8000 -> { stopRecognizer(); showKeys(); showInfo("말씀을 듣지 못했어요. 다시 눌러 주세요.") }
            else -> Bg.main.postDelayed({ if (listening) listen() }, 120)
        }
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
        // 방금 넣은 글이 그대로 있으면(보내지 않음) 새로 말한 내용으로 바꿔 넣음 — put() 이 지우고 넣음 (신부님 요청 2026-11-10)
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
            if (partial.isNotBlank()) lastHeardAt = System.currentTimeMillis()
            val now = (heard.toString() + " " + partial).trim()
            if (now.isNotBlank()) heardView()?.let { it.text = "“$now”"; it.setTextColor(Ui.BROWN) }
        }
        override fun onResults(b: Bundle?) {
            if (!listening) return
            val best = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (best.isNotBlank()) { heard.append(' ').append(best); lastHeardAt = System.currentTimeMillis() }
            partial = ""
            heardView()?.let { v -> heard.toString().trim().takeIf { it.isNotBlank() }?.let { v.text = "“$it”"; v.setTextColor(Ui.BROWN) } }
            // 음성 인식은 말이 잠깐만 끊겨도 한 토막을 끝냄 → 바로 다듬지 않고 이어서 들음.
            // 끝: '듣는 중 · 누르면 끝' 누르기, "~라고 보내 줘", 또는 3.5초 넘게 조용함. 찾기는 한 문장이면 끝
            when {
                searchMode && heard.isNotBlank() -> finishSpeech()
                EndSignal.isEnd(heard.toString()) -> finishSpeech()
                else -> keepListening()
            }
        }
        override fun onError(error: Int) {
            if (!listening) return
            if (error != SpeechRecognizer.ERROR_NETWORK && error != SpeechRecognizer.ERROR_NETWORK_TIMEOUT && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                // 말이 없어서 끝난 것(조용함·못 알아들음)이면 계속 들음
                if (partial.isNotBlank()) { heard.append(' ').append(partial); partial = "" }
                if (searchMode && heard.isNotBlank()) { finishSpeech(); return }
                keepListening(); return
            }
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
            if (before == old) {
                // 커서 뒤에 다른 글이 없을 때만 (사용자가 고쳐 쓰는 중인 글은 건드리지 않음)
                ic.deleteSurroundingText(old.length, 0)
            }
        }
        ic.commitText(text, 1)
        inserted = text
        // 말투 줄은 매번 띄우지 않음 (신부님 요청 2026-11-10): 짧은 안내만 잠깐, 말투를 바꾸려면 위 '말투 ▾'
        showInfo("✅ 넣었어요. 확인하고 직접 보내세요. 말투를 바꾸려면 위 '말투 ▾'")
        infoRow.tag = "done"
        val ai = text
        infoRow.addView(TextView(this).apply {
            this.text = "🚩 이상해요"
            setTextColor(Ui.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(6), dp(2), dp(6), dp(4))
            contentDescription = "이상한 결과 알리기"
            setOnClickListener { askReport(ai) }
            Ui.iconize(this)
        })
        Bg.main.postDelayed({ if (infoRow.tag == "done") hideInfo() }, 6000)
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
        box.add(Ui.text(this, "🚩 이상한 답 알리기", 14f, color = Ui.MUTED).apply {
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener { showKeys(); askReport(text, "찾기: $question") }
        }, 4)
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
        private val EMOJI_GROUPS = listOf(
            "자주 쓰는" to "😊 😂 🥰 😍 👍 🙏 ❤️ 👏 🎉 😭 😅 🤗 😢 🙂 😆 💕",
            "표정" to "😀 😃 😄 😁 😉 😌 🥹 😇 🤔 😮 😲 😳 🥲 😔 😞 😣 😤 😡 😴 🤒 😷 🤭 🫢 😎",
            "손·몸" to "👋 🙌 👐 🤝 ✌️ 👌 🤞 💪 👆 👉 ✋ 🙆 🙇 🙋 🫶 ✊",
            "마음" to "❤️ 🧡 💛 💚 💙 💜 🤍 💖 💗 💝 💞 💌 ✨ 🌟 ⭐ 💯",
            "신앙" to "🙏 ✝️ ⛪ 📿 🕊️ 🕯️ 📖 🔔 👼 🌹 ☦️ 🎄",
            "축하·일상" to "🎂 🎁 🎊 🌸 🌷 💐 ☕ 🍚 🍰 🍎 🏠 🚗 ☀️ 🌧️ ❄️ 🌙",
        )
        private val TOOL_KEYS = listOf("voice", "search", "tone", "layout")
        private val KEY_EDGE = android.graphics.Color.parseColor("#CDBFAF")
        private val KO_ROWS = listOf("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ")
        private val KO_SHIFT = mapOf('ㅂ' to 'ㅃ', 'ㅈ' to 'ㅉ', 'ㄷ' to 'ㄸ', 'ㄱ' to 'ㄲ', 'ㅅ' to 'ㅆ', 'ㅐ' to 'ㅒ', 'ㅔ' to 'ㅖ')
        private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ"
        private const val CONSONANTS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    }
}
