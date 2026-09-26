package com.example.shortsvideogenerator

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
                                Toast.makeText(
                                    this@MainActivity,
                                    "✅ Saved to Videos folder!",
                                    Toast.LENGTH_LONG
                                ).show()
                                statusText.text = "✅ Videos saved successfully!"
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "❌ Error saving to Videos folder",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Error: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
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
                    generateVideoForLine(line, index + 1, cacheDir, outputDir)
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
                    statusText.text = "❌ Error: ${e.message}"
                    generateButton.isEnabled = true
                    progressBar.visibility = ProgressBar.GONE
                }
            }
        }
    }

    private suspend fun saveToVideosFolder(zipFile: File): Uri? = withContext(Dispatchers.IO) {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ShortsVideos_${System.currentTimeMillis()}.zip")
            put(MediaStore.Video.Media.MIME_TYPE, "application/zip")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Videos")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                zipFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }
        }
        uri
    }

    private suspend fun extractAssets(cacheDir: File) = coroutineScope {
        val assetManager = applicationContext.assets
        val filesToExtract = mutableListOf("font.ttf")
        for (i in 1..200) {
            filesToExtract.add("$i.mp4")
        }

        val pendingExtractions = filesToExtract.filter { fileName ->
            val outFile = File(cacheDir, fileName)
            !(outFile.exists() && outFile.length() > 0)
        }

        if (pendingExtractions.isEmpty()) {
            updateStatus("Assets ready (cached)")
            return@coroutineScope
        }

        val chunkSize = 20
        pendingExtractions.chunked(chunkSize).forEach { chunk ->
            chunk.map { fileName ->
                async(Dispatchers.IO) {
                    val outFile = File(cacheDir, fileName)
                    assetManager.open(fileName).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun wrapText(text: String): String {
        val maxCharsPerLine = 15
        val maxLines = 16
        
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = ""
        
        for (word in words) {
            if (word.length > maxCharsPerLine) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = ""
                }
                var remainingWord = word
                while (remainingWord.length > maxCharsPerLine) {
                    lines.add(remainingWord.substring(0, maxCharsPerLine))
                    remainingWord = remainingWord.substring(maxCharsPerLine)
                    if (lines.size >= maxLines) return lines.joinToString("\n")
                }
                currentLine = remainingWord
            } else if (currentLine.isEmpty()) {
                currentLine = word
            } else if ((currentLine.length + 1 + word.length) <= maxCharsPerLine) {
                currentLine += " $word"
            } else {
                lines.add(currentLine)
                if (lines.size >= maxLines) return lines.joinToString("\n")
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty() && lines.size < maxLines) {
            lines.add(currentLine)
        }
        
        return lines.joinToString("\n")
    }

    private suspend fun generateVideoForLine(text: String, index: Int, cacheDir: File, outputDir: File) {
        val inputsFile = File(cacheDir, "inputs.txt")
        val sb = StringBuilder()
        for (i in 1..200) {
            // Escape single quotes in path just in case
            val videoPath = File(cacheDir, "$i.mp4").absolutePath.replace("'", "\\'")
            
            // CRITICAL FIX: inpoint and outpoint MUST come BEFORE the file directive
            sb.append("inpoint 0\n")
            sb.append("outpoint 0.04\n")
            sb.append("file '$videoPath'\n")
        }
        inputsFile.writeText(sb.toString())

        val wrappedText = wrapText(text)
        val textFile = File(cacheDir, "text_$index.txt")
        textFile.writeText(wrappedText)

        val outputPath = File(outputDir, "video_$index.mp4").absolutePath
        val fontPath = File(cacheDir, "font.ttf").absolutePath.replace("'", "\\'")
        val textFilePath = textFile.absolutePath.replace("'", "\\'")

        val filter = "drawtext=fontfile='$fontPath':textfile='$textFilePath':fontcolor=white:fontsize=80:x=200:y=300:box=1:boxcolor=black@0.5:boxborderw=10:line_spacing=2"

        val args = arrayOf(
            "-y",
            "-f", "concat", "-safe", "0", "-i", inputsFile.absolutePath,
            "-vf", filter,
            "-c:v", "libx264", "-preset", "ultrafast",
            "-c:a", "aac", "-b:a", "128k",
            "-s", "1080x1920",
            outputPath
        )

        val session = FFmpegKit.executeWithArguments(args)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            // Get the last 10 lines of the log to keep the error message readable
            val logs = session.allLogsAsString
            val lastLines = logs.split("\n").takeLast(10).joinToString("\n")
            throw Exception("FFmpeg failed for line $index.\nFFmpeg Log:\n$lastLines")
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
