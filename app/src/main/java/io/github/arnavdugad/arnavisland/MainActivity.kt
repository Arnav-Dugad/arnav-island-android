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
    override fun onResume() { super.onResume(); Hub.visible = true; Hub.sendBattery() }
    override fun onPause() { Hub.visible = false; super.onPause() }
    override fun onStop() { super.onStop(); if (!Hub.prefs.getBoolean("reachable", true) && !isChangingConfigurations) LinkService.stop(this) }
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
                share.value = ShareRequest(listOfNotNull(uri), intent.getStringExtra(Intent.EXTRA_TEXT))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                share.value = ShareRequest(uris.orEmpty().toList(), null)
            }
        }
        when (val what = intent.getStringExtra("open")) {
            null -> Unit
            "paste" -> { pasteOnFocus = true; if (hasWindowFocus()) { pasteOnFocus = false; pasteNow() } }
            else -> opened.value = what
        }
        intent.removeExtra("open")
        // Test builds only: a PC at a known address (an emulator can't hear discovery broadcasts).
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

@Composable private fun App(share: ShareRequest?, onShareDone: () -> Unit, opened: String?, onOpened: () -> Unit, link: io.github.arnavdugad.arnavisland.link.PairLink?, onLinkDone: () -> Unit, onDark: (Boolean) -> Unit) {
    val context = LocalContext.current
    var look by remember { mutableStateOf(Look(Hub.prefs.getInt("appearance", 0), Hub.prefs.getBoolean("glass", true), Hub.prefs.getBoolean("weatherGlass", true))) }
    val dark = when (look.appearance) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    LaunchedEffect(dark) { onDark(dark) }
    val reduced = remember { runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    val saver = rememberPowerSave()
    val peers by Hub.peers.collectAsState(); val selected by Hub.selected.collectAsState()
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
    val accent by animateColorAsState(art?.let { Color(it.vivid) } ?: Mint, tween(900), label = "Accent")
    val accent2 by animateColorAsState(art?.let { Color(it.second) } ?: Periwinkle, tween(900), label = "Accent2")
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

    var pairing by remember { mutableStateOf(false) }
    var pairStart by remember { mutableIntStateOf(PAIR_NEARBY) }
    var linkOpen by remember { mutableStateOf(false) }
    var updatesOpen by remember { mutableStateOf(false) }
    var trackpadOpen by remember { mutableStateOf(false) }
    var islandOpen by remember { mutableStateOf(false) }
    var hotspotOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tabs = listOf(TabItem("Remote", Icons.Rounded.Laptop), TabItem("Island", Icons.Rounded.Dashboard), TabItem("Send", Icons.AutoMirrored.Rounded.Send), TabItem("Shelf", Icons.Rounded.Inventory2), TabItem("Devices", Icons.Rounded.Devices))
    val pager = rememberPagerState { tabs.size }
    LaunchedEffect(pairCode) { if (pairCode != null) pairing = true }
    LaunchedEffect(link) { if (link != null) { Hub.pairWithCode(link.code, link.key); pairStart = PAIR_FINDING; pairing = true; onLinkDone() } }
    LaunchedEffect(status?.available) { if (status?.available != true) islandOpen = false }

    // A photo for a PC's Shelf: the camera app takes it, then it goes.
    var photo by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> val uri = photo; photo = null; if (taken && uri != null) Hub.sendPhoto(uri) else Hub.photoFor = null }
    fun shoot() = runCatching { val uri = newPhoto(context); photo = uri; camera.launch(uri) }.onFailure { Hub.photoFor = null; Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "No camera app", "Install one to take photos for your PC")) }
    // The app may use the camera itself (to scan pairing codes), so Android opens the camera app only once that is allowed.
    val cameraAllowed = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) shoot() else { Hub.photoFor = null; Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "The camera isn’t allowed", "Allow it in Settings › Apps › Arnav Island › Permissions")) }
    }
    fun takePhoto() {
        if (pc == null && Hub.photoFor == null) { pairing = true; return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) cameraAllowed.launch(Manifest.permission.CAMERA) else shoot()
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris -> pc?.let { Hub.send(it.id, uris) } }
    fun open(what: String) {
        when (what) {
            "pair" -> { pairStart = PAIR_NEARBY; pairing = true }; "pair_code" -> { pairStart = PAIR_TYPE; pairing = true }; "pair_scan" -> { pairStart = PAIR_SCAN; pairing = true }
            "updates" -> updatesOpen = true; "music_play" -> Hub.music.value?.let { Hub.answerMusic(it, true, context) }
            "camera" -> takePhoto(); "trackpad" -> if (pc != null) trackpadOpen = true else pairing = true
            "findpc" -> scope.launch { Hub.ringPc() }; "island" -> scope.launch { pager.animateScrollToPage(PAGE_ISLAND) }
            "send_photos" -> if (pc != null) pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) else pairing = true
        }
    }
    LaunchedEffect(opened) { if (opened != null) { open(opened); onOpened() } }
    val openNow by rememberUpdatedState<(String) -> Unit> { what -> open(what) }
    LaunchedEffect(Unit) { Hub.requests.collect { openNow(it) } }
    val banner by rememberBanner()

    CompositionLocalProvider(LocalTokens provides tokens, LocalReduced provides reduced, LocalTilt provides { tilt.value }, LocalGlassOn provides look.glass) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)) {
            val ambient = rememberLayerBackdrop(); val screen = rememberLayerBackdrop()
            val floating = remember(ambient, screen) { GlassBackdrops(ambient, screen) }
            val content = remember(ambient) { GlassBackdrops(ambient, ambient) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(); val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Box(Modifier.fillMaxSize().layerBackdrop(screen)) {
                    AmbientBackground(tokens.accent, tokens.accent2, tokens.deep, status?.playing == true, Modifier.layerBackdrop(ambient), still = saver)
                    CompositionLocalProvider(LocalGlass provides content) {
                        val insets = WindowInsets.systemBars.asPaddingValues()
                        val padding = PaddingValues(top = insets.calculateTopPadding() + 58.dp, bottom = insets.calculateBottomPadding() + if (playingHere != null) 180.dp else 104.dp)
                        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                            when (page) {
                                0 -> RemoteScreen(pc, status, statusError, cover, lyrics, { pairing = true }, { linkOpen = true }, { trackpadOpen = true }, padding)
                                PAGE_ISLAND -> IslandScreen(pc, pager.currentPage == PAGE_ISLAND, { pairing = true }, padding)
                                PAGE_SEND -> SendScreen(pc, peers, transfers, moments, { takePhoto() }, { pairing = true }, padding)
                                PAGE_SHELF -> ShelfScreen(pc, transfers, pager.currentPage == PAGE_SHELF, { pairing = true }, padding)
                                else -> DevicesScreen(peers, pc, running, failure, internet, look, { next ->
                                    look = next; Hub.prefs.edit().putInt("appearance", next.appearance).putBoolean("glass", next.glass).putBoolean("weatherGlass", next.weather).apply()
                                }, { pairStart = PAIR_NEARBY; pairing = true }, { pairStart = PAIR_SCAN; pairing = true }, { updatesOpen = true }, { hotspotOpen = true }, padding)
                            }
                        }
                    }
                }
                // Content fades out under the status bar and the island, as it scrolls up.
                Box(Modifier.fillMaxWidth().height(top + 64.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tokens.deep.copy(alpha = if (dark) .92f else .8f).compositeOverBlack(dark), Color.Transparent))))
                // The weather where the PC is, on the glass; and files in flight, between this phone's island and the PC.
                if (look.weather) WeatherGlass(skyOf(status?.weather.orEmpty()))
                val h = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                HandoffParticles(transfers, Offset(.5f, with(density) { (top + 27.dp).toPx() } / h), Offset(.5f, with(density) { (maxHeight - bottom - 130.dp).toPx() } / h))
                CompositionLocalProvider(LocalGlass provides floating) {
                    // The island opened: a tap anywhere else (or back) folds it away.
                    if (islandOpen) {
                        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { islandOpen = false })
                        BackHandler { islandOpen = false }
                    }
                    MiniIsland(status, cover, pc?.name, pc?.online == true, banner, transfers.values.maxByOrNull { it.id },
                        { if (islandOpen) islandOpen = false else if (status?.available == true && banner == null) islandOpen = true else scope.launch { pager.animateScrollToPage(if (transfers.isNotEmpty()) PAGE_SEND else 0) } },
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp), expanded = islandOpen, internet = pc?.internet == true, quality = pc?.let { quality(it) })
                    GlassTabBar(tabs, pager.currentPage + pager.currentPageOffsetFraction, { scope.launch { pager.animateScrollToPage(it) } },
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp).widthIn(max = 460.dp).fillMaxWidth())
                    androidx.compose.animation.AnimatedVisibility(playingHere != null, Modifier.align(Alignment.BottomCenter),
                        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut()) {
                        playingHere?.let { PlayingHere(it, Modifier.navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 92.dp).widthIn(max = 460.dp).fillMaxWidth()) }
                    }
                    PairSheet(pairing, peers, pairCode, pairResult, { pairing = false }, start = pairStart)
                    OfferSheet(offers.firstOrNull())
                    MusicSheet(music, transfers)
                    LinkSheet(linkOpen, pc) { linkOpen = false }
                    TrackpadSheet(trackpadOpen, pc) { trackpadOpen = false }
                    IslandSheets()
                    HotspotSheet(hotspotOpen) { hotspotOpen = false }
                    UpdatesSheet(updatesOpen) { updatesOpen = false }
                    ShareSheet(share, peers, pc, onShareDone)
                    WhatsNewSheet(whatsNew) { whatsNew = null }
                    RingOverlay(ringing)
                }
            }
        }
    }
}

/** The pager's pages (Remote is 0, Devices last). */
private const val PAGE_ISLAND = 1; private const val PAGE_SEND = 2; private const val PAGE_SHELF = 3

/** The top fade's colour: the deep ambient colour, darkened for the dark theme. */
private fun Color.compositeOverBlack(dark: Boolean): Color = if (dark) androidx.compose.ui.graphics.lerp(this, Color.Black, .55f).copy(alpha = alpha) else this
