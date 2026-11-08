package kr.yunho.talkhelper

import android.content.Context
import kr.yunho.talkhelper.core.MyStyle
import kr.yunho.talkhelper.core.RoomMatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 내보내 저장한 대화의 목록. 대화마다 앱이 붙인 고유 번호(r_xxxxxxxx)로 저장하고,
 * 그 대화를 내보낸 카톡 방의 화면 이름과 그 대화에서 말한 사람들을 함께 기억함.
 *  - 카톡 방 이름이 같아도(가족방이 여럿 등) 번호가 달라 서로 덮어쓰지 않음
 *  - 카톡 방에 들어가 찾으면 그 방 이름(또는 화면의 사람 이름들)으로 번호를 바로 찾음 (RoomMatch.lookup)
 * 목록 파일: chats/rooms.json
 */
object Rooms {
    private fun file(c: Context) = File(ChatStore.dir(c), "rooms.json")
    private val lock = Any()
    /** 기억해 두는 사람 수 (많이 말한 순서) */
    private const val MAX_SENDERS = 60

    fun newId() = "r_" + UUID.randomUUID().toString().replace("-", "").take(8)
    fun isId(s: String) = Regex("^r_[0-9a-f]{8}$").matches(s)

    fun all(c: Context): List<RoomMatch.Entry> = synchronized(lock) {
        migrate(c)
        repair(c)
        repairV5(c)
        fillSenders(c)
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

    /** [id] 를 저장 (이름·카톡 방 이름 추가). 사람 목록은 그대로 둠 */
    fun put(c: Context, id: String, name: String, title: String?) = synchronized(lock) {
        val list = read(c).toMutableList()
        val old = list.firstOrNull { it.id == id }
        val titles = (old?.titles.orEmpty() + listOfNotNull(title?.let { RoomMatch.norm(it) }?.takeIf { it.isNotEmpty() })).toSet()
        list.removeAll { it.id == id }
        list.add(RoomMatch.Entry(id, name, titles, old?.senders.orEmpty()))
        write(c, list)
    }

    /** [id] 대화에서 말한 사람들을 기억 (화면의 사람 이름으로 방을 알아보는 데 씀) */
    fun putSenders(c: Context, id: String, chat: String) = synchronized(lock) {
        val list = read(c).toMutableList()
        val old = list.firstOrNull { it.id == id } ?: return@synchronized
        list.removeAll { it.id == id }
        list.add(old.copy(senders = MyStyle.senders(chat).take(MAX_SENDERS).toSet()))
        write(c, list)
    }

    /** 이 방을 화면 이름 [title] 로도 알아보게 기억 (다음부터는 이름만으로 바로 찾음) */
    fun learn(c: Context, id: String, title: String?) {
        if (RoomMatch.isTruncated(title)) return   // 잘린 제목은 기억하지 않음 (앞부분 비교로 매번 찾아짐)
        val t = title?.let { RoomMatch.norm(it) }?.takeIf { it.isNotEmpty() } ?: return
        val e = all(c).firstOrNull { it.id == id } ?: return
        if (t !in e.titles) put(c, id, e.name, title)
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
                val s = o.optJSONArray("senders") ?: JSONArray()
                RoomMatch.Entry(
                    o.getString("id"), o.getString("name"),
                    (0 until t.length()).map { t.getString(it) }.toSet(),
                    (0 until s.length()).map { s.getString(it) }.toSet(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(c: Context, list: List<RoomMatch.Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("name", e.name)
                .put("titles", JSONArray(e.titles.toList())).put("senders", JSONArray(e.senders.toList())))
        }
        file(c).writeText(arr.toString())
    }

    /**
     * 1.106 이전처럼 방 이름으로 저장된 대화(<방>.txt)를 번호로 옮김. 짝은 그 이름 하나만 넘김.
     * (예전 '방 이름 짝'에는 화면 내용으로 잘못 짐작한 짝이 섞여 있어서 넘기지 않음) 한 번만 함.
     */
    private fun migrate(c: Context) {
        val sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("roomsV3", false)) return
        val dir = ChatStore.dir(c)
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
            val titles = setOf(RoomMatch.norm(name)).filter { it.isNotEmpty() }.toSet()
            list.add(RoomMatch.Entry(id, name, titles))
            sp.getString("kakaoMe:$name", null)?.let { edit.putString("kakaoMe:$id", it).remove("kakaoMe:$name") }
        }
        write(c, list)
        edit.remove("roomAliases").putBoolean("roomAliasesV2", true).putBoolean("roomsV3", true).apply()
    }

    /**
     * 1.108 은 옮길 때 예전에 잘못 짐작한 짝(예: 1:1 방 → 그 사람이 든 단체방)까지 넘겼음.
     * 그때 생긴 대화들은 짝을 자기 이름 하나로 되돌림. 한 번만 함.
     */
    private fun repair(c: Context) {
        val sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("roomsV4", false)) return
        write(c, read(c).map { e -> e.copy(titles = setOf(RoomMatch.norm(e.name)).filter { it.isNotEmpty() }.toSet()) })
        sp.edit().putBoolean("roomsV4", true).apply()
    }

    /**
     * 1.139 전에는 '찾기'를 누른 방 이름이 시간이 지나 내보낸 다른 방에 묶일 수 있었음.
     * 저장된 대화 이름과 맞지 않는 화면 이름 짝은 지움. 한 번만 함.
     */
    private fun repairV5(c: Context) {
        val sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("roomsV5", false)) return
        write(c, read(c).map { e ->
            e.copy(titles = (e.titles.filter { RoomMatch.sameRoomName(it, e.name) } + RoomMatch.norm(e.name)).filter { it.isNotEmpty() }.toSet())
        })
        sp.edit().putBoolean("roomsV5", true).apply()
    }

    /** 1.172 전에 저장한 대화에는 사람 목록이 없음 → 한 번 읽어 채움 */
    private fun fillSenders(c: Context) {
        val sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("roomsV6", false)) return
        write(c, read(c).map { e ->
            if (e.senders.isNotEmpty()) e
            else e.copy(senders = MyStyle.senders(ChatStore.chatText(c, e.id).orEmpty()).take(MAX_SENDERS).toSet())
        })
        sp.edit().putBoolean("roomsV6", true).apply()
    }
}
