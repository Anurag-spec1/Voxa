package com.anurag.voxa


import android.content.Context
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext  // Add this import

object SpeechRecognitionManager {

    private const val TAG = "SpeechRecognition"
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.IO)  // Add coroutine scope

    fun startListening(context: Context) {
        if (isListening) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Log.d(TAG, "Ready for speech")
                FloatingHUD.update(context, "Listening...", FloatingHUD.State.LISTENING)
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Visual feedback for sound level
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Speech recognition error: $error")
                isListening = false
                FloatingHUD.hide(context)
                JarvisWakeWordService.startService(context)
            }

            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""

                if (text.isNotEmpty()) {
                    Log.d(TAG, "Recognized: $text")
                    processCommand(context, text)
                }

                isListening = false
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {}

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        val intent = RecognizerIntent.getVoiceDetailsIntent(context).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en")
            putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayOf("en", "hi", "es", "fr"))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.startListening(intent)
        isListening = true
    }

    private fun processCommand(context: Context, command: String) {
        scope.launch {
            // Update HUD
            withContext(Dispatchers.Main) {
                FloatingHUD.update(context, "Processing...", FloatingHUD.State.THINKING)
            }

            // Try fast rule engine first
            val fastResult = FastRuleEngine.process(command)
            if (fastResult != null) {
                ActionExecutor.execute(fastResult)
                withContext(Dispatchers.Main) {
                    FloatingHUD.hide(context)
                }
                return@launch
            }

            // Use Gemini AI for complex commands
            val actions = GeminiPlanner.planActions(command)
            if (actions.isNotEmpty()) {
                ActionExecutor.execute(actions)
            }

            withContext(Dispatchers.Main) {
                FloatingHUD.hide(context)
            }
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        isListening = false
    }
}