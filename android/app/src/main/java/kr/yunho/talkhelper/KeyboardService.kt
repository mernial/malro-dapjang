package kr.yunho.talkhelper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kr.yunho.talkhelper.Ui.add
import kr.yunho.talkhelper.core.EndSignal
import kr.yunho.talkhelper.core.HelperException
import kr.yunho.talkhelper.core.Mode

/**
 * '말로 답장 키보드' (안드로이드 표준 입력 방법).
 * 🎤를 누르고 말하면 AI 가 고른 말투로 다듬어 **입력칸에 글자로 넣음**. 보내기는 사용자가 직접 누름.
 * 카톡 등 다른 앱의 화면을 읽거나 버튼을 누르지 않음 (카카오 안내: 비공식 자동 조작 금지 → 키보드 음성 입력은 가능).
 * 넣은 뒤 말투 칩을 누르면 방금 넣은 글을 그 말투로 바꿔 넣음.
 */
class KeyboardService : InputMethodService() {

    private lateinit var prefs: Prefs
    private var mode = Mode.POLITE

    private lateinit var micButton: TextView
    private lateinit var searchButton: TextView
    private lateinit var answerBox: View
    private lateinit var answerText: TextView
    /** 지금 듣는 것이 '찾기' 질문인지 */
    private var searchMode = false
    private lateinit var status: TextView
    private lateinit var chips: Map<Mode, TextView>

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
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

    override fun onCreateInputView(): View {
        val ctx = this
        mode = prefs.mode
        val root = Ui.vbox(ctx, 10).apply { setBackgroundColor(Ui.BG) }

        // 말투 칩 (누르면 다음부터 그 말투, 방금 넣은 글이 있으면 그 말투로 바꿔 넣음)
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = Ui.hbox(ctx)
        val map = LinkedHashMap<Mode, TextView>()
        for (m in Mode.values()) {
            val chip = Ui.text(ctx, "${m.emoji} ${m.label}", 15f, bold = true).apply {
                val ph = Ui.dp(ctx, 14); val pv = Ui.dp(ctx, 9); setPadding(ph, pv, ph, pv)
                setOnClickListener { switchTone(m) }
            }
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = Ui.dp(ctx, 6) })
            map[m] = chip
        }
        chips = map
        scroll.addView(row)
        root.add(scroll, 0)

        status = Ui.text(ctx, "🎤 누르고 편하게 말하세요. 다듬은 글이 입력칸에 들어가요.", 14f, color = Ui.MUTED).apply {
            maxLines = 3
        }
        root.add(status, 8)

        // 찾은 답 (찾기를 했을 때만 보임, 길면 안에서 넘겨 봄)
        answerText = TextView(ctx).apply {
            setTextColor(Ui.BROWN)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }
        val answerScroll = android.widget.ScrollView(ctx).apply { addView(answerText) }
        answerBox = Ui.vbox(ctx, 12, Ui.THEM).apply {
            visibility = View.GONE
            addView(answerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 170)))
            addView(Ui.text(ctx, "닫기", 15f, bold = true).apply {
                gravity = Gravity.CENTER
                val p = Ui.dp(ctx, 8); setPadding(p, p, p, p)
                setOnClickListener { answerBox.visibility = View.GONE }
            })
        }
        root.add(answerBox, 8)

        // 🎤 말하기 (다듬어 넣기) · 🔎 찾기 (내보내 둔 대화에서)
        val talk = Ui.hbox(ctx)
        micButton = Ui.text(ctx, "", 20f, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = Ui.dp(ctx, 64)
            setOnClickListener { if (listening && !searchMode) finishSpeech() else if (!listening) toggleMic(search = false) }
        }
        talk.add(micButton, weight = 2.2f)
        searchButton = Ui.text(ctx, "🔎 찾기", 18f, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = Ui.dp(ctx, 64)
            background = Ui.rounded(ctx, Ui.CARD, 18, Ui.BROWN)
            setOnClickListener { if (listening && searchMode) finishSpeech() else if (!listening) toggleMic(search = true) }
            Ui.iconize(this)
        }
        talk.add(searchButton, weight = 1f)
        root.add(talk, 8)

        // 아래 줄: 다른 키보드 · 지우기 · 줄바꿈
        val keys = Ui.hbox(ctx)
        fun key(label: String, weight: Float, onClick: () -> Unit) = Ui.text(ctx, label, 17f, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = Ui.dp(ctx, 50)
            background = Ui.rounded(ctx, Ui.CARD, 14, Ui.LINE)
            setOnClickListener { onClick() }
        }.also { keys.add(it, weight = weight) }
        key("⌨ 바꾸기", 1.4f) { otherKeyboard() }
        key("⌫", 1f) { backspace() }
        key("띄어쓰기", 1.4f) { currentInputConnection?.commitText(" ", 1); inserted = null }
        key("↵", 1f) { sendKey(KeyEvent.KEYCODE_ENTER); inserted = null }
        root.add(keys, 10)

        paint()
        resetMic()
        // 안드로이드 15+ 는 키보드도 화면 맨 아래(내비게이션 막대 밑)까지 그림 → 아래 줄이 가리지 않게 막대 높이만큼 띄움
        val base = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30)
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + bottom)
            insets
        }
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) { inserted = null; cache.clear(); lastSpoken = "" }
        mode = prefs.mode
        if (::chips.isInitialized) paint()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRecognizer()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopRecognizer()
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }

    // ───────── 화면 ─────────

    private fun paint() = chips.forEach { (m, chip) ->
        val on = m == mode
        chip.background = Ui.rounded(this, if (on) Ui.BROWN else Ui.CARD, 18, if (on) Ui.BROWN else Ui.LINE)
        chip.setTextColor(if (on) Ui.YELLOW else Ui.BROWN)
        chip.text = "${m.emoji} ${m.label}"
        Ui.iconize(chip)
    }

    private fun showStatus(s: String, color: Int = Ui.MUTED) {
        status.text = s
        status.setTextColor(color)
        Ui.iconize(status)
    }

    private fun resetMic() {
        listening = false
        Bg.main.removeCallbacks(pulse)
        micButton.text = "🎤 누르고 말하기"
        micButton.setTextColor(Ui.YELLOW)
        micButton.background = Ui.rounded(this, Ui.BROWN, 18)
        Ui.iconize(micButton)
        if (::searchButton.isInitialized) {
            searchButton.text = "🔎 찾기"
            searchButton.setTextColor(Ui.BROWN)
            searchButton.background = Ui.rounded(this, Ui.CARD, 18, Ui.BROWN)
            Ui.iconize(searchButton)
        }
    }

    // ───────── 키 ─────────

    private fun otherKeyboard() {
        stopRecognizer()
        // 바로 전에 쓰던 키보드로 (안 되면 키보드 고르기 창)
        val switched = runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
        if (!switched) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
        inserted = null
    }

    private fun sendKey(code: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ───────── 듣기 ─────────

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleMic(search: Boolean) {
        if (listening) { finishSpeech(); return }
        if (!hasMic()) {
            showStatus("마이크 허용이 필요해요. '${getString(R.string.app_name)}' 앱을 열어 마이크를 허용해 주세요.", Ui.RED)
            runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { showStatus("이 휴대폰에서 음성 인식을 쓸 수 없어요.", Ui.RED); return }
        if (ClaudeProvider.get(this) == null) { showStatus("아직 준비가 안 됐어요. 앱을 열어 설정을 마쳐 주세요.", Ui.RED); return }
        heard.clear(); partial = ""
        listening = true
        searchMode = search
        val b = if (search) searchButton else micButton
        b.text = if (search) "🎙️ 듣는 중" else "🎙️ 듣는 중… (누르면 끝)"
        b.setTextColor(Ui.CARD)
        b.background = Ui.rounded(this, Ui.GREEN, 18)
        Ui.iconize(b)
        if (search) answerBox.visibility = View.GONE
        showStatus(if (search) "🔎 무엇을 찾을까요? (예: 모임 날짜 언제로 정했지?)" else "듣고 있어요… 다 말하면 잠시 멈추거나 버튼을 누르세요")
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
        runCatching { r.startListening(i) }.onFailure { resetMic(); showStatus("음성 인식을 시작하지 못했어요.", Ui.RED) }
    }

    private fun stopRecognizer() {
        listening = false
        Bg.main.removeCallbacks(pulse)
        runCatching { recognizer?.cancel() }
    }

    private fun finishSpeech() {
        if (partial.isNotBlank()) { heard.append(' ').append(partial); partial = "" }
        stopRecognizer()
        resetMic()
        val spoken = EndSignal.strip(heard.toString())
        if (spoken.isBlank()) { showStatus("말씀을 듣지 못했어요. 다시 눌러 주세요."); return }
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
            if (now.isNotBlank()) showStatus("“$now”", Ui.BROWN)
        }
        override fun onResults(b: Bundle?) {
            if (!listening) return
            val best = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (best.isNotBlank()) heard.append(' ').append(best)
            partial = ""
            // 키보드는 말이 끝나(잠시 조용해지)면 바로 다듬음. "~라고 보내 줘"는 떼어 냄
            finishSpeech()
        }
        override fun onError(error: Int) {
            if (!listening) return
            if (heard.isNotBlank() || partial.isNotBlank()) { finishSpeech(); return }
            resetMic()
            showStatus(when (error) {
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "인터넷 연결을 확인해 주세요."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 허용이 필요해요."
                else -> "말씀을 듣지 못했어요. 다시 눌러 주세요."
            }, Ui.RED)
        }
    }

    // ───────── 다듬어 넣기 ─────────

    /** [m] 말투로 다듬어 입력칸에 넣음. 이미 넣은 글이 있으면 그것을 지우고 바꿔 넣음 */
    private fun rewrite(m: Mode) {
        cache[m]?.let { put(it); return }
        val claude = ClaudeProvider.get(this) ?: return
        val spoken = lastSpoken
        val id = ++job
        showStatus("${m.emoji} '${m.label}' 말투로 다듬는 중…")
        Bg.run({
            claude.rewrite(m, spoken, null, prefs.myName, prefs.myRole, emptyList())
        }) { r ->
            if (id != job) return@run
            r.onSuccess { text -> cache[m] = text; if (mode == m) put(text) }
                .onFailure { showStatus((it as? HelperException)?.message ?: "문제가 생겼어요. 다시 해 주세요.", Ui.RED) }
        }
    }

    /** 입력칸에 넣기 (방금 넣은 글이 커서 바로 앞에 그대로 있으면 지우고 바꿈) */
    private fun put(text: String) {
        val ic = currentInputConnection ?: return
        inserted?.let { old ->
            val before = ic.getTextBeforeCursor(old.length, 0)?.toString()
            if (before == old) ic.deleteSurroundingText(old.length, 0)
        }
        ic.commitText(text, 1)
        inserted = text
        showStatus("넣었어요. 확인하고 직접 보내세요. 말투를 누르면 바꿔 넣어요.", Ui.GREEN)
    }

    // ───────── 찾기: 카톡 '대화 내보내기'로 저장해 둔 대화에서 (화면은 읽지 않음) ─────────

    private fun searchAll(question: String) {
        val claude = ClaudeProvider.get(this) ?: return
        val id = ++job
        showStatus("🔎 \"$question\" 저장해 둔 대화에서 찾는 중…")
        Bg.run({
            val ids = Rooms.ids(this)
            if (ids.isEmpty()) null
            else {
                val rooms = ids.map { r -> (Rooms.name(this, r) ?: r) to ChatStore.chatText(this, r).orEmpty() }.filter { it.second.isNotBlank() }
                claude.answer(question, kr.yunho.talkhelper.core.ChatSearch.excerptMany(rooms, question), null, manyRooms = true)
            }
        }) { r ->
            if (id != job) return@run
            r.onSuccess { full ->
                if (full == null) {
                    showStatus("아직 저장해 둔 대화가 없어요. 카톡 대화방 ☰ → 설정 → '대화 내용 내보내기' → '말로 답장'으로 보내 두면 찾을 수 있어요.", Ui.RED)
                    return@onSuccess
                }
                val (text, _) = kr.yunho.talkhelper.core.ChatSearch.splitAnswer(full)
                answerText.text = Ui.answer(text)
                answerBox.visibility = View.VISIBLE
                showStatus("🔎 \"$question\"")
            }.onFailure { showStatus((it as? HelperException)?.message ?: "찾지 못했어요. 다시 해 주세요.", Ui.RED) }
        }
    }

    private fun switchTone(m: Mode) {
        mode = m
        prefs.mode = m
        paint()
        if (lastSpoken.isNotBlank() && inserted != null) rewrite(m)
    }
}
