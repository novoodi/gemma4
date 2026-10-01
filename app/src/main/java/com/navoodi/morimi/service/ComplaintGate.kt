package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.repository.FeedbackEntry

/**
 * 불만 후기 게이트 — 사용자가 불만을 남긴 장소를 추천 결과에서 **결정론적으로** 뺀다. 순수 Kotlin.
 * (DEFECT_TEST_2026-10 S5-2·S5-3: 불만이 프롬프트 텍스트로만 전달돼 모델이 같은 곳을 다시 추천)
 *
 * 시나리오: 추천 → 결정(캘린더에 담기) → 한 명이 "별로였다" → 다음 추천에서 그 장소가 빠져야 한다.
 *  - **불만**: 별점 1~2점 후기(3점은 중립, 0점은 미평가 — PastImpression과 같은 기준)
 *  - **불만 대상**: 후기를 쓸 때 기록한 대상 장소([FeedbackEntry.targetPlaces] — 결정한 장소, [feedbackTargets])
 *    + 후기 본문에 이름이 나온 장소([PlaceMatcher.mentionedIn])
 *  - **같은 곳 판정**은 [PlaceMatcher] 규칙(공백·기호·지점명·앞 지역 토큰 무시) — 같은 상호의 다른 지점도 뺀다
 *  - **비슷한 곳**(같은 업종·분위기)은 빼지 않고 랭킹에서 감점한다([PlaceRanker.pastSatisfaction])
 *  - **나간 사람의 불만은 반영하지 않는다** — 작성자([FeedbackEntry.authorUid])가 현재 멤버가 아니면 무시
 *    (작성자 미기록인 예전 후기는 이 기기 사용자의 것으로 본다)
 */
object ComplaintGate {

    const val MAX_RATING = 2

    data class Complaint(val text: String, val targets: List<String>, val rating: Int)

    /** 이 후기를 지금 반영해도 되는가 — 작성자가 현재 멤버(또는 미기록·멤버 정보 없음)일 때만 */
    fun authorIsMember(entry: FeedbackEntry, memberIds: Set<String>?): Boolean =
        memberIds == null || entry.authorUid.isBlank() || entry.authorUid in memberIds

    fun complaintsOf(entries: List<FeedbackEntry>, memberIds: Set<String>?): List<Complaint> =
        entries
            .filter { it.rating in 1..MAX_RATING && authorIsMember(it, memberIds) }
            .distinctBy { Triple(it.feedback, it.date, it.roomId) }
            .map { Complaint(it.feedback, it.targetPlaces.filter { t -> t.isNotBlank() }, it.rating) }

    /** 이 장소가 불만 대상인가 — 기록된 대상과 같은 가게이거나, 후기 본문이 이 장소를 언급 */
    fun isComplained(placeName: String, c: Complaint): Boolean =
        c.targets.any { PlaceMatcher.nameMatches(placeName, it) } || PlaceMatcher.mentionedIn(c.text, placeName)

    data class Result(val kept: List<RecommendedPlace>, val blocked: List<RecommendedPlace>)

    fun apply(places: List<RecommendedPlace>, complaints: List<Complaint>): Result {
        if (complaints.isEmpty()) return Result(places, emptyList())
        val (blocked, kept) = places.partition { p -> complaints.any { isComplained(p.name, it) } }
        return Result(kept, blocked)
    }

    /** 모델에 미리 알려 줄 피할 장소 목록(기록된 대상) — 결과 게이트와 별개의 1차 방어 */
    fun avoidList(complaints: List<Complaint>): List<String> =
        complaints.flatMap { it.targets }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * 후기를 저장할 때 기록할 대상 장소 — 결정(캘린더에 담은)한 장소.
     * "어디가 별로였는지" 본문에 안 써도(별점만 남겨도) 다음 추천에서 그 장소를 뺄 수 있게 한다.
     * 결정이 없으면 추천이 한 곳뿐일 때만 그곳을 대상으로 본다 — 여러 곳 중 어디였는지 모르는데
     * 전부 빼면 가지도 않은 곳까지 막힌다. 그 경우는 본문에 이름이 나온 장소만 뺀다(언급 판정).
     */
    fun feedbackTargets(decided: List<String>, recommended: List<String>): List<String> {
        val d = decided.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (d.isNotEmpty()) return d
        val r = recommended.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (r.size == 1) r else emptyList()
    }

    fun feedbackFor(blocked: List<RecommendedPlace>): String =
        "다음 장소는 사용자가 불만을 남긴 곳이라 제외했습니다: ${blocked.joinToString(", ") { it.name }}. " +
            "이 장소와 같은 상호의 다른 지점은 추천하지 말고, 다른 장소로 다시 추천하세요."
}
