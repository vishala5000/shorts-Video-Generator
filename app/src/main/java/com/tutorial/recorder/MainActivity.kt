package com.tutorial.recorder

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var toggleAudio: ToggleButton

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            requestScreenCapture()
        } else {
            Toast.makeText(this, "Permissions required to record", Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startRecordingService(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        toggleAudio = findViewById(R.id.toggleAudio)

        btnStop.isEnabled = false

        btnStart.setOnClickListener { checkPermissionsAndStart() }
        btnStop.setOnClickListener { stopRecordingService() }

        createNotificationChannel()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            requestScreenCapture()
        } else {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestScreenCapture() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, RecordingService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
            putExtra("recordAudio", toggleAudio.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
        
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "Recording in HD..."
    }

    private fun stopRecordingService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = "STOP_RECORDING"
        }
        startService(intent)
        
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "Ready"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "recorder_channel",
                "Screen Recorder Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

// Nested Service class to keep everything strictly within the single file constraint
class RecordingService : android.app.Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_RECORDING") {
            stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        val recordAudio = intent?.getBooleanExtra("recordAudio", false) ?: false

        if (resultCode != -1 && data != null) {
            val notification = createNotification()
            startForeground(1, notification)

            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            startRecording(recordAudio)
        }

        return START_NOT_STICKY
    }

    private fun startRecording(recordAudio: Boolean) {
        val metrics = resources.displayMetrics
        // Cap at 1080p for hardware stability across fragmented devices
        val recWidth = if (metrics.widthPixels > 1920) 1920 else metrics.widthPixels
        val recHeight = if (metrics.heightPixels > 1080) 1080 else metrics.heightPixels
        val dpi = metrics.densityDpi

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Tutorial_$timestamp.mp4"
        
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Tutorials_Recorder")
        if (!dir.exists()) dir.mkdirs()
        val outputFile = File(dir, fileName)

        mediaRecorder = if (recordAudio) {
            MediaRecorder(applicationContext).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncodingProfileLevel(
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, 
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
                )
                setVideoSize(recWidth, recHeight)
                setVideoFrameRate(60)
                setVideoEncodingBitRate(10_000_000) // 10 Mbps for high quality
                setOutputFile(outputFile.absolutePath)
                prepare()
            }
        } else {
            MediaRecorder(applicationContext).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingProfileLevel(
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, 
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
                )
                setVideoSize(recWidth, recHeight)
                setVideoFrameRate(60)
                setVideoEncodingBitRate(10_000_000)
                setOutputFile(outputFile.absolutePath)
                prepare()
            }
        }

        val surface = mediaRecorder!!.surface
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            recWidth, recHeight, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        mediaRecorder?.start()
        isRecording = true
    }

    private fun stopRecording() {
        if (isRecording) {
            virtualDisplay?.release()
            try { mediaRecorder?.stop() } catch (e: Exception) { /* Ignore if not started */ }
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaProjection?.stop()
            
            virtualDisplay = null
            mediaRecorder = null
            mediaProjection = null
            isRecording = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, "recorder_channel")
            .setContentTitle("V Tutorial Recorder")
            .setContentText("Recording in 1080p60fps...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }
}
