package ch.broillet.veloce.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AppKind { MAP, AUDIO, OTHER }

data class CarApp(
    val component: ComponentName,
    val label: String,
    val kind: AppKind,
)

class CarApps(context: Context) {
    private val packages = context.packageManager

    suspend fun discover(): List<CarApp> = withContext(Dispatchers.IO) {
        val templates = services("androidx.car.app.CarAppService")
        val maps = services("androidx.car.app.CarAppService", "androidx.car.app.category.NAVIGATION")
            .mapTo(mutableSetOf()) { it.serviceInfo.packageName to it.serviceInfo.name }
        val audio = services("android.media.browse.MediaBrowserService") +
            services("androidx.media3.session.MediaLibraryService")

        (templates.map { result ->
            val service = result.serviceInfo
            val kind = if (service.packageName to service.name in maps) AppKind.MAP else AppKind.OTHER
            CarApp(ComponentName(service.packageName, service.name), result.loadLabel(packages).toString(), kind)
        } + audio.map { result ->
            val service = result.serviceInfo
            CarApp(ComponentName(service.packageName, service.name), result.loadLabel(packages).toString(), AppKind.AUDIO)
        }).distinctBy { it.component }.sortedWith(compareBy({ it.kind }, { it.label.lowercase() }))
    }

    private fun services(action: String, category: String? = null) = packages.queryIntentServices(
        Intent(action).apply { category?.let(::addCategory) },
        PackageManager.ResolveInfoFlags.of(0),
    ).filter { it.serviceInfo.exported && it.serviceInfo.enabled && it.serviceInfo.applicationInfo.enabled }
}
