import SwiftUI

struct PosterView: View {
    var anime: Anime
    var body: some View {
        Color.clear.aspectRatio(2.0 / 3, contentMode: .fit).overlay {
            AsyncImage(url: URL(string: anime.poster)) { image in image.resizable().scaledToFill() } placeholder: {
                Rectangle().fill(.quaternary).overlay { Image(systemName: "film").font(.largeTitle).foregroundStyle(.secondary) }
            }
        }
        .clipped().clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityHidden(true)
    }
}

struct AnimeCard: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// `@Environment(\.isFocused)` reads the value a parent handed down, so a card applying
    /// `.focusable()` to its own body was reading its ancestor's focus and never its own.
    @FocusState private var isFocused: Bool
    var anime: Anime
    var caption: String? = nil
    var progress: Double? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            PosterView(anime: anime)
                .overlay(alignment: .bottom) {
                    if sizeClass == .regular {
                        LinearGradient(colors: [.clear, .black.opacity(0.65)], startPoint: .center, endPoint: .bottom)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                }
            if let progress { ProgressView(value: min(1, max(0, progress))).accessibilityLabel("Прогресс просмотра") }
            Text(anime.title).font(sizeClass == .regular ? .headline : .subheadline.weight(.semibold))
                .foregroundStyle(.primary).lineLimit(2, reservesSpace: true)
            Text(caption ?? anime.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2)
        }
        .contentShape(Rectangle())
        .focusable(sizeClass == .regular)
        .focused($isFocused)
        .scaleEffect(isFocused && sizeClass == .regular ? 1.06 : 1)
        .shadow(color: isFocused && sizeClass == .regular ? .primary.opacity(0.28) : .clear, radius: 18)
        .animation(.easeOut(duration: 0.16), value: isFocused)
        .accessibilityElement(children: .combine)
    }
}

struct CatalogGrid<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    @ViewBuilder var content: Content
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 220 : 140), spacing: 20)], alignment: .leading, spacing: 26) { content }
    }
}

struct CatalogRetry: View {
    let message: String
    let retry: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(message, systemImage: "exclamationmark.triangle").foregroundStyle(.secondary)
            Button("Повторить", action: retry).buttonStyle(.bordered)
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
                                    if translation.episodes > 0 { Text("\(translation.episodes) серий").font(.caption).foregroundStyle(.secondary) }
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
