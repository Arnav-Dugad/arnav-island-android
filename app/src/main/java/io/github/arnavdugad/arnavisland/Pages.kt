package io.github.arnavdugad.arnavisland

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1.7: web pages handed over, either way, where they were scrolled to. From the PC (island 0.25): a notification, and
 * the page opens in the app's reader at the same place. From the reader: Continue on your PC opens it there, at the
 * same place too.
 */
object Pages {
    private const val CHANNEL = "pages"; private const val ID = 10

    fun arrived(context: Context, pcName: String, url: String, title: String, scroll: Float) {
        val open = Intent(context, ReaderActivity::class.java).putExtra("url", url).putExtra("scroll", scroll).putExtra("from", pcName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Pages from your PC", NotificationManager.IMPORTANCE_HIGH).apply { description = "A page your PC handed over, to read here" })
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(title.ifBlank { Uri.parse(url).host ?: "A page" }).setContentText("From $pcName  ·  ${if (scroll > .02f) "where you were" else "tap to read"}")
            .setContentIntent(PendingIntent.getActivity(context, url.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION).setTimeoutAfter(10 * 60_000).build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, n) }
        // With the app on screen, it opens at once as well.
        if (Hub.visible) runCatching { context.startActivity(open) }
    }
    fun cancel(context: Context) { runCatching { NotificationManagerCompat.from(context).cancel(ID) } }
}

/** The app's reader: a page in glass chrome, with Continue on your PC. */
class ReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Hub.init(this); enableEdgeToEdge(); Pages.cancel(this)
        val url = intent.getStringExtra("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: run { finish(); return }
        val scroll = intent.getFloatExtra("scroll", -1f)
        setContent { CompositionLocalProvider(LocalTokens provides tokens(true)) { PageReader(url, scroll, intent.getStringExtra("from")) { finish() } } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable fun PageReader(start: String, scroll: Float, from: String?, onClose: () -> Unit) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("") }; var address by remember { mutableStateOf(start) }; var progress by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(scroll <= .005f) }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(said) { if (said != null) { delay(2600); said = null } }
    val pc = Hub.pc()
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else onClose() }
    Box(Modifier.fillMaxSize().background(t.deep)) {
        AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !(request.url.scheme == "https" || request.url.scheme == "http")
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { address = url }
                    override fun onPageFinished(view: WebView, url: String) {
                        title = view.title.orEmpty()
                        // Where it was: once loaded, and again a moment later (a page that grows as it loads moves).
                        if (!placed) scope.launch { for (wait in listOf(0L, 900L)) { delay(wait); view.evaluateJavascript("(function(){var h=document.documentElement.scrollHeight-innerHeight;window.scrollTo(0,Math.max(0,h*$scroll));})()", null) }; placed = true }
                    }
                }
                webChromeClient = object : WebChromeClient() { override fun onProgressChanged(view: WebView, p: Int) { progress = p }; override fun onReceivedTitle(view: WebView, s: String?) { title = s.orEmpty() } }
                loadUrl(start); web = this
            }
        }, modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp))
        // The loading line.
        val shown by animateFloatAsState(progress / 100f, tween(260), label = "Loading")
        if (progress in 1..99) Box(Modifier.statusBarsPadding().padding(top = 64.dp).fillMaxWidth(shown).height(2.dp).background(t.accent))
        // The bar: back, the page, and on to the PC.
        Row(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().glass(GlassShapes.capsule, GlassLevel.Bar).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { if (web?.canGoBack() == true) web?.goBack() else onClose() }, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "Loading…" }, style = Type.bodyStrong, color = t.text, maxLines = 1)
                Text((Uri.parse(address).host ?: address) + (if (from != null) "  ·  from $from" else ""), style = Type.caption, color = t.muted, maxLines = 1)
            }
            GlassIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Open in your browser", { runCatching { web?.context?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, size = 40.dp)
            Spacer(Modifier.width(6.dp))
            if (pc != null) GlassIconButton(Icons.Rounded.DesktopWindows, "Continue on ${pc.name}", {
                val w = web ?: return@GlassIconButton
                w.evaluateJavascript("(function(){var h=document.documentElement.scrollHeight-innerHeight;return h>0?scrollY/h:0;})()") { v ->
                    val f = v?.toFloatOrNull() ?: -1f
                    // An island before 0.25 opens it at the top.
                    scope.launch {
                        val r = if (pc.revision >= 8) Hub.command(Proto.CMD_PAGE, Link.pagePayload(address, title, f), quiet = true) else Hub.command(Proto.CMD_OPEN, address.toByteArray(), quiet = true)
                        said = if (r?.ok == true) "On ${pc.name}${if (pc.revision >= 8 && f > .02f) ", where you were" else ""}" else if (r?.status == Proto.NOT_ALLOWED) "Turn on “My phone can control this PC” on ${pc.name}" else "${pc.name} didn’t answer"
                    }
                }
            }, size = 40.dp, prominent = true)
        }
        // What happened, in a glass pill at the bottom.
        androidx.compose.animation.AnimatedVisibility(said != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 }, exit = androidx.compose.animation.fadeOut()) {
            Text(said.orEmpty(), style = Type.bodyStrong, color = t.text, modifier = Modifier.glass(GlassShapes.capsule, GlassLevel.Bar).padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}
