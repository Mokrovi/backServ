package com.example.backgroundservice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Replace with your actual stream URL from VLC
private const val STREAM_URL = "rtsp://192.168.1.10:8554/stream" // e.g., "rtsp://192.168.1.10:8554/stream"
private const val MAX_LOG_LINES = 100 // Maximum number of log lines to keep in the on-screen display

class CameraStreamActivity : ComponentActivity() {

    private val logMessages = mutableStateListOf<String>()

    private fun logMessage(tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMessage = "[$timestamp] $tag: $message"
        Log.d(tag, message)
        
        if (logMessages.size >= MAX_LOG_LINES) {
            logMessages.removeAt(0)
        }
        logMessages.add(formattedMessage)
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkSignalService.isStreamActivityRunning = true
        logMessage("CameraStreamActivity", "Activity created and stream status set to true.")

        // Start the background service
        val serviceIntent = Intent(this, NetworkSignalService::class.java)
        startService(serviceIntent)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16 / 9f)
                        ) {
                            CameraStreamPlayer(STREAM_URL) {
                                logMessage("CameraStreamActivity", "Playback ended. Finishing activity.")
                                finish()
                            }
                        }
                        LogsDisplay(
                            logMessages = logMessages,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logMessage("CameraStreamActivity", "Activity destroyed, updating stream status.")
        NetworkSignalService.isStreamActivityRunning = false
    }
}

@UnstableApi
@Composable
fun CameraStreamPlayer(streamUrl: String, onPlaybackEnded: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd())
        }

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.02f)
                    .build()
            )
            .build()

        val mediaSource = RtspMediaSource.Factory()
            .setTimeoutMs(10000L) // Timeout for the RTSP session
            .createMediaSource(mediaItem)

        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(key1 = exoPlayer, key2 = onPlaybackEnded) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onPlaybackEnded()
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun LogsDisplay(logMessages: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.padding(8.dp)) {
        items(logMessages) { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall)
            Divider()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16 / 9f)
            ) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()){
                    Text("Camera Stream Area", modifier = Modifier.padding(16.dp))
                }
            }
            LogsDisplay(
                logMessages = listOf(
                    "[10:00:00] App: Initializing...",
                    "[10:00:01] Network: Connecting to stream...",
                    "[10:00:02] CameraStreamActivity: Player ready."
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}
