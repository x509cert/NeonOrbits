package com.neonorbits.app

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders the simulation in three layers, like the HTML version:
 *  1. sky      – nebula wash, twinkling stars, vignette
 *  2. glow     – quarter-res persistent bitmap, blurred + saturated (blur on Android 12+)
 *  3. main     – trails, bodies, sparks, rings with additive blending
 */
class OrbitView(context: Context) : View(context) {

    private val sim = Simulation()
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private var w = 1f
    private var h = 1f
    private var scale = 1.0
    private var ox = 0.0
    private var oy = 0.0
    private var time = 0.0
    private var running = false
    private var lastNs = 0L
    private var avgFrameMs = 16.7

    // "Remove animations" in Accessibility settings -> half speed, like prefers-reduced-motion.
    private val speed: Double =
        if (Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) 0.5 else 1.0

    // ---- Sky -----------------------------------------------------------------
    private class Star(val x: Float, val y: Float, val r: Float, val phase: Double,
                       val speed: Double, val rgb: Int, val bright: Boolean)

    private var stars: List<Star> = emptyList()
    private val bg = Color.rgb(3, 2, 10)
    private val nebulaPaint = Paint()
    private val vignettePaint = Paint()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- Glow layer ----------------------------------------------------------
    private val addMode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private var glowBmp: Bitmap? = null
    private var glowCanvas: Canvas? = null
    private val glowRect = Rect()
    private val glowFade = Paint().apply {
        color = Color.argb(77, 0, 0, 0)                       // ~0.3 per frame, like the JS
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val gLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; xfermode = addMode
    }
    private val gFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = addMode }
    private val glowBlitFallback = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 230 }
    private val glowBlitPlain = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blurLayer: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BlurredLayer() else null

    /** Neon intensity, 0 (bare dots) .. 1 (original look) .. 2 (overdriven). */
    var neon = 1.0
        set(v) { field = v.coerceIn(0.0, 2.0); applyNeon() }

    /** Called on tap before scattering; return true to swallow the tap. */
    var onTap: (() -> Boolean)? = null

    private fun applyNeon() {
        val sat = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation((1 + 0.4 * neon).toFloat()) })
        glowBlitFallback.colorFilter = sat
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (blurLayer as? BlurredLayer)?.configure((22.0 * dp * (0.6 + 0.4 * neon)).toFloat(), sat)
        }
    }

    // Derived gains: glow/bridges scale fully, trails and halo size scale partially.
    private val glowGain get() = neon
    private val trailGain get() = 0.4 + 0.6 * neon
    private val haloScale get() = 0.5 + 0.5 * neon

    // ---- Main layer ----------------------------------------------------------
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; xfermode = addMode
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = addMode }
    private val filament = Path()
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(102, 220, 210, 255)
        textSize = 13f * sp
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.02f
    }

    init { isClickable = true; applyNeon() }

    fun resume() { running = true; lastNs = 0L; postInvalidateOnAnimation() }
    fun pause() { running = false }

    // ---- Layout --------------------------------------------------------------

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = max(1, nw).toFloat(); h = max(1, nh).toFloat()

        val count = ((w / dp) * (h / dp) / 2600f).toInt()
        stars = List(count) {
            val bright = Random.nextDouble() < 0.08
            val r = if (bright) 1.2 + Random.nextDouble() else 0.4 + Random.nextDouble() * 0.6
            Star(
                x = (Random.nextDouble() * w).toFloat(), y = (Random.nextDouble() * h).toFloat(),
                r = (r * dp).toFloat(), phase = Random.nextDouble() * Math.PI * 2,
                speed = 0.4 + Random.nextDouble() * 1.2,
                rgb = hsl(200 + Random.nextDouble() * 80, 0.8, 0.85), bright = bright,
            )
        }

        val big = max(w, h)
        nebulaPaint.shader = RadialGradient(
            w / 2, h / 2, big * 0.7f,
            intArrayOf(Color.argb(41, 70, 40, 120), Color.argb(20, 20, 20, 60), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        val vr = big * 0.75f
        vignettePaint.shader = RadialGradient(
            w / 2, h / 2, vr,
            intArrayOf(Color.TRANSPARENT, Color.argb(191, 0, 0, 0)),
            floatArrayOf((min(w, h) * 0.35f / vr).coerceIn(0f, 0.99f), 1f), Shader.TileMode.CLAMP,
        )

        glowBmp?.recycle()
        val gw = max(1, nw / 4); val gh = max(1, nh / 4)
        val bmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        glowBmp = bmp
        glowCanvas = Canvas(bmp).apply { scale(gw / w, gh / h) }   // draw in full-res coords
        glowRect.set(0, 0, nw, nh)
    }

    // ---- Frame ---------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val elapsedMs = if (lastNs == 0L) 16.7 else min((now - lastNs) / 1e6, 50.0)
        lastNs = now
        avgFrameMs += (elapsedMs - avgFrameMs) * 0.05
        val secs = elapsedMs / 1000.0
        time += secs

        if (running) sim.update(secs, speed)

        val frames = secs * 60.0
        scale += (min(w, h) * 0.36 / sim.extent() - scale) * (1 - 0.965.pow(frames))
        val sh = sim.shake
        ox = (Random.nextDouble() - 0.5) * 14 * dp * sh
        oy = (Random.nextDouble() - 0.5) * 14 * dp * sh

        // Trail length in samples, scaled so it covers the same time on 60/90/120 Hz.
        val tail = (Simulation.TAIL * 16.7 / avgFrameMs).roundToInt().coerceIn(8, Simulation.TRAIL_CAP)

        drawSky(canvas)
        drawGlow(canvas, tail)
        drawMain(canvas, tail)
        canvas.drawText("Tap to scatter the bodies", w / 2, h - 28f * dp, hintPaint)

        if (running) postInvalidateOnAnimation()
    }

    private fun sx(x: Double) = (w / 2.0 + ox + x * scale).toFloat()
    private fun sy(y: Double) = (h / 2.0 + oy + y * scale).toFloat()

    private fun drawSky(c: Canvas) {
        c.drawColor(bg)
        c.drawPaint(nebulaPaint)
        for (s in stars) {
            val tw = 0.55 + 0.45 * sin(time * s.speed + s.phase)
            val a = tw * (if (s.bright) 0.9 else 0.5)
            starPaint.color = withAlpha(s.rgb, a)
            c.drawCircle(s.x, s.y, s.r, starPaint)
        }
        c.drawPaint(vignettePaint)
    }

    private fun drawGlow(c: Canvas, tail: Int) {
        val gc = glowCanvas ?: return
        val bmp = glowBmp ?: return
        gc.drawPaint(glowFade)                                   // fade previous glow

        for (b in sim.bodies) {
            val tr = b.trail
            val n = min(tr.size, tail); val s0 = tr.size - n
            for (i in 1 until n) {
                if (i <= n * 0.5) continue
                val t = i.toDouble() / n; val t2 = t * t
                gLine.color = argb(0.35 * t2 * glowGain, b.tint)
                gLine.strokeWidth = ((8 + 10 * t2) * dp * haloScale).toFloat()
                gc.drawLine(sx(tr.x(s0 + i - 1)), sy(tr.y(s0 + i - 1)), sx(tr.x(s0 + i)), sy(tr.y(s0 + i)), gLine)
            }
            val x = sx(b.x); val y = sy(b.y)
            val r = (bodyRadius(b) * 2.2).toFloat()
            gFill.shader = RadialGradient(
                x, y, r, argb((0.6 + b.flare * 0.4) * glowGain, b.tint), argb(0.0, b.tint), Shader.TileMode.CLAMP,
            )
            gc.drawCircle(x, y, r, gFill)
            gFill.shader = null
        }
        for (p in sim.particles) {
            gFill.color = argb(p.life * 0.5 * glowGain, p.tint)
            gc.drawCircle(sx(p.x), sy(p.y), 6f * dp, gFill)
        }
        for (rg in sim.rings) {
            if (rg.r <= 0) continue
            gLine.color = argb(rg.life * 0.6 * glowGain, 255, 200, 160)
            gLine.strokeWidth = 18f * dp
            gc.drawCircle(sx(rg.x), sy(rg.y), (rg.r * scale).toFloat(), gLine)
        }

        // Composite: GPU blur on Android 12+, otherwise the soft quarter-res upscale alone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && c.isHardwareAccelerated && blurLayer != null) {
            (blurLayer as BlurredLayer).draw(c, bmp, glowRect, glowBlitPlain)
        } else {
            c.drawBitmap(bmp, null, glowRect, glowBlitFallback)
        }
    }

    private fun drawMain(c: Canvas, tail: Int) {
        val bodies = sim.bodies

        // Close encounters: light bridge, brighter as the bond clock runs.
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val a = bodies[i]; val b = bodies[j]
            val k = sim.encounter(a, b)
            if (k < 0) continue
            val x0 = sx(a.x); val y0 = sy(a.y); val x1 = sx(b.x); val y1 = sy(b.y)
            if (hypot(x1 - x0, y1 - y0) < 0.5f) continue
            val ba = 0.5 * k * glowGain
            line.shader = LinearGradient(x0, y0, x1, y1, argb(ba, a.tint), argb(ba, b.tint), Shader.TileMode.CLAMP)
            line.strokeWidth = ((1 + 3 * k) * dp).toFloat()
            c.drawLine(x0, y0, x1, y1, line)
            line.shader = null
        }

        for (b in bodies) {
            val tr = b.trail
            val n = min(tr.size, tail); val s0 = tr.size - n

            // Colour ribbon, thickening and brightening toward the head.
            for (i in 1 until n) {
                val t = i.toDouble() / n; val t2 = t * t
                line.color = argb(0.7 * t2 * trailGain, b.tint)
                line.strokeWidth = ((1 + 6 * t2 * haloScale) * dp).toFloat()
                c.drawLine(sx(tr.x(s0 + i - 1)), sy(tr.y(s0 + i - 1)), sx(tr.x(s0 + i)), sy(tr.y(s0 + i)), line)
            }
            // White-hot filament along the newest third.
            val f0 = floor(n * 0.65).toInt()
            if (n - f0 >= 2) {
                filament.reset()
                filament.moveTo(sx(tr.x(s0 + f0)), sy(tr.y(s0 + f0)))
                for (i in f0 + 1 until n) filament.lineTo(sx(tr.x(s0 + i)), sy(tr.y(s0 + i)))
                line.color = argb(0.45, 255, 255, 255)
                line.strokeWidth = 1.1f * dp
                c.drawPath(filament, line)
            }

            val x = sx(b.x); val y = sy(b.y)
            val r = bodyRadius(b).toFloat()
            fill.shader = RadialGradient(
                x, y, r,
                intArrayOf(argb(0.95 * glowGain, b.tint), argb(0.4 * glowGain, b.tint), argb(0.0, b.tint)),
                floatArrayOf(0f, 0.2f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawCircle(x, y, r, fill)
            fill.shader = null

            line.color = argb(0.9, b.tint); line.strokeWidth = 1.2f * dp
            c.drawCircle(x, y, ((6.5 + b.flare * 4) * dp).toFloat(), line)
            fill.color = b.tint.core
            c.drawCircle(x, y, (if (b.intruder) 5.5f else 4f) * dp, fill)
            fill.color = argb(0.98, 255, 255, 255)
            c.drawCircle(x, y, 2f * dp, fill)
        }

        // Debris sparks: short streaks along their velocity.
        for (p in sim.particles) {
            line.color = argb(p.life, p.tint)
            line.strokeWidth = ((1.5 + p.life * 1.5) * dp).toFloat()
            c.drawLine(sx(p.x - p.vx * 0.03), sy(p.y - p.vy * 0.03), sx(p.x), sy(p.y), line)
        }

        // Shockwave rings.
        for (rg in sim.rings) {
            if (rg.r <= 0) continue
            line.color = argb(rg.life * 0.8, 255, 240, 220)
            line.strokeWidth = ((2 + rg.life * 6) * dp).toFloat()
            c.drawCircle(sx(rg.x), sy(rg.y), (rg.r * scale).toFloat(), line)
        }
    }

    private fun bodyRadius(b: Body): Double {
        val speed = hypot(b.vx, b.vy)
        return ((if (b.intruder) 34.0 else 22.0) + speed * 8 + b.flare * 30) * dp * haloScale
    }

    // ---- Input ---------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (onTap?.invoke() != true) sim.scatter()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ---- Colour helpers ------------------------------------------------------

    private fun a255(a: Double) = (a.coerceIn(0.0, 1.0) * 255).roundToInt()
    private fun argb(a: Double, t: Tint) = Color.argb(a255(a), t.r, t.g, t.b)
    private fun argb(a: Double, r: Int, g: Int, b: Int) = Color.argb(a255(a), r, g, b)
    private fun withAlpha(rgb: Int, a: Double) = (rgb and 0x00FFFFFF) or (a255(a) shl 24)

    private fun hsl(hDeg: Double, s: Double, l: Double): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = (hDeg % 360) / 60
        val x = c * (1 - abs(hp % 2 - 1))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2
        return Color.rgb(((r1 + m) * 255).roundToInt(), ((g1 + m) * 255).roundToInt(), ((b1 + m) * 255).roundToInt())
    }
}

/** Android 12+: GPU Gaussian blur + saturation over the glow bitmap (the CSS blur(14px) saturate(1.4)). */
@TargetApi(Build.VERSION_CODES.S)
private class BlurredLayer {
    private val node = RenderNode("glow").apply { setAlpha(0.9f) }

    fun configure(radiusPx: Float, filter: ColorFilter) {
        node.setRenderEffect(
            RenderEffect.createChainEffect(
                RenderEffect.createColorFilterEffect(filter),
                RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.DECAL),
            )
        )
    }

    fun draw(canvas: Canvas, bmp: Bitmap, dst: Rect, paint: Paint) {
        node.setPosition(0, 0, dst.width(), dst.height())
        val rc = node.beginRecording(dst.width(), dst.height())
        rc.drawBitmap(bmp, null, dst, paint)
        node.endRecording()
        canvas.drawRenderNode(node)
    }
}
