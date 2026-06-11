package com.example.northstar.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.data.SharedLocation
import com.example.northstar.util.LocationParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RouteState(
    val destination: SharedLocation? = null,
    val isResolving: Boolean = false,
    val pendingNavigate: Boolean = false,
)

class RouteViewModel : ViewModel() {
    private val _state = MutableStateFlow(RouteState())
    val state = _state.asStateFlow()

    fun handleSharedText(text: String) {
        val loc = LocationParser.parse(text)
        _state.value = RouteState(
            destination     = loc,
            isResolving     = loc.needsExpansion,
            pendingNavigate = true,
        )
        if (loc.needsExpansion && loc.url != null) {
            viewModelScope.launch {
                val expanded     = LocationParser.expandShortUrl(loc.url)
                val coords       = LocationParser.extractCoords(expanded)
                val resolvedName = if (loc.name.isNotBlank() && loc.name != "Loading…") loc.name
                                   else LocationParser.extractPlaceName(expanded) ?: "Shared location"
                _state.value = _state.value.copy(
                    destination = loc.copy(
                        name           = resolvedName,
                        lat            = coords?.first,
                        lng            = coords?.second,
                        url            = expanded,
                        needsExpansion = false,
                    ),
                    isResolving = false,
                )
            }
        }
    }

    fun onNavigated() {
        _state.value = _state.value.copy(pendingNavigate = false)
    }

    fun clear() {
        _state.value = RouteState()
    }
}
