package com.infinifolder.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import org.infiniforge.fx.api.AudioFrameMetadata
import org.infiniforge.fx.api.FxAlphaMode
import org.infiniforge.fx.api.FxColorEncoding
import org.infiniforge.fx.api.FxDiscontinuity
import org.infiniforge.fx.api.FxFrameInput
import org.infiniforge.fx.api.FxGeometry
import org.infiniforge.fx.api.FxGraph
import org.infiniforge.fx.api.FxRect
import org.infiniforge.fx.api.FxRenderResult
import org.infiniforge.fx.api.FxRenderTarget
import org.infiniforge.fx.api.FxSampling
import org.infiniforge.fx.api.FxTexture
import org.infiniforge.fx.gles.GlesFxEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * TextureView-backed GLES output. It participates in normal Android view ordering,
 * so the existing artwork, labels, and spectrum can be composed predictably.
 * Bitmap ownership stays with the host; GL resources stay on the private render thread.
 */
internal class FxArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    private data class SourceImage(val bitmap: Bitmap?, val revision: Long)

    @Volatile private var sourceImage = SourceImage(null, 0L)
    private var nextSourceRevision = 0L
    @Volatile private var graph = FxGraph("neutral-pass-through", emptyList(), emptyList())
    @Volatile private var audio: AudioFrameMetadata? = null
    @Volatile private var resumed = false
    @Volatile private var outputWidth = 0
    @Volatile private var outputHeight = 0
    @Volatile private var surfaceTexture: SurfaceTexture? = null

    private val threadLock = Any()
    private val frameLock = Any()
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var frameScheduled = false
    private var pendingHistoryReset: FxDiscontinuity? = null
    private var session: EglSession? = null // render-thread owned
    private var lastFailure: String? = null
    private var lastRenderAvailability: Boolean? = null
    var onRenderAvailabilityChanged: ((Boolean) -> Unit)? = null
    private var frameIndex = 0L
    private var lastFrameNanos = 0L

    private val frameRunnable = Runnable {
        synchronized(frameLock) { frameScheduled = false }
        if (!resumed) return@Runnable
        val rendered = renderFrame()
        if (rendered && resumed && sourceImage.bitmap != null) scheduleFrame(FRAME_INTERVAL_MS)
    }

    init {
        isOpaque = false
        surfaceTextureListener = this
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    fun setGraph(value: FxGraph, resetHistory: Boolean = false) {
        graph = value
        if (resetHistory) pendingHistoryReset = FxDiscontinuity.TRACK_CHANGED
        requestFrame()
    }

    fun setSourceBitmap(bitmap: Bitmap?) {
        nextSourceRevision++
        sourceImage = SourceImage(bitmap, nextSourceRevision)
        visibility = if (bitmap == null) GONE else VISIBLE
        requestFrame()
    }

    fun setAudioMetadata(value: AudioFrameMetadata?) {
        audio = value
        requestFrame()
    }

    fun onResume() {
        resumed = true
        if (isAvailable) ensureRenderThread()
        requestFrame()
    }

    fun onPause() {
        resumed = false
        cancelScheduledFrame()
    }

    /** Release GL names before the host pauses or destroys the Activity. */
    fun releaseForHostPause() {
        resumed = false
        cancelScheduledFrame()
        onRenderThreadAndWait {
            releaseSession()
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture = surface
        outputWidth = width
        outputHeight = height
        ensureRenderThread()
        requestFrame()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture = surface
        outputWidth = width
        outputHeight = height
        requestFrame()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (surfaceTexture === surface) surfaceTexture = null
        outputWidth = 0
        outputHeight = 0
        cancelScheduledFrame()
        onRenderThreadAndWait { releaseSession() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        releaseForHostPause()
        val handler = renderHandler
        val thread = renderThread
        if (handler != null && thread != null) {
            handler.post { thread.quitSafely() }
        } else {
            thread?.quitSafely()
        }
        synchronized(threadLock) {
            renderHandler = null
            renderThread = null
        }
        super.onDetachedFromWindow()
    }

    private fun requestFrame() {
        if (!resumed || surfaceTexture == null) return
        ensureRenderThread()
        scheduleFrame(0L)
    }

    private fun scheduleFrame(delayMs: Long) {
        val handler = renderHandler ?: return
        synchronized(frameLock) {
            if (frameScheduled || !resumed || surfaceTexture == null) return
            frameScheduled = true
            handler.postDelayed(frameRunnable, delayMs)
        }
    }

    private fun cancelScheduledFrame() {
        val handler = renderHandler
        if (handler != null) handler.removeCallbacks(frameRunnable)
        synchronized(frameLock) { frameScheduled = false }
    }

    private fun ensureRenderThread() {
        synchronized(threadLock) {
            if (renderThread?.isAlive == true) return
            val thread = HandlerThread("InfinifxRenderer").apply { start() }
            renderThread = thread
            renderHandler = Handler(thread.looper)
        }
    }

    private fun onRenderThreadAndWait(block: () -> Unit) {
        val handler = renderHandler ?: return
        if (Thread.currentThread() === renderThread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        if (!handler.post {
                try { block() } finally { done.countDown() }
            }) return
        if (!done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Timed out waiting for GL resource cleanup")
        }
    }

    /** All EGL and GLES work below runs only on the renderer thread. */
    private fun renderFrame(): Boolean {
        val texture = surfaceTexture ?: return false
        val width = outputWidth
        val height = outputHeight
        val source = sourceImage
        val bitmap = source.bitmap ?: return false
        if (width <= 0 || height <= 0 || bitmap.isRecycled) return false

        return try {
            var current = session
            if (current == null || current.surfaceTexture !== texture) {
                releaseSession()
                current = EglSession.create(texture)
                session = current
                lastFrameNanos = 0L
            }
            current.makeCurrent()
            if (current.width != width || current.height != height) {
                current.engine.resize(width, height)
                current.width = width
                current.height = height
            }
            pendingHistoryReset?.let { reason ->
                current.engine.resetHistory(reason)
                pendingHistoryReset = null
            }
            if (current.sourceTextureName == 0) {
                val names = IntArray(1)
                GLES30.glGenTextures(1, names, 0)
                check(names[0] != 0) { "Unable to allocate source texture" }
                current.sourceTextureName = names[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, current.sourceTextureName)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            }
            if (source.revision != current.uploadedRevision || bitmap.width != current.uploadedWidth || bitmap.height != current.uploadedHeight) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, current.sourceTextureName)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                current.uploadedRevision = source.revision
                current.uploadedWidth = bitmap.width
                current.uploadedHeight = bitmap.height
            }

            val now = System.nanoTime().coerceAtLeast(0L)
            val delta = if (lastFrameNanos == 0L) 0f else
                ((now - lastFrameNanos).coerceAtLeast(0L) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastFrameNanos = now
            val displayRect = fitCenter(bitmap.width, bitmap.height, width, height)
            val frame = FxFrameInput(
                presentationTimeNanos = now,
                deltaTimeSeconds = delta,
                frameIndex = frameIndex++,
                geometry = FxGeometry(
                    sourceWidth = bitmap.width,
                    sourceHeight = bitmap.height,
                    destinationWidth = width,
                    destinationHeight = height,
                    displayRect = displayRect,
                ),
                sourceTexture = FxTexture(
                    name = current.sourceTextureName,
                    width = bitmap.width,
                    height = bitmap.height,
                    alphaMode = if (bitmap.isPremultiplied) FxAlphaMode.PREMULTIPLIED else FxAlphaMode.STRAIGHT,
                    colorEncoding = FxColorEncoding.SRGB,
                    sampling = FxSampling.LINEAR,
                ),
                audio = audio?.takeIf { now >= it.timestampNanos && now - it.timestampNanos <= AUDIO_MAX_AGE_NANOS },
            )
            when (val result = current.engine.render(graph, frame, FxRenderTarget(0, width, height))) {
                is FxRenderResult.Success -> {
                    lastFailure = null
                    setRenderAvailability(true)
                    val swapped = EGL14.eglSwapBuffers(current.display, current.windowSurface)
                    check(swapped) { eglFailure("eglSwapBuffers") }
                    true
                }
                is FxRenderResult.Failure -> {
                    reportFailure("${result.code}:${result.message}", null)
                    hideAfterFailure(source.revision)
                    false
                }
            }
        } catch (error: Throwable) {
            reportFailure("render:${error.message}", error)
            hideAfterFailure(source.revision)
            releaseSession()
            false
        }
    }

    private fun hideAfterFailure(revision: Long) {
        post {
            if (sourceImage.revision == revision) visibility = GONE
        }
    }

    private fun releaseSession() {
        val current = session ?: return
        session = null
        runCatching { current.makeCurrent() }
        runCatching { current.engine.close() }
        runCatching { current.close() }
        lastFrameNanos = 0L
    }

    private fun reportFailure(key: String, error: Throwable?) {
        setRenderAvailability(false)
        if (lastFailure == key) return
        lastFailure = key
        Log.e(TAG, "Infinifx renderer: $key", error)
    }

    private fun setRenderAvailability(available: Boolean) {
        if (lastRenderAvailability == available) return
        lastRenderAvailability = available
        post { onRenderAvailabilityChanged?.invoke(available) }
    }

    private fun fitCenter(sourceWidth: Int, sourceHeight: Int, destinationWidth: Int, destinationHeight: Int): FxRect {
        val scale = min(destinationWidth.toFloat() / sourceWidth, destinationHeight.toFloat() / sourceHeight)
        val width = sourceWidth * scale / destinationWidth
        val height = sourceHeight * scale / destinationHeight
        val left = (1f - width) * 0.5f
        val bottom = (1f - height) * 0.5f
        return FxRect(left, bottom, left + width, bottom + height)
    }

    private class EglSession private constructor(
        val display: EGLDisplay,
        private val config: EGLConfig,
        val context: EGLContext,
        val windowSurface: EGLSurface,
        val surfaceTexture: SurfaceTexture,
        var width: Int,
        var height: Int,
        val engine: GlesFxEngine,
    ) {
        var sourceTextureName = 0
        var uploadedRevision = -1L
        var uploadedWidth = 0
        var uploadedHeight = 0

        fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) { eglFailure("eglMakeCurrent") }
        }

        fun close() {
            if (sourceTextureName != 0) GLES30.glDeleteTextures(1, intArrayOf(sourceTextureName), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, windowSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        companion object {
            fun create(surfaceTexture: SurfaceTexture): EglSession {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { eglFailure("eglGetDisplay") }
                val major = IntArray(1)
                val minor = IntArray(1)
                check(EGL14.eglInitialize(display, major, 0, minor, 0)) { eglFailure("eglInitialize") }
                val attributes = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                    eglFailure("eglChooseConfig(RGBA8/GLES3)")
                }
                val config = requireNotNull(configs[0])
                val contextAttributes = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
                val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
                check(context != EGL14.EGL_NO_CONTEXT) { eglFailure("eglCreateContext(GLES3)") }
                val surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
                check(surface != EGL14.EGL_NO_SURFACE) { eglFailure("eglCreateWindowSurface") }
                check(EGL14.eglMakeCurrent(display, surface, surface, context)) { eglFailure("eglMakeCurrent") }
                return try {
                    EglSession(display, config, context, surface, surfaceTexture, 1, 1, GlesFxEngine())
                } catch (error: Throwable) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, surface)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                    throw error
                }
            }
        }
    }

    companion object {
        private const val TAG = "InfiniMediaFx"
        private const val AUDIO_MAX_AGE_NANOS = 500_000_000L
        private const val FRAME_INTERVAL_MS = 16L
        private const val RELEASE_TIMEOUT_MS = 1_000L
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098

        private fun eglFailure(operation: String): String =
            "$operation failed with EGL error 0x${EGL14.eglGetError().toString(16)}"
    }
}
