package kr.yunho.talkhelper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kr.yunho.talkhelper.Ui.add
import kr.yunho.talkhelper.core.SetupCode

/**
 * 어르신도 따라 할 수 있게, 한 화면에 한 단계만 크게 보여 주는 설정 화면.
 *  1단계: 카톡 위 버튼 켜기 (접근성)
 *  2단계: 마이크 허용
 *  3단계: 설정 코드 붙여넣기 (자녀가 카톡으로 보내 준 것) 또는 API 키 직접 입력
 *  완료: 사용법 + 가족에게 설정 보내기 + 자세한 설정
 */
class MainActivity : Activity() {

    companion object {
        /** 접근성 API 사용에 대한 눈에 띄는 공개 (Play 정책) */
        const val DISCLOSURE = """이 앱은 '접근성' 권한을 다음 용도로만 사용합니다.

• 카카오톡 화면 위에 '도우미' 버튼을 띄웁니다.
• 도우미 버튼을 누를 때(답장·찾기), 그 순간 카카오톡 화면에 보이는 대화 글을 읽습니다. 무엇에 대한 답장인지, 무엇을 찾는지 알기 위해서입니다.
• 다듬은 답장을 카카오톡 입력창에 넣고 전송 버튼을 누릅니다. 보내기 전에 화면에 보여 드리고 취소할 수 있습니다.

읽은 대화 글과 말씀하신 내용은 답장을 다듬거나 찾은 내용을 정리하기 위해 Anthropic(Claude AI, 미국)으로 전송됩니다. 이때 운영자의 중계 서버를 거치지만 중계 서버는 내용을 저장하지 않고 넘기기만 하며, 이 앱은 화면에서 읽은 글을 휴대폰에 저장하지 않습니다.
버튼을 띄울지 정하려고 카카오톡 화면의 구성(입력칸 위치, 채팅·오픈채팅 같은 탭 이름)만 살펴보며, 대화 글은 버튼을 누를 때만 읽습니다. 카카오톡 이외의 앱 화면은 읽지 않으며, 버튼을 누르지 않았을 때는 아무것도 보내지 않습니다."""
    }

    private lateinit var body: LinearLayout
    private var showAdvanced = false
    private var showSettings = false
    private var showHelp = false
    private var triedAccessibility = false
    private var update: Updater.Info? = null
    private var updating: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = Ui.vbox(this, 20)
        // 안드로이드 15 이상은 화면 끝까지 그리므로 상태 표시줄·내비게이션 바 자리를 비워 둠
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(body) })
        // 에뮬레이터 시험(crash-check)에서만: 중계 서버로 다듬기·찾기를 한 번씩 해 보고 기록에 남김
        if (BuildConfig.E2E && intent?.getBooleanExtra("e2e", false) == true) {
            Bg.run({
                val ai = ClaudeProvider.get(this) ?: error("AI 없음")
                val a = ai.rewrite(kr.yunho.talkhelper.core.Mode.POLITE, "내일 모임 몇 시인지 물어봐 줘", null, "", "")
                val b = ai.answer("모임 언제야?", "2026년 11월 1일 오후 3:00, 홍길동 : 다음 모임은 11월 15일 오후 2시입니다", "시험방")
                "rewrite=$a | answer=${b.lines().firstOrNull()}"
            }) { r -> android.util.Log.i("talkhelper-e2e", r.fold({ "OK $it" }, { "FAIL ${it.message}" })) }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        // 새 버전 확인 (조용히, 실패해도 무시)
        Bg.run({ Updater.check(this) }) { r ->
            r.getOrNull().let { if (it != update) { update = it; render() } }
        }
    }

    /** 새 버전이 있으면 맨 위에 크게 알림 */
    private fun updateBanner() {
        val info = update ?: return
        val box = Ui.vbox(this, 18).apply {
            background = Ui.rounded(this@MainActivity, Ui.YELLOW, 22, Ui.BROWN)
            elevation = Ui.dp(this@MainActivity, 4).toFloat()
        }
        box.add(Ui.text(this, "🔔 새 버전이 나왔어요", 24f, bold = true), 0)
        box.add(Ui.text(this, "버전 ${info.versionName} · 더 좋아진 기능을 받아 보세요", 15f), 4)
        if (info.notes.isNotBlank()) box.add(Ui.text(this, info.notes, 14f, color = Ui.MUTED), 4)
        // 플레이 스토어판: 누르면 스토어의 이 앱 화면이 열리고, 거기서 '업데이트'를 누름
        if (Updater.STORE) {
            box.add(Ui.button(this, "⬇️  업데이트하러 가기", Ui.BROWN, Ui.YELLOW, sp = 22f) { Updater.openStore(this) }
                .apply { minHeight = Ui.dp(this@MainActivity, 80) }, 10)
            box.add(Ui.text(this, "Play 스토어가 열리면 '업데이트'를 누르세요.", 14f, color = Ui.MUTED), 6)
            body.add(box, 12)
            return
        }
        val btn = Ui.button(this, updating ?: "⬇️  지금 업데이트", Ui.BROWN, Ui.YELLOW, sp = 22f) {
            if (updating != null || !Updater.ensureInstallAllowed(this)) return@button
            updating = "받는 중… 0%"
            render()
            Bg.run({
                Updater.downloadAndInstall(this) { pct -> Bg.main.post { updating = "받는 중… $pct%"; render() } }
            }) { r ->
                updating = null
                r.exceptionOrNull()?.let { Toast.makeText(this, "업데이트 파일을 받지 못했어요. 인터넷을 확인해 주세요.", Toast.LENGTH_LONG).show() }
                render()
            }
        }.apply { minHeight = Ui.dp(this@MainActivity, 80) }
        box.add(btn, 10)
        body.add(box, 12)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 클립보드는 창이 앞에 있을 때만 읽을 수 있음. 설정 코드를 복사해 두었으면 자동으로 가져옴
        if (hasFocus && !BuildConfig.STORE_SAFE && Prefs(this).apiKey.isBlank()) importFromClipboard(silent = true)
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(this, KakaoAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun micOn() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        val p = Prefs(this)
        body.removeAllViews()
        // 제목을 길게 누르면 숨겨 둔 '자세한 설정' (API 키·고급·문제 확인, 운영자용)
        header()
        updateBanner()

        // 중계 서버가 있으면 설정 코드 단계 없이 두 단계로 끝남
        // 플레이판: 카톡 위 버튼(접근성) 없이 키보드로만 → 1) 키보드 켜고 고르기 2) 마이크
        val steps = if (BuildConfig.STORE_SAFE) listOf(keyboardEnabled(), micOn())
            else listOfNotNull(accessibilityOn(), micOn(), if (ClaudeProvider.relayAvailable) null else p.apiKey.isNotBlank())
        val current = steps.indexOfFirst { !it }
        if (current >= 0 && !showAdvanced) {
            body.add(Ui.text(this, "준비 ${current + 1} / ${steps.size}", 15f, bold = true, color = Ui.MUTED), 20)
            when (current) {
                0 -> if (BuildConfig.STORE_SAFE) stepKeyboard() else stepAccessibility()
                1 -> stepMic()
                2 -> stepSetupCode()
            }
            footer()
            return
        }
        when {
            showAdvanced -> advanced()
            showSettings -> userSettings()
            showHelp -> help()
            else -> done()
        }
        footer()
    }

    /** 모든 화면 맨 아래: 개인정보처리방침과 문의 (구글 플레이 정책: 앱 안에서도 방침을 볼 수 있어야 함) */
    private fun footer() {
        val ctx = this
        val links = Ui.hbox(ctx).apply { gravity = android.view.Gravity.CENTER }
        fun link(label: String, onClick: () -> Unit) = Ui.text(ctx, label, 13f, bold = true, color = Ui.MUTED).apply {
            minHeight = Ui.dp(ctx, 48); gravity = android.view.Gravity.CENTER
            val ph = Ui.dp(ctx, 10); setPadding(ph, 0, ph, 0)
            setOnClickListener { onClick() }
        }
        links.addView(link("개인정보처리방침") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
                .onFailure { Toast.makeText(this, PRIVACY_URL, Toast.LENGTH_LONG).show() }
        })
        links.addView(Ui.text(ctx, "|", 13f, color = Ui.LINE))
        links.addView(link("문의·불편 신고") {
            val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$REPORT_EMAIL"))
                .putExtra(Intent.EXTRA_SUBJECT, "[말로 답장] 문의 (버전 ${BuildConfig.VERSION_NAME})")
            runCatching { startActivity(mail) }.onFailure { Toast.makeText(this, REPORT_EMAIL, Toast.LENGTH_LONG).show() }
        })
        body.add(links, 28)
        body.add(Ui.text(ctx, "버전 ${BuildConfig.VERSION_NAME} · $REPORT_EMAIL\n이 앱은 카카오와 관련이 없는 독립 앱입니다.", 12f, color = Ui.MUTED).apply {
            gravity = android.view.Gravity.CENTER
            setTextIsSelectable(true)
        }, 2)
        body.add(View(this), 24)
    }

    /** '알림으로 답장' 켜기 전 안내와 동의 (알림 접근은 민감한 권한이라 무엇을 읽는지 먼저 알림) */
    private fun confirmNotifyReply() {
        AlertDialog.Builder(this)
            .setTitle("알림 접근 권한 사용 안내")
            .setMessage(
                "'알림으로 답장'을 쓰려면 알림 접근을 허용해야 합니다. 이 앱은 이 권한을 다음 용도로만 씁니다.\n\n" +
                    "• 카카오톡 메시지 알림만 봅니다. 다른 앱의 알림은 받는 즉시 버리고 읽지 않습니다.\n" +
                    "• 알림에 보이는 대화방 이름과 최근 메시지를, 답장을 다듬을 때 Anthropic(Claude AI, 미국)으로 보냅니다.\n" +
                    "• 다듬은 답장은 사용자가 확인한 뒤 카카오톡 알림의 '답장' 칸으로 보냅니다.\n" +
                    "• 알림 내용은 휴대폰에 저장하지 않으며, 카카오톡 알림이 사라지면 함께 잊습니다."
            )
            .setPositiveButton("동의하고 켜기") { _, _ ->
                Prefs(this).notifyReply = true
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
                if (!NotifyReplyService.allowed(this)) {
                    Toast.makeText(this, "목록에서 '${getString(R.string.app_name)}'을 찾아 허용해 주세요.", Toast.LENGTH_LONG).show()
                    runCatching { startActivity(NotifyReplyService.settingsIntent(this)) }
                }
                render()
            }
            .setNegativeButton("동의 안 함", null)
            .show()
    }

    /** 이 폰에 저장한 대화·사진·기록을 모두 지움 (설정은 그대로) */
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("저장한 것 모두 지우기")
            .setMessage("이 앱이 휴대폰에 저장한 것을 모두 지웁니다.\n\n• 내보낸 카톡 대화와 사진\n• 사용량, 보내기·찾기 기록\n\nAPI 키와 말투·보내는 방식 같은 설정은 남습니다. 카카오톡의 대화는 지워지지 않습니다.")
            .setPositiveButton("모두 지우기") { _, _ ->
                ChatStore.dir(this).deleteRecursively()
                Prefs(this).clearRecords()
                Toast.makeText(this, "저장한 대화와 기록을 모두 지웠어요.", Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun bigCard(title: String, desc: String): LinearLayout {
        val box = Kit.card(this, 20)
        box.addView(Ui.text(this, title, 22f, bold = true))
        box.add(Ui.text(this, desc, 17f), 12)
        body.add(box, 14)
        return box
    }

    // ───────── 1단계 (플레이판): 키보드 켜고 고르기 ─────────

    private fun stepKeyboard() {
        val on = keyboardEnabled()
        val box = bigCard(
            "말로 답장 키보드 켜기",
            if (!on) "카톡에서 글을 쓸 때 이 키보드를 쓰면, 🎤를 누르고 말하기만 하면 다듬은 글이 입력칸에 들어가요.\n\n아래 노란 버튼을 누르고 목록에서 '${getString(R.string.keyboard_name)}'을 켠 뒤, 뒤로 가기(◁)로 돌아오세요."
            else "거의 다 됐어요.\n\n아래 노란 버튼을 누르고 '${getString(R.string.keyboard_name)}'을 고르세요."
        )
        box.add(Ui.button(this, if (!on) "👉  1. 키보드 켜기" else "👉  2. 이 키보드 고르기", sp = 24f) {
            if (!on) {
                Toast.makeText(this, "목록에서 '${getString(R.string.keyboard_name)}'을 켜 주세요", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            } else (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
        }.apply { minHeight = Ui.dp(this@MainActivity, 88) }, 18)
        box.add(Ui.text(this, "키보드는 입력한 글을 저장하거나 밖으로 보내지 않아요. 🎤로 말한 내용만 다듬을 때 AI로 보내요.", 14f, color = Ui.MUTED), 12)
    }

    // ───────── 1단계: 접근성 ─────────

    private fun stepAccessibility() {
        val box = bigCard(
            "카톡 위 버튼 켜기",
            "아래 노란 버튼을 누르면 설정 화면이 열립니다.\n\n'${getString(R.string.app_name)}'을 눌러 켜고, '허용'을 누른 뒤\n뒤로 가기(◁)로 돌아오세요."
        )
        box.add(Ui.button(this, "👉  설정 열기", sp = 24f) {
            // 구글 플레이 정책: 접근성 권한을 켜기 전에 무엇을 왜 쓰는지 크게 알리고 동의를 받아야 함
            confirmDisclosure {
                triedAccessibility = true
                openAccessibilitySettings()
            }
        }.apply { minHeight = Ui.dp(this@MainActivity, 88) }, 18)

        if (triedAccessibility) {
            val help = Ui.vbox(this, 16, Ui.MINE)
            help.add(Ui.text(this, "회색으로 막혀서 안 켜지나요?", 20f, bold = true), 0)
            help.add(Ui.text(this,
                "1. 아래 '앱 정보 열기'를 누르세요.\n2. 오른쪽 위 ⋮ (점 세 개)를 누르세요.\n3. '제한된 설정 허용'을 누르세요.\n4. 다시 위의 '설정 열기'를 누르세요.",
                18f), 8)
            help.add(Ui.button(this, "앱 정보 열기", Ui.CARD, sp = 20f) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, 10)
            body.add(help, 16)
        }
    }

    /** 접근성 권한 사용 안내와 동의 (처음 한 번) */
    private fun confirmDisclosure(onAgree: () -> Unit) {
        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("disclosureAccepted", false)) { onAgree(); return }
        AlertDialog.Builder(this)
            .setTitle("접근성 권한 사용 안내")
            .setMessage(DISCLOSURE)
            .setPositiveButton("동의하고 켜기") { _, _ ->
                sp.edit().putBoolean("disclosureAccepted", true).apply()
                onAgree()
            }
            .setNegativeButton("동의 안 함", null)
            .show()
    }

    /** 가능하면 '카톡 도우미' 접근성 상세 화면으로 바로 이동 */
    private fun openAccessibilitySettings() {
        if (Build.VERSION.SDK_INT >= 33) {
            // 공개 SDK 에 상수가 없어 문자열로 지정 (안드로이드 13 이상에서 동작)
            val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, KakaoAccessibilityService::class.java).flattenToString())
            if (runCatching { startActivity(detail) }.isSuccess) return
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ───────── 2단계: 마이크 ─────────

    private fun stepMic() {
        val box = bigCard("마이크 허용", "말로 답장하려면 마이크가 필요합니다.\n\n아래 버튼을 누르고 '앱 사용 중에만 허용'을 누르세요.")
        box.add(Ui.button(this, "👉  마이크 허용하기", sp = 24f) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) || !micAskedBefore()) {
                markMicAsked()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            } else {
                // 두 번 거절하면 더 이상 묻지 않으므로 앱 정보에서 직접 켜야 함
                Toast.makeText(this, "'권한' → '마이크' → '앱 사용 중에만 허용'을 눌러 주세요", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }.apply { minHeight = Ui.dp(this@MainActivity, 88) }, 18)
    }

    private fun micAskedBefore() = getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("micAsked", false)
    private fun markMicAsked() = getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("micAsked", true).apply()

    // ───────── 3단계: 설정 코드 ─────────

    private fun stepSetupCode() {
        val box = bigCard(
            "설정 받기",
            "자녀가 카톡으로 보내 준 '말로 답장 설정' 메시지를\n길게 눌러 '복사'한 뒤, 이 앱으로 돌아와\n아래 버튼을 누르세요."
        )
        box.add(Ui.button(this, "👉  복사한 설정 넣기", sp = 24f) { importFromClipboard(silent = false) }
            .apply { minHeight = Ui.dp(this@MainActivity, 88) }, 18)

        body.add(Ui.text(this, "API 키를 직접 넣으려면 (자녀용)", 15f, bold = true, color = Ui.MUTED), 30)
        val key = Ui.input(this, "API 키 또는 설정 메시지 붙여넣기", password = true)
        body.add(key, 6)
        body.add(Ui.button(this, "저장", Ui.CARD, sp = 18f) {
            val k = keyFrom(key.text.toString())
            if (!k.startsWith("sk-ant-")) {
                Toast.makeText(this, "sk-ant- 로 시작하는 키를 넣어 주세요", Toast.LENGTH_LONG).show()
            } else { Prefs(this).apiKey = k; render() }
        }, 8)
    }

    /** 키 칸에 API 키를 넣든, 설정 코드(안내문 포함)를 통째로 붙여 넣든 키로 바꿔 줌 */
    private fun keyFrom(text: String): String = SetupCode.decode(text) ?: text.trim()

    private fun importFromClipboard(silent: Boolean) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        val key = SetupCode.decode(text)
        if (key == null) {
            if (!silent) AlertDialog.Builder(this)
                .setMessage("복사한 설정을 찾지 못했어요.\n\n카톡에서 '말로 답장 설정' 메시지를 길게 눌러 '복사'를 누른 뒤 다시 해 주세요.")
                .setPositiveButton("확인", null).show()
            return
        }
        Prefs(this).apiKey = key
        render()
        AlertDialog.Builder(this)
            .setMessage("설정을 받았어요! 🎉\n\n이제 받은 카톡 메시지는 지워 주세요.")
            .setPositiveButton("확인", null).show()
    }

    // ───────── 완료: 첫 화면 ─────────

    /** 맨 위: 제목·부제 + 오른쪽 설정 아이콘. 제목을 길게 누르면 운영자용 '자세한 설정' */
    private fun header() {
        val ctx = this
        val row = Ui.hbox(ctx)
        val col = Ui.vbox(ctx)
        col.addView(Ui.text(ctx, getString(R.string.app_name), 26f, bold = true).apply {
            setOnLongClickListener { showAdvanced = !showAdvanced; showSettings = false; showHelp = false; render(); true }
        })
        col.add(Ui.text(ctx, "말하면, 내 말투로 답장해요", 14f, color = Ui.MUTED), 2)
        row.addView(col, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!showSettings && !showHelp && !showAdvanced) {
            val gear = android.widget.FrameLayout(ctx).apply {
                isClickable = true
                contentDescription = "설정"
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.CARD); setStroke(Ui.dp(ctx, 1), Ui.LINE)
                }
                addView(Kit.icon(ctx, R.drawable.ic_sliders, Ui.BROWN, 22), android.widget.FrameLayout.LayoutParams(
                    Ui.dp(ctx, 22), Ui.dp(ctx, 22), android.view.Gravity.CENTER))
                setOnClickListener { showSettings = true; render() }
            }
            row.addView(gear, LinearLayout.LayoutParams(Ui.dp(ctx, 48), Ui.dp(ctx, 48)))
        }
        body.add(row, 4)
    }

    /** 다른 화면(설정·도움말) 위: ‹ 돌아가기 + 제목 */
    private fun subHeader(title: String, back: () -> Unit) {
        val ctx = this
        val row = Ui.hbox(ctx).apply { minimumHeight = Ui.dp(ctx, 48); isClickable = true; setOnClickListener { back() } }
        row.addView(Kit.icon(ctx, R.drawable.ic_chevron, Ui.BROWN, 22).apply { rotation = 180f })
        row.addView(Ui.text(ctx, title, 22f, bold = true), LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 6) })
        row.contentDescription = "$title, 돌아가기"
        body.add(row, 4)
    }

    private fun pickKeyboard() =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()

    private fun openKeyboardSettings() {
        Toast.makeText(this, "목록에서 '${getString(R.string.keyboard_name)}'을 켜 주세요", Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
    }

    /** 말투마다 '알았어 고마워' 미리 보기 */
    private fun toneExample(m: kr.yunho.talkhelper.core.Mode): String = when (m) {
        kr.yunho.talkhelper.core.Mode.POLITE -> "네, 알겠어요. 고마워요."
        kr.yunho.talkhelper.core.Mode.FRIENDLY -> "알았어, 고마워!"
        kr.yunho.talkhelper.core.Mode.BUSINESS -> "네, 확인했습니다. 감사합니다."
        kr.yunho.talkhelper.core.Mode.PARENT -> "그래, 알았다~ 고마워."
        kr.yunho.talkhelper.core.Mode.MINE -> "평소 내가 쓰는 말투로 바꿔 줘요."
    }

    private fun done() {
        val p = Prefs(this)
        val ctx = this
        val store = BuildConfig.STORE_SAFE

        // B. 지금 상태 (실제 시스템 설정을 읽어서 표시, 화면에 돌아올 때마다 다시 읽음)
        val status = Kit.card(ctx)
        if (!store) {
            val on = p.enabled
            status.addView(Kit.statusRow(ctx, R.drawable.ic_mic, "카카오톡 말로 답장",
                if (on) "사용 중" else "꺼짐", if (on) Kit.State.OK else Kit.State.OFF,
                if (on) "카카오톡에서 🎤 버튼을 눌러 말해 보세요." else "켜면 카카오톡에 🎤 버튼이 떠요.",
                Ui.toggle(ctx, on) { v ->
                    p.enabled = v
                    KakaoAccessibilityService.instance?.redrawBubble()
                    render()
                }.apply { contentDescription = "카카오톡 말로 답장 켜기 끄기" }))
            with(Kit) { status.addDivider() }
        }
        val kbOn = keyboardEnabled(); val kbChosen = keyboardChosen()
        when {
            kbOn && kbChosen -> {
                status.addView(Kit.statusRow(ctx, R.drawable.ic_keyboard, "말로 답장 키보드",
                    "설정 완료 · 사용 가능", Kit.State.OK,
                    if (store) "글을 쓸 때 키보드 위 🎤 말로 답장을 누르세요." else null))
                status.addView(Kit.smallButton(ctx, "키보드 변경") { pickKeyboard() }, LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = Ui.dp(ctx, 38); topMargin = Ui.dp(ctx, 10) })
            }
            else -> {
                status.addView(Kit.statusRow(ctx, R.drawable.ic_keyboard,
                    if (store) "키보드 설정이 필요해요" else "말로 답장 키보드",
                    if (kbOn) "키보드를 골라 주세요" else "아직 켜지 않았어요",
                    if (store) Kit.State.NEED else Kit.State.OFF,
                    if (store) null else "카톡 말고 다른 앱에서도 말로 답장을 쓰려면 켜 보세요."))
                fun step(n: Int, label: String, doneStep: Boolean, enabled: Boolean, onClick: () -> Unit) {
                    val b = if (doneStep) Kit.smallButton(ctx, "✅ $n. $label") {} else if (enabled) Kit.primaryButton(ctx, "$n. $label", onClick)
                        else Kit.smallButton(ctx, "$n. $label") {}.apply { alpha = 0.5f }
                    status.add(b, 10)
                }
                step(1, "키보드 켜기", kbOn, true) { openKeyboardSettings() }
                step(2, "키보드 선택하기", false, kbOn) { pickKeyboard() }
            }
        }
        body.add(status, 20)

        // C. 답장 말투: 고른 것만 노랑, 아래에 미리 보기 하나
        body.add(Kit.section(ctx, R.drawable.ic_message_circle, "답장 말투"), 24)
        val toneCard = Kit.card(ctx, 16)
        val chips = LinkedHashMap<kr.yunho.talkhelper.core.Mode, android.widget.TextView>()
        val preview = Ui.text(ctx, "", 17f, bold = true)
        fun paintTones() {
            chips.forEach { (m, v) -> Kit.paintChip(ctx, v, m == p.mode) }
            preview.text = toneExample(p.mode)
        }
        val modes = kr.yunho.talkhelper.core.Mode.values().toList()
        // 한 줄에 두 개씩 (큰 글씨에서도 잘리지 않게)
        for (line in modes.chunked(2)) {
            val row = Ui.hbox(ctx)
            for (m in line) {
                val c = Kit.chip(ctx, "${m.emoji} ${m.label}").apply {
                    contentDescription = "${m.label} 말투"
                    setOnClickListener { p.mode = m; paintTones() }
                }
                chips[m] = c
                row.add(c)
            }
            if (line.size < 2) row.add(View(ctx))   // 마지막 줄도 같은 칸 너비
            toneCard.add(row, if (line.first() == modes.first()) 0 else 8)
        }
        val box = Ui.vbox(ctx, 14).apply {
            background = Ui.rounded(ctx, Ui.BG, 14)
        }
        box.addView(Ui.text(ctx, "🎤 \"알았어 고마워\"", 14f, color = Ui.MUTED))
        box.add(preview, 6)
        toneCard.add(box, 14)
        toneCard.add(Ui.text(ctx, if (store) "키보드 위 '말투'에서도 바꿀 수 있어요." else "카톡 🎤 버튼을 꾹 누르면 방마다 다른 말투로 바꿀 수 있어요.", 13f, color = Ui.MUTED), 10)
        paintTones()
        body.add(toneCard, 10)

        // D. 보내는 방식 (카톡 위 버튼이 직접 보내는 직접 설치판만. 키보드는 보내기를 사용자가 누름)
        if (!store) {
            body.add(Kit.section(ctx, R.drawable.ic_send, "보내는 방식"), 24)
            body.add(Ui.sendModePicker(ctx, p), 10)
        }

        // E. 추가 설정 + F. 사용 방법
        val more = Kit.card(ctx, 16)
        more.addView(Kit.toggleRow(ctx, R.drawable.ic_volume, "찾은 내용 읽어 주기", "찾은 문장을 소리로 들려줘요.", p.readAnswers) { on -> p.readAnswers = on })
        with(Kit) { more.addDivider(10) }
        more.addView(Kit.navRow(ctx, R.drawable.ic_help, "사용 방법",
            if (store) "🎤 누르기 → 말하기 → 보내기" else "마이크 누르기 → 말하기 → 보내기") { showHelp = true; render() })
        body.add(more, 24)
    }

    /** 사용 방법 (첫 화면의 '사용 방법'을 누르면) */
    private fun help() {
        val ctx = this
        subHeader("사용 방법") { showHelp = false; render() }
        val card = Kit.card(ctx)
        fun item(res: Int, title: String, desc: String, first: Boolean = false) {
            if (!first) with(Kit) { card.addDivider(12) }
            val row = Ui.hbox(ctx).apply { gravity = android.view.Gravity.TOP }
            row.addView(Kit.icon(ctx, res, Ui.BROWN, 24))
            val col = Ui.vbox(ctx)
            col.addView(Ui.text(ctx, title, 16f, bold = true))
            col.add(Ui.text(ctx, desc, 15f, color = Ui.MUTED), 4)
            row.addView(col, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 14) })
            card.addView(row)
        }
        if (BuildConfig.STORE_SAFE) {
            item(R.drawable.ic_mic, "🎤 말로 답장", "누르고 편하게 말하면, 다듬은 글이 입력칸에 들어가요. 확인하고 보내기는 직접 누르세요.", true)
            item(R.drawable.ic_message_circle, "말투 ▾", "정중하게·친구처럼 같은 말투를 골라요. 방금 넣은 글도 그 말투로 바뀌어요.")
            item(R.drawable.ic_search, "🔎 찾기", "카톡 대화방 ☰ → 설정 → '대화 내용 내보내기' → '말로 답장'으로 보내 둔 대화에서 찾아 줘요.")
            item(R.drawable.ic_keyboard, "⇄ 천지인 / 두벌식", "자판 모양을 바꿔요.")
        } else {
            item(R.drawable.ic_tap, "한 번 누르기", "음성 답장 시작. 말하고 끝에 \"…라고 보내 줘\"라고 하면 바로 다듬어요.", true)
            item(R.drawable.ic_mic, "길게 누르기", "말투 바꾸기 · 🔎 찾기 · ⚙ 설정")
            item(R.drawable.ic_move, "끌어 옮기기", "버튼 자리를 바꿔요.")
            item(R.drawable.ic_keyboard, "말로 답장 키보드", "다른 앱에서도 키보드 위 🎤로 말하면 다듬은 글이 들어가요.")
        }
        body.add(card, 20)
    }

    /** 사용자 설정 (오른쪽 위 설정 아이콘) */
    private fun userSettings() {
        val ctx = this
        val p = Prefs(this)
        subHeader("설정") { showSettings = false; render() }

        val kb = Kit.card(ctx, 16)
        kb.addView(Kit.navRow(ctx, R.drawable.ic_keyboard, "키보드 켜기·끄기", "휴대폰 키보드 목록 열기") { openKeyboardSettings() })
        with(Kit) { kb.addDivider(10) }
        kb.addView(Kit.navRow(ctx, R.drawable.ic_refresh, "키보드 바꾸기", "말로 답장 키보드 ↔ 다른 키보드") { pickKeyboard() })
        if (!BuildConfig.STORE_SAFE) {
            with(Kit) { kb.addDivider(10) }
            kb.addView(Kit.navRow(ctx, R.drawable.ic_settings, "카톡 위 버튼 (접근성)", "접근성 설정 열기") { openAccessibilitySettings() })
            with(Kit) { kb.addDivider(10) }
            kb.addView(Kit.navRow(ctx, R.drawable.ic_pin, "버튼 위치 처음대로", "카톡 위 🎤 버튼을 오른쪽 가운데로") {
                p.bubbleX = -1; p.bubbleY = -1
                KakaoAccessibilityService.instance?.redrawBubble()
                Toast.makeText(this, "버튼을 오른쪽 가운데로 되돌렸어요. 카톡을 다시 열어 보세요.", Toast.LENGTH_LONG).show()
            })
        }
        body.add(kb, 20)

        // 내 말투 정보 (말투에 반영)
        body.add(Kit.section(ctx, R.drawable.ic_user, "나에 대해 (선택)"), 24)
        val me = Kit.card(ctx, 16)
        me.addView(Ui.text(ctx, "내 이름", 15f, bold = true))
        val name = Ui.input(ctx, "예: 윤호요셉").apply { setText(p.myName) }
        me.add(name, 6)
        me.add(Ui.text(ctx, "나는 어떤 사람인가요?", 15f, bold = true), 14)
        val role = Ui.input(ctx, "예: 본당 주임신부 / 70대 어머니").apply { setText(p.myRole) }
        me.add(role, 6)
        me.add(Ui.text(ctx, "적어 두면 '내 말투'와 답장이 더 나에게 맞춰져요.", 13f, color = Ui.MUTED), 8)
        me.add(Kit.primaryButton(ctx, "저장") {
            p.myName = name.text.toString().trim()
            p.myRole = role.text.toString().trim()
            Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
            showSettings = false
            render()
        }, 14)
        body.add(me, 10)

        val etc = Kit.card(ctx, 16)
        etc.addView(Kit.navRow(ctx, R.drawable.ic_search, "대화에서 찾기", "내보낸 대화 보기·찾기") {
            startActivity(Intent(this, AskActivity::class.java))
        })
        with(Kit) { etc.addDivider(10) }
        etc.addView(Kit.navRow(ctx, R.drawable.ic_trash, "저장한 대화·기록 지우기", "설정은 그대로 남아요") { confirmClearAll() })
        body.add(etc, 24)
    }

    // ───────── 말로 답장 키보드 ─────────

    private fun keyboardEnabled(): Boolean = runCatching {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .enabledInputMethodList.any { it.packageName == packageName }
    }.getOrDefault(false)

    private fun keyboardChosen(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty().startsWith("$packageName/")

    private fun shareSetup() {
        val key = Prefs(this).apiKey
        if (key.isBlank()) return
        AlertDialog.Builder(this)
            .setMessage("설정 메시지에는 API 키가 들어 있습니다.\n가족에게만 보내고, 받은 쪽에서 설정이 끝나면 메시지를 지우도록 해 주세요.")
            .setPositiveButton("보내기") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SetupCode.message(key))
                packageManager.getLaunchIntentForPackage(KAKAO)?.let { send.setPackage(KAKAO) }
                runCatching { startActivity(send) }.onFailure { startActivity(Intent.createChooser(send.setPackage(null), "설정 보내기")) }
            }
            .setNegativeButton("취소", null).show()
    }

    // ───────── 자세한 설정 ─────────

    private fun advanced() {
        val p = Prefs(this)
        body.add(Ui.text(this, "자세한 설정", 22f, bold = true), 10)

        body.add(Ui.text(this, "현재 상태", 16f, bold = true, color = Ui.MUTED), 14)
        body.add(Ui.text(this,
            "${if (BuildConfig.STORE_SAFE) (if (keyboardEnabled() && keyboardChosen()) "✅" else "❌") + " 키보드" else (if (accessibilityOn()) "✅" else "❌") + " 카톡 위 버튼"}   ${if (micOn()) "✅" else "❌"} 마이크   ${if (!BuildConfig.STORE_SAFE && p.apiKey.isNotBlank()) "✅ API 키" else if (ClaudeProvider.relayAvailable) "✅ AI 서버" else "❌ API 키"}",
            16f), 4)
        val row = Ui.hbox(this)
        if (BuildConfig.STORE_SAFE) row.add(Ui.button(this, "키보드 설정", Ui.CARD, sp = 15f) {
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }) else row.add(Ui.button(this, "접근성 설정", Ui.CARD, sp = 15f) { openAccessibilitySettings() })
        row.add(Ui.button(this, "앱 정보", Ui.CARD, sp = 15f) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        body.add(row, 8)

        body.add(Ui.text(this, "Claude API 키", 16f, bold = true), 18)
        val key = Ui.input(this, "sk-ant-...", password = true).apply { setText(p.apiKey) }
        body.add(key, 6)
        body.add(Ui.text(this, "답장을 다듬을 때만 말한 내용과 카톡 화면 글이 Anthropic 으로 전송되며, 사용한 만큼 요금이 나갑니다.", 13f, color = Ui.MUTED), 4)

        body.add(Ui.text(this, "내 이름 (선택)", 16f, bold = true), 14)
        val name = Ui.input(this, "예: 윤호요셉").apply { setText(p.myName) }
        body.add(name, 6)
        body.add(Ui.text(this, "나는 어떤 사람인가요? (선택, 말투에 반영)", 16f, bold = true), 12)
        val role = Ui.input(this, "예: 본당 주임신부 / 70대 어머니").apply { setText(p.myRole) }
        body.add(role, 6)

        fun check(label: String, on: Boolean) = CheckBox(this).apply {
            text = label; isChecked = on; textSize = 17f * p.textScale; setTextColor(Ui.BROWN)
        }
        val pressSend = check("입력창에 넣은 뒤 전송 버튼까지 누르기", p.autoPressSend)
        if (!BuildConfig.STORE_SAFE) body.add(pressSend, 12)


        body.add(Ui.button(this, "저장", sp = 22f) {
            p.apiKey = keyFrom(key.text.toString())
            p.myName = name.text.toString().trim()
            p.myRole = role.text.toString().trim()
            p.autoPressSend = pressSend.isChecked
            Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
            showAdvanced = false
            render()
        }, 18)
        body.add(Ui.button(this, "돌아가기", Ui.CARD, sp = 16f) { showAdvanced = false; render() }, 8)
        if (!BuildConfig.STORE_SAFE) notifyReplySection(p)

        // 운영자용: 사용자들의 사용 횟수·요금 (서버 비밀번호 ADMIN_KEY 필요, 웹 페이지로 열림)
        body.add(Ui.button(this, "📊 사용 현황 (운영자)", Ui.CARD, sp = 15f) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.RELAY_URL.trimEnd('/') + "/admin"))) }
        }, 20)
        body.add(Ui.button(this, "🔎 대화에서 찾기 (대화·사진 저장)", Ui.CARD, sp = 15f) {
            startActivity(Intent(this, AskActivity::class.java))
        }, 20)
        if (!BuildConfig.STORE_SAFE && p.apiKey.isNotBlank()) body.add(Ui.button(this, "👨‍👩‍👧 가족에게 설정 보내기", Ui.CARD, sp = 15f) { shareSetup() }, 8)
        if (!BuildConfig.STORE_SAFE) helperDiag(p)
        // 요금 정하기용 사용량 (휴대폰 안에만 셈)
        body.add(Ui.text(this, "이번 달 사용량", 14f, bold = true, color = Ui.MUTED), 24)
        body.add(Ui.text(this,
            p.monthUsage().describe() + (p.lastMonthUsage()?.let { (m, u) -> "\n지난달($m): ${u.describe()}" } ?: ""),
            13f, color = Ui.MUTED).apply { setTextIsSelectable(true) }, 4)
        p.lastUsage.takeIf { it.isNotBlank() }?.let {
            body.add(Ui.text(this, "마지막 다듬기 사용량", 14f, bold = true, color = Ui.MUTED), 24)
            body.add(Ui.text(this, it, 13f, color = Ui.MUTED), 4)
        }
        sendLog()
        p.aiReports.takeIf { it.isNotBlank() }?.let {
            body.add(Ui.text(this, "🚩 이상한 답장 알림 (이 휴대폰에만 기록)", 14f, bold = true, color = Ui.MUTED), 24)
            body.add(Ui.text(this, it, 11f, color = Ui.MUTED).apply { setTextIsSelectable(true) }, 4)
        }
        body.add(Ui.button(this, "🗑 저장한 대화·기록 모두 지우기", Ui.CARD, Ui.RED, sp = 15f) { confirmClearAll() }, 24)
    }

    /** '알림으로 답장' 예비 기능 (직접 설치판만 — 플레이판은 카카오 안내로 뺌) */
    private fun notifyReplySection(p: Prefs) {
        // '알림으로 답장' 예비 기능 (접근성 없이)
        body.add(Ui.text(this, "🔔 알림으로 답장 (예비 기능)", 16f, bold = true), 20)
        body.add(Ui.text(this,
            "카톡 메시지 알림 아래에 '🎤 말로 답장'을 띄우고, 다듬은 답장을 카톡 알림의 답장 칸으로 보냅니다. 카톡 화면을 읽거나 누르지 않아요." +
                (if (p.notifyReply) "\n지금: 켜짐 · 알림 접근 ${if (NotifyReplyService.allowed(this)) "허용됨 ✅" else "허용 안 됨 ❌ (아래 버튼)"}" else "\n지금: 꺼짐"),
            13f, color = Ui.MUTED), 4)
        val notifyRow = Ui.hbox(this)
        notifyRow.add(Ui.button(this, if (p.notifyReply) "끄기" else "켜기", Ui.CARD, sp = 15f) {
            if (p.notifyReply) { p.notifyReply = false; render() } else confirmNotifyReply()
        })
        if (p.notifyReply) notifyRow.add(Ui.button(this, "알림 접근 설정", Ui.CARD, sp = 15f) {
            runCatching { startActivity(NotifyReplyService.settingsIntent(this)) }
        })
        body.add(notifyRow, 6)
    }

    /** 도우미 버튼(접근성) 상태 (직접 설치판만) */
    private fun helperDiag(p: Prefs) {
        // 도우미 버튼이 안 보일 때 원인 확인용
        body.add(Ui.text(this, "도우미 버튼 상태", 14f, bold = true, color = Ui.MUTED), 24)
        body.add(Ui.text(this,
            (if (KakaoAccessibilityService.instance != null) "접근성 서비스: 연결됨\n" else "접근성 서비스: 연결 안 됨 (접근성을 껐다 켜 주세요)\n") +
                p.bubbleDiag.ifBlank { "기록 없음 (카톡을 한 번 열었다가 돌아와 보세요)" },
            13f, color = Ui.MUTED).apply { setTextIsSelectable(true) }, 4)
        body.add(Ui.button(this, "도우미 버튼 위치 초기화", Ui.CARD, sp = 15f) {
            p.bubbleX = -1; p.bubbleY = -1
            KakaoAccessibilityService.instance?.redrawBubble()
            Toast.makeText(this, "버튼을 오른쪽 가운데로 되돌렸어요. 카톡을 다시 열어 보세요.", Toast.LENGTH_LONG).show()
        }, 6)
    }

    private fun sendLog() {
        Prefs(this).exportLog.takeIf { it.isNotBlank() }?.let {
            body.add(Ui.text(this, "대화 내보내기·찾기 기록 (안 될 때 이 화면을 캡처해 주세요)", 14f, bold = true, color = Ui.MUTED), 28)
            body.add(Ui.text(this, it, 11f, color = Ui.MUTED).apply { setTextIsSelectable(true) }, 4)
        }
        val log = Prefs(this).lastSendLog
        if (log.isBlank() || log == "성공") return
        body.add(Ui.text(this, "마지막 보내기 기록 (안 될 때 이 화면을 캡처해 주세요)", 14f, bold = true, color = Ui.MUTED), 28)
        body.add(Ui.text(this, log, 11f, color = Ui.MUTED).apply { setTextIsSelectable(true) }, 4)
        body.add(View(this), 30)
    }
}
