import SwiftUI

/// Artwork in the shape the shelf asked for. A title has one picture — its poster — so a 16:9 card
/// crops that poster through its middle rather than stretching it into a shape it never had.
struct Artwork: View {
    enum Shape { case poster, still
        var ratio: CGFloat { self == .poster ? 2.0 / 3 : 16.0 / 9 }
        var radius: CGFloat { self == .poster ? Metrics.posterRadius : Metrics.cardRadius }
    }
    var anime: Anime
    var shape: Shape = .poster
    var body: some View {
        Color.clear.aspectRatio(shape.ratio, contentMode: .fit).overlay {
            AsyncImage(url: URL(string: anime.poster)) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(Palette.elevated)
                    .overlay { Image(systemName: "film").font(.title).foregroundStyle(Palette.inkSoft) }
            }
        }
        .clipped()
        .kaeruCard(radius: shape.radius)
        .accessibilityHidden(true)
    }
}

struct PosterView: View {
    var anime: Anime
    var body: some View { Artwork(anime: anime, shape: .poster) }
}

/// Full-width artwork for a hero: the poster blurred out to the edges so nothing is ever stretched,
/// the poster itself sharp where there is room for it, and the side the type sits on darkened.
/// White type on this holds in both appearances — it is a picture, not a surface of the page.
struct Backdrop: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    let anime: Anime
    var body: some View {
        // Drawn inside a clear view of the offered size: a fill-scaled image is bigger than what it
        // was offered, and a stack that measures itself by it hands the caller a backdrop taller
        // than the frame it was asked for.
        Color.clear.overlay { layers }.clipped().accessibilityHidden(true)
    }
    private var layers: some View {
        ZStack {
            Palette.elevated
            AsyncImage(url: URL(string: anime.poster)) { image in
                image.resizable().scaledToFill().blur(radius: 34, opaque: true).opacity(0.7)
            } placeholder: { Color.clear }
            HStack {
                Spacer(minLength: 0)
                AsyncImage(url: URL(string: anime.poster)) { image in
                    image.resizable().scaledToFill()
                } placeholder: { Color.clear }
                .frame(width: sizeClass == .regular ? 520 : 250)
                // Dissolved into the blur on its leading side; a hard edge down the middle of a
                // hero reads as a mistake.
                .mask {
                    LinearGradient(stops: [
                        .init(color: .clear, location: 0),
                        .init(color: .black, location: 0.35)
                    ], startPoint: .leading, endPoint: .trailing)
                }
            }
            LinearGradient(stops: [
                .init(color: .black.opacity(0.78), location: 0),
                .init(color: .black.opacity(0.4), location: 0.5),
                .init(color: .black.opacity(0.05), location: 1)
            ], startPoint: .leading, endPoint: .trailing)
            Palette.scrim(0.9)
        }
    }
}

/// A poster card: the picture, then the name under it. Used wherever a shelf is about titles rather
/// than about an episode waiting to be played.
struct AnimeCard: View {
    var anime: Anime
    var caption: String? = nil
    var progress: Double? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            PosterView(anime: anime)
                .overlay(alignment: .bottom) {
                    if let progress {
                        ProgressTrack(value: progress)
                            .padding(.horizontal, 8).padding(.bottom, 8)
                    }
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(anime.title).font(.kaeruCardTitle).foregroundStyle(Palette.ink)
                    .lineLimit(2).multilineTextAlignment(.leading)
                Text(caption ?? anime.subtitle).font(.kaeruCardCaption).foregroundStyle(Palette.inkSoft).lineLimit(1)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// The landscape card of a shelf about episodes: what you were watching, and how much of it is
/// left. Tapping it plays; the ellipsis carries everything else.
struct EpisodeCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
    var anime: Anime
    var target: ContinueTarget
    var progress: EpisodeProgress?
    var play: () -> Void
    private var fraction: Double? {
        guard target.position > 0, let progress, progress.duration > 0 else { return nil }
        return min(1, max(0, target.position / progress.duration))
    }
    private var caption: String {
        guard target.position > 0, let progress, progress.duration > target.position else {
            return "\(target.episode) серия"
        }
        return "\(target.episode) серия · осталось \(Int(ceil((progress.duration - target.position) / 60))) мин"
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: play) {
                Artwork(anime: anime, shape: .still)
                    .overlay(alignment: .bottom) { Palette.scrim().clipShape(RoundedRectangle(cornerRadius: Metrics.cardRadius, style: .continuous)) }
                    .overlay(alignment: .bottomLeading) {
                        HStack(spacing: 7) {
                            Image(systemName: "play.fill").font(.caption2)
                            Text(caption).font(.kaeruCardCaption).monospacedDigit().lineLimit(1).minimumScaleFactor(0.55)
                        }
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12).padding(.bottom, fraction == nil ? 10 : 14)
                        .padding(.trailing, 40)
                    }
                    .overlay(alignment: .bottom) {
                        if let fraction { ProgressTrack(value: fraction).padding(.horizontal, 10).padding(.bottom, 8) }
                    }
            }
            .buttonStyle(.plain)
            .overlay(alignment: .topTrailing) { menu }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(anime.title). \(caption)")
            .accessibilityHint(target.position > 0 ? "Продолжить просмотр" : "Смотреть")
            .accessibilityAddTraits(.isButton)
            NavigationLink(value: anime) {
                Text(anime.title).font(.kaeruCardTitle).foregroundStyle(Palette.ink)
                    .lineLimit(typeSize.isAccessibilitySize ? 3 : 2)
                    .multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading)
            }.buttonStyle(.plain)
        }
    }
    private var menu: some View {
        Menu {
            EpisodeCardActions(anime: anime, episode: target.episode)
        } label: {
            Image(systemName: "ellipsis")
                .font(.footnote.weight(.bold)).foregroundStyle(.white)
                .frame(width: 30, height: 30)
                .background(.black.opacity(0.45), in: Circle())
        }
        .padding(8)
        .accessibilityLabel("Ещё: \(anime.title)")
    }
}

/// A card of «Скачано»: one episode that is on the device.
///
/// The same landscape card as «Продолжить просмотр», because it answers the same two questions —
/// which episode, and how much of it is left — about the one episode it names rather than about
/// wherever the title as a whole got to.
struct DownloadedCard: View {
    @Environment(AppModel.self) private var model
    let item: DownloadedShelf.Item
    var play: () -> Void
    var body: some View {
        let progress = model.progressFor(animeID: item.anime.id, episode: item.episode)
        EpisodeCard(anime: item.anime,
                    target: ContinueTarget(episode: item.episode, position: progress?.position ?? 0, canPlay: true),
                    progress: progress, play: play)
    }
}

/// What the ellipsis on an episode card offers. Nothing here decides anything new — it spends the
/// choices the app already stores.
struct EpisodeCardActions: View {
    @Environment(AppModel.self) private var model
    let anime: Anime
    let episode: Int
    private var watched: Bool { episode <= (model.rate(for: anime.id)?.episodes ?? 0) }
    private var downloaded: Bool {
        model.downloads.entries.contains { $0.anime.id == anime.id && $0.episode == episode && $0.state == .completed }
    }
    var body: some View {
        NavigationLink(value: anime) { Label("Открыть аниме", systemImage: "info.circle") }
        if model.session != nil {
            if watched {
                Button("Отметить непросмотренной", systemImage: "arrow.uturn.backward") {
                    model.markEpisode(anime: anime, episode: episode, watched: false)
                }
            } else {
                Button("Отметить просмотренной", systemImage: "checkmark") {
                    model.markEpisode(anime: anime, episode: episode, watched: true)
                }
            }
        }
        // Nothing to offer about an episode that is already here; saying so is the point of the
        // line, because this card looks exactly like the ones that are not.
        if downloaded {
            Label("Скачанная серия", systemImage: "checkmark.circle")
        } else if let translation = model.titleTranslations[anime.id] {
            // Offered only where the озвучка is already settled: picking one is the title screen's
            // job, and a download cannot start without it.
            Button("Скачать серию", systemImage: "arrow.down.circle") {
                model.downloads.enqueue(anime: anime, episodes: [episode], translation: translation, quality: model.preferredQuality)
            }
        }
    }
}

/// The one progress indicator in the app: a hairline track with an amber fill, thin enough to read
/// as part of the artwork.
struct ProgressTrack: View {
    var value: Double
    /// The unfilled part. White by default because the track almost always lies over artwork; a
    /// track on a page surface passes the hairline colour instead.
    var track: Color = .white.opacity(0.28)
    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule().fill(Palette.accent).frame(width: proxy.size.width * min(1, max(0.02, value)))
            }
        }
        .frame(height: 3)
        .accessibilityElement()
        .accessibilityLabel("Прогресс просмотра")
        .accessibilityValue(value.formatted(.percent.precision(.fractionLength(0))))
    }
}

/// A shelf opened in full. The chevron next to a heading leads here, and the same cards are laid
/// out as a grid instead of a row.
struct ShelfRoute: Hashable, Identifiable {
    var title: String
    var anime: [Anime]
    /// True for the shelves about an episode waiting to be played, which use landscape cards.
    var episodes: Bool = false
    /// «Скачано» is the one shelf whose cards are about episodes rather than about titles: two
    /// episodes of one show are two cards there, so its rows carry their own episode numbers
    /// rather than being asked where the viewer got to.
    var downloads: [DownloadedShelf.Item] = []
    var id: String { title }
}

/// One line saying the phone is offline.
///
/// Deliberately the quietest thing it could be — surface colour, secondary text, no icon, no way to
/// dismiss it. Being offline is a condition rather than a failure: the downloads go on playing,
/// which is the whole point of them, so it is stated once at the top of the screen and never again.
/// The same sentence as Android's `OfflineStrip`.
struct OfflineStrip: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    var body: some View {
        Text("Нет сети — доступны скачанные серии")
            .font(.subheadline).foregroundStyle(Palette.inkSoft)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Metrics.gutter(sizeClass)).padding(.vertical, 12)
            // Carried up behind the status bar, so the line and the clock above it read as one
            // band rather than as a stripe floating under a gap.
            .background { Palette.surface.ignoresSafeArea(edges: .top) }
            .accessibilityAddTraits(.isStaticText)
    }
}

/// A shelf heading: large, condensed, and a chevron that means the rest of it is a tap away.
struct ShelfHeader: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    let title: String
    var route: ShelfRoute?
    var body: some View {
        Group {
            if let route {
                NavigationLink(value: route) {
                    HStack(spacing: 4) {
                        label
                        Image(systemName: "chevron.right").font(.footnote.weight(.bold)).foregroundStyle(Palette.inkSoft)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(title). Показать всё")
            } else { label }
        }
        .padding(.horizontal, Metrics.gutter(sizeClass))
    }
    private var label: some View {
        Text(title).font(.kaeruShelf(sizeClass != .regular)).foregroundStyle(Palette.ink)
            .lineLimit(2).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
    }
}

struct CatalogGrid<Content: View>: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.dynamicTypeSize) private var typeSize
    /// A grid of 16:9 cards fits fewer across than a grid of posters.
    var still = false
    @ViewBuilder var content: Content
    private var minimum: CGFloat {
        let base = still ? Metrics.stillWidth(sizeClass) * 0.82 : Metrics.gridPosterWidth(sizeClass)
        return typeSize.isAccessibilitySize ? base * 1.35 : base
    }
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: minimum), spacing: Metrics.cardSpacing(sizeClass))],
                  alignment: .leading, spacing: Metrics.cardSpacing(sizeClass) + 10) { content }
    }
}

struct CatalogRetry: View {
    let message: String
    let retry: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(message, systemImage: "exclamationmark.triangle").foregroundStyle(Palette.inkSoft)
            Button("Повторить", action: retry).buttonStyle(.bordered).tint(Palette.accent)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct LibraryStatusMenu: View {
    @Environment(AppModel.self) private var model
    let anime: Anime
    private var rate: LibraryItem? { model.rate(for: anime.id) }
    var body: some View {
        Menu {
            ForEach(WatchStatus.allCases) { status in
                Button {
                    model.queueRate(anime: anime, status: status.rawValue, episodes: rate?.episodes ?? 0)
                } label: {
                    if rate?.status == status.rawValue { Label(status.title, systemImage: "checkmark") }
                    else { Text(status.title) }
                }
            }
        } label: {
            Label(WatchStatus(rawValue: rate?.status ?? "")?.title ?? "В мой список", systemImage: rate == nil ? "plus" : "checkmark")
        }
        .accessibilityIdentifier("library-status")
    }
}

struct EpisodeUndoBar: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        if model.canUndoEpisodeChange {
            HStack(spacing: 16) {
                Text("Просмотренные серии изменены").font(.subheadline)
                Spacer(minLength: 0)
                Button("Отменить") { model.undoEpisodeChange() }.fontWeight(.semibold)
            }
            .padding().background(.regularMaterial)
        }
    }
}

struct TranslationChooser: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let anime: Anime
    @State private var translations: [Translation] = []
    @State private var loading = true
    @State private var failure: String?
    @State private var revision = 0
    private var ordered: [Translation] {
        let preferred = model.preferredTranslation(for: anime.id, available: translations, episode: max(1, model.continueTarget(for: anime).episode))
        return translations.sorted { lhs, rhs in
            if lhs.id == preferred { return rhs.id != preferred }
            if rhs.id == preferred { return false }
            return CatalogPresentation.titlePrecedes(lhs.title, rhs.title)
        }
    }
    var body: some View {
        NavigationStack {
            List {
                Section { Text(anime.title).font(.headline) } footer: { Text("Выбор сохраняется для этого аниме.") }
                if loading { ProgressView("Загружаем озвучки…") }
                else if let failure { CatalogRetry(message: failure) { revision += 1 } }
                else if translations.isEmpty { Text("Источник не предложил озвучки для этого аниме.").foregroundStyle(.secondary) }
                else {
                    ForEach(ordered) { translation in
                        Button {
                            model.rememberTranslation(translation.id, for: anime.id)
                            if model.titleTranslations[anime.id] == translation.id { dismiss() }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(translation.title).foregroundStyle(.primary)
                                    if let kind = translation.kind { Text(kind == "subtitles" ? "Субтитры" : "Озвучка").font(.caption).foregroundStyle(.secondary) }
                                    if translation.episodes > 0 { Text(verbatim: "\(translation.episodes) серий").font(.caption).foregroundStyle(.secondary) }
                                }
                                Spacer()
                                if model.titleTranslations[anime.id] == translation.id { Image(systemName: "checkmark").accessibilityLabel("Выбрано") }
                            }.padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("Озвучка").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
            .task(id: revision) {
                loading = true; failure = nil
                do {
                    let result = try await model.service.translations(anime.id)
                    try Task.checkCancellation()
                    translations = result; loading = false
                } catch is CancellationError {} catch { if !Task.isCancelled { failure = error.localizedDescription; loading = false } }
            }
        }
    }
}
