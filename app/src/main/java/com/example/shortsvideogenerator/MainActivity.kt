package com.example.shortsvideogenerator

import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var textInput: TextInputEditText
    private lateinit var generateButton: Button
    private lateinit var downloadZipButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private var generatedZipFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textInput = findViewById(R.id.textInput)
        generateButton = findViewById(R.id.generateButton)
        downloadZipButton = findViewById(R.id.downloadZipButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        generateButton.setOnClickListener {
            if (checkPermissions()) {
                startGeneration()
            } else {
                requestPermissions()
            }
        }

        downloadZipButton.setOnClickListener {
            generatedZipFile?.let { zipFile ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        updateStatus("Saving ZIP to Videos folder...")
                        val uri = saveToVideosFolder(zipFile)
                        
                        withContext(Dispatchers.Main) {
                            if (uri != null) {
                                Toast.makeText(this@MainActivity, "✅ Saved to Videos folder!", Toast.LENGTH_LONG).show()
                                statusText.text = "✅ Videos saved successfully!"
                            } else {
                                Toast.makeText(this@MainActivity, "❌ Error saving to Videos folder", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE),
                100
            )
        }
    }

    private fun startGeneration() {
        val lines = textInput.text.toString().split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            Toast.makeText(this, "Please enter at least one line of text", Toast.LENGTH_SHORT).show()
            return
        }

        generateButton.isEnabled = false
        downloadZipButton.visibility = Button.GONE
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.max = 100
        progressBar.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(cacheDir, "shorts_assets")
                cacheDir.mkdirs()

                updateStatus("Extracting assets from APK...")
                extractAssets(cacheDir)

                val outputDir = File(cacheDir, "generated_videos")
                outputDir.mkdirs()

                for ((index, line) in lines.withIndex()) {
                    updateStatus("Generating video ${index + 1}/${lines.size}...")
                    concatenateVideos(cacheDir, outputDir, index)
                    progressBar.progress = ((index + 1) * 100) / lines.size
                }

                updateStatus("Creating ZIP file...")
                val zipFile = File(cacheDir, "ShortsVideos.zip")
                zipFiles(outputDir, zipFile)
                generatedZipFile = zipFile

                withContext(Dispatchers.Main) {
                    statusText.text = "✅ Generation complete! Tap Download to save."
                    downloadZipButton.visibility = Button.VISIBLE
                    generateButton.isEnabled = true
                    progressBar.visibility = ProgressBar.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "❌ Error:\n${e.message}"
                    generateButton.isEnabled = true
                    progressBar.visibility = ProgressBar.GONE
                }
            }
        }
    }

    private suspend fun saveToVideosFolder(zipFile: File): Uri? = withContext(Dispatchers.IO) {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ShortsVideos_${System.currentTimeMillis()}.zip")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Videos")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = resolver.insert(collection, contentValues)
        uri?.let {
            try {
                resolver.openOutputStream(it)?.use { outputStream ->
                    zipFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }
            } catch (e: Exception) {
                resolver.delete(it, null, null)
                throw e
            }
        }
        uri
    }

    private suspend fun extractAssets(cacheDir: File) = withContext(Dispatchers.IO) {
        val assetManager = applicationContext.assets
        val filesToExtract = mutableListOf("font.ttf")
        for (i in 1..5) {
            filesToExtract.add("$i.mp4")
        }

        for (fileName in filesToExtract) {
            val outFile = File(cacheDir, fileName)
            if (outFile.exists() && outFile.length() > 0) continue
            
            assetManager.open(fileName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    // NATIVE ANDROID VIDEO CONCATENATION - NO FFMPEG NEEDED
    private suspend fun concatenateVideos(cacheDir: File, outputDir: File, index: Int) = withContext(Dispatchers.IO) {
        val outputPath = File(outputDir, "video_$index.mp4").absolutePath
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var currentOffset = 0L
        
        try {
            // Concatenate all 5 videos
            for (i in 1..5) {
                val videoPath = File(cacheDir, "$i.mp4").absolutePath
                val extractor = MediaExtractor()
                extractor.setDataSource(videoPath)
                
                try {
                    // Process each track (video and audio)
                    for (trackIndex in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(trackIndex)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        
                        extractor.selectTrack(trackIndex)
                        
                        // Add track to muxer if not already added
                        if (mime.startsWith("video/") && videoTrackIndex == -1) {
                            // Force 1080x1920 resolution
                            val newFormat = MediaFormat().apply {
                                setString(MediaFormat.KEY_MIME, mime)
                                setInteger(MediaFormat.KEY_WIDTH, 1080)
                                setInteger(MediaFormat.KEY_HEIGHT, 1920)
                                if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                                    setInteger(MediaFormat.KEY_COLOR_STANDARD, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                                }
                                if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                                    setInteger(MediaFormat.KEY_COLOR_RANGE, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
                                }
                                if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                                }
                                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                    setInteger(MediaFormat.KEY_FRAME_RATE, format.getInteger(MediaFormat.KEY_FRAME_RATE))
                                }
                            }
                            videoTrackIndex = muxer.addTrack(newFormat)
                        } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                            audioTrackIndex = muxer.addTrack(format)
                        }
                        
                        // Copy samples with adjusted timestamps
                        val buffer = ByteBuffer.allocate(1024 * 1024)
                        val bufferInfo = MediaCodec.BufferInfo()
                        
                        while (true) {
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) break
                            
                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize
                            bufferInfo.presentationTimeUs = extractor.sampleTime + currentOffset
                            bufferInfo.flags = extractor.sampleFlags
                            
                            val targetTrack = if (mime.startsWith("video/")) videoTrackIndex else audioTrackIndex
                            if (targetTrack != -1) {
                                muxer.writeSampleData(targetTrack, buffer, bufferInfo)
                            }
                            
                            extractor.advance()
                        }
                        
                        // Update offset for next video
                        currentOffset += extractor.sampleTime
                    }
                } finally {
                    extractor.release()
                }
            }
        } finally {
            muxer.stop()
            muxer.release()
        }
    }

    private suspend fun zipFiles(inputDir: File, zipFile: File) {
        FileOutputStream(zipFile).use { fos ->
            ZipOutputStream(fos).use { zos ->
                for (file in inputDir.listFiles() ?: emptyArray()) {
                    if (file.isFile) {
                        zos.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    private suspend fun updateStatus(status: String) {
        withContext(Dispatchers.Main) {
            statusText.text = status
        }
    }
}
