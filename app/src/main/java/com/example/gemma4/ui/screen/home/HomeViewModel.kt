package com.example.gemma4.ui.screen.home

import androidx.lifecycle.ViewModel
import com.example.gemma4.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel : ViewModel() {

    val rooms = ChatRepository.rooms

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    fun showCreateDialog() { _showCreateDialog.value = true }
    fun hideCreateDialog() { _showCreateDialog.value = false }

    fun createRoom(name: String, participants: List<String>) {
        if (name.isNotBlank()) ChatRepository.createRoom(name.trim(), participants)
        hideCreateDialog()
    }
}
