package com.example.skinscript.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "skin_installer_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        const val DEFAULT_DESTINATION_PATH =
            "/storage/emulated/0/Android/data/com.mobilelegends/files/dragon2017/assets"

        private val KEY_DESTINATION_PATH = stringPreferencesKey("destination_path")
        private val KEY_OVERWRITE_MODE = stringPreferencesKey("overwrite_mode")
    }

    val destinationPath: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_DESTINATION_PATH] ?: DEFAULT_DESTINATION_PATH
    }

    val overwriteMode: Flow<OverwriteMode> = context.dataStore.data.map { preferences ->
        val modeName = preferences[KEY_OVERWRITE_MODE] ?: OverwriteMode.ASK_EVERY_TIME.name
        try {
            OverwriteMode.valueOf(modeName)
        } catch (e: Exception) {
            OverwriteMode.ASK_EVERY_TIME
        }
    }

    suspend fun setDestinationPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DESTINATION_PATH] = path.trim()
        }
    }

    suspend fun setOverwriteMode(mode: OverwriteMode) {
        context.dataStore.edit { preferences ->
            preferences[KEY_OVERWRITE_MODE] = mode.name
        }
    }
}
