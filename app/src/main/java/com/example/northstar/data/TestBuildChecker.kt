package com.example.northstar.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Side-channel for installing test builds WITHOUT a version bump or a GitHub release.
 *
 * The version stays pinned at 1.3.0; instead each pushed APK carries an opaque `buildId`
 * (timestamp/sha) written to the Firestore doc `meta/test_build` by `tools/firebase/push-build.mjs`,
 * alongside a direct download URL (Firebase Storage). The app compares that buildId to the last
 * one it installed (local prefs) — so a freshly pushed build is offered even though its
 * versionName/Code is identical. Install reuses [UpdateChecker.download]/[UpdateChecker.install];
 * because every build is signed with the same key, it installs in place over the current app.
 *
 * No-op unless a Firebase project is configured ([FirebaseGate]).
 */
object TestBuildChecker {
    private const val TAG = "TestBuildChecker"
    private const val PREFS = "northstar_testbuild"
    private const val KEY_INSTALLED = "installed_build_id"
    private const val META_DOC = "meta/test_build"

    data class TestBuild(
        val buildId: String,
        val url: String,
        val builtAt: String,   // human label, e.g. "2026-06-18 14:30"
        val notes: String,
        val sizeBytes: Long,
    )

    /** The build id the app last installed via this channel (empty if never). */
    fun installedBuildId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INSTALLED, "") ?: ""

    /** Record the build id we just handed to the installer, so it isn't offered again. */
    fun markInstalled(context: Context, buildId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_INSTALLED, buildId).apply()
    }

    /** Fetch the currently-published test build, or null if none/Firebase off/error. */
    suspend fun fetchLatest(context: Context): TestBuild? {
        if (!FirebaseGate.isConfigured(context)) return null
        return runCatching {
            val parts = META_DOC.split("/")
            val snap = FirebaseFirestore.getInstance()
                .collection(parts[0]).document(parts[1]).get().await()
            if (!snap.exists()) return null
            val buildId = snap.getString("buildId").orEmpty()
            val url = snap.getString("url").orEmpty()
            if (buildId.isBlank() || url.isBlank()) return null
            TestBuild(
                buildId = buildId,
                url = url,
                builtAt = snap.getString("builtAt").orEmpty(),
                notes = snap.getString("notes").orEmpty(),
                sizeBytes = snap.getLong("sizeBytes") ?: 0L,
            )
        }.onFailure { Log.w(TAG, "test-build check failed: ${it.message}") }.getOrNull()
    }

    /** True if [build] is something other than what we last installed. */
    fun isNew(context: Context, build: TestBuild): Boolean =
        build.buildId.isNotBlank() && build.buildId != installedBuildId(context)
}
