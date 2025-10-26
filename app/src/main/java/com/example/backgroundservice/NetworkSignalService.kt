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
import java.util.HashMap

// Action for the broadcast to close the CameraStreamActivity
const val ACTION_CLOSE_CAMERA_STREAM = "com.example.backgroundservice.ACTION_CLOSE_CAMERA_STREAM"
// Action for the broadcast to close the RemoteCameraViewActivity
const val ACTION_CLOSE_REMOTE_CAMERA_VIEW = "com.example.backgroundservice.ACTION_CLOSE_REMOTE_CAMERA_VIEW"

class NetworkSignalService : Service() {

    private val TAG = "NetworkSignalService"
    private var webServer: MyWebServer? = null
    private val PORT = 8080
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "NetworkSignalServiceChannel"
    private val STREAM_CHANNEL_ID = "StreamNotificationChannel"
    private val ACTION_UPDATE_STREAM_URL = "com.example.backgroundservice.ACTION_UPDATE_STREAM_URL"

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

            if ("/stream" == uri && Method.POST == session.method) {
                return handleStreamRequest(session)
            }

            if ("/trigger" == uri) {
                return if (Method.POST.equals(session.method)) {
                    Log.d(TAG, "Handling POST request for /trigger from ${session.remoteIpAddress}")
                    handleTriggerRemoteStream(session)
                } else {
                    newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed").apply {
                        addHeader("Access-Control-Allow-Origin", "*")
                    }
                }
            }

            if ("/toggle-stream" == uri && Method.GET == session.method) {
                val params = session.parameters
                val streamUrl = params["url"]?.get(0)
                handleToggleStream(streamUrl)
                return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "Signal received, toggling local stream.").apply {
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            }

            // For other requests, return 404
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found").apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }

    private fun openStreamActivity(externalUrl: String?, localUrl: String?) {
        // Если активность уже запущена, обновляем URL вместо создания новой
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

                    // НЕПОСРЕДСТВЕННО открываем активность вместо показа уведомления
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
            } catch (e: Exception) { // Catch any other exceptions during startActivity
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
            // isStreamActivityRunning will be set to true by CameraStreamActivity
        } else {
            Log.d(TAG, "Sending close broadcast to CameraStreamActivity.")
            val intent = Intent(ACTION_CLOSE_CAMERA_STREAM)
            sendBroadcast(intent)
            // isStreamActivityRunning will be set to false by CameraStreamActivity
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
