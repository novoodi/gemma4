package com.navoodi.morimi.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.navoodi.morimi.data.model.CalendarEvent

/**
 * 캘린더 일정 Room 엔티티 (v7 신설).
 *
 * 읽기 모델은 여전히 [com.navoodi.morimi.data.repository.CalendarRepository.events]
 * (인메모리 StateFlow). 이 테이블은 앱 재시작 시 복원을 위한 영속 계층만 담당한다.
 * 컬럼을 추가할 때는 반드시 AppDatabase에 마이그레이션을 함께 등록할 것.
 */
@Entity(tableName = "calendar_event")
data class CalendarEventEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val date: String,
    val time: String,
    val location: String,
    val note: String,
    val roomId: String?,
    val placeName: String,
    val placeAddress: String,
    val placeUrl: String,
    val category: String,
    val createdAt: Long,
)

fun CalendarEvent.toEntity(createdAt: Long = System.currentTimeMillis()) = CalendarEventEntity(
    id = id,
    title = title,
    date = date,
    time = time,
    location = location,
    note = note,
    roomId = roomId,
    placeName = placeName,
    placeAddress = placeAddress,
    placeUrl = placeUrl,
    category = category,
    createdAt = createdAt,
)

fun CalendarEventEntity.toModel() = CalendarEvent(
    id = id,
    title = title,
    date = date,
    time = time,
    location = location,
    note = note,
    roomId = roomId,
    placeName = placeName,
    placeAddress = placeAddress,
    placeUrl = placeUrl,
    category = category,
)
