package com.navoodi.morimi.data.repository

import android.content.SharedPreferences
import android.util.Log
import com.navoodi.morimi.data.local.CalendarEventDao
import com.navoodi.morimi.data.local.toEntity
import com.navoodi.morimi.data.local.toModel
import com.navoodi.morimi.data.model.CalendarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 캘린더 일정 저장소.
 *
 * 읽기 모델은 인메모리 [events] StateFlow — 화면들은 이 API를 그대로 쓴다.
 * 영속은 Room(calendar_event)이 담당한다 (SummaryRepository와 같은 하이드레이션 패턴):
 *  - 앱 시작 시 [attach]가 DB에서 일정을 읽어 [events]에 복원
 *  - 추가·삭제는 인메모리에 즉시 반영하고 DB에는 백그라운드로 write-through
 *
 * 사용자 지정 카테고리 목록은 [categories] — SharedPreferences에 보관한다 (단순 문자열 목록이라 DB 불필요).
 *
 * [attach] 전(테스트, 초기화 전 호출)에도 인메모리로는 정상 동작한다.
 */
object CalendarRepository {

    private const val TAG = "CalendarRepository"

    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    private const val PREF_CATEGORIES = "categories"
    private const val CATEGORY_SEPARATOR = "\u001F" // 사용자가 입력할 수 없는 구분자
    val DEFAULT_CATEGORIES = listOf("학교", "운동", "회의", "모임")

    private val _categories = MutableStateFlow(DEFAULT_CATEGORIES)
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private var dao: CalendarEventDao? = null
    private var scope: CoroutineScope? = null
    private var prefs: SharedPreferences? = null

    /**
     * Room 영속 계층 연결 + 저장된 일정 복원. MoimApp.onCreate에서 한 번 호출.
     * 복원이 끝나기 전에 추가된 일정이 있어도 사라지지 않도록 id 기준으로 병합한다.
     */
    fun attach(dao: CalendarEventDao, scope: CoroutineScope, prefs: SharedPreferences? = null) {
        this.dao = dao
        this.scope = scope
        this.prefs = prefs
        prefs?.getString(PREF_CATEGORIES, null)?.let { saved ->
            _categories.value = saved.split(CATEGORY_SEPARATOR).filter { it.isNotBlank() }
        }
        scope.launch(Dispatchers.IO) {
            val stored = runCatching { dao.getAll().map { it.toModel() } }
                .onFailure { Log.w(TAG, "저장된 일정 복원 실패 — 인메모리로 계속", it) }
                .getOrDefault(emptyList())
            _events.update { current ->
                val storedIds = stored.map { it.id }.toSet()
                stored + current.filterNot { it.id in storedIds }
            }
            Log.d(TAG, "일정 ${stored.size}건 복원")
        }
    }

    // placeUrl이 있으면 placeUrl, 없으면 placeName을 식별 키로 사용
    private fun placeKey(placeUrl: String, placeName: String) = placeUrl.ifBlank { placeName }

    /**
     * 일정 추가. 같은 방에서 같은 장소를 두 번 담는 것만 막는다.
     *
     * 중복 검사는 방(roomId)에서 온 일정에만 적용한다 — 캘린더 화면에서 직접 추가한
     * 일정(roomId == null)은 장소가 비어 있어 서로 "중복"으로 판정되어,
     * 두 번째 일정부터 전부 추가되지 않던 버그가 있었다.
     */
    fun addEvent(event: CalendarEvent): Boolean {
        if (event.roomId != null) {
            val duplicate = _events.value.any { e ->
                e.roomId == event.roomId &&
                    if (event.placeName.isNotBlank()) e.placeName == event.placeName
                    else e.placeName.isBlank()
            }
            if (duplicate) return false
        }
        _events.update { it + event }
        persist { upsert(event.toEntity()) }
        return true
    }

    fun isPlaceAdded(roomId: String, placeUrl: String, placeName: String): Boolean {
        val key = placeKey(placeUrl, placeName)
        return _events.value.any { e ->
            e.roomId == roomId && placeKey(e.placeUrl, e.placeName) == key
        }
    }

    fun removePlaceEvent(roomId: String, placeUrl: String, placeName: String) {
        val key = placeKey(placeUrl, placeName)
        val event = _events.value.firstOrNull { e ->
            e.roomId == roomId && placeKey(e.placeUrl, e.placeName) == key
        }
        event?.let { removeEvent(it.id) }
    }

    fun removeEvent(id: String) {
        _events.update { list -> list.filter { it.id != id } }
        persist { deleteById(id) }
    }

    /** 일정에 카테고리 지정 (빈 문자열 = 미분류) */
    fun updateEventCategory(id: String, category: String) {
        var changed: CalendarEvent? = null
        _events.update { list ->
            list.map { if (it.id == id) it.copy(category = category).also { c -> changed = c } else it }
        }
        changed?.let { ev -> persist { upsert(ev.toEntity()) } }
    }

    // ── 카테고리 관리 ────────────────────────────────────────────────

    /** 카테고리 추가. 공백·중복·예약어("전체")는 거부하고 false 반환 */
    fun addCategory(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed == "전체" || trimmed in _categories.value) return false
        _categories.update { it + trimmed }
        saveCategories()
        return true
    }

    /** 카테고리 삭제. 해당 카테고리가 지정된 일정은 미분류로 되돌린다 (일정 자체는 유지) */
    fun removeCategory(name: String) {
        _categories.update { it - name }
        saveCategories()
        _events.value.filter { it.category == name }.forEach { updateEventCategory(it.id, "") }
    }

    private fun saveCategories() {
        prefs?.edit()?.putString(PREF_CATEGORIES, _categories.value.joinToString(CATEGORY_SEPARATOR))?.apply()
    }

    /** DB 쓰기는 실패해도 화면을 막지 않는다 — 로그만 남기고 인메모리 상태는 유지 */
    private fun persist(block: suspend CalendarEventDao.() -> Unit) {
        val d = dao ?: return
        val s = scope ?: return
        s.launch(Dispatchers.IO) {
            runCatching { d.block() }.onFailure { Log.w(TAG, "일정 DB 반영 실패", it) }
        }
    }
}
