package kr.yunho.talkhelper

import android.content.Context
import kr.yunho.talkhelper.core.Assistant
import kr.yunho.talkhelper.core.ChatSearch
import kr.yunho.talkhelper.core.HelperException
import kr.yunho.talkhelper.core.Mode
import kr.yunho.talkhelper.core.TitleGuard
import kr.yunho.talkhelper.core.Usage
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 중계 서버판 AI. 앱은 '재료'(말한 것, 화면 글, 질문, 대화 일부)만 보내고,
 * AI 지시문은 서버가 붙임 → 앱을 뜯어 봐도 지시문이 없음.
 * 블로킹 함수이므로 반드시 백그라운드 스레드에서 부를 것.
 */
class RelayAssistant(private val ctx: Context) : Assistant {

    override fun rewrite(
        mode: Mode, spoken: String, screenText: String?, myName: String, myRole: String,
        myStyle: List<String>,
    ): String {
        val body = JSONObject()
            .put("mode", mode.name)
            .put("spoken", spoken)
            .put("screenText", screenText ?: "")
            .put("myName", myName)
            .put("myRole", myRole)
            .put("myStyle", JSONArray(myStyle))
        return messageOnly(post("/v2/rewrite", body))
            .trim().removeSurrounding("\"").trim()
            // 사용자가 말하지 않은 호칭(예: 내 신분인 '신부님')으로 상대를 부르면 바로잡음
            .let { TitleGuard.fix(it, spoken) }
    }

    override fun answer(question: String, chat: String, room: String?): String {
        val body = JSONObject()
            .put("question", question)
            .put("room", room ?: "")
            // 대화 전체가 아니라 질문과 관련된 부분만 보냄
            .put("transcript", ChatSearch.excerpt(chat, question))
        return post("/v2/answer", body).trim()
    }

    @Volatile override var lastUsage: Usage? = null
        private set

    private fun post(path: String, body: JSONObject): String {
        val p = Prefs(ctx)
        val conn = try {
            (URL(BuildConfig.RELAY_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-malro-device", p.deviceId)
                setRequestProperty("x-malro-app", "malro-dapjang/${BuildConfig.VERSION_NAME}")
                Integrity.headers(ctx).forEach { (k, v) -> setRequestProperty(k, v) }
            }
        } catch (e: IOException) {
            throw HelperException("인터넷 연결을 확인해 주세요.")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HelperException(errorText(code, text))
            val j = JSONObject(text)
            if (j.optString("stop_reason") == "refusal") {
                throw HelperException("이 내용은 처리할 수 없다고 합니다. 다르게 말해 주세요.")
            }
            j.optJSONObject("usage")?.let { u ->
                lastUsage = Usage(
                    input = u.optLong("input"), cacheRead = u.optLong("cache_read"),
                    cacheWrite = u.optLong("cache_write"), output = u.optLong("output"),
                )
            }
            val out = j.optString("text")
            if (out.isBlank()) throw HelperException("답을 받지 못했습니다. 다시 해 주세요.")
            return out
        } catch (e: HelperException) {
            throw e
        } catch (e: IOException) {
            throw HelperException("인터넷 연결을 확인해 주세요.")
        } catch (e: org.json.JSONException) {
            throw HelperException("AI 서버 답을 읽지 못했어요. 다시 해 주세요.")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 서버가 보낸 한국어 안내가 있으면 그것, 없으면 번호별 안내 */
        fun errorText(code: Int, body: String): String {
            val msg = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull()
                ?.takeIf { it.isNotBlank() && it.any { c -> c in '가'..'힣' } }
            return msg ?: when (code) {
                429 -> "오늘 쓸 수 있는 횟수를 다 썼거나 요청이 많아요. 잠시 뒤나 내일 다시 해 주세요."
                401, 403 -> "AI 서버가 이 요청을 받지 않았어요. 앱을 최신으로 업데이트해 주세요."
                in 500..599 -> "AI 서버가 잠시 불안정해요. 조금 뒤에 다시 해 주세요."
                else -> "AI 서버 오류($code). 조금 뒤에 다시 해 주세요."
            }
        }

        /**
         * 받은 글에서 보낼 메시지만 꺼냄. 형식대로 왔으면 "message" 값,
         * 아니면(드물게) 마지막 문단만 — 앞쪽의 설명·생각이 카톡으로 나가는 일을 막음
         */
        fun messageOnly(raw: String): String {
            val t = raw.trim()
            runCatching { JSONObject(t).optString("message") }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { return it }
            return t.split(Regex("\\n\\s*\\n")).map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: t
        }
    }
}
