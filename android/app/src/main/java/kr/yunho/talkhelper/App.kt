package kr.yunho.talkhelper

import android.content.Context
import android.os.Handler
import android.os.Looper
import kr.yunho.talkhelper.core.Assistant
import kr.yunho.talkhelper.core.Claude
import kr.yunho.talkhelper.core.Mode
import kr.yunho.talkhelper.core.Usage
import kr.yunho.talkhelper.core.UsageStats
import java.util.concurrent.Executors

const val KAKAO = "com.kakao.talk"
/** 문의·신고 받는 메일 (스토어·개인정보처리방침과 같은 주소) */
const val REPORT_EMAIL = "mernial@naver.com"
/** 개인정보처리방침 (플레이에 등록한 주소와 같음. 예전 저장소 catholic-shorts 의 GitHub Pages) */
/**
 * 🚩 이상한 AI 답장 알리기를 받는 구글 설문지 (앱을 나가지 않고 개발자에게 보냄. 구글 AI 생성 콘텐츠 정책).
 * 비어 있으면 휴대폰에만 기록. 설문지 만드는 법: docs/play-store/ai-report-form.md
 *  - REPORT_FORM_URL: https://docs.google.com/forms/d/e/<긴 ID>/formResponse
 *  - REPORT_FORM_FIELDS: 설문 항목 번호 (무엇이 이상했나, 말한 내용, AI 글, 앱 버전 순)
 */
const val REPORT_FORM_URL = "https://docs.google.com/forms/d/e/1FAIpQLSerUu112Bk8P8_o_QDLUWPRoxvM-Yz0XDcHAOF-zC3I7yIcBA/formResponse"
val REPORT_FORM_FIELDS = listOf("entry.535092123", "entry.1325222288", "entry.812589239", "entry.986025835")

const val PRIVACY_URL = "https://mernial.github.io/catholic-shorts/talk-helper-privacy.html"

/** 🚩 알림을 구글 설문지로 보냄 (블로킹, 백그라운드에서). 설문지가 없거나 실패하면 false */
object AiReport {
    fun send(note: String, spoken: String, aiText: String, version: String): Boolean {
        if (REPORT_FORM_URL.isBlank() || REPORT_FORM_FIELDS.any { it.isBlank() }) return false
        val values = listOf(note.ifBlank { "(설명 없음)" }, spoken.take(1000), aiText.take(2000), version)
        val body = REPORT_FORM_FIELDS.zip(values).joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return runCatching {
            val c = java.net.URL(REPORT_FORM_URL).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            c.disconnect()
            code in 200..399
        }.getOrDefault(false)
    }
}

/** 백그라운드에서 일하고 결과는 메인 스레드로 */
object Bg {
    private val exec = Executors.newCachedThreadPool()
    val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        exec.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }
}

/**
 * AI 담당을 만들어 재사용. 지금은 설정의 API 키로 Claude 를 바로 부름.
 * 나중에 유료 판매용 중계 서버가 생기면 여기서 서버판 Assistant 로 바꿔 끼우면 됨.
 * 쓸 때마다 이번 달 사용량을 휴대폰 안에 셈 (요금 정하기용, 밖으로 보내지 않음)
 */
object ClaudeProvider {
    private var key: String? = null
    private var client: Assistant? = null

    /** 중계 서버를 쓸 수 있는지 (키 없이 바로 사용) */
    val relayAvailable get() = BuildConfig.RELAY_URL.isNotBlank()

    /**
     * 플레이판은 언제나 중계 서버로 (지시문이 앱에 없도록).
     * 예전 직접 설치판은 폰에 넣은 API 키가 있으면 그 키로 바로, 없으면 중계 서버로. 둘 다 없으면 null
     */
    @Synchronized
    fun get(ctx: Context): Assistant? {
        if (BuildConfig.STORE_SAFE) {
            // 상수라서 플레이판 빌드에선 아래 Claude 쪽 길이 통째로 빠짐 (R8)
            if (key != "relay") { client = Counting(RelayAssistant(ctx.applicationContext), ctx.applicationContext); key = "relay" }
            return client
        }
        val k = Prefs(ctx).apiKey.trim()
        val id = if (k.isNotBlank()) k else if (relayAvailable) "relay" else return null
        if (id != key) {
            val c: Assistant = if (k.isNotBlank()) Claude(k) else RelayAssistant(ctx.applicationContext)
            client = Counting(c, ctx.applicationContext)
            key = id
        }
        return client
    }

    /** 성공한 요청마다 이번 달 사용량에 더함 */
    private class Counting(private val inner: Assistant, private val app: Context) : Assistant {
        override fun rewrite(mode: Mode, spoken: String, screenText: String?, myName: String, myRole: String, myStyle: List<String>) =
            inner.rewrite(mode, spoken, screenText, myName, myRole, myStyle).also { Prefs(app).addUsage(UsageStats.Kind.REWRITE, inner.lastUsage) }

        override fun answer(question: String, chat: String, room: String?) =
            inner.answer(question, chat, room).also { Prefs(app).addUsage(UsageStats.Kind.SEARCH, inner.lastUsage) }

        override val lastUsage: Usage? get() = inner.lastUsage
    }
}
