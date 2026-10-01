package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.model.Message

/**
 * 대화의 **정본 순서** — 순수 Kotlin(JVM 단위 테스트 가능).
 * (DEFECT_TEST_2026-10 S3: 순서가 섞이면 이미 철회된 "홍대·토요일"이 전송됨)
 *
 * "마지막 언급이 이긴다"(PromptSieve.lastMention)·맥락 인용·증분 압축은 전부 순서에 기대는데,
 * 그동안 아무도 정렬하지 않고 받은 리스트 순서를 믿었다. 이제 기기 안의 모든 단계가 이 순서를 쓴다.
 *
 * 정렬 기준:
 *  1. **서버 시각**(`timestamp`) — 메시지는 `FieldValue.serverTimestamp()`로 저장된다(ChatRepository).
 *     기기 시계가 틀려도 순서가 흔들리지 않는다
 *  2. 같은 시각이면 **문서 id** — Firestore `orderBy("timestamp")`도 동률을 문서 id로 가른다.
 *     화면에 보이는 순서와 같고, 입력 리스트 순서와 무관하게 늘 같은 결과가 나온다
 *  3. 시각이 없거나 이상한 메시지(0 이하 — 필드 누락·형식 오류 시 millisField가 0을 준다)는
 *     **가장 오래된 것**으로 본다. 언제 말했는지 모르는 말이 최신 결정을 덮어쓰지 못하게 한다
 *  4. 아직 서버에 확정되지 않은 내 메시지([Message.pending] — 시각이 기기 시계 추정치)는 **맨 뒤**.
 *     방금 보낸 말이 가장 최신 의도이고, 기기 시계가 늦어도 앞쪽으로 밀리지 않는다
 */
object MessageOrder {

    private val COMPARATOR: Comparator<Message> =
        compareBy<Message>(
            { when { it.pending -> 2; it.timestamp <= 0L -> 0; else -> 1 } },
            { if (it.timestamp <= 0L) 0L else it.timestamp },
            { it.id },
        )

    fun canonical(messages: List<Message>): List<Message> = messages.sortedWith(COMPARATOR)

    /** a가 b보다 앞인가(정본 순서 기준) */
    fun before(a: Message, b: Message): Boolean = COMPARATOR.compare(a, b) < 0
}
