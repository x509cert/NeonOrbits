package com.neonorbits.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var orbits: OrbitView
    private lateinit var panel: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { showPanel(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val dp = resources.displayMetrics.density
        fun px(v: Float) = (v * dp).roundToInt()
        val prefs = getSharedPreferences("neon_orbits", MODE_PRIVATE)

        orbits = OrbitView(this).apply { neon = prefs.getFloat("neon", 1f).toDouble() }
        val root = FrameLayout(this)
        root.addView(orbits, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- Neon panel: label + slider (0..200 %) -----------------------------
        val label = TextView(this).apply {
            setTextColor(Color.argb(210, 220, 210, 255))
            textSize = 13f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            letterSpacing = 0.04f
            setPadding(px(6f), 0, 0, px(2f))
        }
        fun setLabel(p: Int) { label.text = "Neon  $p%" }

        val seek = SeekBar(this).apply {
            max = 200
            progress = (orbits.neon * 100).roundToInt()
            progressTintList = ColorStateList.valueOf(Color.rgb(0, 225, 255))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 200, 190, 255))
            thumbTintList = ColorStateList.valueOf(Color.rgb(255, 163, 245))
            contentDescription = "Neon intensity"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    orbits.neon = p / 100.0
                    setLabel(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { handler.removeCallbacks(autoHide) }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    prefs.edit().putFloat("neon", sb.progress / 100f).apply()
                    scheduleHide()
                }
            })
        }
        setLabel(seek.progress)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14f), px(12f), px(14f), px(10f))
            background = GradientDrawable().apply {
                cornerRadius = 18f * dp
                setColor(Color.argb(170, 10, 8, 24))
                setStroke(px(1f), Color.argb(70, 160, 140, 255))
            }
            addView(label)
            addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            visibility = View.GONE
            isClickable = true                      // don't let taps fall through and scatter
        }
        root.addView(panel, FrameLayout.LayoutParams(px(290f), WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(76f) })

        // ---- Toggle button -----------------------------------------------------
        val button = TextView(this).apply {
            text = "✦"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(200, 157, 251, 255))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(110, 10, 8, 24))
                setStroke(px(1f), Color.argb(110, 0, 225, 255))
            }
            contentDescription = "Adjust neon"
            setOnClickListener { showPanel(panel.visibility != View.VISIBLE) }
        }
        root.addView(button, FrameLayout.LayoutParams(px(46f), px(46f), Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(0, 0, px(20f), px(28f)) })

        // A tap on the sky while the panel is open just closes it.
        orbits.onTap = { if (panel.visibility == View.VISIBLE) { showPanel(false); true } else false }

        setContentView(root)
        goImmersive()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 4000)
    }

    private fun showPanel(show: Boolean) {
        handler.removeCallbacks(autoHide)
        if (show) {
            panel.alpha = 0f
            panel.visibility = View.VISIBLE
            panel.animate().alpha(1f).setDuration(160).start()
            scheduleHide()
        } else if (panel.visibility == View.VISIBLE) {
            panel.animate().alpha(0f).setDuration(160).withEndAction { panel.visibility = View.GONE }.start()
        }
    }

    override fun onResume() { super.onResume(); orbits.resume() }
    override fun onPause() { orbits.pause(); handler.removeCallbacks(autoHide); super.onPause() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}
