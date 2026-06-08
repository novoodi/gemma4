package com.example.gemma4.data.model

data class MeetingSummary(
    val roomId: String,
    val summary: String = "",
    val location: String = "",
    val meetingDate: String = "",
    val activities: String = "",
    val whatToBring: String = "",
    val weather: String = "",
    val directions: String = "",
    val participantProfiles: String = ""
)
