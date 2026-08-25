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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Головний екран: кнопка запису/зупинки, перемикач мікрофона та список відео.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var micSwitch: MaterialSwitch
    private lateinit var adapter: RecordingsAdapter

    private val prefs by lazy { getSharedPreferences("recora", MODE_PRIVATE) }

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

    /** Дозволи, потрібні перед записом (мікрофон, сховище на Android <10). */
    private val recordPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (micSwitch.isChecked &&
                grants[Manifest.permission.RECORD_AUDIO] == false
            ) {
                Toast.makeText(this, R.string.audio_denied, Toast.LENGTH_LONG).show()
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == false
            ) {
                Toast.makeText(this, R.string.storage_denied, Toast.LENGTH_LONG).show()
            }
            // Запис усе одно починаємо: без дозволів Recora працює у fallback-режимі
            launchProjectionConsent()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.buttonRecord)
        statusText = findViewById(R.id.textStatus)
        emptyText = findViewById(R.id.textEmpty)
        micSwitch = findViewById(R.id.switchMic)

        micSwitch.isChecked = prefs.getBoolean("mic_enabled", true)
        micSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mic_enabled", isChecked).apply()
        }

        adapter = RecordingsAdapter(
            items = emptyList(),
            onClick = { openRecording(it) },
            onLongClick = { confirmDelete(it) }
        )
        findViewById<RecyclerView>(R.id.listRecordings).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        recordButton.setOnClickListener {
            if (ScreenRecordService.isRecording.value) {
                stopRecordingService()
            } else {
                ensurePermissionsThenConsent()
            }
        }

        // Підписка на стан запису та подію збереження
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ScreenRecordService.isRecording.collect { render(it) }
                }
                launch {
                    ScreenRecordService.lastSaved.collect { saved ->
                        if (saved) {
                            ScreenRecordService.lastSaved.value = false // одноразова подія
                            refreshRecordings()
                            Snackbar.make(
                                recordButton,
                                getString(R.string.recording_saved),
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

    /** Перед записом: мікрофон (якщо ввімкнено) + сховище на Android <10. */
    private fun ensurePermissionsThenConsent() {
        val needed = mutableListOf<String>()

        if (micSwitch.isChecked &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (needed.isEmpty()) {
            launchProjectionConsent()
        } else {
            recordPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val withAudio = micSwitch.isChecked &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordService.EXTRA_WITH_AUDIO, withAudio)
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
        micSwitch.isEnabled = !recording
    }

    private fun refreshRecordings() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                RecordingStore.listAll(this@MainActivity)
            }
            adapter.submit(items)
            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            emptyText.setText(R.string.no_recordings)
        }
    }

    /** Відкрити відео у зовнішньому плеєрі. */
    private fun openRecording(recording: Recording) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(recording.viewUri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, recording.name))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_video_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, recording.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val ok = RecordingStore.delete(this, recording)
                refreshRecordings()
                Snackbar.make(
                    recordButton,
                    getString(if (ok) R.string.deleted else R.string.delete_failed),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
