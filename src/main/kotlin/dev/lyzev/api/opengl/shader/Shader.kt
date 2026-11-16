/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl.shader

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventReloadShader
import dev.lyzev.api.events.EventWindowResize
import dev.lyzev.api.events.on
import dev.lyzev.api.opengl.Render
import dev.lyzev.schizoid.Schizoid
import org.joml.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL33.glBindSampler
import org.lwjgl.opengl.GL42
import org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER
import org.lwjgl.opengl.GL44
import java.awt.Color
import java.io.FileNotFoundException


/**
 * Abstract class representing a Shader.
 * @param shader The name of the shader.
 */
abstract class Shader(val shader: String) : EventListener {

    var program = 0
    private val uniforms = HashMap<String, Int>()

    /**
     * Binds the shader program.
     */
    fun bind() {
        Render.assertOpenGl()
        if (program == 0) init()
        glUseProgram(program)
    }

    /**
     * Binds the shader program.
     */
    fun unbind() = glUseProgram(0)

    /**
     * Sets a uniform float value.
     * @param name The name of the uniform.
     * @param value The float value to set.
     */
    operator fun set(name: String, value: Float) = glUniform1f(this[name], value)

    /**
     * Sets a uniform integer value.
     * @param name The name of the uniform.
     * @param value The integer value to set.
     */
    operator fun set(name: String, value: Int) = glUniform1i(this[name], value)

    /**
     * Sets a uniform integer array value.
     * @param name The name of the uniform.
     * @param value The integer array value to set.
     */
    operator fun set(name: String, value: IntArray) = glUniform1iv(this[name], value)

    /**
     * Sets a uniform boolean value.
     * @param name The name of the uniform.
     * @param value The boolean value to set.
     */
    operator fun set(name: String, value: Boolean) = glUniform1i(this[name], if (value) 1 else 0)

    /**
     * Sets a uniform [Vector2f] value.
     * @param name The name of the uniform.
     * @param value The [Vector2f] value to set.
     */
    operator fun set(name: String, value: Vector2f) = glUniform2f(this[name], value.x, value.y)

    operator fun set(name: String, x: Float, y: Float) = glUniform2f(this[name], x, y)

    /**
     * Sets a uniform [Vector2i] value.
     * @param name The name of the uniform.
     * @param value The [Vector2i] value to set.
     */
    operator fun set(name: String, value: Vector2i) = glUniform2i(this[name], value.x, value.y)

    /**
     * Sets a uniform [Vector3f] value.
     * @param name The name of the uniform.
     * @param value The [Vector3f] value to set.
     */
    operator fun set(name: String, value: Vector3f) = glUniform3f(this[name], value.x, value.y, value.z)

    operator fun set(name: String, value: Color) =
        glUniform3f(this[name], value.red / 255f, value.green / 255f, value.blue / 255f)

    /**
     * Sets a uniform [Vector4f] value.
     * @param name The name of the uniform.
     * @param value The [Vector4f] value to set.
     */
    operator fun set(name: String, value: Vector4f) = glUniform4f(this[name], value.x, value.y, value.z, value.w)

    operator fun set(name: String, x: Float, y: Float, z: Float, w: Float) = glUniform4f(this[name], x, y, z, w)

    /**
     * Sets a uniform float array value.
     * @param name The name of the uniform.
     * @param value The float array value to set.
     */
    operator fun set(name: String, value: FloatArray) = glUniform1fv(this[name], value)

    /**
     * A buffer for matrix values.
     */
    private val mat4buffer = FloatArray(16)

    /**
     * Sets a uniform [Matrix4f] value.
     * @param name The name of the uniform.
     * @param transpose Whether to transpose the matrix.
     * @param value The [Matrix4f] value to set.
     */
    operator fun set(name: String, transpose: Boolean, value: Matrix4f) =
        glUniformMatrix4fv(this[name], transpose, value.get(mat4buffer))

    private val mat2buffer = FloatArray(4)

    operator fun set(name: String, transpose: Boolean, value: Matrix2f) =
        glUniformMatrix2fv(this[name], transpose, value.get(mat2buffer))

    /**
     * Retrieves the location of a uniform variable.
     * @param name The name of the uniform.
     * @return The location of the uniform variable.
     */
    private operator fun get(name: String): Int = uniforms.getOrPut(name) {
        val location = glGetUniformLocation(program, name)
        // A location of -1 makes every later glUniform* a silent no-op, and caching it would freeze
        // that uniform at whatever the driver kept from an earlier draw. Never let it pass unnoticed.
        if (location < 0) Schizoid.logger.warn("Shader '$shader': uniform '$name' is not active (program $program)")
        location
    }

    /**
     * Processes include directives in shader source code.
     * @param source The shader source code.
     * @return The shader source code with include directives replaced by the included file content.
     */
    fun processIncludes(source: String): String {
        var modifiedSource = source
        val includeRegex = Regex("#include \"(.*?)\"")
        var matchResult = includeRegex.find(modifiedSource)
        while (matchResult != null) {
            val includePath = matchResult.groupValues[1]
            val includeFile = javaClass.getResource("$PATH/include/$includePath")
            if (includeFile == null) {
                Schizoid.logger.error(FileNotFoundException("Could not find include file: $includePath"))
                return modifiedSource
            }
            val includeContent = includeFile.readBytes().decodeToString()
            modifiedSource = modifiedSource.replace(matchResult.value, includeContent)
            matchResult = includeRegex.find(modifiedSource)
        }
        return modifiedSource
    }

    open fun preprocess(source: String): String {
        return processIncludes(source)
    }

    /**
     * Compiles a shader.
     * @param type The type of the shader.
     * @param source The shader source code.
     * @return The shader ID.
     */
    fun compile(type: Int, source: String): Int {
        Render.assertOpenGl()
        val sourceWithIncludes = preprocess(source)
        val shader = glCreateShader(type)
        glShaderSource(shader, sourceWithIncludes)
        glCompileShader(shader)
        val isCompiled = glGetShaderi(shader, GL_COMPILE_STATUS)
        if (isCompiled == 0) {
            val error = glGetShaderInfoLog(shader)
            glDeleteShader(shader)
            throw IllegalStateException("Failed to compile ${this.shader}: $error\n$sourceWithIncludes")
        }
        return shader
    }

    open fun init() {
        Render.assertOpenGl()
        if (program == 0)
            program = glCreateProgram()
    }

    open fun delete() {
        Render.assertOpenGl()
        if (program != 0)
            glDeleteProgram(program)
        program = 0
        uniforms.clear()
    }

    fun link() {
        glBindAttribLocation(program, 0, "position")
        glLinkProgram(program)
        val isLinked = glGetProgrami(program, GL_LINK_STATUS)
        check(isLinked != 0) { "Shader $shader failed to link: ${glGetProgramInfoLog(program)}" }
    }

    fun reload() {
        Render.recordRenderCall(Runnable {
            delete()
            init()
        })
    }

    override val shouldHandleEvents = true

    init {
        instances.add(this)
        on<EventReloadShader> {
            if (!Schizoid.DEVELOPER_MODE) return@on
            reload()
        }
    }

    companion object {

        /**
         * The path to the shaders.
         */
        val PATH = "/assets/${Schizoid.MOD_ID}/shaders"

        private val instances = linkedSetOf<Shader>()

        private var fullscreenVao = 0
        private var fullscreenVbo = 0

        fun closeAll() {
            Render.assertOpenGl()
            instances.toList().forEach(Shader::delete)
            if (fullscreenVao != 0) glDeleteVertexArrays(fullscreenVao)
            if (fullscreenVbo != 0) glDeleteBuffers(fullscreenVbo)
            fullscreenVao = 0
            fullscreenVbo = 0
        }

        /**
         * Draws a full screen quad.
         */
        /**
         * @param restoreState put the caller's VAO/VBO back afterwards. Reading them costs two
         *   driver synchronisation points per draw; a caller that already runs inside `Render.isolated`
         *   (which restores the whole GL state on exit) can skip it and save the stall.
         */
        fun drawFullScreen(restoreState: Boolean = true) {
            Render.assertOpenGl()
            val vao = if (restoreState) glGetInteger(GL_VERTEX_ARRAY_BINDING) else 0
            val vbo = if (restoreState) glGetInteger(GL_ARRAY_BUFFER_BINDING) else 0
            try {
                if (fullscreenVao == 0) {
                    fullscreenVao = glGenVertexArrays()
                    fullscreenVbo = glGenBuffers()
                    glBindVertexArray(fullscreenVao)
                    glBindBuffer(GL_ARRAY_BUFFER, fullscreenVbo)
                    glBufferData(GL_ARRAY_BUFFER, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), GL_STATIC_DRAW)
                    glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0L)
                    glEnableVertexAttribArray(0)
                } else glBindVertexArray(fullscreenVao)
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            } finally {
                if (restoreState) {
                    glBindVertexArray(vao)
                    glBindBuffer(GL_ARRAY_BUFFER, vbo)
                }
            }
        }
    }
}

abstract class ShaderVertexFragment(
    shader: String
) : Shader(shader) {

    final override fun init() {
        val tmp = program
        super.init()
        if (tmp == program)
            return
        try {
            for ((type, suffix) in listOf(GL_VERTEX_SHADER to "vp", GL_FRAGMENT_SHADER to "fp")) {
                val resourceName = shader.lowercase(java.util.Locale.ROOT)
                val path = "$PATH/core/$resourceName/${resourceName}_$suffix.glsl"
                val source = javaClass.getResourceAsStream(path)?.use { it.readAllBytes().decodeToString() }
                    ?: throw FileNotFoundException(path)
                val compiled = compile(type, source)
                glAttachShader(program, compiled)
                glDeleteShader(compiled)
            }
            link()
        } catch (exception: Exception) {
            super.delete()
            throw exception
        }
    }
}

abstract class ShaderCompute(
    shader: String,
    val localSizeX: Int,
    val localSizeY: Int,
    val localSizeZ: Int
) : Shader(shader) {

    var texture: Int = 0
    private var outputWidth = 0
    private var outputHeight = 0
    private var clearFbo = 0

    open fun draw() {
        bind()
        clearTexture()
        bindImageTexture()
        this["imgOutput"] = 0
    }

    fun bindImageTexture() {
        ensureTexture()
        GL42.glBindImageTexture(0, texture, 0, false, 0, GL_READ_WRITE, GL_RGBA32F)
    }

    fun clearTexture() {
        ensureTexture()
        if (GL.getCapabilities().OpenGL44 || GL.getCapabilities().GL_ARB_clear_texture) {
            GL44.glClearTexImage(texture, 0, GL_RGBA, GL_FLOAT, transparent)
        } else Render.isolated {
            if (clearFbo == 0) clearFbo = glGenFramebuffers()
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, clearFbo)
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
            glDisable(GL_SCISSOR_TEST)
            glColorMask(true, true, true, true)
            glClearBufferfv(GL_COLOR, 0, transparent)
        }
    }

    fun drawTexture() {
        ShaderPassThrough.bind()
        glActiveTexture(GL_TEXTURE0)
        glBindSampler(0, 0)
        glBindTexture(GL_TEXTURE_2D, texture)
        ShaderPassThrough["Tex0"] = 0
        ShaderPassThrough["Scale"] = 1f
        ShaderPassThrough["Alpha"] = true
        drawFullScreen()
        ShaderPassThrough.unbind()
    }

    private fun ensureTexture() {
        val width = Schizoid.mc.window.width.coerceAtLeast(1)
        val height = Schizoid.mc.window.height.coerceAtLeast(1)
        if (texture != 0 && outputWidth == width && outputHeight == height) return
        Render.isolated {
            if (texture != 0) glDeleteTextures(texture)
            genTexture(width, height)
        }
    }

    private fun genTexture(width: Int, height: Int) {
        texture = glGenTextures()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA32F,
            width,
            height,
            0,
            GL_RGBA,
            GL_FLOAT,
            0
        )
        outputWidth = width
        outputHeight = height
    }

    override fun preprocess(source: String) = super.preprocess(source).format(localSizeX, localSizeY, localSizeZ)

    override fun init() {
        check(GL.getCapabilities().OpenGL43 || GL.getCapabilities().GL_ARB_compute_shader) {
            "The $shader effect requires OpenGL compute shaders (OpenGL 4.3)."
        }
        val tmp = program
        super.init()
        if (tmp == program)
            return
        try {
            val resourceName = shader.lowercase(java.util.Locale.ROOT)
            val path = "$PATH/core/$resourceName/${resourceName}_cp.glsl"
            (javaClass.getResourceAsStream(path) ?: throw FileNotFoundException(path)).use {
                val computeShader = compile(
                    GL_COMPUTE_SHADER,
                    it.readAllBytes().decodeToString()
                )
                glAttachShader(program, computeShader)
                glDeleteShader(computeShader)
            }

            link()
        } catch (exception: Exception) {
            super.delete()
            throw exception
        }
    }

    override fun delete() {
        super.delete()
        if (texture != 0)
            glDeleteTextures(texture)
        texture = 0
        outputWidth = 0
        outputHeight = 0
        if (clearFbo != 0) glDeleteFramebuffers(clearFbo)
        clearFbo = 0
    }

    init {
        on<EventWindowResize> {
            if (program != 0) reload()
        }
    }

    companion object {
        private val transparent = floatArrayOf(0f, 0f, 0f, 0f)
        val IS_AMD_VENDOR: Boolean
            get() = glGetString(GL_VENDOR)?.lowercase()?.let { "amd" in it || "ati" in it } ?: false
    }
}
