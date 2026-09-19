import SwiftUI

struct PlaybackRoute: Identifiable { let id = UUID(); var anime: Anime; var episode: Int }

struct DetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
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
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .top, spacing: 32) {
                        PosterView(anime: anime).frame(width: 230)
                        summary.frame(minWidth: 300, maxWidth: .infinity, alignment: .leading)
                    }
                    VStack(alignment: .leading, spacing: 24) {
                        PosterView(anime: anime).frame(width: 180).frame(maxWidth: .infinity)
                        summary
                    }
                }
                if loading { ProgressView("Обновляем информацию…").font(.footnote) }
                if let failure { CatalogRetry(message: failure) { revision += 1 } }
                episodeSection
                if !anime.plainDescription.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Об аниме").font(.title2.bold())
                        Text(anime.plainDescription).foregroundStyle(.secondary)
                            .lineLimit(descriptionExpanded ? nil : 4).textSelection(.enabled)
                        Button(descriptionExpanded ? "Свернуть" : "Читать полностью") { descriptionExpanded.toggle() }
                            .font(.subheadline.weight(.semibold))
                    }
                }
            }.padding(20).frame(maxWidth: 1050).frame(maxWidth: .infinity)
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
    private var summary: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(anime.title).font(.largeTitle.bold()).fixedSize(horizontal: false, vertical: true)
            if !anime.originalTitle.isEmpty { Text(anime.originalTitle).font(.title3).foregroundStyle(.secondary) }
            Text(anime.subtitle).font(.subheadline).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 6) {
                Text(airingStatus).font(.subheadline.weight(.medium))
                if anime.status == "ongoing" { Text("Вышло серий: \(anime.episodesAired)").font(.subheadline).foregroundStyle(.secondary) }
                if let kind = anime.kind, !kind.isEmpty { Text(kindTitle(kind)).font(.subheadline).foregroundStyle(.secondary) }
                if let studios = anime.studios, !studios.isEmpty { Text(studios.joined(separator: ", ")).font(.subheadline).foregroundStyle(.secondary) }
            }
            if target.canPlay {
                Button { play(target.episode) } label: {
                    Label(playLabel, systemImage: "play.fill").frame(maxWidth: .infinity).padding(.vertical, 5)
                }.buttonStyle(.borderedProminent).controlSize(.large).accessibilityIdentifier("play-anime")
            } else {
                Label(waitingLabel, systemImage: "calendar").foregroundStyle(.secondary)
                if let date = anime.nextAirDate { Text(date, format: .dateTime.day().month().hour().minute()).foregroundStyle(.secondary) }
            }
            Button { translationsOpen = true } label: {
                Label(model.titleTranslations[anime.id] == nil ? "Выбрать озвучку" : "Изменить озвучку", systemImage: "waveform")
            }.buttonStyle(.bordered).accessibilityIdentifier("detail-translations")
            if model.session != nil {
                LibraryStatusMenu(anime: anime).buttonStyle(.bordered).controlSize(.large)
                if let rate {
                    Stepper("Просмотрено: \(rate.episodes)", value: Binding(get: { model.rate(for: anime.id)?.episodes ?? 0 }, set: { model.setEpisodes(anime: anime, count: $0) }), in: 0...max(totalEpisodes, rate.episodes))
                        .font(.subheadline).accessibilityIdentifier("watched-episodes")
                }
            }
        }
    }
    @ViewBuilder private var episodeSection: some View {
        if totalEpisodes > 0 {
            VStack(alignment: .leading, spacing: 16) {
                Text("Серии").font(.title2.bold())
                Text("Просмотрено \(rate?.episodes ?? 0) из \(totalEpisodes)").font(.subheadline).foregroundStyle(.secondary)
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
        return Button { play(episode) } label: {
            VStack(alignment: .leading, spacing: 9) {
                Label("Серия \(episode)", systemImage: watched ? "checkmark.circle.fill" : available ? "play.circle" : "clock")
                    .font(.subheadline.weight(.medium)).frame(maxWidth: .infinity, alignment: .leading)
                if !available { Text("Не вышла").font(.caption).foregroundStyle(.secondary) }
                else if watched { Text("Просмотрено").font(.caption).foregroundStyle(.secondary) }
                else if let progress, progress.duration > 0, progress.position > 0 {
                    ProgressView(value: min(1, max(0, progress.position / progress.duration)))
                    Text(CatalogPresentation.timestamp(progress.position)).font(.caption).monospacedDigit()
                }
            }.padding(.vertical, 8).frame(maxHeight: .infinity, alignment: .top)
        }
        .buttonStyle(.bordered).disabled(!available)
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
