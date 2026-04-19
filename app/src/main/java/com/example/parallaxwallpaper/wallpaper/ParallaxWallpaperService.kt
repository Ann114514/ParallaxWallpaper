package com.example.parallaxwallpaper.wallpaper

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.example.parallaxwallpaper.data.WallpaperPreferences
import com.example.parallaxwallpaper.depth.DepthEstimator
import com.example.parallaxwallpaper.sensor.GyroscopeSmoother
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    inner class ParallaxEngine : Engine() {

        private val TAG = "ParallaxWallpaperEngine"

        // Dedicated render thread – every GL call must happen here
        private val renderThread = HandlerThread("ParallaxRender").apply { start() }
        private val renderHandler = Handler(renderThread.looper)

        private val glEngine = com.example.parallaxwallpaper.wallpaper.ParallaxEngine()
        private val gyro by lazy { GyroscopeSmoother(this@ParallaxWallpaperService) }
        private val prefs by lazy { WallpaperPreferences(this@ParallaxWallpaperService) }

        // Created lazily with the NNAPI flag loaded from prefs
        private var depthEstimator: DepthEstimator? = null

        private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private var surfaceReady = false
        private var frameScheduled = false
        private var targetFps = 60
        private val frameDurationMs get() = 1000L / targetFps

        // ── Surface callbacks ──────────────────────────────────────────────

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            renderHandler.post {
                if (!surfaceReady) {
                    glEngine.init(holder.surface, w, h)
                    surfaceReady = true
                    loadWallpaperAsync()
                } else {
                    glEngine.onSurfaceChanged(w, h)
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            frameScheduled = false
            renderHandler.post { glEngine.release() }
        }

        // ── Visibility ─────────────────────────────────────────────────────

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                gyro.start()
                scheduleFrame()
            } else {
                gyro.stop()
                renderHandler.removeCallbacksAndMessages(null)
                frameScheduled = false
            }
        }

        // ── Image + depth loading ──────────────────────────────────────────

        private fun loadWallpaperAsync() {
            serviceScope.launch {
                val settings = prefs.settingsFlow.first()
                targetFps = settings.targetFps
                glEngine.parallaxStrength = settings.parallaxStrength

                val bitmap = loadSourceBitmap(settings.imagePath) ?: return@launch

                // Upload raw image immediately so the wallpaper shows something
                renderHandler.post { glEngine.uploadImage(bitmap) }

                if (!DepthEstimator.isModelPresent(this@ParallaxWallpaperService)) {
                    Log.w(TAG, "ONNX model not found – depth map unavailable")
                    bitmap.recycle()
                    return@launch
                }

                // Build estimator on the fly (respects current NNAPI setting)
                depthEstimator?.close()
                depthEstimator = DepthEstimator(
                    this@ParallaxWallpaperService,
                    useNnapi = settings.useNnapi
                )

                val depth = depthEstimator!!.estimate(bitmap)
                renderHandler.post {
                    glEngine.uploadDepth(depth, DepthEstimator.INPUT_SIZE, DepthEstimator.INPUT_SIZE)
                    bitmap.recycle()
                }
            }
        }

        private fun loadSourceBitmap(path: String?): Bitmap? {
            if (!path.isNullOrEmpty()) {
                try { return BitmapFactory.decodeFile(path) }
                catch (e: Exception) { Log.e(TAG, "Cannot load $path", e) }
            }
            return try {
                val wm = WallpaperManager.getInstance(this@ParallaxWallpaperService)
                wm.drawable?.let {
                    val bmp = Bitmap.createBitmap(
                        it.intrinsicWidth.coerceAtLeast(1080),
                        it.intrinsicHeight.coerceAtLeast(1920),
                        Bitmap.Config.ARGB_8888
                    )
                    android.graphics.Canvas(bmp).also { c ->
                        it.setBounds(0, 0, bmp.width, bmp.height)
                        it.draw(c)
                    }
                    bmp
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot load system wallpaper drawable", e)
                null
            }
        }

        // ── Render loop ────────────────────────────────────────────────────

        private fun scheduleFrame() {
            if (!frameScheduled) {
                frameScheduled = true
                renderHandler.postDelayed(::renderFrame, frameDurationMs)
            }
        }

        private fun renderFrame() {
            frameScheduled = false
            if (!surfaceReady || !isVisible) return
            val tilt = gyro.currentTilt
            glEngine.draw(tilt.x, tilt.y)
            scheduleFrame()
        }

        // ── Cleanup ────────────────────────────────────────────────────────

        override fun onDestroy() {
            super.onDestroy()
            serviceScope.cancel()
            gyro.stop()
            renderThread.quitSafely()
            depthEstimator?.close()
        }
    }
}
