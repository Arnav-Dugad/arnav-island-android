package io.github.arnavdugad.arnavisland

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.PeerView

/**
 * 1.8 (#67): this phone as [pc]'s webcam and microphone, each a switch. Android's permission is asked here first; while
 * on, the webcam row flips the lens and lights the torch, and the microphone row mutes and shows how loud it is.
 *
 * 1.9 (#34, #33): while the webcam is on, a small live view of what's being sent; camera options (the picture, the lens
 * it starts on, a mirrored preview) fold out under it; the microphone says how it goes (Opus, AAC or PCM). With island
 * 0.27 on Windows 11 the PC lists this camera as "Arnav Island Phone Camera" in any app.
 */
@Composable fun CameraMicCard(pc: PeerView, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val context = LocalContext.current
    val camOn by PhoneCamera.showing.collectAsState(); val camBusy by PhoneCamera.connecting.collectAsState()
    val facing by PhoneCamera.facing.collectAsState(); val hasTorch by PhoneCamera.hasTorch.collectAsState(); val torch by PhoneCamera.torch.collectAsState()
    val picture by PhoneCamera.picture.collectAsState(); val quality by PhoneCamera.quality.collectAsState(); val mirror by PhoneCamera.mirror.collectAsState()
    val micOn by PhoneMic.showing.collectAsState(); val micBusy by PhoneMic.connecting.collectAsState()
    val level by PhoneMic.level.collectAsState(); val muted by PhoneMic.isMuted.collectAsState(); val micFormat by PhoneMic.format.collectAsState()
    // Asked, then started either way: a "no" comes back as start's banner saying why.
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { PhoneCamera.start(context, pc.id) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { PhoneMic.start(context, pc.id) }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val ready = pc.revision >= CamWire.REVISION
    val real = pc.revision >= CamWire.REVISION_CODEC
    val needs = "Needs Arnav Island 0.26 or later on ${pc.name}"
    var options by remember { mutableStateOf(false) }
    val uhd = remember(facing) { PhoneCamera.can4K(context, facing) }
    fun name(q: Int) = when (q) { PhoneCamera.HD -> "720p"; PhoneCamera.FULL_HD -> "1080p"; PhoneCamera.UHD -> "4K"; else -> "Auto" }
    val side = if (facing == CameraCharacteristics.LENS_FACING_BACK) "back" else "front"

    GlassPanel(modifier.fillMaxWidth()) {
        Column {
            GlassRow(Icons.Rounded.Videocam, "Webcam",
                detail = when {
                    camOn != null -> "On $camOn · $side camera${picture?.let { (w, h) -> " · ${minOf(w, h)}p" } ?: ""}"
                    camBusy -> "Connecting…"; !ready -> needs; else -> "This phone as ${pc.name}’s camera"
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (camOn != null) {
                            GlassIconButton(Icons.Rounded.Cameraswitch, "Flip camera", { PhoneCamera.flip(context) }, size = 38.dp, iconSize = 18.dp)
                            if (hasTorch) GlassIconButton(if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, if (torch) "Turn the light off" else "Turn the light on",
                                { PhoneCamera.setTorch(!torch) }, size = 38.dp, iconSize = 18.dp, prominent = torch)
                        }
                        GlassSwitch(camOn != null || camBusy, { on ->
                            when { !on -> PhoneCamera.stop(context); !ready || granted(Manifest.permission.CAMERA) -> PhoneCamera.start(context, pc.id); else -> askCamera.launch(Manifest.permission.CAMERA) }
                        }, Modifier.semantics { contentDescription = "Webcam" }, enabled = pc.online || camOn != null || camBusy)
                    }
                })
            // 1.9: what the PC sees, live (drawn from the very frames being sent).
            AnimatedVisibility(camOn != null && picture != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                val (w, h) = picture ?: (16 to 9)
                Box(Modifier.fillMaxWidth().padding(start = 65.dp, end = 16.dp, bottom = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.height(if (w >= h) 132.dp else 200.dp).aspectRatio(w.toFloat() / h.coerceAtLeast(1), matchHeightConstraintsFirst = true).clip(GlassShapes.inner).background(t.fill)) {
                        LivePreview(Modifier.fillMaxSize())
                    }
                }
            }
            Hint(if (real) "On ${pc.name}, pick “Arnav Island Phone Camera” in any app: it’s a real camera there on Windows 11. On Windows 10, capture its window with OBS"
                else "On the PC, capture its window with OBS’s virtual camera, or any app that captures a window")
            Hairline()
            // 1.9: the camera's options, folded away.
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { options = !options }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Tune, t.gray)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Camera options", style = Type.body, color = t.text)
                    Text("${name(quality)} · starts on the $side${if (mirror) " · mirrored preview" else ""}", style = Type.caption, color = t.muted)
                }
                FoldArrow(options)
            }
            AnimatedVisibility(options, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    GlassRow(Icons.Rounded.HighQuality, "Picture", when (quality) { PhoneCamera.AUTO -> "1080p on the same Wi-Fi, else 720p"; PhoneCamera.UHD -> "Needs a fast network"; else -> "Its bit rate still follows the network" }, tint = t.purple)
                    val choices = listOfNotNull(PhoneCamera.AUTO, PhoneCamera.HD, PhoneCamera.FULL_HD, PhoneCamera.UHD.takeIf { uhd || quality == PhoneCamera.UHD })
                    Segmented(choices.map(::name), choices.indexOf(quality).coerceAtLeast(0), { PhoneCamera.setQuality(choices[it]) }, Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp))
                    Hairline()
                    GlassRow(Icons.Rounded.Cameraswitch, "Lens", "The one it starts on", tint = t.teal, trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GlassChip("Front", selected = facing != CameraCharacteristics.LENS_FACING_BACK) { PhoneCamera.setLens(context, CameraCharacteristics.LENS_FACING_FRONT) }
                            GlassChip("Back", selected = facing == CameraCharacteristics.LENS_FACING_BACK) { PhoneCamera.setLens(context, CameraCharacteristics.LENS_FACING_BACK) }
                        }
                    })
                    Hairline()
                    GlassRow(Icons.Rounded.Flip, "Mirror the preview", "Only here: ${pc.name} sees you as others do", tint = t.indigo, trailing = {
                        GlassSwitch(mirror, { PhoneCamera.setMirror(it) })
                    })
                }
            }
            Hairline()
            GlassRow(if (muted && micOn != null) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Microphone",
                detail = when { micOn != null -> (if (muted) "Muted on $micOn" else "On $micOn") + (if (micFormat.isNotEmpty()) " · $micFormat" else ""); micBusy -> "Connecting…"; !ready -> needs; else -> "This phone as ${pc.name}’s microphone" },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (micOn != null) GlassIconButton(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (muted) "Unmute" else "Mute",
                            { PhoneMic.muted = !muted }, size = 38.dp, iconSize = 18.dp, prominent = muted)
                        GlassSwitch(micOn != null || micBusy, { on ->
                            when { !on -> PhoneMic.stop(context); !ready || granted(Manifest.permission.RECORD_AUDIO) -> PhoneMic.start(context, pc.id); else -> askMic.launch(Manifest.permission.RECORD_AUDIO) }
                        }, Modifier.semantics { contentDescription = "Microphone" }, enabled = pc.online || micOn != null || micBusy)
                    }
                })
            if (micOn != null) {
                // How loud, live.
                val shown by animateFloatAsState(if (muted) 0f else level, tween(90), label = "MicLevel")
                Box(Modifier.padding(start = 65.dp, end = 16.dp, bottom = 6.dp).fillMaxWidth().height(6.dp).clip(GlassShapes.capsule).background(t.track)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f)).clip(GlassShapes.capsule).background(t.good))
                }
            }
            Hint("On the PC, choose “CABLE Output” as the microphone (needs VB-CABLE)")
        }
    }
}

/** 1.9: a TextureView the camera's GL step draws its picture into while it's on (it lets go of it as the view goes). */
@Composable private fun LivePreview(modifier: Modifier) {
    AndroidView({ ctx ->
        TextureView(ctx).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = PhoneCamera.attachPreview(st, width, height)
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = PhoneCamera.resizePreview(st, width, height)
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { PhoneCamera.detachPreview(st); return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
    }, modifier.semantics { contentDescription = "What the PC sees" })
}

/** A small line of help under a row, lined up with its title. */
@Composable private fun Hint(text: String) {
    Text(text, style = Type.caption, color = LocalTokens.current.faint, modifier = Modifier.padding(start = 65.dp, end = 16.dp, bottom = 12.dp))
}
