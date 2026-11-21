package kr.yunho.talkhelper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * '내 파일 폴더에서 찾기' (신부님 2026-11-18).
 * 사용자가 휴대폰 파일 고르기 창에서 직접 고른 **폴더 하나**만 읽음 (모든 파일 권한 없음 — 구글 정책).
 * 글자는 휴대폰 안에서 꺼내 앱 저장공간(files/docs)에 두고, 찾을 때 질문과 관련된 부분만 AI 에 보냄.
 * 지금 읽는 것: 글 파일(txt·md·csv·json·html·xml·log)과 PDF. 그 밖의 파일(한글·사진 등)은 이름으로만 찾음.
 */
object DocIndex {
    data class Doc(val uri: String, val name: String, val mime: String, val modified: Long, val hasText: Boolean)

    private const val MAX_FILES = 400
    private const val MAX_DEPTH = 4
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_CHARS = 200_000
    private val TEXT_EXT = setOf("txt", "md", "csv", "json", "html", "htm", "xml", "log")

    private fun dir(c: Context) = File(c.filesDir, "docs").apply { mkdirs() }
    private fun indexFile(c: Context) = File(dir(c), "index.json")
    private fun key(uri: String) = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

    fun treeUri(c: Context): Uri? = Prefs(c).docTree?.let { Uri.parse(it) }
    fun enabled(c: Context) = Prefs(c).docConsent && treeUri(c) != null

    /** 고른 폴더의 이름 (화면에 보여 줄 것) */
    fun folderName(c: Context): String? = treeUri(c)?.let { t ->
        runCatching { DocumentsContract.getTreeDocumentId(t).substringAfterLast(':').substringAfterLast('/').ifBlank { "고른 폴더" } }.getOrNull()
    }

    fun docs(c: Context): List<Doc> = runCatching {
        val a = JSONArray(indexFile(c).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Doc(o.getString("uri"), o.getString("name"), o.optString("mime"), o.optLong("modified"), o.optBoolean("text"))
        }
    }.getOrDefault(emptyList())

    fun text(c: Context, d: Doc): String = if (!d.hasText) "" else
        File(dir(c), key(d.uri) + ".txt").takeIf { it.exists() }?.readText().orEmpty()

    /** 폴더를 다시 읽음. 바뀐 파일만 글자를 새로 꺼냄. 읽은 파일 수를 돌려줌 */
    fun refresh(c: Context): Int {
        val tree = treeUri(c) ?: return 0
        val old = docs(c).associateBy { it.uri }
        val found = ArrayList<Doc>()
        walk(c, tree, DocumentsContract.getTreeDocumentId(tree), 0, found, old)
        val keep = found.map { key(it.uri) + ".txt" }.toSet()
        dir(c).listFiles()?.forEach { f -> if (f.name.endsWith(".txt") && f.name !in keep) f.delete() }
        val a = JSONArray()
        found.forEach { d -> a.put(JSONObject().put("uri", d.uri).put("name", d.name).put("mime", d.mime).put("modified", d.modified).put("text", d.hasText)) }
        indexFile(c).writeText(a.toString())
        return found.size
    }

    private fun walk(c: Context, tree: Uri, parentId: String, depth: Int, out: MutableList<Doc>, old: Map<String, Doc>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val rows = ArrayList<Array<Any?>>()
        c.contentResolver.query(kids, cols, null, null, null)?.use { cur ->
            while (cur.moveToNext()) rows.add(arrayOf(cur.getString(0), cur.getString(1), cur.getString(2), cur.getLong(3), cur.getLong(4)))
        }
        for (r in rows) {
            if (out.size >= MAX_FILES) return
            val id = r[0] as String; val name = (r[1] as String?).orEmpty(); val mime = (r[2] as String?).orEmpty()
            val modified = r[3] as Long; val size = r[4] as Long
            if (name.startsWith(".")) continue
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { walk(c, tree, id, depth + 1, out, old); continue }
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
            val prev = old[uri]
            val cache = File(dir(c), key(uri) + ".txt")
            val hasText = if (prev != null && prev.modified == modified && (!prev.hasText || cache.exists())) prev.hasText
            else {
                val t = if (size in 1..MAX_BYTES) runCatching { extract(c, Uri.parse(uri), name, mime) }.getOrNull() else null
                if (!t.isNullOrBlank()) { cache.writeText(t.take(MAX_CHARS)); true } else { cache.delete(); false }
            }
            out.add(Doc(uri, name, mime, modified, hasText))
        }
    }

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun extract(c: Context, uri: Uri, name: String, mime: String): String? {
        val e = ext(name)
        return when {
            e == "pdf" || mime == "application/pdf" -> c.contentResolver.openInputStream(uri)?.use { input ->
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(c.applicationContext)
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                    com.tom_roush.pdfbox.text.PDFTextStripper().apply { endPage = 60 }.getText(doc)
                }
            }
            e in TEXT_EXT || mime.startsWith("text/") -> c.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                var s = String(bytes, Charsets.UTF_8)
                if (s.count { it == '�' } > s.length / 50) s = String(bytes, charset("EUC-KR"))
                if (e == "html" || e == "htm" || e == "xml") s.replace(Regex("<[^>]+>"), " ") else s
            }
            else -> null
        }
    }

    /** 찾기에 넘길 [파일 이름, 글자] (글자를 꺼낸 파일만) */
    fun forSearch(c: Context): List<Pair<String, String>> =
        docs(c).filter { it.hasText }.map { "📄 " + it.name to text(c, it) }.filter { it.second.isNotBlank() }

    /** 이름이 질문과 맞는 파일 (한글·사진처럼 글자를 못 꺼낸 파일도) */
    fun nameMatches(c: Context, question: String, max: Int = 5): List<Doc> {
        val keys = question.split(Regex("[\\s,.?!~]+")).map { it.trim() }.filter { it.length >= 2 }
            .map { it.removeSuffix("파일").removeSuffix("문서").removeSuffix("이").removeSuffix("가") }.filter { it.length >= 2 }
        if (keys.isEmpty()) return emptyList()
        return docs(c).map { d -> d to keys.count { k -> d.name.replace(" ", "").contains(k.replace(" ", ""), ignoreCase = true) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** 그만 쓰기: 폴더 허락을 돌려주고 꺼낸 글자를 모두 지움 */
    fun stop(c: Context) {
        treeUri(c)?.let { t -> runCatching { c.contentResolver.releasePersistableUriPermission(t, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        dir(c).deleteRecursively()
        Prefs(c).docTree = null
        Prefs(c).docConsent = false
    }

    const val CONSENT_TEXT = "📁 고른 폴더 하나 안의 파일만 찾아요. 다른 폴더나 휴대폰의 다른 파일은 보지 않아요.\n\n" +
        "파일은 휴대폰 안에서 읽고, 찾을 때 질문과 관련된 부분만 AI(Anthropic Claude)에 보내 답을 찾아요. 보낸 내용은 저장되지 않아요.\n\n" +
        "다음 화면에서 '다운로드' 안에 새 폴더(예: 찾을 파일)를 만들어 고르시면, 그 폴더에 넣은 파일만 찾아요. 언제든 '그만 쓰기'로 끌 수 있어요."
}
