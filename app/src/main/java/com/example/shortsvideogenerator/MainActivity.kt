package com.example.shortsvideogenerator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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

    private lateinit var textInput: EditText
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
                val destDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Videos")
                destDir.mkdirs()
                val destFile = File(destDir, "ShortsVideos.zip")
                try {
                    zipFile.copyTo(destFile, overwrite = true)
                    Toast.makeText(this, "Saved to ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
                    statusText.text = "ZIP successfully saved to Videos folder."
                } catch (e: Exception) {
                    Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO), 100)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 100)
        }
    }

    private fun startGeneration() {
        val lines = textInput.text.toString().split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            Toast.makeText(this, "Please enter at least one line of text", Toast.LENGTH_SHORT).show()
            return
        }

        generateButton.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.max = 100
        progressBar.progress = 0
        downloadZipButton.visibility = Button.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(cacheDir, "shorts_assets")
                cacheDir.mkdirs()

                updateStatus("Extracting bundled assets from APK (Parallel for max speed)...")
                extractAssets(cacheDir)

                val outputDir = File(cacheDir, "generated_videos")
                outputDir.mkdirs()

                for ((index, line) in lines.withIndex()) {
                    updateStatus("Generating video ${index + 1}/${lines.size}...")
                    generateVideoForLine(line, index + 1, cacheDir, outputDir)
                    progressBar.progress = ((index + 1) * 100) / lines.size
                }

                updateStatus("Zipping videos...")
                val zipFile = File(cacheDir, "ShortsVideos.zip")
                zipFiles(outputDir, zipFile)
                generatedZipFile = zipFile

                withContext(Dispatchers.Main) {
                    statusText.text = "Generation complete! Click Download to save."
                    downloadZipButton.visibility = Button.VISIBLE
                    generateButton.isEnabled = true
                    progressBar.visibility = ProgressBar.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                    generateButton.isEnabled = true
                    progressBar.visibility = ProgressBar.GONE
                }
            }
        }
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
            updateStatus("All assets already extracted. 100% Offline mode ready.")
            return@coroutineScope
        }

        updateStatus("Extracting ${pendingExtractions.size} assets in parallel...")
        
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
            val videoPath = File(cacheDir, "$i.mp4").absolutePath
            sb.append("file '$videoPath'\n")
            sb.append("inpoint 0\n")
            sb.append("outpoint 0.04\n")
        }
        inputsFile.writeText(sb.toString())

        val wrappedText = wrapText(text)
        val textFile = File(cacheDir, "text_$index.txt")
        textFile.writeText(wrappedText)

        val outputPath = File(outputDir, "video_$index.mp4").absolutePath
        val fontPath = File(cacheDir, "font.ttf").absolutePath

        val filter = "drawtext=fontfile='$fontPath':textfile='$textFile':fontcolor=white:fontsize=80:x=200:y=300:box=1:boxcolor=black@0.5:boxborderw=10:line_spacing=2"

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
            throw Exception("FFmpeg failed for line $index: ${session.allLogsAsString}")
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
