package io.github.arnavdugad.arnavisland

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val share = mutableStateOf<ShareRequest?>(null)
    private val opened = mutableStateOf<String?>(null)
    private val pairLink = mutableStateOf<io.github.arnavdugad.arnavisland.link.PairLink?>(null)
    private var pasteOnFocus = false
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
        setContent { App(share.value, { share.value = null }, opened.value, { opened.value = null }, pairLink.value, { pairLink.value = null }, ::bars) }
    }
    /** The status and navigation bars' icons follow the app's theme (which may differ from the system's). */
    private fun bars(dark: Boolean) = runCatching {
        val clear = android.graphics.Color.TRANSPARENT
        val style = if (dark) androidx.activity.SystemBarStyle.dark(clear) else androidx.activity.SystemBarStyle.light(clear, clear)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onStart() { super.onStart(); LinkService.start(this) }
    // 1.7: the ambient light's pace follows how long frames take to draw here.
    private val frameMetrics = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val interval = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(android.view.FrameMetrics.DEADLINE) / 1e6 else 1000.0 / 60
        if (metrics.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME) == 0L) AmbientPace.frame(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1e6, interval.coerceIn(4.0, 50.0))
    }
    private val metricsThread by lazy { android.os.HandlerThread("frame-metrics").apply { start() } }
    override fun onResume() { super.onResume(); Hub.visible = true; Hub.sendBattery(); runCatching { window.addOnFrameMetricsAvailableListener(frameMetrics, android.os.Handler(metricsThread.looper)) } }
    override fun onPause() { Hub.visible = false; runCatching { window.removeOnFrameMetricsAvailableListener(frameMetrics) }; super.onPause() }
    override fun onStop() { super.onStop(); if (!Hub.prefs.getBoolean("reachable", true) && !isChangingConfigurations) LinkService.stop(this) }
    /** 1.8: the volume keys go to whoever has asked for them (the PC's sound on the Remote, slides in the presenter). */
    private var volumeTaken = false
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val up = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        if (up || event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val handler = VolumeKeys.handler
            if (event.action == android.view.KeyEvent.ACTION_DOWN && handler != null) { volumeTaken = handler(up); if (volumeTaken) return true }
            else if (event.action == android.view.KeyEvent.ACTION_UP && volumeTaken) { volumeTaken = false; return true }
        }
        return super.dispatchKeyEvent(event)
    }
    /** Android lets the app read the clipboard only while it has the focus: a new copy goes to the PC now (universal clipboard, or Paste on PC). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (pasteOnFocus) { pasteOnFocus = false; pasteNow() } else Hub.clipboardOut(this)
    }
    private fun pasteNow() {
        val text = runCatching { getSystemService(android.content.ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        if (text.isBlank()) { Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Your clipboard is empty", "Copy something first")); return }
        Hub.scope.launch {
            for (i in 0 until 40) { if (Hub.link != null && Hub.pc()?.online == true) break; delay(100) }
            val pc = Hub.pc() ?: return@launch
            if (Hub.command(Proto.CMD_CLIP_SET, text.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${pc.name}’s clipboard", text.lineSequence().first().take(60)))
        }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        // An island's pairing link (its QR code, read by a camera or a scanner app): pairs at once.
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme.equals("arnavisland", ignoreCase = true)) {
            val link = io.github.arnavdugad.arnavisland.link.Relay.pairLink(intent.dataString.orEmpty())
            if (link != null) pairLink.value = link else Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "That link can’t pair", "Scan the QR code on your PC’s island again"))
            intent.data = null
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                // 1.7: a PC chosen in Android's share sheet (one of this app's share targets): sent there at once.
                share.value = ShareRequest(listOfNotNull(uri), intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.removePrefix("pc:"))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                share.value = ShareRequest(uris.orEmpty().toList(), null, intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.removePrefix("pc:"))
            }
        }
        when (val what = intent.getStringExtra("open")) {
            null -> Unit
            "paste" -> { pasteOnFocus = true; if (hasWindowFocus()) { pasteOnFocus = false; pasteNow() } }
            else -> opened.value = what
        }
        intent.removeExtra("open")
        // Test builds only: a PC at a known address (an emulator can't hear discovery broadcasts).
        // Test builds only: glass parts off, to measure each one's cost ("qa_glass_off": comma-separated names).
        if (BuildConfig.DEBUG) intent.getStringExtra("qa_glass_off")?.let { GlassTuning.off = it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.toSet(); intent.removeExtra("qa_glass_off") }
        if (BuildConfig.DEBUG) intent.getStringExtra("qa_peer")?.let { spec ->
            val id = spec.substringBefore('@'); val host = spec.substringAfter('@').substringBeforeLast(':'); val port = spec.substringAfterLast(':').toIntOrNull() ?: return@let
            Hub.scope.launch { repeat(50) { Hub.link?.let { it.addPeer(id, intent.getStringExtra("qa_name") ?: "Studio PC", host, port, 2, intent.getIntExtra("qa_revision", 3)); return@launch }; delay(100) } }
        }
    }
}

/** Android's battery saver: the light behind the glass stands still while it is on. */
@Composable private fun rememberPowerSave(): Boolean {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var on by remember { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(power) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { on = power?.isPowerSaveMode == true } }
        runCatching { ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return on
}

/** A new file for a photo taken for a PC (photos older than a day, long gone to the PC, are cleared). */
private fun newPhoto(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
    val file = File(dir, "Photo " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date()) + ".jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

/** The app's look: the theme (0 automatic, 1 dark, 2 light) and whether the glass is on. */
data class Look(val appearance: Int, val glass: Boolean)

/**
 * 1.8: the sheets the whole app shares, drawn over every tab and the tab bar: Settings, a PC's details, its connection,
 * the alerts, the presenter, and the rest. Any screen opens one by setting it here.
 */
object Ui {
    var settings by mutableStateOf(false); var pc by mutableStateOf<String?>(null); var connection by mutableStateOf<String?>(null)
    var alerts by mutableStateOf(false); var presenter by mutableStateOf(false); var updates by mutableStateOf(false); var hotspot by mutableStateOf(false)
    var link by mutableStateOf(false); var trackpad by mutableStateOf<Int?>(null); var song by mutableStateOf<Song?>(null); var upNext by mutableStateOf(false)
    var pairing by mutableStateOf(false); var pairStart by mutableIntStateOf(PAIR_NEARBY)
    fun pair(start: Int = PAIR_NEARBY) { pairStart = start; pairing = true }
}

@Composable private fun App(share: ShareRequest?, onShareDone: () -> Unit, opened: String?, onOpened: () -> Unit, link: io.github.arnavdugad.arnavisland.link.PairLink?, onLinkDone: () -> Unit, onDark: (Boolean) -> Unit) {
    val context = LocalContext.current
    var look by remember { mutableStateOf(Look(Hub.prefs.getInt("appearance", 0), Hub.prefs.getBoolean("glass", true))) }
    val dark = when (look.appearance) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    LaunchedEffect(dark) { onDark(dark) }
    // Android's "Remove animations", read again whenever the app comes back.
    var reduced by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        reduced = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
        onPauseOrDispose { }
    }
    val saver = rememberPowerSave()
    val peers by Hub.peers.collectAsState(); val selected by Hub.selected.collectAsState()
    // 1.7: your PCs in Android's share sheet, kept to the paired ones and their names.
    val targets = peers.filter { it.paired && !it.phone }.map { it.id to it.name }
    LaunchedEffect(targets) { ShareTargets.publish(context, peers) }
    val status by Hub.status.collectAsState(); val statusError by Hub.statusError.collectAsState()
    val running by Hub.running.collectAsState(); val failure by Hub.failure.collectAsState(); val internet by Hub.internet.collectAsState()
    val transfers by Hub.transfers.collectAsState(); val moments by Hub.moments.collectAsState()
    val offers by Hub.offers.collectAsState(); val music by Hub.music.collectAsState(); val ringing by Hub.ringing.collectAsState()
    val pairCode by Hub.pairCode.collectAsState(); val pairResult by Hub.pairResult.collectAsState()
    val lyrics by Hub.lyrics.collectAsState()
    val playingHere by Player.now.collectAsState()
    val pc = Hub.pc(peers, selected)

    // The cover of what plays on the PC, and the colours it lends the whole app.
    val coverKey = status?.coverHash?.contentHashCode() ?: 0
    val cover = remember(coverKey, status?.cover != null) { status?.cover?.let { Art.bitmap(it, 720) } }
    val art = remember(cover) { cover?.let { Art.colors(it) } }
    val accent by animateColorAsState(art?.let { Color(it.vivid) } ?: SystemBlue, tween(900), label = "Accent")
    val accent2 by animateColorAsState(art?.let { Color(it.second) } ?: SystemIndigo, tween(900), label = "Accent2")
    val deep by animateColorAsState(art?.let { Color(it.deep) } ?: Color(0xFF0B1220), tween(900), label = "Deep")
    val tokens = tokens(dark, accent, accent2, deep)
    // Without the glass, nothing follows the tilt: the sensor stays off.
    val tilt = rememberTilt(!reduced && look.glass)

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

    val scope = rememberCoroutineScope()
    val tabs = listOf(TabItem("Remote", Icons.Rounded.PlayCircle), TabItem("Island", Icons.Rounded.SpaceDashboard), TabItem("Send", Icons.AutoMirrored.Rounded.Send), TabItem("Shelf", Icons.Rounded.Inventory2), TabItem("Devices", Icons.Rounded.Devices))
    val pager = rememberPagerState { tabs.size }
    val bars = remember { List(tabs.size) { TopBar() } }
    LaunchedEffect(pairCode) { if (pairCode != null) Ui.pairing = true }
    LaunchedEffect(link) { if (link != null) { Hub.pairWithCode(link.code, link.key); Ui.pair(PAIR_FINDING); onLinkDone() } }

    // A photo for a PC's Shelf: the camera app takes it, then it goes.
    var photo by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> val uri = photo; photo = null; if (taken && uri != null) Hub.sendPhoto(uri) else Hub.photoFor = null }
    fun shoot() = runCatching { val uri = newPhoto(context); photo = uri; camera.launch(uri) }.onFailure { Hub.photoFor = null; Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "No camera app", "Install one to take photos for your PC")) }
    // The app may use the camera itself (to scan pairing codes), so Android opens the camera app only once that is allowed.
    val cameraAllowed = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) shoot() else { Hub.photoFor = null; Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "The camera isn’t allowed", "Allow it in Settings › Apps › Arnav Island › Permissions")) }
    }
    fun takePhoto() {
        if (pc == null && Hub.photoFor == null) { Ui.pair(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) cameraAllowed.launch(Manifest.permission.CAMERA) else shoot()
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris -> pc?.let { Hub.send(it.id, uris) } }
    fun open(what: String) {
        when (what) {
            "pair" -> Ui.pair(); "pair_code" -> Ui.pair(PAIR_TYPE); "pair_scan" -> Ui.pair(PAIR_SCAN)
            "updates" -> Ui.updates = true; "music_play" -> Hub.music.value?.let { Hub.answerMusic(it, true, context) }
            "camera" -> takePhoto(); "trackpad" -> if (pc != null) Ui.trackpad = TRACKPAD_PAD else Ui.pair()
            "findpc" -> scope.launch { Hub.ringPc() }
            "island" -> scope.launch { pager.animateScrollToPage(PAGE_ISLAND) }; "remote" -> scope.launch { pager.animateScrollToPage(0) }
            "send" -> scope.launch { pager.animateScrollToPage(PAGE_SEND) }
            "send_photos" -> if (pc != null) pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) else Ui.pair()
        }
    }
    LaunchedEffect(opened) { if (opened != null) { open(opened); onOpened() } }
    val openNow by rememberUpdatedState<(String) -> Unit> { what -> open(what) }
    LaunchedEffect(Unit) { Hub.requests.collect { openNow(it) } }
    val banner = rememberBanner()

    CompositionLocalProvider(LocalTokens provides tokens, LocalReduced provides reduced, LocalTilt provides { tilt.value }, LocalGlassOn provides look.glass) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)) {
            val ambient = rememberLayerBackdrop(); val screen = rememberLayerBackdrop()
            val floating = remember(ambient, screen) { GlassBackdrops(ambient, screen) }
            val content = remember(ambient) { GlassBackdrops(ambient, ambient) }
            Box(Modifier.fillMaxSize()) {
                // 1.7: a finger on the screen (and a fling's settling after) holds the ambient light still.
                val touchScope = rememberCoroutineScope(); var release by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                Box(Modifier.fillMaxSize().layerBackdrop(screen).pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (e.changes.any { it.pressed }) { release?.cancel(); Scene.touching = true }
                            else if (Scene.touching) { release?.cancel(); release = touchScope.launch { kotlinx.coroutines.delay(700); Scene.touching = false } }
                        }
                    }
                }) {
                    AmbientBackground(tokens.accent, tokens.accent2, tokens.deep, status?.playing == true, Modifier.layerBackdrop(ambient), still = saver)
                    CompositionLocalProvider(LocalGlass provides content) {
                        val insets = WindowInsets.systemBars.asPaddingValues()
                        val padding = PaddingValues(top = insets.calculateTopPadding() + NavBarHeight, bottom = insets.calculateBottomPadding() + if (playingHere != null) 172.dp else 100.dp)
                        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                            when (page) {
                                0 -> RemoteScreen(pc, status, statusError, cover, lyrics, bars[0], pager.currentPage == 0 && !Ui.presenter && Ui.trackpad == null, padding)
                                PAGE_ISLAND -> IslandScreen(pc, pager.currentPage == PAGE_ISLAND, bars[PAGE_ISLAND], padding)
                                PAGE_SEND -> SendScreen(pc, peers, transfers, moments, bars[PAGE_SEND], { takePhoto() }, padding)
                                PAGE_SHELF -> ShelfScreen(pc, transfers, pager.currentPage == PAGE_SHELF, bars[PAGE_SHELF], padding)
                                else -> DevicesScreen(peers, pc, running, failure, internet, bars[4], padding)
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalGlass provides floating) {
                    // The navigation bar of the page in view (the nearer one while swiping between two).
                    NavBar(bars[(pager.currentPage + pager.currentPageOffsetFraction).roundToInt().coerceIn(0, tabs.lastIndex)], Modifier.align(Alignment.TopCenter))
                    GlassTabBar(tabs, pager.currentPage + pager.currentPageOffsetFraction, { scope.launch { pager.animateScrollToPage(it) } },
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 460.dp).fillMaxWidth())
                    androidx.compose.animation.AnimatedVisibility(playingHere != null, Modifier.align(Alignment.BottomCenter),
                        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut()) {
                        playingHere?.let { PlayingHere(it, Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 88.dp).widthIn(max = 460.dp).fillMaxWidth()) }
                    }
                    PairSheet(Ui.pairing, peers, pairCode, pairResult, { Ui.pairing = false }, start = Ui.pairStart)
                    OfferSheet(offers.firstOrNull())
                    MusicSheet(music, transfers)
                    LinkSheet(Ui.link, pc) { Ui.link = false }
                    TrackpadSheet(Ui.trackpad != null, pc, Ui.trackpad ?: TRACKPAD_PAD) { Ui.trackpad = null }
                    PresenterSheet(Ui.presenter, pc) { Ui.presenter = false }
                    IslandSheets()
                    RemoteSheets(pc)
                    ShelfSheets()
                    SettingsSheet(Ui.settings, look, { next -> look = next; Hub.prefs.edit().putInt("appearance", next.appearance).putBoolean("glass", next.glass).apply() }) { Ui.settings = false }
                    PcSheet(Ui.pc?.let { id -> peers.firstOrNull { it.id == id } }) { Ui.pc = null }
                    ConnectionSheet(Ui.connection?.let { id -> peers.firstOrNull { it.id == id } }) { Ui.connection = null }
                    AlertsSheet(Ui.alerts) { Ui.alerts = false }
                    HotspotSheet(Ui.hotspot) { Ui.hotspot = false }
                    UpdatesSheet(Ui.updates) { Ui.updates = false }
                    ShareSheet(share, peers, pc, onShareDone)
                    WhatsNewSheet(whatsNew) { whatsNew = null }
                    // What just happened, dropping from the top as iOS's notifications do.
                    TopBanner(banner, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 520.dp))
                    RingOverlay(ringing)
                }
            }
        }
    }
}

/** The pager's pages (Remote is 0, Devices last). */
private const val PAGE_ISLAND = 1; private const val PAGE_SEND = 2; private const val PAGE_SHELF = 3
