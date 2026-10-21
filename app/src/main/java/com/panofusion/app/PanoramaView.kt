package com.panofusion.app

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Ray-cast ERP shader: longitude wraps; unobserved pixels keep their alpha mask. */
class PanoramaView(context: Context, file: File): GLSurfaceView(context) {
    private val renderer = Renderer(file)
    private var lastX = 0f
    private var lastY = 0f
    private val scale = ScaleGestureDetector(context, object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean { renderer.fov = (renderer.fov / detector.scaleFactor).coerceIn(0.45f, 2.1f); requestRender(); return true }
    })
    init { setEGLContextClientVersion(2); setRenderer(renderer); renderMode = RENDERMODE_WHEN_DIRTY }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1 && !scale.isInProgress) {
            renderer.yaw -= (event.x - lastX) / width * renderer.fov
            renderer.pitch = (renderer.pitch + (event.y - lastY) / height * renderer.fov).coerceIn(-1.55f, 1.55f)
            requestRender()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        lastX = event.x; lastY = event.y
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private class Renderer(val file: File): GLSurfaceView.Renderer {
        @Volatile var yaw = 0f
        @Volatile var pitch = 0f
        @Volatile var fov = 1.4f
        private var program = 0
        private var texture = 0
        private var aspect = 1f
        private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); position(0) }
        private fun shader(kind: Int, code: String): Int = glCreateShader(kind).also { glShaderSource(it, code); glCompileShader(it); val ok = IntArray(1); glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0); check(ok[0] == GL_TRUE) { glGetShaderInfoLog(it) } }
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val vertex = shader(GL_VERTEX_SHADER, "attribute vec2 a; varying vec2 p; void main(){p=a;gl_Position=vec4(a,0.,1.);}")
            val fragment = shader(GL_FRAGMENT_SHADER, """
                precision highp float;
                varying vec2 p; uniform sampler2D tex; uniform float yaw; uniform float pitch; uniform float fov; uniform float aspect;
                void main(){
                    vec3 d=normalize(vec3(p.x*aspect*tan(fov*.5),p.y*tan(fov*.5),1.));
                    d=vec3(d.x,cos(pitch)*d.y+sin(pitch)*d.z,-sin(pitch)*d.y+cos(pitch)*d.z);
                    d=vec3(cos(yaw)*d.x+sin(yaw)*d.z,d.y,-sin(yaw)*d.x+cos(yaw)*d.z);
                    vec2 uv=vec2(fract(atan(d.x,d.z)/6.28318530718+.5),.5-asin(clamp(d.y,-1.,1.))/3.14159265359);
                    vec4 color=texture2D(tex,uv);
                    gl_FragColor=vec4(mix(vec3(.09,.10,.12),color.rgb,color.a),1.);
                }
            """.trimIndent())
            program = glCreateProgram(); glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program)
            glDeleteShader(vertex); glDeleteShader(fragment)
            val ids = IntArray(1); glGenTextures(1, ids, 0); texture = ids[0]; glBindTexture(GL_TEXTURE_2D, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            BitmapFactory.decodeFile(file.absolutePath)?.let { GLUtils.texImage2D(GL_TEXTURE_2D, 0, it, 0); it.recycle() }
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { glViewport(0,0,width,height); aspect = width.toFloat() / height }
        override fun onDrawFrame(gl: GL10?) {
            glUseProgram(program); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture)
            glUniform1i(glGetUniformLocation(program,"tex"),0)
            for ((name,value) in listOf("yaw" to yaw,"pitch" to pitch,"fov" to fov,"aspect" to aspect)) glUniform1f(glGetUniformLocation(program,name),value)
            val a = glGetAttribLocation(program,"a"); glEnableVertexAttribArray(a); glVertexAttribPointer(a,2,GL_FLOAT,false,0,vertices); glDrawArrays(GL_TRIANGLE_STRIP,0,4)
        }
    }
}
