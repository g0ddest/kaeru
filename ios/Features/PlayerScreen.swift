import SwiftUI
import AVKit
import Observation

@MainActor @Observable final class PlaybackModel {
    let player = AVPlayer()
    let anime: Anime
    private let model: AppModel
    private let account: String
    private(set) var episode: Int
    private(set) var translation = 0
    private(set) var quality = 720
    private(set) var translations: [Translation] = []
    private(set) var qualities: [Int] = []
    private(set) var loading = true
    var error: String?
    private var stream: Stream?
    private var request = UUID()
    private var itemObservation: NSKeyValueObservation?
    private var rateObservation: NSKeyValueObservation?
    private var timer: Any?
    private var endObservation: NSObjectProtocol?
    private var loadTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?
    private var installedEpisode: Int?
    private var restoring = false
    private var retried = false
    private var closed = false
    private var lastPosition = 0.0
    private var requestedPosition = 0.0
    private var sceneActive = true
    private var wantsPlayback = true
    var episodeCount: Int {
        let count = translations.first(where: { $0.id == translation })?.episodes ?? anime.availableEpisodes
        return min(anime.availableEpisodes, count > 0 ? count : anime.availableEpisodes)
    }
    init(anime: Anime, episode: Int, model: AppModel) {
        self.anime = anime; self.episode = episode; self.model = model; self.account = model.accountKey
        quality = model.preferredQuality; translation = model.preferredTranslation
        player.allowsExternalPlayback = false
        timer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.save() }
        }
        rateObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor in
                guard let self, self.player.timeControlStatus == status else { return }
                if !self.restoring, self.player.currentItem?.status != .failed {
                    if status == .paused { self.wantsPlayback = false }
                    else if status == .playing { self.wantsPlayback = true }
                }
                if status == .paused { self.save() }
            }
        }
        endObservation = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] notification in
            Task { @MainActor in
                guard let self, let item = notification.object as? AVPlayerItem, self.player.currentItem === item else { return }
                self.save()
                if self.model.autoNext && self.episode < self.episodeCount { self.selectEpisode(self.episode + 1) }
            }
        }
    }
    func start() async {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
            translations = try await model.service.translations(anime.id)
            guard !closed else { return }
            if !translations.contains(where: { $0.id == translation }) { translation = translations.first?.id ?? 0 }
            let progress = model.progress[anime.id]
            let position = progress?.episode == episode && progress?.watched == false ? progress!.position : 0
            await resolve(position: position, play: wantsPlayback)
        } catch is CancellationError {} catch { self.error = error.localizedDescription; loading = false }
    }
    func selectEpisode(_ value: Int) {
        guard (1...max(1, episodeCount)).contains(value) else { return }
        save(); player.pause(); episode = value; retried = false
        beginResolve(position: 0, play: true)
    }
    func selectTranslation(_ value: Int) {
        save()
        let position = safePosition
        let play = player.timeControlStatus != .paused
        player.pause(); translation = value; retried = false
        model.preferredTranslation = value; model.savePreferences()
        beginResolve(position: position, play: play)
    }
    func selectQuality(_ value: Int) {
        guard let stream else { return }
        let position = safePosition, play = player.timeControlStatus != .paused
        save(); quality = value; model.preferredQuality = value; model.savePreferences()
        wantsPlayback = play && sceneActive
        request = UUID(); install(stream, position: position, play: play, fence: request)
    }
    func retry() { retried = false; beginResolve(position: requestedPosition, play: true) }
    func suspend() { sceneActive = false; wantsPlayback = false; save(); player.pause() }
    func becameActive() { sceneActive = true }
    func close() {
        guard !closed else { return }
        save(); closed = true; request = UUID(); loadTask?.cancel(); timeoutTask?.cancel(); player.pause()
        itemObservation = nil; rateObservation = nil
        if let timer { player.removeTimeObserver(timer); self.timer = nil }
        if let endObservation { NotificationCenter.default.removeObserver(endObservation); self.endObservation = nil }
        player.replaceCurrentItem(with: nil)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    private var safePosition: Double {
        guard installedEpisode == episode else { return requestedPosition }
        let current = player.currentTime().seconds
        return current.isFinite && current > 0 ? current : lastPosition
    }
    private func save() {
        guard !closed, !restoring, let installedEpisode, let item = player.currentItem else { return }
        let position = player.currentTime().seconds, duration = item.duration.seconds
        guard position.isFinite, duration.isFinite, duration > 0 else { return }
        lastPosition = position
        model.saveProgress(EpisodeProgress(animeID: anime.id, episode: installedEpisode, position: position, duration: duration), anime: anime, account: account)
    }
    private func beginResolve(position: Double, play: Bool) {
        loadTask?.cancel()
        loadTask = Task { await resolve(position: position, play: play) }
    }
    private func resolve(position: Double, play: Bool) async {
        let fence = UUID(); request = fence; loading = true; error = nil
        requestedPosition = position; lastPosition = position; wantsPlayback = play && sceneActive
        player.pause(); restoring = true
        do {
            let result = try await model.service.resolve(anime.id, translation: translation, episode: episode)
            guard fence == request, !closed, !Task.isCancelled else { return }
            stream = result; translation = result.translation.id; episode = result.episode
            qualities = result.urls.map(\.quality).sorted(by: >)
            install(result, position: position, play: play, fence: fence)
        } catch is CancellationError {} catch {
            guard fence == request, !closed else { return }
            self.error = error.localizedDescription; loading = false; restoring = false
        }
    }
    private func install(_ stream: Stream, position: Double, play: Bool, fence: UUID) {
        guard let selected = stream.urls.first(where: { $0.quality == quality }) ?? stream.urls.filter({ $0.quality <= quality }).max(by: { $0.quality < $1.quality }) ?? stream.urls.min(by: { $0.quality < $1.quality }),
              let url = URL(string: selected.url), ["https", "http"].contains(url.scheme) else {
            error = "Для этой серии нет доступного видео."; loading = false; restoring = false; return
        }
        quality = selected.quality; loading = true; restoring = true; lastPosition = position
        timeoutTask?.cancel()
        timeoutTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(30)) } catch { return }
            guard let self, self.request == fence, self.loading, !self.closed else { return }
            self.request = UUID()
            self.player.currentItem?.cancelPendingSeeks()
            self.player.pause(); self.restoring = false; self.loading = false
            self.error = "Видео не ответило вовремя. Проверьте соединение и попробуйте ещё раз."
        }
        // Header propagation was measured by the iOS spike for manifests and segments.
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": stream.headers])
        let item = AVPlayerItem(asset: asset)
        itemObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, self.request == fence, !self.closed else { return }
                switch item.status {
                case .readyToPlay:
                    let duration = item.duration.seconds
                    let target = duration.isFinite && duration > 0 ? min(position, max(0, duration - 1)) : position
                    let seeked = await self.player.seek(to: CMTime(seconds: max(0, target), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                    guard self.request == fence, !self.closed else { return }
                    self.timeoutTask?.cancel()
                    self.restoring = false; self.loading = false
                    if !seeked { self.error = "Не удалось восстановить позицию видео." }
                    if seeked && play && self.wantsPlayback && self.sceneActive { self.player.play() }
                case .failed:
                    self.loading = false; self.restoring = false
                    if !self.retried {
                        self.retried = true
                        self.beginResolve(position: self.safePosition, play: self.wantsPlayback && self.sceneActive)
                    } else { self.error = item.error?.localizedDescription ?? "Не удалось воспроизвести видео." }
                default: break
                }
            }
        }
        installedEpisode = stream.episode
        player.replaceCurrentItem(with: item)
    }
}

struct NativePlayer: UIViewControllerRepresentable {
    let player: AVPlayer
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = false
        return controller
    }
    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) { controller.player = player }
}

struct PlayerScreen: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var playback: PlaybackModel
    init(anime: Anime, episode: Int, model: AppModel) { _playback = State(initialValue: PlaybackModel(anime: anime, episode: episode, model: model)) }
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                NativePlayer(player: playback.player)
                if playback.loading { ProgressView("Открываем серию…").padding(20).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14)) }
                if let error = playback.error {
                    ContentUnavailableView {
                        Label("Видео недоступно", systemImage: "play.slash")
                    } description: { Text(error) } actions: { Button("Повторить") { playback.retry() }.buttonStyle(.bordered) }
                    .background(.black.opacity(0.8))
                }
            }
            .navigationTitle("Серия \(playback.episode)").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Готово") { playback.close(); dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Section(playback.anime.title) {
                            Menu("Серия") {
                                ForEach(1...max(1, playback.episodeCount), id: \.self) { value in
                                    Button { playback.selectEpisode(value) } label: { menuLabel("Серия \(value)", selected: value == playback.episode) }
                                }
                            }
                            Menu("Озвучка") {
                                ForEach(playback.translations) { value in
                                    Button { playback.selectTranslation(value.id) } label: { menuLabel(value.title, selected: value.id == playback.translation) }
                                        .disabled(value.episodes > 0 && value.episodes < playback.episode)
                                }
                            }
                            Menu("Качество") {
                                ForEach(playback.qualities, id: \.self) { value in
                                    Button { playback.selectQuality(value) } label: { menuLabel("\(value)p", selected: value == playback.quality) }
                                }
                            }
                        }
                    } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("Серия, озвучка и качество").disabled(playback.loading)
                }
            }
            .toolbarBackground(.visible, for: .navigationBar)
        }
        .preferredColorScheme(.dark)
        .task { await playback.start() }
        .onDisappear { playback.close() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { playback.becameActive() } else { playback.suspend() } }
    }
    @ViewBuilder private func menuLabel(_ text: String, selected: Bool) -> some View { if selected { Label(text, systemImage: "checkmark") } else { Text(text) } }
}
