package com.infinifolder.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.infinimedia.visualization.AudioFeatureFrame
import com.infinimedia.visualization.MusicVisualizationEngine
import com.infinimedia.visualization.VisualizationPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Canvas artwork renderer with OpenGL fire, smoke and lightning from the same simulation frame. */
class LiveVisualizationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), AutoCloseable {
    private val engine = MusicVisualizationEngine()
    private val lightningFrame = LightningFieldFrameSource(engine.width, engine.height)
    private val fieldSize = engine.width * engine.height
    private val heat = FloatArray(fieldSize)
    private val flame = FloatArray(fieldSize)
    private val light = FloatArray(fieldSize)
    private val charge = FloatArray(fieldSize)
    private val flash = FloatArray(fieldSize)
    private val density = FloatArray(fieldSize)
    private val velocityX = FloatArray(fieldSize)
    private val velocityY = FloatArray(fieldSize)
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val meshColumns = 24
    private val meshRows = 14
    private val artworkMesh = FloatArray((meshColumns + 1) * (meshRows + 1) * 2)
    @Volatile private var artworkSource: Bitmap? = null
    @Volatile private var latestAudio = AudioFeatureFrame.SILENCE
    @Volatile private var playbackRequested = false
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var artworkFadeElapsedSeconds = 0f
    @Volatile private var artworkFadeEnabled = false
    private lateinit var lightningSurface: GLSurfaceView
    @Volatile private var hostResumed = false
    private var lightningSurfaceResumed = false
    var onArtworkOpacityChanged: ((Float) -> Unit)? = null
    private var lastPublishedArtworkOpacity = -1f
    private val simulationThread = Thread({ runSimulation() }, "InfiniMusicSimulation").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        contentDescription = "Live audio visualization"
        lightningSurface = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderMediaOverlay(true)
            setPreserveEGLContextOnPause(true)
            setRenderer(LightningGlRenderer(engine.width, engine.height, lightningFrame))
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(lightningSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    val drawsArtwork: Boolean get() = artworkSource?.isRecycled == false

    /** Safe to call from the independent PCM-analysis worker. */
    fun submitAudioFeatures(frame: AudioFeatureFrame) {
        latestAudio = frame
    }

    fun setPlaybackActive(value: Boolean) {
        playbackRequested = value
        refreshSimulationState()
    }

    /** Album pixels seed the palette and remain available to the field-warped image mesh. */
    fun seedFromArtwork(artwork: Bitmap?) {
        engine.setPalette(artwork?.let(::extractPaletteAntialiased) ?: VisualizationPalette.DEFAULT)
        artworkSource = artwork?.takeUnless { it.isRecycled }
        artworkFadeElapsedSeconds = 0f
        artworkFadeEnabled = true
        lastPublishedArtworkOpacity = -1f
        invalidate()
    }

    fun hardReset() {
        engine.hardReset()
        synchronized(lightningFrame.lock) {
            flash.fill(0f)
            synchronized(engine) {
                engine.copyRenderFields(heat, light, charge, flash, density, velocityX, velocityY, flame)
                lightningFrame.publish(heat, light, flame, density, flash, engine.currentLightningAfterglowColor())
            }
        }
        lightningSurface.requestRender()
        invalidate()
    }

    /** GLSurfaceView's render thread follows the hosting activity lifecycle. */
    fun onHostResume() {
        hostResumed = true
        resumeLightningSurface()
    }

    fun onHostPause() {
        hostResumed = false
        pauseLightningSurface()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || closed) return
        synchronized(lightningFrame.lock) {
            val artworkOpacity = artworkOpacity()
            if (lastPublishedArtworkOpacity < 0f || abs(artworkOpacity - lastPublishedArtworkOpacity) >= 0.015f) {
                lastPublishedArtworkOpacity = artworkOpacity
                onArtworkOpacityChanged?.let { listener -> post { listener(artworkOpacity) } }
            }
            drawWarpedArtwork(canvas, artworkOpacity)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        refreshSimulationState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeLightningSurface()
        refreshSimulationState()
    }

    override fun onDetachedFromWindow() {
        active = false
        pauseLightningSurface()
        super.onDetachedFromWindow()
    }

    private fun resumeLightningSurface() {
        if (hostResumed && !lightningSurfaceResumed && ::lightningSurface.isInitialized) {
            lightningSurfaceResumed = true
            lightningSurface.onResume()
        }
    }

    private fun pauseLightningSurface() {
        if (lightningSurfaceResumed && ::lightningSurface.isInitialized) {
            lightningSurfaceResumed = false
            lightningSurface.onPause()
        }
    }

    private fun refreshSimulationState() {
        active = playbackRequested && isShown && !closed
        if (active) simulationThread.interrupt()
    }

    private fun runSimulation() {
        var previousNs = 0L
        while (!closed) {
            if (!active) {
                previousNs = 0L
                try { Thread.sleep(110L) } catch (_: InterruptedException) { }
                continue
            }
            val startedNs = System.nanoTime()
            val dt = if (previousNs == 0L) 1f / 30f else ((startedNs - previousNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            previousNs = startedNs
            engine.advance(dt, latestAudio)
            if (artworkFadeEnabled) artworkFadeElapsedSeconds += dt
            synchronized(lightningFrame.lock) {
                synchronized(engine) {
                    engine.copyRenderFields(heat, light, charge, flash, density, velocityX, velocityY, flame)
                    lightningFrame.publish(heat, light, flame, density, flash, engine.currentLightningAfterglowColor())
                }
            }
            lightningSurface.requestRender()
            postInvalidateOnAnimation()
            val remainingMs = (33L - (System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
            try { Thread.sleep(remainingMs) } catch (_: InterruptedException) { }
        }
    }

    private fun artworkOpacity(): Float {
        if (!artworkFadeEnabled) return 1f
        val progress = ((artworkFadeElapsedSeconds - 0.5f) / 8f).coerceIn(0f, 1f)
        val smoothProgress = progress * progress * (3f - 2f * progress)
        return 1f - smoothProgress
    }

    private fun extractPaletteAntialiased(bitmap: Bitmap): VisualizationPalette {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return VisualizationPalette.DEFAULT
        val maxSide = 96
        val scale = min(1f, maxSide.toFloat() / max(bitmap.width, bitmap.height))
        val sampleWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val sampleHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val sampled = if (sampleWidth != bitmap.width || sampleHeight != bitmap.height) {
            runCatching { Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true) }.getOrNull()
                ?: return VisualizationPalette.DEFAULT
        } else bitmap
        try {
            val pixels = IntArray(sampled.width * sampled.height)
            sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
            return VisualizationPalette.fromPixelSamples(pixels)
        } finally {
            if (sampled !== bitmap && !sampled.isRecycled) sampled.recycle()
        }
    }

    private fun drawWarpedArtwork(canvas: Canvas, opacity: Float) {
        val artwork = artworkSource?.takeUnless { it.isRecycled } ?: return
        if (opacity <= 0f || width <= 0 || height <= 0) return

        val fitScale = min(width.toFloat() / artwork.width, height.toFloat() / artwork.height)
        val fittedWidth = artwork.width * fitScale
        val fittedHeight = artwork.height * fitScale
        val left = (width - fittedWidth) * 0.5f
        val top = (height - fittedHeight) * 0.5f
        val centerX = width * 0.5f
        val centerY = height * 0.5f

        for (row in 0..meshRows) for (column in 0..meshColumns) {
            val u = column / meshColumns.toFloat()
            val v = row / meshRows.toFloat()
            val sampleX = u * (engine.width - 1)
            val sampleY = v * (engine.height - 1)
            val heatValue = sampleField(heat, sampleX, sampleY).coerceIn(0f, 1f)
            val vx = sampleField(velocityX, sampleX, sampleY)
            val vy = sampleField(velocityY, sampleX, sampleY)
            val baseX = left + fittedWidth * u
            val baseY = top + fittedHeight * v
            val localZoom = 1f + heatValue * 0.075f
            val temperatureWarpX = (u - 0.5f) * heatValue * width * 0.035f
            val temperatureWarpY = (v - 0.5f) * heatValue * height * 0.035f
            val offset = (row * (meshColumns + 1) + column) * 2
            artworkMesh[offset] = centerX + (baseX - centerX) * localZoom + vx * width * 0.085f + temperatureWarpX
            artworkMesh[offset + 1] = centerY + (baseY - centerY) * localZoom + vy * height * 0.085f + temperatureWarpY
        }

        val saveCount = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        artworkPaint.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmapMesh(artwork, meshColumns, meshRows, artworkMesh, 0, null, 0, artworkPaint)
        artworkPaint.alpha = 255
        canvas.restoreToCount(saveCount)
    }

    private fun sampleField(field: FloatArray, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, engine.width - 1)
        val y0 = y.toInt().coerceIn(0, engine.height - 1)
        val x1 = min(engine.width - 1, x0 + 1)
        val y1 = min(engine.height - 1, y0 + 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val top = field[y0 * engine.width + x0] * (1f - fx) + field[y0 * engine.width + x1] * fx
        val bottom = field[y1 * engine.width + x0] * (1f - fx) + field[y1 * engine.width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }

    override fun close() {
        if (closed) return
        closed = true
        playbackRequested = false
        active = false
        simulationThread.interrupt()
        pauseLightningSurface()
        engine.close()
    }
}
