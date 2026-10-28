package ch.broillet.veloce.hosting

import android.app.Application
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CarTextFormattingTest {
    @Test fun retainsPlainText() { assertEquals("Turn right", CarText.create("Turn right").text()) }

    @Test fun replacesDistanceAndDurationPlaceholdersWithoutShiftingLaterSpans() {
        val text = SpannableString("Home:   in  ")
        text.setSpan(DistanceSpan.create(Distance.create(250.0, Distance.UNIT_METERS)), 6, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(DurationSpan.create(600), 11, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals("Home: 250 m in 10 min", CarText.create(text).text())
    }

    @Test fun nullTextIsEmpty() { assertEquals("", (null as CarText?).text()) }
}
