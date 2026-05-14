package com.example.gemma4.data.model

import java.util.UUID

data class CalendarEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String, // YYYY-MM-DD
    val location: String = "",
    val note: String = "",
    val roomId: String? = null
)
