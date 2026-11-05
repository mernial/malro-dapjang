package kr.yunho.talkhelper

import android.content.Context
import kr.yunho.talkhelper.core.RoomMatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 내보내 저장한 대화의 목록. 대화마다 앱이 붙인 고유 번호(r_xxxxxxxx)로 저장하고,
 * 그 대화를 내보낸 카톡 방의 화면 이름을 함께 기억함.
 *  - 카톡 방 이름이 같아도(가족방이 여럿 등) 번호가 달라 서로 덮어쓰지 않음
 *  - 카톡 방에 들어가 찾으면 그 방 이름으로 번호를 바로 찾음 (RoomMatch.lookup)
 * 목록 파일: chats/rooms.json
 */
object Rooms {
    private fun file(c: Context) = File(ChatStore.dir(c), "rooms.json")
    private val lock = Any()

    fun newId() = "r_" + UUID.randomUUID().toString().replace("-", "").take(8)
    fun isId(s: String) = Regex("^r_[0-9a-f]{8}$").matches(s)

    fun all(c: Context): List<RoomMatch.Entry> = synchronized(lock) {
        migrate(c)
        read(c)
    }

    /** 보여 줄 이름 (번호가 아니면 그대로) */
    fun name(c: Context, id: String?): String? = id?.let { i -> all(c).firstOrNull { it.id == i }?.name ?: i }

    /** 저장된 대화 번호들, 최근에 저장한 순서 */
    fun ids(c: Context): List<String> {
        val known = all(c).map { it.id }.toSet()
        return ChatStore.dir(c).listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
            .sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(".txt") }
            .filter { it in known }
    }

    /** [id] 를 저장 (이름·카톡 방 이름 추가) */
    fun put(c: Context, id: String, name: String, title: String?) = synchronized(lock) {
        val list = read(c).toMutableList()
        val old = list.firstOrNull { it.id == id }
        val titles = (old?.titles.orEmpty() + listOfNotNull(title?.let { RoomMatch.norm(it) }?.takeIf { it.isNotEmpty() })).toSet()
        list.removeAll { it.id == id }
        list.add(RoomMatch.Entry(id, name, titles))
        write(c, list)
    }

    fun remove(c: Context, id: String) = synchronized(lock) { write(c, read(c).filterNot { it.id == id }) }

    private fun read(c: Context): List<RoomMatch.Entry> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.optJSONArray("titles") ?: JSONArray()
                RoomMatch.Entry(o.getString("id"), o.getString("name"), (0 until t.length()).map { t.getString(it) }.toSet())
            }
        }.getOrDefault(emptyList())
    }

    private fun write(c: Context, list: List<RoomMatch.Entry>) {
        val arr = JSONArray()
        list.forEach { e -> arr.put(JSONObject().put("id", e.id).put("name", e.name).put("titles", JSONArray(e.titles.toList()))) }
        file(c).writeText(arr.toString())
    }

    /**
     * 1.106 이전처럼 방 이름으로 저장된 대화(<방>.txt)를 번호로 옮김.
     * 이름 자체와, 예전에 사용자가 직접 골라 기억한 카톡 방 이름을 짝으로 넘김. 한 번만 함.
     */
    private fun migrate(c: Context) {
        val sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("roomsV3", false)) return
        val dir = ChatStore.dir(c)
        val aliases = (sp.getString("roomAliases", "") ?: "").lines().mapNotNull { l ->
            l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }
        val list = read(c).toMutableList()
        val edit = sp.edit()
        dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().forEach { f ->
            val name = f.name.removeSuffix(".txt")
            if (isId(name)) return@forEach
            val id = newId()
            val stamp = f.lastModified()
            val moved = File(dir, "$id.txt")
            if (!f.renameTo(moved)) return@forEach
            moved.setLastModified(stamp)
            File(dir, "$name.media").takeIf { it.exists() }?.renameTo(File(dir, "$id.media"))
            val titles = (listOf(RoomMatch.norm(name)) + aliases.filter { it.second == name }.map { it.first })
                .filter { it.isNotEmpty() }.toSet()
            list.add(RoomMatch.Entry(id, name, titles))
            sp.getString("kakaoMe:$name", null)?.let { edit.putString("kakaoMe:$id", it).remove("kakaoMe:$name") }
        }
        write(c, list)
        edit.remove("roomAliases").putBoolean("roomAliasesV2", true).putBoolean("roomsV3", true).apply()
    }
}
