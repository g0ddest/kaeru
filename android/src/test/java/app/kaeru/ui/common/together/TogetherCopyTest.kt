package app.kaeru.ui.common.together

import app.kaeru.domain.together.LostReason
import app.kaeru.domain.together.NoticeKind
import app.kaeru.domain.together.TogetherEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every sentence this feature says, checked where it is built rather than where it is drawn.
 *
 * The strings are the feature as much as the sockets are: a wait without its way out, or a notice
 * that names nobody, is the failure mode the whole design is arranged against.
 */
class TogetherCopyTest {

    @Test
    fun `the message to a friend names the show, the episode and the link`() {
        val text = TogetherCopy.shareText(
            title = "Проводы в последний путь",
            episode = 7,
            link = "https://kaeru.vitaliy.velikodniy.name/w/7f3a",
        )
        assertEquals(
            "Смотрим «Проводы в последний путь», 7 серию. Открой в Kaeru: " +
                "https://kaeru.vitaliy.velikodniy.name/w/7f3a",
            text,
        )
    }

    @Test
    fun `the episode number is an ordinal, so the noun does not agree with it`() {
        // «5 серий» would be five episodes. This is the fifth one, and it stays «серия».
        assertEquals("5 серия", TogetherCopy.episodeNominative(5))
        assertEquals("5 серию", TogetherCopy.episodeAccusative(5))
        assertEquals("21 серия", TogetherCopy.episodeNominative(21))
        assertTrue(TogetherCopy.shareText("Тайтл", 21, "l").contains("21 серию"))
    }

    @Test
    fun `the join screen says who is watching what, and from which minute`() {
        assertEquals(
            "Вася смотрит «Проводы в последний путь», 7 серия, 12:04",
            TogetherCopy.joinLine(
                peerName = "Вася",
                title = "Проводы в последний путь",
                episode = 7,
                positionMs = 724_000,
            ),
        )
    }

    @Test
    fun `an hour into an episode is still read as one time`() {
        val line = TogetherCopy.joinLine("Вася", "Тайтл", 1, positionMs = 3_725_000)
        assertTrue(line, line.endsWith("1:02:05"))
    }

    @Test
    fun `every notice names the person who did the thing`() {
        val notices = NoticeKind.entries.map { kind ->
            kind to TogetherCopy.notice(
                TogetherEvent.Notice(kind, peerName = "Вася", positionMs = 724_000, episode = 8),
            )
        }
        notices.forEach { (kind, text) ->
            assertFalse("$kind is empty", text.isEmpty())
            // «У тебя другая озвучка» is about this viewer's own stream, and is the one line here
            // that is not a report of what somebody else pressed.
            if (kind != NoticeKind.OTHER_VOICE) assertTrue("$kind: $text", text.startsWith("Вася"))
        }
    }

    @Test
    fun `the four notices that carry a number spell it out`() {
        val seeked = TogetherCopy.notice(
            TogetherEvent.Notice(NoticeKind.SEEKED, "Вася", positionMs = 724_000),
        )
        assertEquals("Вася перемотал(а) на 12:04", seeked)
        val episode = TogetherCopy.notice(
            TogetherEvent.Notice(NoticeKind.EPISODE, "Вася", episode = 8),
        )
        assertEquals("Вася включил(а) 8 серию", episode)
        assertEquals(
            "Вася поставил(а) на паузу",
            TogetherCopy.notice(TogetherEvent.Notice(NoticeKind.PAUSED, "Вася")),
        )
        assertEquals(
            "Вася включил(а)",
            TogetherCopy.notice(TogetherEvent.Notice(NoticeKind.PLAYED, "Вася")),
        )
    }

    @Test
    fun `a viewer with no name is still somebody`() {
        assertEquals(
            "Друг подключился(ась)",
            TogetherCopy.notice(TogetherEvent.Notice(NoticeKind.JOINED, peerName = "")),
        )
    }

    @Test
    fun `every way a session can end has its own sentence`() {
        assertEquals("Связь с другом потеряна", TogetherCopy.lost(LostReason.CONNECTION))
        assertEquals("Не удалось подключиться", TogetherCopy.lost(LostReason.WAIT_TIMEOUT))
        assertEquals("В этой сессии уже двое", TogetherCopy.lost(LostReason.ROOM_FULL))
        assertEquals("Сервер совместного просмотра не настроен", TogetherCopy.lost(LostReason.NOT_CONFIGURED))
        // The relay's idle close is the room running out, not the connection dropping — and the
        // same sentence as on the iPhone across the sofa.
        assertEquals("Комната закрылась: в ней шесть часов ничего не происходило", TogetherCopy.lost(LostReason.EXPIRED))
        assertEquals("Сессия закончилась", TogetherCopy.ENDED)
    }

    @Test
    fun `no sentence shouts, and none of them uses a separator dot`() {
        val everything = buildList {
            add(TogetherCopy.WATCH_TOGETHER)
            add(TogetherCopy.LEAVE)
            add(TogetherCopy.WAITING_FRIEND)
            add(TogetherCopy.KEEP_WATCHING)
            add(TogetherCopy.WATCH_ALONE)
            add(TogetherCopy.JOIN)
            add(TogetherCopy.NOT_NOW)
            add(TogetherCopy.CONNECTING)
            add(TogetherCopy.WRITE_PLACEHOLDER)
            add(TogetherCopy.VOICE_HINT)
            add(TogetherCopy.MIC_DENIED)
            add(TogetherCopy.ENDED)
            add(TogetherCopy.EMPTY_HISTORY)
            add(TogetherCopy.LEFT_SESSION)
            addAll(TogetherCopy.PRESETS)
            addAll(LostReason.entries.map(TogetherCopy::lost))
        }
        everything.forEach { line ->
            assertFalse(line, line.contains('·'))
            val words = line.split(' ').filter { it.length > 2 && it.any(Char::isLetter) }
            words.forEach { word ->
                assertFalse("$line: $word", word == word.uppercase() && word != word.lowercase())
            }
        }
    }

    @Test
    fun `the presets are what a person taps instead of opening a keyboard`() {
        assertEquals(listOf("😂", "Стоп, что?", "Дальше!"), TogetherCopy.PRESETS)
    }

    @Test
    fun `a clip says how long it is, the way a player does`() {
        assertEquals("0:07", TogetherCopy.clipLength(7_400))
        assertEquals("0:30", TogetherCopy.clipLength(30_000))
    }
}
