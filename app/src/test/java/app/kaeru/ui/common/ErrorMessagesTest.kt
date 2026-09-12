package app.kaeru.ui.common

import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMessagesTest {
    @Test
    fun `network failures read as a connectivity problem`() {
        assertEquals("Нет соединения. Проверьте интернет", NetworkUnavailable(UnknownHostException("shikimori.one")).toUserMessage())
        assertEquals("Нет соединения. Проверьте интернет", NetworkUnavailable(SocketTimeoutException("timeout")).toUserMessage())
    }

    @Test
    fun `unauthorized and forbidden ask the user to sign in again`() {
        assertEquals("Сессия истекла, войдите снова", HttpError(401).toUserMessage())
        assertEquals("Сессия истекла, войдите снова", HttpError(403).toUserMessage())
    }

    @Test
    fun `rate limiting and server errors get their own copy`() {
        assertEquals("Слишком много запросов, попробуйте позже", HttpError(429).toUserMessage())
        assertEquals("Shikimori недоступен, попробуйте позже", HttpError(500).toUserMessage())
        assertEquals("Shikimori недоступен, попробуйте позже", HttpError(503).toUserMessage())
    }

    @Test
    fun `account session changes ask for a refresh`() {
        assertEquals("Сессия изменилась, обновите экран", AccountSessionChanged("Account session changed").toUserMessage())
    }

    @Test
    fun `a rejected oauth callback asks the user to sign in again`() {
        assertEquals("Не удалось подтвердить вход. Войдите заново", AuthCallbackRejected("state mismatch").toUserMessage())
    }

    @Test
    fun `unmapped failures never leak their own text`() {
        assertEquals("Что-то пошло не так. Повторите попытку", HttpError(404).toUserMessage())
        assertEquals("Что-то пошло не так. Повторите попытку", IllegalStateException("No anime 100 returned by Shikimori").toUserMessage())
        assertEquals("Что-то пошло не так. Повторите попытку", RuntimeException("java.lang.NullPointerException").toUserMessage())
    }

    @Test
    fun `result helper maps only failures`() {
        assertNull(Result.success(Unit).errorMessageOrNull())
        assertEquals("Нет соединения. Проверьте интернет", Result.failure<Unit>(NetworkUnavailable(UnknownHostException("x"))).errorMessageOrNull())
    }
}
