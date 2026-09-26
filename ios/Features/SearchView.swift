import SwiftUI

struct SearchView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var query = ""
    @State private var results: [Anime] = []
    @State private var recent: [String] = []
    @State private var loading = false
    @State private var failure: String?
    @State private var revision = 0
    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    private struct Request: Hashable { var query: String; var revision: Int }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if loading { ProgressView("Ищем аниме…").frame(maxWidth: .infinity).padding(.vertical, 30) }
                else if let failure { CatalogRetry(message: failure) { revision += 1 } }
                else if trimmedQuery.count < 2 {
                    ContentUnavailableView("Найдите свою историю", systemImage: "magnifyingglass", description: Text("Введите хотя бы два символа названия на русском или английском."))
                    if !recent.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Недавние запросы").font(.kaeruShelf(sizeClass != .regular)).foregroundStyle(Palette.ink)
                            ForEach(recent, id: \.self) { value in
                                Button { query = value } label: { Label(value, systemImage: "clock.arrow.circlepath").frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 7) }
                            }
                        }
                    }
                } else if results.isEmpty { ContentUnavailableView.search(text: trimmedQuery) }
                else {
                    CatalogGrid {
                        ForEach(results) { anime in
                            VStack(alignment: .leading, spacing: 10) {
                                NavigationLink(value: anime) { AnimeCard(anime: anime) }.buttonStyle(.plain).accessibilityIdentifier("anime-\(anime.id)")
                                    // The status menu every other poster in the app has on a right
                                    // click; a phone adds from here with the button below.
                                    .kaeruContextMenu { if model.session != nil { LibraryStatusMenu(anime: anime) } }
                                if model.rate(for: anime.id) != nil {
                                    Label("В списке", systemImage: "checkmark").font(.kaeruCaption).foregroundStyle(Palette.inkSoft)
                                } else if model.session != nil {
                                    Button("В планы", systemImage: "plus") { model.queueRate(anime: anime, status: "planned", episodes: 0) }
                                        .font(.kaeruSubheadline).buttonStyle(.bordered).tint(Palette.accent)
                                        .accessibilityLabel("Добавить \(anime.title) в планы")
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, Metrics.gutter(sizeClass)).padding(.vertical, 16)
            .frame(maxWidth: Metrics.contentWidth).frame(maxWidth: .infinity)
        }
        .background(Palette.canvas)
        .searchable(text: $query, prompt: "Название аниме")
        .searchSuggestions {
            ForEach(recent, id: \.self) { value in Label(value, systemImage: "clock.arrow.circlepath").searchCompletion(value) }
        }
        .onSubmit(of: .search) { revision += 1 }
        .task(id: Request(query: trimmedQuery, revision: revision)) {
            let value = trimmedQuery
            failure = nil
            guard value.count >= 2 else { results = []; loading = false; return }
            loading = true
            do {
                try await Task.sleep(for: .milliseconds(350))
                recent = CatalogPresentation.recentQueries(adding: value, to: recent)
                let found = try await model.service.search(value)
                try Task.checkCancellation()
                results = found; loading = false
            } catch is CancellationError {} catch { if !Task.isCancelled { failure = error.localizedDescription; loading = false } }
        }
    }
}
