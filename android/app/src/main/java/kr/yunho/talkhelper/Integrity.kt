package kr.yunho.talkhelper

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 구글 Play Integrity: 중계 서버에 '플레이에서 받은 진짜 앱'임을 알려 주는 표시를 붙임.
 * 요청마다 무작위 글자(nonce)를 만들고, 그 해시를 담은 표시(토큰)를 구글에서 받아 함께 보냄.
 * 서버가 구글에 물어 확인함. 실패해도 앱은 그대로 동작(서버가 정책에 따라 판단).
 * 블로킹 함수이므로 백그라운드 스레드에서 부를 것.
 */
object Integrity {
    @Volatile private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    /** 서버에 붙일 머리글 (x-malro-nonce, x-malro-integrity). 준비가 안 되면 nonce 만 */
    fun headers(ctx: Context): Map<String, String> {
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val token = runCatching { token(ctx, sha256(nonce)) }.getOrNull()
        return buildMap {
            put("x-malro-nonce", nonce)
            if (!token.isNullOrBlank()) put("x-malro-integrity", token)
        }
    }

    private fun token(ctx: Context, requestHash: String): String? {
        if (BuildConfig.CLOUD_PROJECT_NUMBER == 0L) return null
        val p = provider ?: synchronized(this) {
            provider ?: Tasks.await(
                IntegrityManagerFactory.createStandard(ctx.applicationContext).prepareIntegrityToken(
                    StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(BuildConfig.CLOUD_PROJECT_NUMBER)
                        .build()
                ),
                10, TimeUnit.SECONDS,
            ).also { provider = it }
        }
        return try {
            Tasks.await(
                p.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()),
                10, TimeUnit.SECONDS,
            ).token()
        } catch (e: Exception) {
            provider = null   // 오래된 준비물은 버리고 다음에 새로
            null
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
