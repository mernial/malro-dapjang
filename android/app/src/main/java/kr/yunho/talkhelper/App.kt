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

    @Synchronized
    fun get(ctx: Context): Assistant? {
        val k = Prefs(ctx).apiKey.trim()
        if (k.isBlank()) return null
        if (k != key) {
            client = Counting(Claude(k), ctx.applicationContext)
            key = k
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
}
