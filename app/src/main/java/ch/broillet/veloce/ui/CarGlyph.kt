package ch.broillet.veloce.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import ch.broillet.veloce.R

internal enum class CarSymbol(@param:DrawableRes val icon: Int) {
    Home(R.drawable.ic_rounded_home),
    Navigation(R.drawable.ic_rounded_navigation),
    Bell(R.drawable.ic_rounded_notifications),
    Car(R.drawable.ic_rounded_car),
    Back(R.drawable.ic_rounded_back),
    Play(R.drawable.ic_rounded_play),
    Pause(R.drawable.ic_rounded_pause),
    Previous(R.drawable.ic_rounded_previous),
    Next(R.drawable.ic_rounded_next),
    Rewind(R.drawable.ic_rounded_rewind),
    Forward(R.drawable.ic_rounded_forward),
    Close(R.drawable.ic_rounded_close),
    Search(R.drawable.ic_rounded_search),
    Warning(R.drawable.ic_rounded_warning),
    Action(R.drawable.ic_rounded_tune),
    Refresh(R.drawable.ic_rounded_refresh),
    ChevronRight(R.drawable.ic_rounded_chevron_right),
    Library(R.drawable.ic_rounded_library),
    Lyrics(R.drawable.ic_rounded_lyrics),
    Music(R.drawable.ic_rounded_music),
    Veloce(R.drawable.ic_veloce_mark),
}

@Composable
internal fun CarGlyph(symbol: CarSymbol, modifier: Modifier, tint: Color) {
    Icon(painterResource(symbol.icon), contentDescription = null, modifier = modifier, tint = tint)
}
