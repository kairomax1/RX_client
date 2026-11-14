package com.v2ray.ang.ui.applog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppLogUiState(
    /** Null = the "all sections" tab. */
    val section: AppLog.Section? = null,
    val mode: AppLog.Mode = AppLog.Mode.Normal,
    /** Lines of the chosen tab and mode, newest first. */
    val lines: List<AppLog.Entry> = emptyList(),
    /** How many lines each section has in the chosen mode; null key = all. */
    val counts: Map<AppLog.Section?, Int> = emptyMap(),
    val loaded: Boolean = false,
)

class AppLogViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AppLogUiState())
    val state: StateFlow<AppLogUiState> = _state.asStateFlow()

    private var all: List<AppLog.Entry> = emptyList()

    init {
        // The service writes while the screen is open, so the list follows along.
        viewModelScope.launch {
            while (isActive) {
                reload()
                delay(REFRESH_MILLIS)
            }
        }
    }

    private suspend fun reload() {
        all = withContext(Dispatchers.IO) { AppLog.read() }
        publish()
    }

    private fun publish() {
        _state.update { current ->
            val inMode = AppLog.filter(all, null, current.mode)
            val counts = HashMap<AppLog.Section?, Int>()
            counts[null] = inMode.size
            inMode.groupingBy { it.section }.eachCount().forEach { (section, count) -> counts[section] = count }
            current.copy(
                lines = AppLog.filter(all, current.section, current.mode).takeLast(MAX_SHOWN).asReversed(),
                counts = counts,
                loaded = true,
            )
        }
    }

    fun setSection(section: AppLog.Section?) {
        _state.update { it.copy(section = section) }
        publish()
    }

    fun setMode(mode: AppLog.Mode) {
        _state.update { it.copy(mode = mode) }
        publish()
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppLog.clear() }
            reload()
        }
    }

    /**
     * Writes the complete log (every section, every level: the file is for finding a fault) to
     * a text file in the cache and returns it; null when there is nothing to write.
     */
    suspend fun buildFile(): File? = withContext(Dispatchers.IO) {
        val entries = AppLog.read()
        if (entries.isEmpty()) return@withContext null
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val folder = File(getApplication<Application>().cacheDir, "logs").apply { mkdirs() }
            folder.listFiles()?.forEach { it.delete() }
            val header = "RX VPN ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) - Android ${android.os.Build.VERSION.RELEASE} - " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} - ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}"
            File(folder, "rxvpn-log-$stamp.txt").apply { writeText(AppLog.export(entries, header)) }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 1500L
        const val MAX_SHOWN = 800
    }
}
