package com.example.xrealdepthplayer

import android.app.Presentation
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Display
import android.view.Surface

class XrealVideoPresentation(
    context: Context,
    display: Display,
    private val onSurfaceReady: (Surface) -> Unit
) : Presentation(context, display) {

    lateinit var renderer: DepthFilterRenderer
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = DepthFilterRenderer(onSurfaceReady)
        val glView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
    }
}
