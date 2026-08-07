package com.messenger.app.data.roundvideo

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The GPU half of the round-video transcoder.
 *
 * The decoder writes frames into an external (OES) texture and this draws that
 * texture straight onto the encoder's input surface, so a frame never leaves
 * the GPU. The obvious alternative - decode to an ImageReader, crop the YUV in
 * Kotlin, feed the encoder byte buffers - copies every frame through the heap
 * twice and is far too slow and memory-hungry for a 60s capture on a mid-range
 * phone.
 *
 * The only transform applied here is a centre-square crop. Rotation is left
 * to the decoder (which orients the frame via its SurfaceTexture transform)
 * and to the MP4 orientation hint written by the muxer.
 */
internal class EglCore(encoderInputSurface: Surface) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        // EGL_RECORDABLE_ANDROID is what makes the config legal to use with a
        // MediaCodec input surface; without it eglCreateWindowSurface fails on
        // a good number of devices.
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "no suitable EGL config" }

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        surface = EGL14.eglCreateWindowSurface(
            display, configs[0], encoderInputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    /**
     * Stamps the frame's presentation time onto the encoder surface. Without
     * this every frame lands at time 0 and the muxed file plays as an instant
     * blur or refuses to play at all.
     */
    fun setPresentationTime(nsec: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nsec)
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** android.opengl.EGLExt is API 18+; aliased here to keep the call site short. */
private object EGLExt {
    fun eglPresentationTimeANDROID(display: EGLDisplay, surface: EGLSurface, nsec: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(display, surface, nsec)
    }
}

/**
 * Draws the decoder's OES texture onto the current EGL surface, cropped to its
 * centre square.
 */
internal class TextureRenderer {

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uStMatrix = 0
    private var uCropMatrix = 0
    private var uPosMatrix = 0

    var textureId = 0
        private set

    private val stMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)
    private val posMatrix = FloatArray(16)

    private var srcWidth = 1
    private var srcHeight = 1
    private var cropReady = false

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD).position(0) }

    fun setUp() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uCropMatrix = GLES20.glGetUniformLocation(program, "uCropMatrix")
        uPosMatrix = GLES20.glGetUniformLocation(program, "uPosMatrix")
        Matrix.setIdentityM(posMatrix, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
    }

    /**
     * Builds the centre-square crop matrix once per transcode.
     *
     * Rotation is deliberately NOT handled here. Rotating inside normalised
     * texture space distorts, because that space maps to a WxH rectangle - a
     * 90 degree turn there swaps axes of different physical scale and squashes
     * the picture, which is exactly what went wrong. It also invites a sign
     * error, since GL's v axis runs opposite to image rows.
     *
     * Instead the square is cut in the source's own orientation and the
     * rotation is carried as an MP4 orientation hint (see [VideoCompressor]).
     * A centre-square crop commutes with rotation about the centre, so the
     * result is identical - with no trigonometry and nothing to get backwards.
     */
    fun configure(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int) {
        srcWidth = sourceWidth
        srcHeight = sourceHeight
        cropReady = false

        // No rotation is applied on the GPU at all. The decoder already
        // orients the frame (its SurfaceTexture transform does the work) and
        // the file carries the source's rotation as an MP4 orientation hint,
        // so anything added here would be a third rotation on top of two.
        Matrix.setIdentityM(posMatrix, 0)

        android.util.Log.d(
            "RoundGeom",
            "src=${sourceWidth}x$sourceHeight rot=$rotationDegrees"
        )
    }

    /**
     * Builds the centre-square crop, using the real SurfaceTexture transform.
     *
     * This cannot be done in [configure]: the transform is only available once
     * a frame has arrived, and assuming it is the identity is precisely what
     * broke. On this device it is (u,v) -> (v,u) - the decoder swaps the axes
     * while orienting the frame - so a crop of min/W horizontally and min/H
     * vertically arrives on the opposite dimensions. With a 720x480 source
     * that sampled the full 720 width but only 320 of the 480 height, and
     * squeezing that 720x320 strip into a 384x384 square is the stretch.
     *
     * Reading the swap off the matrix rather than inferring it from the
     * rotation metadata keeps this correct on decoders that behave differently.
     */
    private fun buildCropMatrix() {
        val side = minOf(srcWidth, srcHeight).toFloat()
        var scaleX = side / srcWidth
        var scaleY = side / srcHeight

        // m00 near zero with m01 near +/-1 means the transform maps u to v:
        // the axes are swapped downstream, so swap the factors to compensate.
        val axesSwapped = kotlin.math.abs(stMatrix[0]) < 0.5f &&
            kotlin.math.abs(stMatrix[1]) > 0.5f
        if (axesSwapped) {
            val t = scaleX
            scaleX = scaleY
            scaleY = t
        }

        android.util.Log.d(
            "RoundGeom",
            "crop swapped=$axesSwapped scaleX=$scaleX scaleY=$scaleY"
        )

        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.translateM(cropMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.scaleM(cropMatrix, 0, scaleX, scaleY, 1f)
        Matrix.translateM(cropMatrix, 0, -0.5f, -0.5f, 0f)
        cropReady = true
    }

    fun drawFrame(surfaceTexture: SurfaceTexture) {
        surfaceTexture.getTransformMatrix(stMatrix)
        // The transform only exists once a frame has landed, so the crop is
        // built here rather than in configure().
        if (!cropReady) buildCropMatrix()

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uCropMatrix, 1, false, cropMatrix, 0)
        GLES20.glUniformMatrix4fv(uPosMatrix, 1, false, posMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        program = 0
        textureId = 0
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "link failed: " + GLES20.glGetProgramInfoLog(p) }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private companion object {
        // x, y, u, v for a full-surface triangle strip.
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uStMatrix;
            uniform mat4 uCropMatrix;
            uniform mat4 uPosMatrix;
            varying vec2 vTexCoord;
            void main() {
                // Rotating the quad rather than the sampling. The target is
                // square, so this maps the square onto itself and can never
                // change the aspect ratio.
                gl_Position = uPosMatrix * aPosition;
                // Crop first (normalised space), then the SurfaceTexture
                // transform that maps into the texture's real coordinates.
                vTexCoord = (uStMatrix * (uCropMatrix * aTexCoord)).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
