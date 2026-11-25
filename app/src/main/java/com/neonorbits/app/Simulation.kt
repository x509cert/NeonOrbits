package com.neonorbits.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A neon colour: [core] is the bright centre (ARGB), r/g/b is the glow tint. */
class Tint(val core: Int, val r: Int, val g: Int, val b: Int)

/** Fixed-capacity ring buffer of past positions. */
class Trail(private val cap: Int) {
    private val xs = DoubleArray(cap)
    private val ys = DoubleArray(cap)
    private var start = 0
    var size = 0
        private set

    fun push(x: Double, y: Double) {
        val idx: Int
        if (size < cap) {
            idx = (start + size) % cap; size++
        } else {
            idx = start; start = (start + 1) % cap
        }
        xs[idx] = x; ys[idx] = y
    }

    fun x(i: Int) = xs[(start + i) % cap]
    fun y(i: Int) = ys[(start + i) % cap]
}

class Body(
    var x: Double, var y: Double, var vx: Double, var vy: Double,
    val tint: Tint, val id: Int, trailCap: Int,
) {
    var m = 1.0
    var flare = 0.0
    var intruder = false
    var ax = 0.0
    var ay = 0.0
    val trail = Trail(trailCap)
}

class Particle(
    var x: Double, var y: Double, var vx: Double, var vy: Double,
    var life: Double, val decay: Double, val tint: Tint,
)

class Ring(val x: Double, val y: Double, var r: Double, var life: Double)

private class Init(val x: Double, val y: Double, val vx: Double, val vy: Double)

/**
 * Four-body gravity with the intruder / collision / rebirth mechanic.
 * Direct port of the JS version: velocity Verlet, G = 1, softened gravity.
 */
class Simulation {
    companion object {
        const val SOFT = 0.0015
        const val TAIL = 110              // trail samples at 60 fps
        const val TRAIL_CAP = 300         // headroom for 90/120 Hz screens
        const val BOND_R = 0.38
        const val BOND_T = 5.0
        const val HIT_R = 0.11
        const val ENCOUNTER_R = 0.32
        const val SUBSTEPS = 10
        const val DT = 0.0022

        val PALETTE = listOf(
            Tint(0xFF9DFBFF.toInt(), 0, 225, 255),   // electric cyan
            Tint(0xFFFFA3F5.toInt(), 255, 55, 215),  // hot magenta
            Tint(0xFFFFF1A6.toInt(), 255, 185, 35),  // solar amber
            Tint(0xFFD0FF8A.toInt(), 140, 255, 60),  // acid lime
            Tint(0xFFC9B0FF.toInt(), 150, 80, 255),  // ultraviolet
            Tint(0xFFFFB0A0.toInt(), 255, 90, 60),   // ember
        )
        val INTRUDER = Tint(0xFFFFFFFF.toInt(), 255, 240, 220)
    }

    val bodies = ArrayList<Body>()
    val particles = ArrayList<Particle>()
    val rings = ArrayList<Ring>()
    var shake = 0.0
        private set

    private val pairTimers = HashMap<Long, Double>()
    private val seen = HashSet<Long>()
    private var cooldown = 0.0
    private var intruder: Body? = null
    private var intruderLaunchR = 0.0
    private var nextId = 0
    private var paletteIdx = 0
    private var physAcc = 0.0

    init { reset() }

    // ---- Public API --------------------------------------------------------

    /** Default start: two tight binaries orbiting each other. */
    fun reset() = spawn(doubleBinary())

    /** Tap: random chaotic cluster. */
    fun scatter() = spawn(randomCluster())

    /** Largest |x| or |y| among normal bodies (used for auto-zoom). */
    fun extent(): Double {
        var e = 0.25
        for (b in bodies) if (!b.intruder) e = max(e, max(abs(b.x), abs(b.y)))
        return e
    }

    /** Close-encounter strength 0..1 for a pair, or -1 if they're not close. */
    fun encounter(a: Body, b: Body): Double {
        val d = hypot(a.x - b.x, a.y - b.y)
        if (d >= ENCOUNTER_R) return -1.0
        val bond = (pairTimers[key(a, b)] ?: 0.0) / BOND_T
        return min(1.0, (1 - d / ENCOUNTER_R) + bond * 0.6)
    }

    /** Advance one display frame. [secs] is wall time, [speed] scales physics. */
    fun update(secs: Double, speed: Double) {
        physAcc += SUBSTEPS * (secs * 1000.0 / 16.7) * speed
        val n = physAcc.toInt()
        physAcc -= n
        repeat(n) { step(DT) }

        updateBonds(secs)

        val frames = secs * 60.0                 // keep per-frame decays fps-independent
        val decay = 0.9.pow(frames)
        shake *= decay
        for (b in bodies) {
            b.flare *= decay
            b.trail.push(b.x, b.y)
        }
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val a = bodies[i]; val b = bodies[j]
            val k = encounter(a, b)
            if (k >= 0) { a.flare = max(a.flare, k); b.flare = max(b.flare, k) }
        }

        val drag = 0.985.pow(frames)
        for (p in particles) {
            p.x += p.vx * secs; p.y += p.vy * secs
            p.vx *= drag; p.vy *= drag
            p.life -= p.decay * secs
        }
        particles.removeAll { it.life <= 0 }

        for (rg in rings) { rg.r += 2.4 * secs; rg.life -= 0.7 * secs }
        rings.removeAll { it.life <= 0 }

        if (extent() > 7) reset()                // everything flew apart
    }

    // ---- Setup -------------------------------------------------------------

    private fun nextColor(): Tint = PALETTE[paletteIdx++ % PALETTE.size]

    private fun makeBody(o: Init, tint: Tint = nextColor()) =
        Body(o.x, o.y, o.vx, o.vy, tint, nextId++, TRAIL_CAP)

    private fun doubleBinary(): List<Init> {
        val d = 1.05; val s = 0.17
        val vIn = sqrt(1.0 / (4 * s)); val vOut = sqrt(2.0 / (4 * d))
        val c = cos(0.6); val n = sin(0.6)
        return listOf(
            Init(-d + s, 0.0, 0.0, vIn - vOut),
            Init(-d - s, 0.0, 0.0, -vIn - vOut),
            Init(d + s * c, s * n, -vIn * n, vIn * c + vOut),
            Init(d - s * c, -s * n, vIn * n, -vIn * c + vOut),
        )
    }

    private fun randomCluster(): List<Init> {
        val raw = ArrayList<DoubleArray>()
        var px = 0.0; var py = 0.0
        for (i in 0 until 4) {
            val a = (i / 4.0) * Math.PI * 2 + Random.nextDouble() * 0.8
            val r = 0.5 + Random.nextDouble() * 0.7
            val sp = 0.4 + Random.nextDouble() * 0.3
            val t = a + Math.PI / 2 + (Random.nextDouble() - 0.5) * 0.8
            val o = doubleArrayOf(cos(a) * r, sin(a) * r, cos(t) * sp, sin(t) * sp)
            px += o[2]; py += o[3]; raw.add(o)
        }
        return raw.map { Init(it[0], it[1], it[2] - px / 4, it[3] - py / 4) } // zero net momentum
    }

    private fun spawn(init: List<Init>) {
        paletteIdx = 0
        bodies.clear()
        init.forEach { bodies.add(makeBody(it)) }
        particles.clear(); rings.clear()
        intruder = null; pairTimers.clear(); cooldown = 1.5
        computeAccel()
    }

    // ---- Physics -----------------------------------------------------------

    private fun computeAccel() {
        for (b in bodies) { b.ax = 0.0; b.ay = 0.0 }
        val n = bodies.size
        for (i in 0 until n) for (j in i + 1 until n) {
            val bi = bodies[i]; val bj = bodies[j]
            val dx = bj.x - bi.x; val dy = bj.y - bi.y
            val r2 = dx * dx + dy * dy + SOFT
            val inv = 1.0 / (r2 * sqrt(r2))
            bi.ax += bj.m * dx * inv; bi.ay += bj.m * dy * inv
            bj.ax -= bi.m * dx * inv; bj.ay -= bi.m * dy * inv
        }
    }

    private fun step(dt: Double) {                // velocity Verlet
        for (b in bodies) {
            b.vx += 0.5 * b.ax * dt; b.vy += 0.5 * b.ay * dt
            b.x += b.vx * dt; b.y += b.vy * dt
        }
        computeAccel()
        for (b in bodies) { b.vx += 0.5 * b.ax * dt; b.vy += 0.5 * b.ay * dt }
    }

    // ---- Intruder & collision ---------------------------------------------

    private fun key(a: Body, b: Body): Long {
        val lo = min(a.id, b.id); val hi = max(a.id, b.id)
        return (lo.toLong() shl 32) or hi.toLong()
    }

    private fun updateBonds(secs: Double) {
        cooldown = max(0.0, cooldown - secs)
        seen.clear()
        var bestA: Body? = null; var bestB: Body? = null; var bestT = 0.0
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val a = bodies[i]; val b = bodies[j]
            if (a.intruder || b.intruder) continue
            val k = key(a, b); seen.add(k)
            val d = hypot(a.x - b.x, a.y - b.y)
            val t = if (d < BOND_R) (pairTimers[k] ?: 0.0) + secs else 0.0
            pairTimers[k] = t
            if (t > bestT) { bestT = t; bestA = a; bestB = b }
        }
        pairTimers.keys.retainAll(seen)

        if (intruder == null && cooldown == 0.0 && bestT >= BOND_T && bestA != null && bestB != null) {
            launchIntruder(bestA, bestB)
        }
        if (intruder != null) checkImpact()
    }

    private fun launchIntruder(a: Body, b: Body) {
        val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2
        val cvx = (a.vx + b.vx) / 2; val cvy = (a.vy + b.vy) / 2
        var ext = 0.5
        for (o in bodies) ext = max(ext, max(abs(o.x), abs(o.y)))
        val r = ext * 2.2 + 1.5
        val ang = Random.nextDouble() * Math.PI * 2
        val sx = cos(ang) * r; val sy = sin(ang) * r
        val speed = 3.2; val tof = r / speed               // lead the target
        val tx = cx + cvx * tof; val ty = cy + cvy * tof
        val dx = tx - sx; val dy = ty - sy; val l = hypot(dx, dy)
        val intr = makeBody(Init(sx, sy, dx / l * speed, dy / l * speed), INTRUDER)
        intr.intruder = true; intr.m = 1.6
        intruder = intr; intruderLaunchR = r
        bodies.add(intr)
        computeAccel()
    }

    private fun checkImpact() {
        val intr = intruder ?: return
        val hit = bodies.firstOrNull { !it.intruder && hypot(it.x - intr.x, it.y - intr.y) < HIT_R }
        if (hit == null) {
            // Missed everything: let it go once it's well past where it came from.
            if (hypot(intr.x, intr.y) > max(12.0, intruderLaunchR * 1.3)) {
                bodies.remove(intr); intruder = null; computeAccel()
            }
            return
        }
        // Victims: whichever it hit plus that body's nearest partner.
        var partner: Body? = null; var pd = Double.MAX_VALUE
        for (o in bodies) if (o !== hit && !o.intruder) {
            val d = hypot(o.x - hit.x, o.y - hit.y)
            if (d < pd) { pd = d; partner = o }
        }
        explode(listOfNotNull(intr, hit, partner))
    }

    private fun explode(victims: List<Body>) {
        var mx = 0.0; var my = 0.0; var px = 0.0; var py = 0.0; var m = 0.0
        for (v in victims) { mx += v.x * v.m; my += v.y * v.m; px += v.vx * v.m; py += v.vy * v.m; m += v.m }
        val cx = mx / m; val cy = my / m; val cvx = px / m; val cvy = py / m

        bodies.removeAll { it in victims }
        intruder = null

        // Two children fly apart roughly perpendicular to the impact, sharing momentum.
        val ang = atan2(cvy, cvx) + Math.PI / 2 + (Random.nextDouble() - 0.5) * 0.8
        val kick = 0.9; val off = 0.14
        val cols = listOf(nextColor(), nextColor())
        for (s in intArrayOf(-1, 1)) {
            bodies.add(makeBody(Init(
                cx + cos(ang) * off * s, cy + sin(ang) * off * s,
                cvx * 0.5 + cos(ang) * kick * s, cvy * 0.5 + sin(ang) * kick * s,
            ), cols[if (s > 0) 1 else 0]))
        }
        computeAccel()
        cooldown = 4.0; pairTimers.clear()

        // Debris: sparks in the victims' colours, plus a double shockwave.
        for (i in 0 until 160) {
            val v = victims[i % victims.size]
            val a = Random.nextDouble() * Math.PI * 2
            val sp = 0.6 + Random.nextDouble() * 2.6
            particles.add(Particle(
                cx, cy, cvx * 0.3 + cos(a) * sp, cvy * 0.3 + sin(a) * sp,
                1.0, 0.35 + Random.nextDouble() * 0.7, v.tint,
            ))
        }
        rings.add(Ring(cx, cy, 0.0, 1.0))
        rings.add(Ring(cx, cy, -0.25, 1.0))
        shake = 1.0
    }
}
