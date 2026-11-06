package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RoomMatchTest {
    private val saved = listOf("신현주 테클라", "청년부", "가족방")

    @Test
    fun matchesDespiteSpacingAndCounts() {
        assertEquals("신현주 테클라", RoomMatch.find("신현주테클라", saved, emptyMap()))
        assertEquals("청년부", RoomMatch.find("청년부 12", saved, emptyMap()))
        assertEquals("가족방", RoomMatch.find("우리 가족방", saved, emptyMap()))
        assertNull(RoomMatch.find("성가대", saved, emptyMap()))
        assertNull(RoomMatch.find(null, saved, emptyMap()))
    }

    @Test
    fun remembersExportedRoom() {
        val aliases = mapOf(RoomMatch.norm("💕 우리 엄마") to "내보낸 대화")
        assertEquals("내보낸 대화", RoomMatch.find("💕 우리 엄마", saved + "내보낸 대화", aliases))
    }

    @Test
    fun recognizesRoomByMessagesOnScreen() {
        val chats = mapOf(
            "청년부" to "2024. 3. 5. 오후 3:10, 김미카엘 : 모임 토요일 저녁에 할까요?\n2024. 3. 5. 오후 3:12, 윤호 : 좋아요 토요일로 해요",
            "가족방" to "2024. 3. 5. 오후 3:10, 엄마 : 밥 잘 챙겨 먹어라\n2024. 3. 5. 오후 3:12, 윤호 : 네 엄마 잘 먹고 있어요",
        )
        val screen = listOf("청년부 12", "김미카엘", "모임 토요일 저녁에 할까요?", "오후 3:10", "나: 좋아요 토요일로 해요", "새로 온 메시지입니다")
        assertEquals("청년부", RoomMatch.byContent(screen, chats))
        assertNull(RoomMatch.byContent(listOf("전혀 다른 대화 내용이에요"), chats))
    }

    @Test
    fun doesNotGuessAnotherRoomFromNamesAndDates() {
        // 1:1 방 '김은미 로사'를 보는데, 그 사람이 들어 있는 단체방 대화가 저장돼 있는 경우
        val chats = mapOf(
            "상임위원회" to "2026년 11월 5일 수요일\n2026. 11. 5. 오전 9:00, 김은미 로사 : 회의 자료 올렸어요\n2026. 11. 5. 오전 9:01, 윤호 : 확인했어요\n김은미 로사님이 들어왔습니다.",
        )
        val screen = listOf("김은미 로사", "2026년 11월 5일 수요일", "김은미 로사님이 들어왔습니다.", "로사님 안녕하세요 반가워요", "나: 네 신부님 잘 부탁드려요")
        assertNull(RoomMatch.byContent(screen, chats, names = listOf("김은미 로사")))
        // 이름을 넘기지 않아도 날짜·알림 줄과 '절반 이상' 기준 때문에 고르지 않음
        assertNull(RoomMatch.byContent(screen, chats))
    }

    private val chats = mapOf(
        "r_a" to "상임위원회 카카오톡 대화\n2026. 11. 1. 오전 9:00, 김은미 로사 : 회의 자료 올렸어요\n2026. 11. 1. 오전 9:01, 윤호 : 확인했어요",
        "r_b" to "김은미 로사 님과 카카오톡 대화\n2026. 11. 5. 오전 10:00, 윤호 : 로사님 처음 인사드려요\n2026. 11. 5. 오전 10:02, 김은미 로사 : 반가워요 신부님",
        "r_c" to "가족 카카오톡 대화\n2026. 1. 1. 오전 8:00, 엄마 : 새해 복 많이 받아라",
        "r_d" to "가족 카카오톡 대화\n2026. 2. 1. 오전 8:00, 형 : 처가 식구들 단톡방이야",
    )
    private val entries = listOf(
        RoomMatch.Entry("r_a", "상임위원회", setOf(RoomMatch.norm("상임위원회 15"))),
        RoomMatch.Entry("r_b", "김은미 로사", setOf(RoomMatch.norm("김은미 로사"))),
        RoomMatch.Entry("r_c", "가족", setOf(RoomMatch.norm("가족"))),
        RoomMatch.Entry("r_d", "가족", setOf(RoomMatch.norm("가족"))),
    )

    @Test
    fun findsSavedChatOfThisRoomOnly() {
        assertEquals("r_b", RoomMatch.lookup("김은미 로사", entries, listOf("김은미 로사"), chats::getValue))
        assertEquals("r_a", RoomMatch.lookup("상임위원회 15", entries, emptyList(), chats::getValue))
        // 저장 안 한 방: 비슷한 이름이 있어도 고르지 않음
        assertNull(RoomMatch.lookup("로사", entries, emptyList(), chats::getValue))
        assertNull(RoomMatch.lookup("상임위원회 임원", entries, emptyList(), chats::getValue))
    }

    @Test
    fun findsByExportedFileNameEvenIfScreenTitleWasNotBound() {
        // 찾기는 다른 방에서 눌렀고, 시간이 지나 이 방을 내보내 화면 이름이 엉뚱하게 묶였던 경우
        val e = listOf(RoomMatch.Entry("r_t", "봉담성당 주일학교 교사회", setOf(RoomMatch.norm("김은미 로사"))))
        assertEquals("r_t", RoomMatch.lookup("봉담성당 주일학교 교사회 11", e, emptyList()) { "" })
    }

    @Test
    fun sameRoomNameChecksScreenAgainstFile() {
        assertTrue(RoomMatch.sameRoomName("봉담성당 주일학교 교사회 11", "봉담성당 주일학교 교사회"))
        assertTrue(RoomMatch.sameRoomName("김은미 로사", "김은미 로사 님과 카카오톡 대화"))
        assertFalse(RoomMatch.sameRoomName("김은미 로사", "2023 상임위원회"))
        assertFalse(RoomMatch.sameRoomName(null, "가족"))
    }

    @Test
    fun sameNamedRoomsAreToldApartByMessages() {
        val screen = listOf("가족", "형", "처가 식구들 단톡방이야", "나: 알겠어 형 고마워")
        assertEquals("r_d", RoomMatch.lookup("가족", entries, screen, chats::getValue))
        assertNull(RoomMatch.lookup("가족", entries, listOf("가족"), chats::getValue))
    }

    @Test
    fun reExportOfSameRoomReusesItsNumber() {
        val again = chats.getValue("r_b") + "\n2026. 11. 6. 오전 9:00, 윤호 : 내일 봬요"
        assertEquals("r_b", RoomMatch.reuseTarget("김은미 로사", "김은미 로사", again, entries, chats::getValue))
        assertEquals("r_b", RoomMatch.reuseTarget(null, "김은미 로사", again, entries, chats::getValue))
        // 이름만 같은 다른 '가족' 방은 새 번호
        val other = "가족 카카오톡 대화\n2026. 3. 1. 오전 8:00, 동생 : 우리 셋만 있는 방"
        assertNull(RoomMatch.reuseTarget(null, "가족", other, entries, chats::getValue))
        // 처음 내보내는 방은 새 번호
        assertNull(RoomMatch.reuseTarget("성가대", "성가대", "성가대 카카오톡 대화", entries, chats::getValue))
    }
}
