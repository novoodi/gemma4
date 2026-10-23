package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.model.Message

/**
 * 온디바이스 요약(Gemma) 입력을 컨텍스트 한도 안으로 줄인다 — 순수 Kotlin(JVM 단위 테스트 가능).
 * (DEFECT_TEST_2026-10 S4-7: 2000건·약 3.5만 자를 자르지 않고 그대로 넘김)
 *
 * Gemma E2B 컨텍스트는 약 8k 토큰이고 요약 프롬프트 지시문도 들어가므로, 대화 본문은 [MAX_CHARS]자까지만 넣는다.
 *  - **앞부분**([HEAD_CHARS]자까지): 모임 목적이 나오는 첫 메시지들
 *  - **최근 부분**(나머지 예산): 최종 합의가 있는 마지막 메시지들
 *  - 그 사이를 잘랐으면 [GAP_MARKER] 한 줄을 넣어 모델이 중간이 빠졌음을 알게 한다
 * 이 창은 **요약 입력에만** 쓴다. 날짜·지역·인원 추출(PromptSieve)과 상황 분류는 대화 전체를 본다.
 *
 * 글자 수는 LlmService.summarizeForPrivacy가 넘기는 형태(본문을 줄바꿈으로 이어 붙임) 기준이다.
 * 실기기에서 이 예산이 메모리·속도에 맞는지는 아직 확인하지 않았다(DEFECT 문서 S4-7 참고).
 */
object SummaryInputWindow {

    const val MAX_CHARS = 6_000
    const val HEAD_CHARS = 1_200
    const val GAP_MARKER = "…중략…"
    const val GAP_ID = "__summary_gap__"

    data class Window(
        val messages: List<Message>,
        val originalChars: Int,
        val keptChars: Int,
        val truncated: Boolean,
        val headCount: Int = 0,
        val tailCount: Int = 0,
    )

    private fun cost(m: Message) = m.content.length + 1 // 줄바꿈 포함

    /** [ordered]는 정본 순서(오래된 → 최근)여야 한다. */
    fun fit(ordered: List<Message>, maxChars: Int = MAX_CHARS, headChars: Int = HEAD_CHARS): Window {
        val total = ordered.sumOf { cost(it) }
        if (total <= maxChars) return Window(ordered, total, total, truncated = false)

        // 앞부분 — 예산 안에 통째로 들어가는 메시지만(한 건이 넘치면 앞부분만 잘라 넣는다)
        val head = ArrayList<Message>()
        var used = 0
        for (m in ordered) {
            val left = headChars - used
            if (left <= 1) break
            if (cost(m) <= left) { head += m; used += cost(m) }
            else { head += m.copy(content = m.content.take(left - 1)); used += left; break }
        }
        val markerCost = GAP_MARKER.length + 1
        // 최근 부분 — 뒤에서부터 남은 예산만큼(가장 최근 한 건이 넘치면 그 끝부분만)
        val tail = ArrayList<Message>()
        var tailUsed = 0
        val tailBudget = maxChars - used - markerCost
        for (i in ordered.indices.reversed()) {
            if (i < head.size) break // 앞부분과 겹치면 멈춘다
            val m = ordered[i]
            val left = tailBudget - tailUsed
            if (left <= 1) break
            if (cost(m) <= left) { tail += m; tailUsed += cost(m) }
            else { tail += m.copy(content = m.content.takeLast(left - 1)); tailUsed += left; break }
        }
        tail.reverse()
        val gapTime = head.lastOrNull()?.timestamp ?: 0L
        val marker = Message(id = GAP_ID, roomId = ordered.first().roomId, senderId = "", senderName = "",
            content = GAP_MARKER, timestamp = gapTime)
        val kept = head + marker + tail
        return Window(kept, total, used + markerCost + tailUsed, truncated = true, headCount = head.size, tailCount = tail.size)
    }
}
