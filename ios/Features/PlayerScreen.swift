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
    #if os(iOS)
    /// The invitation to hand somebody, the moment there is one to hand over.
    @State private var sharing: TogetherShare?
    #endif
    init(anime: Anime, episode: Int, model: AppModel) {
        self.model = model
        _playback = State(initialValue: PlaybackModel(anime: anime, episode: episode, model: model))
    }
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                NativePlayer(playback: playback, onDoubleTap: { zone in seekFeedback(zone) }) {
                    // Inside AVKit's content overlay rather than stacked over it, so all of this
                    // goes full screen with the picture instead of vanishing behind AVKit's own
                    // window the moment somebody expands the video. Only what it draws answers a
                    // touch; the space between its chips belongs to AVKit's own controls.
                    ZStack {
                        TogetherOverlay(manager: model.together)
                        if !playback.loading, playback.error == nil {
                            offers.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                        }
                        seekHint
                    }
                }
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
            // Verbatim throughout: an episode number is an ordinal, and a `Text` built from a
            // literal formats an `Int` argument in the device's locale — which turned the
            // 1118th episode of a long-running show into «Серия 1.118».
            .navigationTitle(Text(verbatim: "Серия \(playback.episode)"))
            #if os(macOS)
            // «Серия 7 — Название» in the title bar, the Window menu and Mission Control.
            .navigationSubtitle(playback.anime.title)
            #endif
            .kaeruTitleDisplay(.inline)
            .toolbar {
                // A Mac window closes itself — the red button, ⌘W — and that is this.
                #if os(iOS)
                ToolbarItem(placement: .cancellationAction) {
                    Button("Готово") { playback.close(); dismiss() }
                }
                #endif
                // Trailing, in reading order and ending in the menu: «Готово» owns the left of a
                // modal, and the rest sits where the Android client puts it — at the other end of
                // the bar. Watching with somebody is in it rather than in the «…» menu, because
                // it is decided here, on the episode, and a button nobody finds is a feature
                // nobody has. The slot that used to hold it stood empty until a session existed,
                // which took the title with it and left a hole beside «Готово».
                ToolbarItem(placement: .kaeruTrailing) { together }
                // Google Cast has no SDK for the Mac.
                #if os(iOS)
                ToolbarItem(placement: .kaeruTrailing) { CastButton(manager: playback.castManager) }
                #endif
                ToolbarItem(placement: .primaryAction) { options }
            }
            // No strip of its own: what is behind these controls is the picture, and AVKit's own
            // controls sit on nothing but a gradient. They go away together, too — see
            // `PlaybackModel.chromeVisible`.
            .kaeruBarBackground(.hidden)
            .kaeruBar(playback.chromeVisible ? .visible : .hidden)
            .animation(.easeInOut(duration: 0.25), value: playback.chromeVisible)
            .tint(.white)
            #if os(iOS)
            // A room nobody was invited to is a room for one. Android raises the share sheet the
            // moment the room exists; so does this.
            .sheet(item: $sharing) { ShareSheet(items: [$0.text]) }
            #endif
        }
        .preferredColorScheme(.dark)
        #if os(macOS)
        .playerWindowControls(playback: playback, typing: model.together.conversation.composing) { seekFeedback($0) }
        #endif
        .task { await playback.start() }
        .onAppear { Reporting.screen("player") }
        .onAppear { model.playerAppeared() }

        .onDisappear {
            model.playerDisappeared()
            #if os(iOS)
            if !playback.pictureInPicture { playback.close() }
            #else
            // On a phone the episode goes on in picture in picture after its screen has gone. On a
            // Mac the floating picture belongs to the player view that has just been taken down,
            // and a player left open would be a sound with nothing on screen to stop it.
            playback.close()
            #endif
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { playback.becameActive() }
            // Inactive includes Control Center and the PiP transition. Pausing there interrupts
            // ordinary native controls and stops PiP before the system can start it.
            else if phase == .background, OperatingSystem.suspendsAppsInBackground { playback.suspend() }
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
                Text("Серия просмотрена").font(.kaeruSubheadline.weight(.semibold)).foregroundStyle(.white)
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
                    .font(.kaeruHeadline).monospacedDigit().foregroundStyle(.white)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(.black.opacity(0.55), in: Capsule())
                if hint == .back { Spacer(minLength: 0) }
            }
            .padding(.horizontal, 36)
            .allowsHitTesting(false)
            .transition(.opacity)
        }
    }
    /// «Смотрим «…», 7 серию. Открой в Kaeru: …», or nothing while there is no room to share.
    private var invitation: String? {
        guard let link = model.together.invitation?.shareURL else { return nil }
        return TogetherCopy.shareText(title: playback.anime.title, episode: playback.episode,
                                      link: link.absoluteString)
    }
    private func seekFeedback(_ zone: PlayerTapZone) {
        withAnimation(.easeOut(duration: 0.12)) { seekHinted = zone }
        hintRevision += 1
        let revision = hintRevision
        Task {
            try? await Task.sleep(for: .milliseconds(900))
            guard revision == hintRevision else { return }
            withAnimation(.easeIn(duration: 0.2)) { seekHinted = nil }
        }
    }
    private var nextEpisodeLabel: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: "Серия \(playback.episode + 1)").font(.kaeruHeadline)
            if let countdown = playback.nextEpisode.countdown {
                Text("Начнётся через \(countdown) с").font(.kaeruSubheadline).monospacedDigit()
            } else { Text("Следующая серия").font(.kaeruSubheadline).foregroundStyle(.secondary) }
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
    /// An invitation when there is nobody, and who is there when there is.
    ///
    /// AVKit owns the transport bar and there is no supported way to put a button in it, so an
    /// opening is skipped by the offer that appears over the picture, by the timeline, or by a
    /// double tap — not by a menu item that repeats the ∓10 s the player already draws.
    @ViewBuilder private var together: some View {
        if TogetherCopy.sessionChip(phase: model.together.phase, peerName: model.together.peerName) != nil {
            TogetherChip(manager: model.together, invitation: invitation)
        } else {
            Button {
                Task {
                    await model.together.create()
                    guard let invitation else { return }
                    #if os(iOS)
                    sharing = TogetherShare(text: invitation)
                    #else
                    // A Mac has no share sheet to raise without a press, and a link on a Mac goes
                    // into a chat by way of the pasteboard anyway. So it is there already, and the
                    // picture says so; the chip's menu still offers Messages and Mail.
                    Pasteboard.copy(invitation)
                    model.together.conversation.message = TogetherCopy.invitationCopied
                    #endif
                }
            } label: {
                Image(systemName: "person.2")
            }
            .accessibilityLabel(TogetherCopy.watchTogether)
            .kaeruHelp(TogetherCopy.inviteHelp)
            .disabled(playback.loading || model.together.phase == .connecting)
        }
    }
    private var options: some View {
        Menu {
            PlayerOptionsMenu(playback: playback)
        } label: { Image(systemName: "ellipsis") }
        .accessibilityLabel("Настройки воспроизведения")
        .kaeruHelp("Настройки воспроизведения")
        .disabled(playback.loading)
    }
}

/// The episode's settings: which episode, voice, quality and speed, what happens by itself, and the
/// download. The «…» in the player's bar, and on a Mac the same again under «Воспроизведение».
struct PlayerOptionsMenu: View {
    let playback: PlaybackModel
    /// «Следующая серия» at the end. The menu bar has its own, with N beside it.
    var offersNext = true
    var body: some View {
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
            // Closures rather than bare method references: a reference to a main-actor method
            // is not a `@Sendable` function value, and `Binding`'s setter wants one.
            Toggle("Следующая серия автоматически", isOn: Binding(get: { playback.autoNext }, set: { playback.setAutoNext($0) }))
            Toggle("Пропускать эндинг", isOn: Binding(get: { playback.autoSkipEnding }, set: { playback.setAutoSkipEnding($0) }))
            // Neither means anything on a Mac: AVKit there cannot start picture in picture by
            // itself, and nothing suspends an app whose window is behind another.
            #if os(iOS)
            Toggle("Картинка в картинке при выходе", isOn: Binding(get: { playback.pipOnLeave }, set: { playback.setPiPOnLeave($0) }))
            Toggle("Фоновое воспроизведение", isOn: Binding(get: { playback.backgroundPlayback }, set: { playback.setBackgroundPlayback($0) }))
            #endif
        }
        Section {
            if playback.isLocal { Label("Скачанная серия", systemImage: "checkmark.circle") }
            else {
                Button { playback.downloadCurrent() } label: { Label("Скачать серию", systemImage: "arrow.down.circle") }
                    .disabled(playback.translation <= 0)
            }
            if offersNext, playback.hasNext { Button("Следующая серия") { playback.nextNow() } }
        }
    }
    @ViewBuilder private func menuLabel(_ text: String, selected: Bool) -> some View {
        if selected { Label(text, systemImage: "checkmark") } else { Text(text) }
    }
}
