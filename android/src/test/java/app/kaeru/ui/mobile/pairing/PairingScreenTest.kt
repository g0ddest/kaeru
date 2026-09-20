package app.kaeru.ui.mobile.pairing

import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.ui.common.pairing.PairingStage
import app.kaeru.ui.common.pairing.PairingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingScreenTest {
    private val television = PairingRequest("192.168.1.7", 41_234, "nonce", "Гостиная")

    private fun state(stage: PairingStage, name: String = "Гостиная") =
        PairingUiState(television.copy(name = name), stage)

    @Test
    fun `the question names the television so there is something to check`() {
        assertEquals("Войти на телевизоре «Гостиная»?", heading(state(PairingStage.CONFIRM)))
    }

    @Test
    fun `a television that never got a name is still asked about`() {
        assertEquals("Войти на этом телевизоре?", heading(state(PairingStage.CONFIRM, name = "")))
        assertEquals("Войти на этом телевизоре?", heading(state(PairingStage.CONFIRM, name = "   ")))
    }

    @Test
    fun `each step says what is happening rather than repeating the question`() {
        assertEquals("Ждём ответа Shikimori", heading(state(PairingStage.AWAITING_CODE)))
        assertEquals("Передаём код телевизору", heading(state(PairingStage.SENDING)))
        assertEquals("Телевизор вошёл в аккаунт", heading(state(PairingStage.DONE)))
    }

    @Test
    fun `the sentence under the heading always says what happens next`() {
        PairingStage.entries.forEach { stage ->
            assertTrue(stage.name, body(state(stage)).isNotBlank())
        }
    }

    @Test
    fun `nothing on this screen uses the separator the design system forbids`() {
        PairingStage.entries.forEach { stage ->
            assertTrue(stage.name, '·' !in heading(state(stage)) && '·' !in body(state(stage)))
        }
    }
}
