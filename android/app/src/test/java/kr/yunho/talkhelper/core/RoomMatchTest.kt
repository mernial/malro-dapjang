package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
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
}
