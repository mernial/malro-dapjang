package kr.yunho.talkhelper

import android.content.Context
import kr.yunho.talkhelper.core.Mode

/** 보내는 방식 */
enum class SendMode(val label: String, val desc: String) {
    INSTANT("바로", "다듬자마자 바로 보내요"),
    COUNTDOWN("3초 뒤", "3초 보여 주고 보내요 · 그 사이 취소할 수 있어요"),
    CONFIRM("확인 후", "다듬은 글을 보고 '보내기'를 눌러야 보내요"),
    VOICE("듣고 확인", "읽어 드린 뒤 \"보내\"라고 하면 보내요"),
    ;

    companion object {
        /** 고를 수 있는 방식. 플레이판은 사람이 확인하거나 취소할 틈이 없는 '바로'를 뺌 */
        fun available(): List<SendMode> = values().filter { it != INSTANT || !BuildConfig.STORE_SAFE }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()
    /** 중계 서버의 하루 한도를 셀 때 쓰는 이 설치의 무작위 번호 (개인을 알 수 없음). 처음 부를 때 만듦 */
    val deviceId: String
        get() = sp.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().replace("-", "")
            .also { sp.edit().putString("deviceId", it).apply() }
    var myName: String
        get() = sp.getString("myName", "") ?: ""
        set(v) = sp.edit().putString("myName", v).apply()
    /** 내보낸 카톡 대화에 찍힌 '내 카톡 이름' (방마다 다를 수 있어 방 이름별로, 없으면 공통) */
    fun kakaoMe(room: String): String? = sp.getString("kakaoMe:$room", null) ?: sp.getString("kakaoMe", null)
    fun setKakaoMe(room: String, name: String) =
        sp.edit().putString("kakaoMe:$room", name).putString("kakaoMe", name).apply()

    var myRole: String
        get() = sp.getString("myRole", "") ?: ""
        set(v) = sp.edit().putString("myRole", v).apply()
    /** 카톡 입력창에 넣은 뒤 전송 버튼까지 누르기 */
    var autoPressSend: Boolean
        get() = sp.getBoolean("autoPressSend", true)
        set(v) = sp.edit().putBoolean("autoPressSend", v).apply()

    /** 버튼 한 번으로 말하기→다듬기→전송까지 자동 (끄면 보내기 전에 확인) */
    /** 다듬은 문장을 언제 보낼지 (예전 '자동 전송' 체크 설정을 이어받음) */
    var sendMode: SendMode
        get() = sp.getString("sendMode", null)?.let { runCatching { SendMode.valueOf(it) }.getOrNull() }
            ?.takeIf { it in SendMode.available() }
            ?: defaultSendMode().also { sp.edit().putString("sendMode", it.name).apply() }
        set(v) = sp.edit().putString("sendMode", v.name).apply()

    /**
     * 처음 정해지는 보내는 방식. 예전 '자동 전송' 설정이 있으면 이어받고,
     * 이미 쓰던 사람(API 키가 있음)은 '3초 뒤' 그대로, 플레이판 새 사용자는 '확인 후'
     * (구글 접근성 정책: AI가 만든 글을 사람 확인 없이 실행하는 것으로 보이지 않게)
     */
    private fun defaultSendMode(): SendMode = when {
        sp.contains("autoSend") -> if (sp.getBoolean("autoSend", true)) SendMode.COUNTDOWN else SendMode.CONFIRM
        BuildConfig.STORE_SAFE && apiKey.isBlank() -> SendMode.CONFIRM
        else -> SendMode.COUNTDOWN
    }

    /** 꾹 누르면 펼쳐지는 동그라미 순서 (사용자가 끌어서 바꾼 것) */
    var menuOrder: List<String>
        get() = (sp.getString("menuOrder", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("menuOrder", v.joinToString(",")).apply()

    /** 찾은 내용을 소리로 읽어 주기 (기본: 안 읽음) */
    var readAnswers: Boolean
        get() = sp.getBoolean("readAnswers", false)
        set(v) = sp.edit().putBoolean("readAnswers", v).apply()

    /** 카톡 위 🎤 버튼 켜기/끄기 (앱 첫 화면) */
    /** 동그라미 메뉴 '끌어 옮기기' 시범을 보여 준 횟수 (3번까지) */
    var menuDemoCount: Int
        get() = sp.getInt("menuDemoCount", 0)
        set(v) = sp.edit().putInt("menuDemoCount", v).apply()
    /** 내보내기 안내 띠를 옮겨 둔 높이 (-1 = 기본) */
    var guideY: Int
        get() = sp.getInt("guideY", -1)
        set(v) = sp.edit().putInt("guideY", v).apply()
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** 마지막으로 고른 말투 모드 (방마다 따로 고른 게 없는 방에서 씀) */
    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", Mode.POLITE.name)!!) }.getOrDefault(Mode.POLITE)
        set(v) = sp.edit().putString("mode", v.name).apply()

    /** 말투를 방마다 기억하는 열쇠 (인원수·'…'·띄어쓰기를 뺀 방 이름) */
    private fun roomKey(room: String?): String? = room?.let { kr.yunho.talkhelper.core.RoomMatch.norm(it) }?.takeIf { it.isNotEmpty() }

    /**
     * 이 방([room] = 카톡 화면 맨 위 이름)에서 쓰는 말투. 그 방에서 고른 적이 있으면 그것, 없으면 마지막에 고른 말투.
     * 화면에서 이름이 잘린("…") 경우엔 앞부분이 같은 방의 것
     */
    fun modeFor(room: String?): Mode {
        val k = roomKey(room) ?: return mode
        val saved = sp.getString("mode:$k", null)
            ?: if (kr.yunho.talkhelper.core.RoomMatch.isTruncated(room))
                sp.all.entries.firstOrNull { (key, _) -> key.startsWith("mode:$k") }?.value as? String else null
        return saved?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: mode
    }

    /** 이 방의 말투를 [m] 으로 (다른 방은 그대로). 방 이름이 없으면 기본 말투만 바꿈 */
    fun setModeFor(room: String?, m: Mode) {
        mode = m
        roomKey(room)?.let { sp.edit().putString("mode:$it", m.name).apply() }
    }

    /** 말로 답장 키보드 자판: "dubeol"(두벌식) / "cji"(천지인) */
    var keyboardLayout: String
        get() = sp.getString("keyboardLayout", "dubeol") ?: "dubeol"
        set(v) = sp.edit().putString("keyboardLayout", v).apply()

    /** 동그라미 메뉴 '말투' 안의 다섯 말투 순서 (사용자가 끌어서 바꾼 것) */
    var toneOrder: List<String>
        get() = (sp.getString("toneOrder", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("toneOrder", v.joinToString(",")).apply()

    /** 도우미 버튼 진단 기록 */
    var bubbleDiag: String
        get() = sp.getString("bubbleDiag", "") ?: ""
        set(v) = sp.edit().putString("bubbleDiag", v).apply()

    /** 마지막 다듬기 요청의 토큰 사용량 (캐시 확인용) */
    var lastUsage: String
        get() = sp.getString("lastUsage", "") ?: ""
        set(v) = sp.edit().putString("lastUsage", v).apply()

    /** 이번 달 사용량 (휴대폰 안에만). 달이 바뀌면 새로 셈, 지난달 것은 lastMonthUsage 로 */
    private fun monthKey() = java.time.YearMonth.now().toString()   // 예: 2026-11
    @Synchronized fun addUsage(kind: kr.yunho.talkhelper.core.UsageStats.Kind, u: kr.yunho.talkhelper.core.Usage?) {
        val now = monthKey()
        val e = sp.edit()
        if (sp.getString("usageMonth", now) != now) e.putString("lastMonthUsage", sp.getString("usageMonth", "") + "|" + sp.getString("usage", ""))
        val cur = if (sp.getString("usageMonth", now) == now) kr.yunho.talkhelper.core.UsageStats.decode(sp.getString("usage", null)) else kr.yunho.talkhelper.core.UsageStats()
        e.putString("usageMonth", now).putString("usage", cur.add(kind, u).encode()).apply()
    }
    /**
     * 기록만 지움: 사용량, 보내기·내보내기 기록, 방마다 '나' 이름.
     * API 키·이름·말투·보내는 방식 같은 설정은 그대로 둠
     */
    fun clearRecords() {
        val e = sp.edit()
        listOf("exportLog", "lastSendLog", "lastUsage", "usage", "usageMonth", "lastMonthUsage", "bubbleDiag", "aiReports").forEach { e.remove(it) }
        sp.all.keys.filter { it.startsWith("kakaoMe") }.forEach { e.remove(it) }
        e.apply()
    }

    fun monthUsage(): kr.yunho.talkhelper.core.UsageStats =
        if (sp.getString("usageMonth", null) == monthKey()) kr.yunho.talkhelper.core.UsageStats.decode(sp.getString("usage", null))
        else kr.yunho.talkhelper.core.UsageStats()
    /** 지난달 사용량 ("2026-10" to 통계), 없으면 null */
    fun lastMonthUsage(): Pair<String, kr.yunho.talkhelper.core.UsageStats>? =
        sp.getString("lastMonthUsage", null)?.split('|', limit = 2)?.takeIf { it.size == 2 }
            ?.let { it[0] to kr.yunho.talkhelper.core.UsageStats.decode(it[1]) }

    /** 마지막 카톡 보내기 시도 기록 (문제 진단용) */
    // 예전 '방 이름 짝'(roomAliases)은 Rooms 로 옮김 (Rooms.migrate)

    /** 대화 내보내기·찾기 과정 기록 (안 될 때 원인 확인용, 최근 30줄) */
    /** '알림으로 답장' 예비 기능 (접근성 없이 카톡 알림의 답장 칸으로 보냄) */
    var notifyReply: Boolean
        get() = sp.getBoolean("notifyReply", false)
        set(v) = sp.edit().putBoolean("notifyReply", v).apply()

    /** 🚩 이상한 AI 답장 알림 (최근 20건, 휴대폰 안에만) */
    var aiReports: String
        get() = sp.getString("aiReports", "") ?: ""
        set(v) = sp.edit().putString("aiReports", v).apply()
    fun addAiReport(spoken: String, aiText: String, note: String) {
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA).format(java.util.Date())
        val entry = "[$stamp] ${note.ifBlank { "(설명 없음)" }}\n  말한 것: ${spoken.take(200).replace('\n', ' ')}\n  AI 글: ${aiText.take(300).replace('\n', ' ')}"
        aiReports = (listOf(entry) + aiReports.split("\n\n").filter { it.isNotBlank() }).take(20).joinToString("\n\n")
    }

    var exportLog: String
        get() = sp.getString("exportLog", "") ?: ""
        set(v) = sp.edit().putString("exportLog", v).apply()
    fun logExport(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        exportLog = (exportLog.lines().filter { it.isNotBlank() } + "$t $line").takeLast(30).joinToString("\n")
    }

    var lastSendLog: String
        get() = sp.getString("lastSendLog", "") ?: ""
        set(v) = sp.edit().putString("lastSendLog", v).apply()

    val textScale: Float get() = 1.3f

    /** 도우미 카드의 화면 위치 (위에서부터) */
    var panelY: Int
        get() = sp.getInt("panelY", -1)
        set(v) = sp.edit().putInt("panelY", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubbleX", -1)
        set(v) = sp.edit().putInt("bubbleX", v).apply()
    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()
}
