package io.github.arnavdugad.arnavisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val share = mutableStateOf<ShareRequest?>(null)
    private val opened = mutableStateOf<String?>(null)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }
        Hub.init(this); Hub.prefs.edit().putBoolean("started", true).apply()
        LinkService.start(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !Hub.prefs.getBoolean("askedNotify", false)) {
            Hub.prefs.edit().putBoolean("askedNotify", true).apply(); notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        handle(intent)
        setContent { App(share.value, { share.value = null }, opened.value, { opened.value = null }, ::bars) }
    }
    /** The status and navigation bars' icons follow the app's theme (which may differ from the system's). */
    private fun bars(dark: Boolean) = runCatching {
        val clear = android.graphics.Color.TRANSPARENT
        val style = if (dark) androidx.activity.SystemBarStyle.dark(clear) else androidx.activity.SystemBarStyle.light(clear, clear)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onStart() { super.onStart(); LinkService.start(this) }
    override fun onResume() { super.onResume(); Hub.visible = true; Hub.sendBattery() }
    override fun onPause() { Hub.visible = false; super.onPause() }
    override fun onStop() { super.onStop(); if (!Hub.prefs.getBoolean("reachable", true) && !isChangingConfigurations) LinkService.stop(this) }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                share.value = ShareRequest(listOfNotNull(uri), intent.getStringExtra(Intent.EXTRA_TEXT))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                share.value = ShareRequest(uris.orEmpty().toList(), null)
            }
        }
        intent.getStringExtra("open")?.let { opened.value = it }
        // Test builds only: a PC at a known address (an emulator can't hear discovery broadcasts).
        if (BuildConfig.DEBUG) intent.getStringExtra("qa_peer")?.let { spec ->
            val id = spec.substringBefore('@'); val host = spec.substringAfter('@').substringBeforeLast(':'); val port = spec.substringAfterLast(':').toIntOrNull() ?: return@let
            Hub.scope.launch { repeat(50) { Hub.link?.let { it.addPeer(id, intent.getStringExtra("qa_name") ?: "Studio PC", host, port, 2, 2); return@launch }; delay(100) } }
        }
    }
}

@Composable private fun App(share: ShareRequest?, onShareDone: () -> Unit, opened: String?, onOpened: () -> Unit, onDark: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var appearance by remember { mutableIntStateOf(Hub.prefs.getInt("appearance", 0)) }
    val dark = when (appearance) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    LaunchedEffect(dark) { onDark(dark) }
    val reduced = remember { runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    val peers by Hub.peers.collectAsState(); val selected by Hub.selected.collectAsState()
    val status by Hub.status.collectAsState(); val statusError by Hub.statusError.collectAsState()
    val running by Hub.running.collectAsState(); val failure by Hub.failure.collectAsState()
    val transfers by Hub.transfers.collectAsState(); val moments by Hub.moments.collectAsState()
    val offers by Hub.offers.collectAsState(); val music by Hub.music.collectAsState(); val ringing by Hub.ringing.collectAsState()
    val pairCode by Hub.pairCode.collectAsState(); val pairResult by Hub.pairResult.collectAsState()
    val playingHere by Player.now.collectAsState()
    val pc = Hub.pc(peers, selected)

    // The cover of what plays on the PC, and the colours it lends the whole app.
    val coverKey = status?.coverHash?.contentHashCode() ?: 0
    val cover = remember(coverKey, status?.cover != null) { status?.cover?.let { Art.bitmap(it, 720) } }
    val art = remember(cover) { cover?.let { Art.colors(it) } }
    val accent by animateColorAsState(art?.let { Color(it.vivid) } ?: Mint, tween(900), label = "Accent")
    val accent2 by animateColorAsState(art?.let { Color(it.second) } ?: Periwinkle, tween(900), label = "Accent2")
    val deep by animateColorAsState(art?.let { Color(it.deep) } ?: Color(0xFF0B1220), tween(900), label = "Deep")
    val tokens = tokens(dark, accent, accent2, deep)
    val tilt = rememberTilt(!reduced)

    // The remote's status while the app is on screen: every second while music plays, a little less often otherwise.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(pc?.id, pc?.online) {
        Hub.status.value = null
        if (pc == null) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { Hub.refreshStatus(); delay(if (Hub.status.value?.playing == true) 1000 else 2000) } }
    }
    // Checks for updates once a launch; shows what's new after an update.
    var whatsNew by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(Unit) {
        val seen = Hub.prefs.getString("seenVersion", null)
        if (seen != null && seen != Updates.current) whatsNew = Updates.releases.value.firstOrNull { it.versionName == Updates.current.substringBefore('-') }
        Hub.prefs.edit().putString("seenVersion", Updates.current).apply()
        Updates.check()
    }

    var pairing by remember { mutableStateOf(false) }
    var linkOpen by remember { mutableStateOf(false) }
    var updatesOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pairCode) { if (pairCode != null) pairing = true }
    LaunchedEffect(opened) {
        when (opened) { "pair" -> pairing = true; "updates" -> updatesOpen = true; "music_play" -> Hub.music.value?.let { Hub.answerMusic(it, true, context) } }
        if (opened != null) onOpened()
    }
    val banner by rememberBanner()

    CompositionLocalProvider(LocalTokens provides tokens, LocalReduced provides reduced, LocalTilt provides { tilt.value }) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)) {
            val ambient = rememberLayerBackdrop(); val screen = rememberLayerBackdrop()
            val floating = remember(ambient, screen) { GlassBackdrops(ambient, screen) }
            val content = remember(ambient) { GlassBackdrops(ambient, ambient) }
            val tabs = listOf(TabItem("Remote", Icons.Rounded.Laptop), TabItem("Send", Icons.AutoMirrored.Rounded.Send), TabItem("Shelf", Icons.Rounded.Inventory2), TabItem("Devices", Icons.Rounded.Devices))
            val pager = rememberPagerState { tabs.size }
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().layerBackdrop(screen)) {
                    AmbientBackground(tokens.accent, tokens.accent2, tokens.deep, status?.playing == true, Modifier.layerBackdrop(ambient))
                    CompositionLocalProvider(LocalGlass provides content) {
                        val insets = WindowInsets.systemBars.asPaddingValues()
                        val padding = PaddingValues(top = insets.calculateTopPadding() + 58.dp, bottom = insets.calculateBottomPadding() + if (playingHere != null) 180.dp else 104.dp)
                        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                            when (page) {
                                0 -> RemoteScreen(pc, status, statusError, cover, { pairing = true }, { linkOpen = true }, padding)
                                1 -> SendScreen(pc, peers, transfers, moments, { linkOpen = true }, { pairing = true }, padding)
                                2 -> ShelfScreen(pc, transfers, pager.currentPage == 2, { pairing = true }, padding)
                                else -> DevicesScreen(peers, pc, running, failure, appearance, { appearance = it; Hub.prefs.edit().putInt("appearance", it).apply() }, { pairing = true }, { updatesOpen = true }, padding)
                            }
                        }
                    }
                }
                // Content fades out under the status bar and the island, as it scrolls up.
                Box(Modifier.fillMaxWidth().height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tokens.deep.copy(alpha = if (dark) .92f else .8f).compositeOverBlack(dark), Color.Transparent))))
                CompositionLocalProvider(LocalGlass provides floating) {
                    MiniIsland(status, cover, pc?.name, pc?.online == true, banner, transfers.values.maxByOrNull { it.id }, { scope.launch { pager.animateScrollToPage(if (transfers.isNotEmpty()) 1 else 0) } },
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp))
                    GlassTabBar(tabs, pager.currentPage + pager.currentPageOffsetFraction, { scope.launch { pager.animateScrollToPage(it) } },
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp).widthIn(max = 460.dp).fillMaxWidth())
                    androidx.compose.animation.AnimatedVisibility(playingHere != null, Modifier.align(Alignment.BottomCenter),
                        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut()) {
                        playingHere?.let { PlayingHere(it, Modifier.navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 92.dp).widthIn(max = 460.dp).fillMaxWidth()) }
                    }
                    PairSheet(pairing, peers, pairCode, pairResult) { pairing = false }
                    OfferSheet(offers.firstOrNull())
                    MusicSheet(music, transfers)
                    LinkSheet(linkOpen, pc) { linkOpen = false }
                    UpdatesSheet(updatesOpen) { updatesOpen = false }
                    ShareSheet(share, peers, pc, onShareDone)
                    WhatsNewSheet(whatsNew) { whatsNew = null }
                    RingOverlay(ringing)
                }
            }
        }
    }
}

/** The top fade's colour: the deep ambient colour, darkened for the dark theme. */
private fun Color.compositeOverBlack(dark: Boolean): Color = if (dark) androidx.compose.ui.graphics.lerp(this, Color.Black, .55f).copy(alpha = alpha) else this
