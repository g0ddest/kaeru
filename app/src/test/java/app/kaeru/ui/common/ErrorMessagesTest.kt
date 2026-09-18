package app.kaeru.ui.common

import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.error.CastLoadFailed
import app.kaeru.domain.error.DownloadLimitReached
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.EpisodeUnavailableReason
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.SignInUnavailable
import app.kaeru.domain.error.SourceFormatChanged
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
import app.kaeru.domain.error.StorageFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMessagesTest {
    @Test
    fun `network failures read as a connectivity problem`() {
        assertEquals("Нет соединения. Проверьте интернет", NetworkUnavailable(UnknownHostException("shikimori.io")).toUserMessage())
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
    fun `a build that cannot reach its token proxy asks the user to come back later`() {
        assertEquals("Вход временно недоступен, попробуйте позже", SignInUnavailable().toUserMessage())
    }

    @Test
    fun `a rejected oauth callback asks the user to sign in again`() {
        assertEquals("Не удалось подтвердить вход. Войдите заново", AuthCallbackRejected("state mismatch").toUserMessage())
    }

    @Test
    fun `a source without a usable key names kodik and the key`() {
        assertEquals(
            "Kodik недоступен: не удалось получить ключ",
            SourceUnavailable(SourceUnavailableReason.NO_KEY, IllegalStateException("no token")).toUserMessage(),
        )
        assertEquals(
            "Kodik недоступен: не удалось получить ключ",
            SourceUnavailable(SourceUnavailableReason.NO_KEY).toUserMessage(),
        )
    }

    @Test
    fun `an episode that is neither downloaded nor reachable points at downloading it`() {
        // Not the generic connectivity line: a viewer in a tunnel cannot fix their connection,
        // but they can download the next episode before the next tunnel.
        assertEquals(
            "Нет сети. Скачайте серию заранее",
            SourceUnavailable(SourceUnavailableReason.OFFLINE).toUserMessage(),
        )
    }

    @Test
    fun `a refused download names the limit rather than the failure`() {
        assertEquals(
            "Лимит места исчерпан. Удалите загрузки или увеличьте лимит в настройках",
            DownloadLimitReached(limitBytes = 5L * 1024 * 1024 * 1024, usedBytes = 5L * 1024 * 1024 * 1024).toUserMessage(),
        )
    }

    @Test
    fun `a source that turned us away asks to retry instead of blaming the key`() {
        assertEquals(
            "Kodik временно недоступен, попробуйте позже",
            SourceUnavailable(SourceUnavailableReason.REJECTED).toUserMessage(),
        )
        assertEquals(
            "Kodik временно недоступен, попробуйте позже",
            SourceUnavailable(SourceUnavailableReason.REJECTED, IllegalStateException("503")).toUserMessage(),
        )
    }

    @Test
    fun `a missing episode reads as one kodik does not have yet`() {
        val notOnSource = EpisodeUnavailableReason.TITLE_NOT_ON_SOURCE
        assertEquals("Серия ещё не появилась в Kodik", EpisodeNotAvailable(52991, 28, notOnSource).toUserMessage())
        assertEquals("Серия ещё не появилась в Kodik", EpisodeNotAvailable(52991, null, notOnSource).toUserMessage())
    }

    @Test
    fun `an episode one dub lacks says so, since another may have it`() {
        val inTrack = EpisodeUnavailableReason.NOT_IN_TRANSLATION
        assertEquals("Серии 28 ещё нет в этой озвучке", EpisodeNotAvailable(52991, 28, inTrack).toUserMessage())
        assertEquals("Этой серии ещё нет в выбранной озвучке", EpisodeNotAvailable(52991, null, inTrack).toUserMessage())
    }

    @Test
    fun `an episode no dub has says exactly that, with the number`() {
        // «Выберите озвучку» would be a dead end here: every dub was asked, and none has it.
        val nowhere = EpisodeUnavailableReason.NOT_IN_ANY_TRANSLATION
        assertEquals("Серия 28 пока не вышла ни в одной озвучке", EpisodeNotAvailable(52991, 28, nowhere).toUserMessage())
        assertEquals("Серия пока не вышла ни в одной озвучке", EpisodeNotAvailable(52991, null, nowhere).toUserMessage())
    }

    @Test
    fun `a changed source format tells the user to wait for an app update`() {
        assertEquals(
            "Источник обновился, ждите обновления приложения",
            SourceFormatChanged("translations").toUserMessage(),
        )
    }

    @Test
    fun `a local write that failed names the progress that was not saved`() {
        assertEquals(
            "Не удалось сохранить прогресс просмотра",
            StorageFailure(IllegalStateException("disk I/O error")).toUserMessage(),
        )
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

    @Test
    fun `a receiver that will not load the stream is not a Kodik outage`() {
        assertEquals("Chromecast не смог загрузить видео", CastLoadFailed().toUserMessage())
        assertEquals(
            "Chromecast не смог загрузить видео",
            CastLoadFailed(IllegalStateException("receiver timed out")).toUserMessage(),
        )
        // The distinction is the point: one is the television, the other is the source.
        assertEquals("Kodik временно недоступен, попробуйте позже", SourceUnavailable(SourceUnavailableReason.REJECTED).toUserMessage())
    }
}
