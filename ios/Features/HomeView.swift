import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @ScaledMetric(relativeTo: .largeTitle) private var heroFloor = 320.0
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
        Self.newestFirst(model.library.filter { ["watching", "rewatching"].contains($0.status) })
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
    /// What is on the device: the one shelf that works with nothing else available, and the reason
    /// the strip above it says what can still be done rather than only what cannot.
    private var downloaded: [DownloadedShelf.Item] {
        DownloadedShelf.build(entries: model.downloads.entries,
                              counted: { model.rate(for: $0)?.episodes ?? 0 },
                              progress: { model.progressFor(animeID: $0, episode: $1) },
                              threshold: model.preferences.watchedThreshold)
    }
    /// The phone has said it has no network. Not «has not said yet»: `isConnected` is false until
    /// the monitor first speaks, and a strip drawn on that would flash on every cold start.
    private var offline: Bool { model.downloads.connectivityKnown && !model.downloads.isConnected }
    /// Whether anything is pinned above the shelves. The hero runs under the navigation bar only
    /// when nothing is: a strip drawn over artwork would be a line of type on a poster.
    private var notices: Bool { offline || model.availableUpdate != nil }
    private var planned: [Anime] {
        let inProgress = Set(continuing.map(\.id))
        return Self.newestFirst(model.library.filter { $0.status == "planned" && !inProgress.contains($0.anime.id) }).map(\.anime)
    }
    /// Each date parsed once rather than twice per comparison: the shelves are rebuilt on every
    /// redraw, and during playback that is every tick of the position.
    private static func newestFirst(_ items: [LibraryItem]) -> [LibraryItem] {
        items.map { ($0, CatalogPresentation.date($0.updatedAt)) }.sorted { $0.1 > $1.1 }.map(\.0)
    }
    /// What the carousel shows: the episodes waiting to be resumed, then the ones that just aired.
    private func heroTitles(_ fresh: [Anime], _ next: [Anime]) -> [Anime] {
        var seen = Set<Int>()
        return Array((continuing + fresh + next).filter { seen.insert($0.id).inserted }.prefix(5))
    }
    var body: some View {
        GeometryReader { proxy in
            let fresh = waitingTitles(ongoing: true), next = waitingTitles(ongoing: false)
            let hero = heroTitles(fresh, next)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Metrics.shelfSpacing(sizeClass)) {
                    if !hero.isEmpty {
                        HeroCarousel(titles: hero, height: max(proxy.size.height * Metrics.heroFraction(sizeClass), heroFloor)) { play($0) }
                    }
                    // First, and above «Новые серии» on purpose: with no network it is the only
                    // shelf here that can be acted on, and with one it is what the viewer
                    // deliberately put on the device. The hero is never a download — «Скачано» is
                    // about where an episode is, not about what somebody was in the middle of.
                    downloadedShelf
                    episodeShelf("Новые серии", fresh)
                    episodeShelf("Продолжить просмотр", continuing)
                    episodeShelf("Дальше по списку", next)
                    upcomingShelf
                    posterShelf("В планах", planned)
                    // The catalogue is the half of this screen that needs a network. Offline it is
                    // left out rather than shown failing: a «Повторить» that cannot work is worse
                    // than a shelf that is not there.
                    if !offline {
                        posterShelf("Популярно сейчас", model.catalog.filter { $0.status == "ongoing" })
                        seasonSection
                        if model.loading && model.catalog.isEmpty {
                            ProgressView("Загружаем каталог…").frame(maxWidth: .infinity).padding(.top, 40)
                        }
                    }
                    if model.session == nil { invitation }
                }
                .padding(.bottom, 36)
                .frame(maxWidth: Metrics.contentWidth).frame(maxWidth: .infinity)
            }
            .background(Palette.canvas)
            // Above the shelves rather than inside them, so it is on every state this screen has
            // rather than only on the one with rows. The update sits under the offline strip when
            // both are up: no network is the more useful of the two facts, and the update is not
            // going anywhere.
            .safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 0) {
                    if offline { OfflineStrip() }
                    if let update = model.availableUpdate { UpdateStrip(version: update.version) }
                }
            }
            .ignoresSafeArea(edges: hero.isEmpty || notices ? [] : .top)
            // The artwork runs under the navigation bar rather than below it; the bar keeps its
            // buttons — on iPad the sidebar toggle lives there — but loses its background and its
            // title, which the hero says better.
            .navigationTitle(hero.isEmpty ? "Главная" : "")
            .kaeruTitleDisplay(hero.isEmpty ? .large : .inline)
            .kaeruBarBackground(hero.isEmpty ? .visible : .hidden)
        }
        .kaeruRefreshable { await model.reload(); revision += 1 }
        .task(id: "\(season.id)-\(revision)-\(offline)") { if !offline { await loadSeason() } }
        .playerPresentation(item: $route)
    }
    private var invitation: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Ваша коллекция — с вами").font(.kaeruShelf(sizeClass != .regular)).foregroundStyle(Palette.ink)
            Text("Войдите в Shikimori, чтобы видеть свой список и синхронизировать просмотренные серии.")
                .foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
            Button("Войти в Shikimori", systemImage: "person.crop.circle") { Task { await model.signIn() } }
                .buttonStyle(.bordered).tint(Palette.accent).disabled(model.signingIn)
        }
        .padding(.horizontal, Metrics.gutter(sizeClass))
    }
    private var seasonSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                ShelfHeader(title: "Популярное в сезоне", route: seasonal.isEmpty ? nil : ShelfRoute(title: season.title, anime: seasonal))
                Picker("Сезон", selection: $season) {
                    ForEach([-1, 0, 1].map { season.offset($0) }) { value in Text(value.title).tag(value) }
                }
                .pickerStyle(.menu).tint(Palette.inkSoft)
                .accessibilityIdentifier("catalog-season")
                .padding(.horizontal, Metrics.gutter(sizeClass) - 12)
            }
            if seasonLoading { ProgressView("Загружаем сезон…").frame(maxWidth: .infinity).padding() }
            else if let seasonFailure { CatalogRetry(message: seasonFailure) { revision += 1 }.padding(.horizontal, Metrics.gutter(sizeClass)) }
            else if seasonal.isEmpty {
                Text("В этом сезоне пока ничего нет").foregroundStyle(Palette.inkSoft).padding(.horizontal, Metrics.gutter(sizeClass))
            } else { posterRow(seasonal) }
        }
    }
    @ViewBuilder private var upcomingShelf: some View {
        if !upcoming.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                ShelfHeader(title: "Скоро")
                VStack(spacing: 0) {
                    ForEach(upcoming) { anime in
                        NavigationLink(value: anime) {
                            HStack(spacing: 14) {
                                PosterView(anime: anime).frame(width: 46)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(anime.title).font(.kaeruCardTitle).foregroundStyle(Palette.ink).lineLimit(2)
                                    Text(verbatim: "\(anime.episodesAired + 1) серия").font(.kaeruCardCaption).foregroundStyle(Palette.inkSoft)
                                    if let date = anime.nextAirDate {
                                        Text(date, format: .dateTime.weekday().day().month().hour().minute())
                                            .font(.kaeruCaption).foregroundStyle(Palette.inkSoft)
                                    }
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.right").font(.kaeruCaption.weight(.semibold))
                                    .foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
                            }
                            .padding(.vertical, 9)
                        }.buttonStyle(.plain)
                        .kaeruHover(.row)
                    }
                }
                .padding(.horizontal, Metrics.gutter(sizeClass))
            }
        }
    }
    @ViewBuilder private var downloadedShelf: some View {
        let items = downloaded
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                ShelfHeader(title: "Скачано", route: ShelfRoute(title: "Скачано", anime: [], episodes: true, downloads: items))
                row {
                    ForEach(items) { item in
                        DownloadedCard(item: item) { play(item) }
                            .frame(width: Metrics.stillWidth(sizeClass))
                    }
                }
            }
        }
    }
    @ViewBuilder private func episodeShelf(_ title: String, _ anime: [Anime]) -> some View {
        if !anime.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                ShelfHeader(title: title, route: ShelfRoute(title: title, anime: anime, episodes: true))
                row {
                    ForEach(anime) { value in
                        let target = model.continueTarget(for: value)
                        EpisodeCard(anime: value, target: target,
                                    progress: model.progressFor(animeID: value.id, episode: target.episode)) { play(value) }
                            .frame(width: Metrics.stillWidth(sizeClass))
                    }
                }
            }
        }
    }
    @ViewBuilder private func posterShelf(_ title: String, _ anime: [Anime]) -> some View {
        if !anime.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                ShelfHeader(title: title, route: ShelfRoute(title: title, anime: anime))
                posterRow(anime)
            }
        }
    }
    private func posterRow(_ anime: [Anime]) -> some View {
        row {
            ForEach(anime) { value in
                NavigationLink(value: value) { AnimeCard(anime: value) }
                    .buttonStyle(.plain)
                    .frame(width: Metrics.posterWidth(sizeClass))
                    .contextMenu { LibraryStatusMenu(anime: value) }
            }
        }
    }
    private func row<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: Metrics.cardSpacing(sizeClass)) { content() }
                .scrollTargetLayout()
                .padding(.horizontal, Metrics.gutter(sizeClass))
        }
        .scrollIndicators(.hidden)
        .scrollTargetBehavior(.viewAligned)
    }
    /// A downloaded episode opens at itself rather than at wherever the title got to: the card
    /// named one episode, and it is the one on the device.
    private func play(_ item: DownloadedShelf.Item) {
        model.beginPlayback(anime: item.anime)
        route = PlaybackRoute(anime: item.anime, episode: item.episode)
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
