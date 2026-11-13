package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000
            )
        )
        if (response.isNullOrEmpty()) {
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
                ?: throw IllegalStateException("Failed to get response")
        }

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
                ?.firstOrNull()
                ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val asset = pickAsset(latestRelease)
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                // Only the part of the description written for the app.
                releaseNotes = UpdateRelease.appNotes(latestRelease.body),
                downloadUrl = asset.browserDownloadUrl,
                isPreRelease = latestRelease.prerelease,
                downloadSize = asset.size,
                sha256 = UpdateRelease.sha256Of(asset.digest),
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    /** Numeric comparison of dotted versions; a suffix such as "-test" on a part is ignored. */
    fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".")
        val v2 = version2.split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = v1.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val num2 = v2.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    /** The APK built for this phone's processor; the universal build when there is none. */
    private fun pickAsset(release: GitHubRelease): GitHubRelease.Asset {
        val fDroid = "fdroid"
        val wantsFdroid = BuildConfig.APPLICATION_ID.contains(fDroid, ignoreCase = true)
        val candidates = release.assets.filter { it.name.contains(fDroid, ignoreCase = true) == wantsFdroid }
        return UpdateRelease.pickApk(candidates, Build.SUPPORTED_ABIS.toList()) { it.name }
            ?: throw IllegalStateException("No compatible APK found")
    }
}
