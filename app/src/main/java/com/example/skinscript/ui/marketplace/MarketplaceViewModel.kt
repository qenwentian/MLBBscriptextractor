package com.example.skinscript.ui.marketplace

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.skinscript.data.marketplace.DownloadStatus
import com.example.skinscript.data.marketplace.MarketplaceItem
import com.example.skinscript.data.marketplace.SfileRepository
import com.example.skinscript.data.updater.AppUpdateInfo
import com.example.skinscript.data.updater.AppUpdateManager
import com.example.skinscript.data.updater.UpdateCheckState
import com.example.skinscript.data.updater.UpdateDownloadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MarketplaceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MarketplaceViewModel"
    }

    private val context = application.applicationContext
    private val sfileRepository = SfileRepository(context)
    val appUpdateManager = AppUpdateManager(context)

    // Raw items fetched from current source (user repository or global search)
    private val _rawItems = MutableStateFlow<List<MarketplaceItem>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedHeroFilter = MutableStateFlow<String?>(null)
    val selectedHeroFilter: StateFlow<String?> = _selectedHeroFilter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _allIndexedItems = MutableStateFlow<List<MarketplaceItem>>(emptyList())
    val allIndexedItems: StateFlow<List<MarketplaceItem>> = _allIndexedItems.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    // Download state map by item ID
    private val _downloadStates = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadStatus>> = _downloadStates.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    // App Updater states
    private val _updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState.asStateFlow()

    private val _updateDownloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val updateDownloadState: StateFlow<UpdateDownloadState> = _updateDownloadState.asStateFlow()

    private var searchJob: Job? = null
    private var indexJob: Job? = null

    init {
        loadInitialPage()
        startIndexingAllPages()
        checkForAppUpdates()
    }

    private fun startIndexingAllPages() {
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            sfileRepository.indexAllUserPages { batch ->
                _allIndexedItems.value = batch
                if (_searchQuery.value.isEmpty() && _selectedHeroFilter.value == null && _rawItems.value.size <= 25) {
                    _rawItems.value = batch
                }
            }
        }
    }

    fun loadInitialPage() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _currentPage.value = 1

            val query = _searchQuery.value.trim()
            if (query.isNotEmpty()) {
                // Instant local filter on indexed items
                val cached = _allIndexedItems.value.filter { it.title.contains(query, ignoreCase = true) }
                if (cached.isNotEmpty()) {
                    _rawItems.value = cached
                    _isLoading.value = false
                    return@launch
                }
            }

            val result = if (query.isEmpty()) {
                sfileRepository.fetchUserFiles(page = 1)
            } else {
                sfileRepository.searchFiles(query = query, page = 1)
            }

            result.fold(
                onSuccess = { page ->
                    _rawItems.value = page.items
                    _currentPage.value = page.currentPage
                    _totalPages.value = page.totalPages
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed loading initial page", error)
                    _errorMessage.value = "Failed to load skins: ${error.localizedMessage ?: "Network error"}"
                }
            )
            _isLoading.value = false
        }
    }

    fun loadNextPage() {
        if (_isLoadingMore.value || _isLoading.value) return
        if (_currentPage.value >= _totalPages.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true
            val nextPage = _currentPage.value + 1
            val query = _searchQuery.value.trim()

            val result = if (query.isEmpty()) {
                sfileRepository.fetchUserFiles(page = nextPage)
            } else {
                sfileRepository.searchFiles(query = query, page = nextPage)
            }

            result.fold(
                onSuccess = { page ->
                    val combined = (_rawItems.value + page.items).distinctBy { it.id }
                    _rawItems.value = combined
                    _currentPage.value = page.currentPage
                    _totalPages.value = page.totalPages
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed loading page $nextPage", error)
                }
            )
            _isLoadingMore.value = false
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(200) // Fast debounce typing
            if (query.isBlank()) {
                val cached = _allIndexedItems.value
                if (cached.isNotEmpty()) {
                    _rawItems.value = cached
                } else {
                    loadInitialPage()
                }
            } else {
                // Instantly search in full indexed dataset
                val localMatches = _allIndexedItems.value.filter { it.title.contains(query, ignoreCase = true) }
                if (localMatches.isNotEmpty()) {
                    _rawItems.value = localMatches
                } else {
                    // Fallback to online sfile search
                    loadInitialPage()
                }
            }
        }
    }

    fun selectHeroFilter(hero: String?) {
        _selectedHeroFilter.value = hero
    }

    val displayedItems: List<MarketplaceItem>
        get() {
            val query = _searchQuery.value.trim()
            val hero = _selectedHeroFilter.value
            val sourceList = if (_allIndexedItems.value.isNotEmpty()) _allIndexedItems.value else _rawItems.value

            return sourceList.filter { item ->
                val matchesHero = if (hero == null) true else item.title.contains(hero, ignoreCase = true)
                val matchesQuery = if (query.isEmpty()) true else item.title.contains(query, ignoreCase = true)
                matchesHero && matchesQuery
            }
        }

    fun startDownload(item: MarketplaceItem) {
        if (downloadJobs[item.id]?.isActive == true) return

        val job = viewModelScope.launch {
            updateItemStatus(item.id, DownloadStatus.Preparing(secondsLeft = 5))

            val result = sfileRepository.resolveAndDownloadZip(
                item = item,
                onCountdown = { sec ->
                    updateItemStatus(
                        item.id,
                        DownloadStatus.Preparing(
                            secondsLeft = sec,
                            message = if (sec > 0) "Preparing download ($sec s)..." else "Connecting to download server..."
                        )
                    )
                },
                onProgress = { progress, downloaded, total, speed ->
                    updateItemStatus(
                        item.id,
                        DownloadStatus.Downloading(
                            progress = progress,
                            bytesDownloaded = downloaded,
                            totalBytes = total,
                            speedFormatted = speed
                        )
                    )
                }
            )

            result.fold(
                onSuccess = { (file, uri) ->
                    updateItemStatus(item.id, DownloadStatus.Completed(fileUri = uri, localFile = file))
                },
                onFailure = { error ->
                    Log.e(TAG, "Download failed for ${item.title}", error)
                    updateItemStatus(item.id, DownloadStatus.Failed(error.localizedMessage ?: "Download failed"))
                }
            )
        }

        downloadJobs[item.id] = job
    }

    fun cancelDownload(itemId: String) {
        downloadJobs[itemId]?.cancel()
        downloadJobs.remove(itemId)
        updateItemStatus(itemId, DownloadStatus.Idle)
    }

    private fun updateItemStatus(itemId: String, status: DownloadStatus) {
        val current = _downloadStates.value.toMutableMap()
        current[itemId] = status
        _downloadStates.value = current
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    // --- Auto Update Logic ---
    fun checkForAppUpdates() {
        viewModelScope.launch {
            _updateCheckState.value = UpdateCheckState.Checking
            val result = appUpdateManager.checkForUpdates()
            result.fold(
                onSuccess = { updateInfo ->
                    if (updateInfo != null) {
                        _updateCheckState.value = UpdateCheckState.UpdateAvailable(updateInfo)
                    } else {
                        _updateCheckState.value = UpdateCheckState.UpToDate
                    }
                },
                onFailure = { error ->
                    _updateCheckState.value = UpdateCheckState.Error(error.localizedMessage ?: "Failed to check update")
                }
            )
        }
    }

    fun downloadAndInstallUpdate(info: AppUpdateInfo) {
        viewModelScope.launch {
            _updateDownloadState.value = UpdateDownloadState.Downloading(0f, 0L, 0L)
            val result = appUpdateManager.downloadApk(info) { progress, downloaded, total ->
                _updateDownloadState.value = UpdateDownloadState.Downloading(progress, downloaded, total)
            }
            result.fold(
                onSuccess = { apkFile ->
                    _updateDownloadState.value = UpdateDownloadState.ReadyToInstall(apkFile)
                    appUpdateManager.promptInstallApk(apkFile)
                },
                onFailure = { error ->
                    _updateDownloadState.value = UpdateDownloadState.Error(error.localizedMessage ?: "APK download failed")
                }
            )
        }
    }

    fun dismissUpdateDialog() {
        _updateCheckState.value = UpdateCheckState.Idle
        _updateDownloadState.value = UpdateDownloadState.Idle
    }
}
