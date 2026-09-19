package com.agentisco.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentisco.data.repository.UpdateRepository
import com.agentisco.settings.store.UserPreferencesStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel driving app-update checks, resumable downloads and the
 * user-visible update UI state (header progress bar + settings card).
 *
 * All fields are kept in a single [UpdateUiState] that is recomputed from the
 * repository/prefs flows via [refreshState], so every collector just mutates
 * one snapshot — no nested flow merging, no local class definitions.
 */
class UpdateViewModel(
    private val updateRepository: UpdateRepository,
    private val preferencesStore: UserPreferencesStore
) : ViewModel() {

    data class UpdateUiState(
        val updateState: UpdateRepository.UpdateState = UpdateRepository.UpdateState.IDLE,
        val downloadProgress: Float = 0f,
        val error: String? = null,
        val availableUpdate: UpdateRepository.AvailableUpdate? = null,
        val downloadedApkPath: String? = null,
        /** Whether an update APK (finished or resumable partial) sits on disk. */
        val hasUpdateFile: Boolean = false,
        val lastCheckText: String = "Never",
        val autoUpdateEnabled: Boolean = true,
        val showUpdateDialog: Boolean = false
    )

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    init {
        // Each upstream flow simply writes its slice into the UI snapshot.
        viewModelScope.launch {
            updateRepository.updateState.collect { state ->
                _uiState.update { it.copy(updateState = state, hasUpdateFile = updateRepository.hasUpdateFileOnDisk()) }
            }
        }
        viewModelScope.launch {
            updateRepository.updateProgress.collect { progress ->
                _uiState.update { it.copy(downloadProgress = progress) }
            }
        }
        viewModelScope.launch {
            updateRepository.updateError.collect { error ->
                _uiState.update { it.copy(error = error) }
            }
        }
        viewModelScope.launch {
            updateRepository.availableUpdate.collect { update ->
                _uiState.update { it.copy(availableUpdate = update) }
            }
        }
        viewModelScope.launch {
            updateRepository.downloadedApkPath.collect { path ->
                _uiState.update { it.copy(downloadedApkPath = path) }
            }
        }
        viewModelScope.launch {
            preferencesStore.preferences.collect { prefs ->
                _uiState.update {
                    it.copy(
                        autoUpdateEnabled = prefs.autoUpdateEnabled,
                        lastCheckText = updateRepository.formatLastCheck(prefs.lastUpdateCheck)
                    )
                }
            }
        }
        // The file may already be on disk from a previous run, before any state flows.
        _uiState.update { it.copy(hasUpdateFile = updateRepository.hasUpdateFileOnDisk()) }
    }

    /**
     * Runs an update check. When [isAuto] is true this is a background check
     * triggered on app start — it only proceeds when the user's toggle allows
     * automatic checks, and only pops the dialog when it actually finds
     * something newer than the installed build.
     */
    fun checkForUpdates(isAuto: Boolean = false) {
        if (isAuto && !preferencesStore.preferences.value.autoUpdateEnabled) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            val available = updateRepository.checkForUpdates()
            preferencesStore.updatePreferences {
                it.copy(lastUpdateCheck = System.currentTimeMillis())
            }
            if (available && isAuto) {
                _uiState.update { it.copy(showUpdateDialog = true) }
            }
        }
    }

    /** Starts (or resumes) the APK download; auto-launches the installer when done. */
    fun startDownload() {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            if (updateRepository.downloadUpdate()) {
                updateRepository.installDownloadedApk()
            }
        }
    }

    /**
     * Aborts the in-flight download. The partial APK is kept on disk, so the
     * next [startDownload] call resumes from there via an HTTP Range request.
     */
    fun cancelDownload() {
        updateRepository.cancelDownload()
        downloadJob?.cancel()
    }

    /**
     * Deletes the downloaded (or partially downloaded) update APK from disk,
     * alongside its completion marker, so the download can start over clean.
     */
    fun deleteDownloadedFile() {
        updateRepository.deleteDownloadedUpdate()
        downloadJob?.cancel()
        _uiState.update {
            it.copy(
                downloadedApkPath = null,
                downloadProgress = 0f,
                error = null,
                hasUpdateFile = false
            )
        }
    }

    fun installUpdate() {
        updateRepository.installDownloadedApk()
    }

    fun showDialog() {
        _uiState.update { it.copy(showUpdateDialog = true) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun dismissError() {
        updateRepository.resetToIdle()
        _uiState.update { it.copy(error = null) }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        preferencesStore.updatePreferences { it.copy(autoUpdateEnabled = enabled) }
        if (enabled) checkForUpdates(isAuto = true)
    }
}
