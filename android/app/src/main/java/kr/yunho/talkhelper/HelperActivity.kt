package kr.yunho.talkhelper

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import kr.yunho.talkhelper.Ui.add
import kr.yunho.talkhelper.core.EndSignal
import kr.yunho.talkhelper.core.HelperException
import kr.yunho.talkhelper.core.Mode

/**
 * 카톡 위 '도우미' 버튼을 누르면 뜨는 화면.
 * 위쪽에서 말투 모드(정중하게 / 친구처럼 / 업무 연락)를 고름. 마지막에 고른 모드를 기억.
 * 버튼 한 번: 바로 듣기 → 말이 끝나면 Claude 가 정중하게 다듬기 → 3초 뒤 자동 전송 (그 사이 취소 가능).
 * 설정에서 자동 전송을 끄면 다듬은 문장을 확인하고 '보내기'를 눌러야 전송.
 */
class HelperActivity : Activity() {

    companion object {
        /** 버튼을 누른 순간 카톡 화면에 보이던 글 (대화 맥락) */
        @Volatile var pendingScreenText: String? = null
        /** 버튼을 누른 순간의 카톡 방 이름 */
        @Volatile var pendingTitle: String? = null
        /** 카톡 대화방에서 🎤로 연 카드 (그 방을 나가면 접근성 서비스가 닫음) */
        @Volatile var openInRoom: HelperActivity? = null
        /** 내보낸 대화가 저장된 뒤, 기다리던 질문을 이 카드에서 바로 찾아 보여 줄 때 */
        const val EXTRA_QUESTION = "question"
        const val EXTRA_ROOM = "room"
        /** true 면 '대화에서 찾기'로 바로 듣기 시작 (도우미 버튼을 꾹 눌러 🔎 고른 경우) */
        const val EXTRA_ASK = "ask"
        /** true 면 지금 방이 아니라 저장해 둔 모든 대화에서 찾음 (카톡 대화방 목록의 🔎 버튼) */
        const val EXTRA_ALL = "allRooms"
        /** 듣는 동안 이 간격으로 짧게 진동 (듣고 있다는 표시) */
        private const val PULSE_MS = 3000L
        private const val REQ_MIC = 7
        private const val COUNTDOWN = 3
        private const val MAX_RETRY = 4
        /** 말을 시작한 뒤 이만큼 아무 말이 없으면 보내지 않고 잠시 멈춤 ("~라고 보내 줘"라고 해야만 보냄) */
        private const val IDLE_PAUSE_MS = 3 * 60_000L
        /** 처음에 이만큼 아무 말이 없으면 멈춤 */
        private const val NO_SPEECH_MS = 30_000L

    }

    private lateinit var prefs: Prefs
    private var mode = Mode.POLITE
    private lateinit var modeButtons: Map<Mode, Button>
    private var screenText: String? = null

    private lateinit var micButton: Button
    private lateinit var heardView: TextView
    private lateinit var typedInput: EditText
    private lateinit var typedRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var resultEdit: EditText
    private lateinit var resultTitle: TextView
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button
    private lateinit var againRow: LinearLayout

    // 자동 전송 카운트다운
    private var countdown = 0
    private val tick = object : Runnable {
        override fun run() {
            if (countdown <= 0) { send(); return }
            resultTitle.text = "${countdown}초 뒤에 보냅니다…"
            sendButton.text = "📤 지금 보내기"; Ui.iconize(sendButton)
            countdown--
            Bg.main.postDelayed(this, 1000)
        }
    }

    // 말이 끝나면(잠시 조용해지면) 자동으로 듣기를 마침. 말을 시작하기 전 침묵은 몇 번 기다려 줌
    private var retries = 0
    private var lastHeardAt = 0L
    private var listenStartedAt = 0L
    private var paused = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var finishing = false
    private val heard = StringBuilder()
    private var partial = ""
    private var lastSpoken = ""

    /** 듣는 동안 3초마다 짧게 진동 (소리를 못 듣는 자리에서도 듣고 있다는 걸 알게) */
    private val pulse = object : Runnable {
        override fun run() {
            if (!listening) return
            Sounds.buzz(this@HelperActivity, 35, 120)
            Bg.main.postDelayed(this, PULSE_MS)
        }
    }
    private fun startPulse() { Bg.main.removeCallbacks(pulse); Bg.main.postDelayed(pulse, PULSE_MS) }
    private fun stopPulse() = Bg.main.removeCallbacks(pulse)

    /** 저장해 둔 모든 대화에서 찾는 카드인지 (대화방 목록에서 연 경우) */
    private var scopeAll = false
    /** 여러 방에서 찾았을 때 답이 나온 방 이름 ("대화방: …" 첫 줄) */
    private var answerRoom: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        mode = prefs.mode
        Sounds.init(this)   // 듣기 시작·끝 소리를 미리 불러 둠
        start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopRecognizer()
        start()
    }

    /** '알림으로 답장'으로 열린 경우 답장할 카톡 대화 (보낼 때 알림의 답장 칸으로 보냄) */
    private var notifyTarget: NotifyReplyService.Target? = null

    private fun start() {
        // 에뮬레이터 확인용 (디버그판만): 내보내기 안내 띠를 띄우고 닫음
        if ((BuildConfig.DEBUG || BuildConfig.E2E) && intent?.getBooleanExtra("debugGuide", false) == true) {
            KakaoAccessibilityService.instance?.showExportGuide(); finish(); return
        }
        screenText = pendingScreenText
        pendingScreenText = null
        roomTitle = pendingTitle
        pendingTitle = null
        notifyTarget = null
        intent?.getStringExtra(NotifyReplyService.EXTRA_KEY)?.let { key ->
            val t = NotifyReplyService.target(key)
            if (t == null) {
                Toast.makeText(this, "이 메시지 알림이 지나갔어요. 카톡에서 직접 답장해 주세요.", Toast.LENGTH_LONG).show()
                finish(); return
            }
            notifyTarget = t
            screenText = t.context()   // 화면 대신 알림에 보이던 최근 메시지
            roomTitle = t.title
        }
        heard.clear(); partial = ""; lastSpoken = ""
        scopeAll = intent?.getBooleanExtra(EXTRA_ALL, false) == true
        mode = prefs.modeFor(roomTitle)   // 이 방에서 고른 말투 (없으면 마지막에 고른 말투)
        val ask = scopeAll || intent?.getBooleanExtra(EXTRA_ASK, false) == true
        val readyQuestion = intent?.getStringExtra(EXTRA_QUESTION)
        val readyRoom = intent?.getStringExtra(EXTRA_ROOM)
        build()
        // 대화방에서 연 보통 카드만: 뒤로가기 등으로 그 방을 나가면 저절로 닫힘 (찾은 결과·알림 답장 카드는 그대로)
        openInRoom = if (readyQuestion == null && notifyTarget == null && roomTitle != null) this else null
        // 내보내기가 끝나 저장된 대화에서 바로 찾기 (듣기 없이)
        if (readyQuestion != null && readyRoom != null) {
            heardView.text = "물어본 것: $readyQuestion"
            talkRowView.visibility = View.GONE
            val saved = ChatStore.chatText(this, readyRoom).orEmpty()
            answerFrom(readyQuestion, saved, readyRoom, 0)
            return
        }
        // 버튼을 누르자마자 바로 듣기 시작
        if (ClaudeProvider.get(this) == null) showStatus("⚠️ 아직 준비가 안 됐어요. '${getString(R.string.app_name)}' 앱을 열어 설정을 마쳐 주세요.", Ui.RED)
        else {
            askMode = ask
            toggleMic() // 마이크 권한이 없으면 여기서 허용을 물음
            if (ask && listening) heardView.text = if (scopeAll) "🔎 저장해 둔 모든 대화에서 찾아요. 무엇을 찾을까요? (예: 모임 날짜 언제로 정했지?)"
                else "🔎 무엇을 찾을까요? 말씀하세요 (예: 연습이 언제였지?)"
            Ui.iconize(heardView)
        }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ───────── 화면 ─────────

    /** 작은 버튼 (떠 있는 카드용) */
    private fun small(text: String, bg: Int = Ui.CARD, fg: Int = Ui.BROWN, sp: Float = 15f, onClick: (View) -> Unit): Button =
        Ui.button(this, text, bg, fg, sp, onClick).apply {
            minHeight = Ui.dp(this@HelperActivity, 44); minimumHeight = minHeight
            minWidth = 0; minimumWidth = 0
            val p = Ui.dp(this@HelperActivity, 6)
            setPadding(p * 2, p, p * 2, p)
            background = Ui.rounded(this@HelperActivity, bg, 14, if (bg == Ui.CARD) Ui.LINE else null)
        }

    /** 글 칸 안에서 손가락을 위아래로 움직이면 바깥 카드가 아니라 글 칸이 넘어가게 */
    private fun keepScrollInside(v: View, e: MotionEvent) {
        val canScroll = v.canScrollVertically(1) || v.canScrollVertically(-1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                v.parent?.requestDisallowInterceptTouchEvent(canScroll)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    private fun scrollInside(v: TextView) {
        v.setOnTouchListener { view, e -> keepScrollInside(view, e); false }
    }

    /** 카톡 위에 떠 있는 작은 카드 창. 뒤의 카톡 대화가 보이고 스크롤도 됨 */
    private fun setupWindow() {
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val lp = window.attributes
        lp.gravity = Gravity.TOP
        lp.y = prefs.panelY.takeIf { it >= 0 } ?: Ui.dp(this, 70)
        window.attributes = lp
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // 카드 바깥을 누르면 뒤의 카톡이 반응 (대화를 보며 스크롤 가능)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }

    /** 손잡이를 끌어 카드 위치를 옮김 */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View) {
        var downY = 0f; var startY = 0
        handle.setOnTouchListener { _, e ->
            val lp = window.attributes
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    val max = resources.displayMetrics.heightPixels - window.decorView.height
                    lp.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, maxOf(0, max))
                    window.attributes = lp
                }
                MotionEvent.ACTION_UP -> prefs.panelY = lp.y
            }
            true
        }
    }

    private fun build() {
        val ctx = this
        val card = Ui.vbox(ctx, 12).apply {
            background = Ui.rounded(ctx, Ui.BG, 22, Ui.BROWN)
            elevation = Ui.dp(ctx, 12).toFloat()
        }

        // 손잡이 + 닫기
        val head = Ui.hbox(ctx)
        // 지금 말투와 보내는 방식만 작게 (바꾸려면 도우미 버튼을 꾹 눌러 고름)
        val handle = Ui.text(ctx, if (scopeAll) "⠿  🔎 저장해 둔 모든 대화에서 찾기" else "⠿  ${mode.emoji} ${mode.label} · ${prefs.sendMode.label}",
            14f, bold = true, color = Ui.MUTED).apply {
            setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
        }
        head.add(handle, weight = 4f)
        head.add(small("✕", sp = 16f) { finish() }, weight = 1f)
        makeDraggable(handle)
        card.add(head, 0)

        // 말투 모드 (마지막에 고른 것을 기억)
        val buttons = LinkedHashMap<Mode, Button>()
        Mode.values().toList().chunked(2).forEachIndexed { i, pair ->
            val modeRow = Ui.hbox(ctx)
            for (m in pair) buttons[m] = modeRow.add(small("${m.emoji} ${m.label}", sp = 14f) { selectMode(m) }) as Button
            modeRow.visibility = View.GONE   // 카드는 간단히: 말투는 버튼을 꾹 눌러 고름
            card.add(modeRow, if (i == 0) 6 else 4)
        }
        modeButtons = buttons
        paintModes()

        // 보내는 방식 (바로 / 3초 뒤 / 확인 후)
        val sendModeRow = Ui.hbox(ctx)
        sendModeRow.add(Ui.text(ctx, "보내기", 14f, bold = true, color = Ui.MUTED), weight = 0.8f)
        val sendButtons = LinkedHashMap<SendMode, Button>()
        for (m in SendMode.available()) sendButtons[m] = sendModeRow.add(small(m.label, sp = 14f) { selectSendMode(m) }) as Button
        sendModeButtons = sendButtons
        sendModeRow.visibility = View.GONE   // 보내는 방식은 꾹 눌러 ⚙️ 설정에서
        card.add(sendModeRow, 6)
        paintSendModes()

        // 말하기 + 들은 말
        val talkRow = Ui.hbox(ctx)
        talkRowView = talkRow
        micButton = small("", Ui.BROWN, Ui.YELLOW, sp = 16f) { if (!listening) askMode = scopeAll; toggleMic() }
        talkRow.add(micButton, weight = 2f)
        // ⌨️·🔎 는 숨김 (찾기는 버튼을 꾹 눌러 🔎)
        talkRow.add(small("⌨️", sp = 15f) {
            stopRecognizer(); resetMic()
            typedRow.visibility = View.VISIBLE
            typedInput.requestFocus()
        }, weight = 0.7f).visibility = View.GONE
        // 지금 카톡 화면에 보이는 대화에서 묻기
        talkRow.add(small("🔎", sp = 15f) {
            stopRecognizer(); resetMic()
            askMode = true
            toggleMic()
            if (listening) heardView.text = "무엇을 찾을까요? 말씀하세요 (예: 연습이 언제였지?)"
        }, weight = 0.7f).visibility = View.GONE
        card.add(talkRow, 8)
        resetMic()
        heardView = Ui.text(ctx, "다 말하고 끝에 \"…라고 보내 줘\"라고 하면 보냅니다", 15f, color = Ui.MUTED).apply {
            // 길게 말해도 다 보이게 (넘치면 안에서 위아래로 넘겨 봄)
            maxLines = 10
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod()
            scrollInside(this)
        }
        card.add(heardView, 6)

        typedRow = Ui.hbox(ctx).apply { visibility = View.GONE }
        typedInput = Ui.input(ctx, "대충 써도 됩니다", multiline = true).apply { textSize = 16f; maxLines = 3 }
        typedRow.add(typedInput, weight = 3f)
        typedRow.add(small("✨ 다듬기", sp = 14f) {
            val t = typedInput.text.toString().trim()
            if (t.isNotEmpty()) rewrite(t)
        }, weight = 1.3f)
        card.add(typedRow, 6)

        status = Ui.text(ctx, "", 15f, color = Ui.MUTED).apply { visibility = View.GONE }
        card.add(status, 4)

        // 다듬은 결과
        resultBox = Ui.vbox(ctx, 10, Ui.MINE).apply { visibility = View.GONE }
        resultTitle = Ui.text(ctx, "", 14f, bold = true, color = Ui.BROWN)
        resultBox.add(resultTitle, 0)
        resultEdit = Ui.input(ctx, "", multiline = true).apply {
            textSize = 18f * prefs.textScale
            maxLines = 6
            isVerticalScrollBarEnabled = true
            // 고치려고 누르면 자동 전송을 멈춤 + 긴 글은 안에서 위아래로 넘겨 봄
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) { stopConfirm(); stopCountdown() }
                keepScrollInside(v, e)
                false
            }
        }
        resultBox.add(resultEdit, 4)
        val sendRow = Ui.hbox(ctx)
        cancelButton = small("✋ 취소", Ui.CARD, Ui.RED, sp = 17f) { stopCountdown() }
        sendRow.add(cancelButton, weight = 1f)
        sendButton = small("📤 보내기", Ui.BROWN, Ui.YELLOW, sp = 17f) { send() }
        sendRow.add(sendButton, weight = 1.4f)
        resultBox.add(sendRow, 8)
        againRow = Ui.hbox(ctx)
        againRow.add(small("🎤 다시 말하기", sp = 14f) { stopConfirm(); stopCountdown(); resultBox.visibility = View.GONE; toggleMic() })
        againRow.add(small("🔄 다시 다듬기", sp = 14f) { stopCountdown(); if (lastSpoken.isNotBlank()) rewrite(lastSpoken) })
        againRow.add(small("🚩", sp = 14f) { stopConfirm(); stopCountdown(); reportAi(resultEdit.text.toString()) }, weight = 0.5f)
        resultBox.add(againRow, 6)
        card.add(resultBox, 8)

        // 대화에서 찾은 답
        answerBox = Ui.vbox(ctx, 10, Ui.THEM).apply { visibility = View.GONE }
        // 정리된 내용은 휴대폰 기본 글씨 크기로
        answerText = TextView(ctx).apply {
            setTextColor(Ui.BROWN)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }
        answerBox.add(answerText, 0)
        answerPhotos = Ui.vbox(ctx).apply { visibility = View.GONE }
        answerBox.add(answerPhotos, 8)
        val answerRow = Ui.hbox(ctx)
        answerRow.add(small(if (BuildConfig.STORE_SAFE) "🔍 이 대화 찾아가기" else "📍 해당 메시지로 이동", Ui.BROWN, Ui.YELLOW, sp = 15f) { goToMessage() }, weight = 1.6f)
        answerRow.add(small("🚩", sp = 15f) { tts?.stop(); reportAi(answerText.text.toString()) }, weight = 0.5f)
        answerRow.add(small("닫기", sp = 15f) { tts?.stop(); finish() })
        answerBox.add(answerRow, 8)
        card.add(answerBox, 8)

        // 새 버전 알림: 카드 맨 위에 눈에 띄는 노란 띠 (누르면 업데이트하러 감)
        val updateLine = Ui.hbox(ctx).apply {
            visibility = View.GONE
            background = Ui.rounded(ctx, Ui.YELLOW, 16, Ui.BROWN)
            val ph = Ui.dp(ctx, 14); val pv = Ui.dp(ctx, 10); setPadding(ph, pv, Ui.dp(ctx, 8), pv)
            elevation = Ui.dp(ctx, 3).toFloat()
            contentDescription = "새 버전이 나왔어요. 눌러서 업데이트"
            setOnClickListener {
                if (Updater.STORE) Updater.openStore(ctx)
                else startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
        val updateTexts = Ui.vbox(ctx)
        updateTexts.add(Ui.text(ctx, "🔔 새 버전이 나왔어요", 18f, bold = true), 0)
        updateTexts.add(Ui.text(ctx, "더 좋아진 기능을 받아 보세요", 14f, color = Ui.BROWN), 0)
        updateLine.addView(updateTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        updateLine.addView(Ui.text(ctx, "업데이트 ›", 16f, bold = true, color = Ui.YELLOW).apply {
            background = Ui.rounded(ctx, Ui.BROWN, 14)
            val ph = Ui.dp(ctx, 14); val pv = Ui.dp(ctx, 8); setPadding(ph, pv, ph, pv)
            isClickable = false
        })
        // 맨 위(첫 줄 위)에 넣음
        card.addView(updateLine, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = Ui.dp(ctx, 10) })
        Bg.run({ Updater.check(ctx) }) { r -> if (r.getOrNull() != null && !isFinishing) updateLine.visibility = View.VISIBLE }

        val root = FrameLayout(ctx).apply {
            val m = Ui.dp(ctx, 10)
            setPadding(m, 0, m, 0)
            addView(ScrollView(ctx).apply { addView(card) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        setupWindow()
    }

    private fun selectMode(m: Mode) {
        mode = m
        prefs.setModeFor(roomTitle, m)   // 이 방의 말투로 기억
        paintModes()
        // 이미 다듬은 문장이 있으면 새 말투로 다시 다듬음
        if (resultBox.visibility == View.VISIBLE && lastSpoken.isNotBlank()) { stopCountdown(); rewrite(lastSpoken) }
    }

    private lateinit var sendModeButtons: Map<SendMode, Button>

    private fun selectSendMode(m: SendMode) {
        prefs.sendMode = m
        paintSendModes()
        Toast.makeText(this, m.desc, Toast.LENGTH_SHORT).show()
        // 3초 카운트다운 중에 '확인 후'로 바꾸면 멈춤
        if (m == SendMode.CONFIRM || m == SendMode.VOICE) stopCountdown()
    }

    private fun paintSendModes() = sendModeButtons.forEach { (m, b) ->
        val on = m == prefs.sendMode
        b.background = Ui.rounded(this, if (on) Ui.YELLOW else Ui.CARD, 18, if (on) Ui.BROWN else Ui.LINE)
    }

    private fun paintModes() = modeButtons.forEach { (m, b) ->
        val on = m == mode
        b.background = Ui.rounded(this, if (on) Ui.YELLOW else Ui.CARD, 18, if (on) Ui.BROWN else Ui.LINE)
    }

    private fun showStatus(s: String?, color: Int = Ui.MUTED) {
        status.text = s ?: ""
        status.setTextColor(color)
        Ui.iconize(status)
        status.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    // ───────── 음성 입력 ─────────

    private fun toggleMic() {
        if (listening) { stopListening(); return }
        if (!hasMic()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showStatus("이 휴대폰에서 음성 인식을 쓸 수 없습니다. '글로 쓰기'를 이용해 주세요.", Ui.RED)
            return
        }
        stopCountdown()
        // 잠시 멈췄던 거면 들은 말을 그대로 두고 이어서 들음
        if (!paused) { heard.clear(); partial = ""; lastHeardAt = 0L }
        paused = false
        retries = 0
        listenStartedAt = System.currentTimeMillis()
        if (::answerBox.isInitialized) answerBox.visibility = View.GONE
        resultBox.visibility = View.GONE
        listening = true; finishing = false
        micButton.text = "🎙️ 듣는 중 (누르면 끝)"; Ui.iconize(micButton)
        micButton.background = Ui.rounded(this, Ui.GREEN, 18)
        micButton.setTextColor(Ui.CARD)
        heardView.text = if (heard.isNotBlank()) heard.toString().trim() else "듣고 있어요… 다 말하면 끝에 \"…라고 보내 줘\"라고 하세요"
        heardView.visibility = View.VISIBLE
        showStatus(null)
        // 듣기 시작을 분명히: 올라가는 종소리 + 진동, 그 뒤 3초마다 짧은 진동 (소리가 마이크에 섞이지 않게 잠깐 뒤에 듣기 시작)
        Sounds.listenStart(this)
        Sounds.buzz(this, 70)
        startPulse()
        Bg.main.postDelayed({ if (listening && !isFinishing && !isDestroyed) listen() }, 250)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleMic()
        else showStatus("마이크를 허용해야 말로 답장할 수 있어요.", Ui.RED)
    }

    private fun listen() {
        val r = recognizer ?: newRecognizer().also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        addHints(i)
        runCatching { r.startListening(i) }.onFailure { showStatus("음성 인식을 시작하지 못했어요.", Ui.RED); resetMic() }
    }

    /**
     * 음성 인식기: 구글 음성 인식이 있으면 그것을 씀 (삼성 폰은 기본이 삼성 인식기로 잡혀 있어 한국어 정확도가 낮을 수 있음)
     */
    private fun newRecognizer(): SpeechRecognizer {
        val google = android.content.ComponentName(
            "com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        val hasGoogle = runCatching {
            packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
                .any { it.serviceInfo.packageName == google.packageName && it.serviceInfo.name == google.className }
        }.getOrDefault(false)
        return if (hasGoogle) SpeechRecognizer.createSpeechRecognizer(this, google) else SpeechRecognizer.createSpeechRecognizer(this)
    }

    /** 카톡 화면의 이름·세례명·방 이름과 자주 쓰는 호칭을 인식기에 미리 알려 줌 (안드로이드 13 이상) */
    private fun addHints(i: Intent) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val words = kr.yunho.talkhelper.core.SpeechHints.words(screenText, roomTitle)
        i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(words))
    }

    private fun stopListening() {
        listening = false
        finishing = true
        micButton.text = "다듬는 중…"
        micButton.isEnabled = false
        runCatching { recognizer?.stopListening() }
        // 인식기가 결과를 주지 않는 경우를 대비
        Bg.main.postDelayed({ if (finishing) finishSpeech() }, 2500)
    }

    private fun stopRecognizer() {
        listening = false; finishing = false
        stopPulse()
        runCatching { recognizer?.cancel() }
    }

    private fun finishSpeech() {
        finishing = false
        if (partial.isNotBlank()) { heard.append(' ').append(partial); partial = "" }
        resetMic()
        val spoken = EndSignal.strip(heard.toString())
        if (spoken.isBlank()) { heardView.text = "말씀을 듣지 못했어요. 다시 눌러 주세요."; return }
        // 다 들었음을 분명히: 내려오는 종소리 + 진동
        Sounds.listenDone(this)
        Sounds.buzz(this, 90)
        heardView.text = (if (askMode) "물어본 것: " else "내가 한 말: ") + spoken
        if (askMode) askChat(spoken) else rewrite(spoken)
    }

    private fun resetMic() {
        listening = false
        stopPulse()
        micButton.isEnabled = true
        micButton.text = "🎤 눌러서 말하기"; Ui.iconize(micButton)
        micButton.background = Ui.rounded(this, Ui.BROWN, 18)
        micButton.setTextColor(Ui.YELLOW)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(b: Bundle?) {
            partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (partial.isNotBlank()) lastHeardAt = System.currentTimeMillis()
            val now = (heard.toString() + " " + partial).trim()
            showHeard(now.ifBlank { "듣고 있어요…" })
            // 말하는 중간(실시간 받아쓰기)에는 끊지 않음 — 한 마디가 끝난 뒤(onResults)에만 마무리 신호를 봄
        }

        override fun onResults(b: Bundle?) {
            val best = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (best.isNotBlank()) { heard.append(' ').append(best); lastHeardAt = System.currentTimeMillis() }
            partial = ""
            showHeard(heard.toString().trim().ifBlank { "듣고 있어요…" })
            when {
                finishing -> finishSpeech()
                !listening -> {}
                // 묻기는 한 문장이면 충분 → 바로 찾기
                askMode && heard.isNotBlank() -> { listening = false; finishSpeech() }
                // 신호 말이 나왔으면 마무리
                EndSignal.isEnd(heard.toString()) || EndSignal.isEndSegment(best) -> { listening = false; finishSpeech() }
                else -> keepListening()
            }
        }

        override fun onError(error: Int) {
            when {
                finishing -> finishSpeech()
                !listening -> {}
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { resetMic(); showStatus("마이크 권한이 필요해요.", Ui.RED) }
                error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    { resetMic(); showStatus("인터넷 연결을 확인해 주세요.", Ui.RED) }
                else -> {
                    val last = partial
                    if (partial.isNotBlank()) { heard.append(' ').append(partial); partial = "" }
                    if ((askMode && heard.isNotBlank()) || EndSignal.isEnd(heard.toString()) || (last.isNotBlank() && EndSignal.isEndSegment(last))) { listening = false; finishSpeech() }
                    else keepListening()
                }
            }
        }
    }

    /**
     * 신호 말이 나올 때까지 이어서 들음. 말을 쉬어도 끊기지 않음.
     * 한참 조용하면(신호 말을 잊었으면) 그때까지 한 말로 마무리.
     */
    /**
     * 말이 잠깐 끊겨도 멈추지 않고 계속 들음. "~라고 보내 줘"가 들려야(또는 버튼을 눌러야) 마무리.
     * 너무 오래 조용하면 보내지 않고 잠시 멈춤 (들은 말은 그대로 두고 이어서 말할 수 있음)
     */
    private fun keepListening() {
        val now = System.currentTimeMillis()
        val spoken = heard.isNotBlank()
        when {
            !spoken && now - listenStartedAt >= NO_SPEECH_MS -> { resetMic(); heardView.text = "말씀을 듣지 못했어요. 다시 눌러 주세요." }
            spoken && now - lastHeardAt >= IDLE_PAUSE_MS -> pauseListening()
            else -> Bg.main.postDelayed({ if (listening) listen() }, 150)
        }
    }

    /** 들은 말 보여 주기 — 길어지면 마지막 줄이 보이게 아래로 넘김 */
    private fun showHeard(t: String) {
        heardView.text = t
        heardView.post {
            val l = heardView.layout ?: return@post
            val y = l.getLineTop(heardView.lineCount) - (heardView.height - heardView.paddingTop - heardView.paddingBottom)
            heardView.scrollTo(0, maxOf(0, y))
        }
    }

    /** 오래 조용해서 잠시 멈춤 — 보내지는 않음 */
    private fun pauseListening() {
        runCatching { recognizer?.cancel() }
        resetMic()
        paused = true
        micButton.text = "🎤 이어서 말하기"; Ui.iconize(micButton)
        heardView.text = heard.toString().trim() + "\n\n(잠시 멈췄어요. 🎤를 누르고 이어서 말한 뒤 \"…라고 보내 줘\"라고 하세요)"; Ui.iconize(heardView)
    }

    // ───────── 다듬기 ─────────

    // ───────── 대화에서 찾기 (카드 안에서 바로) ─────────

    private var askMode = false
    private lateinit var answerBox: LinearLayout
    private lateinit var answerText: TextView
    private lateinit var answerPhotos: LinearLayout
    private var answerKeyword: String? = null
    private var tts: android.speech.tts.TextToSpeech? = null

    /** 이 대화방을 미리 '내보내기'로 저장해 뒀으면 그 전체 대화 + 지금 화면 */
    private var roomTitle: String? = null
    private lateinit var talkRowView: View

    /**
     * 지금 카톡 방에 해당하는 저장 대화(번호)를 찾아 그 전체 대화 + 지금 화면.
     * 그 방에서 내보낸 대화만 씀 (비슷한 이름·다른 방은 쓰지 않음). 없으면 화면 대화만
     */
    private fun chatForThisRoom(): Pair<String, String?> {
        val screen = screenText.orEmpty()
        val room = kr.yunho.talkhelper.core.RoomMatch.lookup(roomTitle, Rooms.all(this), screen.lines()) { ChatStore.chatText(this, it).orEmpty() }
        // 잘린 제목·화면 메시지로 찾았으면, 지금 화면 이름도 그 방 것으로 기억해 다음엔 바로 찾게
        if (room != null) Rooms.learn(this, room, roomTitle)
        val saved = room?.let { ChatStore.chatText(this, it) }.orEmpty()
        val chat = if (saved.isNotBlank()) "$saved\n\n[지금 카톡 화면]\n$screen" else screen
        return chat to room
    }

    private fun askChat(question: String) {
        if (ClaudeProvider.get(this) == null) return showStatus("⚠️ 아직 준비가 안 됐어요. 앱을 열어 설정을 마쳐 주세요.", Ui.RED)
        resultBox.visibility = View.GONE
        answerBox.visibility = View.GONE
        // 대화방 목록에서 연 카드: 저장해 둔 모든 대화에서
        if (scopeAll) { askAllRooms(question, null, null); return }
        showStatus("🔎 어느 대화인지 보는 중…")
        // 저장된 대화 파일을 읽어 가며 맞춰 보므로 뒤에서
        Bg.run({ chatForThisRoom() }) { r ->
            if (isFinishing) return@run
            val pair = r.getOrNull() ?: return@run showStatus("문제가 생겼어요: ${r.exceptionOrNull()?.message}", Ui.RED)
            val (chat, room) = pair
            val svc = KakaoAccessibilityService.instance
            val savedCount = Rooms.ids(this).size
            prefs.logExport("찾기: 카톡 방 '${roomTitle ?: "못 읽음"}' → ${room?.let { "저장된 '${Rooms.name(this, it)}' ($it)" } ?: "이 방 것은 못 찾음 (저장된 대화 ${savedCount}개)"}")
            when {
                room != null -> answerFrom(question, chat, room, 0)
                // 이 방에서 내보낸 대화를 못 알아봤지만 저장된 대화가 있으면: 다시 내보내라 하지 않고 저장된 모든 대화에서 찾음
                savedCount > 0 -> askAllRooms(question, chat, roomTitle ?: "이")
                // 저장된 대화가 하나도 없을 때만 내보내는 방법을 안내
                svc != null -> { showStatus(null); askExport(question, chat, svc) }
                chat.isBlank() -> showStatus("화면에서 대화를 읽지 못했어요. 카톡 대화방에서 다시 눌러 주세요.", Ui.RED)
                else -> answerFrom(question, chat, null, 0)
            }
        }
    }

    /**
     * 저장해 둔 모든 대화(방마다 "[대화방: 이름]"으로 묶어서)에서 찾기. 답의 첫 줄에 어느 방에서 찾았는지 나옴.
     * [screen] 은 지금 화면 글(있으면 함께 봄), [missingRoom] 은 저장된 것을 못 알아본 지금 방 이름(있으면 그 안내와 내보내기 버튼)
     */
    private fun askAllRooms(question: String, screen: String?, missingRoom: String?) {
        val claude = ClaudeProvider.get(this) ?: return
        showStatus("🔎 저장해 둔 모든 대화에서 찾는 중…")
        // 저장된 대화 목록·파일 읽기는 뒤에서 (큰 파일이 있어도 화면이 멈추지 않게)
        Bg.run({
            val ids = Rooms.ids(this)
            if (ids.isEmpty()) null
            else {
                val rooms = ids.map { id -> (Rooms.name(this, id) ?: id) to ChatStore.chatText(this, id).orEmpty() }.filter { it.second.isNotBlank() }
                val all = rooms + listOfNotNull(screen?.takeIf { it.isNotBlank() }?.let { "지금 카톡 화면" to it })
                claude.answer(question, kr.yunho.talkhelper.core.ChatSearch.excerptMany(all, question), null, manyRooms = true) to ids.size
            }
        }) { r ->
            if (isFinishing) return@run
            val got = r.getOrNull()
            if (r.isSuccess && got == null) {
                showStatus("아직 저장해 둔 대화가 없어요. 찾고 싶은 대화방에 들어가 🎤를 꾹 누르고 🔎 찾기를 하면 내보내는 방법을 알려 드려요.", Ui.RED)
                return@run
            }
            val count = got?.second ?: 0
            showAnswer(question, r.map { it!!.first }, room = null,
                note = missingRoom?.let { "'$it' 방에서 내보낸 대화는 못 찾아서, 저장해 둔 대화 ${count}개에서 찾았어요. 이 방 대화를 내보내 두면 이 방에서 바로 찾아요." })
        }
    }

    /** 플레이판: 질문을 기억해 두고 카드를 닫은 뒤, 직접 내보내는 순서를 화면 위에 띄움 */
    private fun startManualExport(question: String) {
        AskActivity.pendingQuestion = question to System.currentTimeMillis()
        AskActivity.pendingTitle = roomTitle
        AskActivity.pendingScreen = screenText
        finish()
        // 카드가 닫혀도 따라 할 순서가 화면 위에 남게 (카톡은 직접 누름)
        KakaoAccessibilityService.instance?.showExportGuide()
    }

    /** 이 방 대화를 내보낼지 물어봄 */
    private fun askExport(question: String, chat: String, svc: KakaoAccessibilityService) {
        if (BuildConfig.STORE_SAFE) return askManualExport(question, chat)
        run {
            AlertDialog.Builder(this)
                .setTitle("이 카톡방의 대화 메시지를 내보낼까요?")
                .setMessage(
                    "내보내면 이 방의 예전 대화까지 처음부터 찾아서, 대화가 어떻게 진행되고 무엇이 결정됐는지 정리해 드려요.\n\n" +
                        "• 내보낸 대화는 이 휴대폰 안에만 보관돼요. 다른 곳에 저장되지 않아요.\n" +
                        "• 찾을 때만 질문과 관련된 부분을 AI(Claude)에 보내 답을 받아요.\n" +
                        "• 앱에서 언제든 지울 수 있어요."
                )
                .setPositiveButton("내보내고 찾기") { _, _ -> exportThenAsk(question, svc) }
                .setNegativeButton("이번엔 화면에서만") { _, _ -> collectThenAsk(question, chat, svc) }
                .setNeutralButton("취소", null)
                .show()
        }
    }

    /**
     * 플레이판: 카톡 메뉴를 앱이 대신 누르지 않음 (구글 접근성 정책·카카오 약관 우려).
     * 사용자가 직접 '대화 내용 내보내기'를 하도록 순서만 알려 주고, 질문은 15분 동안 기억했다가 이어서 답함.
     */
    private fun askManualExport(question: String, chat: String) {
        val ctx = this
        // 버튼 글: 굵은 제목 한 줄 + 작은 설명 한 줄
        fun label(title: String, sub: String): CharSequence {
            val sb = android.text.SpannableStringBuilder(title).append('\n')
            val start = sb.length
            sb.append(sub)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.72f), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        val box = Ui.vbox(ctx, 20)
        box.add(Ui.text(ctx, "어디에서 찾을까요?", 20f, bold = true), 0)
        box.add(Ui.text(ctx, "🔎 물어본 것: $question", 15f, color = Ui.MUTED), 4)

        val dialog = AlertDialog.Builder(this).setView(android.widget.ScrollView(ctx).apply { addView(box) }).create()

        // ① 내보내기 (노란 칸): 방법 안내를 칸 안에 함께
        val exportBox = Ui.vbox(ctx, 14, Ui.MINE)
        exportBox.add(Ui.button(ctx, label("📤 직접 내보낼게요", "예전 대화까지 모두 찾아요"), Ui.BROWN, Ui.YELLOW, 18f) {
            dialog.dismiss()
            startManualExport(question)
        }, 0)
        exportBox.add(Ui.text(ctx,
            "카톡에서 이렇게 눌러 주세요\n☰ → ⚙ 설정 → 대화 내용 내보내기\n→ 텍스트 메시지만 저장 → 앱 목록에서 '${getString(R.string.app_name)}'", 15f), 10)
        exportBox.add(Ui.text(ctx,
            "• 내보낸 대화는 이 휴대폰 안에만 보관돼요.\n• 찾을 때만 관련된 부분을 AI(Claude)에 보내요.\n• 물어본 것은 기억했다가 내보내면 이어서 찾아요.",
            13f, color = Ui.MUTED), 8)
        box.add(exportBox, 16)

        // ② 화면에서만 (회색 칸)
        val screenBox = Ui.vbox(ctx, 14, Ui.THEM)
        screenBox.add(Ui.button(ctx, label("👀 이번엔 화면에서만", "지금 보이는 대화에서만 찾아요"), Ui.CARD, Ui.BROWN, 18f) {
            dialog.dismiss()
            if (chat.isBlank()) showStatus("화면에서 대화를 읽지 못했어요. 카톡 대화방에서 다시 눌러 주세요.", Ui.RED)
            else answerFrom(question, chat, null, 0)
        }, 0)
        box.add(screenBox, 12)

        box.add(Ui.button(ctx, "취소", Ui.CARD, Ui.MUTED, 16f) { dialog.dismiss() }.apply {
            background = null; minHeight = Ui.dp(ctx, 48)
        }, 8)
        dialog.show()
    }

    /** 카톡 메뉴를 대신 눌러 이 방 대화를 이 앱으로 내보내고, 저장되면 '대화에서 찾기'가 이어서 답함 */
    private fun exportThenAsk(question: String, svc: KakaoAccessibilityService) {
        AskActivity.pendingQuestion = question to System.currentTimeMillis()
        AskActivity.pendingTitle = roomTitle
        AskActivity.pendingScreen = screenText
        finish()   // 카드를 닫아야 카톡 메뉴를 누를 수 있음
        Bg.main.postDelayed({
            // 막히면 덮개에 직접 누를 순서가 크게 남음 (질문은 15분 동안 기억)
            svc.exportChat { _, _ -> }
        }, 400)
    }

    /** 내보내지 않고: 카톡 대화방을 위로 넘기며 예전 대화를 모아서 찾음 */
    private fun collectThenAsk(question: String, chat: String, svc: KakaoAccessibilityService) {
        run {
            showStatus("📜 예전 대화를 넘겨 보며 모으는 중… 잠시만요")
            svc.collectHistory(progress = { n ->
                if (!isFinishing) showStatus("📜 예전 대화를 넘겨 보며 모으는 중… ${n}쪽")
            }) { lines ->
                if (isFinishing) return@collectHistory
                val history = lines.joinToString("\n")
                val all = if (history.length > chat.length) history else chat
                if (all.isBlank()) showStatus("화면에서 대화를 읽지 못했어요. 카톡 대화방에서 다시 눌러 주세요.", Ui.RED)
                else answerFrom(question, all, null, lines.size)
            }
        }
    }

    private fun answerFrom(question: String, chat: String, room: String?, collectedLines: Int) {
        val claude = ClaudeProvider.get(this) ?: return
        val roomName = Rooms.name(this, room)
        showStatus(when {
            room != null -> "🔎 '$roomName' 대화 전체에서 찾는 중…"
            collectedLines > 0 -> "🔎 모은 대화 ${collectedLines}줄에서 찾는 중…"
            else -> "🔎 화면의 대화에서 찾는 중…"
        })
        Bg.run({ claude.answer(question, chat, roomName) }) { r -> showAnswer(question, r, room, null) }
    }

    /**
     * 찾은 결과를 카드에. [room] 은 찾은 저장 대화 번호(여러 방에서 찾았으면 null 이고 답 첫 줄의 "대화방:"으로 앎),
     * [note] 는 결과 아래 작은 안내 (이 방 것을 못 찾아 모든 대화에서 찾았을 때)
     */
    private fun showAnswer(question: String, r: Result<String>, room: String?, note: String?) {
        if (isFinishing) return
        askMode = false
        r.onSuccess { full ->
            val (text, word) = kr.yunho.talkhelper.core.ChatSearch.splitAnswer(full)
            showStatus(null)
            answerKeyword = word
            answerRoom = kr.yunho.talkhelper.core.ChatSearch.answeredRoom(text)
            val notFound = text.contains("찾지 못")
            answerText.text = Ui.answer(if (room == null && note == null && notFound) "$text\n\n※ 더 예전 대화는 카톡 '대화 내용 내보내기'로 이 앱에 저장해 두면 처음부터 다 찾아 드려요." else text)
            // 사진을 찾은 경우: 함께 저장해 둔 사진 중 그 무렵 것을 보여 줌 (사진은 휴대폰 밖으로 안 나감)
            answerPhotos.removeAllViews()
            val at = kr.yunho.talkhelper.core.PhotoFind.photoTime(full)
            val photoRoom = room ?: answerRoom?.let { name -> Rooms.all(this).firstOrNull { it.name == name }?.id }
            val photos = at?.let { ChatStore.photosAt(this, photoRoom, it) }.orEmpty()
            if (photos.isNotEmpty()) answerPhotos.add(PhotoStrip.view(this, photos), 0)
            else if (at != null) answerPhotos.add(Ui.text(this,
                if (photoRoom == null) "사진은 '대화에서 찾기'로 대화를 사진까지 저장해 두면 보여 드려요." else "그 무렵 사진 파일은 저장돼 있지 않아요. 📍로 그 위치에 가서 보세요.",
                14f, color = Ui.MUTED), 0)
            if (note != null) {
                answerPhotos.add(Ui.text(this, note, 13f, color = Ui.MUTED), 8)
                if (BuildConfig.STORE_SAFE) answerPhotos.add(small("📤 이 방 대화 내보내기", sp = 14f) { tts?.stop(); startManualExport(question) }, 6)
            }
            answerPhotos.visibility = if (answerPhotos.childCount > 0) View.VISIBLE else View.GONE
            answerBox.visibility = View.VISIBLE
            talkRowView.visibility = View.GONE   // 정리된 내용과 버튼 두 개만
            if (prefs.readAnswers) speak(text)   // 읽어 주기는 설정에서 켠 경우만
        }.onFailure { showStatus((it as? HelperException)?.message ?: "문제가 생겼어요: ${it.message}", Ui.RED) }
    }

    /**
     * 이상한 AI 결과 알리기 (구글 플레이 'AI 생성 콘텐츠' 정책: 사용자가 부적절한 결과를 신고할 수 있어야 함).
     * 사용자가 확인한 뒤 메일 앱으로 보냄. 앱이 몰래 보내지 않음.
     */
    private fun reportAi(aiText: String) {
        val note = EditText(this).apply { hint = "무엇이 이상했나요? (안 적어도 돼요)"; textSize = 16f; minLines = 2 }
        val spoken = lastSpoken
        AlertDialog.Builder(this)
            .setTitle("🚩 이상한 답장 알리기")
            .setMessage("AI가 만든 글이 이상하거나 불쾌했나요? '알리기'를 누르면 말씀하신 내용과 AI가 만든 글이 운영자에게 바로 전달돼요.")
            .setView(note)
            .setPositiveButton("알리기") { _, _ ->
                val n = note.text.toString().trim()
                prefs.addAiReport(spoken, aiText, n)
                val app = applicationContext
                Bg.run({ AiReport.send(n, spoken, aiText, BuildConfig.VERSION_NAME) }) { r ->
                    val sent = r.getOrDefault(false)
                    Toast.makeText(app, if (sent) "알려 주셔서 고마워요. 운영자에게 전달했어요." else "접수했어요. 지금은 전달하지 못해 이 휴대폰에 기록해 두었어요.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("메일로도 보내기") { _, _ ->
                val n = note.text.toString().trim()
                prefs.addAiReport(spoken, aiText, n)
                val body = "${n.ifBlank { "무엇이 이상했나요? (적어 주세요)" }}\n\n---\n[말한 내용]\n${spoken.take(1000)}\n\n[AI가 만든 글]\n${aiText.take(2000)}\n\n앱 버전 ${BuildConfig.VERSION_NAME}"
                val i = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
                    .putExtra(Intent.EXTRA_SUBJECT, "[말로 답장] 이상한 AI 답장 알리기")
                    .putExtra(Intent.EXTRA_TEXT, body)
                runCatching { startActivity(i) }.onFailure {
                    Toast.makeText(this, "접수했어요. 메일 앱이 없어 $REPORT_EMAIL 로는 보내지 못했어요.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 카드를 닫고, 카톡 대화방 검색으로 그 말이 있는 곳으로 이동 */
    private fun goToMessage() {
        val word = answerKeyword
        val app = applicationContext
        val answerTextCopy = answerText.text.toString()
        tts?.stop()
        finish()
        val svc = KakaoAccessibilityService.instance.takeUnless { BuildConfig.STORE_SAFE }
        if (!word.isNullOrBlank() && BuildConfig.STORE_SAFE) {
            // 플레이판: 대화방을 앱이 대신 넘기지 않고, 찾을 말을 복사해 두고 직접 검색하도록 안내
            // 대화방을 대신 넘기지 않음: 사용자가 🔍를 누르면 검색 칸에 찾을 말만 넣어 줌. 검색은 사용자가 누름
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("찾을말", word))
            val helper = KakaoAccessibilityService.instance
            val roomHint = answerRoom   // 여러 방에서 찾았으면 그 방에 들어가라고 안내
            if (helper != null) helper.waitForSearchBox(word, roomHint)
            else Toast.makeText(app, "'$word'를 복사해 두었어요. ${roomHint?.let { "'$it' 방에 들어가 " } ?: ""}대화방 위쪽 🔍를 누르고 붙여 넣어 찾아 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (word.isNullOrBlank() || svc == null) {
            Toast.makeText(app, "카톡 대화방에서 직접 찾아 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        // 대화방을 위로 넘기며 그 메시지가 보일 때까지 찾아가서 멈춤 (카톡 검색창에 기대지 않음)
        Bg.main.postDelayed({
            // 근거로 나온 원래 문장 앞부분으로 먼저, 없으면 찾을말로
            val quote = Regex("근거\\s*[:：]?[^'‘\"“]*['‘\"“]([^'’\"”]{4,})['’\"”]").find(answerTextCopy)?.groupValues?.get(1)?.trim()?.take(16)
            svc.scrollToText(listOfNotNull(quote, word)) { found ->
                if (found) Toast.makeText(app, "찾은 메시지예요", Toast.LENGTH_SHORT).show()
                else {
                    (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("찾을말", word))
                    Toast.makeText(app, "메시지를 찾지 못했어요. 대화방 위쪽 🔍를 누르고 '$word'를 붙여 넣어 찾아 주세요.", Toast.LENGTH_LONG).show()
                }
            }
        }, 400)
    }

    /** 읽어 주기. [then] 은 다 읽은 뒤 (읽지 못하면 바로) 실행 */
    private fun speak(text: String, then: (() -> Unit)? = null) {
        if (text.isBlank()) { then?.invoke(); return }
        fun say(t: android.speech.tts.TextToSpeech) {
            t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == "say") Bg.main.post { then?.invoke() } }
                @Deprecated("old") override fun onError(id: String?) { Bg.main.post { then?.invoke() } }
            })
            if (t.speak(text.take(3900), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "say") != android.speech.tts.TextToSpeech.SUCCESS) then?.invoke()
        }
        val t = tts ?: android.speech.tts.TextToSpeech(applicationContext) { st ->
            val ready = tts
            if (st == android.speech.tts.TextToSpeech.SUCCESS && ready != null) {
                ready.language = java.util.Locale.KOREAN
                ready.setSpeechRate(0.9f)
                say(ready)
            } else then?.invoke()
        }.also { tts = it; return }
        say(t)
    }

    // ───────── 듣고 확인 (읽어 드리고 말로 대답) ─────────

    private var confirmRec: SpeechRecognizer? = null
    /** 읽는 도중 직접 고치거나 버튼을 누르면 이어서 듣지 않도록 */
    private var confirmId = 0

    private fun readAndConfirm(text: String) {
        stopCountdown()
        resultTitle.text = "🔊 읽어 드릴게요. 듣고 \"보내\" 또는 \"다시\"라고 말씀하세요"; Ui.iconize(resultTitle)
        val id = ++confirmId
        speak("이렇게 보낼까요? $text") {
            if (id == confirmId && !isFinishing && resultBox.visibility == View.VISIBLE) listenConfirm(0)
        }
    }

    private fun listenConfirm(attempt: Int) {
        if (!hasMic() || !SpeechRecognizer.isRecognitionAvailable(this)) return
        stopConfirm()
        resultTitle.text = "🎤 듣고 있어요…  \"보내\" · \"다시\" · \"취소\""; Ui.iconize(resultTitle)
        val rec = newRecognizer()
        confirmRec = rec
        rec.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(error: Int) {
                if (confirmRec !== rec) return
                stopConfirm()
                if (attempt < 1) listenConfirm(attempt + 1)
                else resultTitle.text = "말씀을 듣지 못했어요. 아래 버튼으로 골라 주세요"
            }
            override fun onResults(b: Bundle?) {
                if (confirmRec !== rec) return
                stopConfirm()
                val said = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                when (kr.yunho.talkhelper.core.VoiceAnswer.of(said)) {
                    kr.yunho.talkhelper.core.VoiceAnswer.SEND -> send()
                    kr.yunho.talkhelper.core.VoiceAnswer.AGAIN -> { resultBox.visibility = View.GONE; askMode = false; toggleMic() }
                    kr.yunho.talkhelper.core.VoiceAnswer.CANCEL -> { stopCountdown(); resultTitle.text = "보내지 않았어요. 눌러서 고치거나 버튼을 골라 주세요" }
                    kr.yunho.talkhelper.core.VoiceAnswer.UNKNOWN ->
                        if (attempt < 1) listenConfirm(attempt + 1)
                        else resultTitle.text = "\"$said\" — 잘 모르겠어요. 아래 버튼으로 골라 주세요"
                }
            }
        })
        Sounds.listenStart(this)   // 지금 말하면 된다는 신호
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
    }

    private fun stopConfirm() {
        confirmId++
        tts?.stop()
        confirmRec?.let { runCatching { it.cancel(); it.destroy() } }
        confirmRec = null
    }

    private fun rewrite(spoken: String) {
        val claude = ClaudeProvider.get(this) ?: return showStatus("⚠️ 아직 준비가 안 됐어요. '${getString(R.string.app_name)}' 앱을 열어 설정을 마쳐 주세요.", Ui.RED)
        lastSpoken = spoken
        showStatus("${mode.emoji} '${mode.label}' 말투로 다듬는 중… 잠시만요")
        resultBox.visibility = View.GONE
        val m = mode
        Bg.run({
            // '내 말투': 이 방을 내보내 저장해 뒀으면 그 방에서 내가 보낸 말, 아니면 화면의 내 말풍선을 예시로
            val style = if (m == Mode.MINE) ChatStore.myStyle(this, chatForThisRoom().second).ifEmpty { kr.yunho.talkhelper.core.MyStyle.fromScreen(screenText) } else emptyList()
            claude.rewrite(m, spoken, screenText, prefs.myName, prefs.myRole, style)
                .also { claude.lastUsage?.let { u -> prefs.lastUsage = u.describe() } }
        }) { r ->
            if (isFinishing) return@run
            r.onSuccess { text ->
                showStatus(null)
                resultEdit.setText(text)
                resultEdit.clearFocus()
                resultBox.visibility = View.VISIBLE
                when (prefs.sendMode) {
                    SendMode.INSTANT -> send()
                    SendMode.COUNTDOWN -> startCountdown()
                    SendMode.CONFIRM -> stopCountdown()
                    SendMode.VOICE -> readAndConfirm(text)
                }
                resultBox.post { (resultBox.parent?.parent as? ScrollView)?.smoothScrollTo(0, resultBox.bottom) }
            }.onFailure { showStatus((it as? HelperException)?.message ?: "문제가 생겼어요: ${it.message}", Ui.RED) }
        }
    }

    // ───────── 보내기 ─────────

    private fun startCountdown() {
        cancelButton.visibility = View.VISIBLE
        againRow.visibility = View.GONE
        countdown = COUNTDOWN
        Bg.main.removeCallbacks(tick)
        tick.run()
    }

    /** 자동 전송을 멈추고 직접 확인하는 화면으로 */
    private fun stopCountdown() {
        Bg.main.removeCallbacks(tick)
        countdown = 0
        if (!::resultTitle.isInitialized) return
        resultTitle.text = "이렇게 보낼까요? (눌러서 고칠 수 있어요)"
        sendButton.text = "📤 보내기"; Ui.iconize(sendButton)
        cancelButton.visibility = View.GONE
        againRow.visibility = View.VISIBLE
    }

    private fun send() {
        Bg.main.removeCallbacks(tick)
        stopConfirm()
        val text = resultEdit.text.toString().trim()
        if (text.isEmpty()) return
        val app = applicationContext
        // '알림으로 답장': 카톡 알림의 답장 칸으로 보냄 (접근성 쓰지 않음)
        notifyTarget?.let { t ->
            finish()
            if (NotifyReplyService.send(app, t, text)) toast(app, "보냈습니다") else copyFallback(app, text)
            return
        }
        val pressSend = prefs.autoPressSend
        val svc = KakaoAccessibilityService.instance
        finish() // 이 화면이 닫혀 카톡이 다시 앞에 오면 입력창에 넣음
        if (svc == null) { copyFallback(app, text); return }
        svc.typeAndSend(text, pressSend, { r ->
            when (r) {
                KakaoAccessibilityService.SendResult.SENT -> toast(app, "보냈습니다")
                KakaoAccessibilityService.SendResult.TYPED_ONLY ->
                    toast(app, if (pressSend) "입력창에 넣었어요. 오른쪽 전송 버튼을 눌러 주세요" else "입력창에 넣었어요. 전송을 눌러 주세요")
                KakaoAccessibilityService.SendResult.FAILED -> copyFallback(app, text)
            }
        })
    }

    private fun copyFallback(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("답장", text))
        toast(ctx, "글을 복사했어요. 카톡 입력창을 길게 눌러 '붙여넣기' 해 주세요")
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    /** 사용자가 카톡 대화방을 나갔을 때: 듣기·자동 보내기를 멈추고 카드를 닫음 */
    fun closeBecauseLeftRoom() {
        if (isFinishing) return
        stopCountdown(); stopConfirm(); stopRecognizer()
        finish()
    }

    override fun onDestroy() {
        if (openInRoom === this) openInRoom = null
        Bg.main.removeCallbacks(tick)
        stopPulse()
        stopConfirm()
        tts?.shutdown(); tts = null
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }
}
