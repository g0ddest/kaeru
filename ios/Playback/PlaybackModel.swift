import AVKit
import Observation

struct PlaybackSnapshot: Equatable {
    var animeID: Int
    var episode: Int
    var translation: Int
    var position: Double
    var duration: Double
    var isPlaying: Bool
    var speed: Double
    var ready: Bool
    /// The player wants to play and cannot: it is waiting for the network. This is what the
    /// friend's phone is told, so that it waits too instead of running ahead.
    var buffering = false
}
enum PlaybackLocalAction {
    case playing(Bool), seek(Double), speed(Double), episode(Int)
}

@MainActor @Observable final class PlaybackModel {
    let player = AVPlayer()
    let anime: Anime
    private let model: AppModel
    private let account: String
    private(set) var episode: Int
    private(set) var translation = 0
    private(set) var quality = 0
    private(set) var selectedQuality = 0
    private(set) var translations: [Translation] = []
    private(set) var qualities: [Int] = []
    private(set) var loading = true
    private(set) var isLocal = false
    private(set) var position = 0.0
    private(set) var duration = 0.0
    private(set) var isPlaying = false
    /// Whether this app's own controls are on screen.
    ///
    /// AVKit's transport bar fades itself out a few seconds into an episode and the picture is
    /// left alone, which is the whole feel of a player on this phone. The bar this app draws above
    /// it used to stay — a black strip across the top of a frame with nothing else on it. There is
    /// no API that reports AVKit's own visibility, so this follows the same tap AVKit follows and
    /// fades on the same sort of timer, and it never fades while the episode is paused, because
    /// AVKit does not either.
    private(set) var chromeVisible = true
    @ObservationIgnored private var chromeTimer: Task<Void, Never>?
    /// The single tap AVKit uses to raise and lower its own controls.
    func toggleChrome() {
        chromeVisible.toggle()
        if chromeVisible { scheduleChromeHide() } else { chromeTimer?.cancel(); chromeTimer = nil }
    }
    func showChrome() {
        chromeVisible = true
        scheduleChromeHide()
    }
    private func scheduleChromeHide() {
        chromeTimer?.cancel()
        chromeTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, let self, self.isPlaying, self.error == nil else { return }
            self.chromeVisible = false
        }
    }
    private(set) var pictureInPicture = false
    private(set) var finished = false
    private(set) var nextEpisode = NextEpisodeState()
    private(set) var skipOffer: SkipOffer?
    private(set) var error: String?
    // Future Together integration consumes snapshots and only local actions. Applying a remote
    // snapshot does not echo back through onLocalAction; guests can disable automatic decisions.
    var onLocalAction: ((PlaybackLocalAction) -> Void)?
    private(set) var synchronizationControlled = false
    var snapshot: PlaybackSnapshot {
        PlaybackSnapshot(animeID: anime.id, episode: episode, translation: translation,
                         position: position, duration: duration, isPlaying: isPlaying,
                         speed: speed, ready: !loading && player.currentItem?.status == .readyToPlay,
                         // `waitingToPlayAtSpecifiedRate` is AVKit's spinner: play was asked for
                         // and the segment is not here yet. A player that is loading its first
                         // frames reports it too, which is right — the friend waits either way.
                         buffering: loading || player.timeControlStatus == .waitingToPlayAtSpecifiedRate)
    }
    private(set) var speed: Double
    var autoNext: Bool { model.autoNext }
    var autoSkipEnding: Bool { model.preferences.autoSkipEnding }
    var backgroundPlayback: Bool { model.preferences.backgroundPlayback }
    var pipOnLeave: Bool { model.preferences.pipOnLeave }
    var skipSeconds: Int { model.preferences.skipSeconds }
    var castManager: CastManager { model.cast }
    var hasNext: Bool { episode > 0 && episode < episodeCount }
    var episodeCount: Int {
        // A missing episode in this dub may be served by another eligible dub. The user's
        // remembered preference is retained so it can become available again next episode.
        let known = anime.availableEpisodes
        let offered = translations.map(\.episodes).max() ?? 0
        return known > 0 ? (offered > 0 ? min(known, offered) : known) : 0
    }
    private var stream: Stream?
    private var request = UUID()
    private var itemObservation: NSKeyValueObservation?
    private var statusObservation: NSKeyValueObservation?
    private var timer: Any?
    private var observations: [NSObjectProtocol] = []
    private var loadTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?
    /// The watchdog behind «Открываем серию…»: the last resort when every other deadline has been
    /// cancelled along with the work it was guarding.
    private var stallTask: Task<Void, Never>?
    private var marksTask: Task<Void, Never>?
    private var installedEpisode: Int?
    private var readyItem: AVPlayerItem?
    private var restoring = false
    private var seeking = false
    /// When the session last moved this player without a person asking. See the time-jump observer.
    private var quietSeekAt = Date.distantPast
    /// When the session last paused or started this player without a person asking. See `tick`.
    private var quietPlayingChangeAt = Date.distantPast
    private var seekRevision = UUID()
    private var retried = false
    private var closed = false
    private var started = false
    private var beganLibraryPlayback = false
    private var requestedPosition = 0.0
    private var lastSavedPosition = -1.0
    private var sceneActive = true
    private var intent = PlaybackIntent()
    private var policy = PlaybackPolicy()
    private var marks = SkipMarks()
    private var marksAsked = false
    private var completedEpisode: Int?
    private var interruptionPaused = false
    private var mediaControls: PlaybackMediaControls?
    private var togetherAdapter: PlaybackTogetherAdapter?

    init(anime: Anime, episode: Int, model: AppModel) {
        self.anime = anime; self.episode = episode; self.model = model; account = model.accountKey
        selectedQuality = model.preferredQuality
        speed = model.preferences.playbackSpeed
        player.allowsExternalPlayback = false
        player.defaultRate = Float(model.preferences.playbackSpeed)
        player.audiovisualBackgroundPlaybackPolicy = model.preferences.backgroundPlayback ? .continuesIfPossible : .automatic
        timer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        statusObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor in self?.statusChanged(status) }
        }
        observe(.AVPlayerItemDidPlayToEndTime, reading: { PlaybackModel.item($0) }) { playback, item in
            guard let item, playback.currentItemID == item else { return }
            playback.tick(ended: true); playback.save()
        }
        observe(.AVPlayerItemTimeJumped, reading: { PlaybackModel.item($0) }) { playback, item in
            guard let item, playback.currentItemID == item, !playback.restoring else { return }
            playback.policy.didSeek(to: playback.safePosition)
            // Told to the friend only when a person did it. AVKit reports a jump for reasons of
            // its own as well — a stall it recovered from, a segment boundary, the seek the
            // session itself just asked for — and every one of those used to go out as a `seek`,
            // land on the other phone as «перемотал на», and come back as a correction. Anything
            // within a few seconds of a quiet seek is that seek, not a new one.
            guard !playback.seeking, Date().timeIntervalSince(playback.quietSeekAt) > 3 else { return }
            playback.onLocalAction?(.seek(playback.safePosition))
        }
        observe(AVAudioSession.interruptionNotification, reading: { AudioInterruption($0) }) { playback, interruption in
            playback.handleInterruption(interruption)
        }
        observe(AVAudioSession.routeChangeNotification,
                reading: { $0.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt }) { playback, reason in
            guard reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
            playback.setPlaying(false)
        }
    }

    func start() async {
        // A model that has been closed cannot open anything, and saying so beats a spinner that
        // never stops: the screen keeps its «Повторить», and whoever is watching learns that
        // something went wrong rather than that the episode is slow.
        if closed, loading {
            fail("Плеер закрылся до того, как серия открылась. Откройте её заново.")
            return
        }
        guard !started, !closed else { return }; started = true
        do {
            try activateAudio()
            mediaControls = PlaybackMediaControls(playback: self)
            let target = model.continueTarget(for: anime)
            let start = target.episode == episode && target.rewatch ? 0 : resumePosition(for: episode)
            await resolve(position: start, play: true)
            togetherAdapter = PlaybackTogetherAdapter(playback: self, manager: model.together)
        } catch { fail(error.localizedDescription) }
    }
    private func activateAudio() throws {
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try AVAudioSession.sharedInstance().setActive(true)
    }
    private func resumePosition(for episode: Int) -> Double {
        guard let progress = model.progressFor(animeID: anime.id, episode: episode) else { return 0 }
        return PlaybackPolicy.resume(position: progress.position, duration: progress.duration, threshold: model.preferences.watchedThreshold)
    }
    func selectEpisode(_ value: Int, position: Double? = nil, play: Bool = true, notify: Bool = true) {
        guard !closed, value > 0, value <= episodeCount, value != episode else { return }
        save(); policy.resetEpisode(); completedEpisode = nil
        episode = value; retried = false; finished = false
        if notify { onLocalAction?(.episode(value)) }
        beginResolve(position: position ?? resumePosition(for: value), play: play)
    }
    func selectTranslation(_ value: Int) {
        guard !loading, let choice = translations.first(where: { $0.id == value }), choice.episodes == 0 || choice.episodes >= episode else { return }
        save(); retried = false
        beginResolve(position: safePosition, play: intent.wantsPlayback, explicitTranslation: value)
    }
    func selectQuality(_ value: Int, remember: Bool = true) {
        guard !loading, !isLocal, let stream, value == 0 || qualities.contains(value) else { return }
        save(); let position = safePosition
        selectedQuality = value
        if remember { model.preferredQuality = value; model.savePreferences() }
        let fence = beginTransition(position: position, play: intent.wantsPlayback)
        install(stream, position: position, fence: fence)
    }
    func setSpeed(_ value: Double, remember: Bool = true, notify: Bool = true) {
        guard value.isFinite else { return }
        let speed = min(2, max(0.5, value))
        if remember { model.preferences.playbackSpeed = speed; model.savePreferences() }
        self.speed = speed
        player.defaultRate = Float(speed)
        if player.rate > 0 { player.rate = Float(speed) }
        if notify { onLocalAction?(.speed(speed)) }
        updateMediaControls()
    }
    func setAutoNext(_ value: Bool) { model.autoNext = value; model.savePreferences(); tick() }
    func setAutoSkipEnding(_ value: Bool) { model.preferences.autoSkipEnding = value; model.savePreferences() }
    func setBackgroundPlayback(_ value: Bool) {
        model.preferences.backgroundPlayback = value; model.savePreferences()
        player.audiovisualBackgroundPlaybackPolicy = value ? .continuesIfPossible : .automatic
        if !sceneActive { suspend() }
    }
    func setPiPOnLeave(_ value: Bool) { model.preferences.pipOnLeave = value; model.savePreferences() }
    func setPlaying(_ value: Bool, notify: Bool = true) {
        guard !closed else { return }
        intent.userSetPlaying(value)
        if !notify { quietPlayingChangeAt = Date() }
        if value {
            do { try activateAudio() } catch { self.error = error.localizedDescription; return }
            if intent.shouldPlay, !loading, !restoring, !interruptionPaused { player.play() }
        } else { player.pause(); save() }
        if notify { onLocalAction?(.playing(value)) }
        updateMediaControls()
    }
    func seek(to value: Double, notify: Bool = true) {
        guard !closed, !loading, value.isFinite else { return }
        let target = PlaybackPolicy.clampSeek(value, duration: duration)
        completedEpisode = nil; finished = false
        policy.didSeek(to: target); seeking = true
        if !notify { quietSeekAt = Date() }
        let revision = UUID(), fence = request
        seekRevision = revision
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] success in
            Task { @MainActor in
                guard let self, self.request == fence, self.seekRevision == revision, !self.closed else { return }
                self.seeking = false
                if success { self.position = target; self.tick(); self.save() }
            }
        }
        if notify { onLocalAction?(.seek(target)) }
    }
    func seek(by seconds: Double) { seek(to: safePosition + seconds) }
    func skipCurrent() {
        guard let offer = marks.offer(position: safePosition, duration: duration) else { return }
        switch offer.kind {
        case .opening: seek(to: offer.interval.end)
        case .ending: if hasNext { finishEnding() }
        }
    }
    func nextNow() {
        guard hasNext else { return }
        if let ending = marks.accepted(duration: duration).ending, ending.contains(safePosition) { completeCurrentEpisode() }
        selectEpisode(episode + 1, position: 0)
    }
    func cancelAutoplay() { policy.cancelAutoplay(); tick() }
    func retry() { retried = false; beginResolve(position: requestedPosition, play: intent.wantsPlayback) }
    func downloadCurrent() {
        guard !isLocal, translation > 0 else { return }
        model.downloads.enqueue(anime: anime, episodes: [episode], translation: translation,
                                quality: selectedQuality,
                                translationTitle: translations.first { $0.id == translation }?.title)
    }
    func castCurrent() {
        guard translation > 0, !loading else { return }
        let position = safePosition
        model.cast.localPlayback = { [weak self] in
            guard let self else { return nil }
            return CastHandoff(selection: CastSelection(anime: self.anime, episode: self.episode,
                                                       translation: self.translation, quality: self.selectedQuality),
                               position: self.safePosition, shouldPlay: self.intent.wantsPlayback)
        }
        model.cast.onPauseLocal = { [weak self] in self?.setPlaying(false, notify: false) }
        Task {
            await model.cast.load(anime: anime, episode: episode, translation: translation,
                                  quality: selectedQuality, position: position, autoplay: intent.wantsPlayback)
        }
    }
    /// A fifth of the volume while somebody talks over the episode, and all of it back after.
    /// The same figure as Android's `ExoPlaybackEngine.DUCKED_VOLUME`, and set explicitly because
    /// the system ducks other apps rather than this one against itself.
    func setDucked(_ on: Bool) { player.volume = on ? 0.2 : 1 }
    func setSynchronizationControlled(_ value: Bool) { synchronizationControlled = value }
    func applySynchronization(position: Double, isPlaying: Bool, speed: Double? = nil) {
        if let speed { setSpeed(speed, remember: false, notify: false) }
        seek(to: position, notify: false); setPlaying(isPlaying, notify: false)
    }
    func suspend() {
        sceneActive = false; save()
        intent.suspend(backgroundAllowed: backgroundPlayback, pictureInPicture: pictureInPicture)
        // With automatic PiP enabled, AVFoundation must be allowed to complete its handoff.
        // Its .automatic policy pauses video when no PiP starts and background audio is off.
        if !intent.shouldPlay, !pipOnLeave { player.pause() }
    }
    func becameActive() {
        sceneActive = true
        let wasSuspended = intent.suspended
        intent.activate()
        if wasSuspended, intent.shouldPlay, !loading, !interruptionPaused { player.play() }
    }
    func setPictureInPicture(_ active: Bool) {
        pictureInPicture = active
        if !sceneActive {
            intent.suspend(backgroundAllowed: backgroundPlayback, pictureInPicture: active)
            if intent.shouldPlay, !loading, !interruptionPaused { player.play() }
            else if !intent.shouldPlay { player.pause() }
        }
    }
    func close() {
        guard !closed else { return }
        save(); closed = true; request = UUID()
        loadTask?.cancel(); timeoutTask?.cancel(); marksTask?.cancel(); stallTask?.cancel()
        player.pause(); itemObservation = nil; statusObservation = nil
        if let timer { player.removeTimeObserver(timer); self.timer = nil }
        observations.forEach { NotificationCenter.default.removeObserver($0) }; observations = []
        mediaControls?.close(); mediaControls = nil
        togetherAdapter?.close(); togetherAdapter = nil
        player.replaceCurrentItem(with: nil)
        model.downloads.endPlayback()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private var safePosition: Double {
        guard installedEpisode == episode, !restoring else { return requestedPosition }
        let current = player.currentTime().seconds
        return current.isFinite && current >= 0 ? current : position
    }
    private func statusChanged(_ status: AVPlayer.TimeControlStatus) {
        guard !closed, player.timeControlStatus == status else { return }
        isPlaying = status == .playing
        if !restoring, !loading, !seeking, !intent.suspended, !interruptionPaused, player.currentItem?.status == .readyToPlay {
            // A pause at EOF is engine state, not cancellation of an automatic next episode.
            let atEnd = duration > 0 && safePosition >= duration - 0.1
            if status == .playing || (status == .paused && !atEnd) {
                let playing = status == .playing
                // A player that disagrees with what was asked of it is read as the viewer having
                // used AVKit's own button — that is how a press on the transport bar reaches the
                // friend. But not in the seconds after the session itself paused or started this
                // player: the engine catches up with such a request a beat later, and reading
                // that beat as a person pressing pause sent the friend a `pause` nobody pressed.
                let settling = Date().timeIntervalSince(quietPlayingChangeAt) < 2
                if playing != intent.wantsPlayback, !settling {
                    intent.userSetPlaying(playing); onLocalAction?(.playing(playing))
                }
            }
        }
        if isPlaying, !beganLibraryPlayback, account == model.accountKey {
            beganLibraryPlayback = true; model.beginPlayback(anime: anime)
        }
        if status == .paused { save() }
        updateMediaControls()
    }
    private func tick(ended: Bool = false) {
        guard !closed, !restoring, !loading, !seeking, installedEpisode == episode, let item = player.currentItem, item.status == .readyToPlay else { return }
        let current = player.currentTime().seconds, length = item.duration.seconds
        guard current.isFinite, current >= 0 else { return }
        position = current
        if length.isFinite, length > 0 { duration = length }
        isPlaying = player.timeControlStatus == .playing
        guard duration > 0 else { return }
        if !marksAsked { askForMarks() }
        skipOffer = marks.offer(position: position, duration: duration)
        if skipOffer?.kind == .ending && !hasNext { skipOffer = nil }
        nextEpisode = policy.next(position: position, duration: duration, hasNext: hasNext,
                                  autoNext: autoNext && !synchronizationControlled, ended: ended)
        if nextEpisode.countdown != nil, skipOffer?.kind == .ending { skipOffer = nil }
        if abs(position - lastSavedPosition) >= 5 || ended { save() }
        updateMediaControls()
        if !synchronizationControlled,
           policy.automaticSkip(marks: marks, position: position, duration: duration, playing: isPlaying,
                                ending: autoSkipEnding && episodeCount > 0) == .finishEnding {
            finishEnding(); return
        }
        if nextEpisode.advance { nextNow() }
    }
    private func completeCurrentEpisode() { completedEpisode = episode; save() }
    private func finishEnding() {
        completeCurrentEpisode()
        if hasNext { selectEpisode(episode + 1, position: 0) }
        else { finished = true; setPlaying(false) }
    }
    private func save() {
        guard !closed, !restoring, !seeking, let installedEpisode, let item = player.currentItem else { return }
        let current = player.currentTime().seconds, length = item.duration.seconds
        guard current.isFinite, current >= 0, length.isFinite, length > 0 else { return }
        lastSavedPosition = current
        model.saveProgress(EpisodeProgress(animeID: anime.id, episode: installedEpisode,
                           position: completedEpisode == installedEpisode ? length : current, duration: length), anime: anime, account: account)
    }
    private func beginResolve(position: Double, play: Bool, explicitTranslation: Int? = nil) {
        loadTask?.cancel()
        // Fence and freeze synchronously before a deferred task can save the old item as a new one.
        let fence = beginTransition(position: position, play: play)
        loadTask = Task { await resolve(position: position, play: play, explicitTranslation: explicitTranslation, fence: fence) }
    }
    @discardableResult private func beginTransition(position: Double, play: Bool) -> UUID {
        request = UUID(); loading = true; restoring = true; seeking = false
        error = nil; requestedPosition = max(0, position); self.position = requestedPosition; duration = 0
        intent.userSetPlaying(play); player.pause()
        timeoutTask?.cancel(); marksTask?.cancel(); itemObservation = nil; readyItem = nil
        player.replaceCurrentItem(with: nil)
        marksAsked = false; marks = SkipMarks(); skipOffer = nil; nextEpisode = NextEpisodeState()
        policy.didSeek(to: requestedPosition); lastSavedPosition = -1
        // Behind every other deadline: whatever goes wrong — a request cancelled and never
        // replaced, a model closed under the screen's feet — «Открываем серию…» has to become
        // something a person can act on.
        let fence = request
        stallTask?.cancel()
        stallTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(40))
            guard !Task.isCancelled, let self, self.loading, self.error == nil, self.request == fence else { return }
            TogetherLog.write("stalled: still loading 40s after the request began")
            self.fail("Серия не открылась. Попробуйте ещё раз.")
        }
        return request
    }
    private func resolve(position: Double, play: Bool, explicitTranslation: Int? = nil, fence suppliedFence: UUID? = nil) async {
        let fence = suppliedFence ?? beginTransition(position: position, play: play)
        guard isCurrent(fence) else { return }
        model.downloads.beginPlayback(animeID: anime.id, episode: episode)
        // Deliberately precedes ALL network work, including the translation catalogue.
        let downloaded = model.downloads.entries.filter {
            $0.anime.id == anime.id && $0.episode == episode && $0.state == .completed
        }
        let downloadedTracks = downloaded.map { row in
            translations.first(where: { $0.id == row.translation }) ?? Translation(id: row.translation, title: "", episodes: episode)
        }
        let preferredLocal = explicitTranslation ?? model.preferredTranslation(for: anime.id, available: downloadedTracks, episode: episode)
        let candidates = explicitTranslation.map { id in [id] } ?? ([preferredLocal] + downloaded.map(\.translation))
        for candidate in candidates where candidate > 0 {
            guard let local = model.downloads.localAsset(animeID: anime.id, episode: episode, translation: candidate),
                  let entry = downloaded.first(where: { row in
                      guard let path = row.relativePath else { return false }
                      return URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent(path).standardizedFileURL == local.standardizedFileURL
                  }) else { continue }
            isLocal = true; stream = nil; qualities = []; quality = entry.quality; translation = entry.translation
            if explicitTranslation == entry.translation { model.rememberTranslation(entry.translation, for: anime.id) }
            install(url: local, headers: [:], position: position, fence: fence)
            return
        }
        isLocal = false
        TogetherLog.write("resolve start anime=\(anime.id) episode=\(episode) translation=\(explicitTranslation ?? 0)")
        do {
            if translations.isEmpty {
                let available = try await deadline(20, "Список озвучек") { [model, anime] in
                    try await model.service.translations(anime.id)
                }
                guard isCurrent(fence) else { return }
                translations = available
            }
            let selected = explicitTranslation ?? model.preferredTranslation(for: anime.id, available: translations, episode: episode)
            guard selected > 0 else { throw AppError.message("Для этой серии нет доступной озвучки.") }
            // The chain behind this is four requests to somebody else's player: a token, a page,
            // a decode and a playlist. Any of them can simply never answer, and «Открываем
            // серию…» with nothing behind it is the worst thing a player can show — it looks
            // exactly like an episode that is about to start.
            TogetherLog.write("resolve: asking the source for translation=\(selected)")
            let result = try await deadline(25, "Источник") { [model, anime, episode] in
                try await model.service.resolve(anime.id, translation: selected, episode: episode)
            }
            TogetherLog.write("resolve: source answered with \(result.urls.count) qualities")
            guard isCurrent(fence) else { return }
            guard result.episode == episode else { throw AppError.message("Источник вернул другую серию.") }
            stream = result; translation = result.translation.id
            qualities = result.urls.map(\.quality).filter { $0 > 0 }.sorted(by: >)
            if let explicitTranslation, result.translation.id == explicitTranslation { model.rememberTranslation(explicitTranslation, for: anime.id) }
            install(result, position: position, fence: fence)
        } catch {
            TogetherLog.write("resolve failed: \(error.localizedDescription)")
            guard isCurrent(fence) else { return }
            fail(error.localizedDescription)
        }
    }
    private func isCurrent(_ fence: UUID) -> Bool { fence == request && !closed && !Task.isCancelled && account == model.accountKey }
    /// Whichever finishes first: the work, or the wait. Named, because «не ответил вовремя» about
    /// nothing in particular tells somebody neither what failed nor whether to try again.
    private func deadline<T: Sendable>(_ seconds: Double, _ what: String,
                                       _ operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(for: .seconds(seconds))
                throw AppError.message("\(what) не ответил вовремя. Попробуйте ещё раз.")
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else { throw AppError.message("\(what) не ответил.") }
            return first
        }
    }
    private func install(_ stream: Stream, position: Double, fence: UUID) {
        guard let chosen = PlaybackPolicy.quality(preferred: selectedQuality, available: stream.urls.map(\.quality)),
              let selected = stream.urls.first(where: { $0.quality == chosen }),
              let url = URL(string: selected.url), ["https", "http"].contains(url.scheme) else {
            fail("Для этой серии нет доступного видео."); return
        }
        quality = chosen
        install(url: url, headers: stream.headers, position: position, fence: fence)
    }
    private func install(url: URL, headers: [String: String], position: Double, fence: UUID) {
        TogetherLog.write("install \(url.host ?? "?") position=\(Int(position))s")
        // Existing app's measured header propagation covers HLS manifests and segments.
        let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        installedEpisode = episode
        itemObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            Task { @MainActor in await self?.itemChanged(item, position: position, fence: fence) }
        }
        player.replaceCurrentItem(with: item)
        timeoutTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(30)) } catch { return }
            guard let self, self.isCurrent(fence), self.loading else { return }
            self.request = UUID(); self.player.currentItem?.cancelPendingSeeks()
            self.fail("Видео не ответило вовремя. Попробуйте ещё раз.")
        }
    }
    private func itemChanged(_ item: AVPlayerItem, position: Double, fence: UUID) async {
        guard isCurrent(fence), player.currentItem === item else { return }
        switch item.status {
        case .readyToPlay:
            guard readyItem !== item else { return }; readyItem = item
            TogetherLog.write("player ready")
            let length = item.duration.seconds
            let target = PlaybackPolicy.clampSeek(position, duration: length.isFinite ? max(0, length - 0.1) : length)
            let success = await player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            guard isCurrent(fence), player.currentItem === item else { return }
            timeoutTask?.cancel(); restoring = false; loading = false
            if !success { fail("Не удалось восстановить позицию видео."); return }
            self.position = target; requestedPosition = target
            if intent.shouldPlay, !interruptionPaused { player.play() }
            tick()
        case .failed:
            timeoutTask?.cancel()
            if !isLocal, !retried {
                retried = true
                beginResolve(position: safePosition, play: intent.wantsPlayback)
            } else {
                fail(isLocal ? "Не удалось открыть скачанную серию. Проверьте файл в загрузках." : (item.error?.localizedDescription ?? "Не удалось воспроизвести видео."))
            }
        default: break
        }
    }
    private func askForMarks() {
        marksAsked = true
        let fence = request, episode = episode, duration = duration
        marksTask = Task { [weak self] in
            guard let self else { return }
            do {
                let marks = try await AniSkipClient.shared.marks(animeID: self.anime.id, episode: episode, duration: duration)
                guard self.isCurrent(fence) else { return }
                self.marks = marks.accepted(duration: duration)
            } catch { /* Optional metadata; cancellation and offline playback need no error UI. */ }
        }
    }
    private func fail(_ message: String) {
        loading = false; restoring = false; timeoutTask?.cancel()
        error = message; player.pause(); policy.cancelAutoplay()
    }
    /// One notification, read into a value and then handled on the main actor.
    ///
    /// `queue: .main` is the guarantee the rest of this rests on: the block is delivered on the
    /// main thread, so the work is done in place rather than hopped onto a task. That means an
    /// interruption is dealt with before the next notification arrives instead of some turns
    /// later — and it is what lets `reading` run where the notification is.
    ///
    /// A `Notification` is not `Sendable`, and it should not be: its `object` is whatever posted
    /// it and its `userInfo` is a dictionary of anything. So it never leaves this block. What the
    /// handler gets is the two or three numbers the app actually wanted out of it — or, for a
    /// player item, its identity, which is all the two item notifications ever ask about.
    private func observe<Value: Sendable>(_ name: Notification.Name,
                                          reading: @escaping @Sendable (Notification) -> Value,
                                          handler: @escaping @Sendable @MainActor (PlaybackModel, Value) -> Void) {
        observations.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] notification in
            let value = reading(notification)
            MainActor.assumeIsolated {
                guard let self, !self.closed else { return }
                handler(self, value)
            }
        })
    }

    /// Which item a player-item notification is about, as an identity rather than as the object.
    private nonisolated static func item(_ notification: Notification) -> ObjectIdentifier? {
        (notification.object as? AVPlayerItem).map(ObjectIdentifier.init)
    }
    private var currentItemID: ObjectIdentifier? { player.currentItem.map(ObjectIdentifier.init) }
    private func handleInterruption(_ interruption: AudioInterruption) {
        guard let type = interruption.type else { return }
        if type == .began { interruptionPaused = true; save(); player.pause() }
        else {
            interruptionPaused = false
            if interruption.options.contains(.shouldResume), intent.shouldPlay, !loading {
                do { try activateAudio(); player.play() } catch { self.error = error.localizedDescription }
            }
        }
    }
    private func updateMediaControls() { mediaControls?.update(snapshot: snapshot, title: anime.title, skipSeconds: skipSeconds, hasNext: hasNext) }
}

/// What an audio-interruption notification says, as the two flags this app reads out of one.
///
/// A value rather than the notification, so the answer can cross onto the main actor: the call has
/// come in, or it has ended and the system is saying whether to pick the episode back up.
private struct AudioInterruption: Sendable {
    var type: AVAudioSession.InterruptionType?
    var options: AVAudioSession.InterruptionOptions

    init(_ notification: Notification) {
        type = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
            .flatMap(AVAudioSession.InterruptionType.init(rawValue:))
        options = (notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt)
            .map(AVAudioSession.InterruptionOptions.init(rawValue:)) ?? []
    }
}
