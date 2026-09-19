import XCTest
@testable import Kaeru

@MainActor final class CastManagerTests: XCTestCase {
    private let anime = Anime(id: 42, title: "Example")

    func testConnectionPausesLocalAndResolvesFreshBeforeSendingHandoff() async {
        let transport = CastTestTransport()
        var resolved: [(Int, Int, Int)] = []
        let manager = CastManager(resolve: { id, translation, episode in
            resolved.append((id, translation, episode))
            return Self.stream(episode: episode, translation: translation)
        }, transport: transport)
        var pauses = 0
        manager.localPlayback = { [anime] in .init(selection: .init(anime: anime, episode: 3, translation: 7, quality: 720), position: 123, shouldPlay: false) }
        manager.onPauseLocal = { pauses += 1 }
        transport.onConnection?(.connected("TV"))
        for _ in 0..<20 where transport.loads.isEmpty { await Task.yield() }
        XCTAssertEqual(pauses, 1)
        XCTAssertEqual(resolved.first?.0, 42)
        XCTAssertEqual(resolved.first?.1, 7)
        XCTAssertEqual(resolved.first?.2, 3)
        XCTAssertEqual(transport.loads.first?.position, 123)
        XCTAssertEqual(transport.loads.first?.autoplay, false)
        manager.clearPlayback()
    }

    func testSuspensionDoesNotResumeLocalAndDisconnectReturnsLatestRemotePositionOnce() async {
        let transport = CastTestTransport()
        let manager = CastManager(resolve: { _, translation, episode in Self.stream(episode: episode, translation: translation) }, transport: transport)
        transport.onConnection?(.connected("TV"))
        await manager.load(anime: anime, episode: 3, translation: 7, quality: 720, position: 20, autoplay: true)
        transport.onStatus?(.init(contentID: "https://example.com/3/7", position: 45, duration: 100, phase: .paused))
        var returns: [CastHandoff] = []
        manager.onReturnToLocal = { returns.append($0) }
        transport.onConnection?(.suspended("TV"))
        XCTAssertTrue(returns.isEmpty)
        transport.onConnection?(.connected("TV"))
        XCTAssertEqual(transport.loads.count, 1)
        manager.stopCasting()
        XCTAssertEqual(transport.disconnects, 1)
        transport.onConnection?(.disconnected)
        transport.onConnection?(.disconnected)
        XCTAssertEqual(returns.count, 1)
        XCTAssertEqual(returns.first?.position, 45)
        XCTAssertEqual(returns.first?.shouldPlay, false)
    }

    func testQualityAndTranslationSwapPreservePausedPosition() async {
        let transport = CastTestTransport()
        let manager = CastManager(resolve: { _, translation, episode in Self.stream(episode: episode, translation: translation) }, transport: transport)
        transport.onConnection?(.connected("TV"))
        await manager.load(anime: anime, episode: 3, translation: 7, quality: 720, position: 81, autoplay: false)
        await manager.selectQuality(480)
        await manager.selectTranslation(8)
        XCTAssertEqual(transport.loads.last?.selection.translation, 8)
        XCTAssertEqual(transport.loads.last?.selection.quality, 480)
        XCTAssertEqual(transport.loads.last?.position, 81)
        XCTAssertEqual(transport.loads.last?.autoplay, false)
        manager.clearPlayback()
    }

    func testFinishedNotifiesOnceAndStoppedDoesNotAdvanceEpisode() async {
        let transport = CastTestTransport()
        let manager = CastManager(resolve: { _, translation, episode in Self.stream(episode: episode, translation: translation) }, transport: transport)
        transport.onConnection?(.connected("TV"))
        await manager.load(anime: anime, episode: 3, translation: 7, quality: 720, position: 0, autoplay: true)
        var ended = 0
        manager.onEnded = { _ in ended += 1 }
        transport.onStatus?(.init(contentID: "https://example.com/3/7", position: 10, duration: 100, phase: .stopped))
        XCTAssertEqual(ended, 0)
        for _ in 0..<3 { transport.onStatus?(.init(contentID: "https://example.com/3/7", position: 100, duration: 100, phase: .finished)) }
        XCTAssertEqual(ended, 1)
        manager.clearPlayback()
    }

    func testLateResolutionAfterDisconnectCannotLoadReceiver() async {
        let transport = CastTestTransport()
        var pending: CheckedContinuation<Kaeru.Stream, Error>?
        let manager = CastManager(resolve: { _, _, _ in try await withCheckedThrowingContinuation { pending = $0 } }, transport: transport)
        transport.onConnection?(.connected("TV"))
        let task = Task { await manager.load(anime: anime, episode: 3, translation: 7, quality: 720, position: 0, autoplay: true) }
        for _ in 0..<20 where pending == nil { await Task.yield() }
        transport.onConnection?(.disconnected)
        pending?.resume(returning: Self.stream(episode: 3, translation: 7))
        await task.value
        XCTAssertTrue(transport.loads.isEmpty)
        XCTAssertNil(manager.state.selection)
    }

    private static func stream(episode: Int, translation: Int) -> Kaeru.Stream {
        .init(urls: [.init(quality: 720, url: "https://example.com/\(episode)/\(translation)"),
                     .init(quality: 480, url: "https://example.com/\(episode)/\(translation)/480")],
              headers: ["Referer": "https://private.example"], translation: .init(id: translation, title: "Voice", episodes: 12), episode: episode)
    }
}

@MainActor private final class CastTestTransport: CastTransport {
    var onConnection: ((CastConnection) -> Void)?
    var onStatus: ((CastRemoteStatus) -> Void)?
    var onFailure: ((CastFailure) -> Void)?
    var loads: [CastLoadPayload] = []
    var disconnects = 0
    func start() {}
    func load(_ payload: CastLoadPayload, completion: @escaping (Result<Void, CastFailure>) -> Void) { loads.append(payload); completion(.success(())) }
    func play() {}
    func pause() {}
    func seek(to position: TimeInterval) {}
    func stop() {}
    func disconnect() { disconnects += 1 }
    func presentDevices() {}
    func presentExpandedControls() {}
}
