package com.example.skinscript.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.skinscript.data.InstallProgress
import com.example.skinscript.data.InstallSummary
import com.example.skinscript.data.OverwriteMode
import com.example.skinscript.data.SettingsRepository
import com.example.skinscript.data.SkinPackage
import com.example.skinscript.data.ZipAnalyzer
import com.example.skinscript.installer.FileOperationBackend
import com.example.skinscript.installer.OverwriteDecision
import com.example.skinscript.installer.SkinInstaller
import com.example.skinscript.shizuku.ShizukuFileBackend
import com.example.skinscript.shizuku.ShizukuManager
import com.example.skinscript.shizuku.ShizukuState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

data class OverwritePromptData(
    val fileName: String,
    val targetPath: String,
    val deferredResult: CompletableDeferred<OverwriteDecision>
)

class SkinInstallerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SkinInstallerViewModel"
    }

    private val context = application.applicationContext
    val shizukuManager = ShizukuManager(context)
    private val settingsRepository = SettingsRepository(context)
    private val zipAnalyzer = ZipAnalyzer(context)
    private val fileBackend: FileOperationBackend = ShizukuFileBackend()
    private val installer = SkinInstaller(context, fileBackend)

    private var installJob: Job? = null

    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state

    val destinationPath: StateFlow<String> = settingsRepository.destinationPath
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.DEFAULT_DESTINATION_PATH
        )

    val overwriteMode: StateFlow<OverwriteMode> = settingsRepository.overwriteMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = OverwriteMode.ASK_EVERY_TIME
        )

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _skinPackage = MutableStateFlow<SkinPackage?>(null)
    val skinPackage: StateFlow<SkinPackage?> = _skinPackage.asStateFlow()

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    private val _installProgress = MutableStateFlow(InstallProgress())
    val installProgress: StateFlow<InstallProgress> = _installProgress.asStateFlow()

    private val _installSummary = MutableStateFlow<InstallSummary?>(null)
    val installSummary: StateFlow<InstallSummary?> = _installSummary.asStateFlow()

    private val _testAccessResult = MutableStateFlow<String?>(null)
    val testAccessResult: StateFlow<String?> = _testAccessResult.asStateFlow()

    private val _isTestingAccess = MutableStateFlow(false)
    val isTestingAccess: StateFlow<Boolean> = _isTestingAccess.asStateFlow()

    private val _overwritePrompt = MutableStateFlow<OverwritePromptData?>(null)
    val overwritePrompt: StateFlow<OverwritePromptData?> = _overwritePrompt.asStateFlow()

    init {
        shizukuManager.init()
    }

    override fun onCleared() {
        super.onCleared()
        cancelPendingOverwritePrompt()
        shizukuManager.destroy()
    }

    fun loadZip(uri: Uri) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _analysisError.value = null
            _installSummary.value = null

            zipAnalyzer.analyze(uri).fold(
                onSuccess = { pkg ->
                    _skinPackage.value = pkg
                    if (!pkg.hasValidAssets) {
                        _analysisError.value = "No valid game folders (Art, Audio, UI) detected in this ZIP archive."
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to analyze ZIP: $uri", error)
                    _skinPackage.value = null
                    _analysisError.value = "Failed to analyze ZIP: ${error.localizedMessage ?: "Unknown error"}"
                }
            )
            _isAnalyzing.value = false
        }
    }

    fun setDestination(path: String) {
        viewModelScope.launch {
            settingsRepository.setDestinationPath(path)
            _testAccessResult.value = null
        }
    }

    fun setOverwrite(mode: OverwriteMode) {
        viewModelScope.launch {
            settingsRepository.setOverwriteMode(mode)
        }
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun refreshShizuku() {
        shizukuManager.refreshStatus()
    }

    fun testDestinationAccess() {
        viewModelScope.launch {
            _isTestingAccess.value = true
            _testAccessResult.value = null

            if (!shizukuState.value.isReady) {
                _testAccessResult.value = "Shizuku is not running or authorized."
                _isTestingAccess.value = false
                return@launch
            }

            val path = destinationPath.value
            try {
                val exists = fileBackend.exists(path)
                val writable = fileBackend.isWritable(path)
                _testAccessResult.value = if (writable) {
                    "✓ Destination is accessible and writable (Exists: $exists)"
                } else {
                    "⚠ Destination test returned non-writable: $path"
                }
            } catch (e: Throwable) {
                _testAccessResult.value = "Error testing destination: ${e.localizedMessage}"
            }
            _isTestingAccess.value = false
        }
    }

    fun startInstall() {
        if (_isInstalling.value) return
        val currentPackage = _skinPackage.value ?: return
        val dest = destinationPath.value
        val mode = overwriteMode.value

        installJob = viewModelScope.launch {
            _isInstalling.value = true
            _installSummary.value = null
            _installProgress.value = InstallProgress(
                currentFileName = "Starting...",
                completedCount = 0,
                totalCount = currentPackage.totalFiles,
                isIndeterminate = true
            )

            try {
                val summary = installer.install(
                    skinPackage = currentPackage,
                    destinationBasePath = dest,
                    overwriteMode = mode,
                    onProgress = { progress ->
                        _installProgress.value = progress
                    },
                    onPromptOverwrite = { fileName, targetPath ->
                        val deferred = CompletableDeferred<OverwriteDecision>()
                        _overwritePrompt.value = OverwritePromptData(
                            fileName = fileName,
                            targetPath = targetPath,
                            deferredResult = deferred
                        )
                        val decision = deferred.await()
                        _overwritePrompt.value = null
                        decision
                    }
                )
                _installSummary.value = summary
            } catch (e: CancellationException) {
                Log.d(TAG, "Installation cancelled by user.")
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected error during installation", e)
                _analysisError.value = "Installation failed: ${e.localizedMessage}"
            } finally {
                _isInstalling.value = false
                _overwritePrompt.value = null
            }
        }
    }

    fun respondToOverwritePrompt(decision: OverwriteDecision) {
        val currentPrompt = _overwritePrompt.value
        if (currentPrompt != null && currentPrompt.deferredResult.isActive) {
            currentPrompt.deferredResult.complete(decision)
        }
    }

    fun cancelInstall() {
        cancelPendingOverwritePrompt()
        installJob?.cancel()
        _isInstalling.value = false
    }

    private fun cancelPendingOverwritePrompt() {
        _overwritePrompt.value?.let { prompt ->
            if (prompt.deferredResult.isActive) {
                prompt.deferredResult.cancel(CancellationException("Prompt dismissed or cancelled."))
            }
            _overwritePrompt.value = null
        }
    }

    fun dismissSummary() {
        _installSummary.value = null
    }

    fun dismissError() {
        _analysisError.value = null
    }
}