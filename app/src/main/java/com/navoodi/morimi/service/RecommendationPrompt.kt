package com.navoodi.morimi.service

import java.time.LocalDate

/** 검색어·후기·재시도 피드백까지 조립한 뒤 한 번 더 마스킹하는 클라우드 요청 경계. */
internal object RecommendationPrompt {
    fun build(
        sieved: SievedPrompt,
        chatDate: LocalDate,
        ragContext: String,
        knownNames: List<String>,
        feedback: String = "",
    ): String {
        val ragSection = if (ragContext.isNotBlank())
            "\n[과거 모임 후기 — 온디바이스 시맨틱 검색 회수분]\n$ragContext\n" else ""
        val resolvedDate = sieved.slot(FrameSlot.WHEN)
        val dateRule = if (resolvedDate != SievedPrompt.UNSPECIFIED)
            "모임 날짜는 이미 $resolvedDate 로 확정돼 있다. 다시 계산하지 말고 이 날짜를 YYYY-MM-DD 형식 그대로 사용하라."
        else
            "모임 날짜가 확정되지 않았다. 대화 날짜($chatDate) 기준으로 추정하되, 추정임을 요약에 밝혀라."

        val prompt = """
당신은 모임 조율 어시스턴트입니다. 아래 [정형화 요청 블록]은 사용자 기기에서 개인정보를 제거하고
슬롯 단위로 정규화한 요청서입니다. 이 블록에 적힌 값만을 사실로 삼으세요.

[대화 날짜] $chatDate

${sieved.frame}
$ragSection
[지침]
1. $dateRule
2. getWeather 도구로 모임 날짜와 지역의 날씨를 반드시 조회하세요.
3. searchPlace 도구로 후보 장소를 검색하세요. 검색어는 "${sieved.placeQuery}" 를 기준으로 삼되 필요에 따라 구체화하세요.
4. 장소 2~3곳, 활동 2~3가지, 챙겨갈 것 3~5가지를 추천하세요.
5. [판단 기준 가중치]의 순서를 그대로 따르세요. 1순위 기준을 만족하지 못하는 후보는 다른 기준이 좋아도 넣지 마세요.
6. "제약(배제 조건)"에 적힌 요소는 어떤 이유로도 추천에 포함하지 마세요. 배제했다는 서술도 쓰지 마세요.
7. "미확정 슬롯"에 있는 항목은 임의로 지어내지 마세요.
8. 장소는 "이름 — 이유" 형식으로 쓰고, 이유에는 이 상황(${sieved.context.label})에 왜 맞는지를 한 문장으로 적으세요.
9. 반드시 제공된 JSON 스키마에 맞춰 응답하세요.
""".trimIndent() + if (feedback.isBlank()) "" else
            "\n\n[시스템 검증 피드백 — 반드시 반영할 것]\n$feedback"

        return PiiScrubber.scrub(prompt, knownNames).text
    }
}
