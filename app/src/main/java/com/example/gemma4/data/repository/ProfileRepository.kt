package com.example.gemma4.data.repository

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProfileRepository(context: Context) {

    private val file = File(context.filesDir, "participant_profiles.json")

    // 쓸모없는 "없음"/"미확인" 계열 단어
    private val uselessKeywords = listOf("없음", "미확인", "모름", "불명확")

    fun load(): Map<String, List<String>> {
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            buildMap {
                json.keys().forEach { name ->
                    val arr = json.getJSONArray(name)
                    put(name, List(arr.length()) { arr.getString(it) })
                }
            }
        } catch (e: Exception) {
            Log.e("ProfileRepository", "프로필 로드 실패", e)
            emptyMap()
        }
    }

    fun merge(newTraits: Map<String, List<String>>) {
        val existing = load().toMutableMap()
        newTraits.forEach { (rawName, traits) ->
            val name = normalizeName(rawName, existing)

            val filtered = traits.filter { trait ->
                trait.isNotBlank() && uselessKeywords.none { trait.contains(it) }
            }
            if (filtered.isEmpty()) return@forEach

            val current = existing[name]?.toMutableList() ?: mutableListOf()
            filtered.forEach { trait ->
                if (current.none { it.equals(trait, ignoreCase = true) }) current.add(trait)
            }
            existing[name] = current
        }
        save(existing)
        Log.d("ProfileRepository", "누적된 참여자 프로필:\n${existing.entries.joinToString("\n") { (k, v) -> "$k: ${v.joinToString(", ")}" }}")
    }

    fun toContextString(): String {
        val profiles = load()
        if (profiles.isEmpty()) return ""
        return profiles.entries.joinToString("\n") { (name, traits) ->
            "$name: ${traits.joinToString(", ")}"
        }
    }

    fun clearAll() {
        file.delete()
        Log.d("ProfileRepository", "프로필 초기화 완료")
    }

    private fun save(profiles: Map<String, List<String>>) {
        val json = JSONObject()
        profiles.forEach { (name, traits) -> json.put(name, JSONArray(traits)) }
        file.writeText(json.toString())
    }

    // 기존 이름과 비교해서 오타/중복 이름을 정규화 — 항상 짧은 이름을 정식 이름으로 사용
    private fun normalizeName(name: String, existing: MutableMap<String, List<String>>): String {
        if (name in existing) return name

        for (existingName in existing.keys.toList()) {
            // 포함 관계: 짧은 이름이 정식 (차민영영 → 차민영)
            if (existingName.contains(name) || name.contains(existingName)) {
                val canonical = if (name.length <= existingName.length) name else existingName
                if (canonical != existingName) {
                    existing[canonical] = existing[existingName]!!
                    existing.remove(existingName)
                }
                return canonical
            }
            // 편집거리 1: 기존 이름 유지 (어느 쪽이 맞는지 알 수 없음)
            if (levenshtein(name, existingName) <= 1) return existingName
        }

        return name
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }
}
