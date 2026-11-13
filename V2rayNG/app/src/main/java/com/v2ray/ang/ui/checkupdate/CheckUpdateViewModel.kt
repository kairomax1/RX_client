package com.v2ray.ang.ui.checkupdate

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.UpdateDownloader
import com.v2ray.ang.handler.UpdateNotifier
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What the download button is doing. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val progress: UpdateDownloader.Progress) : DownloadState
    data class Ready(val apk: File) : DownloadState
    data object Failed : DownloadState
}

class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {

    private val _checkPreRelease = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
    )
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()

    /** The newer release, once one was found. */
    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()

    /** Bumped each time a check ends with "nothing newer"; the screen replays its note. */
    private val _noUpdateTick = MutableStateFlow(0)
    val noUpdateTick: StateFlow<Int> = _noUpdateTick.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    /** A finished APK waiting to be handed to the system installer by the screen. */
    private val _installFile = MutableStateFlow<File?>(null)
    val installFile: StateFlow<File?> = _installFile.asStateFlow()

    private var downloadJob: Job? = null

    fun toggleCheckPreRelease(enabled: Boolean) {
        _checkPreRelease.value = enabled
        MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled)
    }

    fun checkForUpdates() {
        launchLoading {
            try {
                val result = UpdateCheckerManager.checkForUpdate(_checkPreRelease.value)
                if (result.hasUpdate) {
                    if (!result.isPreRelease) result.latestVersion?.let { UpdateNotifier.markPending(it) }
                    if (_updateResult.value?.latestVersion != result.latestVersion) {
                        downloadJob?.cancel()
                        _download.value = alreadyDownloaded(result)
                    }
                    _updateResult.value = result
                } else {
                    UpdateNotifier.clearPending()
                    _updateResult.value = null
                    _noUpdateTick.update { it + 1 }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to check for updates", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    /** A complete file from an earlier visit is offered for install straight away. */
    private fun alreadyDownloaded(result: CheckUpdateResult): DownloadState {
        val version = result.latestVersion ?: return DownloadState.Idle
        val file = UpdateDownloader.targetFile(getApplication<Application>(), version)
        val complete = file.length() > 0 && (result.downloadSize <= 0 || file.length() == result.downloadSize)
        return if (complete) DownloadState.Ready(file) else DownloadState.Idle
    }

    /** Starts (or continues) the download. Called only after the user agreed to the size. */
    fun startDownload() {
        val result = _updateResult.value ?: return
        val version = result.latestVersion
        val url = result.downloadUrl
        if (version.isNullOrEmpty() || url.isNullOrEmpty() || downloadJob?.isActive == true) return
        _download.value = DownloadState.Running(UpdateDownloader.Progress(0, result.downloadSize, 0))
        downloadJob = viewModelScope.launch {
            try {
                val apk = UpdateDownloader.download(
                    getApplication<Application>(), version, url, result.downloadSize, result.sha256,
                ) { progress -> _download.value = DownloadState.Running(progress) }
                if (apk != null) {
                    _download.value = DownloadState.Ready(apk)
                    _installFile.value = apk
                } else {
                    _download.value = DownloadState.Failed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to download the update", e)
                _download.value = DownloadState.Failed
            }
        }
    }

    fun install() {
        (_download.value as? DownloadState.Ready)?.let { _installFile.value = it.apk }
    }

    fun onInstallHandled() {
        _installFile.value = null
    }
}
