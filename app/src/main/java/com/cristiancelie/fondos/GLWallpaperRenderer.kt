// © 2026 Cristian Luis Celie. All rights reserved. Portfolio excerpt from Fondos: shared for review only, not licensed for use, copying or redistribution.
package com.cristiancelie.fondos

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

private const val VERTEX_SHADER = """
    attribute vec4 aPosition;
    attribute vec4 aTexCoord;
    uniform mat4 uTexMatrix;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = aPosition;
        vTexCoord = (uTexMatrix * aTexCoord).xy;
    }
"""

private const val FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTexCoord;
    uniform samplerExternalOES sTexture;
    uniform float uDim;
    uniform float uBrightness;
    uniform float uContrast;
    uniform float uSaturation;
    void main() {
        vec4 texColor = texture2D(sTexture, vTexCoord);
        vec3 color = texColor.rgb;
        float luminance = dot(color, vec3(0.299, 0.587, 0.114));
        color = mix(vec3(luminance), color, uSaturation);
        color = (color - 0.5) * uContrast + 0.5;
        color += uBrightness;
        color *= (1.0 - uDim);
        gl_FragColor = vec4(clamp(color, 0.0, 1.0), texColor.a);
    }
"""

private val QUAD_POSITIONS = floatArrayOf(
    -1f, -1f,
    1f, -1f,
    -1f, 1f,
    1f, 1f
)

private val QUAD_TEX_COORDS = floatArrayOf(
    0f, 0f,
    1f, 0f,
    0f, 1f,
    1f, 1f
)

/**
 * Renders MediaPlayer output through an OES external texture instead of handing MediaPlayer the
 * destination surface directly. This is what makes it possible to shift (pan) which part of the
 * video frame is visible: MediaPlayer.setVideoScalingMode only offers "stretch" or "center crop",
 * with no control over crop position, so the crop position has to be applied ourselves in a
 * fragment shader between decode and present.
 *
 * All EGL/GL calls happen on a single dedicated thread (required: an EGL context is thread-bound).
 * Every public method here is safe to call from any thread; it hops onto that thread internally.
 */
class GLWallpaperRenderer {

    private val thread = HandlerThread("FondosGLRender").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile var isUsable = true
        private set

    @Volatile private var panX = 0.5f
    @Volatile private var panY = 0.5f
    @Volatile private var dim = 0f
    @Volatile private var zoom = 1f
    @Volatile private var rotationDegrees = 0
    @Volatile private var brightness = 0f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var lastFrameTs = 0L

    // GL/EGL state — only ever touched on `thread`.
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uDimLoc = 0
    private var uBrightnessLoc = 0
    private var uContrastLoc = 0
    private var uSaturationLoc = 0
    private val stMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)
    private var positionBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var hasFrame = false

    /** Surface to hand to MediaPlayer.setSurface(). Only valid after [start] returns true. */
    var inputSurface: Surface? = null
        private set

    /** Sets up EGL against [windowSurface] and creates the decode-target surface. Blocks briefly. */
    fun start(windowSurface: Surface, width: Int, height: Int): Boolean {
        surfaceWidth = width
        surfaceHeight = height
        val latch = CountDownLatch(1)
        handler.post {
            try {
                initEgl(windowSurface)
                initGl()
                val st = SurfaceTexture(oesTextureId)
                st.setOnFrameAvailableListener({
                    hasFrame = true
                    drawInternal()
                }, handler)
                surfaceTexture = st
                inputSurface = Surface(st)
            } catch (_: Exception) {
                isUsable = false
                releaseInternal()
            } finally {
                latch.countDown()
            }
        }
        // bounded wait: never let a stuck GL thread hang app startup indefinitely
        if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
            isUsable = false
        }
        return isUsable
    }

    fun resize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    /** panX/panY are the normalized (0..1) center of the visible crop window within the video frame. */
    fun setPan(x: Float, y: Float) {
        panX = x
        panY = y
        handler.post {
            if (hasFrame) drawInternal()
        }
    }

    /** 0 = no dimming, 1 = fully black. */
    fun setDim(amount: Float) {
        dim = amount
        handler.post {
            if (hasFrame) drawInternal()
        }
    }

    /** 1 = fills the screen edge-to-edge (default); higher zooms in, cropping tighter around [panX]/[panY]. */
    fun setZoom(amount: Float) {
        zoom = amount.coerceAtLeast(1f)
        handler.post {
            if (hasFrame) drawInternal()
        }
    }

    /** One of 0, 90, 180, 270 — corrects a sideways/upside-down recording. */
    fun setRotation(degrees: Int) {
        rotationDegrees = degrees
        handler.post {
            if (hasFrame) drawInternal()
        }
    }

    /** brightness: -1..1 additive. contrast/saturation: 0..2 multiplicative, 1 = unchanged. */
    fun setColorAdjust(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        handler.post {
            if (hasFrame) drawInternal()
        }
    }

    /** Milliseconds since the last decoded frame was drawn — 0 if no frame has arrived yet. Used to
     * detect a stalled decoder (see the wallpaper service's watchdog) after very long playback. */
    fun millisSinceLastFrame(): Long {
        val ts = lastFrameTs
        return if (ts == 0L) 0L else System.currentTimeMillis() - ts
    }

    fun stop() {
        if (!isUsable) {
            thread.quitSafely()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                releaseInternal()
            } finally {
                // must always fire, or a release failure hangs the caller (and the app) forever
                latch.countDown()
            }
        }
        // bounded wait: if the GL thread is somehow wedged, don't block the caller forever
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun initEgl(windowSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) error("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) error("eglInitialize failed")

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            error("eglChooseConfig failed")
        }
        val config = configs[0] ?: error("no EGL config")

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) error("eglCreateContext failed")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, windowSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) error("eglCreateWindowSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) error("eglMakeCurrent failed")
    }

    private fun initGl() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("program link failed: $log")
        }
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uDimLoc = GLES20.glGetUniformLocation(program, "uDim")
        uBrightnessLoc = GLES20.glGetUniformLocation(program, "uBrightness")
        uContrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
        uSaturationLoc = GLES20.glGetUniformLocation(program, "uSaturation")

        positionBuffer = ByteBuffer.allocateDirect(QUAD_POSITIONS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(QUAD_POSITIONS); position(0)
            }
        texCoordBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(QUAD_TEX_COORDS); position(0)
            }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("shader compile failed: $log")
        }
        return shader
    }

    /**
     * Computes the crop sub-rectangle (scale+translate) and the orientation-correction rotation,
     * both in normalized (0..1) texture-coordinate space — which is always a square parameter
     * space regardless of the physical video/screen pixel aspect, so rotating by 90/270 here never
     * distorts anything the way rotating on-screen geometry on a non-square viewport would.
     */
    private fun updateCropMatrix() {
        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.setIdentityM(rotationMatrix, 0)
        // rotate around the UV center (0.5,0.5) so the crop below operates on the corrected frame
        Matrix.translateM(rotationMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rotationMatrix, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rotationMatrix, 0, -0.5f, -0.5f, 0f)

        if (videoWidth <= 0 || videoHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return

        val swapped = rotationDegrees == 90 || rotationDegrees == 270
        val effVideoW = if (swapped) videoHeight else videoWidth
        val effVideoH = if (swapped) videoWidth else videoHeight

        val videoAspect = effVideoW.toFloat() / effVideoH
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight

        var cropW = 1f
        var cropH = 1f
        if (videoAspect > surfaceAspect) {
            cropW = surfaceAspect / videoAspect
        } else {
            cropH = videoAspect / surfaceAspect
        }
        // zoom shrinks the visible window further so pan can reach deeper into the frame
        cropW = (cropW / zoom).coerceAtMost(1f)
        cropH = (cropH / zoom).coerceAtMost(1f)

        val left = (panX - cropW / 2f).coerceIn(0f, 1f - cropW)
        val top = (panY - cropH / 2f).coerceIn(0f, 1f - cropH)

        // Maps the unit quad (0,0)-(1,1) onto the (left,top)-(left+cropW,top+cropH) sub-rectangle.
        Matrix.translateM(cropMatrix, 0, left, top, 0f)
        Matrix.scaleM(cropMatrix, 0, cropW, cropH, 1f)
    }

    private fun drawInternal() {
        if (!isUsable) return
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
            surfaceTexture?.updateTexImage()
            lastFrameTs = System.currentTimeMillis()
            surfaceTexture?.getTransformMatrix(stMatrix)
            updateCropMatrix()
            // order matters: rotate the raw frame first, then crop the corrected frame, then apply
            // the SurfaceTexture's own hardware-layout transform last.
            Matrix.multiplyMM(tempMatrix, 0, cropMatrix, 0, rotationMatrix, 0)
            Matrix.multiplyMM(combinedMatrix, 0, stMatrix, 0, tempMatrix, 0)

            GLES20.glViewport(0, 0, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

            positionBuffer?.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
            GLES20.glEnableVertexAttribArray(aPositionLoc)

            texCoordBuffer?.position(0)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)

            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, combinedMatrix, 0)
            GLES20.glUniform1f(uDimLoc, dim)
            GLES20.glUniform1f(uBrightnessLoc, brightness)
            GLES20.glUniform1f(uContrastLoc, contrast)
            GLES20.glUniform1f(uSaturationLoc, saturation)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (_: Exception) {
            // a single bad frame shouldn't take down the wallpaper; skip it
        }
    }

    private fun releaseInternal() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Exception) {
            // best-effort cleanup
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        try {
            surfaceTexture?.release()
        } catch (_: Exception) {
            // best-effort cleanup
        }
        surfaceTexture = null
        try {
            inputSurface?.release()
        } catch (_: Exception) {
            // best-effort cleanup
        }
        inputSurface = null
    }
}
