package com.anurag.voxa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VisionEngine {

    private const val TAG = "VisionEngine"
    private lateinit var textRecognizer: TextRecognizer
    private val scope = CoroutineScope(Dispatchers.IO)

    fun initialize(context: Context) {
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    data class DetectedText(
        val text: String,
        val boundingBox: Rect,
        val confidence: Float
    )

    suspend fun analyzeScreen(): List<DetectedText> = suspendCancellableCoroutine { continuation ->
        try {
            // Capture screen
            val bitmap = ScreenshotManager.captureScreen()

            if (bitmap == null) {
                continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            val image = InputImage.fromBitmap(bitmap, 0)

            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val results = mutableListOf<DetectedText>()

                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            for (element in line.elements) {
                                element.boundingBox?.let { box ->
                                    results.add(
                                        DetectedText(
                                            text = element.text,
                                            boundingBox = box,
                                            confidence = element.confidence ?: 0.5f
                                        )
                                    )
                                }
                            }
                        }
                    }

                    continuation.resume(results)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed: ${e.message}")
                    continuation.resume(emptyList())
                }

        } catch (e: Exception) {
            Log.e(TAG, "Screen analysis failed: ${e.message}")
            continuation.resume(emptyList())
        }
    }

    fun performClick(targetText: String, x: Int = 0, y: Int = 0) {
        scope.launch {
            val texts = analyzeScreen()

            if (x > 0 && y > 0) {
                // Click at coordinates
                withContext(Dispatchers.Main) {
                    JarvisAccessibilityService.instance?.clickAtCoordinates(x, y)
                }
                return@launch
            }

            // Find best match
            val match = texts.firstOrNull {
                it.text.contains(targetText, ignoreCase = true)
            }

            if (match != null) {
                val centerX = match.boundingBox.centerX()
                val centerY = match.boundingBox.centerY()

                withContext(Dispatchers.Main) {
                    JarvisAccessibilityService.instance?.clickAtCoordinates(centerX, centerY)
                }
                Log.d(TAG, "Clicked via vision: $targetText at ($centerX, $centerY)")
            } else {
                Log.w(TAG, "Text not found: $targetText")
            }
        }
    }

    suspend fun findTextPosition(targetText: String): Pair<Int, Int>? {
        val texts = analyzeScreen()

        return texts.firstOrNull {
            it.text.contains(targetText, ignoreCase = true)
        }?.let { match ->
            Pair(match.boundingBox.centerX(), match.boundingBox.centerY())
        }
    }

    suspend fun readScreen(): String {
        val texts = analyzeScreen()
        return texts.joinToString("\n") { it.text }
    }
}