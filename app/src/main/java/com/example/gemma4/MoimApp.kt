package com.example.gemma4

import android.app.Application
import com.example.gemma4.service.LlmService

class MoimApp : Application() {
    val llmService: LlmService by lazy { LlmService(this) }
}
