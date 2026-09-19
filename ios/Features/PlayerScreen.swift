import SwiftUI

struct PlayerScreen: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var playback: PlaybackModel
    /// Held so this screen can say it is on screen: a deep link that arrives now must not open a
    /// second player over this one.
    private let model: AppModel
    init(anime: Anime, episode: Int, model: AppModel) {
        self.model = model
        _playback = State(initialValue: PlaybackModel(anime: anime, episode: episode, model: model))
    }
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                NativePlayer(playback: playback)
                if playback.loading {
                    ProgressView("Открываем серию…")
                        .padding(20).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
                }
                if let error = playback.error {
                    ContentUnavailableView {
                        Label("Видео недоступно", systemImage: "play.slash")
                    } description: { Text(error) } actions: {
                        Button("Повторить") { playback.retry() }.buttonStyle(.bordered)
                    }.background(.black.opacity(0.8))
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !playback.loading, playback.error == nil { playbackActions }
            }
            .navigationTitle("Серия \(playback.episode)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Готово") { playback.close(); dismiss() }
                }
                ToolbarItem(placement: .primaryAction) { options }
                ToolbarItem(placement: .topBarLeading) { CastButton(manager: playback.castManager) }
            }
            .toolbarBackground(.visible, for: .navigationBar)
        }
        .preferredColorScheme(.dark)
        .task { await playback.start() }
        .onAppear { model.playerAppeared() }
        .onDisappear {
            model.playerDisappeared()
            if !playback.pictureInPicture { playback.close() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { playback.becameActive() }
            // Inactive includes Control Center and the PiP transition. Pausing there interrupts
            // ordinary native controls and stops PiP before the system can start it.
            else if phase == .background { playback.suspend() }
        }
    }
    private var playbackActions: some View {
        VStack(spacing: 12) {
            if playback.finished { Text("Серия просмотрена").font(.headline) }
            if playback.nextEpisode.offered {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 16) { nextEpisodeLabel; nextEpisodeButtons }
                    VStack(alignment: .leading, spacing: 12) { nextEpisodeLabel; nextEpisodeButtons }
                }
                .padding(14).frame(maxWidth: 560, alignment: .leading)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { seekButtons; skipButton }
                VStack(spacing: 12) { seekButtons; skipButton }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(.ultraThinMaterial)
    }
    private var nextEpisodeLabel: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Серия \(playback.episode + 1)").font(.headline)
            if let countdown = playback.nextEpisode.countdown {
                Text("Начнётся через \(countdown) с").font(.subheadline).monospacedDigit()
            } else { Text("Следующая серия").font(.subheadline).foregroundStyle(.secondary) }
        }.accessibilityElement(children: .combine)
    }
    private var nextEpisodeButtons: some View {
        HStack(spacing: 12) {
            Button("Смотреть") { playback.nextNow() }.buttonStyle(.borderedProminent)
            if playback.nextEpisode.countdown != nil {
                Button("Отмена") { playback.cancelAutoplay() }.buttonStyle(.bordered)
            }
        }.controlSize(.large)
    }
    private var seekButtons: some View {
        HStack(spacing: 12) {
            Button { playback.seek(by: -Double(playback.skipSeconds)) } label: {
                Label("−\(playback.skipSeconds) с", systemImage: "gobackward")
            }.accessibilityLabel("Назад на \(playback.skipSeconds) секунд")
                .keyboardShortcut(.leftArrow, modifiers: [])
            Button { playback.seek(by: Double(playback.skipSeconds)) } label: {
                Label("+\(playback.skipSeconds) с", systemImage: "goforward")
            }.accessibilityLabel("Вперёд на \(playback.skipSeconds) секунд")
                .keyboardShortcut(.rightArrow, modifiers: [])
            Button("+85 с") { playback.seek(by: 85) }
                .accessibilityLabel("Вперёд на 85 секунд")
        }.buttonStyle(.bordered).controlSize(.large)
    }
    @ViewBuilder private var skipButton: some View {
        if let offer = playback.skipOffer {
            Button(offer.kind == .opening ? "Пропустить опенинг" : "Следующая серия") { playback.skipCurrent() }
                .buttonStyle(.borderedProminent).controlSize(.large)
        }
    }
    private var options: some View {
        Menu {
            Section(playback.anime.title) {
                Menu("Серия") {
                    ForEach(1...max(1, playback.episodeCount), id: \.self) { value in
                        Button { playback.selectEpisode(value) } label: { menuLabel("Серия \(value)", selected: value == playback.episode) }
                    }
                }.disabled(playback.episodeCount == 0)
                Menu("Озвучка") {
                    ForEach(playback.translations) { value in
                        Button { playback.selectTranslation(value.id) } label: { menuLabel(value.title, selected: value.id == playback.translation) }
                            .disabled(value.episodes > 0 && value.episodes < playback.episode)
                    }
                }.disabled(playback.translations.isEmpty)
                Menu("Качество") {
                    Button { playback.selectQuality(0) } label: { menuLabel("Авто", selected: playback.selectedQuality == 0) }
                    ForEach(playback.qualities, id: \.self) { value in
                        Button { playback.selectQuality(value) } label: { menuLabel("\(value)p", selected: value == playback.selectedQuality) }
                    }
                }.disabled(playback.isLocal || playback.qualities.isEmpty)
                Menu("Скорость") {
                    ForEach([0.5, 0.75, 1, 1.25, 1.5, 1.75, 2], id: \.self) { value in
                        Button { playback.setSpeed(value) } label: { menuLabel("\(value.formatted())×", selected: value == playback.speed) }
                    }
                }
            }
            Section {
                Toggle("Следующая серия автоматически", isOn: Binding(get: { playback.autoNext }, set: playback.setAutoNext))
                Toggle("Пропускать эндинг", isOn: Binding(get: { playback.autoSkipEnding }, set: playback.setAutoSkipEnding))
                Toggle("Картинка в картинке при выходе", isOn: Binding(get: { playback.pipOnLeave }, set: playback.setPiPOnLeave))
                Toggle("Фоновое воспроизведение", isOn: Binding(get: { playback.backgroundPlayback }, set: playback.setBackgroundPlayback))
            }
            Section {
                if playback.isLocal { Label("Скачанная серия", systemImage: "checkmark.circle") }
                else {
                    Button { playback.castCurrent() } label: { Label("Смотреть на Chromecast", systemImage: "tv") }
                        .disabled(playback.translation <= 0 || !playback.castManager.isConnected)
                    Button { playback.downloadCurrent() } label: { Label("Скачать серию", systemImage: "arrow.down.circle") }
                        .disabled(playback.translation <= 0)
                }
                if playback.hasNext { Button("Следующая серия") { playback.nextNow() } }
            }
        } label: { Image(systemName: "ellipsis.circle") }
        .accessibilityLabel("Настройки воспроизведения")
        .disabled(playback.loading)
    }
    @ViewBuilder private func menuLabel(_ text: String, selected: Bool) -> some View {
        if selected { Label(text, systemImage: "checkmark") } else { Text(text) }
    }
}
