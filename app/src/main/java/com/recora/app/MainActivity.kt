package com.recora.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File

/**
 * Головний екран: кнопка запису/зупинки та список готових відео.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var adapter: RecordingsAdapter

    /** Системний діалог дозволу на захоплення екрана (MediaProjection). */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startRecordingService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    /** Дозвіл на сповіщення (Android 13+). Не критично, якщо відмовлено. */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.buttonRecord)
        statusText = findViewById(R.id.textStatus)
        emptyText = findViewById(R.id.textEmpty)

        adapter = RecordingsAdapter(emptyList()) { file -> openRecording(file) }
        findViewById<RecyclerView>(R.id.listRecordings).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        recordButton.setOnClickListener {
            if (ScreenRecordService.isRecording.value) {
                stopRecordingService()
            } else {
                launchProjectionConsent()
            }
        }

        // Підписка на стан запису та подію збереження файла
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ScreenRecordService.isRecording.collect { render(it) }
                }
                launch {
                    ScreenRecordService.lastSavedFile.collect { file ->
                        if (file != null) {
                            ScreenRecordService.lastSavedFile.value = null // одноразова подія
                            refreshRecordings()
                            Snackbar.make(
                                recordButton,
                                getString(R.string.recording_saved, file.name),
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        askNotificationPermissionIfNeeded()
        render(ScreenRecordService.isRecording.value)
    }

    override fun onResume() {
        super.onResume()
        refreshRecordings()
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopRecordingService() {
        startService(
            Intent(this, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_STOP)
        )
    }

    private fun render(recording: Boolean) {
        recordButton.setText(if (recording) R.string.stop_recording else R.string.start_recording)
        statusText.setText(if (recording) R.string.recording_active else R.string.hint_start)
    }

    private fun refreshRecordings() {
        val files = ScreenRecordService.outputDir(this)
            .listFiles { f -> f.isFile && f.extension.equals("mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        adapter.submit(files)
        emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Відкрити відео у зовнішньому плеєрі через FileProvider. */
    private fun openRecording(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, file.name))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_video_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
