package com.example.cameralink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.net.NetworkInterface
import java.util.concurrent.Executors

class CameraStreamingService : LifecycleService() {

    private var streamingServer: StreamingServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var camera: Camera? = null
    private var isStreamingActive = false

    // Auto-flash (torch in low light) state. Read on the camera-analyzer thread and written on
    // the main thread during (re)bind, so these must be volatile for cross-thread visibility.
    @Volatile private var torchOn = false
    @Volatile private var lastTorchEval = 0L

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "camera_streaming_channel"
        const val ACTION_START_STREAMING = "com.example.cameralink.START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.example.cameralink.STOP_STREAMING"
        const val EXTRA_PORT = "port"

        // Auto-flash tuning. Average Y-plane luminance is 0 (black) .. 255 (white).
        // Hysteresis: turn the torch ON only when clearly dark, and OFF only once the
        // scene is comfortably bright, so it doesn't oscillate. Evaluations are throttled
        // so the torch toggles at most about once per interval.
        private const val TORCH_ON_LUMA = 45
        private const val TORCH_OFF_LUMA = 110
        private const val TORCH_EVAL_INTERVAL_MS = 1500L

        fun startService(context: Context, port: Int = 8080) {
            val intent = Intent(context, CameraStreamingService::class.java).apply {
                action = ACTION_START_STREAMING
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CameraStreamingService::class.java).apply {
                action = ACTION_STOP_STREAMING
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        TailscalePinger.init(this)
        CameraSettings.init(this)
        // Rebind the camera live whenever lens/resolution changes.
        CameraSettings.onChanged = {
            ContextCompat.getMainExecutor(this).execute {
                if (isStreamingActive) startCamera()
            }
        }
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_STREAMING -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                startStreaming(port)
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startStreaming(port: Int) {
        // Start foreground service with notification
        val notification = createNotification("Starting camera stream...", getIpAddress(), port)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )

        // The start intent can be redelivered (e.g. the activity is recreated on rotation and
        // re-invokes startService). Don't bind a second server on the same port - that throws
        // BindException: EADDRINUSE and crashes the process.
        if (streamingServer != null) {
            return
        }

        isStreamingActive = true

        // Start streaming server
        streamingServer = StreamingServer(port).apply {
            start()
        }

        // Start camera
        startCamera()

        // Update notification with streaming info
        val updatedNotification = createNotification("Camera streaming active", getIpAddress(), port)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, updatedNotification)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                CameraSettings.availableLenses = CameraLensResolver.availableLenses(this, provider)

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            CameraSettings.resolution.size,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            streamingServer?.updateFrame(imageProxy)
                            maybeUpdateTorch(imageProxy)
                            imageProxy.close()
                        }
                    }

                val cameraSelector = try {
                    CameraLensResolver.selectorFor(this, provider, CameraSettings.lens)
                } catch (e: Exception) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.unbindAll()
                // A rebind drops any active torch; reset our tracking so auto-flash
                // re-evaluates from scratch against the newly bound camera.
                torchOn = false
                lastTorchEval = 0L
                camera = try {
                    provider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
                } catch (e: Exception) {
                    // Requested lens may be unusable on this device; fall back to default.
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalyzer)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * When auto-flash is enabled and the active lens has a torch, turn the torch on while the
     * scene is dark and off once it's bright again. Evaluation is throttled and uses hysteresis
     * to avoid flickering. Cheap: samples the already-available Y (luma) plane.
     */
    private fun maybeUpdateTorch(imageProxy: ImageProxy) {
        val cam = camera ?: return
        val canFlash = cam.cameraInfo.hasFlashUnit()

        if (!CameraSettings.autoFlash || !canFlash) {
            if (torchOn) {
                cam.cameraControl.enableTorch(false)
                torchOn = false
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTorchEval < TORCH_EVAL_INTERVAL_MS) return
        lastTorchEval = now

        val luma = averageLuminance(imageProxy)
        val shouldBeOn = if (torchOn) luma < TORCH_OFF_LUMA else luma < TORCH_ON_LUMA
        if (shouldBeOn != torchOn) {
            cam.cameraControl.enableTorch(shouldBeOn)
            torchOn = shouldBeOn
        }
    }

    /** Average brightness (0..255) of a sparse sample of the image's luma plane. */
    private fun averageLuminance(imageProxy: ImageProxy): Int {
        val plane = imageProxy.planes.firstOrNull() ?: return 0
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) return 0

        val stepX = (width / 32).coerceAtLeast(1)
        val stepY = (height / 32).coerceAtLeast(1)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            val rowStart = y * rowStride
            var x = 0
            while (x < width) {
                val idx = rowStart + x * pixelStride
                if (idx < buffer.limit()) {
                    sum += (buffer.get(idx).toInt() and 0xFF)
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        return if (count > 0) (sum / count).toInt() else 0
    }

    private fun stopStreaming() {
        isStreamingActive = false
        streamingServer?.stop()
        streamingServer = null
        if (torchOn) {
            try {
                camera?.cameraControl?.enableTorch(false)
            } catch (e: Exception) {
                // Camera may already be released; unbindAll below clears the torch anyway.
            }
            torchOn = false
        }
        camera = null
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CameraLink::StreamingWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L /*10 hours*/)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for camera streaming service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, ipAddress: String, port: Int): Notification {
        val stopIntent = Intent(this, CameraStreamingService::class.java).apply {
            action = ACTION_STOP_STREAMING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (ipAddress.isNotEmpty() && ipAddress != "Unable to get IP") {
            "Stream URL: http://$ipAddress:$port"
        } else {
            "Waiting for network connection..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$contentText\n\nTap to open app. Camera will continue streaming even with screen off.")
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && hostAddress.indexOf(':') < 0) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    override fun onDestroy() {
        super.onDestroy()
        CameraSettings.onChanged = null
        stopStreaming()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}

