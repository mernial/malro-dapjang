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
        // 제목을 길게 누르면 숨겨 둔 '자세한 설정' (API 키·이름·고급 받아쓰기·문제 확인)
        body.add(Ui.text(this, getString(R.string.app_name), 30f, bold = true).apply {
            setOnLongClickListener { showAdvanced = !showAdvanced; render(); true }
        }, 0)
        updateBanner()

        // 중계 서버가 있으면 설정 코드 단계 없이 두 단계로 끝남
        // 플레이판: 카톡 위 버튼(접근성) 없이 키보드로만 → 1) 키보드 켜고 고르기 2) 마이크
        val steps = if (BuildConfig.STORE_SAFE) listOf(keyboardEnabled() && keyboardChosen(), micOn())
            else listOfNotNull(accessibilityOn(), micOn(), if (ClaudeProvider.relayAvailable) null else p.apiKey.isNotBlank())
        val current = steps.indexOfFirst { !it }
        if (current >= 0 && !showAdvanced) {
            body.add(Ui.text(this, "준비 ${current + 1} / ${steps.size}", 18f, bold = true, color = Ui.MUTED), 8)
            when (current) {
                0 -> if (BuildConfig.STORE_SAFE) stepKeyboard() else stepAccessibility()
                1 -> stepMic()
                2 -> stepSetupCode()
            }
            footer()
            return
        }
        if (!showAdvanced) done() else advanced()
        footer()
    }

    /** 모든 화면 맨 아래: 개인정보처리방침과 문의 (구글 플레이 정책: 앱 안에서도 방침을 볼 수 있어야 함) */
    private fun footer() {
        body.add(Ui.button(this, "개인정보처리방침 보기", Ui.CARD, sp = 14f) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
                .onFailure { Toast.makeText(this, PRIVACY_URL, Toast.LENGTH_LONG).show() }
        }, 32)
        body.add(Ui.text(this, "버전 ${BuildConfig.VERSION_NAME}\n문의·불편 신고: $REPORT_EMAIL\n이 앱은 카카오와 관련이 없는 독립 앱입니다.", 13f, color = Ui.MUTED)
            .apply { setTextIsSelectable(true) }, 8)
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
        val box = Ui.vbox(this, 18, Ui.CARD)
        box.add(Ui.text(this, title, 26f, bold = true), 0)
        box.add(Ui.text(this, desc, 19f), 12)
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

    // ───────── 완료 ─────────

    private fun done() {
        val p = Prefs(this)

        keyboardCard()
        if (BuildConfig.STORE_SAFE) { storeHowTo(); return }

        // 켜기 / 끄기: 흰 카드 + 직접 그린 큰 스위치 (기본 스위치를 키우면 잘려 보여서)
        val ctx = this
        val onRow = Ui.hbox(ctx).apply {
            background = Ui.rounded(ctx, Ui.CARD, 22, if (p.enabled) Ui.BROWN else Ui.LINE)
            val pad = Ui.dp(ctx, 18); setPadding(pad, pad, pad, pad)
            gravity = android.view.Gravity.CENTER_VERTICAL
            elevation = Ui.dp(ctx, 2).toFloat()
        }
        val label = Ui.vbox(ctx)
        label.add(Ui.text(ctx, if (p.enabled) "켜져 있어요" else "꺼져 있어요", 24f, bold = true,
            color = if (p.enabled) Ui.BROWN else Ui.MUTED), 0)
        label.add(Ui.text(ctx, if (p.enabled) "카톡 대화방에 🎤 버튼이 떠요" else "카톡에 버튼이 나오지 않아요", 15f, color = Ui.MUTED), 2)
        onRow.addView(label, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Ui.toggle(ctx, p.enabled) { on ->
            p.enabled = on
            KakaoAccessibilityService.instance?.redrawBubble()
            render()
        }
        toggle.contentDescription = "말로 답장 켜기 끄기"
        onRow.addView(toggle)
        onRow.setOnClickListener { toggle.performClick() }
        body.add(onRow, 16)

        // 사용법: 세 가지 손동작을 칩으로 한눈에
        val how = Ui.vbox(ctx, 16, Ui.THEM)
        how.add(Ui.text(ctx, "카톡 🎤 버튼 쓰는 법", 17f, bold = true), 0)
        fun gesture(chip: String, what: String) {
            // 칩은 글자 크기만큼, 설명은 그 아래 줄에 (큰 글씨 설정에서도 줄이 꼬이지 않게)
            val item = Ui.vbox(ctx)
            item.addView(Ui.text(ctx, chip, 15f, bold = true, color = Ui.YELLOW).apply {
                background = Ui.rounded(ctx, Ui.BROWN, 14)
                val ph = Ui.dp(ctx, 12); val pv = Ui.dp(ctx, 5); setPadding(ph, pv, ph, pv)
            }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            item.add(Ui.text(ctx, what, 17f).apply { setPadding(Ui.dp(ctx, 4), 0, 0, 0) }, 6)
            how.add(item, 14)
        }
        gesture("누르기", "말하고 끝에 \"…라고 보내 줘\" 또는 \"…라고 전해 줘\"")
        gesture("꾹 누르기", "말투 바꾸기 · 🔎 찾기 · ⚙ 설정")
        gesture("끌어 옮기기", "버튼 자리 바꾸기")
        body.add(how, 16)

        // 말투 예시: 같은 말이 말투마다 어떻게 바뀌는지 한눈에
        val ex = Ui.vbox(ctx, 16, Ui.CARD)
        ex.add(Ui.text(ctx, "말투에 따라 이렇게 바뀌어요", 17f, bold = true), 0)
        ex.add(Ui.text(ctx, "🎤 \"알았어 고마워\"", 18f, bold = true).apply {
            background = Ui.rounded(ctx, Ui.THEM, 14)
            val ph = Ui.dp(ctx, 14); val pv = Ui.dp(ctx, 10); setPadding(ph, pv, ph, pv)
        }, 12)
        fun example(m: kr.yunho.talkhelper.core.Mode, out: String) {
            val row = Ui.hbox(ctx)
            row.addView(Ui.text(ctx, "${m.emoji} ${m.label}", 14f, bold = true).apply {
                background = Ui.rounded(ctx, Ui.CARD, 14, Ui.LINE)
                gravity = android.view.Gravity.CENTER
                val ph = Ui.dp(ctx, 8); val pv = Ui.dp(ctx, 8); setPadding(ph, pv, ph, pv)
            }, android.widget.LinearLayout.LayoutParams(Ui.dp(ctx, 124), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(Ui.text(ctx, out, 16f, bold = true).apply {
                background = Ui.rounded(ctx, Ui.MINE, 14)
                val ph = Ui.dp(ctx, 12); val pv = Ui.dp(ctx, 9); setPadding(ph, pv, ph, pv)
            }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 8) })
            ex.add(row, 10)
        }
        example(kr.yunho.talkhelper.core.Mode.POLITE, "네, 알겠어요. 고마워요.")
        example(kr.yunho.talkhelper.core.Mode.FRIENDLY, "알았어, 고마워!")
        example(kr.yunho.talkhelper.core.Mode.BUSINESS, "네, 확인했습니다. 감사합니다.")
        ex.add(Ui.text(ctx, "🤗 자녀에게 · 🪞 내 말투도 있어요. 🎤 버튼을 꾹 누르고 '말투'를 고르면 그 방에서는 계속 그 말투로 써요.", 14f, color = Ui.MUTED), 12)
        body.add(ex, 16)

        // 보내는 방식: 한 줄 알약 + 설명 한 줄
        body.add(Ui.text(ctx, "보내는 방식", 18f, bold = true), 24)
        body.add(Ui.sendModePicker(ctx, p), 8)

        // 찾은 내용 읽어 주기 (기본: 안 읽음) — 위와 같은 모양의 스위치
        val readRow = Ui.hbox(ctx).apply { setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4)) }
        readRow.addView(Ui.text(ctx, "찾은 내용 소리로 읽어 주기", 18f),
            android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        readRow.addView(Ui.toggle(ctx, p.readAnswers) { on -> p.readAnswers = on }.apply { contentDescription = "찾은 내용 소리로 읽어 주기" })
        body.add(readRow, 24)

        body.add(View(this), 40)
    }

    /** 플레이판 완료 화면: 키보드 쓰는 법 + 말투 예시 */
    private fun storeHowTo() {
        val ctx = this
        val how = Ui.vbox(ctx, 16, Ui.THEM)
        how.add(Ui.text(ctx, "키보드 쓰는 법", 17f, bold = true), 0)
        fun item(chip: String, what: String) {
            val it = Ui.vbox(ctx)
            it.addView(Ui.text(ctx, chip, 15f, bold = true, color = Ui.YELLOW).apply {
                background = Ui.rounded(ctx, Ui.BROWN, 14)
                val ph = Ui.dp(ctx, 12); val pv = Ui.dp(ctx, 5); setPadding(ph, pv, ph, pv)
                Ui.iconize(this)
            }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            it.add(Ui.text(ctx, what, 17f).apply { setPadding(Ui.dp(ctx, 4), 0, 0, 0) }, 6)
            how.add(it, 14)
        }
        item("🎤 말로 답장", "누르고 편하게 말하면, 다듬은 글이 입력칸에 들어가요. 확인하고 보내기는 직접 누르세요.")
        item("말투 ▾", "정중하게·다정하게 같은 말투를 골라요. 방금 넣은 글도 그 말투로 바뀌어요.")
        item("🔎 찾기", "카톡 대화방 ☰ → 설정 → '대화 내용 내보내기' → '말로 답장'으로 보내 둔 대화에서 찾아 줘요.")
        item("⇄ 천지인", "두벌식·천지인 자판을 바꿔요.")
        body.add(how, 16)

        val ex = Ui.vbox(ctx, 16, Ui.CARD)
        ex.add(Ui.text(ctx, "말투에 따라 이렇게 바뀌어요", 17f, bold = true), 0)
        ex.add(Ui.text(ctx, "🎤 \"알았어 고마워\"", 18f, bold = true).apply {
            background = Ui.rounded(ctx, Ui.THEM, 14)
            val ph = Ui.dp(ctx, 14); val pv = Ui.dp(ctx, 10); setPadding(ph, pv, ph, pv)
        }, 12)
        for ((m, out) in listOf(
            kr.yunho.talkhelper.core.Mode.POLITE to "네, 알겠어요. 고마워요.",
            kr.yunho.talkhelper.core.Mode.FRIENDLY to "알았어, 고마워!",
            kr.yunho.talkhelper.core.Mode.BUSINESS to "네, 확인했습니다. 감사합니다.",
        )) {
            val row = Ui.hbox(ctx)
            row.addView(Ui.text(ctx, "${m.emoji} ${m.label}", 14f, bold = true).apply {
                background = Ui.rounded(ctx, Ui.CARD, 14, Ui.LINE)
                gravity = android.view.Gravity.CENTER
                val pp = Ui.dp(ctx, 8); setPadding(pp, pp, pp, pp)
            }, android.widget.LinearLayout.LayoutParams(Ui.dp(ctx, 124), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(Ui.text(ctx, out, 16f, bold = true).apply {
                background = Ui.rounded(ctx, Ui.MINE, 14)
                val ph = Ui.dp(ctx, 12); val pv = Ui.dp(ctx, 9); setPadding(ph, pv, ph, pv)
            }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 8) })
            ex.add(row, 10)
        }
        ex.add(Ui.text(ctx, "🤗 자녀에게 · 🪞 내 말투도 있어요. 키보드 위 '말투 ▾'에서 고르세요.", 14f, color = Ui.MUTED), 12)
        body.add(ex, 16)
        body.add(View(this), 40)
    }

    // ───────── 말로 답장 키보드 ─────────

    private fun keyboardEnabled(): Boolean = runCatching {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .enabledInputMethodList.any { it.packageName == packageName }
    }.getOrDefault(false)

    private fun keyboardChosen(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty().startsWith("$packageName/")

    /** 키보드 켜기·고르기 안내 카드 (카톡 등 어디서나: 말하면 다듬은 글이 입력칸에) */
    private fun keyboardCard() {
        val ctx = this
        val on = keyboardEnabled(); val chosen = keyboardChosen()
        val box = Ui.vbox(ctx, 18, Ui.MINE)
        box.add(Ui.text(ctx, "⌨ 말로 답장 키보드", 22f, bold = true), 0)
        box.add(Ui.text(ctx, "평소처럼 글도 치고, 위의 🎤 말로 답장을 누르고 말하면 다듬은 글이 입력칸에 들어가요. 보내기는 직접 누르세요.", 15f), 6)
        box.add(Ui.text(ctx,
            "${if (on) "✅" else "1️⃣"} 키보드 켜기   ${if (chosen) "✅" else "2️⃣"} 키보드 고르기", 16f, bold = true), 10)
        if (!on) box.add(Ui.button(ctx, "👉 1. 키보드 켜기", Ui.BROWN, Ui.YELLOW, sp = 18f) {
            Toast.makeText(ctx, "목록에서 '${getString(R.string.keyboard_name)}'을 켜 주세요", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }, 10)
        else if (!chosen) box.add(Ui.button(ctx, "👉 2. 이 키보드 고르기", Ui.BROWN, Ui.YELLOW, sp = 18f) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
        }, 10)
        else {
            box.add(Ui.text(ctx, "준비됐어요. 이제 글을 쓸 때마다 말로 답장 키보드가 떠요.", 15f, color = Ui.GREEN), 8)
            box.add(Ui.button(ctx, "다른 키보드로 바꾸기", Ui.CARD, Ui.BROWN, sp = 15f) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
            }, 8)
        }
        body.add(box, 16)
    }

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
