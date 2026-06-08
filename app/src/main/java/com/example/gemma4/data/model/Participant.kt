package com.example.gemma4.data.model

import java.util.UUID

data class Participant(
    val id: String = UUID.randomUUID().toString(),
    val name: String
)
