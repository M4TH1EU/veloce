package ch.broillet.veloce.hosting

import android.text.Spanned
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import java.util.Locale

internal fun CarText?.text(): String {
    val source = this?.toCharSequence() ?: return ""
    if (source !is Spanned) return source.toString()
    val result = StringBuilder(source)
    source.getSpans(0, source.length, Any::class.java)
        .sortedByDescending(source::getSpanStart).forEach { span ->
            val replacement = when (span) {
                is DistanceSpan -> span.distance.distance()
                is DurationSpan -> "${(span.durationSeconds + 59) / 60} min"
                else -> return@forEach
            }
            result.replace(source.getSpanStart(span), source.getSpanEnd(span), replacement)
        }
    return result.toString()
}

internal fun Distance?.distance(): String {
    if (this == null) return ""
    val unit = when (displayUnit) {
        Distance.UNIT_METERS -> "m"
        Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> "km"
        Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> "mi"
        Distance.UNIT_FEET -> "ft"
        Distance.UNIT_YARDS -> "yd"
        else -> return ""
    }
    return String.format(Locale.getDefault(), if (displayDistance % 1.0 == 0.0) "%.0f %s" else "%.1f %s", displayDistance, unit)
}
