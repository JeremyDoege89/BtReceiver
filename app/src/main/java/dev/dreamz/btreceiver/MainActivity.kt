package dev.dreamz.btreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.dreamz.btreceiver.ui.BtReceiverTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var media: SourceMedia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        media = SourceMedia(applicationContext)
        setContent {
            BtReceiverTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen(media)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        media.start()
    }

    override fun onStop() {
        media.stop()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(media: SourceMedia) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<BtMode.Status?>(null) }
    var switching by remember { mutableStateOf<BtMode.Mode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf<BtMode.Result?>(null) }
    var removed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { setup = BtMode.install(context) }

    LifecycleResumeEffect(setup, removed) {
        val job = scope.launch {
            val installed = setup ?: return@launch
            if (removed) return@launch
            if (installed.code != 0) {
                status = BtMode.Status(error = "Couldn't set up root access. Allow BT Receiver in your root manager (KernelSU, Magisk or APatch), then reopen the app.\n${installed.output}")
                return@launch
            }
            while (true) {
                if (switching == null) {
                    val previous = status?.activeDevice
                    status = BtMode.status()
                    if (status?.activeDevice != null && status?.activeDevice != previous && !media.hasSession) {
                        media.reconnect()
                    }
                }
                delay(4000)
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    fun switchTo(mode: BtMode.Mode) {
        if (switching != null || status?.mode == mode) return
        switching = mode
        message = null
        scope.launch {
            val r = BtMode.set(mode)
            status = BtMode.status()
            if (status?.mode != mode) message = "Switch failed: ${r.output}"
            switching = null
            media.reconnect()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_bluetooth),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("BT Receiver", style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (removed) {
                RemovedCard()
                return@Column
            }
            HeroStatusCard(status, switching)
            ModeCard(status, switching, message, ::switchTo)
            AnimatedVisibility(
                visible = status?.mode == BtMode.Mode.SPEAKER && switching == null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                NowPlayingCard(media)
            }
            VolumeCard()
            status?.takeIf { it.error == null }?.let { st ->
                DeviceCard(
                    status = st,
                    setupNote = setup?.output?.lines()?.firstOrNull { it.startsWith("Disabled the old") },
                    onRemove = {
                        switching = BtMode.Mode.NORMAL
                        scope.launch {
                            BtMode.uninstall()
                            switching = null
                            removed = true
                        }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroStatusCard(status: BtMode.Status?, switching: BtMode.Mode?) {
    val (containerColor, onColor) = when {
        status?.error != null -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        (switching ?: status?.mode) == BtMode.Mode.SPEAKER ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    val mode = switching ?: status?.mode
    val headline = when {
        status == null -> "Checking…"
        status.error != null -> "Not ready"
        mode == BtMode.Mode.SPEAKER -> "Speaker mode"
        mode == BtMode.Mode.NORMAL -> "Regular Bluetooth"
        else -> "Setting up…"
    }
    val subtext = when {
        status == null -> "Reading the phone's Bluetooth state."
        status.error != null -> "See the mode card below for details."
        switching != null -> "Restarting the Bluetooth service."
        mode == BtMode.Mode.SPEAKER -> {
            if (status.activeDevice != null) "Connected to ${status.activeDevice}"
            else "Pair a source phone or PC to start playing to this phone."
        }
        else -> "This phone is a regular Bluetooth source. Switch to Speaker to receive audio."
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor, contentColor = onColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_bluetooth),
                    contentDescription = null,
                    tint = onColor,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(4.dp))
                Text(subtext, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (switching != null) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = onColor)
            }
        }
    }
}

@Composable
private fun RemovedCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("All cleaned up", style = MaterialTheme.typography.titleMedium)
            Text(
                "Removed from the system and Bluetooth is back to normal. You can uninstall the app now, or reopen it to set it up again.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(status: BtMode.Status, setupNote: String?, onRemove: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This phone", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(status.rootManager) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("SDK ${status.sdk}") },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
            Text("Chip: ${status.soc.ifBlank { "unknown" }}", style = MaterialTheme.typography.bodyMedium)
            if (status.sdk in 1..32) {
                Text(
                    "Android 13 or newer is needed for speaker mode.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!status.hasResetprop) {
                Text(
                    "resetprop not found; using setprop. Regular mode may not fully restore the stock settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            setupNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Remove from system")
            }
            Text(
                "Switches back to regular Bluetooth and removes the boot script. Do this before uninstalling the app.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeCard(
    status: BtMode.Status?,
    switching: BtMode.Mode?,
    message: String?,
    onSwitch: (BtMode.Mode) -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mode", style = MaterialTheme.typography.titleMedium)
            val selected = switching ?: status?.mode
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selected == BtMode.Mode.SPEAKER,
                    onClick = { onSwitch(BtMode.Mode.SPEAKER) },
                    enabled = status?.error == null && switching == null,
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Speaker") }
                SegmentedButton(
                    selected = selected == BtMode.Mode.NORMAL,
                    onClick = { onSwitch(BtMode.Mode.NORMAL) },
                    enabled = status?.error == null && switching == null,
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Regular") }
            }
            when {
                switching != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Restarting Bluetooth…", style = MaterialTheme.typography.bodyMedium)
                }
                status == null -> Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                status.error != null -> Text(
                    status.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                status.mode == BtMode.Mode.SPEAKER -> {
                    if (!status.sinkRunning && status.bluetoothOn) {
                        Text(
                            "The receiver service didn't start. This phone's Bluetooth may not support receiving audio.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "This phone is a Bluetooth speaker. Play audio from your PC or phone to it. It can't play to Bluetooth headphones in this mode.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (status.activeDevice != null) "Connected source: ${status.activeDevice}"
                        else "No source connected. A device must pair while the phone is in speaker mode.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Pair a new source")
                    }
                }
                else -> Text(
                    "Regular Bluetooth: the phone plays to headphones and car kits. It can't receive audio in this mode.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (status != null && !status.bluetoothOn && status.error == null) {
                Text("Bluetooth is off.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun NowPlayingCard(media: SourceMedia) {
    val scope = rememberCoroutineScope()
    val now by media.state.collectAsState()

    fun send(viaSession: () -> Unit, key: String) {
        if (media.hasSession) viaSession() else scope.launch { BtMode.dispatchKey(key) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Now playing", style = MaterialTheme.typography.titleMedium)
            Text(
                now.title ?: now.error ?: if (now.connected) "Nothing playing" else "Waiting for the Bluetooth media session…",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            listOfNotNull(now.artist, now.album).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { send(media::previous, "previous") }, Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_prev), "Previous")
                }
                FilledIconButton(onClick = { send(media::playPause, "play-pause") }, Modifier.size(72.dp)) {
                    Icon(
                        painterResource(if (now.playing) R.drawable.ic_pause else R.drawable.ic_play),
                        if (now.playing) "Pause" else "Play",
                        Modifier.size(36.dp),
                    )
                }
                FilledTonalIconButton(onClick = { send(media::next, "next") }, Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_next), "Next")
                }
                FilledTonalIconButton(onClick = { send(media::stopPlayback, "stop") }, Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_stop), "Stop")
                }
            }
        }
    }
}

@Composable
private fun VolumeCard() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${volume.toInt()} / $max",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                },
                valueRange = 0f..max.toFloat(),
                steps = (max - 1).coerceAtLeast(0),
            )
        }
    }
}
