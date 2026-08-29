package com.example.parallaxwallpaper.wallpaper

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

/**
 * V4: DepthWeaver-style 2.5D renderer for Android live wallpaper.
 *
 * Major differences from the original ParallaxWallpaper:
 *  - real height-field mesh instead of per-pixel UV displacement
 *  - near-maximum GLES2 16-bit mesh density (254x254 cells)
 *  - stronger perspective/depth similar to DepthWeaver defaults
 *  - one-time GPU texture baking around depth discontinuities
 *  - native EGL/OpenGL ES 2.0 rendering; no WebView/Three.js dependency
 */
class ParallaxEngine {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var meshProgram = 0
    private var bakeProgram = 0

    private var vertexBuffer = 0
    private var indexBuffer = 0
    private var indexCount = 0

    private var sourceTexture = 0
    private var depthTexture = 0
    private var bakedTexture = 0
    private var bakeFramebuffer = 0
    private var bakedReady = false

    private var sourceTexWidth = 1
    private var sourceTexHeight = 1

    private var meshPositionLoc = -1
    private var meshTexCoordLoc = -1
    private var meshMvpLoc = -1
    private var meshTextureLoc = -1

    private var bakePositionLoc = -1
    private var bakeSourceLoc = -1
    private var bakeDepthLoc = -1
    private var bakeColorTexelLoc = -1
    private var bakeDepthTexelLoc = -1

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

    private val bakeQuad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
            ))
            rewind()
        }

    companion object {
        private const val TAG = "ParallaxEngineV4"

        // 255 * 255 = 65,025 vertices, just below the GLES2 16-bit index limit.
        private const val GRID_SEGMENTS = 254

        // DepthWeaver uses a much closer camera than our conservative V3.
        private const val CAMERA_Z = 2.0f
        private const val FOV_Y_DEG = 60.0f

        // Rotation needs extra source area so the corners do not expose black.
        private const val OVERSCAN = 1.28f

        // Wallpaper textures do not need full camera-photo resolution.
        private const val MAX_TEXTURE_DIMENSION = 2048

        private val MESH_VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val MESH_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uImageTexture;
            varying vec2 vTexCoord;

            void main() {
                gl_FragColor = texture2D(uImageTexture, vTexCoord);
            }
        """.trimIndent()

        /*
         * A lightweight native equivalent of DepthWeaver's baking pass.
         * It detects depth discontinuities and only blurs those regions.
         *
         * This is run once after a depth map arrives, not every frame.
         */
        private val BAKE_VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vUv;

            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);

                // Source Android textures use v=0 as the top image row.
                // Render the top source row into the bottom FBO row so that
                // sampling the baked texture later with v=0 remains upright.
                vUv = aPosition * 0.5 + 0.5;
            }
        """.trimIndent()

        private val BAKE_FRAGMENT_SHADER = """
            precision mediump float;

            uniform sampler2D uSource;
            uniform sampler2D uDepth;
            uniform vec2 uColorTexel;
            uniform vec2 uDepthTexel;

            varying vec2 vUv;

            float depthAt(vec2 uv) {
                return texture2D(uDepth, clamp(uv, 0.0, 1.0)).r;
            }

            vec4 colorAt(vec2 uv) {
                return texture2D(uSource, clamp(uv, 0.0, 1.0));
            }

            void main() {
                float dc = depthAt(vUv);
                float dl = depthAt(vUv - vec2(uDepthTexel.x, 0.0));
                float dr = depthAt(vUv + vec2(uDepthTexel.x, 0.0));
                float du = depthAt(vUv - vec2(0.0, uDepthTexel.y));
                float dd = depthAt(vUv + vec2(0.0, uDepthTexel.y));

                float gradient = max(
                    max(abs(dr - dl), abs(dd - du)),
                    max(max(abs(dc - dl), abs(dc - dr)),
                        max(abs(dc - du), abs(dc - dd)))
                );

                float edge = smoothstep(0.025, 0.14, gradient);
                vec4 original = colorAt(vUv);

                if (edge < 0.01) {
                    gl_FragColor = original;
                    return;
                }

                // DepthWeaver-like edge texture baking. The blur radius grows
                // only at steep depth boundaries, leaving normal detail sharp.
                vec2 r = uColorTexel * mix(1.5, 5.5, edge);

                vec4 sum = original * 4.0;
                float weight = 4.0;

                sum += colorAt(vUv + vec2( r.x, 0.0)) * 2.0;
                sum += colorAt(vUv + vec2(-r.x, 0.0)) * 2.0;
                sum += colorAt(vUv + vec2(0.0,  r.y)) * 2.0;
                sum += colorAt(vUv + vec2(0.0, -r.y)) * 2.0;
                weight += 8.0;

                vec2 d = r * 0.72;
                sum += colorAt(vUv + vec2( d.x,  d.y));
                sum += colorAt(vUv + vec2(-d.x,  d.y));
                sum += colorAt(vUv + vec2( d.x, -d.y));
                sum += colorAt(vUv + vec2(-d.x, -d.y));
                weight += 4.0;

                vec2 r2 = r * 1.65;
                sum += colorAt(vUv + vec2( r2.x, 0.0)) * 0.65;
                sum += colorAt(vUv + vec2(-r2.x, 0.0)) * 0.65;
                sum += colorAt(vUv + vec2(0.0,  r2.y)) * 0.65;
                sum += colorAt(vUv + vec2(0.0, -r2.y)) * 0.65;
                weight += 2.6;

                vec4 blurred = sum / weight;

                // Do not completely erase the source; feather it around edges.
                float mixAmount = edge * 0.82;
                gl_FragColor = mix(original, blurred, mixAmount);
            }
        """.trimIndent()
    }

    fun init(surface: Surface, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)

        setupEgl(surface)
        setupPrograms()
        setupBuffersAndTextures()
        updateProjection()

        // A flat placeholder mesh exists before ONNX depth inference finishes.
        rebuildMesh(null, 0, 0)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        updateProjection()
        rebuildMesh(lastDepth, lastDepthW, lastDepthH)
    }

    fun uploadImage(bitmap: Bitmap) {
        imageWidth = bitmap.width.coerceAtLeast(1)
        imageHeight = bitmap.height.coerceAtLeast(1)

        val uploadBitmap = makeRenderSizedBitmap(bitmap)
        sourceTexWidth = uploadBitmap.width
        sourceTexHeight = uploadBitmap.height

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uploadBitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (uploadBitmap !== bitmap) {
            uploadBitmap.recycle()
        }

        bakedReady = false
        deleteBakeTarget()

        // Preserve aspect immediately while depth inference runs.
        rebuildMesh(lastDepth, lastDepthW, lastDepthH)

        // If depth was already present (e.g. surface recreation), rebuild bake.
        if (lastDepth != null) {
            allocateAndUploadDepthTexture(lastDepth!!, lastDepthW, lastDepthH)
            runBakePass()
        }
    }

    fun uploadDepth(depth: FloatArray, w: Int, h: Int) {
        if (w <= 1 || h <= 1 || depth.size < w * h) return

        lastDepth = depth.copyOf(w * h)
        lastDepthW = w
        lastDepthH = h

        allocateAndUploadDepthTexture(lastDepth!!, w, h)
        rebuildMesh(lastDepth, w, h)
        runBakePass()
    }

    fun draw(tiltX: Float, tiltY: Float) {
        if (meshProgram == 0 || sourceTexture == 0 || indexCount == 0) return

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(meshProgram)

        val normalizedStrength =
            ((parallaxStrength.coerceIn(0.01f, 0.12f) - 0.01f) / 0.11f)
                .coerceIn(0f, 1f)

        // V3 was about 6°. V4 defaults to ~10° and can reach 16°.
        val maxViewAngleDeg = 8.0f + 8.0f * normalizedStrength

        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(
            model, 0,
            -tiltY.coerceIn(-1f, 1f) * maxViewAngleDeg,
            1f, 0f, 0f
        )
        Matrix.rotateM(
            model, 0,
            tiltX.coerceIn(-1f, 1f) * maxViewAngleDeg,
            0f, 1f, 0f
        )

        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glUniformMatrix4fv(meshMvpLoc, 1, false, mvp, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(
            GLES20.GL_TEXTURE_2D,
            if (bakedReady) bakedTexture else sourceTexture
        )
        GLES20.glUniform1i(meshTextureLoc, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)

        val stride = 5 * 4
        GLES20.glEnableVertexAttribArray(meshPositionLoc)
        GLES20.glEnableVertexAttribArray(meshTexCoordLoc)
        GLES20.glVertexAttribPointer(
            meshPositionLoc, 3, GLES20.GL_FLOAT, false, stride, 0
        )
        GLES20.glVertexAttribPointer(
            meshTexCoordLoc, 2, GLES20.GL_FLOAT, false, stride, 3 * 4
        )

        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            indexCount,
            GLES20.GL_UNSIGNED_SHORT,
            0
        )

        GLES20.glDisableVertexAttribArray(meshPositionLoc)
        GLES20.glDisableVertexAttribArray(meshTexCoordLoc)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        deleteBakeTarget()

        if (meshProgram != 0) GLES20.glDeleteProgram(meshProgram)
        if (bakeProgram != 0) GLES20.glDeleteProgram(bakeProgram)

        if (vertexBuffer != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
        }
        if (indexBuffer != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(indexBuffer), 0)
        }

        val textures = intArrayOf(sourceTexture, depthTexture)
        GLES20.glDeleteTextures(2, textures, 0)

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

        meshProgram = 0
        bakeProgram = 0
        vertexBuffer = 0
        indexBuffer = 0
        sourceTexture = 0
        depthTexture = 0
        indexCount = 0
        bakedReady = false
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

        check(
            EGL14.eglChooseConfig(
                eglDisplay,
                configAttribs,
                0,
                configs,
                0,
                configs.size,
                count,
                0
            ) && count[0] > 0
        ) {
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
        check(eglContext != EGL14.EGL_NO_CONTEXT) {
            "Unable to create EGL context"
        }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            configs[0]!!,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) {
            "Unable to create EGL surface"
        }

        check(
            EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext
            )
        ) {
            "Unable to make EGL context current"
        }

        EGL14.eglSwapInterval(eglDisplay, 1)

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    private fun setupPrograms() {
        meshProgram = linkProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        bakeProgram = linkProgram(BAKE_VERTEX_SHADER, BAKE_FRAGMENT_SHADER)

        meshPositionLoc = GLES20.glGetAttribLocation(meshProgram, "aPosition")
        meshTexCoordLoc = GLES20.glGetAttribLocation(meshProgram, "aTexCoord")
        meshMvpLoc = GLES20.glGetUniformLocation(meshProgram, "uMvp")
        meshTextureLoc = GLES20.glGetUniformLocation(meshProgram, "uImageTexture")

        bakePositionLoc = GLES20.glGetAttribLocation(bakeProgram, "aPosition")
        bakeSourceLoc = GLES20.glGetUniformLocation(bakeProgram, "uSource")
        bakeDepthLoc = GLES20.glGetUniformLocation(bakeProgram, "uDepth")
        bakeColorTexelLoc = GLES20.glGetUniformLocation(bakeProgram, "uColorTexel")
        bakeDepthTexelLoc = GLES20.glGetUniformLocation(bakeProgram, "uDepthTexel")
    }

    private fun setupBuffersAndTextures() {
        val buffers = IntArray(2)
        GLES20.glGenBuffers(2, buffers, 0)
        vertexBuffer = buffers[0]
        indexBuffer = buffers[1]

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        sourceTexture = textures[0]
        depthTexture = textures[1]

        configureTexture(sourceTexture)
        configureTexture(depthTexture)

        // Deterministic black source placeholder.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
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

        // Deterministic mid-depth placeholder.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        val mid = ByteBuffer.allocateDirect(1).apply {
            put(128.toByte())
            rewind()
        }
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            1,
            1,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            mid
        )

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun configureTexture(texture: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
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
    }

    private fun makeRenderSizedBitmap(bitmap: Bitmap): Bitmap {
        val maxDim = max(bitmap.width, bitmap.height)
        if (maxDim <= MAX_TEXTURE_DIMENSION) return bitmap

        val scale = MAX_TEXTURE_DIMENSION.toFloat() / maxDim.toFloat()
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun allocateAndUploadDepthTexture(
        depth: FloatArray,
        w: Int,
        h: Int
    ) {
        val bytes = ByteBuffer.allocateDirect(w * h)
        for (i in 0 until w * h) {
            val v = (depth[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            bytes.put(v.toByte())
        }
        bytes.rewind()

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            w,
            h,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            bytes
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun runBakePass() {
        if (
            bakeProgram == 0 ||
            sourceTexture == 0 ||
            depthTexture == 0 ||
            sourceTexWidth <= 1 ||
            sourceTexHeight <= 1 ||
            lastDepthW <= 1 ||
            lastDepthH <= 1
        ) {
            bakedReady = false
            return
        }

        ensureBakeTarget()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bakeFramebuffer)
        GLES20.glViewport(0, 0, sourceTexWidth, sourceTexHeight)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(bakeProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLES20.glUniform1i(bakeSourceLoc, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        GLES20.glUniform1i(bakeDepthLoc, 1)

        GLES20.glUniform2f(
            bakeColorTexelLoc,
            1f / sourceTexWidth.toFloat(),
            1f / sourceTexHeight.toFloat()
        )
        GLES20.glUniform2f(
            bakeDepthTexelLoc,
            1f / lastDepthW.toFloat(),
            1f / lastDepthH.toFloat()
        )

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        bakeQuad.position(0)
        GLES20.glEnableVertexAttribArray(bakePositionLoc)
        GLES20.glVertexAttribPointer(
            bakePositionLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            2 * 4,
            bakeQuad
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bakePositionLoc)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        bakedReady = true
    }

    private fun ensureBakeTarget() {
        if (bakedTexture != 0 && bakeFramebuffer != 0) return

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        bakedTexture = tex[0]
        configureTexture(bakedTexture)

        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            sourceTexWidth,
            sourceTexHeight,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        bakeFramebuffer = fbo[0]

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bakeFramebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            bakedTexture,
            0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Bake framebuffer incomplete: 0x${status.toString(16)}"
        }
    }

    private fun deleteBakeTarget() {
        if (bakeFramebuffer != 0) {
            GLES20.glDeleteFramebuffers(
                1,
                intArrayOf(bakeFramebuffer),
                0
            )
            bakeFramebuffer = 0
        }
        if (bakedTexture != 0) {
            GLES20.glDeleteTextures(
                1,
                intArrayOf(bakedTexture),
                0
            )
            bakedTexture = 0
        }
        bakedReady = false
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

    private fun rebuildMesh(
        depth: FloatArray?,
        depthW: Int,
        depthH: Int
    ) {
        if (vertexBuffer == 0 || indexBuffer == 0) return

        val hasDepth =
            depth != null &&
            depthW > 1 &&
            depthH > 1 &&
            depth.size >= depthW * depthH

        val segments = if (hasDepth) GRID_SEGMENTS else 1
        val cols = segments + 1

        val screenAspect =
            surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val imageAspect =
            imageWidth.toFloat() / imageHeight.toFloat()

        val halfFovRad =
            Math.toRadians((FOV_Y_DEG / 2.0).toDouble())
        val visibleHalfH =
            CAMERA_Z * tan(halfFovRad).toFloat()
        val visibleHalfW =
            visibleHalfH * screenAspect

        val coverScale = max(
            visibleHalfH,
            visibleHalfW / imageAspect.coerceAtLeast(0.01f)
        ) * OVERSCAN

        val halfW = imageAspect * coverScale
        val halfH = coverScale

        val softened =
            if (hasDepth) softenDepthEdges(depth!!, depthW, depthH)
            else null

        val normalizedStrength =
            ((parallaxStrength.coerceIn(0.01f, 0.12f) - 0.01f) / 0.11f)
                .coerceIn(0f, 1f)

        // V3 default was ~0.12. V4 default is ~0.48, close to the
        // visibly dimensional character of DepthWeaver's 0.7 default.
        val depthAmplitude = 0.28f + 0.72f * normalizedStrength

        val vertices = FloatArray((segments + 1) * (segments + 1) * 5)
        var vi = 0

        for (gy in 0..segments) {
            val v = gy.toFloat() / segments.toFloat()
            val py = (1f - 2f * v) * halfH

            for (gx in 0..segments) {
                val u = gx.toFloat() / segments.toFloat()
                val px = (2f * u - 1f) * halfW

                val d =
                    if (softened != null) {
                        sampleBilinear(
                            softened,
                            depthW,
                            depthH,
                            u,
                            v
                        )
                    } else {
                        0.5f
                    }

                // DepthEstimator convention used by this project:
                // 0 = closest, 1 = farthest.
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

        val vertexBufferData = ByteBuffer
            .allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBufferData.put(vertices).rewind()

        val indexBufferData = ByteBuffer
            .allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        indexBufferData.put(indices).rewind()

        GLES20.glBindBuffer(
            GLES20.GL_ARRAY_BUFFER,
            vertexBuffer
        )
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            vertices.size * 4,
            vertexBufferData,
            GLES20.GL_STATIC_DRAW
        )

        GLES20.glBindBuffer(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            indexBuffer
        )
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * 2,
            indexBufferData,
            GLES20.GL_STATIC_DRAW
        )

        GLES20.glBindBuffer(
            GLES20.GL_ARRAY_BUFFER,
            0
        )
        GLES20.glBindBuffer(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            0
        )

        indexCount = indices.size
    }

    /**
     * Feather only steep depth transitions. The geometry still has real
     * depth, but foreground/background borders do not become needle-thin
     * walls when viewed obliquely.
     */
    private fun softenDepthEdges(
        src: FloatArray,
        w: Int,
        h: Int
    ): FloatArray {
        val pass1 = FloatArray(w * h)
        val out = FloatArray(w * h)

        fun srcAt(x: Int, y: Int): Float {
            val xx = x.coerceIn(0, w - 1)
            val yy = y.coerceIn(0, h - 1)
            return src[yy * w + xx]
        }

        // First gentle Gaussian pass.
        for (y in 0 until h) {
            for (x in 0 until w) {
                pass1[y * w + x] = (
                    srcAt(x - 1, y - 1) +
                    2f * srcAt(x, y - 1) +
                    srcAt(x + 1, y - 1) +
                    2f * srcAt(x - 1, y) +
                    4f * srcAt(x, y) +
                    2f * srcAt(x + 1, y) +
                    srcAt(x - 1, y + 1) +
                    2f * srcAt(x, y + 1) +
                    srcAt(x + 1, y + 1)
                ) / 16f
            }
        }

        fun blurAt(x: Int, y: Int): Float {
            val xx = x.coerceIn(0, w - 1)
            val yy = y.coerceIn(0, h - 1)
            return pass1[yy * w + xx]
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val center = srcAt(x, y)

                val gradient = max(
                    max(
                        abs(srcAt(x + 1, y) - srcAt(x - 1, y)),
                        abs(srcAt(x, y + 1) - srcAt(x, y - 1))
                    ),
                    max(
                        abs(center - srcAt(x + 1, y)),
                        abs(center - srcAt(x, y + 1))
                    )
                )

                val edge = smoothStep(0.025f, 0.16f, gradient)

                // A second local smoothing pass is only mixed in at edges.
                val localBlur = (
                    blurAt(x - 1, y) +
                    2f * blurAt(x, y) +
                    blurAt(x + 1, y) +
                    blurAt(x, y - 1) +
                    blurAt(x, y + 1)
                ) / 6f

                val feather = edge * 0.88f
                out[y * w + x] =
                    center * (1f - feather) +
                    localBlur * feather
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

    private fun smoothStep(
        edge0: Float,
        edge1: Float,
        x: Float
    ): Float {
        val t =
            ((x - edge0) / (edge1 - edge0))
                .coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun linkProgram(
        vertexSource: String,
        fragmentSource: String
    ): Int {
        val vs = compileShader(
            GLES20.GL_VERTEX_SHADER,
            vertexSource
        )
        val fs = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            fragmentSource
        )

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(
            program,
            GLES20.GL_LINK_STATUS,
            status,
            0
        )

        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        if (status[0] == GLES20.GL_FALSE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Program link failed: $log")
        }

        return program
    }

    private fun compileShader(
        type: Int,
        source: String
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(
            shader,
            GLES20.GL_COMPILE_STATUS,
            status,
            0
        )

        if (status[0] == GLES20.GL_FALSE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            Log.e(TAG, "Shader compile error: $log")
            error("Shader compile failed: $log")
        }

        return shader
    }
}
