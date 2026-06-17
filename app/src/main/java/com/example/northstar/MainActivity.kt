package com.example.northstar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.northstar.data.SyncRepository
import com.example.northstar.ui.navigation.AppNavigation
import com.example.northstar.ui.theme.Bg1
import com.example.northstar.ui.theme.NorthstarTheme
import com.example.northstar.viewmodel.RouteViewModel
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    private val routeViewModel: RouteViewModel by viewModels()
    private var authListener: FirebaseAuth.AuthStateListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Firestore sync follows sign-in: start mirroring/listening when signed in,
        // stop on sign-out. Only wired when Firebase is configured (bring-your-own-project);
        // otherwise the app runs fully local with no sync.
        val sync = SyncRepository.get(applicationContext)
        if (com.example.northstar.data.FirebaseGate.isConfigured(applicationContext)) {
            authListener = FirebaseAuth.AuthStateListener { fa ->
                if (fa.currentUser != null) sync.startSync() else sync.stopSync()
            }.also { FirebaseAuth.getInstance().addAuthStateListener(it) }
        }

        // Maintenance + document-expiry reminders on app open (fire even if Garage is never
        // opened). Only on a fresh launch — savedInstanceState != null means this is a config
        // change (e.g. rotation), where re-running it is wasted work.
        if (savedInstanceState == null) {
            Thread {
                com.example.northstar.data.MaintenanceNotifier.check(
                    applicationContext, sync.maintenanceItems(), sync.odometer().toInt()
                )
                com.example.northstar.data.MaintenanceNotifier.checkDocuments(applicationContext, sync.documents())
            }.start()
        }

        handleIntent(intent)
        setContent {
            NorthstarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg1) {
                    AppNavigation(routeViewModel = routeViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't leak / accumulate the auth listener across Activity recreations.
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        authListener = null
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    routeViewModel.handleSharedText(text)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: return
                routeViewModel.handleSharedText(uri)
            }
        }
    }
}
