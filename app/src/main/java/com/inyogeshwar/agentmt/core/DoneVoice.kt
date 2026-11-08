package com.inyogeshwar.agentmt.core

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speaks a short line when a task finishes, so the user knows without looking (phone locked or app in background). */
class DoneVoice(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(text: String = "Your task is completed.") {
        val t = tts
        if (t != null && ready) { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agentmt-done"); return }
        pending = text
        if (t == null) tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { tts?.language = Locale.getDefault() }
                pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "agentmt-done") }
            }
            pending = null
        }
    }
}
