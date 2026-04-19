package com.example.parallaxwallpaper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.parallaxwallpaper.data.WallpaperPreferences
import com.example.parallaxwallpaper.data.WallpaperSettings
import com.example.parallaxwallpaper.depth.DepthEstimator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = WallpaperPreferences(app)

    val settings: StateFlow<WallpaperSettings> = prefs.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WallpaperSettings()
    )

    /** True when the ONNX model file is present in assets. */
    val modelReady: Boolean = DepthEstimator.isModelPresent(app)

    private val _imageCopying = MutableStateFlow(false)
    val imageCopying: StateFlow<Boolean> = _imageCopying.asStateFlow()

    fun pickImage(uri: Uri) {
        viewModelScope.launch {
            _imageCopying.value = true
            val dst = File(getApplication<Application>().filesDir, "wallpaper_source.jpg")
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                prefs.setImagePath(dst.absolutePath)
            } finally {
                _imageCopying.value = false
            }
        }
    }

    fun setParallaxStrength(value: Float)  { viewModelScope.launch { prefs.setParallaxStrength(value) } }
    fun setTargetFps(fps: Int)             { viewModelScope.launch { prefs.setTargetFps(fps) } }
    fun setUseNnapi(enabled: Boolean)      { viewModelScope.launch { prefs.setUseNnapi(enabled) } }
}
