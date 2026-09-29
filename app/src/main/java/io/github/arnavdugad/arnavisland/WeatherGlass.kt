package io.github.arnavdugad.arnavisland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The sky where your PC is, as its island names it ("14° Rain", "9° Thunderstorm"...). */
enum class Sky { None, Drizzle, Rain, Storm, Snow, Fog }
fun skyOf(weather: String): Sky {
    val w = weather.lowercase()
    return when {
        "thunder" in w || "storm" in w -> Sky.Storm
        "drizzle" in w -> Sky.Drizzle
        "rain" in w || "shower" in w -> Sky.Rain
        "snow" in w || "sleet" in w -> Sky.Snow
        "fog" in w || "mist" in w || "haze" in w -> Sky.Fog
        else -> Sky.None
    }
}

/**
 * The weather where your PC is, on the app's glass: drops that bead on it and now and then run down (rain, drizzle),
 * a storm's double flash, snow drifting past, or fog. It sits over everything and takes no touches; it is still with
 * reduced motion and stops while the app is hidden.
 */
@Composable fun WeatherGlass(sky: Sky, modifier: Modifier = Modifier) {
    if (sky == Sky.None) return
    val reduced = LocalReduced.current; val owner = LocalLifecycleOwner.current; val density = LocalDensity.current.density
    val scene = remember(sky) { WeatherScene(sky) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(scene, reduced, owner) {
        if (reduced) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = System.nanoTime()
            while (isActive) {
                delay(33)
                val now = System.nanoTime(); scene.step(((now - last) / 1e9).toFloat().coerceAtMost(.1f)); last = now; tick = now
            }
        }
    }
    Canvas(modifier.fillMaxSize()) { tick.hashCode(); scene.draw(this, density) }
}

private class Drop(var x: Float, var y: Float, var r: Float, var v: Float = 0f, var sliding: Boolean = false, var age: Float = 0f, var wobble: Float = Random.nextFloat() * 6f)
private class Flake(var x: Float, var y: Float, val r: Float, val speed: Float, val sway: Float, val phase: Float)

/** The drops, flakes and flashes, in fractions of the screen (so any size and rotation fits). */
private class WeatherScene(val sky: Sky) {
    private val drops = ArrayList<Drop>(); private val trail = ArrayList<Drop>()
    private val flakes = ArrayList<Flake>()
    private var time = 0f; private var nextSlide = 1.2f; private var nextFlash = 3f + Random.nextFloat() * 5f; private var flash = 100f
    init {
        val n = when (sky) { Sky.Drizzle -> 16; Sky.Rain -> 28; Sky.Storm -> 34; else -> 0 }
        repeat(n) { drops += Drop(Random.nextFloat(), Random.nextFloat(), size()).also { it.age = 1f } }
        if (sky == Sky.Snow) repeat(46) { flakes += Flake(Random.nextFloat(), Random.nextFloat(), 1.2f + Random.nextFloat() * 2.4f, .02f + Random.nextFloat() * .035f, .01f + Random.nextFloat() * .02f, Random.nextFloat() * 6.28f) }
    }
    /** A drop's radius (dp): drizzle is fine, rain and storms heavier. */
    private fun size() = if (sky == Sky.Drizzle) 1.8f + Random.nextFloat() * 2f else 2.4f + Random.nextFloat() * 3.8f
    fun step(dt: Float) {
        time += dt
        if (drops.isNotEmpty()) {
            // Now and then a drop grows heavy and runs down, leaving a trail of beads.
            nextSlide -= dt
            if (nextSlide <= 0f) { drops.filter { !it.sliding && it.r > 3f }.randomOrNull()?.sliding = true; nextSlide = (if (sky == Sky.Drizzle) 2.4f else 1.1f) * (.6f + Random.nextFloat()) }
            for (d in drops) {
                d.age = (d.age + dt * 1.6f).coerceAtMost(1f)
                if (d.sliding) {
                    d.v = (d.v + dt * .35f).coerceAtMost(.42f); val before = d.y; d.y += d.v * dt
                    d.x += sin(time * 3f + d.wobble) * .0009f
                    if (((d.y * 90).toInt() != (before * 90).toInt()) && Random.nextFloat() < .5f) trail += Drop(d.x, before, d.r * .38f).also { it.age = 1f }
                    if (d.y > 1.08f) { d.x = Random.nextFloat(); d.y = Random.nextFloat() * .9f; d.v = 0f; d.sliding = false; d.age = 0f; d.r = size() }
                } else d.r = (d.r + dt * .06f).coerceAtMost(6.8f)
            }
            trail.forEach { it.age -= dt * .35f }; trail.removeAll { it.age <= 0f }
            if (trail.size > 120) trail.subList(0, trail.size - 120).clear()
        }
        if (sky == Sky.Storm) {
            nextFlash -= dt
            if (nextFlash <= 0f) { flash = 0f; nextFlash = 6f + Random.nextFloat() * 6f }
            flash += dt
        }
        for (f in flakes) { f.y += f.speed * dt; if (f.y > 1.05f) { f.y = -.05f; f.x = Random.nextFloat() } }
    }
    fun draw(s: DrawScope, density: Float) = with(s) {
        val w = size.width; val h = size.height
        if (sky == Sky.Fog) {
            for (k in 0 until 3) {
                val y = h * (.2f + .3f * k) + sin(time * .1f + k * 2f) * h * .03f; val x = w * (sin(time * .05f + k) * .2f)
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .085f), Color.Transparent), y - h * .12f, y + h * .12f), Offset(x - w * .2f, y - h * .12f), androidx.compose.ui.geometry.Size(w * 1.4f, h * .24f))
            }
        }
        for (d in trail) bead(Offset(d.x * w, d.y * h), d.r * density, d.age * .8f, 1f)
        for (d in drops) bead(Offset(d.x * w, d.y * h), d.r * density, d.age, if (d.sliding) 1f + (d.v * 5f).coerceAtMost(1.1f) else 1f)
        for (f in flakes) {
            val p = Offset((f.x + sin(time * 1.3f + f.phase) * f.sway) * w, f.y * h); val r = f.r * density
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .85f), Color.White.copy(alpha = .2f), Color.Transparent), p, r * 2.2f), r * 2.2f, p)
        }
        if (sky == Sky.Storm) {
            // Two quick flashes, a quarter of a second apart.
            val a = pulse(flash) + .7f * pulse(flash - .26f)
            if (a > .01f) drawRect(Color.White.copy(alpha = (a * .16f).coerceAtMost(.2f)))
        }
    }
    private fun pulse(x: Float) = if (x < 0f) 0f else exp(-x * 14f) * (1f - exp(-x * 90f))
    /**
     * A drop of water on glass, as a small lens: a soft contact shadow, a clear body a touch darker at the top and bright
     * at the bottom where it gathers the light, a thin rim, and a glint. A running drop stretches with its speed.
     */
    private fun DrawScope.bead(c: Offset, r: Float, alpha: Float, stretch: Float) {
        if (alpha <= 0f || r <= .3f) return
        val h = r * stretch; val top = Offset(c.x - r, c.y + r - 2 * h); val size = androidx.compose.ui.geometry.Size(r * 2, h * 2)
        drawOval(Color.Black.copy(alpha = .1f * alpha), top + Offset(-r * .05f, r * .22f), androidx.compose.ui.geometry.Size(size.width * 1.1f, size.height * 1.05f))
        drawOval(Brush.verticalGradient(0f to Color.Black.copy(alpha = .12f * alpha), .55f to Color.White.copy(alpha = .05f * alpha), 1f to Color.White.copy(alpha = .46f * alpha), startY = top.y, endY = top.y + size.height), top, size)
        drawOval(Color.White.copy(alpha = .24f * alpha), top, size, style = androidx.compose.ui.graphics.drawscope.Stroke((r * .12f).coerceAtLeast(.8f)))
        drawCircle(Color.White.copy(alpha = .92f * alpha), r * .24f, Offset(c.x - r * .36f, top.y + h * .5f))
    }
}

