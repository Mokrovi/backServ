package com.example.backgroundservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.example.backgroundservice.NetworkSignalService.Companion.isStreamActivityRunning

class CameraStreamActivity : ComponentActivity() {

    private var isReceiverRegistered = false

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_CAMERA_STREAM) {
                finish()
            }
        }
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val externalStreamUrl = intent.getStringExtra("EXTERNAL_STREAM_URL")
        val localStreamUrl = intent.getStringExtra("LOCAL_STREAM_URL")
        val singleStreamUrl = intent.getStringExtra("STREAM_URL")

        val primaryUrl = externalStreamUrl ?: singleStreamUrl

        if (primaryUrl == null && localStreamUrl == null) {
            finish()
            return
        }

        val intentFilter = IntentFilter(ACTION_CLOSE_CAMERA_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, intentFilter)
        }
        isReceiverRegistered = true
        isStreamActivityRunning = true

        val serviceIntent = Intent(this, NetworkSignalService::class.java)
        startService(serviceIntent)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Gray) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .aspectRatio(16 / 9f)
                        ) {
                            CameraStreamPlayer(primaryUrl, localStreamUrl, onPlaybackEnded = {
                                finish()
                            })
                        }
                        Spacer(Modifier.weight(5f))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            unregisterReceiver(closeReceiver)
        }
        isStreamActivityRunning = false
    }
}

@UnstableApi
@Composable
fun CameraStreamPlayer(
    primaryUrl: String?,
    fallbackUrl: String?,
    onPlaybackEnded: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd())
        }
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
    }

    DisposableEffect(key1 = exoPlayer, key2 = primaryUrl, key3 = fallbackUrl) {
        val listener = object : Player.Listener {
            private var isUsingPrimaryUrl = true

            override fun onPlayerError(error: PlaybackException) {
                if (isUsingPrimaryUrl && fallbackUrl != null) {
                    isUsingPrimaryUrl = false
                    val fallbackMediaItem = MediaItem.Builder()
                        .setUri(fallbackUrl)
                        .setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setMaxPlaybackSpeed(1.02f)
                                .build()
                        )
                        .build()
                    val fallbackMediaSource = RtspMediaSource.Factory()
                        .setTimeoutMs(10000L)
                        .createMediaSource(fallbackMediaItem)

                    exoPlayer.setMediaSource(fallbackMediaSource)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                } else {
                    onPlaybackEnded()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onPlaybackEnded()
                }
            }
        }
        exoPlayer.addListener(listener)

        val initialUrl = primaryUrl ?: fallbackUrl
        if (initialUrl != null) {
            val mediaItem = MediaItem.Builder()
                .setUri(initialUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.02f)
                        .build()
                )
                .build()

            val mediaSource = RtspMediaSource.Factory()
                .setTimeoutMs(10000L)
                .createMediaSource(mediaItem)

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            onPlaybackEnded() // No URLs provided
        }

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
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
