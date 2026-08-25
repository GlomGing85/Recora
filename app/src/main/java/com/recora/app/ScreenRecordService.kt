package com.recora.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сервіс переднього плану, що захоплює екран через MediaProjection
 * і записує відео у MP4 (H.264) за допомогою MediaRecorder.
 *
 * Сумісність: Android 7.0 (API 24) і новіші.
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.recora.app.action.START"
        const val ACTION_STOP = "com.recora.app.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_recording"

        /** Чи йде запис зараз (для оновлення UI активності). */
        val isRecording = MutableStateFlow(false)

        /** Одноразова подія: останній успішно збережений файл. */
        val lastSavedFile = MutableStateFlow<File?>(null)

        /** Тека, куди зберігаються записи (не потребує дозволів на сховище). */
        fun outputDir(context: Context): File {
            val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val dir = if (movies != null) {
                File(movies, "ScreenRecorder")
            } else {
                File(context.filesDir, "recordings")
            }
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    private val recording = AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var callbackRegistered = false

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Android 14+ вимагає зареєстрований Callback ще ДО createVirtualDisplay(). */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Система припинила захоплення (наприклад, дозвіл скасовано)
            workerHandler.post { stopRecording() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("ScreenRecordWorker").apply { start() }
        workerHandler = Handler(workerThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (recording.get()) return START_STICKY

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                // FGS типу mediaProjection має стартувати ДО getMediaProjection() (вимога Android 14+)
                startForegroundWithType(buildNotification())
                workerHandler.post { startRecording(resultCode, data) }
            }

            ACTION_STOP -> workerHandler.post { stopRecording() }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ record

    private fun startRecording(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            toast(getString(R.string.projection_error))
            stopForegroundAndSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, workerHandler)
        callbackRegistered = true

        // Реальні розміри екрана
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            toast(getString(R.string.recording_error, "display metrics"))
            stopRecording()
            return
        }

        // Обмежуємо довшу сторону до 1280 px — щоб старі кодеки (Android 7) тягнули
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        val longer = maxOf(width, height)
        val maxSide = 1280
        if (longer > maxSide) {
            val scale = maxSide.toFloat() / longer
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }
        // Кодеки вимагають парні розміри
        width -= width % 2
        height -= height % 2

        val bitRate = (width.toLong() * height * 4L)
            .coerceIn(2_000_000L, 16_000_000L).toInt()

        val file = createOutputFile()
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(bitRate)
                setOutputFile(file.absolutePath)
                prepare()
            }

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenRecorder",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null, null
            )

            recorder.start()
        } catch (e: Exception) {
            e.printStackTrace()
            file.delete()
            releaseRecorderAndDisplay()
            stopProjection()
            toast(getString(R.string.recording_error, e.javaClass.simpleName))
            stopForegroundAndSelf()
            return
        }

        mediaRecorder = recorder
        currentFile = file
        recording.set(true)
        isRecording.value = true
    }

    // ------------------------------------------------------------------- stop

    @Synchronized
    private fun stopRecording() {
        if (!callbackRegistered && mediaRecorder == null && mediaProjection == null) return

        val wasRecording = recording.getAndSet(false)
        isRecording.value = false

        val file = currentFile
        var savedOk = false
        try {
            if (wasRecording) {
                mediaRecorder?.stop() // кине виняток, якщо запис занадто короткий
                savedOk = true
            }
        } catch (e: Exception) {
            // Менше ~1 секунди — кодек не встиг записати кадри
            file?.delete()
            toast(getString(R.string.recording_too_short))
        }

        releaseRecorderAndDisplay()
        stopProjection()
        currentFile = null

        if (savedOk && file != null && file.exists()) {
            // Щоб відео зʼявилося у «Галереї»
            MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            lastSavedFile.value = file
        }

        stopForegroundAndSelf()
    }

    private fun releaseRecorderAndDisplay() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
    }

    private fun stopProjection() {
        if (callbackRegistered) {
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            callbackRegistered = false
        }
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ misc

    private fun createOutputFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(outputDir(this), "REC_$stamp.mp4")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(getString(R.string.notif_recording_text))
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop), stopPending)
            .build()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopRecording()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopRecording()
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
