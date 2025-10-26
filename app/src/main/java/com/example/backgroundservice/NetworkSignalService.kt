package com.example.backgroundservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import org.json.JSONObject
import org.json.JSONArray
import java.util.HashMap

const val ACTION_CLOSE_CAMERA_STREAM = "com.example.backgroundservice.ACTION_CLOSE_CAMERA_STREAM"
const val ACTION_CLOSE_REMOTE_CAMERA_VIEW = "com.example.backgroundservice.ACTION_CLOSE_REMOTE_CAMERA_VIEW"
const val ACTION_UPDATE_STREAM_URL = "com.example.backgroundservice.ACTION_UPDATE_STREAM_URL"
const val ACTION_PLAY_ANIMATION = "com.example.backgroundservice.ACTION_PLAY_ANIMATION"
const val ACTION_STOP_ANIMATION = "com.example.backgroundservice.ACTION_STOP_ANIMATION"
const val ACTION_SET_ANIMATION_VOLUME = "com.example.backgroundservice.ACTION_SET_ANIMATION_VOLUME"

class NetworkSignalService : Service() {

    private val TAG = "NetworkSignalService"
    private var webServer: MyWebServer? = null
    private val PORT = 8080
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "NetworkSignalServiceChannel"
    private val STREAM_CHANNEL_ID = "StreamNotificationChannel"

    companion object {
        var isServiceRunning = false
        var isStreamActivityRunning = false
        var isRemoteCameraActivityRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service creating.")
        isServiceRunning = true

        createNotificationChannel()
        createStreamNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        webServer = MyWebServer(PORT)
        try {
            webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val ipAddress = getLocalIpAddress()
            Log.d(TAG, "NanoHTTPD server started on: http://$ipAddress:$PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Error starting NanoHTTPD server", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Network Signal Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createStreamNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val streamChannel = NotificationChannel(
                STREAM_CHANNEL_ID,
                "Stream Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming streams"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(streamChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Network Signal Service")
            .setContentText("Service is running in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    inner class MyWebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            Log.d(TAG, "Received request: ${session.method} $uri from ${session.remoteIpAddress}")

            if (Method.OPTIONS.equals(session.method)) {
                val response = newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, null, 0)
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE")
                response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
                response.addHeader("Access-Control-Max-Age", "86400")
                return response
            }

            when {
                "/stream" == uri && Method.POST == session.method -> {
                    return handleStreamRequest(session)
                }
                "/videos" == uri && Method.GET == session.method -> {
                    return handleGetVideosRequest(session)
                }
                "/play-animation" == uri && Method.POST == session.method -> {
                    return handlePlayAnimationRequest(session)
                }
                "/stop-animation" == uri && Method.POST == session.method -> {
                    return handleStopAnimationRequest(session)
                }
                "/animation-volume" == uri && Method.POST == session.method -> {
                    return handleAnimationVolumeRequest(session)
                }
                "/trigger" == uri -> {
                    return if (Method.POST.equals(session.method)) {
                        Log.d(TAG, "Handling POST request for /trigger from ${session.remoteIpAddress}")
                        handleTriggerRemoteStream(session)
                    } else {
                        newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed").apply {
                            addHeader("Access-Control-Allow-Origin", "*")
                        }
                    }
                }
                "/toggle-stream" == uri && Method.GET == session.method -> {
                    val params = session.parameters
                    val streamUrl = params["url"]?.get(0)
                    handleToggleStream(streamUrl)
                    return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "Signal received, toggling local stream.").apply {
                        addHeader("Access-Control-Allow-Origin", "*")
                    }
                }
                else -> {
                    return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found").apply {
                        addHeader("Access-Control-Allow-Origin", "*")
                    }
                }
            }
        }
    }

    private fun openStreamActivity(externalUrl: String?, localUrl: String?) {
        if (isStreamActivityRunning) {
            val updateIntent = Intent(ACTION_UPDATE_STREAM_URL).apply {
                putExtra("EXTERNAL_STREAM_URL", externalUrl)
                putExtra("LOCAL_STREAM_URL", localUrl)
            }
            sendBroadcast(updateIntent)
            return
        }

        val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isDebugMode = sharedPreferences.getBoolean("debug_mode", false)

        val intent = Intent(this, CameraStreamActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTERNAL_STREAM_URL", externalUrl)
            putExtra("LOCAL_STREAM_URL", localUrl)
            putExtra("DEBUG_MODE", isDebugMode)
        }

        try {
            startActivity(intent)
            Log.d(TAG, "CameraStreamActivity started automatically")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CameraStreamActivity automatically", e)
            showFallbackNotification(externalUrl, localUrl)
        }
    }

    private fun handleStreamRequest(session: IHTTPSession): Response {
        try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val jsonBody = files["postData"]
            if (jsonBody != null) {
                val json = JSONObject(jsonBody)
                val localUrl = json.optString("local_url", null)
                val externalUrl = json.optString("external_url", null)

                if (localUrl != null || externalUrl != null) {
                    Log.d(TAG, "Received stream URLs. External: $externalUrl, Local: $localUrl")

                    openStreamActivity(externalUrl, localUrl)

                    return newFixedLengthResponse(Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK").apply {
                        addHeader("Access-Control-Allow-Origin", "*")
                    }
                } else {
                    Log.w(TAG, "Request to /stream without any URL in body: $jsonBody")
                    return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "No stream URL found in request body.").apply {
                        addHeader("Access-Control-Allow-Origin", "*")
                    }
                }
            } else {
                Log.w(TAG, "Request to /stream with empty body.")
                return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Empty request body.").apply {
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling /stream request", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error.").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }

    private fun handleGetVideosRequest(session: IHTTPSession): Response {
        try {
            val videoFiles = getVideoFiles()
            val jsonArray = JSONArray()

            for (videoFile in videoFiles) {
                val jsonObject = JSONObject()
                jsonObject.put("name", videoFile)
                jsonArray.put(jsonObject)
            }

            return newFixedLengthResponse(Status.OK, "application/json", jsonArray.toString()).apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video files", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error getting video files").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }

    private fun handlePlayAnimationRequest(session: IHTTPSession): Response {
        try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val jsonBody = files["postData"]
            if (jsonBody != null) {
                val json = JSONObject(jsonBody)
                val videoName = json.optString("video_name", "")

                if (videoName.isNotEmpty()) {
                    Log.d(TAG, "Received play animation request for: $videoName")

                    CameraStreamActivity.currentVideoName = videoName
                    Log.d(TAG, "Updated currentVideoName to: $videoName")

                    return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "Animation started").apply {
                        addHeader("Access-Control-Allow-Origin", "*")
                    }
                }
            }
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid request").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling play animation request", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }

    private fun handleStopAnimationRequest(session: IHTTPSession): Response {
        try {
            Log.d(TAG, "Received stop animation request")

            CameraStreamActivity.currentVideoName = null
            Log.d(TAG, "Stopped animation")

            return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "Animation stopped").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling stop animation request", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }

    private fun handleAnimationVolumeRequest(session: IHTTPSession): Response {
        try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val jsonBody = files["postData"]
            if (jsonBody != null) {
                val json = JSONObject(jsonBody)
                val volume = json.optDouble("volume", 1.0)

                Log.d(TAG, "Received volume change request: $volume")

                CameraStreamActivity.currentVolume = volume.toFloat()
                Log.d(TAG, "Updated volume to: ${CameraStreamActivity.currentVolume}")

                return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "Volume set").apply {
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            }
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid request").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling volume request", e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }

    private fun getVideoFiles(): List<String> {
        val videoFiles = mutableListOf<String>()

        try {
            val externalDirs = arrayOf(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                android.os.Environment.getExternalStorageDirectory()
            )

            val videoExtensions = arrayOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "3gp", "webm", "m4v")

            for (directory in externalDirs) {
                if (directory != null && directory.exists() && directory.isDirectory) {
                    scanDirectoryForVideos(directory, videoFiles, videoExtensions, 0)
                }
            }

            videoFiles.sort()

            Log.d(TAG, "Found video files: ${videoFiles.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Error getting video files", e)
        }

        return videoFiles
    }

    private fun scanDirectoryForVideos(
        directory: java.io.File,
        videoFiles: MutableList<String>,
        extensions: Array<String>,
        depth: Int
    ) {
        try {
            if (depth > 5) return

            val files = directory.listFiles() ?: return

            for (file in files) {
                if (file.isDirectory) {
                    if (!file.name.startsWith(".") &&
                        !file.name.equals("Android", true) &&
                        !file.name.equals("lost+found", true)) {
                        scanDirectoryForVideos(file, videoFiles, extensions, depth + 1)
                    }
                } else if (file.isFile) {
                    val fileName = file.name.toLowerCase()
                    val extension = fileName.substringAfterLast('.', "")
                    if (extensions.contains(extension)) {
                        videoFiles.add(file.name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning directory ${directory.absolutePath}", e)
        }
    }

    private fun scanDirectoryForVideos(directory: java.io.File, videoFiles: MutableList<String>, extensions: Array<String>) {
        try {
            val files = directory.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isDirectory) {
                        if (file.absolutePath.count { it == '/' } < 8) {
                            scanDirectoryForVideos(file, videoFiles, extensions)
                        }
                    } else if (file.isFile) {
                        val fileName = file.name.toLowerCase()
                        val extension = fileName.substringAfterLast('.', "")
                        if (extensions.contains(extension)) {
                            videoFiles.add(file.name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning directory ${directory.absolutePath}", e)
        }
    }

    private fun showFallbackNotification(externalUrl: String?, localUrl: String?) {
        val intent = Intent(this, CameraStreamActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("EXTERNAL_STREAM_URL", externalUrl)
            putExtra("LOCAL_STREAM_URL", localUrl)
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, STREAM_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming Stream")
            .setContentText("Tap to open the stream.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2, notificationBuilder.build())
    }

    private fun handleTriggerRemoteStream(session: IHTTPSession): Response {
        return if (!isRemoteCameraActivityRunning) {
            Log.d(TAG, "Attempting to start RemoteCameraViewActivity.")
            val remoteIpAddress = session.remoteIpAddress
            val intent = Intent(this@NetworkSignalService, RemoteCameraViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("REMOTE_IP_ADDRESS", remoteIpAddress)
            }
            try {
                startActivity(intent)
                Log.i(TAG, "RemoteCameraViewActivity started successfully for IP: $remoteIpAddress")
                NanoHTTPD.newFixedLengthResponse(Status.OK, NanoHTTPD.MIME_PLAINTEXT, "Remote stream started.").apply {
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "RemoteCameraViewActivity not found. Check AndroidManifest.xml.", e)
                NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Server error: Target activity not found.").apply {
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RemoteCameraViewActivity due to an unexpected error.", e)
                NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Server error: Could not start remote view. ${e.localizedMessage}").apply {
                    addHeader("Access-control-allow-origin", "*")
                }
            }
        } else {
            Log.d(TAG, "Sending close broadcast to RemoteCameraViewActivity.")
            val intent = Intent(ACTION_CLOSE_REMOTE_CAMERA_VIEW)
            sendBroadcast(intent)
            NanoHTTPD.newFixedLengthResponse(Status.OK, NanoHTTPD.MIME_PLAINTEXT, "Remote stream stopped.").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }

    private fun handleToggleStream(streamUrl: String?) {
        if (!isStreamActivityRunning) {
            if (streamUrl == null) {
                Log.e(TAG, "Stream URL is null, cannot start CameraStreamActivity.")
                return
            }
            Log.d(TAG, "Starting CameraStreamActivity.")
            val intent = Intent(this, CameraStreamActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("STREAM_URL", streamUrl)
            }
            startActivity(intent)
        } else {
            Log.d(TAG, "Sending close broadcast to CameraStreamActivity.")
            val intent = Intent(ACTION_CLOSE_CAMERA_STREAM)
            sendBroadcast(intent)
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addresses: List<java.net.InetAddress> = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (addr is Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return "0.0.0.0"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started.")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying.")
        isServiceRunning = false
        webServer?.stop()
        Log.d(TAG, "NanoHTTPD server stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}