package app.kite.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import app.kite.AppSettings
import app.kite.kt

@Composable
internal fun rememberDictation(onWords: (String) -> Unit, onUnavailable: () -> Unit): () -> Unit {
    val currentResult = rememberUpdatedState(onWords)
    val currentFailure = rememberUpdatedState(onUnavailable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val words = it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!words.isNullOrBlank()) currentResult.value(words.trim())
    }
    return {
        runCatching { launcher.launch(dictationIntent()) }
            .onFailure { currentFailure.value() }
    }
}

private fun dictationIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE,
            if (AppSettings.czechVoiceSearch) "cs-CZ" else AppSettings.language.locale.toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PROMPT, kt("Say an address or place", "Vyslovte adresu nebo název místa"))
