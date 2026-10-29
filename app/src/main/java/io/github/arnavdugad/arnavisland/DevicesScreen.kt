package io.github.arnavdugad.arnavisland

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Your devices (1.8: the settings moved to their own sheet): this phone, what's left to set up, your PCs (each with its
 * connection, when it was last here, and its details a tap away), and pairing another.
 */
@Composable fun DevicesScreen(peers: List<PeerView>, pc: PeerView?, running: Boolean, failure: String?, internet: Boolean, bar: TopBar, padding: PaddingValues) {
    val t = LocalTokens.current; val scroll = rememberScrollState()
    bar.follow("Devices", scroll, actions = { GlassIconButton(Icons.Rounded.Settings, "Settings", { Ui.settings = true }, size = 38.dp, iconSize = 19.dp) })
    val anywhere = Hub.prefs.getBoolean("internet", true)
    val seen by Hub.lastSeen.collectAsState(); Hub.looks.collectAsState().value
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = 16.dp)) {
        ScreenTitle("Devices", over = "Arnav Island")
        // This phone.
        GlassPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.PhoneAndroid, t.good, size = 50.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(Hub.phoneName(), style = Type.headline, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(failure ?: when { !running -> "Starting…"; internet -> "Reachable anywhere"; anywhere -> "Connecting…"; else -> "On this Wi-Fi" },
                        style = Type.caption, color = if (failure != null) t.danger else t.muted)
                }
                LiveDot(running && failure == null)
            }
        }
        HealthCard()
        val paired = peers.filter { it.paired }
        Section("Your PCs") {
            paired.forEachIndexed { i, p ->
                if (i > 0) Hairline()
                // 1.3: its connection's quality ring; 1.8: when it was last here (#39), its own icon and colour (#40).
                val q = quality(p)
                GlassRow(pcIcon(p.id, p.phone), p.name, listOfNotNull(if (p.online) q.text else seen[p.id]?.let { "Last seen ${ago(it).lowercase()}" } ?: "Away",
                    if (p.online && !p.remote) "Update needed" else null).joinToString("  ·  "),
                    tint = if (p.online) pcColor(p.id) else t.gray, ring = if (p.online) q else null, chevron = true, onClick = { Ui.pc = p.id }) {
                    if (p.id == pc?.id) Icon(Icons.Rounded.CheckCircle, "Remote and sends go here", tint = t.blue, modifier = Modifier.size(20.dp))
                }
            }
            if (paired.isNotEmpty()) Hairline()
            GlassRow(Icons.Rounded.Add, "Pair a PC", tint = t.blue, chevron = true, onClick = { Ui.pair() })
            Hairline()
            GlassRow(Icons.Rounded.QrCodeScanner, "Scan QR code", tint = t.indigo, chevron = true, onClick = { Ui.pair(PAIR_SCAN) })
        }
        Section(null) {
            GlassRow(Icons.Rounded.Settings, "Settings", tint = t.gray, chevron = true, onClick = { Ui.settings = true })
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 1.8: what's left to set up (#36): each permission the app is still missing for what it does, with a tap to give it
 * and a cross to not be asked again. Gone when nothing's left.
 */
@Composable private fun HealthCard() {
    val t = LocalTokens.current; val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { version++; onPauseOrDispose { } }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { Hub.prefs.edit().putBoolean("recent_photos", true).apply(); RecentPhotos.sync(context) }; version++ }
    class Missing(val key: String, val icon: ImageVector, val colour: Color, val title: String, val detail: String, val fix: () -> Unit)
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val missing = remember(version) {
        fun open(i: Intent) = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        buildList {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Missing("notify", Icons.Rounded.Notifications, t.danger, "Notifications", "For files, music and alerts") { notify.launch(Manifest.permission.POST_NOTIFICATIONS) })
            if (!Mirror.allowed(context)) add(Missing("listener", Icons.Rounded.NotificationsActive, t.warn, "Notification access", "Show notifications on your PC") { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            if (power?.isIgnoringBatteryOptimizations(context.packageName) == false) add(Missing("battery", Icons.Rounded.BatteryAlert, t.good, "Run in the background", "So your PCs can always reach it") {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))).onFailure { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } })
            if (!RecentPhotos.permitted(context)) add(Missing("photos", Icons.Rounded.PhotoLibrary, t.pink, "Photo access", "Show new photos on your PC") { photos.launch(RecentPhotos.permission()) })
            if (!PhoneScreen.controllable(context)) add(Missing("access", Icons.Rounded.TouchApp, t.blue, "Control from your PC", "Let your PC tap and type here") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        }.filterNot { Hub.prefs.getBoolean("health_skip_${it.key}", false) }
    }
    AnimatedVisibility(missing.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Section(if (missing.size == 1) "1 thing to finish" else "${missing.size} things to finish") {
            missing.forEachIndexed { i, m ->
                if (i > 0) Hairline()
                GlassRow(m.icon, m.title, m.detail, tint = m.colour) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassButton(m.fix, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Fix", style = Type.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = LocalTokens.current.blue) }
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(32.dp).clip(CircleShape).clickable { Hub.prefs.edit().putBoolean("health_skip_${m.key}", true).apply(); version++ }.semantics { contentDescription = "Don't ask about ${m.title}" }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, null, tint = t.faint, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The app's version, whether a newer one is out (and its download), and the way to the release history. */
@Composable fun UpdateCard(onUpdates: () -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current; val scope = rememberCoroutineScope()
    val state by Updates.state.collectAsState()
    var auto by remember { mutableStateOf(Updates.auto()) }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(t.blue), contentAlignment = Alignment.Center) {
                    when (val s = state) {
                        is Updates.State.Downloading -> ProgressRing(s.fraction, Color.White, Color.White.copy(alpha = .3f), Modifier.size(32.dp), 3.5.dp)
                        is Updates.State.Checking, is Updates.State.Installing -> { val spin by rememberInfiniteTransition(label = "U").animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "R")
                            Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = spin }) }
                        is Updates.State.Available -> Icon(Icons.Rounded.NewReleases, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        else -> Icon(Icons.Rounded.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Arnav Island ${Updates.current}", style = Type.bodyStrong, color = t.text)
                    Text(when (val s = state) {
                        Updates.State.Idle -> if (Updates.lastChecked() > 0) "Checked ${ago(Updates.lastChecked()).lowercase()}" else "Not checked yet"
                        Updates.State.Checking -> "Checking…"
                        is Updates.State.Current -> "Up to date  ·  checked ${ago(s.checkedAt).lowercase()}"
                        is Updates.State.Available -> "${s.release.versionName} is out"
                        is Updates.State.Downloading -> "Downloading ${s.release.versionName}  ·  ${(s.fraction * 100).toInt()}%"
                        is Updates.State.Installing -> "Installing ${s.release.versionName}…"
                        is Updates.State.Failed -> s.why
                    }, style = Type.caption, color = if (state is Updates.State.Failed) t.danger else if (state is Updates.State.Available) t.accent else t.muted)
                }
                val available = (state as? Updates.State.Available)?.release
                if (available != null) GlassButton({ scope.launch { if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        Hub.banners.tryEmit(Banner(Banner.Kind.Update, "Allow Arnav Island to install updates", "Then tap Update again")) } else Updates.install(available, context) } },
                    prominent = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Text("Update", style = Type.bodyStrong) }
                else GlassButton({ scope.launch { Updates.check(force = true) } }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) { Text("Check", style = Type.bodyStrong) }
            }
            Hairline()
            GlassRow(Icons.Rounded.Bolt, "Update automatically", tint = t.yellow) { GlassSwitch(auto, { auto = it; Hub.prefs.edit().putBoolean("autoUpdate", it).apply() }) }
            Hairline()
            GlassRow(Icons.Rounded.History, "Release history", tint = t.gray, chevron = true, onClick = onUpdates)
        }
    }
}

// ---- Settings (1.8, #35) ----
/** A setting: the page it's on, its words (for search), and its row. */
private class Entry(val page: Int, val title: String, val words: String, val row: @Composable () -> Unit)
private val SettingsPages = listOf("Connection", "On your PC’s island", "On this phone", "Alerts & live updates", "Appearance", "Updates", "Privacy", "About")

/**
 * 1.8: Settings, as iOS has them: a search field over grouped pages; a page slides in from the right and back. Searching
 * shows every matching setting, wherever it lives, under its page's name.
 */
@Composable fun SettingsSheet(visible: Boolean, look: Look, onLook: (Look) -> Unit, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current
    var page by remember { mutableStateOf<Int?>(null) }; var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible) { if (visible) { page = null; query = "" } }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { version++; onPauseOrDispose { } }
    fun flag(key: String, default: Boolean = true): Boolean { version.hashCode(); return Hub.prefs.getBoolean(key, default) }
    fun set(key: String, value: Boolean) { Hub.prefs.edit().putBoolean(key, value).apply(); version++ }
    val mirrorAllowed = remember(version) { Mirror.allowed(context) }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val unrestricted = remember(version) { power?.isIgnoringBatteryOptimizations(context.packageName) == true }
    val photoAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> set("recent_photos", granted); RecentPhotos.sync(context) }
    val hotspot by Hub.hotspot.collectAsState(); val hotspotOn by Hub.hotspotOn.collectAsState()
    val widgets = remember { AppWidgetManager.getInstance(context) }
    fun open(i: Intent) = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val entries = listOf(
        Entry(0, "Reach my PCs anywhere", "internet relay mobile data network anywhere") {
            val anywhere = flag("internet")
            GlassRow(Icons.Rounded.Public, "Reach my PCs anywhere", null, tint = t.blue) {
                GlassSwitch(anywhere, { on -> set("internet", on); Hub.restart() }) } },
        Entry(0, "Stay reachable", "background closed service reachable") {
            GlassRow(Icons.Rounded.Wifi, "Stay reachable", "While the app is closed", tint = t.blue) { GlassSwitch(flag("reachable"), { on -> set("reachable", on); if (on) LinkService.start(context) }) } },
        Entry(0, "Run in the background", "battery optimisation always reachable") {
            GlassRow(Icons.Rounded.BatteryAlert, "Run in the background", null, tint = t.good) {
                if (!unrestricted) GlassButton({ open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))).onFailure { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text("Allow", style = Type.bodyStrong) } else Icon(Icons.Rounded.CheckCircle, null, tint = t.good) } },
        Entry(0, "Accept files without asking", "auto accept files downloads") {
            GlassRow(Icons.Rounded.Download, "Accept files without asking", tint = t.good) { GlassSwitch(flag("autoAccept", false), { set("autoAccept", it) }) } },
        Entry(1, "Your notifications", "notifications mirror island reply") {
            GlassRow(Icons.Rounded.NotificationsActive, "Your notifications", if (!mirrorAllowed) "Needs notification access" else null, tint = t.danger) {
                GlassSwitch(mirrorAllowed && flag("mirror"), { on -> if (on && !mirrorAllowed) open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); set("mirror", on) }) } },
        Entry(1, "Calls", "calls ringing decline phone") {
            GlassRow(Icons.Rounded.Call, "Calls", tint = t.good) {
                GlassSwitch(mirrorAllowed && flag("calls"), { on -> if (on && !mirrorAllowed) open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); set("calls", on) }) } },
        Entry(1, "Your battery", "battery forecast low") {
            GlassRow(Icons.Rounded.BatteryChargingFull, "Your battery", tint = t.good) { GlassSwitch(flag("battery"), { set("battery", it); if (it) Hub.sendBattery(force = true) }) } },
        Entry(1, "Your hotspot", "hotspot tethering join wifi") {
            GlassRow(Icons.Rounded.WifiTethering, "Your hotspot", when { hotspot.name.isEmpty() -> null; hotspotOn && hotspot.enabled -> "“${hotspot.name}” is on"; else -> "“${hotspot.name}”" },
                tint = t.good, onClick = { Ui.hotspot = true }) { GlassSwitch(hotspot.enabled && hotspot.name.isNotEmpty(), { on -> if (on && hotspot.name.isEmpty()) Ui.hotspot = true else Hub.setHotspot(enabled = on) }) } },
        Entry(1, "Live activities on your PC", "live activity delivery swiggy zomato blinkit zepto ride uber ola timer tracking order") {
            GlassRow(Icons.Rounded.DeliveryDining, "Live activities on your PC", if (!mirrorAllowed) "Needs notification access" else "Deliveries, rides, timers", tint = t.warn) {
                GlassSwitch(mirrorAllowed && flag("mirror") && LiveCapture.enabled(), { on -> if (on && !mirrorAllowed) open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); if (on) set("mirror", true); LiveCapture.setEnabled(on); version++ }) } },
        Entry(1, "Your phone’s details", "details storage memory network") {
            GlassRow(Icons.Rounded.PhoneAndroid, "Your phone’s details", tint = t.gray) { GlassSwitch(flag("details"), { set("details", it); if (it) Hub.sendDetails(force = true) }) } },
        Entry(1, "Photos you take", "photos screenshots paste shelf camera") {
            GlassRow(Icons.Rounded.PhotoCamera, "Photos you take", if (flag("recent_photos", false) && !RecentPhotos.permitted(context)) "Needs photo access" else null, tint = t.pink) {
                GlassSwitch(flag("recent_photos", false) && RecentPhotos.permitted(context), { on -> if (on && !RecentPhotos.permitted(context)) photoAccess.launch(RecentPhotos.permission()) else { set("recent_photos", on); RecentPhotos.sync(context) } }) } },
        Entry(1, "Universal clipboard", "clipboard copy paste") {
            GlassRow(Icons.Rounded.ContentPaste, "Universal clipboard", tint = t.indigo) { GlassSwitch(flag("clipboard"), { set("clipboard", it) }) } },
        Entry(2, "Your PC’s music player", "lock screen media player quick settings") {
            GlassRow(Icons.Rounded.MusicNote, "Your PC’s music player", "Lock screen and quick settings", tint = t.pink) {
                GlassSwitch(flag("pcMedia"), { on -> set("pcMedia", on); if (on) PcMedia.update(context, Hub.pc(), Hub.status.value) else PcMedia.clear(context) }) } },
        Entry(2, "Volume buttons turn your PC", "volume keys buttons sound") {
            GlassRow(Icons.Rounded.VolumeUp, "Volume buttons turn your PC", "While the Remote shows", tint = t.blue) { GlassSwitch(flag("volumeKeys"), { set("volumeKeys", it) }) } },
        Entry(2, "Widgets", "widgets home screen lyrics batteries transfer") {
            Column {
                GlassRow(Icons.Rounded.Widgets, "Widgets", if (widgets.isRequestPinAppWidgetSupported) null else "Add from your home screen", tint = t.blue)
                if (widgets.isRequestPinAppWidgetSupported) Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 60.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Now playing" to PcWidget::class.java, "Controls" to ControlsWidget::class.java, "Mixer" to MixerWidget::class.java, "Quick actions" to ActionsWidget::class.java, "Lyrics" to LyricsWidget::class.java, "Batteries" to BatteriesWidget::class.java, "Transfers" to TransferWidget::class.java,
                        "PC’s numbers" to StatsWidget::class.java, "Focus" to FocusWidget::class.java).forEach { (name, c) -> GlassChip(name, icon = Icons.Rounded.Add) { runCatching { widgets.requestPinAppWidget(ComponentName(context, c), null, null) } } }
                }
            } },
        Entry(2, "Use with Gemini", "gemini assistant voice google ask hey app functions shortcuts routines") {
            GlassRow(Icons.Rounded.Mic, "Use with Gemini", tint = t.purple, chevron = true, onClick = { MoreUi.gemini = true }) },
        Entry(2, "Live wallpaper", "wallpaper ambient light colours home lock screen") {
            GlassRow(Icons.Rounded.Wallpaper, "Live wallpaper", tint = t.purple, chevron = true, onClick = { open(AmbientWallpaper.chooserIntent(context)) }) },
        Entry(2, "Clear song history", "history songs recently played loved") {
            GlassRow(Icons.Rounded.History, "Clear song history", "Loved songs stay", tint = t.gray, onClick = { Hub.clearHistory(); Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Song history cleared", "Loved songs stay")) }) },
        Entry(3, "Alerts", "alerts cpu gpu memory disk battery charged notification") {
            GlassRow(Icons.Rounded.NotificationImportant, "Alerts", Alerts.kinds.filter { Alerts.on(it.key) }.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.title.lowercase() }?.replaceFirstChar { it.uppercase() } ?: "Off",
                tint = t.danger, chevron = true, onClick = { Ui.alerts = true }) },
        Entry(3, "Transfers as live updates", "live updates transfers progress status bar") {
            GlassRow(Icons.Rounded.SwapVert, "Transfers as live updates", tint = t.blue) { GlassSwitch(flag("liveTransfers"), { set("liveTransfers", it) }) } },
        Entry(3, "Your PC’s song as a live update", "live update song music status bar chip") {
            GlassRow(Icons.Rounded.GraphicEq, "Your PC’s song as a live update", if (Build.VERSION.SDK_INT >= 36) null else "Needs Android 16", tint = t.pink) {
                GlassSwitch(flag("liveSong") && Build.VERSION.SDK_INT >= 36, { set("liveSong", it); if (!it) LiveUpdates.song(context, null, null) }, enabled = Build.VERSION.SDK_INT >= 36) } },
        Entry(4, "Theme", "appearance dark light automatic theme") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { IconTile(Icons.Rounded.Contrast, t.indigo); Spacer(Modifier.width(14.dp)); Text("Theme", style = Type.body, color = t.text) }; Spacer(Modifier.height(12.dp)); Segmented(listOf("Automatic", "Dark", "Light"), look.appearance, { onLook(look.copy(appearance = it)) }, Modifier.fillMaxWidth()) } },
        Entry(4, "Widgets in Material You", "widgets material you wallpaper colours") {
            if (Build.VERSION.SDK_INT >= 31) GlassRow(Icons.Rounded.Palette, "Widgets in Material You", tint = t.purple) {
                GlassSwitch(flag("widgetsYou", false), { set("widgetsYou", it); WidgetStyle.set(context, it) }) } },
    )
    GlassSheet(visible, onDismiss, Modifier.fillMaxHeight(.92f)) {
        if (page != null) BackHandler { page = null }
        AnimatedContent(page, transitionSpec = {
            if (targetState != null) (slideInHorizontally(spring(dampingRatio = .9f, stiffness = 420f)) { it } + fadeIn()) togetherWith (slideOutHorizontally(tween(220)) { -it / 3 } + fadeOut(tween(160)))
            else (slideInHorizontally(spring(dampingRatio = .9f, stiffness = 420f)) { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally(tween(220)) { it } + fadeOut(tween(160)))
        }, label = "Settings", modifier = Modifier.weight(1f, fill = false)) { shown ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (shown == null) {
                    SheetHeader("Settings", null, onDismiss)
                    Spacer(Modifier.height(8.dp))
                    SearchField(query, { query = it }, "Search settings")
                    val q = query.trim().lowercase()
                    if (q.isNotEmpty()) {
                        val found = entries.filter { q in it.title.lowercase() || q.split(' ').all { w -> w in it.words || w in it.title.lowercase() } }
                        if (found.isEmpty()) Text("No settings match “$query”", style = Type.body, color = t.muted, modifier = Modifier.fillMaxWidth().padding(28.dp), textAlign = TextAlign.Center)
                        found.groupBy { it.page }.forEach { (p, list) -> Section(SettingsPages[p]) { list.forEachIndexed { i, e -> if (i > 0) Hairline(); e.row() } } }
                    } else {
                        val icons = listOf(Icons.Rounded.Public to t.blue, Icons.Rounded.SpaceDashboard to t.indigo, Icons.Rounded.PhoneAndroid to t.good, Icons.Rounded.NotificationImportant to t.danger,
                            Icons.Rounded.Palette to t.purple, Icons.Rounded.SystemUpdate to t.gray, Icons.Rounded.Shield to t.good, Icons.Rounded.Info to t.gray)
                        Section(null) { for (i in 0..3) { if (i > 0) Hairline(); GlassRow(icons[i].first, SettingsPages[i], tint = icons[i].second, chevron = true, onClick = { page = i }) } }
                        Section(null) { for (i in 4..7) { if (i > 4) Hairline(); GlassRow(icons[i].first, SettingsPages[i], tint = icons[i].second, chevron = true, onClick = { page = i }) } }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clip(GlassShapes.capsule).clickable { page = null }.padding(end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back to Settings", tint = t.accent, modifier = Modifier.size(18.dp)); Text("Settings", style = Type.body, color = t.accent)
                        }
                        Spacer(Modifier.weight(1f)); GlassIconButton(Icons.Rounded.Close, "Close", onDismiss, size = 34.dp, iconSize = 17.dp)
                    }
                    Text(SettingsPages[shown], style = Type.title, color = t.text, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    when (shown) {
                        5 -> { Spacer(Modifier.height(16.dp)); UpdateCard { Ui.updates = true } }
                        6 -> Section(null) {
                            Column(Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { IconTile(Icons.Rounded.Shield, t.good); Spacer(Modifier.width(12.dp)); Text("Private by design", style = Type.headline, color = t.text) }
                                Spacer(Modifier.height(10.dp))
                                Text("Everything stays between your own devices, encrypted end to end. No accounts, no analytics, no cloud.", style = Type.body, color = t.muted)
                            }
                        }
                        7 -> Section(null) {
                            GlassRow(Icons.Rounded.Info, "Version", tint = t.gray, value = Updates.current); Hairline()
                            GlassRow(Icons.Rounded.PhoneAndroid, "This phone", tint = t.good, value = Hub.phoneName()); Hairline()
                            var crash by remember { mutableStateOf(Crashes.last(context)) }
                            crash?.let { (_, at) -> GlassRow(Icons.Rounded.BugReport, "Last crash", ago(at), tint = t.danger, onClick = { Crashes.share(context) }) {
                                GlassIconButton(Icons.Rounded.Close, "Forget it", { Crashes.forget(context); crash = null }, size = 30.dp, iconSize = 15.dp) }; Hairline() }
                            GlassRow(Icons.AutoMirrored.Rounded.OpenInNew, "Source code", "GitHub", tint = t.blue, chevron = true, onClick = { openUrl(context, "https://github.com/${Updates.REPO}") })
                        }
                        else -> Section(null) { entries.filter { it.page == shown }.forEachIndexed { i, e -> if (i > 0) Hairline(); e.row() } }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ---- a PC's details (1.8, #39, #40, #38) ----
/** A PC's details: its own name, colour and icon (here only), when it was last here, its connection, and wake or forget it. */
@Composable fun PcSheet(p: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val seen by Hub.lastSeen.collectAsState(); val machines by Hub.machines.collectAsState(); Hub.looks.collectAsState().value
    var name by remember(p?.id) { mutableStateOf("") }; var confirmForget by remember(p?.id) { mutableStateOf(false) }
    LaunchedEffect(p?.id) { name = p?.let { Hub.pcLook(it.id).name ?: it.name }.orEmpty() }
    GlassSheet(p != null, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
        if (p == null) return@GlassSheet
        val look = Hub.pcLook(p.id)
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth()) { GlassIconButton(Icons.Rounded.Close, "Close", onDismiss, Modifier.align(Alignment.TopEnd), size = 34.dp, iconSize = 17.dp) }
            IconTile(pcIcon(p.id, p.phone), pcColor(p.id), size = 76.dp)
            Spacer(Modifier.height(12.dp))
            Text(p.name, style = Type.title2, color = t.text, textAlign = TextAlign.Center)
            Text(if (p.online) quality(p).text else seen[p.id]?.let { "Last seen ${ago(it).lowercase()}" } ?: "Away", style = Type.caption, color = t.muted)
            // Its name here.
            Section("Name") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (name.isEmpty()) Text("A name for it", style = Type.body, color = t.faint)
                        BasicTextField(name, { name = it.take(40) }, singleLine = true, textStyle = Type.body.copy(color = t.text), cursorBrush = SolidColor(t.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name for ${p.name}" })
                    }
                    val changed = name.isNotBlank() && name != (look.name ?: p.name)
                    if (changed) Text("Save", style = Type.bodyStrong, color = t.accent, modifier = Modifier.clip(CircleShape).clickable { Hub.setPcLook(p.id, look.copy(name = name.trim())) }.padding(8.dp))
                    else if (look.name != null) Text("Reset", style = Type.body, color = t.muted, modifier = Modifier.clip(CircleShape).clickable { Hub.setPcLook(p.id, look.copy(name = null)); name = "" }.padding(8.dp))
                }
            }
            if (!p.phone) {
                Section("Colour") {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        PcColors.forEachIndexed { i, c -> val chosen = look.color == i
                            val ring by animateFloatAsState(if (chosen) 1f else 0f, spring(dampingRatio = .6f, stiffness = 500f), label = "Swatch")
                            Box(Modifier.size(34.dp).border(2.5.dp, t.text.copy(alpha = ring), CircleShape).padding(4.dp).clip(CircleShape).background(c).clickable(role = Role.RadioButton) { Hub.setPcLook(p.id, look.copy(color = i)) }
                                .semantics { contentDescription = "Colour ${i + 1}" + if (chosen) ", chosen" else "" })
                        }
                    }
                }
                Section("Icon") {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        PcIcons.forEachIndexed { i, icon -> val chosen = look.icon == i
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (chosen) pcColor(p.id) else t.fill).clickable(role = Role.RadioButton) { Hub.setPcLook(p.id, look.copy(icon = i)) }, contentAlignment = Alignment.Center) {
                                Icon(icon, "Icon ${i + 1}", tint = if (chosen) Color.White else t.muted, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Section(null) {
                GlassRow(Icons.Rounded.Lan, "Connection", tint = qualityColor(quality(p).kind), value = if (p.online) quality(p).text.substringBefore("  ·") else "Away", chevron = true, onClick = { Ui.connection = p.id })
                Hairline()
                GlassRow(Icons.Rounded.Schedule, "Last seen", tint = t.indigo, value = if (p.online) "Here now" else seen[p.id]?.let { ago(it) } ?: "Not yet")
                Hairline()
                GlassRow(Icons.Rounded.Shield, "Island protocol", tint = t.good, value = "${p.version}.${p.revision}")
                if (!p.phone && !machines[p.id].isNullOrEmpty()) { Hairline(); GlassRow(Icons.Rounded.PowerSettingsNew, "Wake ${p.name}", tint = t.good, chevron = true, onClick = { Hub.wake(p.id) }) }
            }
            Spacer(Modifier.height(16.dp))
            if (!p.phone && p.id != Hub.pc()?.id) { GlassButton({ Hub.choose(p.id); onDismiss() }, Modifier.fillMaxWidth(), prominent = true, contentPadding = PaddingValues(vertical = 15.dp)) { Text("Use this PC", style = Type.headline) }; Spacer(Modifier.height(10.dp)) }
            GlassButton({ if (confirmForget) { Hub.forget(p.id); onDismiss() } else confirmForget = true }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 15.dp)) {
                Text(if (confirmForget) "Tap again to forget ${p.name}" else "Forget this PC", style = Type.headline, color = t.danger)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ---- a connection's details (1.8, #37) ----
/**
 * How a PC is reached, in full: the path (this Wi-Fi, directly over the internet, or through the relay), its round trip
 * over time, the relays it's heard on, IPv6, its address and the encryption; and Test again, which finds the paths anew.
 */
@Composable fun ConnectionSheet(p: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val trail by Hub.rttTrail.collectAsState(); val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    GlassSheet(p != null, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
        if (p == null) return@GlassSheet
        val q = quality(p)
        SheetHeader("Connection", p.name, onDismiss)
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            GlassPanel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { QualityRing(q, Modifier.fillMaxSize(), 3.dp); Icon(if (!p.online) Icons.Rounded.CloudOff else if (p.internet) Icons.Rounded.Public else Icons.Rounded.Wifi, null, tint = qualityColor(q.kind), modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when { !p.online -> "Away"; !p.internet -> "On this Wi-Fi"; p.path == 2 -> "Directly, over the internet"; else -> "Through the relay" }, style = Type.headline, color = t.text)
                    }
                }
            }
            val samples = trail[p.id].orEmpty()
            if (p.internet && samples.size >= 2) Section("Round trip") {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) { RollingNumber("${samples.last().second.roundToInt()}", Type.title, t.text); Text(" ms", style = Type.headline, color = t.muted, modifier = Modifier.padding(bottom = 3.dp)) }
                    Text("Best ${samples.minOf { it.second }.roundToInt()} ms  ·  average ${samples.map { it.second }.average().roundToInt()} ms", style = Type.caption, color = t.muted)
                    Spacer(Modifier.height(10.dp))
                    MorphGraph(samples.map { it.second.toFloat() }, qualityColor(q.kind), Modifier.fillMaxWidth().height(70.dp))
                }
            }
            if (p.online && !p.phone) SpeedTest(p)
            Section("Details") {
                GlassRow(Icons.Rounded.Route, "Path", tint = t.blue, value = when { !p.online -> "—"; !p.internet -> "Local"; p.path == 2 -> "Direct"; else -> "Relay" }); Hairline()
                if (p.internet) { GlassRow(Icons.Rounded.Speed, "Round trip", tint = t.good, value = if (p.rtt > 0) "${p.rtt.roundToInt()} ms" else "—"); Hairline()
                    GlassRow(Icons.Rounded.Hub, "Relays heard on", tint = t.indigo, value = "${p.relays} of 3"); Hairline()
                    GlassRow(Icons.Rounded.Language, "IPv6", tint = t.teal, value = if (p.v6) "Yes" else "No"); Hairline() }
                if (!p.address.isNullOrBlank()) { GlassRow(Icons.Rounded.SettingsEthernet, "Address", tint = t.gray, value = p.address); Hairline() }
                GlassRow(Icons.Rounded.Lock, "Encryption", tint = t.good, value = "AES-256-GCM"); Hairline()
                GlassRow(Icons.Rounded.Shield, "Island protocol", tint = t.gray, value = "${p.version}.${p.revision}")
            }
            Spacer(Modifier.height(16.dp))
            GlassButton({ testing = true; Hub.retest(); scope.launch { kotlinx.coroutines.delay(2500); testing = false } }, Modifier.fillMaxWidth(), prominent = true, enabled = !testing, contentPadding = PaddingValues(vertical = 15.dp)) {
                if (testing) { Radar(t.onAccent, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Testing…", style = Type.headline) } else { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Test again", style = Type.headline) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
