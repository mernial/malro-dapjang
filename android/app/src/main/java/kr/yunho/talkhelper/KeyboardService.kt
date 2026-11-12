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
import kr.yunho.talkhelper.core.MyStyle

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
        // 예전에 소리를 꺼 둔 채 키보드가 꺼졌으면 되돌림
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("beepMuted", false)) { beepMuted = true; unmuteBeep() }
    }

    // ───────── 크기 (폴드·일반 폰) ─────────

    private fun dp(v: Int) = Ui.dp(this, v)
    private val screenWdp get() = resources.configuration.screenWidthDp
    private val screenHdp get() = resources.configuration.screenHeightDp
    /** 키 높이: 화면 높이에 맞춰 (너무 작거나 크지 않게) */
    /** 넓은 화면 (폴드 펼침·태블릿): 자음·모음 사이를 띄우고 키 간격을 넓힘 */
    private val wide get() = screenWdp >= 600
    private val keyH get() = dp((keyHdp - keyShrinkDp).coerceAtLeast(36))
    /** 키보드 창이 모자라 맨 아래 줄이 잘릴 때 키 높이를 이만큼 줄임 (폴드 가로 화면, 신부님 2026-11-12) */
    private var keyShrinkDp = 0
    /** 키 높이(dp). 폴드처럼 넓은 화면은 조금 더 크게 (오타 줄이기, 신부님 2026-11-11) */
    private val keyHdp get() = if (wide) (screenHdp * 0.068f).toInt().coerceIn(50, 62) else (screenHdp * 0.062f).toInt().coerceIn(42, 56)
    private val keyTextSp get() = if (screenHdp < 640) 18f else 21f

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun sysDimen(name: String): Int = runCatching {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id != 0) resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    // ───────── 화면 만들기 ─────────

    /** 지금 키보드 색 */
    private var th = KbTheme.ALL[0]

    override fun onCreateInputView(): View {
        val ctx = this
        mode = prefs.mode
        th = KbTheme.of(prefs.keyboardTheme)
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(th.bg)
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
            setTextColor(th.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Ui.rounded(ctx, th.key, 12, th.edge)
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
            // ⚙ 키보드 설정: 말로 답장 앱의 설정 화면 (키보드 바꾸기·내 이름 등)
            "settings" to { tool("settings", "⚙", 0.5f) { openAppSettings() }.apply { contentDescription = "키보드 설정" } to 0.5f },
        )
        for (k in toolOrder()) {
            val (v, w) = tools.getValue(k)()
            toolbar.addView(v, LinearLayout.LayoutParams(0, dp(42), w).apply { marginEnd = dp(4) })
        }
        r.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 2) 알림 줄 (넣었어요·말투 고르기·오류)
        infoRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        infoText = TextView(ctx).apply {
            setTextColor(th.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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
                // 아래 자리가 늘어 맨 아래 줄이 잘리는지 다시 봄
                v.postDelayed({ if (::content.isInitialized && !listening) showKeys() }, 120)
            }
            insets
        }
        // 아래 내비게이션 막대를 어두운 띠 대신 키보드 바탕색으로
        window.window?.let { w ->
            if (android.os.Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION") w.navigationBarColor = th.bg
            // 아래 막대의 '키보드 내리기 ∨'·'키보드 바꾸기' 그림을 진하게 (밝은 키보드에선 어두운 그림, 어두운 키보드에선 밝은 그림)
            val lightBar = th.id != "dark"
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val f = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                w.insetsController?.setSystemBarsAppearance(if (lightBar) f else 0, f)
            } else {
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = if (lightBar) w.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    else w.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
        paintTone()
        showKeys()
        return r
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 첫 화면에서 키보드 색을 바꿨으면 새 색으로 다시 그림
        if (::content.isInitialized && KbTheme.of(prefs.keyboardTheme) != th && !listening) setInputView(onCreateInputView())
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
        val want = if (cls == EditorInfo.TYPE_CLASS_NUMBER || cls == EditorInfo.TYPE_CLASS_PHONE) Layer.SYM else if (layer == Layer.SYM) Layer.KO else if (layer == Layer.EMOJI) (if (emojiFrom == Layer.EN) Layer.EN else Layer.KO) else layer
        if (want != layer || !restarting || actionChanged) { layer = want; shift = false; if (::content.isInitialized && !listening) showKeys() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // 키보드가 내려가면 이모티콘·기호 판은 닫아 둠 → 다음에 열면 늘 글자 자판부터 (루비로사 2026-11-10)
        if (layer == Layer.EMOJI || layer == Layer.SYM) { layer = if (emojiFrom == Layer.EN && layer == Layer.EMOJI) Layer.EN else Layer.KO; shift = false; if (::content.isInitialized) showKeys() }
        stopRecognizer()
        hangul.clear()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopRecognizer()
        unmuteBeep()
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
        keyActions.clear()
        if (hwKeyboard()) { content.addView(hwHint()); return }
        val keys = when (layer) {
            Layer.KO -> if (prefs.keyboardLayout == "cji") cheonjiin() else qwerty(KO_ROWS, KO_SHIFT)
            Layer.EN -> qwerty(EN_ROWS, null)
            Layer.SYM -> symbols()
            Layer.EMOJI -> emojis()
        }
        content.addView(keys)
        // 누른 키가 살짝 커질 때 옆·위로 잘리지 않게
        unclip(keys); content.clipChildren = false; content.clipToPadding = false
        content.post {
            val root = content.parent as? View ?: return@post
            val avail = root.height - root.paddingBottom - content.top
            android.util.Log.i("talkhelper-kb", "size root=${root.height} pad=${root.paddingBottom} avail=$avail keys=${keys.height} keyH=$keyH shrink=$keyShrinkDp")
            // 창이 키보드보다 작아 맨 아래 줄이 잘리면 → 키를 조금 낮춰 다시 그림 (줄 수로 나눠 한 번에)
            val over = keys.height - avail
            if (over > 0 && avail > 0 && keyShrinkDp < 16 && keys.height > 0) {
                val rows = if (layer == Layer.EMOJI) 4 else (keys as? ViewGroup)?.let { countRows(it) }?.coerceAtLeast(1) ?: 4
                keyShrinkDp += (over / resources.displayMetrics.density / rows).toInt() + 1
                showKeys()
            }
        }
    }

    /** 자판 안의 키 줄 수 (가로 줄 LinearLayout 중 키가 든 것) */
    private fun countRows(g: ViewGroup): Int {
        var n = 0
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is LinearLayout && c.orientation == LinearLayout.HORIZONTAL && (0 until c.childCount).any { keyActions.containsKey(c.getChildAt(it)) }) n++
            else if (c is ViewGroup) n += countRows(c)
        }
        return n
    }

    // ───────── 블루투스(실물) 키보드 ─────────
    // 실물 키보드를 붙이면 안드로이드가 화면 키보드를 숨겨 🎤 줄까지 사라지고, 한글도 안 쳐졌음 (신부님 2026-11-10)
    // → 화면에는 🎤 줄과 안내 한 줄만 두고, 실물 키 입력을 두벌식 한글로 조합함

    /** 실물 키보드에서 지금 한글로 치는지 (한/영 키 · Shift+Space · 오른쪽 Alt 로 바꿈) */
    private var hwKorean = true

    private fun hwKeyboard(): Boolean {
        // 휴대폰 설정 '실제 키보드 사용 중에 화면 키보드 표시'를 켰으면 화면 자판 그대로
        val showSoft = runCatching { android.provider.Settings.Secure.getInt(contentResolver, "show_ime_with_hard_keyboard", 0) == 1 }.getOrDefault(false)
        if (showSoft) return false
        val c = resources.configuration
        return c.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            c.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /** 가로 화면에서도 앱 화면을 가리는 전체 화면 입력 모드는 쓰지 않음 */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** 실물 키보드가 있어도 🎤 줄은 늘 보이게 */
    override fun onEvaluateInputViewShown(): Boolean { super.onEvaluateInputViewShown(); return true }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        keyShrinkDp = 0   // 화면을 돌리거나 펴면 다시 원래 높이부터
        if (::content.isInitialized && !listening) showKeys()
    }

    private fun hwHint(): View = TextView(this).apply {
        text = "⌨ 블루투스 키보드로 치는 중 · 지금 ${if (hwKorean) "한글" else "영어"}\n한/영 바꾸기: 한/영 키 또는 Shift + 스페이스"
        gravity = Gravity.CENTER
        setTextColor(th.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(8), dp(8), dp(8), dp(10))
        setOnClickListener { toggleHwKorean() }
    }

    private fun toggleHwKorean() {
        finishComposing()
        hwKorean = !hwKorean
        if (::content.isInitialized && !listening) showKeys()
    }

    private val hwJamo: Map<Char, Char> by lazy {
        val m = HashMap<Char, Char>()
        EN_ROWS.forEachIndexed { r, row -> row.forEachIndexed { i, c -> m[c] = KO_ROWS[r][i] } }
        m
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentInputConnection == null || !hwKeyboard() && event.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) return super.onKeyDown(keyCode, event)
        // 한/영 바꾸기
        if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH || keyCode == KeyEvent.KEYCODE_KANA || keyCode == KeyEvent.KEYCODE_EISU ||
            keyCode == KeyEvent.KEYCODE_ALT_RIGHT || (keyCode == KeyEvent.KEYCODE_SPACE && event.isShiftPressed)) {
            if (event.repeatCount == 0) toggleHwKorean()
            return true
        }
        // Ctrl·Alt·Win 과 함께 누른 키(복사·붙여넣기 등)는 앱에 그대로
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) { finishComposing(); return super.onKeyDown(keyCode, event) }
        if (hwKorean && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val c = 'a' + (keyCode - KeyEvent.KEYCODE_A)
            var j = hwJamo[c] ?: return super.onKeyDown(keyCode, event)
            if (event.isShiftPressed) j = KO_SHIFT[j] ?: j
            lastCjiKey = null
            typeChar(j, true)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL && hangul.isComposing) { backspace(); return true }
        // 그 밖의 키(스페이스·엔터·숫자·화살표…)는 조합을 끝내고 앱에 그대로
        if (!KeyEvent.isModifierKey(keyCode)) finishComposing()
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH || keyCode == KeyEvent.KEYCODE_KANA || keyCode == KeyEvent.KEYCODE_EISU ||
            keyCode == KeyEvent.KEYCODE_ALT_RIGHT) return true
        if (hwKorean && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 키 누름 진동 */
    private fun tap(v: View) {
        // 글자를 먼저 넣고 진동은 그다음 (진동 때문에 글자가 늦게 뜨지 않게)
        Sounds.buzz(this, 8, 40)
    }

    /** 지금 자판의 키와 그 동작 (빈틈·겹친 손가락을 가장 가까운 키로 보내려고) */
    private val keyActions = HashMap<View, () -> Unit>()

    /** 화면 좌표 (x, y) 에 가장 가까운 키를 누름. 키 사이 틈이면 가까운 키로 (가운데 큰 빈칸은 제외) */
    private fun pressAt(x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        var best: View? = null; var bestD = Float.MAX_VALUE
        for (k in keyActions.keys) {
            if (!k.isAttachedToWindow || k.visibility != View.VISIBLE || inScrollView(k)) continue
            val parent = k.parent as? View ?: continue
            parent.getLocationOnScreen(loc)
            val l = loc[0] + k.left; val t = loc[1] + k.top; val r = loc[0] + k.right; val b = loc[1] + k.bottom
            val dx = if (x < l) l - x else if (x > r) x - r else 0f
            val dy = if (y < t) t - y else if (y > b) y - b else 0f
            val d = maxOf(dx, dy)
            if (d < bestD) { bestD = d; best = k }
        }
        val k = best ?: return false
        // 키 사이 틈은 가까운 키로 (넓은 화면은 틈이 넓어 조금 더 멀리까지). 가운데 큰 빈칸 한가운데는 그대로 빈칸
        if (bestD > dp(if (wide) 20 else 12)) return false
        keyActions[k]?.invoke()
        tap(k)
        pop(k, true); Bg.main.postDelayed({ pop(k, false) }, 110)
        return true
    }

    private fun pressPointer(v: View, e: MotionEvent) {
        val i = e.actionIndex
        val x: Float; val y: Float
        if (android.os.Build.VERSION.SDK_INT >= 29) { x = e.getRawX(i); y = e.getRawY(i) }
        else { val loc = IntArray(2); v.getLocationOnScreen(loc); x = loc[0] + e.getX(i); y = loc[1] + e.getY(i) }
        pressAt(x, y)
    }

    private fun inScrollView(v: View): Boolean {
        var p = v.parent
        while (p is View) {
            if (p is ScrollView || p is HorizontalScrollView) return true
            p = p.parent
        }
        return false
    }

    private fun unclip(v: View) {
        if (v !is ViewGroup) return
        v.clipChildren = false; v.clipToPadding = false
        for (i in 0 until v.childCount) unclip(v.getChildAt(i))
    }

    /** 누르는 동안 키가 살짝 커짐 → 지금 어느 키를 누르는지 알기 쉽게 (신부님 요청 2026-11-10) */
    private fun pop(v: View, down: Boolean) {
        v.animate().cancel()
        if (down) {
            // 움직임 없이 그 자리에서 바로 커짐 (기다림 0)
            v.scaleX = 1.18f; v.scaleY = 1.18f; v.translationZ = dp(6).toFloat()
        } else {
            v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setStartDelay(0).setDuration(90).start()
        }
    }

    /** 키 하나 */
    @SuppressLint("ClickableViewAccessibility")
    private fun key(label: String, special: Boolean = false, repeat: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(th.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 2) keyTextSp - 5 else keyTextSp)
        // 얇은 테두리로 키 경계를 또렷하게 (옆 키를 잘못 누르는 일 줄이기)
        background = Ui.rounded(this@KeyboardService, if (special) th.special else th.key, 8, th.edge)
        elevation = dp(1).toFloat()
        Ui.iconize(this)
        keyActions[this] = onClick
        if (repeat) {
            // 지우기: 꾹 누르고 있으면 계속 지움
            val rep = object : Runnable { override fun run() { onClick(); Bg.main.postDelayed(this, 70) } }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; pop(v, true); Sounds.buzz(this@KeyboardService, 8, 40); onClick(); Bg.main.postDelayed(rep, 450) }
                    MotionEvent.ACTION_POINTER_DOWN -> pressPointer(v, e)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; pop(v, false); Bg.main.removeCallbacks(rep) }
                }
                true
            }
        } else {
            // 손가락이 닿는 순간 바로 입력 (예전엔 손을 뗄 때 입력되고, 손가락이 조금만 미끄러져도 눌림이 취소됐음 — 신부님 2026-11-11 '반응이 느림').
            // 이모티콘 판처럼 밀어서 넘기는 칸 안의 키만 예전처럼 뗄 때 (밀다가 잘못 들어가지 않게)
            setOnTouchListener { v, e ->
                val inScroll = inScrollView(v)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pop(v, true)
                        if (!inScroll) { v.isPressed = true; onClick(); tap(v) }
                    }
                    // 이 키를 누른 채 다른 손가락이 닿았는데 그 손가락도 이 키로 온 경우(키가 커져 옆을 덮음 등) → 손가락 자리의 키로
                    MotionEvent.ACTION_POINTER_DOWN -> if (!inScroll) pressPointer(v, e)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pop(v, false); if (!inScroll) v.isPressed = false }
                }
                !inScroll   // 밀어 넘기는 칸이 아니면 여기서 끝 (click 두 번 안 되게)
            }
            // 화면 읽기(톡백)의 두 번 누르기 등은 click 으로 옴
            setOnClickListener { Sounds.buzz(this@KeyboardService, 8, 40); onClick() }
        }
    }

    private fun keyLp(w: Float) = LinearLayout.LayoutParams(0, keyH, w).apply {
        // 키 사이를 조금 더 띄움 (신부님 2026-11-11)
        val h = if (wide) dp(KEY_GAP_WIDE) else dp(3); val v = if (wide) dp(5) else dp(4)
        setMargins(h, v, h, v)
    }
    private fun gap(w: Float): Pair<View, Float> = View(this) to w
    /** 넓은 화면에서 자음·모음 사이 빈칸 (좁은 화면은 없음) */
    private fun mid(): List<Pair<View, Float>> = if (wide) listOf(gap(MID_GAP)) else emptyList()
    /** 띄어쓰기 폭: 넓은 화면에선 가운데 틈만큼 더 길게 (양손 엄지 어디서나 닿게, 신부님 2026-11-12) */
    private fun spaceW(base: Float): Float = if (wide) base + MID_GAP else base

    /**
     * 넓은 화면 두벌식: 키가 정사각형에 가깝도록 전체 폭을 줄임 →
     * 바깥 키(ㅂ·ㅁ·ㅋ·ㅔ·ㅣ·ㅡ)는 안쪽으로, 가운데 틈 옆 키(ㅅ·ㅎ·ㅍ·ㅛ·ㅗ·ㅠ)는 바깥으로 (신부님 2026-11-11)
     */
    private fun squareWidthDp(): Int {
        val unit = keyHdp * 1.12f + 2 * KEY_GAP_WIDE   // 키 폭 ≈ 높이 (살짝 넓게)
        return (unit * (10 + MID_GAP)).toInt() + 8
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun row(items: List<Pair<View, Float>>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        // 글자 기준선 맞춤을 끔: 그림(😊·🔎) 키만 위로 올라가 줄이 어긋나던 것
        isBaselineAligned = false
        items.forEach { (v, w) -> addView(v, keyLp(w)) }
        // 키 사이 틈을 눌러도 가장 가까운 키로 (틈 때문에 눌림이 빠지지 않게)
        setOnTouchListener { rv, e ->
            if (inScrollView(rv)) return@setOnTouchListener false   // 이모티콘 판은 밀어 넘기기 그대로
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pressPointer(rv, e)
            }
            true
        }
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
            // 아래 줄: 띄어쓰기 말고는 조금 줄이고 띄어쓰기를 넓게 (신부님 2026-11-12)
            key("!#1", special = true) { toSymbols() } to BOTTOM_W[0],
            key("한/영", special = true) { if (ko) toEnglish() else toKorean() } to BOTTOM_W[1],
            emojiKey() to BOTTOM_W[2],
            key(",", special = true) { commitPlain(",") } to BOTTOM_W[3],
            key("띄어쓰기") { space() } to spaceW(BOTTOM_W[4]),
            key(".", special = true) { commitPlain(".") } to BOTTOM_W[5],
            enterKey() to BOTTOM_W[6],
        )
        return column(listOf(
            // 폴드처럼 넓으면 왼쪽(자음)·오른쪽(모음) 사이를 조금 띄움. 모든 줄에 같은 폭이라 키 줄이 어긋나지 않음
            row(r1.take(5) + mid() + r1.drop(5)),
            row(listOf(gap(0.5f)) + r2.take(5) + mid() + r2.drop(5) + listOf(gap(0.5f))),
            row(listOf(shiftKey) + r3.take(4) + mid() + r3.drop(4) + listOf(back)),
            row(bottom),
        ), maxWidthDp = if (wide) squareWidthDp() else null)
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
                setTextColor(th.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = Ui.rounded(this@KeyboardService, th.key, 20, th.edge)
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
    private var emojiTab = 0
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
        // 넓은 화면은 한글·영어 자판과 같은 폭·같은 자리 (신부님 2026-11-11 '모든 자판 위치 통일')
        val panelW = if (wide) minOf(squareWidthDp(), screenWdp) else screenWdp
        val cols = ((panelW - 16) / 40).coerceAtLeast(8)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), 0) }
        fun fill(list: List<String>) {
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
        // 위 탭으로 무리를 나눔: 자주 쓰는(최근 쓴 것 먼저)·표정·손·마음·신앙·자연·음식·사물 (신부님 요청 2026-11-11)
        val tabs = listOf("🕘" to "자주") + EMOJI_GROUPS.map { (t, l) -> l.substringBefore(' ') to t }
        emojiTab = emojiTab.coerceIn(0, tabs.lastIndex)
        if (emojiTab == 0) fill((recentEmojis() + FREQUENT.split(' ')).distinct())
        else fill(EMOJI_GROUPS[emojiTab - 1].second.split(' '))
        val scroll = ScrollView(this).apply { addView(grid) }
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(2), dp(4), 0) }
        tabs.forEachIndexed { i, (icon, label) ->
            val on = i == emojiTab
            tabBar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (on) th.key else android.graphics.Color.TRANSPARENT) }
                addView(TextView(this@KeyboardService).apply { text = icon; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) })
                addView(TextView(this@KeyboardService).apply {
                    text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(if (on) th.text else th.muted); if (on) setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                contentDescription = "$label 이모티콘"
                setOnClickListener { Sounds.buzz(this@KeyboardService, 8, 40); emojiTab = i; showKeys() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(1), 0, dp(1), 0) })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 높이도 글자 자판과 똑같이 (탭 + 이모티콘 칸 = 키 세 줄, 아래 줄 = 키 한 줄) → 자판을 바꿔도 키보드가 들썩이지 않음
        val rowH = keyH + 2 * dp(if (wide) 5 else 4)
        val tabH = dp(46)
        col.addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tabH))
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH * 3 - tabH))
        // 아래 줄: 글자 자판 아래 줄과 같은 높이
        val bottom = row(listOf(
            // 글자 자판 아래 줄과 같은 칸 폭: 돌아가기(!#1 자리)·기호(한/영 자리)·지우기(😊+쉼표 자리)·띄어쓰기·점·줄바꿈
            key(if (emojiFrom == Layer.EN) "ABC" else "가", special = true) { layer = if (emojiFrom == Layer.EMOJI) Layer.KO else emojiFrom; showKeys() } to BOTTOM_W[0],
            key("!#1", special = true) { toSymbols() } to BOTTOM_W[1],
            key("⌫", special = true, repeat = true) { backspace() } to BOTTOM_W[2] + BOTTOM_W[3],
            key("띄어쓰기") { space() } to spaceW(BOTTOM_W[4]),
            key(".", special = true) { commitPlain(".") } to BOTTOM_W[5],
            enterKey() to BOTTOM_W[6],
        ))
        col.addView(column(listOf(bottom), maxWidthDp = if (wide) squareWidthDp() else null))   // 글자 자판과 같은 폭 (예전 520dp 제한 때문에 조금 넓은 화면에서 아래 줄이 안쪽으로 들어갔음)
        if (!wide || screenWdp <= panelW + 16) return col
        return FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(dp(panelW), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun symbols(): View {
        // 한글 자판과 같은 틀: ⌫ 는 셋째 줄 오른쪽 끝, 띄어쓰기·완료도 같은 자리 (신부님 요청 2026-11-11)
        // 자주 쓰는 ! ? ~ 는 엄지가 닿는 셋째 줄, . , 는 띄어쓰기 옆
        fun k(ch: String, w: Float = 1f) = key(ch) { commitPlain(ch) } to w
        fun sk(ch: String, w: Float) = key(ch, special = true) { commitPlain(ch) } to w
        val r1 = "1234567890".map { k("$it") }
        val r2 = "-/:;()₩&@\"".map { k("$it") }
        val r3 = "!?~'^*+".map { k("$it") }
        val back = key("⌫", special = true, repeat = true) { backspace() } to 1.5f
        val bottom = listOf(
            // 한글 자판 아래 줄과 칸마다 같은 폭 → 자판을 바꿔도 띄어쓰기·옆 키 자리가 그대로 (신부님 2026-11-12)
            key("가", special = true) { toKorean() } to BOTTOM_W[0],
            sk("#", BOTTOM_W[1]),
            sk("=", BOTTOM_W[2]),
            sk(",", BOTTOM_W[3]),
            key("띄어쓰기") { space() } to spaceW(BOTTOM_W[4]),
            sk(".", BOTTOM_W[5]),
            enterKey() to BOTTOM_W[6],
        )
        return column(listOf(
            row(r1.take(5) + mid() + r1.drop(5)),
            row(r2.take(5) + mid() + r2.drop(5)),
            row(listOf(sk("%", 1.5f)) + r3.take(4) + mid() + r3.drop(4) + listOf(back)),
            row(bottom),
        ), maxWidthDp = if (wide) squareWidthDp() else null)
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
        if (!sel.isNullOrEmpty()) { ic.commitText("", 1); return }
        // 이모티콘(😊·❤️·👨‍👩‍👧)은 글자 칸 2~여러 개 → 한 칸만 지우면 반쪽이 남아 '�'로 보였음 (루비로사 2026-11-11)
        // 보이는 글자 하나(문자소)만큼 지움
        val before = ic.getTextBeforeCursor(32, 0)?.toString().orEmpty()
        ic.deleteSurroundingText(lastGraphemeLength(before), 0)
    }

    /** 글 끝의 보이는 글자 하나가 차지하는 칸 수 (이모티콘·결합 문자 포함) */
    private fun lastGraphemeLength(text: String): Int {
        if (text.isEmpty()) return 1
        val n = runCatching {
            val it = android.icu.text.BreakIterator.getCharacterInstance()
            it.setText(text)
            val end = it.last()
            end - it.previous()
        }.getOrDefault(0)
        if (n > 0) return n
        // 예비: 서로 짝인 반쪽 글자(서로게이트)는 둘을 함께
        return if (text.length >= 2 && Character.isLowSurrogate(text.last()) && Character.isHighSurrogate(text[text.length - 2])) 2 else 1
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
            // 눈에 잘 띄게: 진한 바탕 + 큰 글씨 (신부님 2026-11-12 '오른쪽 밑 화살표가 잘 안 보여')
            val dark = th.id == "dark"
            background = Ui.rounded(this@KeyboardService, if (dark) Ui.YELLOW else Ui.BROWN, 8, null)
            setTextColor(if (dark) Ui.BROWN else Ui.YELLOW)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (label == "↵") setTextSize(TypedValue.COMPLEX_UNIT_SP, keyTextSp + 6)
            Ui.iconize(this)
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
        toneButton.text = "${mode.emoji} ▾"
        toneButton.contentDescription = "말투: ${mode.label}"
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
                // 그림만 크게 (신부님 요청 2026-11-10). 이름은 화면 읽기·위 안내 글로
                text = m.emoji
                contentDescription = "${m.label} 말투"
                gravity = Gravity.CENTER
                minWidth = dp(52)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
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
    /** '내 말투'에 내가 보낸 말을 예시로 쓸지 처음 한 번 물음. 고르면 [then] 계속 */
    private fun askStyleConsent(then: () -> Unit) {
        showInfo(MyStyle.CONSENT_TEXT)
        infoRow.tag = "consent"
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER
            setTextColor(if (primary) Ui.CARD else Ui.BROWN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Ui.rounded(this@KeyboardService, if (primary) Ui.BROWN else Ui.CARD, 14, if (primary) null else Ui.LINE)
            setOnClickListener { onClick() }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }) }
        b("동의하고 쓰기", true) { prefs.myStyleConsent = true; then() }
        b("예시 없이 쓰기", false) { prefs.myStyleConsent = false; then() }
        infoRow.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

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

    private fun openAppSettings() {
        stopRecognizer()
        finishComposing()
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_SETTINGS, true))
        }
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
        // 어두운 키보드에서도 읽히게: 기본 글자색은 키보드 색을 따름
        infoText.setTextColor(if (color == Ui.BROWN) th.text else color)
        Ui.iconize(infoText)
        while (infoRow.childCount > 1) infoRow.removeViewAt(1)
        if (tones) infoRow.addView(toneChips(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        infoRow.tag = if (tones) "tones" else null
        infoRow.visibility = View.VISIBLE
    }

    private fun hideInfo() { if (::infoRow.isInitialized) infoRow.visibility = View.GONE }

    private fun switchTone(m: Mode) {
        if (m == Mode.MINE && prefs.myStyleConsent == null) { askStyleConsent { switchTone(m) }; return }
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
        box.add(Ui.text(this, "잠깐만요…", 19f, bold = true).apply { gravity = Gravity.CENTER; tag = "readyTitle" }, 0)
        box.add(Ui.text(this, "‘띵’ 소리가 나면 말씀하세요", 15f, color = Ui.MUTED).apply { gravity = Gravity.CENTER; tag = "heard" }, 6)
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
        listenStartedAt = System.currentTimeMillis(); lastHeardAt = listenStartedAt; lastVoiceAt = 0L; errStreak = 0
        listening = true
        searchMode = search
        hideInfo()
        showListeningPanel(search)
        // 인식기를 바로 켜고, 정말 들을 준비가 된 순간(onReadyForSpeech)에 신호음·진동으로 '말하세요'를 알림.
        // 예전엔 신호음 뒤 0.7초를 기다렸다 켜서, 신호음을 듣자마자 한 앞 낱말이 잘렸음 (신부님 2026-11-10)
        waitingReady = true
        listen()
    }

    /** 첫 토막에서 인식기가 들을 준비가 됐는지 기다리는 중 */
    private var waitingReady = false
    private val readyFallback = Runnable { if (listening && waitingReady) onReady() }

    private fun onReady() {
        if (!waitingReady) return
        waitingReady = false
        Bg.main.removeCallbacks(readyFallback)
        // 인식기 '띵'이 끝난 뒤 소리를 되돌리고 우리 부드러운 신호음
        Bg.main.removeCallbacks(unmuteTask)
        // 구글 '띵'은 준비된 순간에 0.3~0.4초 울림 → 다 끝난 뒤(0.5초)에 소리를 되돌려야 겹치지 않음 (신부님 2026-11-11 '소리 두 개')
        Bg.main.postDelayed({
            val wasMuted = beepMuted
            unmuteBeep()
            if (!listening) return@postDelayed
            // 소리를 못 끈 때(블루투스·이어폰·음악 재생 중)는 구글 '띵'이 이미 울렸으니 우리 소리는 내지 않음 (진동만)
            if (wasMuted) {
                Sounds.listenStart(this)
                // 우리 시작 소리가 끝나면 다시 꺼 둠 → 듣는 동안 인식기 '삑' 소리가 안 남
                Bg.main.postDelayed({ if (listening) muteBeep() }, 700)
            }
            Sounds.buzz(this, 70)
            heardView()?.takeIf { heard.isBlank() && partial.isBlank() }?.text = if (searchMode) "예) 모임 날짜 언제로 정했지?" else "쉬어 가며 말해도 돼요. 다 말하면 아래를 누르거나 4초쯤 기다리세요"
            content.findViewWithTag<TextView>("readyTitle")?.let {
                it.text = if (searchMode) "🔎 무엇을 찾을까요?" else "🎤 편하게 말하세요"; Ui.iconize(it)
            }
        }, 500)
        Bg.main.removeCallbacks(pulse); Bg.main.postDelayed(pulse, 3000)
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
            // 한 토막을 길게 들어 다시 켜는 횟수를 줄임 (다시 켜는 사이의 말이 빠지고, 짧게 나누면 앞뒤 말로 맞히는 힘이 약해짐)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 구글 음성 인식은 켤 때마다 자기 '띵' 소리를 냄 → 이어 듣기로 여러 번 켜면 소리가 겹쳐 들렸음.
        // 켜는 순간만 잠깐 미디어·알림 소리를 끄고 0.6초 뒤 되돌림 (우리 부드러운 신호음만 들리게)
        muteBeep()
        runCatching { r.startListening(i) }.onFailure { unmuteBeep(); stopRecognizer(); showKeys(); showInfo("음성 인식을 시작하지 못했어요.", color = Ui.RED) }
        Bg.main.removeCallbacks(unmuteTask)
        // 이어 듣기로 다시 켤 때는 소리를 끈 채로 둠 — 예전엔 0.6초 뒤 되돌려서, 토막이 끝나고 다시 켤 때마다
        // 구글 인식기의 '삑' 소리가 중간중간 들렸음 (신부님 2026-11-12). 다 듣고 끝낼 때(stopRecognizer) 되돌림
        if (waitingReady) Bg.main.postDelayed(readyFallback, 2000)   // 준비 알림이 안 오는 휴대폰
    }

    private var beepMuted = false
    private val unmuteTask = Runnable { unmuteBeep() }
    private val BEEP_STREAMS = intArrayOf(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_SYSTEM)
    /**
     * 인식기 '띵'을 가리려고 소리를 잠깐 끔 — 휴대폰 스피커로 듣고 음악이 안 나올 때만.
     * 차 블루투스·이어폰에서는 끄지 않음: 블루투스는 끈 소리가 제대로 안 돌아와 차 음량이 0이 됐음 (신부님 2026-11-11)
     */
    private fun muteBeep() {
        if (beepMuted || !listening) return
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        if (am.isMusicActive || externalAudio(am)) return
        for (st in BEEP_STREAMS) runCatching { am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_MUTE, 0) }
        beepMuted = true
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("beepMuted", true).apply()
    }
    private fun unmuteBeep() {
        if (!beepMuted) return
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        for (st in BEEP_STREAMS) runCatching { am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0) }
        beepMuted = false
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("beepMuted", false).apply()
    }

    /** 블루투스(차·이어폰)·유선 이어폰·USB 로 소리가 나가는지 */
    private fun externalAudio(am: android.media.AudioManager): Boolean = runCatching {
        val ext = setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_HEARING_AID, 26 /* BLE_HEADSET */, 27 /* BLE_SPEAKER */, 30 /* BLE_BROADCAST */,
        )
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in ext }
    }.getOrDefault(true)

    private var listenStartedAt = 0L
    private var lastHeardAt = 0L
    /** 마이크에 목소리가 들어온 마지막 때 (글자로 아직 안 바뀌었어도) */
    private var lastVoiceAt = 0L
    /** 글자 없이 연달아 난 오류 수 — 다시 켜는 간격을 조금씩 늘림 */
    private var errStreak = 0

    /** 한 토막이 끝난 뒤 이어서 듣기. 4초 넘게 조용하면 그때까지 한 말로 다듬고, 8초 동안 아무 말도 없으면 멈춤 */
    private fun keepListening(delayMs: Long = 60) {
        val now = System.currentTimeMillis()
        val spoken = heard.isNotBlank()
        when {
            spoken && now - lastHeardAt >= 4000 -> finishSpeech()
            // 목소리가 들어오는 동안은 기다림 — 예전엔 말하는 중인데도 글자가 늦으면 10초에 끊고 '듣지 못했어요'라고 했음 (신부님 2026-11-12)
            !spoken && now - maxOf(listenStartedAt, lastVoiceAt) >= 10000 -> { stopRecognizer(); showKeys(); showInfo("말씀을 듣지 못했어요. 다시 눌러 주세요.") }
            !spoken && now - listenStartedAt >= 30000 -> { stopRecognizer(); showKeys(); showInfo("잘 알아듣지 못했어요. 휴대폰을 입 가까이 대고 다시 눌러 주세요.") }
            // 인식기가 막 끝난 직후 바로 켜면 '아직 바쁨' 오류가 나 헛돌 수 있어 아주 잠깐 쉬고 다시 들음
            else -> Bg.main.postDelayed({ if (listening) listen() }, delayMs)
        }
    }

    /** 인식기를 새로 만듦 ('바쁨'·연결 끊김 오류 뒤) */
    private fun resetRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun stopRecognizer() {
        // 듣는 중이었을 때만 끄는 소리를 막음 (예전엔 키보드가 내려갈 때마다 소리를 껐다 켰음)
        if (listening) muteBeep()
        listening = false
        waitingReady = false
        Bg.main.removeCallbacks(readyFallback)
        Bg.main.removeCallbacks(pulse)
        runCatching { recognizer?.cancel() }
        Bg.main.removeCallbacks(unmuteTask)
        Bg.main.postDelayed(unmuteTask, 250)
    }

    private fun finishSpeech() {
        if (!listening) return
        if (partial.isNotBlank()) { heard.append(' ').append(partial); partial = "" }
        stopRecognizer()
        showKeys()
        val spoken = EndSignal.strip(heard.toString())
        if (spoken.isBlank()) { showInfo("말씀을 듣지 못했어요. 다시 눌러 주세요."); return }
        Bg.main.postDelayed({ unmuteBeep(); Sounds.listenDone(this) }, 300)   // 인식기 끄는 소리를 막은 뒤에 우리 끝 소리
        Sounds.buzz(this, 90)
        if (searchMode) { searchMode = false; searchAll(spoken); return }
        lastSpoken = spoken
        cache.clear()
        // 방금 넣은 글이 그대로 있으면(보내지 않음) 새로 말한 내용으로 바꿔 넣음 — put() 이 지우고 넣음 (신부님 요청 2026-11-10)
        rewrite(mode)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (listening) onReady() }
        override fun onBeginningOfSpeech() { lastVoiceAt = System.currentTimeMillis(); if (listening && waitingReady) onReady() }
        override fun onRmsChanged(rmsdB: Float) { if (rmsdB > 5f) lastVoiceAt = System.currentTimeMillis() }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(b: Bundle?) {
            if (!listening) return
            partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (partial.isNotBlank()) { lastHeardAt = System.currentTimeMillis(); lastVoiceAt = lastHeardAt; errStreak = 0 }
            val now = (heard.toString() + " " + partial).trim()
            if (now.isNotBlank()) heardView()?.let { it.text = "“$now”"; it.setTextColor(Ui.BROWN) }
        }
        override fun onResults(b: Bundle?) {
            if (!listening) return
            val best = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (best.isNotBlank()) { heard.append(' ').append(best); lastHeardAt = System.currentTimeMillis(); errStreak = 0 }
            partial = ""
            heardView()?.let { v -> heard.toString().trim().takeIf { it.isNotBlank() }?.let { v.text = "“$it”"; v.setTextColor(Ui.BROWN) } }
            // 음성 인식은 말이 잠깐만 끊겨도 한 토막을 끝냄 → 바로 다듬지 않고 이어서 들음.
            // 끝: '듣는 중 · 누르면 끝' 누르기, "~라고 보내 줘", 또는 4초 넘게 조용함. 찾기는 한 문장이면 끝
            when {
                searchMode && heard.isNotBlank() -> finishSpeech()
                EndSignal.isEnd(heard.toString()) -> finishSpeech()
                else -> keepListening()
            }
        }
        override fun onError(error: Int) {
            if (!listening) return
            android.util.Log.i("talkhelper-kb", "speech error=$error streak=$errStreak")
            if (error != SpeechRecognizer.ERROR_NETWORK && error != SpeechRecognizer.ERROR_NETWORK_TIMEOUT && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                // 말이 없어서 끝난 것(조용함·못 알아들음)이면 계속 들음
                if (partial.isNotBlank()) { heard.append(' ').append(partial); partial = "" }
                if (searchMode && heard.isNotBlank()) { finishSpeech(); return }
                errStreak++
                // 바쁨(8)·클라이언트(5)·서버 끊김(11)은 인식기가 꼬인 것 → 새로 만들고 조금 쉬었다 다시.
                // 예전엔 곧바로 다시 켜다 같은 오류로 헛돌아, 말해도 하나도 못 듣고 10초 뒤 '듣지 못했어요'가 떴음
                val stuck = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT || error == 11
                if (stuck || errStreak >= 3) resetRecognizer()
                keepListening(if (stuck) 250L + 150L * errStreak.coerceAtMost(4) else 60L); return
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
        if (m == Mode.MINE && prefs.myStyleConsent == null) { askStyleConsent { rewrite(m) }; return }
        val claude = ClaudeProvider.get(this) ?: return
        val spoken = lastSpoken
        val id = ++job
        val useStyle = m == Mode.MINE && prefs.myStyleConsent == true
        showInfo("${m.emoji} '${m.label}' 말투로 다듬는 중…")
        Bg.run({
            // 내 말투(동의함): 저장한 대화에서 내가 보낸 말만 예시로 (다른 사람 말은 넣지 않음)
            val style = if (useStyle) runCatching { ChatStore.myStyleAll(this) }.getOrDefault(emptyList()) else emptyList()
            claude.rewrite(m, spoken, null, prefs.myName, prefs.myRole, style)
        }) { r ->
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
            setTextColor(th.muted)
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
        private const val FREQUENT = "😊 😂 🥰 😍 👍 🙏 ❤️ 👏 🎉 😭 😅 🤗 😢 🙂 😆 💕"
        /** 탭 무리: 첫 이모티콘이 탭 그림 */
        private val EMOJI_GROUPS = listOf(
            "표정" to "😀 😃 😄 😁 😆 😅 😂 🤣 😊 🙂 😉 😌 😍 🥰 😘 🥹 😇 🤗 🤭 🫢 🤔 😮 😲 😳 🥲 😢 😭 😔 😞 😣 😤 😡 😴 🤒 😷 😎 🥳 😋 😜 🤩",
            "손" to "👍 👋 🙌 👏 👐 🤝 ✌️ 👌 🤞 💪 👆 👉 ✋ 🙆 🙇 🙋 🫶 ✊ 👎 🙅 🤷 🤦",
            "마음" to "❤️ 🧡 💛 💚 💙 💜 🤍 🖤 💖 💗 💓 💝 💞 💕 💌 💘 ✨ 🌟 ⭐ 💯 🔥 💤",
            "신앙" to "🙏 ✝️ ⛪ 📿 🕊️ 🕯️ 📖 🔔 👼 🌹 🎄 ⭐ 🍞 🍷 💒 🎶",
            "자연" to "🌸 🌷 🌹 🌻 💐 🍀 🌳 🍁 🌈 ☀️ 🌤️ 🌧️ ☔ ❄️ 🌙 ⭐ 🐶 🐱 🐰 🐻 🐥 🦋 🐟 🐾",
            "음식" to "🍚 🍜 🍲 🍙 🍰 🎂 🍞 🍎 🍊 🍓 🍉 🍇 ☕ 🍵 🍺 🍷 🥤 🍗 🍕 🍔 🍦 🍫",
            "사물" to "🎁 🎉 🎊 🎈 🏠 🚗 🚌 ✈️ 📱 💻 📷 📚 ✏️ 💰 💊 🏥 ⏰ 📅 🎵 ⚽ 👓 👕",
        )
        private val TOOL_KEYS = listOf("voice", "search", "tone", "layout", "settings")
        private const val KEY_GAP_WIDE = 6
        /** 넓은 화면에서 자음·모음 사이 빈칸 (키 몇 개 폭) */
        private const val MID_GAP = 2.6f
        /** 모든 자판 아래 줄 칸 폭 (한글·영어·기호·이모티콘이 같은 자리) */
        private val BOTTOM_W = floatArrayOf(1.2f, 1.25f, 0.9f, 0.75f, 4.55f, 0.75f, 1.25f)
        private val KO_ROWS = listOf("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ")
        private val KO_SHIFT = mapOf('ㅂ' to 'ㅃ', 'ㅈ' to 'ㅉ', 'ㄷ' to 'ㄸ', 'ㄱ' to 'ㄲ', 'ㅅ' to 'ㅆ', 'ㅐ' to 'ㅒ', 'ㅔ' to 'ㅖ')
        private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ"
        private const val CONSONANTS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    }
}
