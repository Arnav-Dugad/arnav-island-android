package io.github.arnavdugad.arnavisland

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.PeerView

/**
 * 1.8 (#67): this phone as [pc]'s webcam and microphone, each a switch. Android's permission is asked here first; while
 * on, the webcam row flips the lens and lights the torch, and the microphone row mutes and shows how loud it is.
 */
@Composable fun CameraMicCard(pc: PeerView, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val context = LocalContext.current
    val camOn by PhoneCamera.showing.collectAsState(); val camBusy by PhoneCamera.connecting.collectAsState()
    val facing by PhoneCamera.facing.collectAsState(); val hasTorch by PhoneCamera.hasTorch.collectAsState(); val torch by PhoneCamera.torch.collectAsState()
    val micOn by PhoneMic.showing.collectAsState(); val micBusy by PhoneMic.connecting.collectAsState()
    val level by PhoneMic.level.collectAsState(); val muted by PhoneMic.isMuted.collectAsState()
    // Asked, then started either way: a "no" comes back as start's banner saying why.
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { PhoneCamera.start(context, pc.id) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { PhoneMic.start(context, pc.id) }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val ready = pc.revision >= CamWire.REVISION
    val needs = "Needs Arnav Island 0.26 or later on ${pc.name}"

    GlassPanel(modifier.fillMaxWidth()) {
        Column {
            GlassRow(Icons.Rounded.Videocam, "Webcam",
                detail = when { camOn != null -> "On $camOn · ${if (facing == CameraCharacteristics.LENS_FACING_BACK) "back" else "front"} camera"; camBusy -> "Connecting…"; !ready -> needs; else -> "This phone as ${pc.name}’s camera" },
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
            Hint("On the PC, capture its window with OBS’s virtual camera, or any app that captures a window")
            Hairline()
            GlassRow(if (muted && micOn != null) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Microphone",
                detail = when { micOn != null -> if (muted) "Muted on $micOn" else "On $micOn"; micBusy -> "Connecting…"; !ready -> needs; else -> "This phone as ${pc.name}’s microphone" },
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

/** A small line of help under a row, lined up with its title. */
@Composable private fun Hint(text: String) {
    Text(text, style = Type.caption, color = LocalTokens.current.faint, modifier = Modifier.padding(start = 65.dp, end = 16.dp, bottom = 12.dp))
}
