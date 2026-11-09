package kr.yunho.talkhelper

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import kr.yunho.talkhelper.core.Mode

/**
 * 1) 카카오톡이 화면에 있을 때만 '도우미' 버튼을 띄움
 * 2) 버튼을 누르면 카톡 화면에 보이는 글(대화 맥락)을 모아 도우미 화면(HelperActivity)을 엶
 *    꾹 누르면 버튼 둘레에 말투·찾기 동그라미 메뉴가 펼쳐짐
 * 3) 정중하게 다듬은 답장을 카톡 입력창에 넣고 전송 버튼을 누름
 * 카카오톡 이외의 앱 화면은 읽지 않음.
 */
class KakaoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: KakaoAccessibilityService? = null
            private set

        private val SEND_IDS = listOf("send", "send_button", "send_btn", "btn_send", "sendButton", "send_button_layout", "chatroom_send").map { "$KAKAO:id/$it" }
        /** 입력창 옆에 있지만 전송이 아닌 버튼 */
        private val NOT_SEND = listOf("이모티콘", "샵", "검색", "#", "더보기", "메뉴", "첨부", "음성", "Emoticon", "Search", "More")
        // 떠 있는 버튼 색
        private val CHARCOAL_TOP = android.graphics.Color.parseColor("#3A3A40")
        private val CHARCOAL_BOTTOM = android.graphics.Color.parseColor("#17171A")
        private val GOLD = android.graphics.Color.parseColor("#C9A96E")
        /** 이만큼 꾹 누르면 동그라미 메뉴 */
        private const val LONG_PRESS_MS = 450L
        private val SEND_WORDS = listOf("전송", "보내기", "메시지 전송", "Send")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val refresh = Runnable { updateBubble() }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        diag("서비스 연결됨")
        // 예전 '고급 받아쓰기'로 받아 둔 큰 파일(약 550MB)이 남아 있으면 지움
        Thread { filesDir.listFiles { f -> f.name.startsWith("ggml-") }?.forEach { it.delete() } }.start()
        updateBubble()
    }

    // ───────── 진단 기록 (설정 화면 '도우미 버튼 상태'에 보임) ─────────

    private var lastDiag = ""
    /** 마지막으로 앞에 나온 앱 (창 목록을 못 받는 기기를 위한 보조 정보) */
    private var lastPkg = ""

    private fun diag(s: String) {
        if (s == lastDiag) return
        lastDiag = s
        val t = java.text.SimpleDateFormat("M/d HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        Prefs(this).bubbleDiag = "$t  $s"
    }

    /** 위치 초기화 등으로 버튼을 다시 그림 */
    fun redrawBubble() {
        removeBubble()
        handler.postDelayed({ updateBubble() }, 200)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            // 키보드·상단바는 '앞에 나온 앱'으로 치지 않음
            val ignore = pkg.isEmpty() || pkg == packageName || "inputmethod" in pkg || "honeyboard" in pkg || pkg == "com.android.systemui"
            if (!ignore) lastPkg = pkg
        }
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 120)
        if (pendingSearch != null && event?.packageName == KAKAO) {
            handler.removeCallbacks(fillSearch)
            handler.postDelayed(fillSearch, 300)
        }
    }

    private val fillSearch = Runnable { fillSearchBox() }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        removeBubble()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideExportGuide()
        removeCover()
        removeBubble()
        instance = null
        super.onDestroy()
    }

    // ───────── 카톡 화면 읽기 ─────────

    private fun appWindows() = runCatching { windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } }.getOrDefault(emptyList())

    fun kakaoRoot(): AccessibilityNodeInfo? =
        appWindows().firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == KAKAO } }
            ?: rootInActiveWindow?.takeIf { it.packageName == KAKAO }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (visit(node)) return node
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, visit)?.let { return it }
        }
        return null
    }

    /** 카톡 화면에 보이는 글자들 (입력창 제외) */
    /**
     * 카톡 화면에 보이는 글. 오른쪽에 붙은 말풍선(내가 보낸 말)은 앞에 "나: " 를 붙여
     * AI 가 누가 한 말인지 헷갈리지 않게 함 (상대가 나를 부르는 '신부님' 같은 호칭을 상대에게 쓰는 일 방지)
     */
    /**
     * 대화 내보내기를 마친 뒤 카톡 설정 화면들을 뒤로 닫아 대화방으로 돌아감
     * (입력창이 보이면 대화방에 온 것). 많아야 네 번 뒤로.
     */
    fun backToChat(done: () -> Unit, left: Int = 4, waits: Int = 8) {
        val root = kakaoRoot()
        // 카톡 화면이 아직 앞에 안 왔으면(이 앱 화면이 닫히는 중) 조금 기다림. 끝내 안 오면 뒤로는 누르지 않음
        if (root == null) {
            if (waits > 0) handler.postDelayed({ backToChat(done, left, waits - 1) }, 250)
            else handler.post { done() }
            return
        }
        // 대화방(아래쪽 입력 칸)이 보이면 도착. 카톡 설정 화면에서만, 많아야 네 번 '뒤로' (휴대폰 기본 뒤로 키)
        if (inChatRoom() || left <= 0) { handler.postDelayed({ done() }, 250); return }
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ backToChat(done, left - 1, 4) }, 550)
    }

    /**
     * 화면에서 대화방이 차지한 부분. 폴드·태블릿처럼 큰 화면에서는 카톡이 왼쪽 채팅 목록과 오른쪽 대화방을
     * 함께 보여 주므로, 아래쪽 메시지 입력 칸이 있는 쪽만 대화방으로 봄. 보통 휴대폰이면 카톡 창 전체
     */
    private fun roomPane(root: AccessibilityNodeInfo): Rect {
        val win = bounds(root)
        val input = chatInput(root) ?: return win
        val ib = bounds(input)
        // 입력 칸 왼쪽 '+' 버튼과 오른쪽 이모티콘·# 버튼까지 대화방으로 침
        val l = maxOf(win.left, ib.left - Ui.dp(this, 90))
        val r = minOf(win.right, ib.right + Ui.dp(this, 140))
        return if (r - l > win.width() * 0.85) win else Rect(l, win.top, r, win.bottom)
    }

    /** 지금 카톡 대화방 이름 (대화방 부분 맨 위 왼쪽에 있는 제목 글자) */
    fun chatTitle(): String? {
        val root = kakaoRoot() ?: return null
        val win = bounds(root)
        val pane = roomPane(root)
        var best: AccessibilityNodeInfo? = null
        walk(root) { n ->
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && !n.isEditable && n.isVisibleToUser) {
                val b = bounds(n)
                val inPane = b.centerX() in pane.left..pane.right
                val top = b.top - win.top < win.height() * 0.15 && b.left - pane.left < pane.width() * 0.6
                if (inPane && top && !Regex("^\\d{1,2}:\\d{2}$").matches(t) && (best == null || b.top < bounds(best!!).top)) best = n
            }
            false
        }
        return best?.text?.toString()?.trim()
    }

    fun screenTexts(): List<String> {
        val root = kakaoRoot() ?: return emptyList()
        return textsIn(root, roomPane(root))
    }

    /** [node] 안의 글자들. 창 [win] 오른쪽에 붙은 말풍선은 "나: " 표시 */
    private fun textsIn(node: AccessibilityNodeInfo, win: Rect): List<String> {
        val w = win.width().coerceAtLeast(1)
        val out = ArrayList<String>()
        walk(node) { n ->
            if (!n.isEditable) {
                val t = n.text?.toString()?.trim()
                val b = bounds(n)
                // 대화방 부분 밖(큰 화면의 왼쪽 채팅 목록 등) 글은 뺌
                if (!t.isNullOrEmpty() && b.centerX() in win.left..win.right) {
                    val mine = b.left - win.left > w * 0.3 && win.right - b.right < w * 0.12 && b.width() < w * 0.8
                    val line = if (mine) "나: $t" else t
                    if (out.lastOrNull() != line) out += line
                }
            }
            out.size > 400
        }
        return out
    }

    // ───────── 예전 대화 모으기 (위로 넘기며) ─────────

    /** 대화 목록 (위아래로 넘길 수 있는 가장 큰 칸) */
    private fun chatList(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val lists = ArrayList<AccessibilityNodeInfo>()
        walk(root) { n -> if (n.isScrollable && n.isVisibleToUser) lists += n; false }
        return lists.maxByOrNull { bounds(it).let { b -> b.width().toLong() * b.height() } }
    }

    @Volatile private var collecting = false
    @Volatile private var collectingSince = 0L
    @Volatile private var stopCollect = false

    /** 앞 작업이 1분 넘게 안 끝났으면 멈춘 것으로 보고 새로 시작할 수 있게 함 */
    private fun startWork(): Boolean {
        val now = System.currentTimeMillis()
        if (collecting && now - collectingSince < 60_000) return false
        removeCover()
        collecting = true
        collectingSince = now
        stopCollect = false
        return true
    }

    // 넘기는 동안 화면을 덮는 안내 (대화가 휙휙 넘어가는 모습을 보이지 않게)
    private var cover: android.widget.LinearLayout? = null
    private var coverText: TextView? = null
    private var coverStop: TextView? = null

    private fun showCover(title: String = "예전 대화에서 찾고 있어요…", stopLabel: String = "그만 찾고 지금까지 본 데서 찾기") {
        if (cover != null) return
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#F21C1C1F"))
            isClickable = true   // 덮개 뒤 카톡이 눌리지 않게
            val p = Ui.dp(this@KakaoAccessibilityService, 32); setPadding(p, p, p, p)
        }
        box.addView(TextView(this).apply {
            text = "🔎"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            gravity = Gravity.CENTER
        })
        coverText = TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@KakaoAccessibilityService, 16), 0, Ui.dp(this@KakaoAccessibilityService, 32))
        }
        box.addView(coverText)
        box.addView(TextView(this).apply {
            coverStop = this
            text = stopLabel
            setTextColor(CHARCOAL_BOTTOM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            val p = Ui.dp(this@KakaoAccessibilityService, 14); setPadding(p * 2, p, p * 2, p)
            background = Ui.rounded(this@KakaoAccessibilityService, android.graphics.Color.WHITE, 24)
            setOnClickListener { stopCollect = true; coverText?.text = "지금까지 본 대화로 찾을게요…" }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(box, lp); cover = box }
    }

    // ───────── 플레이판: 직접 내보낼 때 화면 위에 남는 순서 안내 (카톡을 대신 누르지 않음) ─────────
    private var guide: View? = null
    private var guideTitleView: TextView? = null
    private var guideTitles: Pair<String, String>? = null
    private var guideBody: View? = null
    private var guideHint: View? = null
    private var guideFold: TextView? = null
    private var guideCompact = false

    /**
     * '찾을 말 넣기' 안내: 대화방 밖(목록·다른 탭)에서는 작은 한 줄로 접고, 대화방에 들어가면 다시 펼침.
     * 접힌 동안에도 ✕ 로 닫을 수 있음
     */
    private fun setGuideCompact(compact: Boolean) {
        if (guide == null || compact == guideCompact) return
        guideCompact = compact
        val (full, short) = guideTitles ?: return
        guideTitleView?.let { it.text = if (compact) short else full; Ui.iconize(it) }
        guideBody?.visibility = if (compact) View.GONE else View.VISIBLE
        guideHint?.visibility = if (compact) View.GONE else View.VISIBLE
        guideFold?.visibility = if (compact) View.GONE else View.VISIBLE
        guide?.post { avoidKeyboard() }
    }

    /** 지금 떠 있는 안내 띠가 '찾을 말 넣기' 안내인지 (카톡을 떠나면 닫음. 내보내기 안내는 공유 창에서도 남겨 둠) */
    private var guideIsSearch = false
    private val guideTimeout = Runnable { hideExportGuide() }

    /** 카드를 닫은 뒤에도 내보내는 순서를 위쪽에 작게 띄워 둠. 뒤의 카톡은 그대로 누를 수 있음 */
    fun showExportGuide() {
        pendingSearch = null   // '찾을 말 넣기'를 기다리던 것은 그만 (설정 화면 글 칸에 잘못 넣지 않게)
        val app = getString(R.string.app_name)
        val body = android.text.SpannableStringBuilder(
            "① 오른쪽 위 ☰\n② 아래쪽 ⚙ 설정\n③ 대화 내용 내보내기\n④ 텍스트 메시지만 저장\n")
        val start = body.length
        body.append("⑤ 앱 목록에서 '$app' 누르기\n    (아래에 '대화에서 찾기'라고 써 있어요)")
        // 마지막 단계(우리 앱 고르기)는 노랗고 굵게
        body.setSpan(android.text.style.ForegroundColorSpan(Ui.YELLOW), start, body.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        body.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, body.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        showGuide("📤 대화 내보내기 순서", body)
    }

    /**
     * 반투명한 작은 안내 띠. 손가락으로 위아래로 끌어 옮길 수 있고, '접기'로 작은 단추만 남길 수 있음.
     * 카톡을 대신 누르지 않음 (안내만)
     */
    private fun showGuide(title: String, body: CharSequence, search: Boolean = false, shortTitle: String? = null) {
        hideExportGuide()
        guideIsSearch = search
        val ctx = this
        val dark = android.graphics.Color.argb(225, 0x2A, 0x16, 0x16)   // 진한 갈색, 살짝 비침
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = Ui.rounded(ctx, dark, 18, GOLD)
            val ph = Ui.dp(ctx, 14); val pv = Ui.dp(ctx, 10); setPadding(ph, pv, Ui.dp(ctx, 8), pv)
            elevation = Ui.dp(ctx, 8).toFloat()
        }
        val head = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(ctx).apply {
            guideTitleView = this
            guideTitles = title to (shortTitle ?: title)
            text = title
            setTextColor(Ui.YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            Ui.iconize(this)
        }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun chip(label: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val p = Ui.dp(ctx, 6); setPadding(p * 2, p, p * 2, p)
            background = Ui.rounded(ctx, android.graphics.Color.argb(60, 255, 255, 255), 12)
            setOnClickListener { onClick() }
        }
        val bodyView = TextView(ctx).apply {
            text = body
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            Ui.iconize(this)
            setLineSpacing(0f, 1.25f)
            setPadding(0, Ui.dp(ctx, 6), Ui.dp(ctx, 6), 0)
        }
        val fold = chip("접기") {}
        guideFold = fold
        fold.setOnClickListener {
            val show = bodyView.visibility != View.VISIBLE
            bodyView.visibility = if (show) View.VISIBLE else View.GONE
            fold.text = if (show) "접기" else "펴기"
        }
        head.addView(fold)
        head.addView(chip("✕") { hideExportGuide() }.also { Ui.iconize(it) }, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 6) })
        box.addView(head)
        box.addView(bodyView)
        guideBody = bodyView
        box.addView(TextView(ctx).apply {
            guideHint = this
            text = "끌어서 옮길 수 있어요"
            setTextColor(android.graphics.Color.argb(150, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, Ui.dp(ctx, 4), 0, 0)
        })
        val m = Ui.dp(ctx, 10)
        val lp = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - m * 2, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 카톡 맨 위 제목줄(☰)은 가리지 않게 그 아래에. 지난번에 옮겨 둔 자리가 있으면 거기
            y = Prefs(ctx).guideY.takeIf { it >= 0 } ?: Ui.dp(ctx, 110)
        }
        // 띠를 끌면 위아래로 옮김 (자리 기억)
        var downY = 0f; var startY = 0
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    val maxY = resources.displayMetrics.heightPixels - box.height
                    lp.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, maxOf(0, maxY))
                    runCatching { wm.updateViewLayout(box, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> { Prefs(ctx).guideY = lp.y; box.post { avoidKeyboard() }; true }
                else -> false
            }
        }
        runCatching { wm.addView(box, lp); guide = box; box.post { avoidKeyboard() } }
            .onFailure { diag("안내 띠 그리기 실패: ${it.javaClass.simpleName} ${it.message}") }
        handler.removeCallbacks(guideTimeout)
        handler.postDelayed(guideTimeout, 15 * 60_000L)
    }

    // ───────── 찾을 말 넣기: 사용자가 카톡 🔍를 누르면, 열린 검색 칸에 찾을 말을 넣어 줌 (검색은 사용자가 누름) ─────────
    private var pendingSearch: String? = null
    private var pendingSearchAt = 0L

    /** [room] 은 여러 방에서 찾았을 때 그 말이 있는 방 이름 (그 방에 들어가라고 안내) */
    fun waitForSearchBox(word: String, room: String? = null) {
        pendingSearch = word
        pendingSearchAt = System.currentTimeMillis()
        // 카톡 🔍를 눌러 검색 칸이 열려도 화면 전환 알림이 오지 않을 수 있어, 기다리는 동안 0.5초마다 살펴봄
        handler.removeCallbacks(searchPoll)
        handler.postDelayed(searchPoll, 500)
        val title = if (room != null) "🔍 '$room' 방에 들어가 오른쪽 위 돋보기를 누르세요" else "🔍 대화방 오른쪽 위 돋보기를 누르세요"
        showGuide(title, "검색 칸이 열리면 '$word'를 넣어 드려요.\n그다음 키보드의 검색(🔍)을 눌러 주세요.", search = true,
            shortTitle = "🔍 '$word' 찾기" + (room?.let { " · '$it' 방에서" } ?: ""))
    }

    /** 카톡 화면이 바뀔 때마다: 위쪽에 새로 열린 빈 글 칸(대화방 검색 칸)이 있으면 찾을 말을 넣음 */
    private val searchPoll: Runnable = object : Runnable {
        override fun run() {
            if (pendingSearch == null) return
            fillSearchBox()
            if (pendingSearch != null) handler.postDelayed(this, 500)
        }
    }

    private fun fillSearchBox() {
        val word = pendingSearch ?: return
        if (System.currentTimeMillis() - pendingSearchAt > 3 * 60_000) { pendingSearch = null; hideExportGuide(); return }
        val root = kakaoRoot() ?: return
        val win = bounds(root)
        val box = walk(root) {
            it.isEditable && it.isVisibleToUser && bounds(it).top - win.top < win.height() * 0.3
        } ?: return
        pendingSearch = null
        hideExportGuide()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, word) }
        if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            android.widget.Toast.makeText(this, "'$word'를 넣었어요. 키보드의 검색(🔍)을 눌러 주세요.", android.widget.Toast.LENGTH_LONG).show()
        } else {
            android.widget.Toast.makeText(this, "'$word'는 복사돼 있어요. 검색 칸을 꾹 눌러 붙여 넣어 주세요.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun hideExportGuide() {
        guideIsSearch = false
        guideCompact = false
        guideTitleView = null; guideBody = null; guideHint = null; guideFold = null; guideTitles = null
        handler.removeCallbacks(guideTimeout)
        val had = guide != null
        guide?.let { runCatching { wm.removeView(it) } }
        guide = null
        if (had) handler.post { avoidKeyboard() }   // 띠를 피해 옮겼던 버튼을 제자리로
    }

    private fun removeCover() {
        cover?.let { runCatching { wm.removeView(it) } }
        cover = null
        coverText = null
        coverStop = null
    }

    /**
     * 사용자가 '찾기'를 했을 때, 지금 대화방을 위로 넘기며 예전 대화 글을 모음.
     * [maxPages] 쪽 또는 [maxMs] 가 지나거나 대화 처음에 닿으면 멈추고, 원래 자리(맨 아래)로 돌아간 뒤 [done].
     * 모은 글은 휴대폰 안에 있고, 질문과 관련된 부분만 AI 에 보냄.
     */
    fun collectHistory(maxPages: Int = 40, maxMs: Long = 15_000, progress: (Int) -> Unit, done: (List<String>) -> Unit) {
        if (!startWork()) { done(emptyList()); return }
        showCover()
        val start = System.currentTimeMillis()
        var acc: List<String> = emptyList()
        var pages = 0
        var scrolled = 0

        fun finish() {
            // 넘긴 만큼 다시 아래로 (맨 아래 최신 대화로 돌아감)
            fun back(left: Int) {
                val list = chatList(kakaoRoot())
                if (left <= 0 || list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    collecting = false
                    removeCover()
                    done(acc)
                    return
                }
                handler.postDelayed({ back(left - 1) }, 120)
            }
            back(scrolled + 3)
        }

        fun step() {
            val root = kakaoRoot()
            val list = chatList(root)
            if (root == null || list == null) { finish(); return }
            val page = textsIn(list, roomPane(root))
            val top = acc.isNotEmpty() && kr.yunho.talkhelper.core.History.reachedTop(acc, page)
            acc = kr.yunho.talkhelper.core.History.prepend(acc, page)
            pages++
            progress(pages)
            coverText?.text = "예전 대화에서 찾고 있어요…\n(${pages}쪽 살펴봄)"
            if (stopCollect) coverText?.text = "지금까지 본 대화로 찾을게요…"
            if (top || stopCollect || pages >= maxPages || System.currentTimeMillis() - start > maxMs) { finish(); return }
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) { finish(); return }
            scrolled++
            handler.postDelayed({ step() }, 450)   // 예전 대화가 그려질 때까지 잠깐 기다림
        }
        step()
    }

    /**
     * 대화방을 위로 넘기며 [word] 가 들어 있는 메시지가 화면에 보이면 멈춤 (가장 최근 것부터).
     * 넘기는 동안은 덮개로 가림. 찾으면 그 메시지가 보이는 상태로 [done](true)
     */
    fun scrollToText(words: List<String>, maxPages: Int = 120, done: (Boolean) -> Unit) {
        if (!startWork()) { done(false); return }
        showCover("메시지로 이동하는 중이에요…", "그만")
        var pages = 0
        var last: List<String> = emptyList()
        fun end(ok: Boolean) { collecting = false; removeCover(); done(ok) }
        fun step() {
            if (stopCollect) { end(false); return }
            val root = kakaoRoot()
            val list = chatList(root)
            if (root == null || list == null) { end(false); return }
            // 앞의 것(원래 문장)이 더 정확하므로 먼저 찾음
            val hit = words.firstNotNullOfOrNull { w -> walk(list) { n -> n.isVisibleToUser && n.text?.toString()?.contains(w) == true } }
            if (hit != null) {
                hit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                handler.postDelayed({ end(true) }, 300)
                return
            }
            val page = textsIn(list, roomPane(root))
            if (page == last || pages >= maxPages) { end(false); return }   // 맨 처음에 닿았거나 너무 오래
            last = page
            pages++
            coverText?.text = "메시지로 이동하는 중이에요…\n(${pages}쪽 올라감)"
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) { end(false); return }
            handler.postDelayed({ step() }, 400)
        }
        step()
    }

    // ───────── 대화 내보내기 (사용자가 허락했을 때 카톡 메뉴를 대신 눌러 줌) ─────────

    /** 화면에 떠 있는 모든 창(카톡, 공유 창 등)의 뿌리 */
    private fun allRoots(): List<AccessibilityNodeInfo> =
        (runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()) + listOfNotNull(rootInActiveWindow))
            .filter { it.packageName != packageName }

    /** [iconOnly] 면 아이콘 버튼 설명(contentDescription)만 봄 — 대화 글에 같은 낱말이 있어도 누르지 않게 */
    private fun findAny(match: (String) -> Boolean, iconOnly: Boolean = false): AccessibilityNodeInfo? {
        for (r in allRoots()) {
            walk(r) { n ->
                val l = if (iconOnly) n.contentDescription?.toString()?.trim().orEmpty() else label(n)
                n.isVisibleToUser && l.isNotEmpty() && match(l)
            }?.let { return it }
        }
        return null
    }

    /** [match] 에 맞는 것을 찾아 누름. 안 보이면 [scroll] 이면 목록을 넘겨 가며 [tries] 번 다시 봄 */
    private fun tapWhenShown(match: (String) -> Boolean, tries: Int, scroll: Boolean, iconOnly: Boolean = false, then: (Boolean) -> Unit) {
        if (stopCollect) { then(false); return }
        val n = findAny(match, iconOnly)
        if (n != null && tapNode(n)) {
            handler.postDelayed({ then(true) }, 900)
            return
        }
        if (tries <= 0) { then(false); return }
        if (scroll) {
            allRoots().forEach { r ->
                walk(r) { x -> x.isScrollable && x.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        }
        handler.postDelayed({ tapWhenShown(match, tries - 1, scroll, iconOnly, then) }, 450)
    }

    /**
     * 누르기: 누를 수 있는 칸이면 그 칸을 누르고, 아니면(글자만 있는 줄) 그 자리를 손가락으로 톡 누르듯 터치
     */
    private fun tapNode(n: AccessibilityNodeInfo): Boolean {
        clickable(n)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        val b = bounds(n)
        if (b.isEmpty) return false
        val path = android.graphics.Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 공유 창에 이 앱이 보이는지 */
    private fun shareTargetShown(appName: String) =
        findAny({ it == "대화에서 찾기" || it == appName || it.startsWith(appName) }) != null

    /**
     * 4단계: '텍스트만 보내기' 고르기. 이름이 기종마다 조금 달라 넓게 찾고,
     * 확인 창이 끼어들면 '확인'을 누르고, 이미 공유 창이 떴으면 넘어감
     */
    private fun pickTextOnly(appName: String, tries: Int, then: (Boolean) -> Unit) {
        if (stopCollect) { then(false); return }
        if (shareTargetShown(appName)) { then(true); return }
        // 기종마다 '텍스트만 보내기' / '텍스트 메시지만 저장'(외부로 전송) 등 이름이 다름. '모든 메시지 내부저장소'는 제외
        val option = findAny({ (it.contains("텍스트") || it.contains("Text", ignoreCase = true)) && !it.contains("내부") && !it.contains("모든") && !it.contains("Internal", ignoreCase = true) })
        if (option != null && tapNode(option)) {
            Prefs(this).logExport("4 '${label(option).take(20)}' 누름")
            // 고른 뒤 '보내기/확인' 버튼이 따로 있는 화면이면 그것도 누름
            handler.postDelayed({
                if (!shareTargetShown(appName)) findAny({ it == "보내기" || it == "확인" || it == "내보내기" || it == "OK" })?.let { tapNode(it) }
                handler.postDelayed({ then(true) }, 700)
            }, 700)
            return
        }
        // 확인 창이 먼저 떴으면 확인
        findAny({ it == "확인" || it == "OK" || it == "내보내기" })?.takeIf { label(it).length <= 5 }?.let {
            Prefs(this).logExport("4 확인 창 '${label(it)}' 누름")
            tapNode(it)
        }
        if (tries <= 0) { then(false); return }
        handler.postDelayed({ pickTextOnly(appName, tries - 1, then) }, 450)
    }

    /** 공유 창에서 이 앱을 찾아 누름. 안 보이면 '더보기'를 한 번 열고, 그래도 없으면 목록을 넘겨 가며 찾음 */
    private fun pickShareTarget(appName: String, tries: Int, openedMore: Boolean, then: (Boolean) -> Unit) {
        if (stopCollect) { then(false); return }
        findAny({ it == "대화에서 찾기" || it == appName || it.startsWith(appName) })?.let { n ->
            if (tapNode(n)) { handler.postDelayed({ then(true) }, 900); return }
        }
        if (tries <= 0) { then(false); return }
        var more = openedMore
        if (!more) {
            findAny({ it == "더보기" || it == "More" || it.contains("더 보기") })?.let { n ->
                if (tapNode(n)) { more = true; Prefs(this).logExport("5 공유 창 '더보기' 누름") }
            }
        } else {
            allRoots().forEach { r -> walk(r) { x -> x.isScrollable && x.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
        }
        handler.postDelayed({ pickShareTarget(appName, tries - 1, more, then) }, 600)
    }

    /**
     * 지금 카톡 대화방의 '대화 내용 내보내기 → 텍스트만 보내기'를 눌러 이 앱('대화에서 찾기')으로 보냄.
     * 카톡 화면 구성이 바뀌어 못 찾으면 [done](false, 막힌 단계)
     */
    fun exportChat(done: (Boolean, String) -> Unit) {
        val log = Prefs(this)
        if (!startWork()) { log.logExport("내보내기: 다른 작업 중이라 시작 못 함"); done(false, "시작"); return }
        log.logExport("내보내기 시작")
        showCover("이 대화방의 대화를 내보내는 중이에요…\n내보낸 대화는 이 휴대폰 안에만 보관돼요", "그만두기")
        val appName = getString(R.string.app_name)
        fun end(ok: Boolean, step: String) {
            collecting = false
            if (ok) {
                log.logExport("내보내기: 공유 창에서 이 앱을 고름")
                removeCover()
            } else {
                // 어디서 막혔는지 기록 (그때 화면에 보이던 버튼 이름들도)
                val seen = allRoots().flatMap { r ->
                    val l = ArrayList<String>()
                    walk(r) { n -> if (n.isVisibleToUser && (n.isClickable || n.contentDescription != null)) label(n).takeIf { it.isNotEmpty() }?.let { l += it.take(20) }; l.size > 25 }
                    l
                }.distinct().take(25)
                log.logExport("내보내기 막힘: $step · 보이던 것: ${seen.joinToString(", ")}")
                // 덮개를 남겨 직접 할 순서를 크게 보여 줌 (닫기를 눌러야 사라짐)
                coverText?.text = "$step 단계를 자동으로 못 눌렀어요.\n\n직접 이렇게 눌러 주세요:\n☰ → ⚙ 설정 → 대화 내용 내보내기\n→ 텍스트 메시지만 저장 → '${getString(R.string.app_name)}'\n\n물어보신 질문은 기억해 두었다가 이어서 찾아 드려요."; coverText?.let { Ui.iconize(it) }
                coverStop?.text = "닫고 직접 하기"
                coverStop?.setOnClickListener { removeCover() }
            }
            done(ok, step)
        }
        // 1) 오른쪽 위 ☰ 메뉴
        tapWhenShown({ it.contains("메뉴") || it.contains("서랍") || it == "Menu" }, 8, false, iconOnly = true, then = { ok1 ->
            if (!ok1) return@tapWhenShown end(false, "오른쪽 위 ☰ 메뉴")
                        log.logExport("1 메뉴 열림")
            // 2) 서랍 아래 ⚙ 설정
            tapWhenShown({ it == "설정" || it == "채팅방 설정" || it.contains("Settings") }, 8, false, iconOnly = true, then = { ok2 ->
                if (!ok2) return@tapWhenShown end(false, "아래쪽 ⚙ 설정")
                        log.logExport("2 설정 열림")
                // 3) 대화 내용 내보내기
                tapWhenShown({ it.contains("대화 내용 내보내기") || it.contains("Export") }, 10, true) { ok3 ->
                    if (!ok3) return@tapWhenShown end(false, "'대화 내용 내보내기'")
                        log.logExport("3 내보내기 누름")
                    // 4) 텍스트만 보내기
                    pickTextOnly(appName, 12) { ok4 ->
                        if (!ok4) return@pickTextOnly end(false, "'텍스트만 보내기'")
                        log.logExport("4 텍스트만 보내기 누름")
                        // 5) 공유 창에서 이 앱('대화에서 찾기') 고르기 — 첫 줄에 없으면 '더보기'를 열어 찾음
                        pickShareTarget(appName, 16, false) { ok5 ->
                            end(ok5, if (ok5) "" else "공유 창에서 '대화에서 찾기'")
                        }
                    }
                }
            })
        })
    }

    private fun findEditable(root: AccessibilityNodeInfo?) = walk(root) { it.isEditable && it.isVisibleToUser }

    private fun label(n: AccessibilityNodeInfo) = (n.contentDescription ?: n.text)?.toString()?.trim().orEmpty()

    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    /** 누를 수 있는 가장 가까운 조상 (자기 자신 포함) */
    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        while (n != null && !n.isClickable) n = n.parent
        return n
    }

    /**
     * 전송 버튼 찾기.
     * 1) 알려진 버튼 id  2) '전송'/'보내기' 글자  3) 입력창과 같은 줄에서 가장 오른쪽에 있는 누를 수 있는 버튼
     */
    private fun findSend(root: AccessibilityNodeInfo?, edit: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (id in SEND_IDS) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser }?.let { n -> clickable(n)?.let { return it } }
        }
        walk(root) { n -> n.isVisibleToUser && SEND_WORDS.any { w -> label(n) == w || label(n).startsWith("$w ") } }
            ?.let { n -> clickable(n)?.let { return it } }
        if (edit == null) return null
        val e = bounds(edit)
        val candidates = ArrayList<AccessibilityNodeInfo>()
        walk(root) { n ->
            val l = label(n)
            val other = NOT_SEND.any { l.contains(it) }
            if (n.isClickable && n.isVisibleToUser && !n.isEditable && !other) {
                val r = bounds(n)
                val sameRow = r.centerY() in (e.top - e.height())..(e.bottom + e.height())
                if (sameRow && r.left >= e.right - 4 && r.width() < e.width()) candidates += n
            }
            false
        }
        return candidates.maxByOrNull { bounds(it).right }
    }

    /** 실패했을 때 원인을 알 수 있도록 입력창 근처의 버튼 정보를 기록 */
    private fun describeInputRow(root: AccessibilityNodeInfo?, edit: AccessibilityNodeInfo?): String {
        if (root == null) return "카톡 화면을 찾지 못함"
        val e = edit?.let { bounds(it) }
        val sb = StringBuilder()
        if (edit != null) sb.append("입력창: ${edit.viewIdResourceName} ${e}\n")
        walk(root) { n ->
            val r = bounds(n)
            val near = e == null || r.centerY() in (e.top - e.height())..(e.bottom + e.height())
            if (near && n.isVisibleToUser && (n.isClickable || label(n).isNotEmpty())) {
                sb.append("- ${n.className?.toString()?.substringAfterLast('.')} id=${n.viewIdResourceName?.substringAfter('/')} " +
                    "글=\"${label(n).take(20)}\" 누름=${n.isClickable} 위치=${r.left},${r.top}~${r.right},${r.bottom}\n")
            }
            sb.length > 3000
        }
        return sb.toString()
    }

    /**
     * 카톡 대화방의 🔍(대화 검색)을 눌러 [keyword] 를 넣고 검색 → 그 대화 위치로 이동.
     * 카톡 화면 구조에 따라 실패할 수 있음 (그때는 false)
     */
    fun searchInChat(keyword: String, done: (Boolean) -> Unit, attempt: Int = 0) {
        val root = kakaoRoot()
        val searchBtn = root?.let { r ->
            walk(r) { n -> n.isVisibleToUser && label(n).let { it == "검색" || it == "대화 검색" || it == "Search" } }
        }?.let { clickable(it) }
        if (searchBtn == null) {
            if (attempt < 8) handler.postDelayed({ searchInChat(keyword, done, attempt + 1) }, 300) else done(false)
            return
        }
        searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            // 검색칸은 화면 위쪽에 열림 → 가장 위에 있는 입력칸
            val r = kakaoRoot()
            val edits = ArrayList<AccessibilityNodeInfo>()
            walk(r) { n -> if (n.isEditable && n.isVisibleToUser) edits += n; false }
            val field = edits.minByOrNull { bounds(it).top }
            if (field == null) { done(false); return@postDelayed }
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, keyword) }
            val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok && Build.VERSION.SDK_INT >= 30) {
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            done(ok)
        }, 700)
    }

    enum class SendResult { SENT, TYPED_ONLY, FAILED }

    /**
     * 카톡 입력창에 [text] 를 넣고, 설정에 따라 전송 버튼도 누름.
     * 도우미 화면이 닫히고 카톡이 다시 앞에 올 때까지 잠깐 기다렸다가 시도함.
     */
    fun typeAndSend(text: String, pressSend: Boolean, done: (SendResult) -> Unit, attempt: Int = 0) {
        val root = kakaoRoot()
        val edit = findEditable(root)
        if (edit == null) {
            if (attempt < 10) handler.postDelayed({ typeAndSend(text, pressSend, done, attempt + 1) }, 300)
            else { Prefs(this).lastSendLog = "입력창을 찾지 못함\n" + describeInputRow(root, null); done(SendResult.FAILED) }
            return
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            Prefs(this).lastSendLog = "입력창에 글을 넣지 못함\n" + describeInputRow(root, edit)
            done(SendResult.FAILED); return
        }
        if (!pressSend) { done(SendResult.TYPED_ONLY); return }
        // 글자가 들어간 뒤에야 전송 버튼이 나타나므로 기다렸다가 몇 번 시도
        pressSendButton(0, done)
    }

    private fun pressSendButton(attempt: Int, done: (SendResult) -> Unit) {
        handler.postDelayed({
            val root = kakaoRoot()
            val edit = findEditable(root)
            val send = findSend(root, edit)
            if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                // 전송되면 입력창이 비워짐 → 확인
                handler.postDelayed({
                    val left = findEditable(kakaoRoot())?.text?.toString().orEmpty()
                    val hint = findEditable(kakaoRoot())?.hintText?.toString().orEmpty()
                    if (left.isBlank() || left == hint) { Prefs(this).lastSendLog = "성공"; done(SendResult.SENT) }
                    else { Prefs(this).lastSendLog = "전송 버튼을 눌렀지만 글이 남아 있음\n" + describeInputRow(kakaoRoot(), findEditable(kakaoRoot())); done(SendResult.TYPED_ONLY) }
                }, 600)
            } else if (attempt < 2) {
                pressSendButton(attempt + 1, done)
            } else {
                Prefs(this).lastSendLog = "전송 버튼을 찾지 못함\n" + describeInputRow(root, edit)
                done(SendResult.TYPED_ONLY)
            }
        }, 600)
    }

    // ───────── 떠 있는 버튼 ─────────

    private val leftRoomCheck = Runnable {
        val a = HelperActivity.openInRoom ?: return@Runnable
        if (kakaoRoot() != null && !inChatRoom()) a.closeBecauseLeftRoom()
    }

    /** 지금 카톡 화면이 대화방인지: 화면 아래 절반에 보이는 글 입력 칸이 있으면 대화방 (위쪽 검색 칸은 치지 않음) */
    private fun inChatRoom(): Boolean {
        val root = kakaoRoot() ?: return false
        return chatInput(root) != null
    }

    /** 화면에 떠 있는 키보드의 윗선 (없으면 null) */
    private fun keyboardTop(): Int? = runCatching {
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .map { Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { it.height() > 0 }
            .minOfOrNull { it.top }
    }.getOrNull()

    /**
     * 대화방 아래쪽 메시지 입력 칸. 키보드가 올라오면 카톡이 화면 전체 크기 그대로 입력 칸만 키보드 위로 올리는 폰이 있어
     * (큰 키보드면 입력 칸이 화면 가운데보다 위로 감), 키보드 위쪽까지만 카톡 화면으로 보고 그 아래 절반에서 찾음
     */
    private fun chatInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val win = bounds(root)
        val bottom = keyboardTop()?.takeIf { it > win.top + win.height() / 4 && it < win.bottom }?.let { minOf(it, win.bottom) } ?: win.bottom
        val mid = win.top + (bottom - win.top) / 2
        return walk(root) { it.isEditable && it.isVisibleToUser && bounds(it).centerY() > mid }
    }

    /** 지금 카톡 화면이 대화방 목록('채팅' 탭)인지: 맨 위 제목이 '채팅'. (대화방 안이 아닐 때만 봄) */
    /**
     * 지금 카톡 화면이 대화방 목록인지 (대화방 안이 아닐 때만 봄):
     *  - '채팅' 탭: 위쪽 '채팅' 제목(글자·설명 어느 쪽이든) 또는 아래 탭 줄의 골라진 '채팅' 탭 ('안읽음' 칩을 골랐으면 아님)
     *  - 오픈채팅 목록: 위쪽에 '오픈채팅'(제목이나 '지금' 탭의 골라진 칩)이 있고, '숏폼' 등 다른 칩이 골라져 있지 않음
     * 친구·쇼핑·더보기 같은 다른 탭에서는 false → 버튼을 숨김
     */
    private fun inChatList(): Boolean {
        val root = kakaoRoot() ?: return false
        val win = bounds(root)
        val chatNames = setOf("채팅", "Chats", "채팅 목록", "채팅 탭")
        val openNames = setOf("오픈채팅", "OpenChat", "오픈 채팅")
        var chat = false; var open = false; var otherChip = false; var unread = false
        walk(root) { n ->
            if (!n.isVisibleToUser) return@walk false
            val l = label(n).removeSuffix(" 탭").trim()
            if (l.isEmpty()) return@walk false
            val b = bounds(n)
            val top = b.top - win.top < win.height() * 0.25 && b.left - win.left < win.width() * 0.7
            val picked = n.isSelected || n.isChecked
            when {
                l in chatNames && (top || picked) -> chat = true
                n.isSelected && l.startsWith("채팅") -> chat = true
                l in openNames && top -> open = true
                // '지금' 탭에서 오픈채팅이 아닌 다른 칩(숏폼 등)을 골랐으면 오픈채팅 목록이 아님
                top && picked && (l == "숏폼" || l.contains("Shorts", ignoreCase = true)) -> otherChip = true
                // 채팅 탭의 '안읽음' 칩을 골랐으면 버튼을 띄우지 않음 (사용자 요청)
                top && picked && (l.startsWith("안읽음") || l.startsWith("안 읽음") || l.equals("Unread", ignoreCase = true)) -> unread = true
            }
            false
        }
        return (chat && !unread) || (open && !otherChip && !unread)
    }

    /** 카톡 화면 위쪽에 보이는 이름들 (골라진 것은 [*]) — 진단용 */
    private fun topLabels(): String {
        val root = kakaoRoot() ?: return ""
        val win = bounds(root)
        val out = LinkedHashSet<String>()
        walk(root) { n ->
            if (n.isVisibleToUser) {
                val l = label(n)
                val b = bounds(n)
                if (l.isNotEmpty() && l.length <= 12 && b.top - win.top < win.height() * 0.25) out += l + if (n.isSelected || n.isChecked) "[*]" else ""
            }
            out.size >= 10
        }
        return out.joinToString(",")
    }

    /** 떠 있는 버튼의 종류: 대화방(일반·오픈채팅)에선 🎤, 채팅·오픈채팅 목록에선 🔎(저장된 모든 대화에서 찾기), 그 밖에선 숨김 */
    private enum class BubbleKind { MIC, SEARCH }
    private var bubbleKind = BubbleKind.MIC

    private fun updateBubble() {
        val wins = appWindows()
        val apps = wins.mapNotNull { it.root?.packageName?.toString() }
        val kakao = if (BuildConfig.STORE_SAFE) {
            // 플레이판은 접근성 설정에서 카톡·이 앱만 보도록 제한함(packageNames). 다른 앱 창은 내용이 안 보여(root=null)
            // '앱이 없다'로 보이므로, 카톡 창이 실제로 보일 때만 카톡으로 침. 창 목록을 못 받으면 지금 활성 창으로 판단
            KAKAO in apps || (wins.isEmpty() && rootInActiveWindow?.packageName == KAKAO)
        } else {
            // 창 목록을 못 받는 기기에서는 마지막으로 앞에 나온 앱으로 판단
            KAKAO in apps || (apps.isEmpty() && lastPkg == KAKAO)
        }
        // 카톡 안에서도 대화방(🎤)과 대화방 목록(🔎)에서만 (쇼핑·설정·친구 목록 등에서는 숨김)
        val room = kakao && inChatRoom()
        val list = kakao && !room && inChatList()
        // 🎤 카드를 연 채 뒤로가기 등으로 대화방을 나가면 카드(마이크)도 닫음
        //   (화면이 잠깐 바뀌는 중일 수 있어 0.7초 뒤 한 번 더 보고 그래도 대화방이 아니면 닫음)
        if (kakao && !room && HelperActivity.openInRoom != null) {
            handler.removeCallbacks(leftRoomCheck)
            handler.postDelayed(leftRoomCheck, 700)
        }
        // '찾을 말 넣기' 안내는 카톡을 떠나면 닫음 (다른 앱·홈 화면에 남지 않게)
        if (!kakao && guideIsSearch) { pendingSearch = null; hideExportGuide() }
        // 카톡 안: 대화방에서는 펼치고, 채팅 목록에서는 한 줄로 접고, 오픈채팅·쇼핑·더보기 같은 다른 화면에서는 숨김
        if (kakao && guideIsSearch) {
            setGuideCompact(!room)
            guide?.visibility = if (room || list) View.VISIBLE else View.GONE
        }
        val show = (room || list) && packageName !in apps && Prefs(this).enabled
        if (show) { showBubble(if (room) BubbleKind.MIC else BubbleKind.SEARCH); avoidKeyboard() } else removeBubble()
        // 플레이판: 카톡을 떠날 때(홈·다른 앱) 그쪽 이벤트는 받지 않으므로, 버튼이 떠 있는 동안 1초마다 다시 확인
        // 카톡 안에서 탭(채팅·오픈채팅·쇼핑 등)을 옮기면 창 바뀜 알림이 오지 않는 폰이 많아,
        // 카톡이 앞에 있는 동안은 버튼이 숨어 있어도 1초마다 다시 봄 (카톡을 떠나면 멈춤)
        if (kakao || (BuildConfig.STORE_SAFE && bubble != null)) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 600)
        }
        // 카톡 안인데 대화방·목록이 아니면, 위쪽에 보이는 이름들을 기록 (오픈채팅 목록을 못 알아볼 때 원인 확인용)
        val topNames = if (kakao && !room && !list) topLabels() else ""
        // 대화방으로 본 까닭 (목록인데 🎤가 뜰 때 원인 확인용): 카톡 창 크기와 입력 칸 자리
        val why = if (room) kakaoRoot()?.let { r -> val w = bounds(r); chatInput(r)?.let { i -> val b = bounds(i); "창 ${w.width()}x${w.height()} 입력칸 ${b.left}~${b.right},${b.top} · " } }.orEmpty() else ""
        diag("$why${if (topNames.isNotEmpty()) "위쪽: $topNames · " else ""}앱 창 ${wins.size}개 [${apps.joinToString().ifBlank { "없음" }}] · 최근 앱 ${lastPkg.ifBlank { "-" }} · 카톡 ${if (kakao) "인식" else "못 찾음"} · 대화방 ${if (room) "예" else if (list) "목록" else "아니오"} · 버튼 ${if (bubble != null) "표시 중" else "숨김"}")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble(kind: BubbleKind) {
        if (bubble != null && bubbleKind == kind) return
        if (bubble != null) removeBubble()   // 종류가 바뀌면(대화방 ↔ 목록) 새로 그림
        bubbleKind = kind
        val size = Ui.dp(this, 62)
        // 차콜 원 + 얇은 금색 테두리 + 흰 마이크(또는 돋보기) 하나 (글자 없이 간결하게)
        val v = android.widget.ImageView(this).apply {
            if (kind == BubbleKind.MIC) setImageResource(R.drawable.ic_bubble_mic)
            else {
                setImageResource(R.drawable.ic_search)
                imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                scaleX = 1.15f; scaleY = 1.15f
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(CHARCOAL_TOP, CHARCOAL_BOTTOM),
            ).apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(Ui.dp(this@KakaoAccessibilityService, 2), GOLD)
            }
            elevation = Ui.dp(this@KakaoAccessibilityService, 10).toFloat()
            contentDescription = if (kind == BubbleKind.MIC) getString(R.string.app_name) + " 열기. 길게 누르면 말투와 찾기 메뉴"
                else "저장된 모든 대화에서 찾기"
        }
        // 지금 화면 크기 (폴드폰은 펼침·접힘에 따라 바뀜)
        val (sw, sh) = screenSize()
        val prefs = Prefs(this)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val margin = Ui.dp(this@KakaoAccessibilityService, 12)
            // 저장된 위치가 지금 화면 밖이면(큰 화면에서 옮겨 둔 위치 등) 화면 안으로 끌어옴
            x = (prefs.bubbleX.takeIf { it >= 0 } ?: (sw - size - margin)).coerceIn(0, maxOf(0, sw - size))
            y = (prefs.bubbleY.takeIf { it >= 0 } ?: (sh * 0.55).toInt()).coerceIn(0, maxOf(0, sh - size))
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        var longPressed = false
        val slop = Ui.dp(this, 10)
        val longPress = Runnable {
            if (!dragged) {
                longPressed = true
                showMenu(kind)
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragged = false; longPressed = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    // 메뉴가 펼쳐진 뒤에는 손가락이 지나는 동그라미를 표시 (떼면 그것을 고름)
                    if (longPressed) { hoverMenu(e.rawX, e.rawY, Math.hypot(dx.toDouble(), dy.toDouble()) > size); return@setOnTouchListener true }
                    if (dragged || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        if (!dragged) handler.removeCallbacks(longPress)
                        dragged = true
                        lp.x = (startX + dx).toInt().coerceIn(0, sw - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, sh - size)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    when {
                        longPressed -> releaseMenu(e.rawX, e.rawY, Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > size)
                        dragged -> { prefs.bubbleX = lp.x; prefs.bubbleY = lp.y }
                        else -> openHelper(ask = kind == BubbleKind.SEARCH, all = kind == BubbleKind.SEARCH)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); true }
                else -> false
            }
        }
        runCatching { wm.addView(v, lp); bubble = v; bubbleParams = lp }
            .onFailure { diag("버튼 그리기 실패: ${it.javaClass.simpleName} ${it.message}") }
    }

    /** 키보드가 올라와 버튼 자리를 덮으면 키보드 바로 위로 잠깐 옮김 (키보드가 내려가면 원래 자리로). 자리는 저장하지 않음 */
    private fun avoidKeyboard() {
        val v = bubble ?: return
        val lp = bubbleParams ?: return
        val size = lp.height
        val (_, sh) = screenSize()
        val saved = Prefs(this).bubbleY.takeIf { it >= 0 } ?: (sh * 0.55).toInt()
        val kb = keyboardTop()
        var want = if (kb != null && kb < sh && saved + size > kb - Ui.dp(this, 8)) maxOf(0, kb - size - Ui.dp(this, 12))
            else saved.coerceIn(0, maxOf(0, sh - size))
        // 떠 있는 안내 띠와 겹치면 띠 아래로 (아래 자리가 없으면 위로)
        guide?.takeIf { it.height > 0 }?.let { g ->
            val gl = IntArray(2).also { g.getLocationOnScreen(it) }
            val gTop = gl[1]; val gBottom = gl[1] + g.height
            val gap = Ui.dp(this, 10)
            if (want + size > gTop - gap && want < gBottom + gap) {
                val below = gBottom + gap
                val limit = (kb ?: sh) - size - gap
                want = if (below <= limit) below else maxOf(0, gTop - size - gap)
            }
        }
        if (lp.y != want) {
            lp.y = want
            runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    /** 지금 화면의 가로·세로 픽셀 */
    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** 폴드폰을 펼치거나 접는 등 화면이 바뀌면 버튼을 새 화면 안에 다시 그림 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubble != null) {
            removeBubble()
            handler.postDelayed({ updateBubble() }, 300)
        }
    }

    private fun removeBubble() {
        closeMenu()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    /**
     * 도우미 카드 열기. [ask] 면 말투 다듬기 대신 '대화에서 찾기'로 바로 듣기 시작 (도우미 버튼을 꾹 눌러 🔎 고른 경우).
     * [all] 이면 지금 방이 아니라 저장해 둔 모든 대화에서 찾음 (대화방 목록의 🔎 버튼)
     */
    private fun openHelper(ask: Boolean = false, all: Boolean = false) {
        closeMenu()
        // 대화방 목록에서 '모든 대화에서 찾기'를 열 때는 화면 글(방 이름·미리보기)을 읽지 않음 (저장해 둔 대화만 씀)
        HelperActivity.pendingScreenText = if (all) null else screenTexts().joinToString("\n")
        HelperActivity.pendingTitle = if (all) null else chatTitle()
        val intent = Intent(this, HelperActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(HelperActivity.EXTRA_ASK, ask)
            .putExtra(HelperActivity.EXTRA_ALL, all)
        startActivity(intent)
    }

    private fun openQuickSettings() {
        closeMenu()
        startActivity(Intent(this, QuickSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ───────── 꾹 누르면 버튼 둘레에 펼쳐지는 동그라미 메뉴 ─────────
    //  첫 단계: [말투] [찾기] [설정]  →  '말투'를 누르거나 꾹 누르면 그 자리에 다섯 말투가 펼쳐짐 (이 방의 말투로 기억)

    private var menu: FrameLayout? = null
    private var menuHint: TextView? = null
    /** 메뉴를 연 순간의 대화방 이름 (이 방의 말투를 고르는 데 씀) */
    private var menuRoom: String? = null

    /** 동그라미 하나. [onHold] 가 있으면 꾹 눌렀을 때 그것을 함(말투 펼치기), 없으면 꾹 누른 채 끌어 자리 바꾸기 */
    private class MenuEntry(val key: String, val label: String, val on: Boolean, val act: () -> Unit, val onHold: (() -> Unit)? = null)
    /** 지금 펼쳐진 단계의 동그라미들, 놓인 순서 (자리 번호 = 목록 순서) */
    private val menuOrder = mutableListOf<Pair<MenuEntry, View>>()
    private var menuSlots: List<Pair<Float, Float>> = emptyList()
    private var menuSlotSize = 0
    /** 지금 말투 단계인지 (순서를 저장할 곳이 다름) */
    private var menuToneLevel = false
    private var menuItems: List<Pair<View, () -> Unit>> = emptyList()
    private var menuHot: View? = null

    /** 동그라미 누르기 = 고르기, 꾹 누른 채 끌기 = 자리 옮기기 ('말투'는 꾹 누르면 펼침) */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachReorder(v: View, entry: MenuEntry) {
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f
        var dragging = false; var moved = false; var held = false
        val slop = Ui.dp(this, 10)
        val startDrag = Runnable {
            if (moved) return@Runnable
            val hold = entry.onHold
            if (hold != null) {
                // 꾹 누르면 펼침: 손가락을 뗀 뒤 눌러 골라도 되고, 그대로 끌고 가 동그라미 위에서 떼도 됨
                held = true
                hold()
                return@Runnable
            }
            dragging = true
            buzz()
            v.bringToFront()
            v.animate().scaleX(1.2f).scaleY(1.2f).setStartDelay(0).setDuration(120).start()
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = v.translationX; startY = v.translationY
                    dragging = false; moved = false; held = false
                    handler.postDelayed(startDrag, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    val far = Math.hypot(dx.toDouble(), dy.toDouble())
                    if (held) { hoverMenu(e.rawX, e.rawY, far > slop * 2); return@setOnTouchListener true }
                    if (!dragging && far > slop) { moved = true; handler.removeCallbacks(startDrag) }
                    if (dragging && menuSlots.isNotEmpty()) {
                        v.translationX = startX + dx; v.translationY = startY + dy
                        // 손가락에 가장 가까운 자리로 옮기고, 나머지는 한 칸씩 비켜 줌
                        val cx = v.translationX + menuSlotSize / 2f; val cy = v.translationY + menuSlotSize / 2f
                        val target = menuSlots.indices.minByOrNull { i -> Math.hypot((menuSlots[i].first - cx).toDouble(), (menuSlots[i].second - cy).toDouble()) } ?: return@setOnTouchListener true
                        val cur = menuOrder.indexOfFirst { it.second === v }
                        if (cur >= 0 && target != cur) {
                            menuOrder.add(target, menuOrder.removeAt(cur))
                            layoutMenu(except = v)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(startDrag)
                    when {
                        held -> {
                            // 펼쳐진 말투 동그라미 위에서 뗐으면 그것을 고름. 이 동그라미는 이제 숨겨졌으니 더는 반응하지 않게
                            v.setOnTouchListener(null)
                            val far = Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble())
                            if (e.actionMasked == MotionEvent.ACTION_UP) releaseMenu(e.rawX, e.rawY, far > slop * 2)
                        }
                        dragging -> {
                            dragging = false
                            layoutMenu(except = null)
                            val keys = menuOrder.map { it.first.key }
                            if (menuToneLevel) Prefs(this).toneOrder = keys else Prefs(this).menuOrder = keys
                            menuItems = menuOrder.map { it.second to it.first.act }
                        }
                        !moved && e.actionMasked == MotionEvent.ACTION_UP -> entry.act()
                    }
                }
            }
            true
        }
    }

    /** 놓인 순서대로 자리에 맞춰 움직임 ([except] 는 지금 끌고 있는 동그라미) */
    private fun layoutMenu(except: View?) {
        val sz = menuSlotSize
        menuOrder.forEachIndexed { i, (_, view) ->
            if (view === except || i >= menuSlots.size) return@forEachIndexed
            val (x, y) = menuSlots[i]
            view.animate().translationX(x - sz / 2f).translationY(y - sz / 2f).scaleX(1f).scaleY(1f)
                .setStartDelay(0).setDuration(160).start()
        }
    }

    private fun oval(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(fill)
        setStroke(Ui.dp(this@KakaoAccessibilityService, 2), stroke)
    }

    /** 저장해 둔 순서대로 ([saved] 에 없는 것은 뒤에) */
    private fun ordered(all: List<MenuEntry>, saved: List<String>): List<MenuEntry> =
        saved.mapNotNull { k -> all.firstOrNull { it.key == k } } + all.filter { it.key !in saved }

    private fun showMenu(kind: BubbleKind) {
        if (bubble == null) return
        closeMenu()
        val prefs = Prefs(this)
        menuRoom = if (kind == BubbleKind.MIC) chatTitle() else null
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x66101012)
            setOnClickListener { closeMenu() }   // 빈 곳을 누르면 닫힘
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        // 화면 아래 가운데 알약 모양 안내 (어두운 바탕 위에서도 잘 보이게 흰 바탕·굵은 글씨)
        val hint = TextView(this).apply {
            setTextColor(CHARCOAL_BOTTOM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val ph = Ui.dp(this@KakaoAccessibilityService, 18); val pv = Ui.dp(this@KakaoAccessibilityService, 10)
            setPadding(ph, pv, ph, pv)
            background = Ui.rounded(this@KakaoAccessibilityService, 0xF2FFFFFF.toInt(), 24, GOLD)
            elevation = Ui.dp(this@KakaoAccessibilityService, 6).toFloat()
            text = " "
        }
        root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dp(this@KakaoAccessibilityService, 56) })
        runCatching { wm.addView(root, lp); menu = root; menuHint = hint }
            .onFailure { diag("메뉴 그리기 실패: ${it.javaClass.simpleName} ${it.message}"); return }
        buzz()

        // 첫 단계: 말투(지금 이 방의 말투 아이콘) · 찾기 · 설정. 순서는 사용자가 끌어서 바꾼 대로
        val cur = prefs.modeFor(menuRoom)
        val all = if (kind == BubbleKind.MIC) listOf(
            MenuEntry("TONE", "${cur.emoji}\n말투", false, { openTones() }, onHold = { openTones() }),
            MenuEntry("ASK", "🔎\n찾기", false, { openHelper(ask = true) }),
            MenuEntry("SETTINGS", "⚙️\n설정", false, { openQuickSettings() }),
        ) else listOf(
            MenuEntry("ASK", "🔎\n찾기", false, { openHelper(ask = true, all = true) }),
            MenuEntry("SETTINGS", "⚙️\n설정", false, { openQuickSettings() }),
        )
        menuToneLevel = false
        val hintText = if (kind == BubbleKind.MIC) "👆 누르면 고르기   ·   지금 말투: ${cur.label}" else "👆 찾기: 저장해 둔 모든 대화에서 찾아요"
        // 창이 그려진 뒤(크기를 안 뒤)에 동그라미를 놓음
        root.post { if (menu === root) showLevel(root, ordered(all, prefs.menuOrder), hintText, demo = false) }
    }

    /** '말투' 동그라미: 첫 단계 동그라미들을 접고 그 자리에 다섯 말투를 펼침. 고르면 이 방의 말투로 기억 */
    private fun openTones() {
        val root = menu ?: return
        if (menuToneLevel) return
        val prefs = Prefs(this)
        val room = menuRoom
        val cur = prefs.modeFor(room)
        // 첫 단계 동그라미는 숨기고 더는 눌리지 않게
        // (꾹 누르고 있는 '말투' 동그라미는 손을 뗄 때까지 손가락 움직임을 받아야 하므로 그대로 둠 — 떼면 스스로 멈춤)
        menuOrder.forEach { (entry, v) ->
            if (entry.key != "TONE") v.setOnTouchListener(null)
            v.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setStartDelay(0).setDuration(140).start()
        }
        val all = Mode.values().map { m ->
            MenuEntry(m.name, "${m.emoji}\n${m.label}", m == cur, { prefs.setModeFor(room, m); openHelper() })
        }
        menuToneLevel = true
        buzz()
        showLevel(root, ordered(all, prefs.toneOrder), "👆 누르면 이 방의 말투로 정해져요   ·   ✋ 꾹 누른 채 끌면 자리 바꾸기", demo = true)
    }

    /**
     * [entries] 를 버튼 둘레 반원에 펼침. 아래 설명 알약과 떠 있는 안내 띠는 피해서 (겹치면 반원 전체를 옮김).
     * [demo] 면 처음 몇 번은 꾹 눌러 끄는 시범을 보여 줌
     */
    private fun showLevel(root: FrameLayout, entries: List<MenuEntry>, hintText: String, demo: Boolean) {
        val b = bubble ?: return
        val prefs = Prefs(this)
        menuHint?.let { it.text = hintText; Ui.iconize(it) }
        val size = Ui.dp(this, 78)
        menuOrder.clear()
        val items = entries.map { entry ->
            val (label, on, act) = Triple(entry.label, entry.on, entry.act)
            val item = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 0.95f)
                if (on) { setTextColor(0xFFFFFFFF.toInt()); background = oval(CHARCOAL_BOTTOM, GOLD) }
                else { setTextColor(CHARCOAL_BOTTOM); background = oval(0xFFFFFFFF.toInt(), 0xFFE4DED3.toInt()) }
                // 이모티콘 대신 앱과 같은 선 아이콘 (글자보다 조금 크게)
                text = Ui.iconize(this@KakaoAccessibilityService, label, currentTextColor, Ui.dp(this@KakaoAccessibilityService, 24))
                setLineSpacing(Ui.dp(this@KakaoAccessibilityService, 2).toFloat(), 1f)
                elevation = Ui.dp(this@KakaoAccessibilityService, 8).toFloat()
                contentDescription = label.substringAfter("\n")
                alpha = 0f; scaleX = 0.3f; scaleY = 0.3f
            }
            attachReorder(item, entry)
            menuOrder += entry to item
            root.addView(item, FrameLayout.LayoutParams(size, size))
            item to act
        }
        menuItems = items

        // 버튼 자리에서 화면 가운데 쪽으로 부채꼴로 하나씩 펼침
        val o = IntArray(2).also { root.getLocationOnScreen(it) }
        val bl = IntArray(2).also { b.getLocationOnScreen(it) }
        val cx = bl[0] + b.width / 2f - o[0]
        val cy = bl[1] + b.height / 2f - o[1]
        val w = root.width; val h = root.height
        // 피할 띠: 아래 설명 알약, 화면에 떠 있는 안내 띠(내보내기 순서·검색 안내)
        val bands = ArrayList<kr.yunho.talkhelper.core.MenuLayout.Box>()
        // 설명 알약은 글이 막 바뀌어 폭을 아직 모를 수 있어 가로 전체를 피함
        menuHint?.let { if (it.height > 0) bands += kr.yunho.talkhelper.core.MenuLayout.Box(0f, it.top.toFloat(), w.toFloat(), it.bottom.toFloat()) }
        guide?.let { g ->
            val gl = IntArray(2).also { g.getLocationOnScreen(it) }
            if (g.height > 0) bands += kr.yunho.talkhelper.core.MenuLayout.Box(
                (gl[0] - o[0]).toFloat(), (gl[1] - o[1]).toFloat(), (gl[0] - o[0] + g.width).toFloat(), (gl[1] - o[1] + g.height).toFloat())
        }
        // 버튼 둘레에 바짝 붙여 놓음 (버튼은 가리지 않음). 벽·안내 칸에 걸리면 둘레를 돌거나 조금씩 넓혀 피함
        val p = kr.yunho.talkhelper.core.MenuLayout.place(
            n = items.size, cx = cx, cy = cy, w = w.toFloat(), h = h.toFloat(),
            bubbleR = b.width / 2f, maxSize = size.toFloat(),
            gap = Ui.dp(this, 6).toFloat(), margin = Ui.dp(this, 8).toFloat(), minSize = Ui.dp(this, 56).toFloat(),
            avoid = bands,
        )
        // 피할 자리가 아예 없으면 설명 알약을 위로 옮김
        if (!p.clearOfBands) menuHint?.let { hv ->
            (hv.layoutParams as? FrameLayout.LayoutParams)?.let { hl ->
                hl.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                hl.topMargin = Ui.dp(this, 56); hl.bottomMargin = 0
                hv.layoutParams = hl
            }
        }
        val sz = p.size.toInt()
        menuSlots = p.centers
        menuSlotSize = sz
        items.forEachIndexed { i, (v, _) ->
            if (sz != size) v.layoutParams = FrameLayout.LayoutParams(sz, sz)
            val (x, y) = p.centers[i]
            v.translationX = cx - sz / 2f; v.translationY = cy - sz / 2f
            v.animate().translationX(x - sz / 2f).translationY(y - sz / 2f).alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(i * 50L).setDuration(220).start()
        }
        // 처음 몇 번은 손가락이 동그라미 하나를 꾹 눌러 끌어 보이는 시범을 보여 줌
        val shown = prefs.menuDemoCount
        if (demo && shown < 3 && items.size >= 2) {
            prefs.menuDemoCount = shown + 1
            root.postDelayed({ if (menu === root) dragDemo(root, items.last().first, cx, cy) }, items.size * 50L + 450)
        }
    }

    /**
     * 시범: 손가락(👆)이 동그라미 [v] 위에 나타나 꾹 누르고(살짝 커짐) 버튼 쪽([cx],[cy])으로 끌었다가 제자리로.
     * 실제로 순서를 바꾸지는 않음 (보여 주기만)
     */
    private fun dragDemo(root: FrameLayout, v: View, cx: Float, cy: Float) {
        val ctx = this
        val homeX = v.translationX; val homeY = v.translationY
        val vx = homeX + v.width / 2f; val vy = homeY + v.height / 2f
        // 버튼 쪽으로 동그라미 지름의 반만큼 끌어 보임
        val len = Math.hypot((cx - vx).toDouble(), (cy - vy).toDouble()).toFloat().coerceAtLeast(1f)
        val dx = (cx - vx) / len * v.width * 0.6f; val dy = (cy - vy) / len * v.height * 0.6f
        // 손가락 자리: 반투명 흰 동그라미 + 금색 테두리 (화면 녹화의 '터치 표시' 같은 모양)
        val ring = Ui.dp(ctx, 44)
        val hand = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(170, 255, 255, 255))
                setStroke(Ui.dp(ctx, 3), GOLD)
            }
            alpha = 0f
            elevation = Ui.dp(ctx, 12).toFloat()
        }
        root.addView(hand, FrameLayout.LayoutParams(ring, ring))
        hand.post {
            val hx = vx - ring / 2f; val hy = vy - ring / 2f
            hand.translationX = hx + Ui.dp(ctx, 30); hand.translationY = hy + Ui.dp(ctx, 30)
            val lift = Ui.dp(ctx, 16).toFloat()
            hand.animate().alpha(1f).translationX(hx).translationY(hy).setDuration(350).withEndAction {
                // 꾹 누름: 동그라미가 살짝 커지고 떠오름
                v.animate().scaleX(1.15f).scaleY(1.15f).translationZ(lift).setDuration(250).withEndAction {
                    // 끌기
                    v.animate().translationX(homeX + dx).translationY(homeY + dy).setDuration(500).start()
                    hand.animate().translationX(hx + dx).translationY(hy + dy).setDuration(500).withEndAction {
                        // 놓기: 제자리로 (시범이라 순서는 그대로)
                        v.animate().translationX(homeX).translationY(homeY).scaleX(1f).scaleY(1f).translationZ(0f)
                            .setStartDelay(250).setDuration(350).start()
                        hand.animate().alpha(0f).setStartDelay(250).setDuration(350)
                            .withEndAction { root.removeView(hand) }.start()
                    }.start()
                }.start()
            }.start()
        }
    }

    /** 메뉴가 펼쳐질 때 '드르륵' 한 번 (휴대폰 '터치 진동' 설정이 꺼져 있어도 울리도록 진동기를 직접 씀) */
    private fun buzz() = Sounds.buzz(this, 60)

    private fun itemAt(rawX: Float, rawY: Float): Pair<View, () -> Unit>? = menuItems.firstOrNull { (v, _) ->
        val l = IntArray(2).also { v.getLocationOnScreen(it) }
        rawX >= l[0] && rawX <= l[0] + v.width && rawY >= l[1] && rawY <= l[1] + v.height
    }

    /** 꾹 누른 채로 손가락을 옮기면 지나는 동그라미를 크게 */
    private fun hoverMenu(rawX: Float, rawY: Float, movedAway: Boolean) {
        // 버튼 위에 그대로 있으면(펼쳐지는 중인 동그라미가 손 밑을 지나가도) 아무것도 고르지 않음
        val hot = if (movedAway) itemAt(rawX, rawY)?.first else null
        if (hot === menuHot) return
        menuHot?.animate()?.scaleX(1f)?.scaleY(1f)?.setStartDelay(0)?.setDuration(100)?.start()
        hot?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setStartDelay(0)?.setDuration(100)?.start()
        menuHot = hot
    }

    /**
     * 꾹 누른 자리(버튼 위)에서 손을 떼면 메뉴를 펼친 채로 두어 동그라미를 눌러 고르게 함.
     * 손가락을 동그라미까지 끌고 가서 뗐을 때만 바로 고름
     */
    private fun releaseMenu(rawX: Float, rawY: Float, movedAway: Boolean) {
        menuHot?.animate()?.scaleX(1f)?.scaleY(1f)?.setStartDelay(0)?.setDuration(100)?.start()
        menuHot = null
        if (movedAway) itemAt(rawX, rawY)?.second?.invoke()
    }

    private fun closeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
        menuHint = null
        menuRoom = null
        menuToneLevel = false
        menuOrder.clear()
        menuItems = emptyList()
        menuHot = null
    }
}
