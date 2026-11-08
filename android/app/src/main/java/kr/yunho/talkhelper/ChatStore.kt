package kr.yunho.talkhelper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kr.yunho.talkhelper.core.ChatSearch
import kr.yunho.talkhelper.core.MyStyle
import kr.yunho.talkhelper.core.PhotoFind
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * '대화에서 찾기'용으로 내보낸 카톡 대화를 휴대폰 안(앱 저장공간)에 저장.
 *  - chats/<번호>.txt       대화 글 (번호는 앱이 붙인 고유 번호, 이름·카톡 방 짝은 Rooms 에)
 *  - chats/<번호>.media/    함께 내보낸 사진 (있을 때만). 사진은 휴대폰 밖으로 보내지 않음
 */
object ChatStore {
    fun dir(c: Context) = File(c.filesDir, "chats").apply { mkdirs() }
    fun mediaDir(c: Context, room: String) = File(dir(c), "$room.media")
    private fun safe(room: String) = room.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)

    /**
     * 앱에서 내보내기를 시작한 카톡 방의 화면 이름. 있으면 저장한 대화를 이 방과 짝지음
     * (파일에 방 이름이 없을 때 이름으로도 씀)
     */
    @Volatile var nameHint: String? = null
    @Volatile var screenHint: String? = null

    /** 저장 결과: 대화 번호, 보여 줄 이름, 함께 저장한 사진 수 */
    data class Saved(val room: String, val name: String, val photos: Int)

    /** 공유받은 글(.txt) 또는 압축 파일(.zip, 사진 포함 가능) 저장 */
    fun importStream(c: Context, open: () -> InputStream?): Saved? {
        val incoming = File(dir(c), ".incoming").apply { deleteRecursively(); mkdirs() }
        var text: String? = null
        var photos = 0
        val input = BufferedInputStream(open() ?: return null)
        input.use { ins ->
            ins.mark(4)
            val zip = ins.read() == 'P'.code && ins.read() == 'K'.code
            ins.reset()
            if (zip) {
                ZipInputStream(ins).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        val name = e.name.substringAfterLast('/')
                        when {
                            name.endsWith(".txt", ignoreCase = true) && text == null -> text = z.readBytes().toString(Charsets.UTF_8)
                            PhotoFind.isImage(name) -> {
                                val f = unique(incoming, name)
                                f.outputStream().use { z.copyTo(it) }
                                if (e.time > 0) f.setLastModified(e.time)
                                photos++
                            }
                        }
                    }
                }
            } else text = ins.readBytes().toString(Charsets.UTF_8)
        }
        return finish(c, text, incoming, photos)
    }

    /** 공유받은 글자만 저장 */
    fun importText(c: Context, text: String): Saved? =
        finish(c, text, File(dir(c), ".incoming").apply { deleteRecursively(); mkdirs() }, 0)

    /** 카톡 '모든 메시지 내부 저장소에 저장'으로 만든 폴더를 골라 가져오기 (글 + 사진) */
    fun importTree(c: Context, tree: Uri): Saved? {
        val cr = c.contentResolver
        val incoming = File(dir(c), ".incoming").apply { deleteRecursively(); mkdirs() }
        var text: String? = null
        var photos = 0
        fun walk(docId: String, depth: Int) {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            cr.query(kids, cols, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getString(0) ?: continue
                    val name = cur.getString(1) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    when {
                        cur.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR -> if (depth < 3) walk(id, depth + 1)
                        name.endsWith(".txt", ignoreCase = true) && text == null ->
                            text = cr.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        PhotoFind.isImage(name) -> {
                            val f = unique(incoming, name)
                            cr.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                            val mod = cur.getLong(3)
                            if (mod > 0) f.setLastModified(mod)
                            photos++
                        }
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return finish(c, text, incoming, photos)
    }

    private fun unique(d: File, name: String): File {
        var f = File(d, name)
        var i = 1
        while (f.exists()) f = File(d, "${i++}_$name")
        return f
    }

    private fun finish(c: Context, text: String?, incoming: File, photos: Int): Saved? {
        // 카톡 대화가 아니면(다른 앱에서 사진·문서를 잘못 보낸 경우) 저장하지 않음
        if (text.isNullOrBlank() || !looksLikeKakaoChat(text)) { incoming.deleteRecursively(); return null }
        val hint = nameHint?.takeIf { it.isNotBlank() }
        val fileName = ChatSearch.roomName(text)
        val name = safe(fileName ?: hint ?: "내보낸 대화")
        // 앱에서 '찾기'를 누른 방과 실제로 내보낸 방이 같은지: 이름이 맞거나, 그때 화면에 보이던 메시지가 이 파일에 들어 있으면 같은 방
        // (화면 제목이 잘렸거나 파일 속 이름과 달라도 묶임). 둘 다 아니면(다른 방을 내보냄) 묶지 않음
        val screenOk = screenHint?.let { kr.yunho.talkhelper.core.RoomMatch.matches(it.lines(), text) >= 2 } ?: false
        val bind = hint?.takeIf { fileName == null || kr.yunho.talkhelper.core.RoomMatch.sameRoomName(it, fileName) || screenOk }
        // 같은 방을 다시 내보낸 것이면 그 번호에 덮어쓰고, 아니면 새 번호 (이름만 같은 다른 방은 섞지 않음)
        val room = kr.yunho.talkhelper.core.RoomMatch.reuseTarget(bind, name, text, Rooms.all(c)) { chatText(c, it).orEmpty() }
            ?: Rooms.newId()
        File(dir(c), "$room.txt").writeText(text)
        Rooms.put(c, room, name, name)
        if (bind != null) Rooms.put(c, room, name, bind)
        // 이 대화에서 말한 사람들도 기억 (내보낸 뒤 새 메시지만 보여도 화면의 이름으로 방을 알아보게)
        Rooms.putSenders(c, room, text)
        // 같은 방을 예전에 다른 번호로 저장해 둔 것(이름·첫 메시지가 같음)은 지워 하나로 합침
        val first = kr.yunho.talkhelper.core.RoomMatch.firstMessage(text)
        if (first != null) Rooms.all(c).filter { e ->
            e.id != room && kr.yunho.talkhelper.core.RoomMatch.norm(e.name) == kr.yunho.talkhelper.core.RoomMatch.norm(name) &&
                kr.yunho.talkhelper.core.RoomMatch.firstMessage(chatText(c, e.id).orEmpty()) == first
        }.forEach { old ->
            old.titles.forEach { t -> Rooms.put(c, room, name, t) }
            // 사진은 새로 받은 게 없으면 예전 것을 옮겨 둠
            val oldMedia = mediaDir(c, old.id); val newMedia = mediaDir(c, room)
            if (oldMedia.exists() && !newMedia.exists()) oldMedia.renameTo(newMedia)
            delete(c, old.id)
        }
        val media = mediaDir(c, room)
        if (photos > 0) { media.deleteRecursively(); incoming.renameTo(media) }
        else incoming.deleteRecursively()   // 글만 다시 내보낸 경우 전에 받은 사진은 그대로 둠
        return Saved(room, name, photos)
    }

    /** 내보낸 카톡 대화인지: 첫머리에 '카카오톡 대화' 표시가 있거나, 앞쪽에 '날짜, 이름 : 메시지' 줄이 있음 */
    fun looksLikeKakaoChat(text: String): Boolean =
        ChatSearch.roomName(text) != null ||
            text.lineSequence().take(300).count { MyStyle.parse(it) != null } >= 2

    fun hasPhotos(c: Context, room: String?) = room != null && (mediaDir(c, room).list()?.isNotEmpty() == true)

    /** [at] 무렵에 올라온 사진 파일 (파일 이름의 시각, 없으면 파일 시각으로 맞춤) */
    fun photosAt(c: Context, room: String?, at: LocalDateTime): List<File> {
        if (room == null) return emptyList()
        val files = mediaDir(c, room).listFiles()?.filter { PhotoFind.isImage(it.name) }.orEmpty()
        val timed = files.map { f ->
            f to (PhotoFind.nameTime(f.name) ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(f.lastModified()), ZoneId.systemDefault()))
        }
        return PhotoFind.pick(timed, at)
    }

    // ───────── 내 말투 ─────────

    fun chatText(c: Context, room: String): String? = File(dir(c), "$room.txt").takeIf { it.exists() }?.readText()

    /** 이 방 대화에서 '나'인 사람 이름. 저장해 둔 것 → 설정 이름과 맞는 사람 → 여러 방에 공통으로 나오는 사람 */
    fun meFor(c: Context, room: String, chat: String = chatText(c, room).orEmpty()): String? {
        val p = Prefs(c)
        val senders = MyStyle.senders(chat)
        p.kakaoMe(room)?.takeIf { it in senders }?.let { return it }
        val others = Rooms.ids(c).filter { it != room }.take(5)
            .map { r -> MyStyle.senders(chatText(c, r).orEmpty()) }
        return MyStyle.guessMe(p.myName, listOf(senders) + others)?.also { p.setKakaoMe(room, it) }
    }

    /** 이 방에서 내가 보낸 최근 메시지 (내 말투 예시). 방을 저장하지 않았거나 내가 누군지 모르면 빈 목록 */
    fun myStyle(c: Context, room: String?): List<String> {
        val r = room ?: return emptyList()
        val chat = chatText(c, r) ?: return emptyList()
        val me = meFor(c, r, chat) ?: return emptyList()
        return MyStyle.examples(chat, me)
    }

    fun delete(c: Context, room: String) {
        File(dir(c), "$room.txt").delete()
        mediaDir(c, room).deleteRecursively()
        Rooms.remove(c, room)
    }
}
