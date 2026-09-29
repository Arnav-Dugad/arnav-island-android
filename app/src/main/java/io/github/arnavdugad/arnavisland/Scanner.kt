package io.github.arnavdugad.arnavisland

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.arnavdugad.arnavisland.link.PairLink
import io.github.arnavdugad.arnavisland.link.Relay
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * Reads QR codes from camera frames: each frame's brightness plane goes straight to ZXing, which reads a QR code at any
 * angle (and, failing that, inverted, for a light code on a dark screen). [accept] turns a text into what was looked for,
 * or null to keep looking.
 */
class QrAnalyzer<T : Any>(private val accept: (String) -> T?, private val found: (T) -> Unit, private val other: () -> Unit = {}) : ImageAnalysis.Analyzer {
    @Volatile private var done = false
    // One buffer for every frame (a frame's brightness is about a megabyte), on the analyzer's one thread.
    private var data = ByteArray(0); private var frames = 0
    override fun analyze(image: ImageProxy) {
        try {
            if (done) return
            val plane = image.planes[0]; val buffer = plane.buffer
            if (data.size != buffer.remaining()) data = ByteArray(buffer.remaining())
            buffer.get(data)
            // The island draws its code dark on white: light on dark is tried only every fourth frame.
            val text = read(luminance(data, plane.rowStride, image.width, image.height), inverted = ++frames % 4 == 0) ?: return
            val result = accept(text)
            if (result != null) { done = true; found(result) } else other()
        } catch (_: Exception) {
        } finally { image.close() }
    }
    companion object {
        private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        /** A frame's brightness plane (rows [rowStride] bytes apart; the last may stop short of a full stride) as ZXing reads it. */
        fun luminance(data: ByteArray, rowStride: Int, width: Int, height: Int): LuminanceSource {
            val rows = minOf(height, (data.size + rowStride - width) / rowStride)
            return PlanarYUVLuminanceSource(data, rowStride, rows, 0, 0, width, rows, false)
        }
        /** The text of the QR code in a picture ([inverted]: also as light on dark), or null when there is none. */
        fun read(source: LuminanceSource, inverted: Boolean = true): String? {
            val reader = QRCodeReader()
            return runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
                ?: if (!inverted) null else runCatching { reader.reset(); reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }.getOrNull()
        }
    }
}

/**
 * Pairing by the island's QR code (Shelf › Nearby › Pair with a code), in the pairing sheet: a live viewfinder whose
 * corners breathe and a band of light sweeps down it while it looks; on the code, the corners close in, glow and pairing
 * begins. The camera runs only while this shows, and nothing it sees is kept.
 */
@Composable fun ScanPane(onLink: (PairLink) -> Unit, onType: () -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current
    fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(allowed()) }
    // Refused for good (Android asks no more): the button opens the app's settings instead.
    var blocked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; val activity = context.activity()
        blocked = !ok && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { granted = allowed(); onPauseOrDispose { } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Scan the QR code", style = Type.title, color = t.text)
        Spacer(Modifier.height(4.dp))
        Text("On your PC: the island’s Shelf › Nearby › Pair with a code. Any network works.", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth(.9f).aspectRatio(1f).clip(RoundedCornerShape(34.dp)).background(Color(0xFF05070B)), contentAlignment = Alignment.Center) {
            if (granted) Viewfinder(onLink)
            else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Rounded.PhotoCamera, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text("The camera reads the code", style = Type.bodyStrong, color = Color.White, textAlign = TextAlign.Center)
                Text("Nothing it sees is kept or sent", style = Type.caption, color = Color.White.copy(alpha = .7f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                GlassButton({
                    if (blocked) runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    else ask.launch(Manifest.permission.CAMERA)
                }, prominent = true, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp)) { Text(if (blocked) "Allow it in Settings" else "Allow the camera", style = Type.bodyStrong) }
            }
        }
        Spacer(Modifier.height(16.dp))
        GlassButton(onType, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)) {
            Icon(Icons.Rounded.Keyboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Type the code instead", style = Type.caption)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** The camera's picture with the viewfinder over it; [onLink] once a pairing link is read (after its little lock-on). */
@Composable private fun Viewfinder(onLink: (PairLink) -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current; val owner = LocalLifecycleOwner.current; val haptics = LocalHapticFeedback.current
    val reduced = LocalReduced.current
    var locked by remember { mutableStateOf<PairLink?>(null) }
    // Another QR code in view: said for two seconds.
    var wrongAt by remember { mutableLongStateOf(0L) }; var wrongShown by remember { mutableStateOf(false) }
    LaunchedEffect(wrongAt) { if (wrongAt > 0) { wrongShown = true; delay(2_000); wrongShown = false } }
    var failed by remember { mutableStateOf(false) }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val onFound by rememberUpdatedState(onLink)
    DisposableEffect(owner) {
        val main = Handler(Looper.getMainLooper()); val executor = Executors.newSingleThreadExecutor(); var lastWrong = 0L
        val future = ProcessCameraProvider.getInstance(context); var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                val p = future.get(); provider = p
                val show = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val selector = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val analysis = ImageAnalysis.Builder().setResolutionSelector(selector).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor, QrAnalyzer({ Relay.pairLink(it) }, { link -> main.post { locked = link } }, { val now = System.currentTimeMillis(); if (now - lastWrong > 1_000) { lastWrong = now; main.post { wrongAt = now } } }))
                // The back camera; a tablet or laptop with only a front one uses that.
                val lens = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                p.unbindAll(); p.bindToLifecycle(owner, lens, show, analysis)
            }.onFailure { failed = true }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { provider?.unbindAll() }; executor.shutdown() }
    }
    // Locked on: a firm tap, the corners close in and glow, then pairing starts.
    LaunchedEffect(locked) { val link = locked ?: return@LaunchedEffect; haptics.performHapticFeedback(HapticFeedbackType.Confirm); delay(if (reduced) 0 else 520); onFound(link) }
    val lock by animateFloatAsState(if (locked != null) 1f else 0f, spring(dampingRatio = .62f, stiffness = 420f), label = "Lock")
    val sweep = if (reduced) 0f else rememberInfiniteTransition(label = "Sweep").animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "S").value
    val breath = if (reduced) 0f else rememberInfiniteTransition(label = "Breath").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "B").value
    Box(Modifier.fillMaxSize().semantics { contentDescription = "Camera, looking for the island’s QR code" }) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            // The frame: a square window, the rest dimmed.
            val side = minOf(w, h) * (.68f - .1f * lock - .015f * breath); val l = (w - side) / 2; val top = (h - side) / 2
            val dim = Color.Black.copy(alpha = .38f)
            drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(w, top)); drawRect(dim, Offset(0f, top + side), androidx.compose.ui.geometry.Size(w, h - top - side))
            drawRect(dim, Offset(0f, top), androidx.compose.ui.geometry.Size(l, side)); drawRect(dim, Offset(l + side, top), androidx.compose.ui.geometry.Size(w - l - side, side))
            val color = lerp(Color.White, t.good, lock); val arm = side * .2f; val stroke = 5.dp.toPx()
            if (lock > 0f) drawRoundRect(Brush.radialGradient(listOf(t.good.copy(alpha = .35f * lock), Color.Transparent), Offset(w / 2, h / 2), side * .8f), Offset(l, top), androidx.compose.ui.geometry.Size(side, side))
            for ((cx, cy, dx, dy) in listOf(floatArrayOf(l, top, 1f, 1f), floatArrayOf(l + side, top, -1f, 1f), floatArrayOf(l, top + side, 1f, -1f), floatArrayOf(l + side, top + side, -1f, -1f))) {
                drawLine(color, Offset(cx, cy), Offset(cx + arm * dx, cy), stroke, StrokeCap.Round)
                drawLine(color, Offset(cx, cy), Offset(cx, cy + arm * dy), stroke, StrokeCap.Round)
            }
            // A band of light sweeping down while it looks.
            if (lock == 0f && !reduced) {
                val y = top + side * sweep
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, t.accent.copy(alpha = .28f), Color.Transparent), y - 26.dp.toPx(), y + 26.dp.toPx()), Offset(l + stroke, y - 26.dp.toPx()), androidx.compose.ui.geometry.Size(side - 2 * stroke, 52.dp.toPx()))
                drawLine(t.accent.copy(alpha = .8f), Offset(l + stroke * 2, y), Offset(l + side - stroke * 2, y), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
        val note = when {
            failed -> "The camera couldn’t start"
            locked != null -> "Found it"
            wrongShown -> "That isn’t an Arnav Island code"
            else -> "Point it at the island’s QR code"
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .5f)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.QrCodeScanner, null, tint = if (locked != null) t.good else Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
            Text(note, style = Type.caption, color = Color.White)
        }
    }
}
