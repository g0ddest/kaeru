package app.kaeru

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun buildConfigHasPackage() {
        assertEquals("app.kaeru", BuildConfig.APPLICATION_ID)
    }
}
