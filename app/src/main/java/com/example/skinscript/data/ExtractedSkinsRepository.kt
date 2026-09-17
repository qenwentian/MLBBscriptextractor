package com.example.skinscript.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class ExtractedSkinRecord(
    val id: String,
    val displayName: String,
    val heroName: String?,
    val filePath: String?,
    val fileUriString: String,
    val extractedAt: Long,
    val assetPaths: List<String>
)

private val Context.extractedSkinsDataStore: DataStore<Preferences> by preferencesDataStore(name = "extracted_skins_registry")

class ExtractedSkinsRepository(private val context: Context) {

    companion object {
        private val KEY_SAVED_SKINS = stringPreferencesKey("saved_extracted_skins_json")
    }

    val savedSkins: Flow<List<ExtractedSkinRecord>> = context.extractedSkinsDataStore.data.map { preferences ->
        val jsonString = preferences[KEY_SAVED_SKINS] ?: "[]"
        deserializeList(jsonString)
    }

    suspend fun recordExtracted(packages: List<SkinPackage>) {
        context.extractedSkinsDataStore.edit { preferences ->
            val existing = deserializeList(preferences[KEY_SAVED_SKINS] ?: "[]").toMutableList()
            val now = System.currentTimeMillis()

            for (pkg in packages) {
                // Determine file path if sourceUri is file or has path
                val filePath = if (pkg.sourceUri?.scheme == "file") pkg.sourceUri?.path else (pkg.unwrappedFile?.absolutePath)
                val record = ExtractedSkinRecord(
                    id = pkg.displayName,
                    displayName = pkg.displayName,
                    heroName = pkg.detectedHero,
                    filePath = filePath,
                    fileUriString = pkg.sourceUri?.toString() ?: "",
                    extractedAt = now,
                    assetPaths = pkg.allAssetFiles.map { it.relativeAssetPath }
                )

                // Replace if existing with same id/displayName
                existing.removeAll { it.id == record.id || it.displayName == record.displayName }
                existing.add(0, record)
            }

            preferences[KEY_SAVED_SKINS] = serializeList(existing)
        }
    }

    suspend fun removeRecord(id: String) {
        context.extractedSkinsDataStore.edit { preferences ->
            val existing = deserializeList(preferences[KEY_SAVED_SKINS] ?: "[]").toMutableList()
            existing.removeAll { it.id == id }
            preferences[KEY_SAVED_SKINS] = serializeList(existing)
        }
    }

    suspend fun clearAll() {
        context.extractedSkinsDataStore.edit { preferences ->
            preferences[KEY_SAVED_SKINS] = "[]"
        }
    }

    private fun serializeList(list: List<ExtractedSkinRecord>): String {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("displayName", item.displayName)
            obj.put("heroName", item.heroName ?: JSONObject.NULL)
            obj.put("filePath", item.filePath ?: JSONObject.NULL)
            obj.put("fileUriString", item.fileUriString)
            obj.put("extractedAt", item.extractedAt)

            val assetsArray = JSONArray()
            item.assetPaths.forEach { assetsArray.put(it) }
            obj.put("assetPaths", assetsArray)

            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    private fun deserializeList(jsonString: String): List<ExtractedSkinRecord> {
        return try {
            val jsonArray = JSONArray(jsonString)
            val result = mutableListOf<ExtractedSkinRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val assetList = mutableListOf<String>()
                val assetsArr = obj.optJSONArray("assetPaths")
                if (assetsArr != null) {
                    for (j in 0 until assetsArr.length()) {
                        assetList.add(assetsArr.getString(j))
                    }
                }

                result.add(
                    ExtractedSkinRecord(
                        id = obj.getString("id"),
                        displayName = obj.getString("displayName"),
                        heroName = if (obj.isNull("heroName")) null else obj.getString("heroName"),
                        filePath = if (obj.isNull("filePath")) null else obj.getString("filePath"),
                        fileUriString = obj.getString("fileUriString"),
                        extractedAt = obj.optLong("extractedAt", 0L),
                        assetPaths = assetList
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }
}
