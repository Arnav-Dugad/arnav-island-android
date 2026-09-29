package io.github.arnavdugad.arnavisland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Mote(val born: Long, val life: Float, val from: Offset, val bend: Offset, val to: Offset, val size: Float, val second: Boolean)

/**
 * Files in flight, seen: while something goes to your PC, motes of light stream up into the app's island (and on to
 * the PC); while something comes from it, they pour out of the island. More of them the faster it goes; a finished
 * send bursts from the island. Fractions of the screen: [island] where the island sits, [dock] where sends start.
 */
@Composable fun HandoffParticles(transfers: Map<Int, Transfer>, island: Offset, dock: Offset, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val reduced = LocalReduced.current; val density = LocalDensity.current.density
    if (reduced) return
    val motes = remember { ArrayList<Mote>() }
    var frame by remember { mutableLongStateOf(0L) }
    val sending by rememberUpdatedState(transfers.values.any { it.outgoing }); val receiving by rememberUpdatedState(transfers.values.any { !it.outgoing })
    val rate by rememberUpdatedState(transfers.values.sumOf { it.rate })
    // A send that finished (it was nearly all there when it left the list) bursts from the island.
    val seen = remember { HashMap<Int, Transfer>() }
    val bursts = remember { mutableIntStateOf(0) }
    LaunchedEffect(transfers) {
        val gone = seen.keys - transfers.keys
        gone.forEach { id -> val last = seen[id]; if (last != null && last.outgoing && last.total > 0 && last.done >= last.total * .97) bursts.intValue++ }
        seen.clear(); seen.putAll(transfers)
    }
    val active = transfers.isNotEmpty()
    val burst = bursts.intValue
    LaunchedEffect(active, burst) {
        if (burst > 0) {
            val now = System.nanoTime()
            repeat(22) { k -> val a = 2 * PI * k / 22 + Random.nextDouble() * .2; val d = .1f + Random.nextFloat() * .08f
                motes += Mote(now, .7f + Random.nextFloat() * .3f, island, island, island + Offset((cos(a) * d).toFloat(), (sin(a) * d * .55f).toFloat()), 2.2f + Random.nextFloat() * 2f, k % 2 == 0) }
        }
        var carry = 0f; var last = System.nanoTime()
        while (isActive && (active || motes.isNotEmpty())) {
            val now = withFrameNanosSafe()
            val dt = ((now - last) / 1e9f).coerceAtMost(.05f); last = now
            if (sending || receiving) {
                carry += dt * (14f + 26f * (rate / (1 shl 20)).toFloat().coerceIn(0f, 1f))
                while (carry >= 1f) {
                    carry -= 1f
                    val up = sending && (!receiving || Random.nextBoolean())
                    val side = (Random.nextFloat() - .5f) * .5f
                    val start = if (up) dock + Offset(side * .5f, Random.nextFloat() * .03f) else island
                    val end = if (up) island + Offset(0f, -.03f) else dock + Offset(side, Random.nextFloat() * .05f)
                    motes += Mote(now, .9f + Random.nextFloat() * .5f, start, Offset((start.x + end.x) / 2 + side * .6f, (start.y + end.y) / 2), end, 1.6f + Random.nextFloat() * 2.4f, Random.nextBoolean())
                }
            }
            motes.removeAll { (now - it.born) / 1e9f > it.life }
            frame = now
        }
    }
    Canvas(modifier.fillMaxSize()) {
        frame.hashCode()
        val now = System.nanoTime(); val w = size.width; val h = size.height
        for (m in motes) {
            val f = ((now - m.born) / 1e9f / m.life).coerceIn(0f, 1f)
            val e = if (f < .5f) 4 * f * f * f else 1 - (-2 * f + 2).let { it * it * it } / 2
            val u = 1 - e
            val x = u * u * m.from.x + 2 * u * e * m.bend.x + e * e * m.to.x; val y = u * u * m.from.y + 2 * u * e * m.bend.y + e * e * m.to.y
            val p = Offset(x * w, y * h); val alpha = sin(PI.toFloat() * f); val r = m.size * density * (1f - .45f * f)
            val color = if (m.second) t.accent2 else t.accent
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .45f * alpha), Color.Transparent), p, r * 3.2f), r * 3.2f, p)
            drawCircle(Color.White.copy(alpha = .9f * alpha), r * .55f, p)
        }
    }
}

/** The next frame's time, as a suspending call (the loop above stops with its composition). */
private suspend fun withFrameNanosSafe(): Long = androidx.compose.runtime.withFrameNanos { it }
