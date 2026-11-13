package com.v2ray.ang.dto

data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val error: String? = null,
    val isPreRelease: Boolean = false,
    /** Size of the APK for this phone in bytes; 0 when unknown. */
    val downloadSize: Long = 0L,
    /** Published SHA-256 of that APK (lower-case hex), or null when the release has none. */
    val sha256: String? = null,
)