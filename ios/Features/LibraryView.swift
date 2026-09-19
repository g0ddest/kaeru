import SwiftUI

struct LibraryView: View {
    @Environment(AppModel.self) private var model
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
            $0.status == status.rawValue && (query.isEmpty || $0.anime.title.localizedCaseInsensitiveContains(query) || $0.anime.originalTitle.localizedCaseInsensitiveContains(query))
        }.sorted {
            let lhs = CatalogPresentation.date($0.updatedAt), rhs = CatalogPresentation.date($1.updatedAt)
            if sort == .updated && lhs != rhs { return lhs > rhs }
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
                    Button("Войти") { Task { await model.signIn() } }.buttonStyle(.borderedProminent).disabled(model.signingIn)
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        HStack {
                            Picker("Статус", selection: $status) {
                                ForEach(WatchStatus.allCases) { value in
                                    Text("\(value.title) (\(model.library.filter { $0.status == value.rawValue }.count))").tag(value)
                                }
                            }.pickerStyle(.menu).accessibilityIdentifier("library-filter")
                            Spacer(minLength: 0)
                            if model.syncing { ProgressView().controlSize(.small).accessibilityLabel("Синхронизация") }
                            else if !model.pending.isEmpty { Image(systemName: "icloud.and.arrow.up").accessibilityLabel("Ожидает синхронизации") }
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
                    }.padding().frame(maxWidth: 1200).frame(maxWidth: .infinity)
                }
                .searchable(text: $query, prompt: "В моём списке")
                .refreshable { await model.reloadLibrary(); await model.flush() }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Picker("Сортировка", selection: $sort) { ForEach(LibraryOrder.allCases) { Text($0.label).tag($0) } }
                        } label: { Label("Сортировка", systemImage: "arrow.up.arrow.down") }
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
            if query.isEmpty && (status == .watching || status == .planned) { Button("Найти аниме", action: onSearch).buttonStyle(.bordered) }
        }
    }
    private var emptyTitle: String {
        switch status {
        case .watching: "Вы ничего не смотрите"
        case .planned: "В планах пока пусто"
        case .completed: "Завершённых тайтлов пока нет"
        case .rewatching: "Вы ничего не пересматриваете"
        case .onHold: "Ничего не отложено"
        case .dropped: "Ничего не брошено"
        }
    }
}
