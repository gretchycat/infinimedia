package com.infinifolder.viewer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.infinimedia.visualization.VisualizationPalette
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Composites fire, smoke and lightning from one uploaded simulation frame. */
internal class LightningGlRenderer(
    private val width: Int,
    private val height: Int,
    private val frame: LightningFieldFrameSource,
) : GLSurfaceView.Renderer {
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD); position(0) }
    private val flashUpload = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
    private val fireUpload = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    private var blurProgram = 0
    private var compositeProgram = 0
    private var copyProgram = 0
    private var flashTexture = 0
    private var fireTexture = 0
    private var blurTexture = 0
    private var compositeTexture = 0
    private var blurFramebuffer = 0
    private var compositeFramebuffer = 0
    private var surfaceWidth = width
    private var surfaceHeight = height
    private var uploadedVersion = Long.MIN_VALUE
    private var paletteColor = VisualizationPalette.DEFAULT.accent

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        blurProgram = createProgram(VERTEX_SHADER, BLUR_SHADER)
        compositeProgram = createProgram(VERTEX_SHADER, COMPOSITE_SHADER)
        copyProgram = createProgram(VERTEX_SHADER, COPY_SHADER)
        flashTexture = createTexture(GLES20.GL_LUMINANCE, GLES20.GL_LUMINANCE, width, height)
        fireTexture = createTexture(GLES20.GL_RGBA, GLES20.GL_RGBA, width, height)
        blurTexture = createTexture(GLES20.GL_RGBA, GLES20.GL_RGBA, width, height)
        compositeTexture = createTexture(GLES20.GL_RGBA, GLES20.GL_RGBA, width, height)
        blurFramebuffer = createFramebuffer(blurTexture, "blur")
        compositeFramebuffer = createFramebuffer(compositeTexture, "composite")
        uploadedVersion = Long.MIN_VALUE
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (blurProgram == 0 || compositeProgram == 0 || copyProgram == 0 || flashTexture == 0 ||
            fireTexture == 0 || blurFramebuffer == 0 || compositeFramebuffer == 0
        ) return
        uploadLatestFrame()

        // First pass blurs the same field that contains the visible bolt. The
        // red and green channels carry narrow and wider horizontal Gaussian passes.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(blurProgram)
        bindTexture(0, flashTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uFlash"), 0)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(blurProgram, "uTexel"),
            1f / width,
            1f / height,
        )
        drawQuad(blurProgram)

        // Combine the simulation fields and the lightning blur at simulation
        // resolution. A final texture pass scales this result to the display.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, compositeFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(compositeProgram)
        bindTexture(0, flashTexture)
        bindTexture(1, blurTexture)
        bindTexture(2, fireTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uFlash"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uBlur"), 1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uFireFields"), 2)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(compositeProgram, "uTexel"),
            1f / width,
            1f / height,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(compositeProgram, "uCoreThreshold"),
            LIGHTNING_CORE_THRESHOLD,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(compositeProgram, "uCoreFullIntensity"),
            LIGHTNING_CORE_FULL_INTENSITY,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(compositeProgram, "uCoreSupportThreshold"),
            LIGHTNING_CORE_SUPPORT_THRESHOLD,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(compositeProgram, "uCoreSupportFullIntensity"),
            LIGHTNING_CORE_SUPPORT_FULL_INTENSITY,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(compositeProgram, "uInnerGain"),
            LIGHTNING_INNER_GLOW_GAIN,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(compositeProgram, "uInnerMaxAlpha"),
            LIGHTNING_INNER_GLOW_MAX_ALPHA,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(compositeProgram, "uOuterGain"),
            LIGHTNING_OUTER_GLOW_GAIN,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(compositeProgram, "uOuterMaxAlpha"),
            LIGHTNING_OUTER_GLOW_MAX_ALPHA,
        )
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(compositeProgram, "uPaletteColor"),
            ((paletteColor ushr 16) and 0xff) / 255f,
            ((paletteColor ushr 8) and 0xff) / 255f,
            (paletteColor and 0xff) / 255f,
        )
        drawQuad(compositeProgram)

        // SurfaceView is transparent over the Canvas-drawn artwork. Emit
        // premultiplied alpha so fire and lightning preserve that artwork.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(
            GLES20.GL_ONE,
            GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE,
            GLES20.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES20.glUseProgram(copyProgram)
        bindTexture(0, compositeTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "uImage"), 0)
        drawQuad(copyProgram)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun uploadLatestFrame() {
        synchronized(frame.lock) {
            if (uploadedVersion == frame.version) return
            flashUpload.clear()
            flashUpload.put(frame.flashPixels)
            flashUpload.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, flashTexture)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                flashUpload,
            )
            fireUpload.clear()
            fireUpload.put(frame.firePixels)
            fireUpload.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fireTexture)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                fireUpload,
            )
            paletteColor = frame.paletteColor
            uploadedVersion = frame.version
        }
    }

    private fun createTexture(internalFormat: Int, format: Int, width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            internalFormat,
            width,
            height,
            0,
            format,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        return texture
    }

    private fun createFramebuffer(texture: Int, name: String): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        val framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture,
            0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "$name framebuffer is incomplete: 0x${status.toString(16)}")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return framebuffer
    }

    private fun bindTexture(unit: Int, texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    }

    private fun drawQuad(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
        quad.position(0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertex == 0 || fragment == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val result = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (result[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Lightning shader link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val result = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0)
        if (result[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Lightning shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private companion object {
        const val TAG = "InfiniLightningGL"
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
        )
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """
        const val BLUR_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uFlash;
            uniform vec2 uTexel;
            void main() {
                float center = texture2D(uFlash, vTexCoord).r;
                float oneLeft = texture2D(uFlash, vTexCoord - vec2(uTexel.x, 0.0)).r;
                float oneRight = texture2D(uFlash, vTexCoord + vec2(uTexel.x, 0.0)).r;
                float twoLeft = texture2D(uFlash, vTexCoord - vec2(uTexel.x * 2.0, 0.0)).r;
                float twoRight = texture2D(uFlash, vTexCoord + vec2(uTexel.x * 2.0, 0.0)).r;
                float fourLeft = texture2D(uFlash, vTexCoord - vec2(uTexel.x * 4.0, 0.0)).r;
                float fourRight = texture2D(uFlash, vTexCoord + vec2(uTexel.x * 4.0, 0.0)).r;
                float narrow = (center * 6.0 + (oneLeft + oneRight) * 4.0 + twoLeft + twoRight) / 16.0;
                float wide = (center * 6.0 + (twoLeft + twoRight) * 4.0 + fourLeft + fourRight) / 16.0;
                gl_FragColor = vec4(narrow, wide, 0.0, 1.0);
            }
        """
        const val COPY_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uImage;
            void main() {
                // Restore top-origin display orientation after the composite
                // pass has written into a bottom-origin OpenGL framebuffer.
                gl_FragColor = texture2D(uImage, vec2(vTexCoord.x, 1.0 - vTexCoord.y));
            }
        """
        const val COMPOSITE_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uFlash;
            uniform sampler2D uBlur;
            uniform sampler2D uFireFields;
            uniform vec2 uTexel;
            uniform float uCoreThreshold;
            uniform float uCoreFullIntensity;
            uniform float uCoreSupportThreshold;
            uniform float uCoreSupportFullIntensity;
            uniform float uInnerGain;
            uniform float uInnerMaxAlpha;
            uniform float uOuterGain;
            uniform float uOuterMaxAlpha;
            uniform vec3 uPaletteColor;

            vec3 fireColor(float temperature) {
                if (temperature < 0.22) {
                    return mix(vec3(0.345, 0.063, 0.0), vec3(0.788, 0.169, 0.0), temperature / 0.22);
                }
                if (temperature < 0.52) {
                    return mix(vec3(0.788, 0.169, 0.0), vec3(1.0, 0.396, 0.0), (temperature - 0.22) / 0.30);
                }
                if (temperature < 0.8) {
                    return mix(vec3(1.0, 0.396, 0.0), vec3(1.0, 0.831, 0.231), (temperature - 0.52) / 0.28);
                }
                return mix(vec3(1.0, 0.831, 0.231), vec3(1.0, 1.0, 0.941), (temperature - 0.8) / 0.2);
            }

            void main() {
                // FBO rows use GL's bottom-origin coordinates, while uploaded
                // simulation arrays use top-origin rows. The blur pass writes
                // that image upside down, so flip its lookup to align glow with
                // the raw flash field and other uploaded simulation fields.
                vec2 blurCoord = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
                vec4 center = texture2D(uBlur, blurCoord);
                vec4 upOne = texture2D(uBlur, blurCoord + vec2(0.0, uTexel.y));
                vec4 downOne = texture2D(uBlur, blurCoord - vec2(0.0, uTexel.y));
                vec4 upTwo = texture2D(uBlur, blurCoord + vec2(0.0, uTexel.y * 2.0));
                vec4 downTwo = texture2D(uBlur, blurCoord - vec2(0.0, uTexel.y * 2.0));
                vec4 upFour = texture2D(uBlur, blurCoord + vec2(0.0, uTexel.y * 4.0));
                vec4 downFour = texture2D(uBlur, blurCoord - vec2(0.0, uTexel.y * 4.0));
                float innerBlur = (center.r * 6.0 + (upOne.r + downOne.r) * 4.0 + upTwo.r + downTwo.r) / 16.0;
                float outerBlur = (center.g * 6.0 + (upTwo.g + downTwo.g) * 4.0 + upFour.g + downFour.g) / 16.0;

                // The input fields use square-root encoding to preserve useful
                // precision for low heat and the lingering smolder/smoke values.
                vec4 encodedFire = texture2D(uFireFields, vTexCoord);
                vec4 fireFields = encodedFire * encodedFire;
                float temperature = max(clamp(fireFields.r * 1.2 + fireFields.g * 0.28, 0.0, 1.0), fireFields.b);
                // Keep the active flame bright and in front; smoke is a faint,
                // lingering layer behind it instead of tinting the whole plume red.
                float fireAlpha = clamp(pow(temperature, 0.42) * 0.96, 0.0, 0.96);
                float smokeAlpha = clamp(fireFields.a * 0.32, 0.0, 0.38);
                float fireSmokeAlpha = fireAlpha + smokeAlpha * (1.0 - fireAlpha);
                float fireColorAmount = fireSmokeAlpha > 0.0001
                    ? clamp(fireAlpha / fireSmokeAlpha, 0.0, 1.0)
                    : 0.0;
                float visualTemperature = pow(temperature, 0.72);
                vec3 fireSmokeColor = mix(vec3(0.518, 0.545, 0.565), fireColor(visualTemperature), fireColorAmount);
                vec3 firePremultiplied = fireSmokeColor * fireSmokeAlpha;

                float rawFlash = texture2D(uFlash, vTexCoord).r;
                float rightFlash = texture2D(uFlash, vTexCoord + vec2(uTexel.x, 0.0)).r;
                float leftFlash = texture2D(uFlash, vTexCoord - vec2(uTexel.x, 0.0)).r;
                float upFlash = texture2D(uFlash, vTexCoord + vec2(0.0, uTexel.y)).r;
                float downFlash = texture2D(uFlash, vTexCoord - vec2(0.0, uTexel.y)).r;
                float upRightFlash = texture2D(uFlash, vTexCoord + uTexel).r;
                float upLeftFlash = texture2D(uFlash, vTexCoord + vec2(-uTexel.x, uTexel.y)).r;
                float downRightFlash = texture2D(uFlash, vTexCoord + vec2(uTexel.x, -uTexel.y)).r;
                float downLeftFlash = texture2D(uFlash, vTexCoord - uTexel).r;
                float neighborFlash = max(
                    max(max(rightFlash, leftFlash), max(upFlash, downFlash)),
                    max(max(upRightFlash, upLeftFlash), max(downRightFlash, downLeftFlash))
                );
                float coreStrength = smoothstep(uCoreThreshold, uCoreFullIntensity, rawFlash);
                float connectedStrength = smoothstep(
                    uCoreSupportThreshold,
                    uCoreSupportFullIntensity,
                    neighborFlash
                );
                float coreAlpha = coreStrength * connectedStrength;
                float innerAlpha = clamp(innerBlur * uInnerGain, 0.0, uInnerMaxAlpha);
                float outerAlpha = clamp(outerBlur * uOuterGain, 0.0, uOuterMaxAlpha);
                float paleOverPalette = innerAlpha * (1.0 - outerAlpha);
                float haloAlpha = outerAlpha + paleOverPalette;
                float finalAlpha = coreAlpha + haloAlpha * (1.0 - coreAlpha);
                vec3 haloPremultiplied = uPaletteColor * outerAlpha + vec3(0.933, 0.933, 1.0) * paleOverPalette;
                vec3 lightningPremultiplied = vec3(coreAlpha) + haloPremultiplied * (1.0 - coreAlpha);
                vec3 premultiplied = lightningPremultiplied + firePremultiplied * (1.0 - finalAlpha);
                finalAlpha = finalAlpha + fireSmokeAlpha * (1.0 - finalAlpha);
                gl_FragColor = vec4(premultiplied, finalAlpha);
            }
        """
    }
}
