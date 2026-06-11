package com.example.northstar.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AuthState(
    val isSignedIn: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class AuthViewModel : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state = _state.asStateFlow()

    init {
        runCatching {
            if (Firebase.auth.currentUser != null) _state.value = AuthState(isSignedIn = true)
        }
    }

    fun signInWithGoogle(idToken: String) {
        _state.value = AuthState(loading = true)
        viewModelScope.launch {
            runCatching {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                Firebase.auth.signInWithCredential(credential).await()
                _state.value = AuthState(isSignedIn = true)
            }.onFailure { e ->
                _state.value = AuthState(error = e.message ?: "Sign-in failed")
            }
        }
    }

    fun signOut() {
        runCatching { Firebase.auth.signOut() }
        _state.value = AuthState(isSignedIn = false)
    }
}
