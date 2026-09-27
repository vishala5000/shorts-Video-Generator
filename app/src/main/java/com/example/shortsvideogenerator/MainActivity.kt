package com.example.shortsvideogenerator

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

                val mediaItems = (1..5).map { i ->
                    MediaItem.fromUri(Uri.fromFile(File(cacheDir, "$i.mp4")))
                }

                for ((index, line) in lines.withIndex()) {
                    updateStatus("Generating video ${index + 1}/${lines.size}...")
                    
                    val textBitmap = createTextBitmap(wrapText(line), cacheDir)
                    val outputPath = File(outputDir, "video_$index.mp4").absolutePath
                    
                    processVideoWithText(this@MainActivity, mediaItems, textBitmap, outputPath)
                    
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

    private suspend fun processVideoWithText(
        context: Context,
        mediaItems: List<MediaItem>,
        textBitmap: Bitmap,
        outputPath: String
    ) = suspendCancellableCoroutine { continuation ->
        val overlayEffect = BitmapOverlay.createStaticBitmapOverlay(textBitmap)
        val effects = Effects(listOf(overlayEffect), listOf())
        
        val sequence = EditedMediaItemSequence(mediaItems.map { EditedMediaItem.Builder(it).build() })
        val editedMediaItem = EditedMediaItem.Builder(sequence).setEffects(effects).build()
        
        var transformerStarted = false
        var transformerCompleted = false
        
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformerCompleted = true
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                
                override fun onError(composition: Composition, exportResult: ExportResult, exception: Exception) {
                    transformerCompleted = true
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
            })
            .build()
        
        try {
            transformer.start(editedMediaItem, outputPath)
            transformerStarted = true
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
            return@suspendCancellableCoroutine
        }
        
        continuation.invokeOnCancellation {
            if (transformerStarted && !transformerCompleted) {
                try {
                    transformer.cancel()
                } catch (e: Exception) {
                    // Ignore cancellation errors
                }
            }
        }
    }

    private fun createTextBitmap(wrappedText: String, cacheDir: File): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val typeface = try {
            Typeface.createFromFile(File(cacheDir, "font.ttf"))
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 80f
            textAlign = Paint.Align.LEFT
            this.typeface = typeface
        }
        
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80000000")
            style = Paint.Style.FILL
        }
        
        val lines = wrappedText.split("\n")
        val fontMetrics = paint.fontMetrics
        val lineHeight = fontMetrics.descent - fontMetrics.ascent + 2f
        
        var currentY = 300f
        
        lines.forEach { line ->
            val textWidth = paint.measureText(line)
            val boxLeft = 200f - 10f
            val boxTop = currentY + fontMetrics.ascent - 10f
            val boxRight = 200f + textWidth + 10f
            val boxBottom = currentY + fontMetrics.descent + 10f
            
            canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, bgPaint)
            canvas.drawText(line, 200f, currentY, paint)
            
            currentY += lineHeight
        }
        
        return bitmap
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
