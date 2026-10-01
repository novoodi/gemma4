package com.navoodi.morimi.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_status")
data class UserStatusEntity(
    @PrimaryKey
    val roomId: String,
    val participants: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val availability: List<String> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis(),
    /**
     * 이 프로필을 만들 때의 방 멤버 uid(v8, 2026-10). 이 중 누가 나가면 프로필의 선호·불호를 쓰지 않고
     * 남은 멤버 대화로 다시 만든다([com.navoodi.morimi.data.pipeline.MemberScope]).
     * 비어 있으면 출처 미기록(v7 이전 프로필). 사람별 선호 저장으로 확장할 때의 출처 기록이기도 하다.
     */
    @ColumnInfo(defaultValue = "")
    val sourceMemberIds: List<String> = emptyList(),
)
