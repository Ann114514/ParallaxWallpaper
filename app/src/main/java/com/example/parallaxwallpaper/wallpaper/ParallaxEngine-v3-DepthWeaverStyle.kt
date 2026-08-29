package com.example.parallaxwallpaper.wallpaper

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.tan

/**
 * DepthWeaver-inspired native Android renderer.
 *
 * Instead of shifting texture UVs per pixel, the wallpaper is converted into
 * a real 2.5D height-field mesh. The depth map changes vertex Z positions and
 * the gyroscope rotates the whole mesh in 3D.
 *
 * This implementation is original Android/OpenGL ES code; it does not copy
 * DepthWeaver source code.
 */
class ParallaxEngine {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var vertexBuffer = 0
    private var indexBuffer = 0
    private var imageTexture = 0
    private var indexCount = 0

    private var aPosition = -1
    private var aTexCoord = -1
    private var uMvp = -1
    private var uImageTexture = -1

    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var imageWidth = 1
    private var imageHeight = 1

    private var lastDepth: FloatArray? = null
    private var lastDepthW = 0
    private var lastDepthH = 0

    var parallaxStrength: Float = 0.04f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    companion object {
        private const val TAG = "ParallaxEngineV3"

        // 128 x 128 cells = 16,641 vertices / 32,768 triangles.
        // This remains safely below the GLES2 unsigned-short index limit.
        private const val GRID_SEGMENTS = 128

        private const val CAMERA_Z = 3.0f
        private const val FOV_Y_DEG = 45.0f
        private const val OVERSCAN = 1.14f

        private val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uImageTexture;
            varying vec2 vTexCoord;

            void main() {
                gl_FragColor = texture2D(uImageTexture, vTexCoord);
            }
        """.trimIndent()
    }

    fun init(surface: Surface, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)

        setupEgl(surface)
        setupShaders()
        setupTexture()
        setupBuffers()
        updateProjection()

        // A flat mesh is available immediately, before depth inference finishes.
        rebuildMesh(null, 0, 0)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        updateProjection()

        // Center-crop/overscan depends on the screen aspect ratio.
        rebuildMesh(lastDepth, lastDepthW, lastDepthH)
    }

    fun uploadImage(bitmap: Bitmap) {
        imageWidth = bitmap.width.coerceAtLeast(1)
        imageHeight = bitmap.height.coerceAtLeast(1)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // Rebuild so image aspect ratio is preserved instead of stretched.
        rebuildMesh(lastDepth, lastDepthW, lastDepthH)
    }

    fun uploadDepth(depth: FloatArray, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || depth.size < w * h) return

        // DepthEstimator owns/reuses its output buffer. Keep our own copy.
        lastDepth = depth.copyOf(w * h)
        lastDepthW = w
        lastDepthH = h

        rebuildMesh(lastDepth, w, h)
    }

    fun draw(tiltX: Float, tiltY: Float) {
        if (program == 0 || imageTexture == 0 || indexCount == 0) return

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // The depth mesh supplies actual 3D geometry. Gyro now rotates the
        // scene instead of tearing UV coordinates apart.
        val clampedStrength = parallaxStrength.coerceIn(0.01f, 0.12f)
        val maxAngle = 4.0f + clampedStrength * 50.0f // default ~6 degrees

        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, -tiltY.coerceIn(-1f, 1f) * maxAngle, 1f, 0f, 0f)
        Matrix.rotateM(model, 0,  tiltX.coerceIn(-1f, 1f) * maxAngle, 0f, 1f, 0f)

        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture)
        GLES20.glUniform1i(uImageTexture, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)

        val stride = 5 * 4
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, stride, 3 * 4)

        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            indexCount,
            GLES20.GL_UNSIGNED_SHORT,
            0
        )

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (vertexBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
        if (indexBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(indexBuffer), 0)
        if (imageTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(imageTexture), 0)

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        vertexBuffer = 0
        indexBuffer = 0
        imageTexture = 0
        indexCount = 0
    }

    private fun setupEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            "Unable to initialize EGL"
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(
            eglDisplay, configAttribs, 0, configs, 0, configs.size, count, 0
        ) && count[0] > 0) {
            "Unable to choose EGL config"
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            configs[0]!!,
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            configs[0]!!,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL surface" }

        check(EGL14.eglMakeCurrent(
            eglDisplay, eglSurface, eglSurface, eglContext
        )) {
            "Unable to make EGL context current"
        }

        // Ask EGL to synchronize swaps to the display refresh.
        EGL14.eglSwapInterval(eglDisplay, 1)

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    private fun setupShaders() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == GLES20.GL_FALSE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            program = 0
            error("Program link failed: $log")
        }

        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uImageTexture = GLES20.glGetUniformLocation(program, "uImageTexture")
    }

    private fun setupTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        imageTexture = ids[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        // Initialize with an opaque black pixel so drawing before image upload
        // is deterministic.
        val black = ByteBuffer.allocateDirect(4).apply {
            put(0.toByte())
            put(0.toByte())
            put(0.toByte())
            put(255.toByte())
            rewind()
        }
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            1,
            1,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            black
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun setupBuffers() {
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vertexBuffer = ids[0]
        indexBuffer = ids[1]
    }

    private fun updateProjection() {
        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()

        Matrix.perspectiveM(
            projection,
            0,
            FOV_Y_DEG,
            aspect,
            0.1f,
            100f
        )

        Matrix.setLookAtM(
            view,
            0,
            0f, 0f, CAMERA_Z,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
    }

    private fun rebuildMesh(depth: FloatArray?, depthW: Int, depthH: Int) {
        if (vertexBuffer == 0 || indexBuffer == 0) return

        val hasDepth = depth != null &&
            depthW > 1 &&
            depthH > 1 &&
            depth.size >= depthW * depthH

        val segments = if (hasDepth) GRID_SEGMENTS else 1
        val cols = segments + 1
        val rows = segments + 1

        val screenAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()

        val halfFovRad = Math.toRadians((FOV_Y_DEG / 2.0).toDouble())
        val visibleHalfH = CAMERA_Z * tan(halfFovRad).toFloat()
        val visibleHalfW = visibleHalfH * screenAspect

        // Plane base half-height = 1, half-width = imageAspect.
        // Scale until the plane covers the screen while preserving image ratio.
        val coverScale = max(
            visibleHalfH,
            visibleHalfW / imageAspect.coerceAtLeast(0.01f)
        ) * OVERSCAN

        val halfW = imageAspect * coverScale
        val halfH = coverScale

        val softened = if (hasDepth) {
            softenDepthEdges(depth!!, depthW, depthH)
        } else {
            null
        }

        val strength = parallaxStrength.coerceIn(0.01f, 0.12f)
        val depthAmplitude = strength * 3.0f // default 0.12 world units

        val vertices = FloatArray(cols * rows * 5)
        var vi = 0

        for (gy in 0..segments) {
            val v = gy.toFloat() / segments.toFloat()
            val py = (1f - 2f * v) * halfH

            for (gx in 0..segments) {
                val u = gx.toFloat() / segments.toFloat()
                val px = (2f * u - 1f) * halfW

                val d = if (softened != null) {
                    sampleBilinear(softened, depthW, depthH, u, v)
                } else {
                    0.5f
                }

                // DepthEstimator convention: 0 = near, 1 = far.
                // Center around zero to avoid moving the entire plane.
                val near = 1f - d
                val pz = (near - 0.5f) * depthAmplitude

                vertices[vi++] = px
                vertices[vi++] = py
                vertices[vi++] = pz
                vertices[vi++] = u
                vertices[vi++] = v
            }
        }

        val indices = ShortArray(segments * segments * 6)
        var ii = 0
        for (y in 0 until segments) {
            for (x in 0 until segments) {
                val i0 = y * cols + x
                val i1 = i0 + 1
                val i2 = i0 + cols
                val i3 = i2 + 1

                indices[ii++] = i0.toShort()
                indices[ii++] = i2.toShort()
                indices[ii++] = i1.toShort()

                indices[ii++] = i1.toShort()
                indices[ii++] = i2.toShort()
                indices[ii++] = i3.toShort()
            }
        }

        val vertexBytes = ByteBuffer
            .allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
        val vertexFloatBuffer: FloatBuffer = vertexBytes.asFloatBuffer()
        vertexFloatBuffer.put(vertices).rewind()

        val indexBytes = ByteBuffer
            .allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
        val indexShortBuffer: ShortBuffer = indexBytes.asShortBuffer()
        indexShortBuffer.put(indices).rewind()

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            vertices.size * 4,
            vertexFloatBuffer,
            GLES20.GL_STATIC_DRAW
        )

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * 2,
            indexShortBuffer,
            GLES20.GL_STATIC_DRAW
        )

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        indexCount = indices.size
    }

    /**
     * High depth gradients are exactly where height-field geometry tends to
     * form ugly stretched "walls". We softly feather only those areas.
     *
     * This is intentionally a one-time CPU preprocessing step, not a costly
     * multi-sample blur on every rendered frame.
     */
    private fun softenDepthEdges(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)

        fun at(x: Int, y: Int): Float {
            val xx = x.coerceIn(0, w - 1)
            val yy = y.coerceIn(0, h - 1)
            return src[yy * w + xx]
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val center = at(x, y)

                val left = at(x - 1, y)
                val right = at(x + 1, y)
                val up = at(x, y - 1)
                val down = at(x, y + 1)

                val grad = max(
                    max(kotlin.math.abs(right - left), kotlin.math.abs(down - up)),
                    max(
                        kotlin.math.abs(center - left),
                        kotlin.math.abs(center - right)
                    )
                )

                // 3x3 Gaussian-ish blur.
                val blurred = (
                    at(x - 1, y - 1) + 2f * at(x, y - 1) + at(x + 1, y - 1) +
                    2f * at(x - 1, y) + 4f * center + 2f * at(x + 1, y) +
                    at(x - 1, y + 1) + 2f * at(x, y + 1) + at(x + 1, y + 1)
                ) / 16f

                val edge = smoothStep(0.035f, 0.16f, grad)
                val feather = edge * 0.72f
                out[y * w + x] = center * (1f - feather) + blurred * feather
            }
        }

        return out
    }

    private fun sampleBilinear(
        src: FloatArray,
        w: Int,
        h: Int,
        u: Float,
        v: Float
    ): Float {
        val x = u.coerceIn(0f, 1f) * (w - 1)
        val y = v.coerceIn(0f, 1f) * (h - 1)

        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)

        val tx = x - x0
        val ty = y - y0

        val d00 = src[y0 * w + x0]
        val d10 = src[y0 * w + x1]
        val d01 = src[y1 * w + x0]
        val d11 = src[y1 * w + x1]

        val top = d00 + (d10 - d00) * tx
        val bottom = d01 + (d11 - d01) * tx
        return top + (bottom - top) * ty
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES20.GL_FALSE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            Log.e(TAG, "Shader compile error: $log")
            error("Shader compile failed: $log")
        }
        return shader
    }
}
