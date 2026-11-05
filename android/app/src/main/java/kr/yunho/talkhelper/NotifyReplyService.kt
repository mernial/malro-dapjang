package kr.yunho.talkhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kr.yunho.talkhelper.core.NotifyContext

/**
 * '알림으로 답장' 예비 기능 (접근성 없이 동작).
 *
 * 카톡 메시지 알림이 오면 그 알림의 '답장' 칸(RemoteInput)을 기억해 두고,
 * 조용한 우리 알림 "🎤 말로 답장"을 띄움 → 누르면 HelperActivity 가 열려 말로 답장을 다듬고
 * 사용자가 확인하면 카톡 알림의 답장 칸으로 보냄. 카톡 화면을 읽거나 누르지 않음.
 *
 * - 설정에서 켠 경우(Prefs.notifyReply)에만 동작. 카톡 이외 앱의 알림은 보지 않고 바로 버림
 * - 기억은 메모리에만 (휴대폰에 저장하지 않음). 카톡 알림이 사라지면 함께 지움
 */
class NotifyReplyService : NotificationListenerService() {

    /** 답장할 수 있는 카톡 대화 하나 */
    data class Target(
        val key: String,
        val title: String,
        val messages: List<NotifyContext.Msg>,
        val reply: Notification.Action,
        val input: RemoteInput,
        val time: Long,
    ) {
        fun context(): String = NotifyContext.format(title, messages)
    }

    companion object {
        private const val CHANNEL = "notify_reply"
        const val EXTRA_KEY = "notifyKey"
        private val targets = LinkedHashMap<String, Target>()

        fun target(key: String?): Target? = key?.let { synchronized(targets) { targets[it] } }

        private fun remember(t: Target) = synchronized(targets) {
            targets.remove(t.key); targets[t.key] = t
            while (targets.size > 20) targets.remove(targets.keys.first())
        }

        private fun forget(key: String) = synchronized(targets) { targets.remove(key) }

        /** 알림 접근 권한이 이 앱에 있는지 */
        fun allowed(c: Context): Boolean {
            val me = ComponentName(c, NotifyReplyService::class.java).flattenToString()
            return Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.split(':')?.contains(me) == true
        }

        /** 알림 접근 설정 화면 (가능하면 이 앱 항목으로 바로) */
        fun settingsIntent(c: Context): Intent =
            if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, NotifyReplyService::class.java).flattenToString())
            else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        /** 카톡 알림의 답장 칸으로 [text] 를 보냄. 성공하면 true */
        fun send(c: Context, t: Target, text: String): Boolean = runCatching {
            val results = Bundle().apply { putCharSequence(t.input.resultKey, text) }
            val fill = Intent()
            RemoteInput.addResultsToIntent(arrayOf(t.input), fill, results)
            t.reply.actionIntent.send(c, 0, fill)
            forget(t.key)
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId(t.key))
            true
        }.getOrDefault(false)

        private fun notifId(key: String) = 7000 + (key.hashCode() and 0x0fff)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName != KAKAO || !Prefs(this).notifyReply) return
        val n = s.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return
        val input = reply.remoteInputs.first { it.allowFreeFormInput }
        val ex = n.extras ?: return
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim().orEmpty()
        val messages = messagesOf(ex)
        if (messages.isEmpty()) return
        val t = Target(s.key, title, messages, reply, input, s.postTime)
        remember(t)
        postOurs(t)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName != KAKAO) return
        forget(s.key)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId(s.key))
    }

    /** 알림 속 메시지들 (MessagingStyle 이 있으면 최근 여러 개, 없으면 본문 한 줄) */
    @Suppress("DEPRECATION")
    private fun messagesOf(ex: Bundle): List<NotifyContext.Msg> {
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        val list = arr?.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val person = if (Build.VERSION.SDK_INT >= 28) b.getParcelable<android.app.Person>("sender_person")?.name else null
            val sender = (person ?: b.getCharSequence("sender"))?.toString()
            NotifyContext.Msg(sender, text)
        }.orEmpty()
        if (list.isNotEmpty()) return list
        val text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT) ?: return emptyList()
        val who = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        return listOf(NotifyContext.Msg(who, text.toString()))
    }

    /** 조용한 우리 알림: "💬 방 이름 — 마지막 메시지" + [🎤 말로 답장] */
    private fun postOurs(t: Target) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "말로 답장 (알림으로 답장)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "카톡 메시지에 말로 답장할 수 있게 조용히 알려 줍니다"
                setShowBadge(false)
            })
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val open = Intent(this, HelperActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_KEY, t.key)
        val pi = PendingIntent.getActivity(this, notifId(t.key), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bubble_mic)
            .setContentTitle("💬 ${t.title.ifBlank { "카카오톡" }}")
            .setContentText(NotifyContext.preview(t.messages))
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "🎤 말로 답장", pi).build())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(notifId(t.key), n)
    }
}
