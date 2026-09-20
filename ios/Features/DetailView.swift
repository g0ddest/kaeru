import SwiftUI

struct PlaybackRoute: Identifiable { let id = UUID(); var anime: Anime; var episode: Int }

struct DetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.horizontalSizeClass) private var sizeClass
    @ScaledMetric(relativeTo: .largeTitle) private var headerFloor = 280.0
    let initial: Anime
    @State private var details: Anime?
    @State private var route: PlaybackRoute?
    @State private var failure: String?
    @State private var loading = false
    @State private var revision = 0
    @State private var visibleEpisodes = 60
    @State private var descriptionExpanded = false
    @State private var translationsOpen = false
    @State private var unwatchEpisode: Int?
    @State private var detailRequest = UUID()
    private var anime: Anime { details?.id == initial.id ? details! : initial }
    private var rate: LibraryItem? { model.rate(for: anime.id) }
    private var target: ContinueTarget { model.continueTarget(for: anime) }
    private var playableEpisodes: Int { max(anime.availableEpisodes, rate?.episodes ?? 0) }
    private var totalEpisodes: Int { max(anime.episodes, playableEpisodes) }
    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    header(height: max(proxy.size.height * 0.38, headerFloor))
                    summary.padding(.horizontal, Metrics.gutter(sizeClass))
                    if loading { ProgressView("Обновляем информацию…").font(.footnote).padding(.horizontal, Metrics.gutter(sizeClass)) }
                    if let failure { CatalogRetry(message: failure) { revision += 1 }.padding(.horizontal, Metrics.gutter(sizeClass)) }
                    episodeSection.padding(.horizontal, Metrics.gutter(sizeClass))
                    if !anime.plainDescription.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Об аниме").font(.kaeruShelf(sizeClass != .regular)).foregroundStyle(Palette.ink)
                            Text(anime.plainDescription).foregroundStyle(Palette.inkSoft)
                                .lineLimit(descriptionExpanded ? nil : 4).textSelection(.enabled)
                            Button(descriptionExpanded ? "Свернуть" : "Читать полностью") { descriptionExpanded.toggle() }
                                .font(.subheadline.weight(.semibold)).tint(Palette.accent)
                        }.padding(.horizontal, Metrics.gutter(sizeClass))
                    }
                }
                .padding(.bottom, 32)
                .frame(maxWidth: Metrics.contentWidth).frame(maxWidth: .infinity)
            }
            .background(Palette.canvas)
        }
        .navigationTitle(anime.title).navigationBarTitleDisplayMode(.inline)
        .task(id: "\(initial.id)-\(revision)") { await loadDetails() }
        .refreshable { await loadDetails() }
        .safeAreaInset(edge: .bottom, spacing: 0) { EpisodeUndoBar() }
        .sheet(isPresented: $translationsOpen) { TranslationChooser(anime: anime) }
        .fullScreenCover(item: $route) { PlayerScreen(anime: $0.anime, episode: $0.episode, model: model) }
        .confirmationDialog("Отметить серию непросмотренной?", isPresented: Binding(get: { unwatchEpisode != nil }, set: { if !$0 { unwatchEpisode = nil } }), titleVisibility: .visible) {
            if let episode = unwatchEpisode {
                Button("Отметить непросмотренной", role: .destructive) {
                    model.markEpisode(anime: anime, episode: episode, watched: false)
                    unwatchEpisode = nil
                }
            }
            Button("Отмена", role: .cancel) { unwatchEpisode = nil }
        } message: {
            Text("Серия \(unwatchEpisode ?? 1) и все серии после неё станут непросмотренными. Их сохранённые позиции сбросятся. Это действие можно отменить.")
        }
        .modifier(CompletionSuggestionPresentation(enabled: route == nil))
    }
    /// The title, its artwork, and the one thing you came here to press.
    private func header(height: CGFloat) -> some View {
        // No Spacer here: inside a scroll view one grows to the whole proposed height and the
        // artwork swallows the screen. The frame below does the same job and stops where told.
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                Text(anime.title).font(.kaeruHero(sizeClass != .regular)).foregroundStyle(.white)
                    .lineLimit(3).minimumScaleFactor(0.7).fixedSize(horizontal: false, vertical: true)
                if !anime.originalTitle.isEmpty {
                    Text(anime.originalTitle).font(.subheadline).foregroundStyle(.white.opacity(0.75)).lineLimit(2)
                }
                Text(facts).font(.footnote).foregroundStyle(.white.opacity(0.85))
                    .lineLimit(3).fixedSize(horizontal: false, vertical: true)
                if target.canPlay {
                    Button { play(target.episode) } label: {
                        Label(playLabel, systemImage: "play.fill")
                            .font(.headline).lineLimit(1).minimumScaleFactor(0.8)
                            .padding(.horizontal, 22).padding(.vertical, 12)
                            .background(.white, in: Capsule()).foregroundStyle(.black)
                    }
                    .buttonStyle(.plain).accessibilityIdentifier("play-anime").padding(.top, 4)
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        Label(waitingLabel, systemImage: "calendar")
                        if let date = anime.nextAirDate { Text(date, format: .dateTime.day().month().hour().minute()) }
                    }
                    .font(.subheadline).foregroundStyle(.white.opacity(0.9)).padding(.top, 4)
                }
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(.horizontal, Metrics.gutter(sizeClass))
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, minHeight: height, alignment: .bottomLeading)
        .background { Backdrop(anime: anime) }
    }
    /// One line of what this title is, in the order a viewer asks: year, length, rating, kind,
    /// studio.
    private var facts: String {
        var parts = [airingStatus, anime.subtitle]
        if anime.status == "ongoing" { parts.append("вышло \(anime.episodesAired)") }
        if let kind = anime.kind, !kind.isEmpty { parts.append(kindTitle(kind)) }
        if let studios = anime.studios, !studios.isEmpty { parts.append(studios.joined(separator: ", ")) }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
    private var summary: some View {
        VStack(alignment: .leading, spacing: 14) {
            Button { translationsOpen = true } label: {
                Label(model.titleTranslations[anime.id] == nil ? "Выбрать озвучку" : "Изменить озвучку", systemImage: "waveform")
            }.buttonStyle(.bordered).tint(Palette.inkSoft).accessibilityIdentifier("detail-translations")
            if model.session != nil {
                LibraryStatusMenu(anime: anime).buttonStyle(.bordered).tint(Palette.inkSoft).controlSize(.large)
                if let rate {
                    Stepper("Просмотрено: \(rate.episodes)", value: Binding(get: { model.rate(for: anime.id)?.episodes ?? 0 }, set: { model.setEpisodes(anime: anime, count: $0) }), in: 0...max(totalEpisodes, rate.episodes))
                        .font(.subheadline).foregroundStyle(Palette.ink).accessibilityIdentifier("watched-episodes")
                }
            }
        }
    }
    @ViewBuilder private var episodeSection: some View {
        if totalEpisodes > 0 {
            VStack(alignment: .leading, spacing: 16) {
                Text("Серии").font(.kaeruShelf(sizeClass != .regular)).foregroundStyle(Palette.ink)
                Text(verbatim: "Просмотрено \(rate?.episodes ?? 0) из \(totalEpisodes)").font(.subheadline).foregroundStyle(Palette.inkSoft)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 200 : 125), spacing: 12)], spacing: 12) {
                    ForEach(1...min(visibleEpisodes, totalEpisodes), id: \.self) { episode in episodeTile(episode) }
                }
                if visibleEpisodes < totalEpisodes {
                    Button("Показать ещё \(min(60, totalEpisodes - visibleEpisodes)) серий") { visibleEpisodes += 60 }
                } else if totalEpisodes > 60 {
                    Button("Свернуть серии") { visibleEpisodes = 60 }
                }
            }
        } else { ContentUnavailableView("Серии ещё не вышли", systemImage: "calendar", description: Text("Добавьте аниме в планы, чтобы вернуться к нему позже.")) }
    }
    private func episodeTile(_ episode: Int) -> some View {
        let watched = episode <= (rate?.episodes ?? 0)
        let available = episode <= playableEpisodes
        let progress = model.progressFor(animeID: anime.id, episode: episode)
        // Three rows in every tile, always: title, track, caption. The grid gives a row the height
        // of its tallest tile, so a bar that appeared on some of them and not others left the rest
        // with a hole in the middle and their captions at different heights — a wall of episodes
        // that read as ragged rather than as a list.
        let fraction = episodeFraction(watched: watched, progress: progress)
        return Button { play(episode) } label: {
            VStack(alignment: .leading, spacing: 9) {
                Label("\(episode) серия", systemImage: watched ? "checkmark.circle.fill" : available ? "play.circle" : "clock")
                    .font(.subheadline.weight(.medium)).frame(maxWidth: .infinity, alignment: .leading)
                ProgressTrack(value: fraction, track: Palette.hairline, minimumFill: 0)
                    .opacity(available ? 1 : 0.4)
                let caption = episodeCaption(watched: watched, available: available, progress: progress)
                Text(caption?.text ?? "Не начата")
                    .font(.caption).monospacedDigit().foregroundStyle(Palette.inkSoft).lineLimit(1)
                    .accessibilityLabel(caption?.spoken ?? "")
                    // An episode nobody has touched has nothing to say, but it still holds the
                    // line: the placeholder keeps its tile the same height as its neighbours
                    // without putting the same sentence under two dozen of them.
                    .opacity(caption == nil ? 0 : 1)
                    .accessibilityHidden(caption == nil)
            }
            .padding(12).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous)
                    .strokeBorder(watched ? Palette.accent.opacity(0.55) : Palette.hairline, lineWidth: Metrics.hairline)
            }
            .foregroundStyle(available ? Palette.ink : Palette.inkSoft)
        }
        .buttonStyle(.plain).disabled(!available)
        .accessibilityIdentifier("episode-\(episode)")
        .contextMenu {
            if available {
                Button("Смотреть", systemImage: "play") { play(episode) }
                if model.session != nil {
                    if watched { Button("Отметить непросмотренной", systemImage: "arrow.uturn.backward") { unwatchEpisode = episode } }
                    else { Button("Отметить просмотренной", systemImage: "checkmark") { model.markEpisode(anime: anime, episode: episode, watched: true) } }
                }
            }
        }
        .accessibilityAction(named: watched ? "Отметить непросмотренной" : "Отметить просмотренной") {
            guard available, model.session != nil else { return }
            if watched { unwatchEpisode = episode }
            else { model.markEpisode(anime: anime, episode: episode, watched: true) }
        }
    }
    /// Filled at all only by something actually watched: marked-off episodes are full, the one
    /// somebody stopped in the middle of is where they stopped, and everything else is empty.
    private func episodeFraction(watched: Bool, progress: EpisodeProgress?) -> Double {
        if watched { return 1 }
        guard let progress, progress.duration > 0, progress.position > 0 else { return 0 }
        return min(1, progress.position / progress.duration)
    }
    /// Nil when there is nothing to say about this episode yet. A tile is about as wide as one
    /// Russian word, so the position is shown as the time alone and read out as the sentence.
    private func episodeCaption(watched: Bool, available: Bool,
                                progress: EpisodeProgress?) -> (text: String, spoken: String)? {
        if !available { return ("Не вышла", "Не вышла") }
        if watched { return ("Просмотрено", "Просмотрено") }
        if let progress, progress.duration > 0, progress.position > 0 {
            let stamp = CatalogPresentation.timestamp(progress.position)
            return (stamp, "Остановились на \(stamp)")
        }
        return nil
    }
    private var playLabel: String {
        if target.rewatch { return "Пересмотреть с первой серии" }
        if target.position > 0 { return "Продолжить с \(CatalogPresentation.timestamp(target.position))" }
        return "Смотреть серию \(target.episode)"
    }
    private var waitingLabel: String { anime.availableEpisodes == 0 ? "Ещё не вышло" : "Ждём серию \(target.episode)" }
    private var airingStatus: String {
        switch anime.status { case "ongoing": "Онгоинг"; case "released": "Вышло"; case "anons": "Анонс"; default: "" }
    }
    private func kindTitle(_ kind: String) -> String {
        switch kind { case "tv": "Сериал"; case "movie": "Фильм"; case "ova": "OVA"; case "ona": "ONA"; case "special", "tv_special": "Спецвыпуск"; case "music": "Музыкальное видео"; default: kind.uppercased() }
    }
    private func play(_ episode: Int) {
        guard episode > 0, episode <= playableEpisodes else { return }
        model.beginPlayback(anime: anime)
        route = PlaybackRoute(anime: anime, episode: episode)
    }
    private func loadDetails() async {
        let request = UUID(); detailRequest = request
        loading = true; failure = nil
        defer { if detailRequest == request { loading = false } }
        do {
            let result = try await model.service.details(initial.id)
            try Task.checkCancellation()
            if detailRequest == request { details = result }
        } catch is CancellationError {} catch { if !Task.isCancelled && detailRequest == request { failure = error.localizedDescription } }
    }
}

/// Applied to detail only; the player owns its presentation while a full-screen playback route is open.
private struct CompletionSuggestionPresentation: ViewModifier {
    @Environment(AppModel.self) private var model
    let enabled: Bool
    func body(content: Content) -> some View {
        content.confirmationDialog("Перевести аниме в завершённые?", isPresented: Binding(get: { enabled && model.completionSuggestion != nil }, set: { if !$0 && enabled { model.completionSuggestion = nil } }), titleVisibility: .visible) {
            if let anime = model.completionSuggestion {
                Button("Завершить просмотр") {
                    model.queueRate(anime: anime, status: "completed", episodes: model.rate(for: anime.id)?.episodes ?? 0)
                    model.completionSuggestion = nil
                }
            }
            Button("Позже", role: .cancel) { model.completionSuggestion = nil }
        } message: { Text(model.completionSuggestion?.title ?? "") }
    }
}
