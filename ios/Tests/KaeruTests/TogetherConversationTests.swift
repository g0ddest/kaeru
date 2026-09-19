import XCTest
@testable import Kaeru

/// The rules the corner of the picture obeys, run through a whole evening without sleeping through
/// any of it. Every number here is Android's.
@MainActor final class TogetherConversationTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_758_000_000)
    private func at(_ seconds: Double) -> Date { start.addingTimeInterval(seconds) }
    private func said(_ text: String, mine: Bool = false, id: Int64 = 0) -> TogetherSaid {
        TogetherSaid(id: id, mine: mine, author: mine ? TogetherCopy.you : "Вася", text: text, at: start)
    }

    func testALineFadesAfterSevenSecondsAndIsGoneAtTheEndOfThem() {
        let conversation = TogetherConversation()
        conversation.chat("это же тот самый кадр", mine: false, author: "Вася", now: start)
        conversation.sweep(now: at(6.5))
        XCTAssertEqual(conversation.stack.count, 1)
        XCTAssertFalse(conversation.stack[0].leaving)
        // The fade is part of the seven seconds rather than added to them.
        conversation.sweep(now: at(6.75))
        XCTAssertTrue(conversation.stack[0].leaving, "It is on its way out, not gone")
        conversation.sweep(now: at(7.01))
        XCTAssertTrue(conversation.stack.isEmpty)
    }

    func testAtMostThreeStandInTheCornerAndTheNewestSurvives() {
        let conversation = TogetherConversation()
        for index in 1...5 { conversation.chat("строка \(index)", mine: false, author: "Вася", now: start) }
        XCTAssertEqual(conversation.stack.count, 3)
        XCTAssertEqual(conversation.stack.map(\.text), ["строка 3", "строка 4", "строка 5"])
        XCTAssertEqual(conversation.history.count, 5, "The history keeps all of it")
    }

    /// A screen reader running: nothing may be taken away on a clock.
    func testNothingExpiresWhileTheScreenIsBeingListenedTo() {
        let conversation = TogetherConversation()
        conversation.autoHide = false
        conversation.chat("привет", mine: false, author: "Вася", now: start)
        conversation.sweep(now: at(60))
        XCTAssertEqual(conversation.stack.count, 1)
        XCTAssertFalse(conversation.stack[0].leaving)
    }

    func testThreeReactionsAreInFlightAndNoFourth() {
        let conversation = TogetherConversation()
        for _ in 0..<6 { conversation.fly(.heart, mine: false, now: start) }
        XCTAssertEqual(conversation.reactions.count, 3)
        conversation.sweep(now: at(1.2))
        XCTAssertTrue(conversation.reactions.isEmpty)
        conversation.fly(.fire, mine: true, now: at(1.2))
        XCTAssertEqual(conversation.reactions.count, 1, "The room clears and the next one flies")
    }

    func testANoticeIsReplacedRatherThanQueuedAndLastsThreeSeconds() {
        let conversation = TogetherConversation()
        conversation.notice(.paused, peerName: "Вася", now: start)
        XCTAssertEqual(conversation.notice?.text, "Вася поставил(а) на паузу")
        conversation.notice(.played, peerName: "Вася", now: at(1))
        XCTAssertEqual(conversation.notice?.text, "Вася включил(а)")
        conversation.sweep(now: at(3.5))
        XCTAssertNotNil(conversation.notice, "Its own three seconds, counted from when it was said")
        conversation.sweep(now: at(4.01))
        XCTAssertNil(conversation.notice)
    }

    /// «Догоняет» has no clock. It ends when the gap does, or when the friend does something else.
    func testCatchingUpIsAWaitThatEndsWhenTheGapDoes() {
        let conversation = TogetherConversation()
        conversation.notice(.catchingUp, peerName: "Вася", now: start)
        XCTAssertEqual(conversation.wait, TogetherWait(text: "Вася догоняет…", exit: .keepWatching))
        conversation.sweep(now: at(120))
        XCTAssertNotNil(conversation.wait, "A clock would take it away while the friend was behind")
        conversation.caughtUp()
        XCTAssertNil(conversation.wait)
    }

    func testAnythingElseTheFriendDoesProvesTheCatchingUpFinished() {
        let conversation = TogetherConversation()
        conversation.notice(.catchingUp, peerName: "Вася", now: start)
        conversation.notice(.paused, peerName: "Вася", now: at(4))
        XCTAssertNil(conversation.wait)
        XCTAssertEqual(conversation.notice?.text, "Вася поставил(а) на паузу")
    }

    /// Every wait carries a way out, and the two ways out mean different things.
    func testWaitingForAFriendGivesUpAfterHalfAMinuteButKeepsTheRoom() {
        let conversation = TogetherConversation()
        conversation.phaseChanged(.live, peerPresent: false, error: nil, now: start)
        XCTAssertEqual(conversation.wait, TogetherWait(text: "Ждём друга…", exit: .keepWatching))
        conversation.sweep(now: at(29))
        XCTAssertNotNil(conversation.wait)
        conversation.sweep(now: at(30.1))
        XCTAssertNil(conversation.wait, "The line goes; the room stays")
    }

    func testAnInvitationNobodyAnsweredBecomesAFailureWithAWayForward() {
        let conversation = TogetherConversation()
        conversation.phaseChanged(.connecting, peerPresent: false, error: nil, now: start)
        XCTAssertEqual(conversation.wait, TogetherWait(text: "Подключаемся…", exit: .watchAlone))
        conversation.sweep(now: at(30.1))
        XCTAssertEqual(conversation.wait, TogetherWait(text: "Не удалось подключиться", exit: .watchAlone))
    }

    func testTheReceiptForEndingASessionIsSaidAndGone() {
        let conversation = TogetherConversation()
        conversation.phaseChanged(.ended, peerPresent: false, error: nil, now: start)
        XCTAssertEqual(conversation.wait?.text, "Сессия закончилась")
        conversation.sweep(now: at(3.1))
        XCTAssertNil(conversation.wait)
    }

    func testAFriendWhoWalksInTakesTheWaitAwayWithThem() {
        let conversation = TogetherConversation()
        conversation.phaseChanged(.live, peerPresent: false, error: nil, now: start)
        conversation.phaseChanged(.live, peerPresent: true, error: nil, now: at(4))
        XCTAssertNil(conversation.wait)
        conversation.sweep(now: at(40))
        XCTAssertNil(conversation.wait, "And the clock that was running goes with it")
    }

    /// A friend in a tunnel: the seat is held, so the way out keeps the room rather than closing it.
    func testAHeldSeatOffersToKeepWatchingAndALostOneToWatchAlone() {
        let conversation = TogetherConversation()
        conversation.phaseChanged(.reconnecting, peerPresent: false, error: nil, now: start)
        XCTAssertEqual(conversation.wait, TogetherWait(text: "Восстанавливаем связь…", exit: .keepWatching))
        conversation.phaseChanged(.failed, peerPresent: false, error: .disconnected, now: at(30))
        XCTAssertEqual(conversation.wait, TogetherWait(text: "Связь с другом потеряна", exit: .watchAlone))
    }

    func testEachFailureSaysWhatToDoNext() {
        XCTAssertEqual(TogetherCopy.lost(.roomFull), "В этой сессии уже двое")
        XCTAssertEqual(TogetherCopy.lost(.notConfigured), "Сервер совместного просмотра не настроен")
        XCTAssertEqual(TogetherCopy.lost(.timeout), "Не удалось подключиться")
        XCTAssertEqual(TogetherCopy.lost(nil), "Связь с другом потеряна")
    }

    func testLeavingAWaitSaysWhichButtonItWas() {
        let conversation = TogetherConversation()
        conversation.phaseChanged(.live, peerPresent: false, error: nil, now: start)
        XCTAssertEqual(conversation.leaveWait(), .keepWatching)
        XCTAssertNil(conversation.wait)
        conversation.phaseChanged(.failed, peerPresent: false, error: .disconnected, now: at(1))
        XCTAssertEqual(conversation.leaveWait(), .watchAlone)
    }

    /// A clip from the other phone goes straight through the speaker; one this viewer recorded
    /// does not, because they have just heard themselves say it.
    func testAnArrivingClipPlaysItselfAndMineDoesNot() {
        let conversation = TogetherConversation()
        conversation.clip(TogetherClip(data: Data([1, 2, 3]), durationMs: 7_400), mine: true, author: TogetherCopy.you, now: start)
        XCTAssertNil(conversation.playing)
        conversation.clip(TogetherClip(data: Data([4, 5]), durationMs: 2_000), mine: false, author: "Вася", now: at(1))
        XCTAssertEqual(conversation.playing?.clip?.durationMs, 2_000)
        conversation.clipPlayed()
        XCTAssertNil(conversation.playing)
    }

    func testAClipCanBeHeardAgainAfterTheCornerHasForgottenIt() {
        let conversation = TogetherConversation()
        conversation.clip(TogetherClip(data: Data([4, 5]), durationMs: 2_000), mine: false, author: "Вася", now: start)
        conversation.clipPlayed()
        let id = conversation.history[0].id
        conversation.sweep(now: at(8))
        XCTAssertTrue(conversation.stack.isEmpty)
        conversation.replay(id)
        XCTAssertEqual(conversation.playing?.id, id)
    }

    func testAMessageIsTrimmedToWhatTheProtocolCarriesAndAnEmptyOneIsNotSaid() {
        let conversation = TogetherConversation()
        conversation.chat("   ", mine: true, author: TogetherCopy.you, now: start)
        XCTAssertTrue(conversation.history.isEmpty)
        conversation.chat(String(repeating: "я", count: 400), mine: true, author: TogetherCopy.you, now: start)
        XCTAssertEqual(conversation.history.first?.text?.count, TogetherCopy.maxChars)
    }

    func testASessionThatIsOverTakesItsConversationWithIt() {
        let conversation = TogetherConversation()
        conversation.chat("привет", mine: false, author: "Вася", now: start)
        conversation.fly(.clap, mine: false, now: start)
        conversation.notice(.joined, peerName: "Вася", now: start)
        conversation.phaseChanged(.idle, peerPresent: false, error: nil, now: at(1))
        XCTAssertTrue(conversation.stack.isEmpty)
        XCTAssertTrue(conversation.history.isEmpty)
        XCTAssertTrue(conversation.reactions.isEmpty)
        XCTAssertNil(conversation.notice)
        XCTAssertNil(conversation.wait)
    }

    /// Whoever is on the other phone, named — including when Shikimori never named them.
    func testSomebodyWithoutANameIsStillCalledSomething() {
        XCTAssertEqual(TogetherCopy.notice(.left, peerName: "  "), "Друг вышел(ла)")
        XCTAssertEqual(TogetherCopy.notice(.seeked, peerName: "Вася", positionMs: 724_000), "Вася перемотал(а) на 12:04")
        XCTAssertEqual(TogetherCopy.notice(.episode, peerName: "Вася", episode: 7), "Вася включил(а) 7 серию")
    }

    func testTheChipNamesWhoeverIsThereAndSaysNothingWhenNobodyIs() {
        XCTAssertEqual(TogetherCopy.sessionChip(phase: .live, peerName: "Вася"), "Вася")
        XCTAssertEqual(TogetherCopy.sessionChip(phase: .live, peerName: nil), "Ждём друга…")
        XCTAssertEqual(TogetherCopy.sessionChip(phase: .connecting, peerName: nil), "Подключаемся…")
        XCTAssertNil(TogetherCopy.sessionChip(phase: .ended, peerName: "Вася"))
        XCTAssertNil(TogetherCopy.sessionChip(phase: .idle, peerName: nil))
    }
}
