package com.example.gemma4.data.repository

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ParticipantProfile(
    val userId: String,
    val name: String,
    val traits: List<String>
)

class ProfileRepository(context: Context) {

    private val file = File(context.filesDir, "participant_profiles.json")

    // JSON 구조: { "userId": { "name": "이름", "traits": ["특징1", ...] }, ... }
    // 구 포맷 { "이름": ["특징1", ...] } 항목은 스킵하여 마이그레이션 충돌 방지
    fun load(): Map<String, ParticipantProfile> {
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            buildMap {
                json.keys().forEach { key ->
                    when (val value = json.get(key)) {
                        is JSONObject -> {
                            // 신 포맷: {"userId": {"name": ..., "traits": [...]}}
                            val name = value.optString("name", key)
                            val arr = value.getJSONArray("traits")
                            put(key, ParticipantProfile(
                                userId = key,
                                name = name,
                                traits = List(arr.length()) { arr.getString(it) }
                            ))
                        }
                        is JSONArray -> {
                            // 구 포맷: {"이름": [...]} — 다음 저장 시 자동으로 제거됨
                            Log.w("ProfileRepository", "구 포맷 항목 감지 ($key), 스킵")
                        }
                        else -> Log.w("ProfileRepository", "알 수 없는 포맷: key=$key")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ProfileRepository", "프로필 파일 읽기 실패", e)
            emptyMap()
        }
    }

    fun loadProfile(userId: String): ParticipantProfile? = load()[userId]

    fun saveProfile(userId: String, name: String, traits: List<String>) {
        val profiles = load().toMutableMap()
        profiles[userId] = ParticipantProfile(userId = userId, name = name, traits = traits)
        save(profiles)
        Log.d("ProfileRepository", "프로필 저장: [$userId] $name → ${traits.joinToString(", ")}")
    }

    fun getProfilesForUsers(userIds: List<String>): Map<String, ParticipantProfile> {
        val all = load()
        return userIds.mapNotNull { id -> all[id]?.let { id to it } }.toMap()
    }

    fun toContextString(userIds: List<String>? = null): String {
        val profiles = if (userIds != null) getProfilesForUsers(userIds) else load()
        if (profiles.isEmpty()) return ""
        return profiles.values.joinToString("\n") { profile ->
            "${profile.name}: ${profile.traits.joinToString(", ")}"
        }
    }

    fun clearAll() {
        file.delete()
        Log.d("ProfileRepository", "프로필 초기화 완료")
    }

    private fun save(profiles: Map<String, ParticipantProfile>) {
        val json = JSONObject()
        profiles.forEach { (userId, profile) ->
            val obj = JSONObject().apply {
                put("name", profile.name)
                put("traits", JSONArray(profile.traits))
            }
            json.put(userId, obj)
        }
        file.writeText(json.toString())
    }
}
