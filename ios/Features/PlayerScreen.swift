import SwiftUI

struct PlayerScreen: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var playback: PlaybackModel
    /// Held so this screen can say it is on screen: a deep link that arrives now must not open a
    /// second player over this one.
    private let model: AppModel
    /// Which way the last double tap went, while its label is still up.
    @State private var seekHinted: PlayerTapZone?
    @State private var hintRevision = 0
    init(anime: Anime, episode: Int, model: AppModel) {
        self.model = model
        _playback = State(initialValue: PlaybackModel(anime: anime, episode: episode, model: model))
    }
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                NativePlayer(playback: playback) { zone in seekFeedback(zone) }
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
            .overlay(alignment: .bottomTrailing) {
                if !playback.loading, playback.error == nil { offers }
            }
            .overlay { seekHint }
            // Verbatim throughout: an episode number is an ordinal, and a `Text` built from a
            // literal formats an `Int` argument in the device's locale — which turned the
            // 1118th episode of a long-running show into «Серия 1.118».
            .navigationTitle(Text(verbatim: "Серия \(playback.episode)"))
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
    /// What the native controls cannot say: that the episode is nearly over and the next one is
    /// about to start, and that the ending can be skipped.
    ///
    /// Offers, rather than a control panel. The panel that used to sit here was a second row of
    /// transport controls stacked under AVKit's own, which in full-screen landscape meant two of
    /// everything on one picture. Seeking is a double tap near an edge now, as on Android, and
    /// lives in the menu for anybody who would rather press a button.
    @ViewBuilder private var offers: some View {
        VStack(alignment: .trailing, spacing: 12) {
            if playback.finished && !playback.nextEpisode.offered {
                Text("Серия просмотрена").font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(.black.opacity(0.62), in: Capsule())
            }
            if playback.nextEpisode.offered {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 16) { nextEpisodeLabel; nextEpisodeButtons }
                    VStack(alignment: .leading, spacing: 12) { nextEpisodeLabel; nextEpisodeButtons }
                }
                .padding(14).frame(maxWidth: 420, alignment: .leading)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            skipButton
        }
        .padding(.horizontal, 20)
        // Clear of AVKit's transport bar, which owns the bottom of the picture.
        .padding(.bottom, 104)
        .animation(.easeInOut(duration: 0.2), value: playback.nextEpisode.offered)
    }
    /// «−10 с» / «+10 с» where the finger landed, for as long as it takes to read.
    @ViewBuilder private var seekHint: some View {
        if let hint = seekHinted {
            HStack {
                if hint == .forward { Spacer(minLength: 0) }
                Label(hint == .back ? "−\(playback.skipSeconds) с" : "+\(playback.skipSeconds) с",
                      systemImage: hint == .back ? "gobackward" : "goforward")
                    .font(.headline).monospacedDigit().foregroundStyle(.white)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(.black.opacity(0.55), in: Capsule())
                if hint == .back { Spacer(minLength: 0) }
            }
            .padding(.horizontal, 36)
            .allowsHitTesting(false)
            .transition(.opacity)
        }
    }
    private func seekFeedback(_ zone: PlayerTapZone) {
        withAnimation(.easeOut(duration: 0.12)) { seekHinted = zone }
        hintRevision += 1
        let revision = hintRevision
        Task {
            try? await Task.sleep(for: .milliseconds(700))
            guard revision == hintRevision else { return }
            withAnimation(.easeIn(duration: 0.2)) { seekHinted = nil }
        }
    }
    private var nextEpisodeLabel: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: "Серия \(playback.episode + 1)").font(.headline)
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
    @ViewBuilder private var skipButton: some View {
        if let offer = playback.skipOffer {
            Button(offer.kind == .opening ? "Пропустить опенинг" : "Следующая серия") { playback.skipCurrent() }
                .buttonStyle(.borderedProminent).controlSize(.large)
                .tint(.white).foregroundStyle(.black)
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
            Section("Перемотка") {
                Button { playback.seek(by: -Double(playback.skipSeconds)) } label: {
                    Label("Назад на \(playback.skipSeconds) с", systemImage: "gobackward")
                }.keyboardShortcut(.leftArrow, modifiers: [])
                Button { playback.seek(by: Double(playback.skipSeconds)) } label: {
                    Label("Вперёд на \(playback.skipSeconds) с", systemImage: "goforward")
                }.keyboardShortcut(.rightArrow, modifiers: [])
                // About the length of an opening, for anybody who would rather press once than
                // drag a timeline they cannot see the frames of.
                Button { playback.seek(by: 85) } label: { Label("Вперёд на 85 с", systemImage: "forward.end.alt") }
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
