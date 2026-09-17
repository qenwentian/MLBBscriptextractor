package com.example.skinscript.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.skinscript.data.ConflictDetector
import com.example.skinscript.data.ConflictGroup
import com.example.skinscript.data.ExtractedSkinRecord
import com.example.skinscript.data.ExtractedSkinsRepository
import com.example.skinscript.data.InstallProgress
import com.example.skinscript.data.InstallSummary
import com.example.skinscript.data.OverwriteMode
import com.example.skinscript.data.SettingsRepository
import com.example.skinscript.data.SkinPackage
import com.example.skinscript.data.SkinStorageManager
import com.example.skinscript.data.ZipAnalyzer
import com.example.skinscript.installer.FileOperationBackend
import com.example.skinscript.installer.OverwriteDecision
import com.example.skinscript.installer.SkinInstaller
import com.example.skinscript.shizuku.ShizukuFileBackend
import com.example.skinscript.shizuku.ShizukuManager
import com.example.skinscript.shizuku.ShizukuState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException

data class OverwritePromptData(
    val fileName: String,
    val targetPath: String,
    val deferredResult: CompletableDeferred<OverwriteDecision>
)

data class ConflictPromptData(
    val conflictGroup: ConflictGroup,
    val deferredResult: CompletableDeferred<SkinPackage?>
)

class SkinInstallerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SkinInstallerViewModel"
    }

    private val context = application.applicationContext
    val shizukuManager = ShizukuManager(context)
    private val settingsRepository = SettingsRepository(context)
    private val zipAnalyzer = ZipAnalyzer(context)
    private val skinStorageManager = SkinStorageManager(context)
    private val extractedSkinsRepository = ExtractedSkinsRepository(context)
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

    val savedExtractedSkins: StateFlow<List<ExtractedSkinRecord>> = extractedSkinsRepository.savedSkins
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _loadedPackages = MutableStateFlow<List<SkinPackage>>(emptyList())
    val loadedPackages: StateFlow<List<SkinPackage>> = _loadedPackages.asStateFlow()

    val skinPackage: StateFlow<SkinPackage?> = MutableStateFlow<SkinPackage?>(null).apply {
        viewModelScope.launch {
            _loadedPackages.collect { pkgs ->
                value = pkgs.firstOrNull()
            }
        }
    }.asStateFlow()

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

    private val _conflictPrompt = MutableStateFlow<ConflictPromptData?>(null)
    val conflictPrompt: StateFlow<ConflictPromptData?> = _conflictPrompt.asStateFlow()

    private val _skinzipsList = MutableStateFlow<List<File>>(emptyList())
    val skinzipsList: StateFlow<List<File>> = _skinzipsList.asStateFlow()

    init {
        shizukuManager.init()
        refreshSkinZips()
    }

    override fun onCleared() {
        super.onCleared()
        cancelPendingPrompts()
        shizukuManager.destroy()
    }

    fun loadZip(uri: Uri) {
        loadZips(listOf(uri))
    }

    fun loadZips(uris: List<Uri>) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _analysisError.value = null
            _installSummary.value = null

            val analyzed = mutableListOf<SkinPackage>()
            val errors = mutableListOf<String>()

            for (uri in uris) {
                zipAnalyzer.analyze(uri).fold(
                    onSuccess = { pkg ->
                        if (pkg.hasValidAssets) {
                            analyzed.add(pkg)
                        } else {
                            errors.add("${pkg.displayName}: No valid game folders (Art, Audio, UI) detected.")
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to analyze ZIP: $uri", error)
                        errors.add("Failed to analyze $uri: ${error.localizedMessage ?: "Unknown error"}")
                    }
                )
            }

            _loadedPackages.value = analyzed
            if (errors.isNotEmpty() && analyzed.isEmpty()) {
                _analysisError.value = errors.joinToString("\n")
            } else if (errors.isNotEmpty()) {
                _analysisError.value = "Some packages could not be loaded:\n" + errors.joinToString("\n")
            }
            _isAnalyzing.value = false
        }
    }

    fun removePackage(pkg: SkinPackage) {
        _loadedPackages.value = _loadedPackages.value.filter { it != pkg }
    }

    fun clearLoadedPackages() {
        _loadedPackages.value = emptyList()
    }

    fun getSkinZipsDirectoryPath(): String {
        return skinStorageManager.getSkinZipsDirectory().absolutePath
    }

    fun refreshSkinZips() {
        viewModelScope.launch(Dispatchers.IO) {
            _skinzipsList.value = skinStorageManager.listSkinZips()
        }
    }

    fun importZipsToSkinZips(uris: List<Uri>, onCompleted: ((Int) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val imported = skinStorageManager.importZips(uris)
            _skinzipsList.value = skinStorageManager.listSkinZips()
            withContext(Dispatchers.Main) {
                onCompleted?.invoke(imported.size)
            }
        }
    }

    fun deleteSkinZip(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            skinStorageManager.deleteZip(file)
            _skinzipsList.value = skinStorageManager.listSkinZips()
        }
    }

    fun loadSelectedSkinZips(files: List<File>) {
        if (files.isEmpty()) return
        loadZips(files.map { skinStorageManager.getFileUri(it) })
    }

    fun loadFromSkinZipsFolder() {
        val zips = skinStorageManager.listSkinZips()
        if (zips.isEmpty()) {
            _analysisError.value = "No ZIP archives found in skinzips folder."
            return
        }
        loadZips(zips.map { skinStorageManager.getFileUri(it) })
    }

    fun reextractAllSkins() {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _analysisError.value = null
            val saved = savedExtractedSkins.value
            if (saved.isEmpty()) {
                val zips = skinStorageManager.listSkinZips()
                if (zips.isEmpty()) {
                    _analysisError.value = "No saved extracted skins or skinzips found to re-extract."
                    _isAnalyzing.value = false
                    return@launch
                }
                loadZips(zips.map { skinStorageManager.getFileUri(it) })
                return@launch
            }

            val urisToLoad = mutableListOf<Uri>()
            for (record in saved) {
                val file = record.filePath?.let { File(it) }
                if (file != null && file.exists()) {
                    urisToLoad.add(skinStorageManager.getFileUri(file))
                } else {
                    val inDirZip = File(skinStorageManager.getSkinZipsDirectory(), "${record.displayName}.zip")
                    if (inDirZip.exists()) {
                        urisToLoad.add(skinStorageManager.getFileUri(inDirZip))
                    } else {
                        val inDirExact = File(skinStorageManager.getSkinZipsDirectory(), record.displayName)
                        if (inDirExact.exists()) {
                            urisToLoad.add(skinStorageManager.getFileUri(inDirExact))
                        } else {
                            try {
                                urisToLoad.add(Uri.parse(record.fileUriString))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            if (urisToLoad.isEmpty()) {
                _analysisError.value = "Could not locate the saved skin zip files in storage."
                _isAnalyzing.value = false
                return@launch
            }

            loadZips(urisToLoad.distinct())
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
                    "Destination is accessible and writable (Exists: $exists)"
                } else {
                    "Destination test returned non-writable: $path"
                }
            } catch (e: Throwable) {
                _testAccessResult.value = "Error testing destination: ${e.localizedMessage}"
            }
            _isTestingAccess.value = false
        }
    }

    fun startInstall() {
        if (_isInstalling.value) return
        val currentPackages = _loadedPackages.value
        if (currentPackages.isEmpty()) return

        val dest = destinationPath.value
        val mode = overwriteMode.value

        installJob = viewModelScope.launch {
            _isInstalling.value = true
            _installSummary.value = null

            // 1. Conflict resolution across packages
            val resolvedPackages = resolveDuplicateConflicts(currentPackages)
            if (resolvedPackages.isEmpty()) {
                _isInstalling.value = false
                return@launch
            }

            val totalFilesAcrossAll = resolvedPackages.sumOf { it.totalFiles }
            _installProgress.value = InstallProgress(
                currentFileName = "Starting...",
                completedCount = 0,
                totalCount = totalFilesAcrossAll,
                isIndeterminate = true
            )

            try {
                val summary = installer.installBatch(
                    packages = resolvedPackages,
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

                // Record successfully installed packages into registry
                if (summary.success > 0) {
                    extractedSkinsRepository.recordExtracted(resolvedPackages)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Installation cancelled by user.")
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected error during installation", e)
                _analysisError.value = "Installation failed: ${e.localizedMessage}"
            } finally {
                _isInstalling.value = false
                _overwritePrompt.value = null
                _conflictPrompt.value = null
            }
        }
    }

    private suspend fun resolveDuplicateConflicts(packages: List<SkinPackage>): List<SkinPackage> {
        if (packages.size <= 1) return packages

        val conflictGroups = findConflictGroups(packages)
        if (conflictGroups.isEmpty()) return packages

        val excluded = mutableSetOf<SkinPackage>()

        for (group in conflictGroups) {
            val deferred = CompletableDeferred<SkinPackage?>()
            _conflictPrompt.value = ConflictPromptData(group, deferred)
            val chosen = deferred.await()
            _conflictPrompt.value = null

            if (chosen != null) {
                // Keep only the chosen package from this group
                for (p in group.packages) {
                    if (p != chosen) {
                        excluded.add(p)
                    }
                }
            } else {
                // User skipped entire conflicting group
                excluded.addAll(group.packages)
            }
        }

        return packages.filter { it !in excluded }
    }

    fun findConflictGroups(packages: List<SkinPackage>): List<ConflictGroup> =
        ConflictDetector.findConflictGroups(packages)

    fun respondToConflictPrompt(chosenPackage: SkinPackage?) {
        val currentPrompt = _conflictPrompt.value
        if (currentPrompt != null && currentPrompt.deferredResult.isActive) {
            currentPrompt.deferredResult.complete(chosenPackage)
        }
    }

    fun respondToOverwritePrompt(decision: OverwriteDecision) {
        val currentPrompt = _overwritePrompt.value
        if (currentPrompt != null && currentPrompt.deferredResult.isActive) {
            currentPrompt.deferredResult.complete(decision)
        }
    }

    fun cancelInstall() {
        cancelPendingPrompts()
        installJob?.cancel()
        _isInstalling.value = false
    }

    private fun cancelPendingPrompts() {
        _overwritePrompt.value?.let { prompt ->
            if (prompt.deferredResult.isActive) {
                prompt.deferredResult.cancel(CancellationException("Prompt dismissed or cancelled."))
            }
            _overwritePrompt.value = null
        }
        _conflictPrompt.value?.let { prompt ->
            if (prompt.deferredResult.isActive) {
                prompt.deferredResult.cancel(CancellationException("Prompt dismissed or cancelled."))
            }
            _conflictPrompt.value = null
        }
    }

    fun dismissSummary() {
        _installSummary.value = null
    }

    fun dismissError() {
        _analysisError.value = null
    }
}