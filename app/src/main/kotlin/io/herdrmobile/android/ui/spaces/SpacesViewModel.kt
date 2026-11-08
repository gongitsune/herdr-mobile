package io.herdrmobile.android.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.herdrmobile.android.connection.HostConnections
import kotlinx.coroutines.flow.*

class SpacesViewModel(connections: HostConnections) : ViewModel() {
    val demo = MutableStateFlow(false)
    val errors = connections.workspaces.map { list -> list.mapNotNull { it.error } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val spaces = combine(connections.workspaces, demo) { hosts, preview ->
        if (preview) FakeSpaces.spaces else hosts.flatMap { it.mobileSpaces() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selectedTabs = MutableStateFlow<Map<String, String>>(emptyMap())
    fun select(spaceId: String, tabId: String) { selectedTabs.value = selectedTabs.value + (spaceId to tabId) }
}
