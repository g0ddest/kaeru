import SwiftUI

struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// «Мой список» is filtered by status; «Недавно добавленные» is the same shelf in the order it
    /// arrived, without a filter to choose.
    enum Mode { case list, recent }
    var mode: Mode = .list
    let onSearch: () -> Void
    @State private var status: WatchStatus = .watching
    @State private var query = ""
    @State private var sort = LibraryOrder.updated
    private enum LibraryOrder: String, CaseIterable, Identifiable {
        case updated, title
        var id: Self { self }
        var label: String { self == .updated ? "По обновлению" : "По названию" }
    }
    @State private var listing: LibraryListing?
    @State private var listingKey = 0
    private var request: LibraryQuery {
        LibraryQuery(recent: mode == .recent, status: status.rawValue, text: self.query, byTitle: sort == .title)
    }
    var body: some View {
        Group {
            if model.session == nil {
                ContentUnavailableView {
                    Label("Ваш список аниме", systemImage: "rectangle.stack")
                } description: { Text("Войдите в Shikimori, чтобы открыть свою коллекцию.") } actions: {
                    Button("Войти") { Task { await model.signIn() } }
                        .buttonStyle(.borderedProminent).tint(Palette.accent).disabled(model.signingIn)
                }
            } else {
                let key = LibraryListingCache.key(model.library, request)
                // The list built for exactly this, or the last one while the new one is built.
                let current = LibraryListingCache[key] ?? listing
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        HStack {
                            if mode == .list {
                                Picker("Статус", selection: $status) {
                                    ForEach(WatchStatus.allCases) { value in
                                        Text("\(value.title) (\(current?.counts[value.rawValue] ?? 0))").tag(value)
                                    }
                                }.pickerStyle(.menu).tint(Palette.inkSoft).accessibilityIdentifier("library-filter")
                            }
                            Spacer(minLength: 0)
                            if model.syncing { ProgressView().controlSize(.small).accessibilityLabel("Синхронизация") }
                            else if !model.pending.isEmpty { Image(systemName: "icloud.and.arrow.up").foregroundStyle(Palette.inkSoft).accessibilityLabel("Ожидает синхронизации") }
                        }
                        if let current, listingKey == key || LibraryListingCache[key] != nil {
                        if current.items.isEmpty { empty }
                        else {
                            CatalogGrid {
                                ForEach(current.items, id: \.anime.id) { item in
                                    let total = max(item.anime.episodes, item.anime.availableEpisodes, item.episodes)
                                    NavigationLink(value: item.anime) {
                                        AnimeCard(anime: item.anime, caption: total > 0 ? "\(item.episodes) из \(total) серий" : "Серии ещё не вышли",
                                                  progress: total > 0 ? Double(item.episodes) / Double(total) : nil)
                                    }.buttonStyle(.plain)
                                    .contextMenu { LibraryStatusMenu(anime: item.anime) }
                                }
                            }
                        }
                        } else {
                            ProgressView("Загружаем список…").frame(maxWidth: .infinity).padding(.top, 40)
                        }
                    }
                    .padding(.horizontal, Metrics.gutter(sizeClass)).padding(.vertical, 16)
                    .frame(maxWidth: Metrics.contentWidth).frame(maxWidth: .infinity)
                }
                .background(Palette.canvas)
                .task(id: key) {
                    if let cached = LibraryListingCache[key] { listing = cached; listingKey = key; return }
                    let library = model.library, request = request
                    let built = await Task.detached(priority: .userInitiated) { LibraryListing.build(library, request) }.value
                    guard !Task.isCancelled else { return }
                    LibraryListingCache[key] = built
                    listing = built
                    listingKey = key
                }
                .searchable(text: $query, prompt: mode == .recent ? "Среди недавних" : "В моём списке")
                .kaeruRefreshable { await model.reloadLibrary(); await model.flush() }
                .toolbar {
                    if mode == .list {
                        ToolbarItem(placement: .kaeruTrailing) {
                            Menu {
                                Picker("Сортировка", selection: $sort) { ForEach(LibraryOrder.allCases) { Text($0.label).tag($0) } }
                            } label: { Label("Сортировка", systemImage: "arrow.up.arrow.down") }
                            .kaeruHelp("Сортировка")
                        }
                    }
                }
            }
        }
    }
    private var empty: some View {
        ContentUnavailableView {
            Label(query.isEmpty ? emptyTitle : "Ничего не найдено", systemImage: "rectangle.stack")
        } description: {
            Text(query.isEmpty ? "Меняйте статус на странице аниме — оно появится в нужном разделе." : "Попробуйте другое название или выберите другой статус.")
        } actions: {
            if query.isEmpty && (mode == .recent || status == .watching || status == .planned) {
                Button("Найти аниме", action: onSearch).buttonStyle(.bordered).tint(Palette.accent)
            }
        }
    }
    private var emptyTitle: String {
        guard mode == .list else { return "Здесь пока пусто" }
        switch status {
        case .watching: return "Вы ничего не смотрите"
        case .planned: return "В планах пока пусто"
        case .completed: return "Завершённых тайтлов пока нет"
        case .rewatching: return "Вы ничего не пересматриваете"
        case .onHold: return "Ничего не отложено"
        case .dropped: return "Ничего не брошено"
        }
    }
}
