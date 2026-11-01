package ch.broillet.veloce.settings

import android.content.Context
import android.text.format.DateFormat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class AppPalette { GRAPHITE, DYNAMIC, SAGE, OCEAN, EMBER }
enum class ClockFormat {
    SYSTEM, TWELVE_HOUR, TWENTY_FOUR_HOUR;

    fun is24Hour(context: Context): Boolean = when (this) {
        SYSTEM -> DateFormat.is24HourFormat(context)
        TWELVE_HOUR -> false
        TWENTY_FOUR_HOUR -> true
    }
}

data class AppSettings(
    val setupComplete: Boolean = false,
    val theme: ThemeMode = ThemeMode.DARK,
    val palette: AppPalette = AppPalette.GRAPHITE,
    val clockFormat: ClockFormat = ClockFormat.SYSTEM,
    val notificationBanners: Boolean = true,
    val artworkBackground: Boolean = true,
    val hiddenAppPackages: Set<String> = emptySet(),
) {
    fun isAppVisible(packageName: String): Boolean = packageName !in hiddenAppPackages
}

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private inline fun <reified T : Enum<T>> read(key: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == preferences.getString(key, null) } ?: fallback

    private val mutable = MutableStateFlow(AppSettings(
        setupComplete = preferences.getBoolean("setup_complete", false),
        theme = read("theme", ThemeMode.DARK),
        palette = read("palette", AppPalette.GRAPHITE),
        clockFormat = read("clock_format", ClockFormat.SYSTEM),
        notificationBanners = preferences.getBoolean("notification_banners", true),
        artworkBackground = preferences.getBoolean("artwork_background", true),
        hiddenAppPackages = preferences.getStringSet("hidden_apps", emptySet()).orEmpty().toSet(),
    ))
    val state = mutable.asStateFlow()

    @Synchronized fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(mutable.value)
        preferences.edit {
            putBoolean("setup_complete", next.setupComplete)
            putString("theme", next.theme.name)
            putString("palette", next.palette.name)
            putString("clock_format", next.clockFormat.name)
            putBoolean("notification_banners", next.notificationBanners)
            putBoolean("artwork_background", next.artworkBackground)
            putStringSet("hidden_apps", next.hiddenAppPackages)
        }
        mutable.value = next
    }

    fun setAppVisible(packageName: String, visible: Boolean) = update {
        it.copy(hiddenAppPackages = if (visible) it.hiddenAppPackages - packageName else it.hiddenAppPackages + packageName)
    }
}
