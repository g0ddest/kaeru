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
    private var entries: [LibraryItem] {
        model.library.filter {
            (mode == .recent || $0.status == status.rawValue)
                && (query.isEmpty || $0.anime.title.localizedCaseInsensitiveContains(query) || $0.anime.originalTitle.localizedCaseInsensitiveContains(query))
        }.sorted {
            let lhs = CatalogPresentation.date($0.updatedAt), rhs = CatalogPresentation.date($1.updatedAt)
            if (mode == .recent || sort == .updated) && lhs != rhs { return lhs > rhs }
            if $0.anime.title == $1.anime.title { return $0.anime.id < $1.anime.id }
            return CatalogPresentation.titlePrecedes($0.anime.title, $1.anime.title)
        }
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
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        HStack {
                            if mode == .list {
                                Picker("Статус", selection: $status) {
                                    ForEach(WatchStatus.allCases) { value in
                                        Text("\(value.title) (\(model.library.filter { $0.status == value.rawValue }.count))").tag(value)
                                    }
                                }.pickerStyle(.menu).tint(Palette.inkSoft).accessibilityIdentifier("library-filter")
                            }
                            Spacer(minLength: 0)
                            if model.syncing { ProgressView().controlSize(.small).accessibilityLabel("Синхронизация") }
                            else if !model.pending.isEmpty { Image(systemName: "icloud.and.arrow.up").foregroundStyle(Palette.inkSoft).accessibilityLabel("Ожидает синхронизации") }
                        }
                        if entries.isEmpty { empty }
                        else {
                            CatalogGrid {
                                ForEach(entries, id: \.anime.id) { item in
                                    let total = max(item.anime.episodes, item.anime.availableEpisodes, item.episodes)
                                    NavigationLink(value: item.anime) {
                                        AnimeCard(anime: item.anime, caption: total > 0 ? "\(item.episodes) из \(total) серий" : "Серии ещё не вышли",
                                                  progress: total > 0 ? Double(item.episodes) / Double(total) : nil)
                                    }.buttonStyle(.plain)
                                    .contextMenu { LibraryStatusMenu(anime: item.anime) }
                                }
                            }
                        }
                    }
                    .padding(.horizontal, Metrics.gutter(sizeClass)).padding(.vertical, 16)
                    .frame(maxWidth: Metrics.contentWidth).frame(maxWidth: .infinity)
                }
                .background(Palette.canvas)
                .searchable(text: $query, prompt: mode == .recent ? "Среди недавних" : "В моём списке")
                .refreshable { await model.reloadLibrary(); await model.flush() }
                .toolbar {
                    if mode == .list {
                        ToolbarItem(placement: .topBarTrailing) {
                            Menu {
                                Picker("Сортировка", selection: $sort) { ForEach(LibraryOrder.allCases) { Text($0.label).tag($0) } }
                            } label: { Label("Сортировка", systemImage: "arrow.up.arrow.down") }
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
