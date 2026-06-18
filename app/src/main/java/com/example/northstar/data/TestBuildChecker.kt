package com.example.northstar.data

import android.content.Context
import android.util.Log
import com.example.northstar.util.BuildId
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Decides whether the installed APK matches the published build, by CHECKSUM.
 *
 * The version stays pinned (e.g. 1.3.0), so version comparison can't tell builds apart. Instead
 * `tools/firebase/push-build.mjs` records the pushed APK's SHA-256 (12-hex) in `meta/test_build`,
 * and the app compares it against [BuildId.sha12] — the hash of its OWN running APK. If they
 * differ, a different build is published and the app prompts a redownload + reinstall.
 *
 * Checksum (not a stored "last installed" id) is the source of truth: it's correct no matter HOW
 * the build was installed — in-app, the public permalink, or adb — and the prompt clears itself the
 * moment the matching APK is running. Same idea works for a release channel once releases publish a
 * checksum; see [UpdateChecker]. No-op unless a Firebase project is configured ([FirebaseGate]).
 */
object TestBuildChecker {
    private const val TAG = "TestBuildChecker"
    private const val META_DOC = "meta/test_build"

    data class TestBuild(
        val buildId: String,
        val sha256: String,    // 12-hex SHA-256 of the published APK (matches BuildId.sha12)
        val url: String,
        val builtAt: String,   // human label, e.g. "2026-06-18 14:30"
        val notes: String,
        val sizeBytes: Long,
    )

    /** Fetch the currently-published test build, or null if none/Firebase off/error. */
    suspend fun fetchLatest(context: Context): TestBuild? {
        if (!FirebaseGate.isConfigured(context)) return null
        return runCatching {
            val parts = META_DOC.split("/")
            val snap = FirebaseFirestore.getInstance()
                .collection(parts[0]).document(parts[1]).get().await()
            if (!snap.exists()) return null
            val url = snap.getString("url").orEmpty()
            if (url.isBlank()) return null
            TestBuild(
                buildId = snap.getString("buildId").orEmpty(),
                sha256 = snap.getString("sha256").orEmpty(),
                url = url,
                builtAt = snap.getString("builtAt").orEmpty(),
                notes = snap.getString("notes").orEmpty(),
                sizeBytes = snap.getLong("sizeBytes") ?: 0L,
            )
        }.onFailure { Log.w(TAG, "test-build check failed: ${it.message}") }.getOrNull()
    }

    /**
     * True if the running APK's checksum differs from the published one — i.e. a redownload +
     * reinstall is needed. False when already on the published build, or when either checksum is
     * unknown (don't nag if we can't be sure).
     */
    fun needsInstall(context: Context, build: TestBuild): Boolean {
        if (build.sha256.isBlank()) return false
        val mine = BuildId.sha12(context)
        return mine != "unknown" && mine != build.sha256
    }
}
