package com.infinifolder.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.AttributeSet
import android.util.Log
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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/** Transparent GLES overlay. Bitmap ownership stays with the host; GL names stay on the GL thread. */
internal class FxArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {
    private data class SourceImage(val bitmap: Bitmap?, val revision: Long)
    @Volatile private var sourceImage = SourceImage(null, 0L)
    private var nextSourceRevision = 0L
    @Volatile private var graph = FxGraph("neutral-pass-through", emptyList(), emptyList())
    @Volatile private var audio: AudioFrameMetadata? = null

    private var engine: GlesFxEngine? = null
    private var sourceTextureName = 0
    private var uploadedRevision = -1L
    private var uploadedWidth = 0
    private var uploadedHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0
    private var frameIndex = 0L
    private var lastFrameNanos = 0L
    private var lastFailure: String? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setPreserveEGLContextOnPause(true)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    fun setGraph(value: FxGraph, resetHistory: Boolean = false) {
        graph = value
        if (resetHistory) queueEvent { runCatching { engine?.resetHistory(FxDiscontinuity.TRACK_CHANGED) } }
        requestRender()
    }

    fun setSourceBitmap(bitmap: Bitmap?) {
        nextSourceRevision++
        sourceImage = SourceImage(bitmap, nextSourceRevision)
        visibility = if (bitmap == null) GONE else VISIBLE
        requestRender()
    }

    fun setAudioMetadata(value: AudioFrameMetadata?) {
        audio = value
        requestRender()
    }

    /** Queue GL cleanup before pausing the view, while its EGL context can still be current. */
    fun releaseForHostPause() {
        if (!isAttachedToWindow) return
        val done = CountDownLatch(1)
        runCatching {
            queueEvent {
                releaseGpuResources()
                done.countDown()
            }
            if (!done.await(500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for FX GL cleanup; EGL teardown will reclaim the context")
            }
        }.onFailure { Log.w(TAG, "Could not queue FX GL cleanup", it) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A recreated context invalidates every old name; the driver owns reclaiming those names.
        engine = null
        sourceTextureName = 0
        uploadedRevision = -1L
        uploadedWidth = 0
        uploadedHeight = 0
        lastFrameNanos = 0L
        runCatching { engine = GlesFxEngine() }
            .onFailure { reportFailure("engine-create:${it.message}", it) }
        GLES30.glClearColor(0f, 0f, 0f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        outputWidth = width
        outputHeight = height
        if (width > 0 && height > 0) runCatching { engine?.resize(width, height) }
            .onFailure { reportFailure("resize:${it.message}", it) }
    }

    override fun onDrawFrame(gl: GL10?) {
        val width = outputWidth
        val height = outputHeight
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val image = sourceImage
        val bitmap = image.bitmap ?: return
        if (width <= 0 || height <= 0 || bitmap.isRecycled) return
        try {
            val currentEngine = engine ?: GlesFxEngine().also { engine = it }
            if (sourceTextureName == 0) {
                val names = IntArray(1)
                GLES30.glGenTextures(1, names, 0)
                sourceTextureName = names[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureName)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            }
            val revision = image.revision
            if (revision != uploadedRevision || bitmap.width != uploadedWidth || bitmap.height != uploadedHeight) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureName)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                uploadedRevision = revision
                uploadedWidth = bitmap.width
                uploadedHeight = bitmap.height
            }
            val now = System.nanoTime().coerceAtLeast(0L)
            val delta = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos).coerceAtLeast(0L) / 1_000_000_000f).coerceIn(0f, 0.1f)
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
                    name = sourceTextureName,
                    width = bitmap.width,
                    height = bitmap.height,
                    alphaMode = FxAlphaMode.PREMULTIPLIED,
                    colorEncoding = FxColorEncoding.SRGB,
                    sampling = FxSampling.LINEAR,
                ),
                audio = audio?.takeIf { now >= it.timestampNanos && now - it.timestampNanos <= AUDIO_MAX_AGE_NANOS },
            )
            when (val result = currentEngine.render(graph, frame, FxRenderTarget(0, width, height))) {
                is FxRenderResult.Success -> lastFailure = null
                is FxRenderResult.Failure -> reportFailure("${result.code}:${result.message}", null)
            }
        } catch (error: Throwable) {
            reportFailure("render:${error.message}", error)
        }
    }

    private fun releaseGpuResources() {
        runCatching { engine?.resetHistory(FxDiscontinuity.PAUSED) }
        runCatching { engine?.close() }
        engine = null
        if (sourceTextureName != 0) GLES30.glDeleteTextures(1, intArrayOf(sourceTextureName), 0)
        sourceTextureName = 0
        uploadedRevision = -1L
        lastFrameNanos = 0L
    }

    private fun reportFailure(key: String, error: Throwable?) {
        if (lastFailure == key) return
        lastFailure = key
        Log.e(TAG, "Infinifx renderer: $key", error)
    }

    private fun fitCenter(sourceWidth: Int, sourceHeight: Int, destinationWidth: Int, destinationHeight: Int): FxRect {
        val scale = min(destinationWidth.toFloat() / sourceWidth, destinationHeight.toFloat() / sourceHeight)
        val width = sourceWidth * scale / destinationWidth
        val height = sourceHeight * scale / destinationHeight
        val left = (1f - width) * 0.5f
        val bottom = (1f - height) * 0.5f
        return FxRect(left, bottom, left + width, bottom + height)
    }

    companion object {
        private const val TAG = "InfiniMediaFx"
        private const val AUDIO_MAX_AGE_NANOS = 500_000_000L
    }
}
