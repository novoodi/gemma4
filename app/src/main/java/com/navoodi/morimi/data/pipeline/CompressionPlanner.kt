package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.model.Message

/**
 * 증분 압축 스케줄 — 무엇을, 언제 압축할지 정한다. 순수 Kotlin(JVM 단위 테스트 가능).
 * (DEFECT_TEST_2026-10 S3 동시 입력: "입력하는 동안 다른 사람 데이터가 들어와도 누락·덮어쓰기 없이")
 *
 * **예전 방식의 누락 경로**
 *  1. 내가 보낸 순간 전체 메시지 수가 정확히 10의 배수일 때만 압축했다 — 다른 사람 메시지가 끼어
 *     배수를 건너뛰면 한참 동안 압축이 안 됐다
 *  2. 그때 최근 15개만 넘겼다 — 사이에 15개를 넘게 쌓인 메시지는 영영 압축되지 않았다
 *  3. "어디까지 압축했나"를 시각 하나로만 셌다면, 늦게 도착한 옛 시각 메시지가 빠졌을 것이다
 *
 * **지금 방식**: 압축한 메시지를 **문서 id 집합**으로 기억한다. 아직 압축 안 된 메시지가
 * [TRIGGER_COUNT]개 이상이면 그 전부를 정본 순서로 [WINDOW_SIZE]씩 나눠 압축한다.
 * - 압축 도중 도착한 메시지는 이번 묶음에 없으니 id 집합에도 안 들어가고 → 다음 회차에 잡힌다
 * - 늦게 도착한 옛 메시지도 id가 새로우니 잡힌다(시각 워터마크였다면 빠졌을 것)
 * - 병합은 합집합이라 같은 메시지를 두 번 압축해도 덮어쓰기가 없다(중복 제거)
 * - 한 번에 따라잡는 양은 [MAX_CATCHUP_WINDOWS]개 창으로 묶는다(Gemma 호출 상한). 넘친 옛 메시지는
 *   압축하지 않고 처리됨으로 표시한다 — 오래 비운 방에 들어왔을 때 수십 번 호출하지 않기 위함
 */
object CompressionPlanner {

    const val TRIGGER_COUNT = 10
    const val WINDOW_SIZE = 15
    /** 창마다 앞에 붙이는 이미 압축된 메시지 수 — "나도" 같은 앞 말 맥락용 */
    const val CONTEXT_OVERLAP = 2
    const val MAX_CATCHUP_WINDOWS = 3

    data class Plan(
        /** Gemma에 넘길 묶음들(정본 순서, 맥락 겹침 포함) */
        val batches: List<List<Message>>,
        /** 이번 회차가 끝나면 압축된 것으로 표시할 id — 상한으로 건너뛴 옛 메시지 포함 */
        val coveredIds: Set<String>,
        /** 상한 때문에 압축하지 않고 넘긴 메시지 수 */
        val skipped: Int,
    )

    /** 아직 압축되지 않은 메시지(정본 순서) */
    fun pending(messages: List<Message>, compressedIds: Set<String>): List<Message> =
        MessageOrder.canonical(messages).filter { it.id !in compressedIds }

    /**
     * 압축할 때가 됐으면 계획을, 아니면 null. [force]면 개수와 무관하게(추천 직전 등) 남은 것을 압축한다.
     */
    fun plan(messages: List<Message>, compressedIds: Set<String>, force: Boolean = false): Plan? {
        val ordered = MessageOrder.canonical(messages)
        val pending = ordered.filter { it.id !in compressedIds }
        if (pending.isEmpty() || (!force && pending.size < TRIGGER_COUNT)) return null

        val windows = pending.chunked(WINDOW_SIZE)
        val kept = windows.takeLast(MAX_CATCHUP_WINDOWS)
        val position = ordered.withIndex().associate { it.value.id to it.index }
        val batches = kept.map { window ->
            val first = position.getValue(window.first().id)
            val context = ordered.subList(maxOf(0, first - CONTEXT_OVERLAP), first)
                .filter { it.id !in window.map { w -> w.id } }
            context + window
        }
        return Plan(
            batches = batches,
            coveredIds = pending.map { it.id }.toSet(),
            skipped = windows.dropLast(MAX_CATCHUP_WINDOWS).sumOf { it.size },
        )
    }

    /**
     * 화면을 처음 열 때 이미 압축된 것으로 볼 메시지 — 프로필이 마지막으로 저장된 시각 이전 메시지.
     * 프로필이 없으면 아무것도 압축되지 않은 상태로 시작한다(상한 안에서 최근 것부터 따라잡는다).
     */
    fun initiallyCompressed(messages: List<Message>, profileUpdatedAt: Long?): Set<String> {
        if (profileUpdatedAt == null || profileUpdatedAt <= 0L) return emptySet()
        return messages.filter { !it.pending && it.timestamp in 1..profileUpdatedAt }.map { it.id }.toSet()
    }
}
