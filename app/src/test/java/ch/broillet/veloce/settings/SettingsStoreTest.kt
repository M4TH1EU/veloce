package ch.broillet.veloce.settings

import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.provider.Settings
import ch.broillet.veloce.apps.AppKind
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.apps.visibleInCar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun appearanceAndSetupSurviveAStoreRestart() {
        val store = SettingsStore(context)
        store.update { it.copy(setupComplete = true, palette = AppPalette.OCEAN, theme = ThemeMode.SYSTEM,
            clockFormat = ClockFormat.TWENTY_FOUR_HOUR, notificationBanners = false, artworkBackground = false) }
        assertEquals(store.state.value, SettingsStore(context).state.value)
    }

    @Test fun unknownStoredChoicesFallBackWithoutLosingOtherPreferences() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
            .putString("palette", "REMOVED_PALETTE").putString("clock_format", "UNKNOWN")
            .putBoolean("setup_complete", true).commit()
        val settings = SettingsStore(context).state.value
        assertEquals(AppPalette.GRAPHITE, settings.palette)
        assertEquals(ClockFormat.SYSTEM, settings.clockFormat)
        assertTrue(settings.setupComplete)
    }

    @Test fun explicitClockFormatsOverrideTheSystem() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        assertTrue(ClockFormat.SYSTEM.is24Hour(context))
        assertFalse(ClockFormat.TWELVE_HOUR.is24Hour(context))
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        assertFalse(ClockFormat.SYSTEM.is24Hour(context))
        assertTrue(ClockFormat.TWENTY_FOUR_HOUR.is24Hour(context))
    }

    @Test fun hidingAnAppPersistsAndHidesAllOfItsCarServices() {
        val navigation = CarApp(ComponentName("example.maps", "NavigationService"), "Maps", AppKind.MAP)
        val otherService = navigation.copy(component = ComponentName("example.maps", "MediaService"), kind = AppKind.AUDIO)
        val music = CarApp(ComponentName("example.music", "MediaService"), "Music", AppKind.AUDIO)
        val installed = listOf(navigation, otherService, music)
        val store = SettingsStore(context)
        store.setAppVisible("example.maps", false)
        store.update { it.copy(palette = AppPalette.SAGE) }

        val restored = SettingsStore(context).state.value
        assertEquals(setOf("example.maps"), restored.hiddenAppPackages)
        assertEquals(listOf(music), installed.visibleInCar(restored))
        assertEquals(3, installed.size)

        store.setAppVisible("example.maps", true)
        assertEquals(installed, installed.visibleInCar(SettingsStore(context).state.value))
    }

    @Test fun showingOneAppPreservesOtherHiddenAppsAndShowAllResetsThem() {
        val store = SettingsStore(context)
        store.setAppVisible("example.maps", false)
        store.setAppVisible("example.music", false)
        store.setAppVisible("example.maps", true)
        assertTrue(store.state.value.isAppVisible("example.maps"))
        assertFalse(store.state.value.isAppVisible("example.music"))
        store.update { it.copy(hiddenAppPackages = emptySet()) }
        assertTrue(SettingsStore(context).state.value.isAppVisible("example.music"))
    }
}
