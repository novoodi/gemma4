package com.example.gemma4.service

import android.content.Context
import android.util.Log
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.model.Message
import com.example.gemma4.data.model.Participant
import com.example.gemma4.data.repository.ProfileRepository
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LlmService(private val context: Context) {

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val NO_MARKDOWN = "별표, 샵, 대괄호 같은 특수기호 없이 일반 텍스트로만 답해줘."
        private val uselessKeywords = listOf("없음", "미확인", "모름", "불명확")
    }

    private var engine: Engine? = null
    private var activeConversation: Any? = null
    val profileRepository = ProfileRepository(context)

    val modelPath: String
        get() = "${context.getExternalFilesDir("models")?.absolutePath}/$MODEL_FILENAME"

    val isModelAvailable: Boolean
        get() = File(modelPath).exists()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        val config = EngineConfig(modelPath = modelPath, backend = Backend.GPU())
        engine = Engine(config)
        engine!!.initialize()
    }

    private fun closeConversation(conv: Any) {
        try {
            (conv as? AutoCloseable)?.close()
                ?: conv::class.java.getMethod("close").invoke(conv)
        } catch (_: Exception) {}
    }

    private fun closeActiveConversation() {
        val conv = activeConversation ?: return
        activeConversation = null
        closeConversation(conv)
    }

    suspend fun runPipeline(roomId: String, messages: List<Message>, roomParticipants: List<Participant> = emptyList()): MeetingSummary {
        val eng = checkNotNull(engine) { "엔진이 초기화되지 않았습니다" }

        closeActiveConversation()

        // 화자를 [ID: userId(이름)] 형태로 태깅
        val senderIdToParticipant = roomParticipants.associateBy { it.id }
        val transcript = messages.joinToString("\n") { msg ->
            val p = senderIdToParticipant[msg.senderId]
            val tag = if (p != null) "[ID: ${p.id}(${p.name})]" else "[${msg.senderName}]"
            "$tag: ${msg.content}"
        }
        Log.d("LlmService", "트랜스크립트 앞 3줄:\n${transcript.lines().take(3).joinToString("\n")}")
        Log.d("LlmService", "roomParticipants: ${roomParticipants.map { "${it.id}=${it.name}" }}")

        val chatDate = messages.firstOrNull()?.let {
            Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: LocalDate.now()

        // ── Conv A: Steps 1-4 (요약/장소/날짜/도시) ──────────────────────────
        // 엔진이 동시에 하나의 session만 지원하므로 각 단계를 순차적으로 열고 닫음
        val convA = eng.createConversation()
        activeConversation = convA

        val summary: String
        val location: String
        val meetingDate: String
        val city: String
        try {
            summary = withContext(Dispatchers.IO) {
                convA.sendMessage(
                    "아래 모임 대화를 읽고 핵심 내용을 3~5문장으로 정리해줘. $NO_MARKDOWN\n\n$transcript"
                ).toString()
            }
            location = withContext(Dispatchers.IO) {
                convA.sendMessage(
                    "위 대화에서 모임 장소 이름만 짧게 알려줘. $NO_MARKDOWN"
                ).toString()
            }
            meetingDate = withContext(Dispatchers.IO) {
                val raw = convA.sendMessage(
                    "대화가 이루어진 날짜는 $chatDate 입니다. " +
                    "위 대화에서 참석자들이 최종 확정한 모임 당일 날짜를 YYYY-MM-DD 형식으로만 답해줘. " +
                    "투표 마감일, 제안된 날짜, 취소된 날짜는 제외하고 최종 결정된 날짜만 추출해. " +
                    "'N일'처럼 일만 있으면 $chatDate 의 연도와 월을 그대로 사용해. " +
                    "날짜를 특정할 수 없으면 미정 이라고만 해. $NO_MARKDOWN"
                ).toString()
                Log.d("LlmService", "날짜 raw: $raw")
                Regex("\\d{4}-\\d{2}-\\d{2}").find(raw)?.value ?: "미정"
            }
            city = withContext(Dispatchers.IO) {
                val raw = convA.sendMessage(
                    "위 모임 장소가 있는 도시나 지역 이름을 한국어로만 알려줘. 예: 서울, 부산, 인천, 대구, 홍대, 강남. $NO_MARKDOWN"
                ).toString().trim()
                Log.d("LlmService", "도시 raw: $raw")
                raw
            }
        } finally {
            closeActiveConversation()  // convA 닫기 — 다음 conv 생성 전 반드시 해제
        }

        // ── Conv B: Step 5 (성향 추출 — 독립 컨텍스트) ──────────────────────
        withContext(Dispatchers.IO) {
            val convB = eng.createConversation()
            activeConversation = convB
            try {
                val participantList = roomParticipants.joinToString("\n") { "  ${it.id} = ${it.name}" }
                val raw = convB.sendMessage(
                    "아래 모임 채팅 대화를 분석해서 각 참여자의 고정된 개인 성향을 추출해줘.\n\n" +
                    "=== 대화 내용 ===\n$transcript\n\n" +
                    "참여자 ID 목록 (반드시 아래 ID만 JSON 키로 사용할 것):\n$participantList\n\n" +
                    "추출할 것 (앞으로도 계속 해당되는 개인 특성):\n" +
                    "- 음식 제약/알레르기: 예) 매운 음식 못 먹음, 해산물 알레르기\n" +
                    "- 음주 여부: 예) 술 안 마심, 음주 가능\n" +
                    "- 장소 유형 선호: 예) 조용한 곳 선호, 실내 선호\n" +
                    "- 시간대 제약: 예) 야간 불가, 주말만 가능\n\n" +
                    "절대 추출하지 말 것:\n" +
                    "- 이번 대화에서의 행동: 장소 추천, 시간 조율, 투표 참여 등\n" +
                    "- 일회성 상황: 오늘 늦음, 이번에 알바 있음 등\n" +
                    "- 추측이나 불확실한 정보\n\n" +
                    "명확한 성향이 없는 참여자는 생략해. 다른 텍스트 없이 JSON만 출력해.\n" +
                    "좋은 예: {\"user_002\": [\"술 안 마심\", \"조용한 장소 선호\"], \"user_003\": [\"매운 음식 못 먹음\"]}\n" +
                    "나쁜 예: {\"음주\": \"없음\", \"user_002\": [\"장소 추천함\", \"시간 정함\"]}"
                ).toString()
                Log.d("LlmService", "성향 raw: $raw")

                val idToParticipant = roomParticipants.associateBy { it.id }
                val nameToParticipant = roomParticipants.associateBy { it.name }
                val traitsById = parseProfileJson(raw)

                for ((key, fresh) in traitsById) {
                    val participant = resolveParticipant(key, idToParticipant, nameToParticipant)
                    if (participant == null) {
                        Log.w("LlmService", "참여자 매핑 실패: key=$key → 스킵 (참여자 ID/이름 아님)")
                        continue
                    }
                    Log.d("LlmService", "매핑 성공: $key → ${participant.name}(${participant.id})")
                    val existing = profileRepository.loadProfile(participant.id)?.traits ?: emptyList()
                    val resolved = resolveConflicts(participant.name, existing, fresh) { msg ->
                        convB.sendMessage(msg).toString()
                    }
                    profileRepository.saveProfile(participant.id, participant.name, resolved)
                    Log.d("LlmService", "저장 완료: [${participant.id}] ${participant.name} → $resolved")
                }
            } finally {
                closeActiveConversation()  // convB 닫기
            }
        }

        val profileContext = buildRecommendationContext(roomParticipants)
        Log.d("LlmService", "profileContext (${profileContext.length}자):\n$profileContext")
        val profilePrefix = if (profileContext.isNotBlank()) "참여자 성향 정보:\n$profileContext\n\n" else ""

        // ── Conv C: Steps 6-7 (활동/준비물 추천 — transcript 재주입) ─────────
        val convC = eng.createConversation()
        activeConversation = convC

        return try {
            val activities = withContext(Dispatchers.IO) {
                convC.sendMessage(
                    "아래 모임 대화를 참고해서 답해줘.\n$transcript\n\n" +
                    "${profilePrefix}위 모임에서 어떤 활동을 할 예정인지 2~3문장으로 알려줘. $NO_MARKDOWN"
                ).toString()
            }
            val whatToBring = withContext(Dispatchers.IO) {
                convC.sendMessage(
                    "${profilePrefix}위 모임에 참석할 때 챙겨가면 좋을 것들을 3~5가지 추천해줘. 줄바꿈으로 구분하고 $NO_MARKDOWN"
                ).toString()
            }

            val weather = WeatherService.getWeather(city, meetingDate)
            val directions = "지하철 2호선 홍대입구역 2번 출구 하차 후 도보 2분"

            MeetingSummary(
                roomId = roomId,
                summary = summary,
                location = location,
                meetingDate = meetingDate,
                activities = activities,
                whatToBring = whatToBring,
                weather = weather,
                directions = directions,
                participantProfiles = profileContext
            )
        } finally {
            closeActiveConversation()  // convC 닫기
        }
    }

    private suspend fun resolveConflicts(
        name: String,
        existing: List<String>,
        fresh: List<String>,
        sendMsg: suspend (String) -> String
    ): List<String> {
        val filtered = fresh.filter { it.isNotBlank() && uselessKeywords.none { kw -> it.contains(kw) } }
        if (existing.isEmpty()) return filtered

        return try {
            val raw = sendMsg(
                "참여자 '$name'의 성향 목록을 정리해줘.\n" +
                "기존 성향: [${existing.joinToString(" / ")}]\n" +
                "새 성향: [${filtered.joinToString(" / ")}]\n\n" +
                "규칙:\n" +
                "1. 서로 반대되는 정보가 있으면 새 성향을 채택하고 기존은 제거\n" +
                "2. 반대되지 않으면 두 목록 모두 유지\n" +
                "3. 같은 의미의 항목은 하나로 합침\n" +
                "4. \"없음\", \"미확인\", \"모름\" 같은 무의미한 항목 제거\n\n" +
                "다른 텍스트 없이 아래 JSON 형식으로만 출력해.\n" +
                "출력 형식: {\"traits\": [\"성향1\", \"성향2\"]}"
            )
            Log.d("LlmService", "충돌 해소 raw ($name): $raw")
            parseJsonTraits(raw)
                .filter { uselessKeywords.none { kw -> it.contains(kw) } }
                .ifEmpty { filtered }
        } catch (e: Exception) {
            Log.e("LlmService", "충돌 해소 실패 ($name), fallback 사용", e)
            filtered
        }
    }

    private fun parseJsonTraits(raw: String): List<String> {
        return try {
            val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(raw)?.value
                ?: return emptyList()
            val json = JSONObject(jsonStr)
            // 모델이 "traits" 대신 userId 등 다른 키를 쓰는 경우를 위해 첫 번째 배열 값도 폴백으로 시도
            val arr = if (json.has("traits")) {
                json.getJSONArray("traits")
            } else {
                val firstKey = json.keys().asSequence().firstOrNull() ?: return emptyList()
                json.getJSONArray(firstKey)
            }
            List(arr.length()) { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 현재 방 참여자들의 글로벌 프로필을 꺼내 추천용 컨텍스트를 조립한다.
     * - 공통 제약: 누구 한 명이라도 가진 제약은 그룹 전체에 적용
     * - 개인 선호: 제약이 아닌 선호 항목은 개인별로 나열
     */
    private fun buildRecommendationContext(roomParticipants: List<Participant>): String {
        val userIds = roomParticipants.map { it.id }
        val profiles = profileRepository.getProfilesForUsers(userIds)
        Log.d("LlmService", "buildRecommendationContext: userIds=$userIds, 찾은 프로필=${profiles.keys}")
        if (profiles.isEmpty()) {
            Log.w("LlmService", "buildRecommendationContext: 저장된 프로필 없음 → 카드 미표시")
            return ""
        }

        val constraintKeywords = listOf("못", "안 마", "불가", "제한", "알레르기", "금지", "싫어")

        val groupConstraints = mutableListOf<String>()
        val individualPrefs = mutableListOf<Pair<String, List<String>>>()

        profiles.values.forEach { profile ->
            val (constraints, prefs) = profile.traits.partition { trait ->
                constraintKeywords.any { kw -> trait.contains(kw) }
            }
            constraints.forEach { groupConstraints.add("${profile.name}: $it") }
            if (prefs.isNotEmpty()) individualPrefs.add(profile.name to prefs)
        }

        return buildString {
            if (groupConstraints.isNotEmpty()) {
                appendLine("【그룹 공통 제약 — 반드시 고려할 것】")
                groupConstraints.forEach { appendLine("- $it") }
            }
            if (individualPrefs.isNotEmpty()) {
                if (groupConstraints.isNotEmpty()) appendLine()
                appendLine("【개인 선호도 — 가능하면 반영】")
                individualPrefs.forEach { (name, prefs) ->
                    appendLine("- $name: ${prefs.joinToString(", ")}")
                }
            }
        }.trim()
    }

    // 파싱된 키를 Participant에 매핑: 정확한 ID → 이름 → 숫자 부분 비교 순으로 시도
    private fun resolveParticipant(
        key: String,
        idToParticipant: Map<String, Participant>,
        nameToParticipant: Map<String, Participant>
    ): Participant? {
        idToParticipant[key]?.let { return it }
        nameToParticipant[key]?.let { return it }
        // 모델이 user_001 → user_01 / user_1 처럼 앞의 0을 빠뜨리는 경우 숫자만 비교
        val keyNum = key.filter { it.isDigit() }.trimStart('0').ifEmpty { return null }
        return idToParticipant.values.firstOrNull { p ->
            p.id.filter { it.isDigit() }.trimStart('0') == keyNum
        }
    }

    // {"userId": ["특징1", "특징2"], ...} 형식의 JSON을 파싱
    // 모델이 괄호 오류나 문자열 값을 섞어 출력해도 정규식 폴백으로 복구
    private fun parseProfileJson(raw: String): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()

        // 1차: JSONObject 파싱 (키별로 배열/문자열 양쪽 처리)
        val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(raw)?.value
        if (jsonStr != null) {
            try {
                val json = JSONObject(jsonStr)
                json.keys().forEach { key ->
                    val traits = runCatching {
                        val arr = json.getJSONArray(key)
                        List(arr.length()) { arr.getString(it) }.filter { it.isNotBlank() }
                    }.getOrElse {
                        runCatching { listOf(json.getString(key)).filter { it.isNotBlank() } }
                            .getOrDefault(emptyList())
                    }
                    if (traits.isNotEmpty()) result[key] = traits
                }
            } catch (e: Exception) {
                Log.w("LlmService", "JSONObject 파싱 실패, 정규식 폴백 진입: ${e.message}")
            }
        }

        // 2차 폴백: 정규식으로 키-값 쌍 직접 추출 (괄호 오류가 있어도 개별 패턴은 살아남음)
        if (result.isEmpty()) {
            // "key": ["val1", "val2"] 패턴
            Regex(""""([^"]+)"\s*:\s*\[([^\]]*)\]""").findAll(raw).forEach { m ->
                val key = m.groupValues[1]
                val traits = Regex(""""([^"]+)"""")
                    .findAll(m.groupValues[2])
                    .map { it.groupValues[1] }
                    .filter { it.isNotBlank() }
                    .toList()
                if (traits.isNotEmpty()) result[key] = traits
            }
            // "key": "value" 패턴 (배열로 감싸지 않은 경우)
            Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(raw).forEach { m ->
                val key = m.groupValues[1]
                val value = m.groupValues[2]
                if (!result.containsKey(key) && value.isNotBlank()) result[key] = listOf(value)
            }
        }

        Log.d("LlmService", "parseProfileJson 결과 (${result.size}건): ${result.keys}")
        return result
    }

    fun release() {
        closeActiveConversation()
        engine = null
    }
}
