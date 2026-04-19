package com.example.parallaxwallpaper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("wallpaper_prefs")

data class WallpaperSettings(
    val imagePath: String?   = null,
    val parallaxStrength: Float = 0.04f,
    val targetFps: Int       = 60,
    val useNnapi: Boolean    = false,   // hardware-accelerated ONNX inference via NNAPI
)

class WallpaperPreferences(private val context: Context) {

    companion object {
        private val KEY_IMAGE_PATH        = stringPreferencesKey("image_path")
        private val KEY_PARALLAX_STRENGTH = floatPreferencesKey("parallax_strength")
        private val KEY_TARGET_FPS        = intPreferencesKey("target_fps")
        private val KEY_USE_NNAPI         = intPreferencesKey("use_nnapi")
    }

    val settingsFlow: Flow<WallpaperSettings> = context.dataStore.data.map { prefs ->
        WallpaperSettings(
            imagePath        = prefs[KEY_IMAGE_PATH],
            parallaxStrength = prefs[KEY_PARALLAX_STRENGTH] ?: 0.04f,
            targetFps        = prefs[KEY_TARGET_FPS] ?: 60,
            useNnapi         = (prefs[KEY_USE_NNAPI] ?: 0) != 0,
        )
    }

    suspend fun setImagePath(path: String)         { context.dataStore.edit { it[KEY_IMAGE_PATH] = path } }
    suspend fun setParallaxStrength(value: Float)  { context.dataStore.edit { it[KEY_PARALLAX_STRENGTH] = value } }
    suspend fun setTargetFps(fps: Int)             { context.dataStore.edit { it[KEY_TARGET_FPS] = fps } }
    suspend fun setUseNnapi(enabled: Boolean)      { context.dataStore.edit { it[KEY_USE_NNAPI] = if (enabled) 1 else 0 } }
}
