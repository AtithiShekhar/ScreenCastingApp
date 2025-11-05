package com.example.cast_screen


import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class ScreenCastService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isStreaming = false

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "ScreenCastChannel"
    private val RTSP_PORT = 8554

    private var screenWidth = 1920
    private var screenHeight = 1080
    private val videoBitrate = 2500000
    private val videoFrameRate = 30

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        getScreenDimensions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService()
            startScreenCapture(resultCode, data)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun getScreenDimensions() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels }
        screenWidth = (screenWidth / 2) * 2
        screenHeight = (screenHeight / 2) * 2
    }

    private fun startForegroundService() {
        val notification = createNotification("Preparing screen cast...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        try {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            setupMediaCodec()

            startRTSPServer()
            updateNotification("Screen casting active - Waiting for connection")
        } catch (e: Exception) {
            e.printStackTrace()
            updateNotification("Error: ${e.message}")
            stopSelf() } }

    private fun setupMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, videoFrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = createInputSurface()
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCast",
                    screenWidth,
                    screenHeight,
                    resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null)
                start() }

            isStreaming = true

        } catch (e: Exception) {
            e.printStackTrace()
            throw IOException("Failed to setup MediaCodec: ${e.message}") } }

    private fun startRTSPServer() {
        thread {
            try {
                serverSocket = ServerSocket(RTSP_PORT)
                updateNotification("Ready to cast - Port: $RTSP_PORT")

                while (isStreaming) {
                    try {
                        clientSocket = serverSocket?.accept()
                        updateNotification("Client connected - Streaming...")
                        handleClient(clientSocket!!)
                    } catch (e: Exception) {
                        if (isStreaming) {
                            e.printStackTrace() } } }
            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("Server error: ${e.message}") } }
        thread {
            encodeAndStream() } }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream().bufferedWriter()
            val request = input.readLine()
            if (request?.startsWith("OPTIONS") == true || request?.startsWith("DESCRIBE") == true) {
                val response = """
                    RTSP/1.0 200 OK
                    CSeq: 1
                    Content-Type: application/sdp
                    Content-Length: 0
                    
                """.trimIndent()

                output.write(response)
                output.flush() }
        } catch (e: Exception) {
            e.printStackTrace() } }

    private fun encodeAndStream() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isStreaming) {
            try {
                mediaCodec?.let { codec ->
                    val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000)

                    if (outputBufferId >= 0) {
                        val outputBuffer: ByteBuffer = codec.getOutputBuffer(outputBufferId)!!

                        if (bufferInfo.size > 0) {
                            try {
                                clientSocket?.let { socket ->
                                    if (socket.isConnected) {
                                        val data = ByteArray(bufferInfo.size)
                                        outputBuffer.get(data)
                                        socket.getOutputStream().write(data)
                                        socket.getOutputStream().flush() } }
                            } catch (e: Exception) {
                                updateNotification("Client disconnected - Waiting for connection") } }

                        codec.releaseOutputBuffer(outputBufferId, false) } }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isStreaming) {
                    Thread.sleep(100) } } } }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Cast Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for screen casting service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ScreenCastService::class.java).apply {
            action = "STOP_CAST"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Cast Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace() }
        virtualDisplay?.release()
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaProjection?.stop()
        virtualDisplay = null
        mediaCodec = null
        mediaProjection = null }

    override fun onBind(intent: Intent?): IBinder? = null
}