package app.kaeru.ui.common.together

import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.AccountRepository
import app.kaeru.domain.together.LostReason
import app.kaeru.domain.together.NoticeKind
import app.kaeru.domain.together.PeerHello
import app.kaeru.domain.together.ReactionKind
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.SessionState
import app.kaeru.domain.together.TogetherEvent
import app.kaeru.domain.together.TogetherSessionApi
import app.kaeru.player.FakeLibraryRepository
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * What the two screens of a shared viewing are, as values.
 *
 * Time is the subject of half of these: the stack, the reactions, the notices and every wait are
 * all things that end by themselves, and the only honest way to test «ends by itself» is to move
 * a virtual clock and read the state again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TogetherViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val link = RoomLink("cm9vbTEyMzQ", ByteArray(16) { it.toByte() })

    private class FakeSession : TogetherSessionApi {
        val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
        val bus = MutableSharedFlow<TogetherEvent>(extraBufferCapacity = 16)
        val chats = mutableListOf<String>()
        val reactions = mutableListOf<ReactionKind>()
        val voices = mutableListOf<Pair<ByteArray, Int>>()
        val joins = mutableListOf<Pair<RoomLink, String>>()
        var hosted: String? = null
        var left = 0
        var alone = 0
        var hostLink = RoomLink("aG9zdDEyMzQ", ByteArray(16))

        override val state: StateFlow<SessionState> get() = sessionState
        override val events: SharedFlow<TogetherEvent> get() = bus
        override suspend fun host(name: String): RoomLink {
            hosted = name
            sessionState.value = SessionState.Hosting(hostLink, waiting = true)
            return hostLink
        }
        override suspend fun join(link: RoomLink, name: String) {
            joins += link to name
            sessionState.value = SessionState.Joining(link, hello = null)
        }
        override suspend fun leave() {
            left++
            sessionState.value = SessionState.Idle
        }
        override suspend fun sendChat(text: String) { chats += text }
        override suspend fun sendReaction(kind: ReactionKind) { reactions += kind }
        override suspend fun sendVoice(bytes: ByteArray, durationMs: Int) { voices += bytes to durationMs }
        override fun watchAlone() { alone++ }
    }

    private class FakeAccounts(nickname: String?) : AccountRepository {
        override val account: Flow<Account?> =
            MutableStateFlow(nickname?.let { Account(id = 1, nickname = it, avatarUrl = null) })
        override suspend fun refresh() = Result.success(Unit)
    }

    private val session = FakeSession()
    private val library = FakeLibraryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-09-16T19:42:00Z"), ZoneOffset.UTC)

    private fun viewModel(nickname: String? = "vitaliy") =
        TogetherViewModel(session, FakeAccounts(nickname), library, clock)

    private fun anime(id: Int, title: String) = Anime(
        id = id,
        nameRu = title,
        nameRomaji = title,
        posterUrl = "https://poster/$id.jpg",
        screenshotUrls = emptyList(),
        status = AnimeStatus.ONGOING,
        episodes = 12,
        episodesAired = 8,
        nextEpisodeAt = null,
        score = null,
        year = 2026,
        studio = null,
        description = null,
    )

    // --- hosting -------------------------------------------------------------------------------

    @Test
    fun `sharing opens a room and hands the screen a message to send`() = runTest {
        val vm = viewModel()
        vm.share(title = "Проводы в последний путь", episode = 7)
        runCurrent()
        assertEquals("vitaliy", session.hosted)
        val request = vm.uiState.value.share
        assertNotNull(request)
        assertEquals(session.hostLink.toHttps(), request!!.link)
        assertTrue(request.text, request.text.contains("7 серию"))
        assertTrue(request.text, request.text.endsWith(request.link))
        vm.shareShown()
        assertNull(vm.uiState.value.share)
    }

    @Test
    fun `a viewer whose name never loaded is still somebody to the other phone`() = runTest {
        val vm = viewModel(nickname = null)
        vm.share(title = "Тайтл", episode = 1)
        runCurrent()
        assertEquals("Друг", session.hosted)
    }

    @Test
    fun `waiting for a friend is one line with a way out of it`() = runTest {
        val vm = viewModel()
        session.sessionState.value = SessionState.Hosting(link, waiting = true)
        runCurrent()
        val wait = vm.uiState.value.wait
        assertEquals("Ждём друга…", wait?.text)
        assertEquals(WaitExit.KEEP_WATCHING, wait?.exit)
        assertEquals(TogetherPhase.HOSTING, vm.uiState.value.phase)
    }

    @Test
    fun `that line goes away by itself after thirty seconds, and the room stays open`() = runTest {
        val vm = viewModel()
        session.sessionState.value = SessionState.Hosting(link, waiting = true)
        runCurrent()
        advanceTimeBy(29_000)
        assertNotNull(vm.uiState.value.wait)
        advanceTimeBy(2_000)
        assertNull(vm.uiState.value.wait)
        assertEquals(TogetherPhase.HOSTING, vm.uiState.value.phase)
        assertEquals(0, session.left)
    }

    @Test
    fun `pressing the way out of a wait ends the wait and nothing else`() = runTest {
        val vm = viewModel()
        session.sessionState.value = SessionState.Hosting(link, waiting = true)
        runCurrent()
        vm.leaveWait()
        runCurrent()
        assertNull(vm.uiState.value.wait)
        assertEquals(1, session.alone)
        assertEquals(0, session.left)
    }

    // --- living session ------------------------------------------------------------------------

    @Test
    fun `two phones on one episode is a live session with a name on it`() = runTest {
        val vm = viewModel()
        session.sessionState.value = SessionState.Live("Вася", offsetMs = 40, driftMs = 120)
        runCurrent()
        assertEquals(TogetherPhase.LIVE, vm.uiState.value.phase)
        assertEquals("Вася", vm.uiState.value.peerName)
        assertNull(vm.uiState.value.wait)
    }

    @Test
    fun `an incoming message lives seven seconds in the corner and forever in the history`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.ChatItem(1, fromPeer = true, text = "это тот самый кадр", at = 0))
        runCurrent()
        assertEquals(1, vm.uiState.value.stack.size)
        assertEquals("Вася", vm.uiState.value.stack.single().author)
        advanceTimeBy(7_100)
        assertTrue(vm.uiState.value.stack.isEmpty())
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals("это тот самый кадр", vm.uiState.value.history.single().text)
    }

    @Test
    fun `the corner holds three messages, the history holds all of them`() = runTest {
        val vm = viewModel()
        live()
        repeat(4) { n ->
            session.bus.emit(TogetherEvent.ChatItem(n.toLong(), fromPeer = true, text = "line $n", at = 0))
            advanceTimeBy(100)
        }
        runCurrent()
        assertEquals(listOf("line 1", "line 2", "line 3"), vm.uiState.value.stack.map { it.text })
        assertEquals(4, vm.uiState.value.history.size)
    }

    @Test
    fun `with a screen reader on, nothing in the corner disappears on a timer`() = runTest {
        val vm = viewModel()
        live()
        vm.setAutoHide(false)
        session.bus.emit(TogetherEvent.ChatItem(1, fromPeer = true, text = "ага", at = 0))
        advanceTimeBy(20_000)
        assertEquals(1, vm.uiState.value.stack.size)
        vm.clearStack()
        assertTrue(vm.uiState.value.stack.isEmpty())
    }

    @Test
    fun `a message this viewer sends appears here as well as there`() = runTest {
        val vm = viewModel()
        live()
        vm.sendChat("  ага  ")
        runCurrent()
        assertEquals(listOf("ага"), session.chats)
        assertEquals("Вы", vm.uiState.value.stack.single().author)
        assertTrue(vm.uiState.value.stack.single().mine)
    }

    @Test
    fun `an empty message is not a message`() = runTest {
        val vm = viewModel()
        live()
        vm.sendChat("   ")
        runCurrent()
        assertTrue(session.chats.isEmpty())
        assertTrue(vm.uiState.value.stack.isEmpty())
    }

    @Test
    fun `a message longer than the protocol allows is cut, not refused`() = runTest {
        val vm = viewModel()
        live()
        vm.sendChat("я".repeat(260))
        runCurrent()
        assertEquals(200, session.chats.single().length)
    }

    @Test
    fun `the session never echoes this viewer's own words back into the corner twice`() = runTest {
        val vm = viewModel()
        live()
        vm.sendChat("ага")
        runCurrent()
        session.bus.emit(TogetherEvent.ChatItem(99, fromPeer = false, text = "ага", at = 0))
        runCurrent()
        assertEquals(1, vm.uiState.value.stack.size)
    }

    // --- reactions -----------------------------------------------------------------------------

    @Test
    fun `three reactions fly at once and a fourth is simply not added`() = runTest {
        val vm = viewModel()
        live()
        ReactionKind.entries.take(4).forEach { kind ->
            session.bus.emit(TogetherEvent.ReactionEvent(kind.ordinal.toLong(), fromPeer = true, kind = kind))
        }
        runCurrent()
        assertEquals(3, vm.uiState.value.reactions.size)
    }

    @Test
    fun `a reaction is gone in a second and a bit`() = runTest {
        val vm = viewModel()
        live()
        vm.sendReaction(ReactionKind.FIRE)
        runCurrent()
        assertEquals(listOf(ReactionKind.FIRE), session.reactions)
        assertEquals(1, vm.uiState.value.reactions.size)
        assertTrue(vm.uiState.value.reactions.single().mine)
        advanceTimeBy(1_300)
        assertTrue(vm.uiState.value.reactions.isEmpty())
    }

    // --- notices -------------------------------------------------------------------------------

    @Test
    fun `somebody else's pause is one line at the top for three seconds`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.Notice(NoticeKind.PAUSED, "Вася"))
        runCurrent()
        assertEquals("Вася поставил(а) на паузу", vm.uiState.value.notice?.text)
        advanceTimeBy(3_100)
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun `a second notice replaces the first rather than queueing behind it`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.Notice(NoticeKind.PAUSED, "Вася"))
        advanceTimeBy(500)
        session.bus.emit(TogetherEvent.Notice(NoticeKind.PLAYED, "Вася"))
        runCurrent()
        assertEquals("Вася включил(а)", vm.uiState.value.notice?.text)
        // The first notice's own timer must not take the second one down with it.
        advanceTimeBy(2_700)
        assertEquals("Вася включил(а)", vm.uiState.value.notice?.text)
    }

    // --- voice ---------------------------------------------------------------------------------

    @Test
    fun `an arriving clip plays once by itself and stays to be heard again`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.VoiceClip(7, fromPeer = true, bytes = byteArrayOf(1, 2, 3), durationMs = 7_400))
        runCurrent()
        assertEquals(7L, vm.uiState.value.playing?.id)
        vm.clipPlayed()
        assertNull(vm.uiState.value.playing)
        val item = vm.uiState.value.stack.single()
        assertEquals(7_400, item.clip?.durationMs)
        vm.replay(item.id)
        assertEquals(item.id, vm.uiState.value.playing?.id)
    }

    @Test
    fun `a clip this viewer records shows up in their own corner`() = runTest {
        val vm = viewModel()
        live()
        vm.sendVoice(byteArrayOf(9), durationMs = 2_000)
        runCurrent()
        assertEquals(1, session.voices.size)
        val item = vm.uiState.value.stack.single()
        assertTrue(item.mine)
        assertEquals(2_000, item.clip?.durationMs)
        // Nothing plays a clip back at the person who just spoke it.
        assertNull(vm.uiState.value.playing)
    }

    @Test
    fun `a refused microphone is said once and breaks nothing`() = runTest {
        val vm = viewModel()
        live()
        vm.microphoneDenied()
        assertEquals("Нужен доступ к микрофону", vm.uiState.value.message)
        vm.messageShown()
        assertNull(vm.uiState.value.message)
        assertEquals(TogetherPhase.LIVE, vm.uiState.value.phase)
    }

    // --- losing it -----------------------------------------------------------------------------

    @Test
    fun `a dropped connection says so and offers the only thing left to do`() = runTest {
        val vm = viewModel()
        live()
        session.sessionState.value = SessionState.Lost(LostReason.CONNECTION)
        runCurrent()
        assertEquals(TogetherPhase.LOST, vm.uiState.value.phase)
        assertEquals("Связь с другом потеряна", vm.uiState.value.wait?.text)
        assertEquals(WaitExit.WATCH_ALONE, vm.uiState.value.wait?.exit)
    }

    @Test
    fun `a full room and an unconfigured build each say what actually happened`() = runTest {
        val vm = viewModel()
        session.sessionState.value = SessionState.Lost(LostReason.ROOM_FULL)
        runCurrent()
        assertEquals("В этой сессии уже двое", vm.uiState.value.wait?.text)
        session.sessionState.value = SessionState.Lost(LostReason.NOT_CONFIGURED)
        runCurrent()
        assertEquals("Сервер совместного просмотра не настроен", vm.uiState.value.wait?.text)
    }

    @Test
    fun `a session somebody ended is over, not broken`() = runTest {
        val vm = viewModel()
        live()
        session.sessionState.value = SessionState.Ended
        runCurrent()
        assertEquals(TogetherPhase.ENDED, vm.uiState.value.phase)
        assertEquals("Сессия закончилась", vm.uiState.value.wait?.text)
    }

    @Test
    fun `leaving a shared viewing leaves it`() = runTest {
        val vm = viewModel()
        live()
        vm.leave()
        runCurrent()
        assertEquals(1, session.left)
        assertEquals(TogetherPhase.IDLE, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    // --- joining -------------------------------------------------------------------------------

    @Test
    fun `opening a link knocks on the room and waits with nothing to show yet`() = runTest {
        val vm = viewModel()
        vm.open(link.toHttps())
        runCurrent()
        assertEquals(link, session.joins.single().first)
        assertEquals("vitaliy", session.joins.single().second)
        val join = vm.uiState.value.join
        assertTrue(join!!.loading)
        assertNull(join.line)
        assertNull(join.error)
    }

    @Test
    fun `a link that is not one of ours is refused on the spot`() = runTest {
        val vm = viewModel()
        vm.open("https://example.com/w/nope")
        runCurrent()
        assertTrue(session.joins.isEmpty())
        assertEquals("Ссылка не подошла", vm.uiState.value.join?.error)
    }

    @Test
    fun `when the other phone answers, the screen says what is being watched and from where`() = runTest {
        val vm = viewModel()
        library.put(
            LibraryEntry(
                anime = anime(42, "Проводы в последний путь"),
                rate = UserRate(0, 42, ListStatus.WATCHING, 6, Instant.EPOCH),
                watch = null,
            ),
        )
        vm.open(link.toHttps())
        runCurrent()
        session.sessionState.value = SessionState.Joining(
            link,
            PeerHello("Вася", animeId = 42, episode = 7, translationId = 3, positionMs = 724_000, playing = true),
        )
        runCurrent()
        val join = vm.uiState.value.join!!
        assertFalse(join.loading)
        assertEquals("Вася смотрит «Проводы в последний путь», 7 серия, 12:04", join.line)
        assertEquals("https://poster/42.jpg", join.posterUrl)
        assertEquals(42, join.animeId)
        assertEquals(7, join.episode)
    }

    @Test
    fun `nobody answering for thirty seconds is a failure with a way forward`() = runTest {
        val vm = viewModel()
        vm.open(link.toHttps())
        runCurrent()
        advanceTimeBy(29_000)
        assertNull(vm.uiState.value.join?.error)
        advanceTimeBy(2_000)
        assertEquals("Не удалось подключиться", vm.uiState.value.join?.error)
        assertFalse(vm.uiState.value.join!!.loading)
    }

    @Test
    fun `after a failed attempt, joining knocks on the same room again`() = runTest {
        val vm = viewModel()
        vm.open(link.toHttps())
        runCurrent()
        advanceTimeBy(31_000)
        session.sessionState.value = SessionState.Idle
        runCurrent()
        vm.join()
        runCurrent()
        assertEquals(2, session.joins.size)
        assertEquals(link, session.joins.last().first)
        assertNull(vm.uiState.value.join?.error)
    }

    @Test
    fun `joining a room already being joined does not knock twice`() = runTest {
        val vm = viewModel()
        vm.open(link.toHttps())
        runCurrent()
        vm.join()
        runCurrent()
        assertEquals(1, session.joins.size)
    }

    @Test
    fun `opening the episode closes the screen that asked, and keeps the session`() = runTest {
        val vm = viewModel()
        vm.open(link.toHttps())
        runCurrent()
        vm.joinScreenDone()
        assertNull(vm.uiState.value.join)
        assertEquals(0, session.left)
    }

    @Test
    fun `saying no to an invitation closes the room behind it`() = runTest {
        val vm = viewModel()
        vm.open(link.toHttps())
        runCurrent()
        vm.dismissJoin()
        runCurrent()
        assertEquals(1, session.left)
        assertNull(vm.uiState.value.join)
    }

    /**
     * A session already running, with the view model's own collectors attached to it.
     *
     * The advance matters: events are a hot bus with no replay, and anything emitted before the
     * view model has subscribed is simply not there to be read back.
     */
    private fun TestScope.live() {
        session.sessionState.value = SessionState.Live("Вася", offsetMs = 0, driftMs = 0)
        runCurrent()
    }
}
