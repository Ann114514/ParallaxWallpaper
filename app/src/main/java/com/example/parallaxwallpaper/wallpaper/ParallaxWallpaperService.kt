package com.example.parallaxwallpaper.wallpaper

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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

        private val renderThread = HandlerThread("ParallaxRender").apply { start() }
        private val renderHandler = Handler(renderThread.looper)

        private val glEngine = com.example.parallaxwallpaper.wallpaper.ParallaxEngine()
        private val gyro by lazy { GyroscopeSmoother(this@ParallaxWallpaperService) }
        private val prefs by lazy { WallpaperPreferences(this@ParallaxWallpaperService) }

        private var depthEstimator: DepthEstimator? = null
        private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private var surfaceReady = false
        private var frameScheduled = false
        private var targetFps = 60
        private val frameDurationMs get() = 1000L / targetFps.coerceAtLeast(1)

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
            renderHandler.removeCallbacksAndMessages(null)
            renderHandler.post { glEngine.release() }
        }

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

        private fun loadWallpaperAsync() {
            serviceScope.launch {
                val settings = prefs.settingsFlow.first()
                targetFps = settings.targetFps
                glEngine.parallaxStrength = settings.parallaxStrength

                val bitmap = loadSourceBitmap(settings.imagePath) ?: return@launch

                renderHandler.post {
                    if (surfaceReady) glEngine.uploadImage(bitmap)
                }

                if (!DepthEstimator.isModelPresent(this@ParallaxWallpaperService)) {
                    Log.w(TAG, "ONNX model not found – depth map unavailable")
                    bitmap.recycle()
                    return@launch
                }

                depthEstimator?.close()
                depthEstimator = DepthEstimator(
                    this@ParallaxWallpaperService,
                    useNnapi = settings.useNnapi
                )

                val depth = depthEstimator!!.estimate(bitmap)

                renderHandler.post {
                    if (surfaceReady) {
                        glEngine.uploadDepth(
                            depth,
                            DepthEstimator.INPUT_SIZE,
                            DepthEstimator.INPUT_SIZE
                        )
                    }
                    bitmap.recycle()
                }
            }
        }

        private fun loadSourceBitmap(path: String?): Bitmap? {
            if (!path.isNullOrEmpty()) {
                try {
                    decodeFileRespectingExif(path)?.let { return it }
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot load $path", e)
                }
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

        /**
         * BitmapFactory ignores JPEG EXIF orientation. Normalize it here so
         * photos selected from the camera/gallery have the same orientation
         * inside the live wallpaper as they do in the gallery app.
         */
        private fun decodeFileRespectingExif(path: String): Bitmap? {
            val decoded = BitmapFactory.decodeFile(path) ?: return null

            val orientation = try {
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.setRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return decoded
            }

            val corrected = Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                matrix,
                true
            )

            if (corrected !== decoded) decoded.recycle()
            return corrected
        }

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

        override fun onDestroy() {
            super.onDestroy()
            serviceScope.cancel()
            gyro.stop()
            renderHandler.removeCallbacksAndMessages(null)
            renderThread.quitSafely()
            depthEstimator?.close()
        }
    }
}
