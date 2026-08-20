package com.example.skinscript.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

data class ShizukuState(
    val isRunning: Boolean = false,
    val isGranted: Boolean = false,
    val version: Int = 0,
    val statusMessage: String = "Checking Shizuku status..."
) {
    val isReady: Boolean
        get() = isRunning && isGranted
}

class ShizukuManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku Binder Received")
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku Binder Dead")
        _state.value = ShizukuState(
            isRunning = false,
            isGranted = false,
            statusMessage = "Shizuku service stopped"
        )
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Shizuku permission result: $granted")
                refreshStatus()
            }
        }

    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            refreshStatus()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Shizuku listeners", e)
            _state.value = ShizukuState(
                isRunning = false,
                isGranted = false,
                statusMessage = "Shizuku not initialized: ${e.localizedMessage}"
            )
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unregister Shizuku listeners", e)
        }
    }

    fun refreshStatus() {
        try {
            val isRunning = Shizuku.pingBinder()
            if (!isRunning) {
                _state.value = ShizukuState(
                    isRunning = false,
                    isGranted = false,
                    statusMessage = "Shizuku service is not running. Please start Shizuku app."
                )
                return
            }

            val version = runCatching { Shizuku.getVersion() }.getOrDefault(0)
            val isGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

            val message = if (isGranted) {
                "Shizuku running (v$version) & Authorized"
            } else {
                "Shizuku running (v$version) - Permission needed"
            }

            _state.value = ShizukuState(
                isRunning = true,
                isGranted = isGranted,
                version = version,
                statusMessage = message
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking Shizuku status", e)
            _state.value = ShizukuState(
                isRunning = false,
                isGranted = false,
                statusMessage = "Shizuku error: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } else {
                refreshStatus()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
            _state.value = _state.value.copy(
                statusMessage = "Permission request failed: ${e.localizedMessage}"
            )
        }
    }
}
