package ch.broillet.veloce.ui

import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

internal data class CarKeyboardLayout(val rows: List<String>, val locale: Locale)

private val letterLayouts = mapOf(
    "qwerty" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
    "qwertz" to listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
    "azerty" to listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
    "dvorak" to listOf("',.pyfgcrl", "aoeuidhtns", ";qjkxbmwvz"),
    "colemak" to listOf("qwfpgjluy", "arstdhneio", "zxcvbkm"),
    "workman" to listOf("qdrwbjfup", "ashtgyneoi", "zxmcvkl"),
    "turkish_f" to listOf("fgğıodrnhpq", "uieaütkmlyş", "jövcçzsb"),
    "russian" to listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"),
    "ukrainian" to listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбюґ"),
    "greek" to listOf("ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ"),
    "arabic" to listOf("ضصثقفغعهخحج", "شسيبلاتنمكط", "ئءؤرلىةوزظ"),
    "hebrew" to listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"),
)

@Suppress("DEPRECATION")
private fun keyboardLayout(subtype: InputMethodSubtype?, fallback: Locale): CarKeyboardLayout {
    val languageTag = subtype?.languageTag?.ifBlank { subtype.locale }.orEmpty().replace('_', '-')
    val locale = languageTag.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag)?.takeIf { it.language.isNotBlank() } ?: fallback
    val hints = listOfNotNull(
        subtype?.getExtraValueOf("KeyboardLayoutSet"),
        if (Build.VERSION.SDK_INT >= 36) subtype?.layoutLabelNonLocalized?.toString() else null,
        subtype?.physicalKeyboardHintLayoutType,
    )
    val explicit = hints.firstNotNullOfOrNull { hint ->
        val name = hint.lowercase(Locale.ROOT)
        letterLayouts.keys.firstOrNull { name == it || name.startsWith("${it}_") || name.startsWith("$it ") }
    }
    val name = explicit ?: when {
        locale.country == "CH" && locale.language in setOf("fr", "it", "rm") || locale.language in setOf("de", "cs", "sk", "sl", "hr", "bs", "hu") -> "qwertz"
        locale.language == "fr" && locale.country != "CA" -> "azerty"
        locale.language == "ru" -> "russian"
        locale.language == "uk" -> "ukrainian"
        locale.language == "el" -> "greek"
        locale.language == "ar" -> "arabic"
        locale.language in setOf("he", "iw") -> "hebrew"
        else -> "qwerty"
    }
    val rows = when {
        name == "qwerty" && locale.language == "tr" -> listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç")
        name == "qwerty" && locale.language == "es" -> listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        else -> letterLayouts.getValue(name)
    }
    return CarKeyboardLayout(rows, locale)
}

@Composable
internal fun rememberPhoneKeyboardLayout(): CarKeyboardLayout {
    val context = LocalContext.current.applicationContext
    val locales = LocalConfiguration.current.locales
    return remember(context, locales) {
        val subtype = runCatching { context.getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype }.getOrNull()
        keyboardLayout(subtype, locales[0])
    }
}
