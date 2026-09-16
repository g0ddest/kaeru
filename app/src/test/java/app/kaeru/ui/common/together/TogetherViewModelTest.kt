package app.kaeru.ui.common.together

import app.kaeru.domain.together.PendingWatchLink
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
            // As the engine does: `Ended` is where it settles, and leaving what has already been
            // left changes nothing — which is the whole of what the screen has to cope with.
            val settled = sessionState.value
            if (settled is SessionState.Ended || settled is SessionState.Idle) return
            left++
            sessionState.value = SessionState.Ended
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

    private val parked = PendingWatchLink()

    private fun viewModel(nickname: String? = "vitaliy") =
        TogetherViewModel(session, FakeAccounts(nickname), library, parked, clock)

    private fun hello(name: String, animeId: Int = 42, episode: Int = 3) = PeerHello(
        name = name,
        animeId = animeId,
        episode = episode,
        translationId = 1,
        positionMs = 10_000,
        playing = true,
    )

    private fun entry(id: Int, title: String) = LibraryEntry(
        anime = anime(id, title),
        rate = UserRate(0, id, ListStatus.WATCHING, 1, Instant.EPOCH),
        watch = null,
    )

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
    fun `the receipt for a session that ended takes itself off the screen`() = runTest {
        val vm = viewModel()
        live()
        session.sessionState.value = SessionState.Ended
        runCurrent()
        assertEquals("Сессия закончилась", vm.uiState.value.wait?.text)

        advanceTimeBy(3_001)
        runCurrent()

        assertNull(vm.uiState.value.wait)
    }

    @Test
    fun `pressing the exit on an ended session clears it, however often it is pressed`() = runTest {
        val vm = viewModel()
        live()
        vm.leave()
        runCurrent()
        assertEquals("Сессия закончилась", vm.uiState.value.wait?.text)

        vm.leaveWait()
        runCurrent()
        assertNull(vm.uiState.value.wait)

        // And the engine, already settled, says nothing new — so nothing puts it back.
        vm.leaveWait()
        runCurrent()
        assertNull(vm.uiState.value.wait)
    }

    @Test
    fun `a player opened after somebody else's session ended draws none of it`() = runTest {
        // The session is one per process and is still parked where the last screen left it. This
        // screen never saw it running, so the receipt is not addressed to anybody here.
        session.sessionState.value = SessionState.Ended
        val vm = viewModel()
        runCurrent()

        assertNull(vm.uiState.value.wait)
        vm.playerAttached()
        runCurrent()
        assertNull(vm.uiState.value.wait)
    }

    @Test
    fun `the screen that watched it end keeps the receipt for its three seconds`() = runTest {
        val vm = viewModel()
        live()
        session.sessionState.value = SessionState.Ended
        runCurrent()
        assertEquals("Сессия закончилась", vm.uiState.value.wait?.text)

        // Whichever way round these two land — both are on the main dispatcher and nothing orders
        // them — the receipt belongs to this screen and stays for its three seconds.
        vm.playerAttached()
        runCurrent()
        assertEquals("Сессия закончилась", vm.uiState.value.wait?.text)

        advanceTimeBy(3_001)
        runCurrent()
        assertNull(vm.uiState.value.wait)
    }

    @Test
    fun `a player left for good takes the session with it`() = runTest {
        val vm = viewModel()
        live()

        vm.playerGone(finishing = true, changingConfigurations = false)
        runCurrent()

        assertEquals(1, session.left)
    }

    @Test
    fun `a rotation, a home button and a floating window all leave it running`() = runTest {
        val vm = viewModel()
        live()

        vm.playerGone(finishing = true, changingConfigurations = true)
        vm.playerGone(finishing = false, changingConfigurations = false)
        runCurrent()

        assertEquals(0, session.left)
        assertEquals(TogetherPhase.LIVE, vm.uiState.value.phase)
    }

    @Test
    fun `a player with no session behind it leaves nothing`() = runTest {
        val vm = viewModel()
        runCurrent()

        vm.playerGone(finishing = true, changingConfigurations = false)
        runCurrent()

        assertEquals(0, session.left)
    }

    @Test
    fun `leaving a shared viewing leaves it`() = runTest {
        val vm = viewModel()
        live()
        vm.leave()
        runCurrent()
        assertEquals(1, session.left)
        // `Ended`, not `Idle`: the engine says so, and the receipt on screen goes by itself.
        assertEquals(TogetherPhase.ENDED, vm.uiState.value.phase)
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
    fun `a link that is not one of ours is refused on the spot, with nothing to retry`() = runTest {
        val vm = viewModel()
        vm.open("https://example.com/w/nope")
        runCurrent()
        assertTrue(session.joins.isEmpty())
        assertEquals("Ссылка не подходит", vm.uiState.value.join?.error)
        assertFalse("there is no room behind a link that is not one", vm.uiState.value.join!!.retryable)
    }

    @Test
    fun `an invitation that arrives signed out waits for the sign-in and then opens`() = runTest {
        val vm = viewModel()
        vm.offer(link.toHttps())
        runCurrent()
        // Nothing has been joined and nothing is on screen: there is no shell to put it on yet.
        assertTrue(session.joins.isEmpty())
        assertNull(vm.uiState.value.join)
        assertEquals(link.toHttps(), vm.invitationWaiting.value)

        vm.openPending()
        runCurrent()
        assertEquals(link, session.joins.single().first)
        assertTrue(vm.uiState.value.join!!.loading)
        assertNull("taken once and only once", vm.invitationWaiting.value)
    }

    @Test
    fun `a parked invitation is opened by whichever shell asks first`() = runTest {
        val vm = viewModel()
        vm.offer(link.toHttps())
        vm.openPending()
        vm.openPending()
        runCurrent()
        assertEquals(1, session.joins.size)
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
    fun `saying no to an invitation abandons the attempt and leaves the engine able to take another`() = runTest {
        val vm = viewModel()
        vm.open(link.toHttps())
        runCurrent()
        vm.dismissJoin()
        runCurrent()
        assertEquals(1, session.alone)
        // Not `leave()`: that ends in `Ended`, and a second invitation in the same process would
        // be knocking on an engine that has been told the session is over.
        assertEquals(0, session.left)
        assertNull(vm.uiState.value.join)
        assertNull(vm.uiState.value.wait)
    }

    @Test
    fun `a session that comes back does not come back with the failure still on it`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.Notice(NoticeKind.CATCHING_UP, "Вася"))
        runCurrent()
        session.sessionState.value = SessionState.Lost(LostReason.CONNECTION)
        runCurrent()
        assertEquals("Связь с другом потеряна", vm.uiState.value.wait?.text)
        // Back, and still a few seconds apart. The line about the connection belonged to the
        // session that failed, not to this one.
        session.sessionState.value = SessionState.Live("Вася", offsetMs = 0, driftMs = 6_000)
        runCurrent()
        assertNull(vm.uiState.value.wait)
        assertEquals(TogetherPhase.LIVE, vm.uiState.value.phase)
    }

    @Test
    fun `the clock on joining runs from the knock to the session, not to the hello`() = runTest {
        val vm = viewModel()
        vm.open(link.toHttps())
        runCurrent()
        advanceTimeBy(20_000)
        // The other phone answers. Everything that can still stall is ahead of this moment.
        session.sessionState.value = SessionState.Joining(link, hello("Вася"))
        runCurrent()
        assertNull(vm.uiState.value.join?.error)
        advanceTimeBy(11_000)
        assertEquals("Не удалось подключиться", vm.uiState.value.wait?.text)
        assertEquals(WaitExit.WATCH_ALONE, vm.uiState.value.wait?.exit)
        assertEquals("Не удалось подключиться", vm.uiState.value.join?.error)
    }

    @Test
    fun `reaching the session stops that clock`() = runTest {
        val vm = viewModel()
        vm.open(link.toHttps())
        runCurrent()
        advanceTimeBy(20_000)
        session.sessionState.value = SessionState.Live("Вася", offsetMs = 0, driftMs = 0)
        runCurrent()
        advanceTimeBy(30_000)
        assertNull(vm.uiState.value.wait)
        assertEquals(TogetherPhase.LIVE, vm.uiState.value.phase)
    }

    // --- catching up ---------------------------------------------------------------------------

    @Test
    fun `a friend who is behind is a wait with a way out, not a remark that scrolls past`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.Notice(NoticeKind.CATCHING_UP, "Вася"))
        runCurrent()
        assertEquals("Вася догоняет…", vm.uiState.value.wait?.text)
        assertEquals(WaitExit.KEEP_WATCHING, vm.uiState.value.wait?.exit)
        assertNull("the corner is for people talking, not for this", vm.uiState.value.notice)
        // No clock: it ends when the gap does, and a timer would take it away while he is behind.
        advanceTimeBy(60_000)
        assertEquals("Вася догоняет…", vm.uiState.value.wait?.text)
    }

    @Test
    fun `it goes when the gap closes`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.Notice(NoticeKind.CATCHING_UP, "Вася"))
        runCurrent()
        session.sessionState.value = SessionState.Live("Вася", offsetMs = 0, driftMs = 4_000)
        runCurrent()
        assertNotNull("four seconds apart is still behind", vm.uiState.value.wait)
        session.sessionState.value = SessionState.Live("Вася", offsetMs = 0, driftMs = 400)
        runCurrent()
        assertNull(vm.uiState.value.wait)
    }

    @Test
    fun `and it goes when the friend does anything at all`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.Notice(NoticeKind.CATCHING_UP, "Вася"))
        runCurrent()
        session.bus.emit(TogetherEvent.Notice(NoticeKind.PLAYED, "Вася"))
        runCurrent()
        assertNull(vm.uiState.value.wait)
        assertEquals("Вася включил(а)", vm.uiState.value.notice?.text)
    }

    @Test
    fun `pressing «смотреть дальше» on it only dismisses the line`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.Notice(NoticeKind.CATCHING_UP, "Вася"))
        runCurrent()
        vm.leaveWait()
        runCurrent()
        assertNull(vm.uiState.value.wait)
        assertEquals(TogetherPhase.LIVE, vm.uiState.value.phase)
        assertEquals(0, session.left)
        session.sessionState.value = SessionState.Live("Вася", offsetMs = 0, driftMs = 9_000)
        runCurrent()
        assertNull("dismissed means dismissed, not until the next state", vm.uiState.value.wait)
    }

    // --- one session at a time ------------------------------------------------------------------

    @Test
    fun `a new session does not start with the last one's conversation in the corner`() = runTest {
        val vm = viewModel()
        live()
        session.bus.emit(TogetherEvent.ChatItem(1, fromPeer = true, text = "это тот самый кадр", at = 0))
        runCurrent()
        session.sessionState.value = SessionState.Lost(LostReason.CONNECTION)
        runCurrent()
        assertEquals(1, vm.uiState.value.history.size)
        session.sessionState.value = SessionState.Hosting(link, waiting = true)
        runCurrent()
        assertTrue(vm.uiState.value.stack.isEmpty())
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun `a session that is merely getting going keeps what has been said in it`() = runTest {
        val vm = viewModel()
        session.sessionState.value = SessionState.Hosting(link, waiting = true)
        runCurrent()
        session.sessionState.value = SessionState.Live("Вася", 0, 0)
        runCurrent()
        session.bus.emit(TogetherEvent.ChatItem(1, fromPeer = true, text = "ага", at = 0))
        runCurrent()
        session.sessionState.value = SessionState.Hosting(link, waiting = false)
        runCurrent()
        assertEquals(1, vm.uiState.value.history.size)
    }

    @Test
    fun `a second invitation describes the show it is actually about`() = runTest {
        val vm = viewModel()
        library.put(entry(42, "Проводы в последний путь"))
        library.put(entry(77, "Другой тайтл"))
        vm.open(link.toHttps())
        runCurrent()
        session.sessionState.value = SessionState.Joining(link, hello("Вася", animeId = 42))
        runCurrent()
        assertEquals("https://poster/42.jpg", vm.uiState.value.join?.posterUrl)

        session.sessionState.value = SessionState.Idle
        runCurrent()
        vm.open(link.toHttps())
        runCurrent()
        session.sessionState.value = SessionState.Joining(link, hello("Петя", animeId = 77))
        runCurrent()
        assertEquals("https://poster/77.jpg", vm.uiState.value.join?.posterUrl)
        assertEquals("Петя смотрит «Другой тайтл», 3 серия, 0:10", vm.uiState.value.join?.line)
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
