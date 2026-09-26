import XCTest
@testable import Kaeru

@MainActor private final class StubRepository: UpdateRepository {
    var lastResult: UpdateResult?
    var outcome: Result<UpdateResult, Error> = .failure(UpdateFailed(reason: .unknown))
    var forced: [Bool] = []
    /// Holds the first check open, so a test can watch the screen while one is still in flight.
    var waits = false
    private var gate: CheckedContinuation<Void, Never>?
    private var freed = false

    func check(force: Bool) async -> Result<UpdateResult, Error> {
        forced.append(force)
        if waits, !freed { await withCheckedContinuation { gate = $0 } }
        return outcome
    }
    func answer() { freed = true; gate?.resume(); gate = nil }
}

@MainActor final class UpdateModelTests: XCTestCase {
    private let epoch = Date(timeIntervalSince1970: 1_758_326_400)
    private var opened: [URL] = []
    private var opens = true

    /// A link already resolved for its system. The model hands it over as it is, whichever it is.
    private let installLink = "itms-services://?action=download-manifest&url=https%3A%2F%2Fexample.test%2Fm.plist"

    private func release(_ version: String, install: String? = nil,
                         page: String? = "https://example.test/r") -> UpdateRelease {
        UpdateRelease(version: version, publishedAt: epoch, notes: "Что нового",
                      install: install.flatMap(URL.init(string:)), page: page.flatMap(URL.init(string:)),
                      sizeBytes: 31_457_280)
    }
    private func model(_ repository: StubRepository, installed: String = "0.5.1") -> UpdateModel {
        UpdateModel(repository: repository, installedVersion: installed) { [self] url in
            opened.append(url)
            return opens
        }
    }

    func testAnOfferedReleaseBecomesTheAvailableStage() async {
        let repository = StubRepository()
        repository.outcome = .success(UpdateResult(checkedAt: epoch, installedVersion: "0.5.1", release: release("0.6.0")))
        let model = model(repository)
        model.check(force: false)
        await settle()
        XCTAssertEqual(model.stage, .available)
        XCTAssertEqual(model.release?.version, "0.6.0")
        XCTAssertEqual(model.checkedAt, epoch)
        XCTAssertNil(model.message)
    }

    func testNothingNewerIsNotTheSameAsNothingKnown() async {
        let repository = StubRepository()
        repository.outcome = .success(UpdateResult(checkedAt: epoch, installedVersion: "0.5.1", release: nil))
        let model = model(repository)
        model.check(force: false)
        await settle()
        XCTAssertEqual(model.stage, .upToDate)
    }

    /// Telling somebody they have the latest version when nothing was ever checked is a sentence
    /// that is simply untrue.
    func testAFirstCheckThatFailedOnAFreshDeviceSaysNothingIsKnown() async {
        let repository = StubRepository()
        repository.outcome = .failure(UpdateFailed(reason: .noNetwork))
        let model = model(repository)
        model.check(force: false)
        await settle()
        XCTAssertEqual(model.stage, .unknown)
        XCTAssertEqual(model.message, "Нет связи")
    }

    /// The whole of what «offline: show the last known result» means.
    func testATunnelLeavesTheReleaseOnScreenUnderTheFailure() async {
        let repository = StubRepository()
        repository.lastResult = UpdateResult(checkedAt: epoch, installedVersion: "0.5.1", release: release("0.6.0"))
        repository.outcome = .failure(UpdateFailed(reason: .noNetwork))
        let model = model(repository)
        model.check(force: true)
        await settle()
        XCTAssertEqual(model.stage, .available)
        XCTAssertEqual(model.release?.version, "0.6.0")
        XCTAssertEqual(model.message, "Нет связи")
    }

    func testTheRateLimitSaysHowLongItLasts() async {
        let repository = StubRepository()
        repository.outcome = .failure(UpdateFailed(reason: .rateLimited))
        let model = model(repository)
        model.check(force: true)
        await settle()
        XCTAssertEqual(model.message, "GitHub ограничил запросы, попробуйте через час")
    }

    /// Opening the screen asks unforced — the app already asked at launch, and a second request a
    /// minute later would spend the hourly budget on an answer it has in hand.
    func testOpeningTheScreenAsksUnforcedAndThePressAsksForced() async {
        let repository = StubRepository()
        repository.outcome = .success(UpdateResult(checkedAt: epoch, installedVersion: "0.5.1", release: nil))
        let model = model(repository)
        model.check(force: false)
        await settle()
        model.check()
        await settle()
        XCTAssertEqual(repository.forced, [false, true])
    }

    /// Two answers to one question would race to write the same three fields.
    func testASecondPressWhileACheckIsRunningBuysNothing() async {
        let repository = StubRepository()
        repository.waits = true
        repository.outcome = .success(UpdateResult(checkedAt: epoch, installedVersion: "0.5.1", release: nil))
        let model = model(repository)
        model.check(force: false)
        await settle()
        XCTAssertEqual(model.stage, .checking)
        XCTAssertFalse(model.canCheck)
        model.check()
        await settle()
        repository.answer()
        await settle()
        XCTAssertEqual(repository.forced, [false])
    }

    // MARK: - what the press does

    func testTheInstallLinkIsHandedToTheSystem() async {
        let repository = StubRepository()
        repository.outcome = .success(UpdateResult(checkedAt: epoch, installedVersion: "0.5.1",
                                                   release: release("0.6.0", install: installLink)))
        let model = model(repository)
        model.check(force: false)
        await settle()
        model.install()
        await settle()
        XCTAssertEqual(opened.map(\.absoluteString), [installLink])
        XCTAssertNil(model.message)
    }

    /// The platform difference: with nothing to install from there is still a page, and a page is
    /// somewhere to send somebody.
    func testWithNothingToInstallFromThePageIsOpenedInstead() async {
        let repository = StubRepository()
        repository.outcome = .success(UpdateResult(checkedAt: epoch, installedVersion: "0.5.1", release: release("0.6.0")))
        let model = model(repository)
        model.check(force: false)
        await settle()
        model.install()
        await settle()
        XCTAssertEqual(opened.map(\.absoluteString), ["https://example.test/r"])
    }

    func testALinkNothingTookIsSaidOutLoud() async {
        opens = false
        let repository = StubRepository()
        repository.outcome = .success(UpdateResult(checkedAt: epoch, installedVersion: "0.5.1",
                                                   release: release("0.6.0", install: installLink)))
        let model = model(repository)
        model.check(force: false)
        await settle()
        model.install()
        await settle()
        #if os(macOS)
        XCTAssertEqual(model.message, "Не удалось открыть загрузку")
        #else
        XCTAssertEqual(model.message, "iOS не открыла установку")
        #endif
    }

    // MARK: - the words

    /// The press and the sentence over it say what this system does with the release: the iPhone
    /// installs it, the Mac only downloads it — the viewer drags it into place.
    func testThePressSaysWhatThisSystemDoes() {
        #if os(macOS)
        XCTAssertEqual(UpdateCopy.install, "Скачать")
        XCTAssertEqual(UpdateCopy.installNote,
                       "Браузер скачает образ диска — откройте его, закройте Kaeru и перетащите новую версию в «Программы» с заменой")
        XCTAssertEqual(UpdateCopy.pageNote, "У этого выпуска нет образа для Mac — страница выпуска на GitHub")
        #else
        XCTAssertEqual(UpdateCopy.install, "Установить")
        XCTAssertEqual(UpdateCopy.installNote, "iOS спросит, можно ли установить приложение, и поставит его поверх текущего")
        XCTAssertEqual(UpdateCopy.pageNote, "У этого выпуска нет файла для установки на iPhone — страница выпуска на GitHub")
        #endif
    }

    func testTheRowAndTheHeadlineAreOneSentence() {
        XCTAssertEqual(UpdateCopy.available("0.6.0"), "Доступна версия 0.6.0")
    }

    /// A release with neither a date nor a size prints nothing rather than an empty phrase with
    /// punctuation in it.
    func testTheReleaseLineOmitsWhatIsNotKnown() {
        var value = release("0.6.0")
        XCTAssertEqual(UpdateCopy.releaseLine(value)?.contains(","), true)
        value.sizeBytes = 0
        XCTAssertEqual(UpdateCopy.releaseLine(value)?.contains(","), false)
        value.publishedAt = nil
        XCTAssertNil(UpdateCopy.releaseLine(value))
    }

    func testCheckedLineIsNothingOnADeviceThatNeverManagedACheck() {
        XCTAssertNil(UpdateCopy.checkedLine(nil))
        XCTAssertEqual(UpdateCopy.checkedLine(epoch)?.hasPrefix("Проверено "), true)
    }

    /// Anything that is not one of ours says the general sentence rather than its own English
    /// `localizedDescription`: this screen is in Russian throughout.
    func testAForeignErrorStillReadsAsRussian() {
        XCTAssertEqual(UpdateCopy.message(for: URLError(.badURL)), "Не удалось проверить обновления")
        XCTAssertEqual(UpdateCopy.message(for: UpdateFailed(reason: .noNetwork)), "Нет связи")
    }

    /// Lets the model's own task run to completion without sleeping the test.
    private func settle() async {
        for _ in 0..<6 { await Task.yield() }
    }
}
