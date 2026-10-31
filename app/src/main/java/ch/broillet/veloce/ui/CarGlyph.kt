package ch.broillet.veloce.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

internal enum class CarSymbol(val data: String, val filled: Boolean = false) {
    Home("M3,10 L12,3 L21,10 L21,20 Q21,21 20,21 L15,21 L15,14 L9,14 L9,21 L4,21 Q3,21 3,20 Z"),
    Navigation("M21,3 L14,21 L10,14 L3,10 Z M10,14 L21,3"),
    Bell("M5,9 Q5,2 12,2 Q19,2 19,9 L19,15 L21,18 L3,18 L5,15 Z M9,21 Q12,23 15,21"),
    Car("M4,10 L6,4 L18,4 L20,10 M3,11 Q3,9 5,9 L19,9 Q21,9 21,11 L21,18 L3,18 Z M5,18 L5,21 M19,18 L19,21 M6,13 L8,13 M16,13 L18,13"),
    Back("M15,5 L8,12 L15,19"),
    Play("M8,4 L21,12 L8,20 Z", true),
    Pause("M6,4 L10,4 L10,20 L6,20 Z M14,4 L18,4 L18,20 L14,20 Z", true),
    Previous("M5,5 L5,19 M19,5 L9,12 L19,19 Z"),
    Next("M19,5 L19,19 M5,5 L15,12 L5,19 Z"),
    Rewind("M12,6 L4,12 L12,18 Z M21,6 L13,12 L21,18 Z"),
    Forward("M3,6 L11,12 L3,18 Z M12,6 L20,12 L12,18 Z"),
    Close("M5,5 L19,19 M19,5 L5,19"),
    Search("M15.5,15.5 L22,22 M18,10 A8,8 0,1 1,2,10 A8,8 0,1 1,18,10"),
    Warning("M12,3 L22,21 L2,21 Z M12,9 L12,14 M12,17 L12,18"),
    Action("M4,7 L20,7 M4,17 L20,17 M8,4 L8,10 M16,14 L16,20"),
    Refresh("M20,8 C18.4,4.3 14.4,2.2 10.5,3 C6.6,3.8 3.5,7 3,11 C2.4,16.1 6.6,21 12,21 C15.9,21 19.2,18.6 20.5,15 M20,3 L20,8 L15,8"),
    ChevronRight("M9,5 L16,12 L9,19"),
    Library("M3,4 L7,4 L7,21 L3,21 Z M10,4 L14,4 L14,21 L10,21 Z M17,4 L20,3 L23,20 L20,21 Z"),
    Lyrics("M4,3 L20,3 L20,18 L10,18 L5,22 L5,18 L4,18 Z M8,7 L16,7 M8,11 L16,11 M8,15 L12,15"),
    Music("M9,18 L9,5 L20,3 L20,16 M9,9 L20,7 M9,18 C9,22 2,22 2,19 C2,16 9,15 9,18 M20,16 C20,20 13,20 13,17 C13,14 20,13 20,16"),
    Veloce("M4,5 L11,20 L20,5 M11,20 L15,5"),
}

private val paths = CarSymbol.entries.associateWith { PathParser().parsePathString(it.data).toPath() }

@Composable
internal fun CarGlyph(symbol: CarSymbol, modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(paths.getValue(symbol), tint, style = if (symbol.filled) androidx.compose.ui.graphics.drawscope.Fill
                else Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
