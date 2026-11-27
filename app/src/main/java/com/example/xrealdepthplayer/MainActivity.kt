package com.example.xrealdepthplayer

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var presentation: XrealVideoPresentation? = null
    private var activeRenderer: DepthFilterRenderer? = null
    private var outputSurface: Surface? = null

    private lateinit var tvStatus: TextView
    private lateinit var seekProgress: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var btnPlayPause: Button

    private lateinit var sbEdge: SeekBar
    private lateinit var sbPop: SeekBar
    private lateinit var sbVig: SeekBar
    private lateinit var sbChr: SeekBar

    private var isUserSeeking = false
    private val handler = Handler(Looper.getMainLooper())

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadAndPlayVideo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        activeRenderer?.videoAspectRatio =
                            (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    btnPlayPause.text = if (isPlaying) "⏸ 一時停止" else "▶ 再生"
                }
            })
        }

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 48, 44, 48)
        }
        scroll.addView(root)

        tvStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }
        root.addView(tvStatus)

        // XREAL接続チェックと出力先セットアップ
        val previewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 540
            )
        }
        setupDisplayOutput(previewContainer)
        root.addView(previewContainer)

        // 動画ファイル選択ボタン
        val btnSelect = Button(this).apply {
            text = "📁 動画ファイルを選択 (MP4 / MKV / MOV)"
            setOnClickListener {
                pickVideoLauncher.launch(arrayOf("video/*"))
            }
        }
        root.addView(btnSelect)

        // 再生コントロール & シークバー
        tvTime = TextView(this).apply { text = "00:00 / 00:00" }
        seekProgress = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = player?.duration ?: 0L
                        if (dur > 0) player?.seekTo((dur * p) / 1000L)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { isUserSeeking = true }
                override fun onStopTrackingTouch(s: SeekBar?) { isUserSeeking = false }
            })
        }
        btnPlayPause = Button(this).apply {
            text = "▶ 再生"
            setOnClickListener {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
            }
        }
        root.addView(tvTime)
        root.addView(seekProgress)
        root.addView(btnPlayPause)

        // プリセットボタン行
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 16)
        }
        presetRow.addView(createPresetBtn("標準3D") { applyPreset(120, 40, 30, 2) })
        presetRow.addView(createPresetBtn("強力立体") { applyPreset(185, 85, 50, 4) })
        presetRow.addView(createPresetBtn("風景奥行き") { applyPreset(140, 65, 60, 3) })
        root.addView(presetRow)

        // 各パラメータースライダー
        sbEdge = addSlider(root, "輪郭の立体強調 (Edge)", 250, 130) {
            activeRenderer?.edgeStrength = it / 100f
        }
        sbPop = addSlider(root, "手前・奥コントラスト (Depth Pop)", 150, 45) {
            activeRenderer?.depthPop = it / 100f
        }
        sbVig = addSlider(root, "周辺減光奥行き誘導 (Vignette)", 80, 35) {
            activeRenderer?.vignetteStrength = it / 100f
        }
        sbChr = addSlider(root, "微細色収差ブースト (Chromatic)", 10, 2) {
            activeRenderer?.chromaticShift = it * 0.001f
        }

        setContentView(scroll)
        startProgressUpdater()
    }

    private fun setupDisplayOutput(fallbackContainer: FrameLayout) {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val externalDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        if (externalDisplays.isNotEmpty()) {
            val xreal = externalDisplays[0]
            tvStatus.text = "✅ XREAL One 接続検出 (${xreal.name})：グラスへフルスクリーン出力中"
            // スマホ側プレビュー枠は最小化
            fallbackContainer.layoutParams.height = 1
            presentation = XrealVideoPresentation(this, xreal) { surface ->
                outputSurface = surface
                runOnUiThread { player?.setVideoSurface(surface) }
            }
            presentation?.show()
            activeRenderer = presentation?.renderer
        } else {
            tvStatus.text = "⚠️ XREAL未接続（スマホ画面でフィルタープレビュー中。接続後に再起動でグラス出力）"
            val renderer = DepthFilterRenderer { surface ->
                outputSurface = surface
                runOnUiThread { player?.setVideoSurface(surface) }
            }
            activeRenderer = renderer
            val glView = GLSurfaceView(this).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            fallbackContainer.addView(glView)
        }
    }

    private fun loadAndPlayVideo(uri: Uri) {
        player?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            play()
        }
    }

    private fun createPresetBtn(title: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun applyPreset(edge: Int, pop: Int, vig: Int, chr: Int) {
        sbEdge.progress = edge
        sbPop.progress = pop
        sbVig.progress = vig
        sbChr.progress = chr
    }

    private fun addSlider(parent: LinearLayout, label: String, max: Int, init: Int, onChange: (Int) -> Unit): SeekBar {
        val tv = TextView(this).apply { text = "$label: $init" }
        val sb = SeekBar(this).apply {
            this.max = max
            progress = init
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$label: $p"
                    onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(tv)
        parent.addView(sb)
        return sb
    }

    private fun startProgressUpdater() {
        handler.post(object : Runnable {
            override fun run() {
                player?.let { p ->
                    val dur = p.duration.coerceAtLeast(0L)
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    if (dur > 0 && !isUserSeeking) {
                        seekProgress.progress = ((pos * 1000L) / dur).toInt()
                    }
                    tvTime.text = "${formatMs(pos)} / ${formatMs(dur)}"
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        outputSurface?.release()
        presentation?.dismiss()
        super.onDestroy()
    }
}
