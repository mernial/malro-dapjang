package kr.yunho.talkhelper

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 듣기 시작·끝을 알리는 짧은 종소리(앱에 넣어 둔 소리 파일)와 진동.
 * 소리는 미디어 음량으로. 휴대폰이 무음·진동 모드면 소리는 내지 않고 진동만
 */
object Sounds {
    private var pool: SoundPool? = null
    private var startId = 0
    private var doneId = 0
    private val loaded = HashSet<Int>()

    /** 미리 불러 둠 (처음 소리가 늦지 않게). 여러 번 불러도 한 번만 */
    @Synchronized
    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(2)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        val app = ctx.applicationContext
        startId = p.load(app, R.raw.listen_start, 1)
        doneId = p.load(app, R.raw.listen_done, 1)
        pool = p
    }

    /** 듣기 시작: 올라가는 두 음 */
    fun listenStart(ctx: Context) = play(ctx, startId)

    /** 다 들었음: 내려오는 두 음 */
    fun listenDone(ctx: Context) = play(ctx, doneId)

    /** 휴대폰이 무음·진동 모드인지 (그때는 소리 없이 진동만) */
    private fun silent(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL
    }.getOrDefault(false)

    private fun play(ctx: Context, id: Int) {
        if (silent(ctx)) return
        init(ctx)
        val ready = synchronized(loaded) { id in loaded }
        if (ready) runCatching { pool?.play(id, 0.9f, 0.9f, 1, 0, 1f) }
    }

    /** 진동 [ms] 밀리초. 휴대폰 '터치 진동' 설정이 꺼져 있어도 울리도록 진동기를 직접 씀 */
    fun buzz(ctx: Context, ms: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) = runCatching {
        val vib = if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        vib.vibrate(VibrationEffect.createOneShot(ms, amplitude))
    }
}
