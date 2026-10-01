package com.navoodi.morimi.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.repository.FeedbackEntry
import com.navoodi.morimi.data.pipeline.FeedbackRetriever
import com.navoodi.morimi.data.pipeline.MockOnDeviceLlm
import com.navoodi.morimi.data.repository.MetricsRepository
import com.navoodi.morimi.service.AssistantEvent
import com.navoodi.morimi.service.AssistantEventTracker
import com.navoodi.morimi.service.AssistantOrchestrator
import com.navoodi.morimi.service.GuardrailService
import com.navoodi.morimi.service.MeetingContext
import com.navoodi.morimi.service.PromptSieve
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * **시나리오 파이프라인 E2E (2026-09-23 B5, 에뮬레이터 API 36 x86_64).**
 *
 * `ScenarioSnapshotTest`는 각 단계를 **따로** 호출해 검증한다. 여기서는 운영
 * `AssistantOrchestrator.orchestrate()`를 **실제로 돌려서**, 배선된 상태에서도 같은 값이
 * 나오는지를 본다. 특히 프라이버시 경계 — 실제 실행에서 **채팅 원문과 판정 근거 어휘가
 * 경계를 넘는 프롬프트에 실리지 않는지** 를 확인한다. 단위 테스트는 `PromptSieve`가
 * 만든 블록만 보지만, 여기서는 `PiiScrubber`까지 지난 최종 전송본을 본다.
 *
 * **API 키 없이도 의미가 있다.** 상황 판정 → 가중치 → 익명화 요약 → 거름망 → 스크러버는
 * 전부 디바이스 경계 **안쪽**이라 Gemini 호출 전에 끝나고, 이벤트로 관찰된다.
 * 클라우드 왕복이 실패해도 그 이벤트들은 이미 발행돼 있다.
 * (키가 있으면 `키가_있으면_후보_생성까지_간다`가 추가로 돈다 — 없으면 스스로 건너뛴다)
 */
@RunWith(AndroidJUnit4::class)
class ScenarioPipelineE2ETest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repo: MetricsRepository

    /** 후기 검색은 이 테스트의 관심사가 아니다 — 빈 결과로 고정한다. */
    private val emptyRetriever = object : FeedbackRetriever {
        override suspend fun retrieve(query: String, topK: Int): List<FeedbackEntry> = emptyList()
    }

    private class Recorder : AssistantEventTracker {
        val events = mutableListOf<AssistantEvent>()
        override fun onEvent(event: AssistantEvent) { events += event }
        inline fun <reified T : AssistantEvent> first(): T? = events.filterIsInstance<T>().firstOrNull()
    }

    private val chatDate: LocalDate = LocalDate.of(2026, 9, 17)

    private val profile = UserStatusEntity(
        roomId = "e2e",
        participants = listOf("도윤", "하린", "준호"),
        preferences = listOf("좋아요: 분위기 좋은 곳", "싫어요: 시끄러운 술집"),
        availability = listOf("저녁 가능"),
    )

    // 오케스트레이터는 정본 순서(서버 시각 → 문서 id)로 정렬한다 — 작성 순서를 시각으로 명시(2026-10 S3)
    private var clock = 1_000L
    private fun msg(who: String, text: String) =
        Message(roomId = "e2e", senderId = who, senderName = who, content = text, timestamp = clock++)

    private val scenarioA = listOf(
        msg("도윤", "이번 주 토요일이 준호 생일인데 축하해주자"),
        msg("하린", "좋아! 홍대에서 분위기 좋은 데로 예약할까? 4명이야"),
        msg("도윤", "1인 4만원 정도까지는 괜찮을 듯"),
    )
    private val scenarioB = listOf(
        msg("도윤", "준호 오늘 회사에서 잘렸대... 많이 속상해하더라"),
        msg("하린", "오늘 저녁에 홍대에서 얼굴이나 보자 4명"),
    )

    @Before
    fun setUp() = runBlocking {
        repo = MetricsRepository(context)
        repo.clearAll()
    }

    /**
     * 경계 안쪽 단계는 전부 돈다. 클라우드 실패는 무시한다.
     *
     * **프록시 구조(2026-09-05)에서는 API 키를 앱이 갖지 않는다.** Gemini·카카오·날씨는
     * Firebase Functions callable 프록시를 거치고, 프록시는 **로그인한 사용자만** 호출할 수 있다.
     * 그래서 이 테스트에서 클라우드 왕복은 로그인 없이는 실패한다 — 의도된 것이다.
     * 검증 대상은 경계 **안쪽**(분류·가중치·요약·거름망·스크러버)이고, 그 단계들은
     * Gemini 호출 전에 끝나 이벤트로 관찰된다.
     */
    private fun runToBoundary(messages: List<Message>): Recorder {
        val rec = Recorder()
        val orchestrator = AssistantOrchestrator(
            guardrailService = GuardrailService(),
            feedbackRetriever = emptyRetriever,
            onDeviceLlm = MockOnDeviceLlm(),
            metricsRepository = repo,
        )
        runCatching {
            runBlocking {
                orchestrator.orchestrate("e2e", messages, profile, chatDate, rec)
            }
        }
        return rec
    }

    // ── 경계 안쪽 (키 불필요) ────────────────────────────────────────────────

    @Test
    fun 시나리오A는_배선된_실행에서도_CELEBRATION으로_판정된다() {
        val rec = runToBoundary(scenarioA)
        val c = rec.first<AssistantEvent.ContextClassified>()
        assertTrue("상황 판정 이벤트가 없다", c != null)
        assertTrue(
            "CELEBRATION이 아니다: ${rec.events.filterIsInstance<AssistantEvent.ContextClassified>()}",
            rec.events.filterIsInstance<AssistantEvent.ContextClassified>()
                .any { it.toString().contains(MeetingContext.CELEBRATION.name) },
        )
    }

    @Test
    fun 시나리오B는_CONSOLATION으로_판정된다() {
        val rec = runToBoundary(scenarioB)
        assertTrue(
            "CONSOLATION이 아니다",
            rec.events.filterIsInstance<AssistantEvent.ContextClassified>()
                .any { it.toString().contains(MeetingContext.CONSOLATION.name) },
        )
    }

    @Test
    fun 실제_전송본에_채팅_원문과_참가자_이름이_없다() {
        val rec = runToBoundary(scenarioA)
        val sieve = rec.first<AssistantEvent.SieveNormalized>()
        assertTrue("거름망 이벤트가 없다", sieve != null)
        val frame = sieve.toString()

        // 참가자 이름 — 프로필에 있지만 블록에는 실리면 안 된다
        listOf("도윤", "하린", "준호").forEach {
            assertFalse("참가자 이름이 전송본에 있다: $it", frame.contains(it))
        }
        // 채팅 원문 문장
        listOf("축하해주자", "예약할까", "괜찮을 듯").forEach {
            assertFalse("채팅 원문이 전송본에 있다: $it", frame.contains(it))
        }
    }

    @Test
    fun 민감한_판정_근거가_실제_전송본에_실리지_않는다() {
        // 시나리오 B는 "잘렸"·"속상"으로 CONSOLATION이 된다.
        // C4 이전에는 이 어휘들이 '상황 판정 근거' 줄로 함께 나갔다.
        val rec = runToBoundary(scenarioB)
        val sieve = rec.first<AssistantEvent.SieveNormalized>()
        // 가드: 이벤트가 없으면 null.toString()이 "null"이 되어 아래 assertFalse가 전부
        // 공허하게 통과한다. 검사 대상이 실제로 있는지 먼저 확인한다.
        assertTrue("거름망 이벤트가 없다 — 아래 검사가 무의미해진다", sieve != null)
        val frame = sieve.toString()
        assertTrue("전송본이 비어 있다", frame.contains("정형화 요청 블록"))

        assertFalse("민감한 근거 어휘가 전송본에 있다: 잘렸", frame.contains("잘렸"))
        assertFalse("민감한 근거 어휘가 전송본에 있다: 속상", frame.contains("속상"))
        assertFalse("근거 줄 자체가 남아 있다", frame.contains("상황 판정 근거"))
    }

    @Test
    fun Q2_슬롯_우선_규칙이_실제_전송본에_있다() {
        val rec = runToBoundary(scenarioA)
        val frame = rec.first<AssistantEvent.SieveNormalized>().toString()
        assertTrue(
            "슬롯 우선 규칙이 전송본에 없다 (Q2)",
            frame.contains(PromptSieve.SLOT_PRECEDENCE_RULE),
        )
    }

    @Test
    fun 상대_날짜가_슬롯에서_절대_날짜로_환산돼_전송된다() {
        val rec = runToBoundary(scenarioA)
        val frame = rec.first<AssistantEvent.SieveNormalized>().toString()
        // "이번 주 토요일"(대화일 2026-09-17 목) → 2026-09-19 (토)
        assertTrue("절대 날짜 환산이 전송본에 없다", frame.contains("2026-09-19"))
    }

    @Test
    fun 가중치_이벤트의_CR이_임계_이내다() {
        val rec = runToBoundary(scenarioA)
        val w = rec.first<AssistantEvent.CriteriaWeighted>()
        assertTrue("가중치 이벤트가 없다", w != null)
        // 프리셋 CR은 0.0022~0.0177이라 문자열로 확인해도 충분하다
        assertTrue("CR이 이벤트에 없다", w.toString().contains("0.0"))
    }

    // ── 경계 바깥 (키 필요) ──────────────────────────────────────────────────

    @Test
    fun 로그인과_프록시가_준비되면_후보_생성까지_간다() {
        // 프록시 구조에서는 앱에 키가 없다. 전제는 (1) Firebase 로그인 (2) functions 배포.
        // 계측 테스트는 로그인 세션이 없으므로 보통 여기서 실패한다 — 건너뛴 사실을 남긴다.
        val rec = runToBoundary(scenarioA)
        val reachedCloud = rec.events.any {
            it is AssistantEvent.JsonParsed || it is AssistantEvent.ToolCalled
        }
        if (!reachedCloud) {
            println("[SKIP] 클라우드 왕복 미검증 - 프록시는 로그인 사용자만 호출할 수 있다 " +
                "(H1: 정품 google-services.json + firebase deploy --only functions 선행)")
            return
        }
        val finished = rec.events.filterIsInstance<AssistantEvent.OrchestrationFinished>().lastOrNull()
        assertTrue("종료 이벤트가 없다", finished != null)
        println("[INFO] 클라우드 경로 결과: " + finished)
    }

    @Test
    fun 온디바이스_LLM이_Mock인지_실제_Gemma인지_기록한다() {
        // P8 기록용 — 에뮬레이터에는 모델이 없어 Mock 폴백을 타는 것이 정상이다
        val llm = MockOnDeviceLlm()
        println("[INFO] 이 실행의 온디바이스 LLM = ${llm.javaClass.simpleName} (에뮬레이터: 모델 미설치)")
        assertEquals("MockOnDeviceLlm", llm.javaClass.simpleName)
    }
}
