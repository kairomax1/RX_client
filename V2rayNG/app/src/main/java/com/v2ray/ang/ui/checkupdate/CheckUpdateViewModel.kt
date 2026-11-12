package com.v2ray.ang.ui.checkupdate

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.UpdateNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {

    private val _checkPreRelease = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
    )
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()

    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    fun toggleCheckPreRelease(enabled: Boolean) {
        _checkPreRelease.value = enabled
        MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled)
    }

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    /** A downloaded APK waiting to be handed to the system installer by the screen. */
    private val _installFile = MutableStateFlow<File?>(null)
    val installFile: StateFlow<File?> = _installFile.asStateFlow()

    /**
     * @param autoInstall opened from the update notification: skip the dialog and go straight to
     * download and install.
     */
    fun checkForUpdates(autoInstall: Boolean = false) {
        launchLoading {
            try {
                val result = UpdateCheckerManager.checkForUpdate(_checkPreRelease.value)
                if (result.hasUpdate) {
                    if (!result.isPreRelease) result.latestVersion?.let { UpdateNotifier.markPending(it) }
                    _updateResult.value = result
                    if (autoInstall) downloadAndInstall(result) else _showUpdateDialog.value = true
                } else {
                    UpdateNotifier.clearPending()
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to check for updates", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun downloadAndInstall(result: CheckUpdateResult) {
        val version = result.latestVersion
        val url = result.downloadUrl
        if (version.isNullOrEmpty() || url.isNullOrEmpty() || _downloading.value) return
        _downloading.value = true
        viewModelScope.launch {
            try {
                val apk = UpdateNotifier.downloadApk(getApplication<Application>(), version, url)
                if (apk != null) _installFile.value = apk else toastError(R.string.update_download_failed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to download the update", e)
                toastError(R.string.update_download_failed)
            } finally {
                _downloading.value = false
            }
        }
    }

    fun onInstallHandled() {
        _installFile.value = null
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }
}
