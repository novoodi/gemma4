package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message

/**
 * 현재 방 멤버 기준으로 대화·프로필의 범위를 정한다 — 순수 Kotlin(JVM 단위 테스트 가능).
 * (DEFECT_TEST_2026-10 S2-1~S2-6: 나간 사람이 인원·선호·불호·지역·재시도에 계속 반영됨)
 *
 * 멤버 판정은 **senderId(uid)** 로 한다. 이름으로 하면 동명이인이 섞이고, 표시 이름은 바뀔 수 있다.
 * 멤버 목록은 Firestore `rooms/{id}.participantUids` — 앱이 가진 유일한 "현재 멤버" 기록이다.
 * 입·퇴장 시각은 남지 않으므로, 다시 들어온 사람은 예전 발언까지 포함해 현재 멤버로 본다.
 *
 * **맥락 보존**: 나간 사람의 메시지는 버리되, 남은 멤버가 바로 다음에 "나도", "나도 싫어"처럼
 * 짧게 동조했다면 그 동조 메시지 안에 앞 말을 인용으로 붙인다. 동조한 사람의 선호는 살아남고,
 * 나간 사람 혼자 한 말은 선호 추출 대상에서 빠진다. 인용은 기기 안(Gemma·거름망)에서만 쓰이고,
 * 클라우드로 가는 요약은 기존대로 스크러버를 거친다.
 */
object MemberScope {

    /** 앞 말에 기대는 짧은 동조 — 이것만으로는 무엇에 동의했는지 알 수 없다 */
    private val ENDORSEMENT = Regex(
        """^\s*(?:ㅇㅇ\s*)?(?:나도|저도|나두|저두|나 도|동의|인정|ㅇㅈ|ㄹㅇ|222|마자|맞아|ㅇㄱㄹㅇ|같은 생각|나 역시)"""
    )
    private const val MAX_ENDORSEMENT_LEN = 20
    const val QUOTE_PREFIX = " (앞 사람 말: "

    /**
     * 멤버의 메시지만 남긴다(입력 순서 유지). 동조 메시지 바로 앞(시각 순)이 비멤버 발언이면 그 내용을 인용으로 붙인다.
     * [memberIds]가 null이면 멤버 정보가 없는 것 — 아무것도 거르지 않는다(이전 동작).
     */
    fun scope(messages: List<Message>, memberIds: Set<String>?): List<Message> {
        if (memberIds == null) return messages
        // "바로 앞 말"은 정본 순서(서버 시각 → 문서 id)로 찾는다 — 리스트 순서를 믿지 않는다
        val timeOrder = messages.withIndex().sortedWith { a, b ->
            when {
                MessageOrder.before(a.value, b.value) -> -1
                MessageOrder.before(b.value, a.value) -> 1
                else -> a.index.compareTo(b.index)
            }
        }
        val previousOf = HashMap<Int, Message>()
        timeOrder.zipWithNext { a, b -> previousOf[b.index] = a.value }

        return messages.mapIndexedNotNull { i, m ->
            if (m.senderId !in memberIds) return@mapIndexedNotNull null
            val prev = previousOf[i]
            if (prev != null && prev.senderId !in memberIds && isEndorsement(m.content) && prev.content.isNotBlank()) {
                m.copy(content = m.content + QUOTE_PREFIX + prev.content.trim() + ")")
            } else m
        }
    }

    internal fun isEndorsement(text: String): Boolean =
        text.trim().length <= MAX_ENDORSEMENT_LEN && ENDORSEMENT.containsMatchIn(text)

    /**
     * 이 프로필의 선호·불호를 지금 써도 되는가 — 나간 사람의 말이 섞였을 수 있으면 false.
     *
     * - 출처([UserStatusEntity.sourceMemberIds])가 기록돼 있으면: 그 멤버가 전부 아직 멤버여야 한다
     * - 출처가 없으면(v7 이전 프로필): 대화에 등장한 발화자가 전부 아직 멤버일 때만(아무도 안 나갔을 때만)
     * [memberIds]가 null이면 판단할 수 없으므로 이전 동작대로 쓴다.
     */
    fun profileUsable(status: UserStatusEntity?, memberIds: Set<String>?, historySenderIds: Set<String>): Boolean {
        if (status == null || memberIds == null) return true
        val source = status.sourceMemberIds.toSet()
        return if (source.isNotEmpty()) memberIds.containsAll(source)
        else memberIds.containsAll(historySenderIds)
    }

    /**
     * 프로필을 현재 멤버 기준으로 다시 만들어야 하는가.
     * - 쓸 수 없는 프로필(나간 사람 포함 가능)
     * - 다시 들어온 사람: 출처에 없는 현재 멤버가 예전에 말한 기록이 있다(그 발언이 빠져 있다)
     * 새로 들어와 아직 말하지 않은 멤버는 다시 만들 이유가 없다.
     */
    fun needsRebuild(status: UserStatusEntity?, memberIds: Set<String>?, historySenderIds: Set<String>): Boolean {
        if (status == null || memberIds == null) return false
        if (!profileUsable(status, memberIds, historySenderIds)) return true
        val source = status.sourceMemberIds.toSet()
        return source.isNotEmpty() && (memberIds intersect historySenderIds).any { it !in source }
    }
}
