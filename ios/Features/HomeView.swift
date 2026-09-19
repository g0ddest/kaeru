import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @ScaledMetric(relativeTo: .body) private var posterWidth = 150.0
    @State private var route: PlaybackRoute?
    @State private var season = CatalogSeason.current()
    @State private var seasonal: [Anime] = []
    @State private var seasonLoading = true
    @State private var seasonFailure: String?
    @State private var revision = 0
    private var continuing: [Anime] {
        var seen = Set<Int>()
        return (model.progress.values.sorted { $0.updatedAt > $1.updatedAt }.compactMap { model.recentAnime[$0.animeID] } + model.library.map(\.anime)).filter {
            seen.insert($0.id).inserted && model.rate(for: $0.id)?.status != "completed" && model.continueTarget(for: $0).position > 0
        }
    }
    private var active: [LibraryItem] {
        model.library.filter { ["watching", "rewatching"].contains($0.status) }.sorted {
            CatalogPresentation.date($0.updatedAt) > CatalogPresentation.date($1.updatedAt)
        }
    }
    private func waitingTitles(ongoing: Bool) -> [Anime] {
        let inProgress = Set(continuing.map(\.id))
        return active.map(\.anime).filter {
            let target = model.continueTarget(for: $0)
            return ($0.status == "ongoing") == ongoing && target.canPlay && !target.rewatch && !inProgress.contains($0.id)
        }
    }
    private var upcoming: [Anime] {
        let now = Date(), horizon = now.addingTimeInterval(7 * 24 * 60 * 60)
        return active.map(\.anime).filter {
            guard $0.status == "ongoing", let date = $0.nextAirDate else { return false }
            return date > now && date < horizon
        }.sorted { ($0.nextAirDate ?? .distantFuture) < ($1.nextAirDate ?? .distantFuture) }
    }
    private var planned: [Anime] {
        let inProgress = Set(continuing.map(\.id))
        return model.library.filter { $0.status == "planned" && !inProgress.contains($0.anime.id) }
            .sorted { CatalogPresentation.date($0.updatedAt) > CatalogPresentation.date($1.updatedAt) }.map(\.anime)
    }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 32) {
                let fresh = waitingTitles(ongoing: true), next = waitingTitles(ongoing: false)
                if let featured = continuing.first ?? fresh.first ?? next.first {
                    ResumeFeature(anime: featured) { play(featured) }.padding(.horizontal)
                }
                shelf("Новые серии", anime: fresh, episodes: true)
                shelf("Продолжить просмотр", anime: continuing, episodes: true)
                shelf("Дальше по списку", anime: next, episodes: true)
                if !upcoming.isEmpty {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Скоро").font(.title2.bold())
                        ForEach(upcoming) { anime in
                            NavigationLink(value: anime) {
                                HStack(spacing: 16) {
                                    PosterView(anime: anime).frame(width: 52)
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(anime.title).font(.headline).foregroundStyle(.primary)
                                        Text("Серия \(anime.episodesAired + 1)").font(.subheadline).foregroundStyle(.secondary)
                                        if let date = anime.nextAirDate { Text(date, format: .dateTime.weekday().day().month().hour().minute()).font(.caption).foregroundStyle(.secondary) }
                                    }
                                    Spacer(minLength: 0)
                                    Image(systemName: "chevron.right").foregroundStyle(.tertiary).accessibilityHidden(true)
                                }
                            }.buttonStyle(.plain)
                        }
                    }.padding(.horizontal)
                }
                shelf("В планах", anime: planned)
                shelf("Популярно сейчас", anime: model.catalog.filter { $0.status == "ongoing" })
                seasonSection
                if model.loading && model.catalog.isEmpty { ProgressView("Загружаем каталог…").frame(maxWidth: .infinity) }
                if model.session == nil {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Ваша коллекция — с вами").font(.title2.bold())
                        Text("Войдите в Shikimori, чтобы видеть свой список и синхронизировать просмотренные серии.").foregroundStyle(.secondary)
                        Button("Войти в Shikimori", systemImage: "person.crop.circle") { Task { await model.signIn() } }
                            .buttonStyle(.bordered).disabled(model.signingIn)
                    }.padding(.horizontal)
                }
            }.padding(.vertical, sizeClass == .regular ? 32 : 20).frame(maxWidth: 1400).frame(maxWidth: .infinity)
        }
        .background(sizeClass == .regular ? Color.black : Color.clear)
        .refreshable { await model.reload(); revision += 1 }
        .task(id: "\(season.id)-\(revision)") { await loadSeason() }
        .fullScreenCover(item: $route) { PlayerScreen(anime: $0.anime, episode: $0.episode, model: model) }
    }
    private var seasonSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Популярное в сезоне").font(.title2.bold())
                Picker("Сезон", selection: $season) {
                    ForEach([-1, 0, 1].map { season.offset($0) }) { value in Text(value.title).tag(value) }
                }.pickerStyle(.menu).accessibilityIdentifier("catalog-season")
            }.padding(.horizontal)
            if seasonLoading { ProgressView("Загружаем сезон…").frame(maxWidth: .infinity).padding() }
            else if let seasonFailure { CatalogRetry(message: seasonFailure) { revision += 1 }.padding(.horizontal) }
            else if seasonal.isEmpty { Text("В этом сезоне пока ничего нет").foregroundStyle(.secondary).padding(.horizontal) }
            else { cards(seasonal, episodes: false) }
        }
    }
    @ViewBuilder private func shelf(_ title: String, anime: [Anime], episodes: Bool = false) -> some View {
        if !anime.isEmpty {
            VStack(alignment: .leading, spacing: 16) {
                Text(title).font(.title2.bold()).padding(.horizontal)
                cards(anime, episodes: episodes)
            }
        }
    }
    private func cards(_ anime: [Anime], episodes: Bool) -> some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 20) {
                ForEach(anime) { title in
                    let target = model.continueTarget(for: title)
                    let progress = model.progressFor(animeID: title.id, episode: target.episode)
                    NavigationLink(value: title) {
                        AnimeCard(anime: title, caption: episodes ? caption(target: target, progress: progress) : nil,
                                  progress: episodes && target.position > 0 && (progress?.duration ?? 0) > 0 ? target.position / progress!.duration : nil)
                            .frame(width: min(240, posterWidth))
                    }.buttonStyle(.plain)
                }
            }.padding(.horizontal)
        }.scrollIndicators(.hidden)
    }
    private func caption(target: ContinueTarget, progress: EpisodeProgress?) -> String {
        if target.position > 0, let progress, progress.duration > target.position {
            return "Серия \(target.episode) · осталось \(Int(ceil((progress.duration - target.position) / 60))) мин"
        }
        return "Серия \(target.episode)"
    }
    private func play(_ anime: Anime) {
        let target = model.continueTarget(for: anime)
        guard target.canPlay else { return }
        model.beginPlayback(anime: anime)
        route = PlaybackRoute(anime: anime, episode: target.episode)
    }
    private func loadSeason() async {
        seasonLoading = true; seasonFailure = nil
        do {
            let result = try await model.service.seasonal(year: season.year, season: season.apiValue)
            try Task.checkCancellation()
            seasonal = result; seasonLoading = false
        } catch is CancellationError {} catch { if !Task.isCancelled { seasonFailure = error.localizedDescription; seasonLoading = false } }
    }
}

private struct ResumeFeature: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
    let anime: Anime
    let play: () -> Void
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .center, spacing: 28) {
                PosterView(anime: anime).frame(width: 200)
                summary.frame(minWidth: 240, maxWidth: .infinity, alignment: .leading)
            }
            VStack(alignment: .leading, spacing: 20) {
                PosterView(anime: anime).frame(width: typeSize.isAccessibilitySize ? 160 : 130)
                summary
            }
        }.padding(24).frame(maxWidth: .infinity, alignment: .leading)
            .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 24))
    }
    private var summary: some View {
        let target = model.continueTarget(for: anime)
        return VStack(alignment: .leading, spacing: 14) {
            Text(anime.title).font(.largeTitle.bold()).fixedSize(horizontal: false, vertical: true)
            Text(target.position > 0 ? "Серия \(target.episode) · с \(CatalogPresentation.timestamp(target.position))" : "Серия \(target.episode)").foregroundStyle(.secondary)
            Button(action: play) { Label(target.position > 0 ? "Продолжить" : "Смотреть", systemImage: "play.fill") }
                .buttonStyle(.borderedProminent).controlSize(.large).accessibilityIdentifier("home-resume")
            NavigationLink("Об аниме", value: anime).font(.subheadline)
        }
    }
}
