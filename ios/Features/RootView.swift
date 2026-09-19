import SwiftUI

enum AppSection: String, CaseIterable, Identifiable {
    case home, library, search
    var id: Self { self }
    var title: String { switch self { case .home: "Главная"; case .library: "Мой список"; case .search: "Поиск" } }
    var icon: String { switch self { case .home: "play.rectangle.fill"; case .library: "rectangle.stack.fill"; case .search: "magnifyingglass" } }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.scenePhase) private var scenePhase
    @State private var selection: AppSection = .home
    @State private var settings = false
    var body: some View {
        @Bindable var model = model
        Group {
            if sizeClass == .regular {
                NavigationSplitView {
                    List(AppSection.allCases, selection: Binding<AppSection?>(get: { selection }, set: { if let value = $0 { selection = value } })) { section in
                        Label(section.title, systemImage: section.icon).tag(section)
                    }
                    .navigationTitle("Kaeru")
                    .toolbar { ToolbarItem(placement: .bottomBar) { settingsButton } }
                } detail: { stack(selection) }
            } else {
                TabView(selection: $selection) {
                    ForEach(AppSection.allCases) { section in
                        stack(section).tabItem { Label(section.title, systemImage: section.icon) }.tag(section)
                    }
                }
            }
        }
        .sheet(isPresented: $settings) { SettingsView() }
        .alert("Kaeru", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK", role: .cancel) { model.error = nil }
        } message: { Text(model.error ?? "") }
        .task { await model.start() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await model.flush() } } }
    }
    private var settingsButton: some View {
        Button { settings = true } label: { Image(systemName: "person.crop.circle") }.accessibilityLabel("Аккаунт и настройки")
    }
    private func stack(_ section: AppSection) -> some View {
        NavigationStack {
            Group {
                switch section {
                case .home: HomeView()
                case .library: LibraryView()
                case .search: SearchView()
                }
            }
            .navigationTitle(section.title)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { settingsButton } }
            .navigationDestination(for: Anime.self) { DetailView(initial: $0) }
        }
    }
}

struct PosterView: View {
    var anime: Anime
    var body: some View {
        Color.clear.aspectRatio(2 / 3, contentMode: .fit).overlay {
            AsyncImage(url: URL(string: anime.poster)) { image in image.resizable().scaledToFill() } placeholder: {
                Rectangle().fill(.quaternary).overlay { Image(systemName: "film").font(.largeTitle).foregroundStyle(.secondary) }
            }
        }
        .clipped().clipShape(RoundedRectangle(cornerRadius: 10))
        .accessibilityHidden(true)
    }
}

struct AnimeCard: View {
    var anime: Anime
    var caption: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            PosterView(anime: anime)
            Text(anime.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(2, reservesSpace: true)
            Text(caption ?? anime.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .contentShape(Rectangle())
    }
}

struct HomeView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                if !model.continueWatching.isEmpty {
                    shelf("Продолжить просмотр", anime: model.continueWatching, continuation: true)
                }
                let newEpisodes = model.library.filter { ["watching", "rewatching"].contains($0.status) && $0.anime.availableEpisodes > $0.episodes }.map(\.anime)
                if !newEpisodes.isEmpty { shelf("Новые серии", anime: newEpisodes) }
                let upcoming = model.library.map(\.anime).filter { ($0.nextAirDate ?? .distantPast) > Date() }.sorted { $0.nextEpisodeAt < $1.nextEpisodeAt }
                if !upcoming.isEmpty {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Скоро").font(.title2.bold())
                        ForEach(upcoming.prefix(5)) { anime in
                            NavigationLink(value: anime) {
                                HStack(spacing: 12) {
                                    PosterView(anime: anime).frame(width: 44)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(anime.title).font(.headline).foregroundStyle(.primary).lineLimit(2)
                                        if let date = anime.nextAirDate { Text(date, format: .dateTime.day().month().hour().minute()).font(.subheadline).foregroundStyle(.secondary) }
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                                }
                            }.buttonStyle(.plain)
                        }
                    }.padding(.horizontal)
                }
                if !model.catalog.isEmpty { shelf("Популярное", anime: model.catalog) }
                if model.catalog.isEmpty && model.continueWatching.isEmpty {
                    if model.loading { ProgressView("Загружаем каталог…").frame(maxWidth: .infinity).padding(.top, 100) }
                    else {
                        ContentUnavailableView {
                            Label("Пока пусто", systemImage: "film.stack")
                        } description: { Text("Обновите каталог или найдите аниме в поиске.") } actions: {
                            Button("Обновить") { Task { await model.reload() } }.buttonStyle(.bordered)
                        }
                    }
                }
                if model.session == nil {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Ваша коллекция — с вами").font(.title3.bold())
                        Text("Войдите в Shikimori, чтобы видеть свой список и синхронизировать просмотренные серии.").font(.subheadline).foregroundStyle(.secondary)
                        Button("Войти в Shikimori", systemImage: "person.crop.circle") { Task { await model.signIn() } }
                            .buttonStyle(.bordered).disabled(model.signingIn)
                    }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 16)).padding(.horizontal)
                }
            }.padding(.vertical)
        }.refreshable { await model.reload() }
    }
    private func shelf(_ title: String, anime: [Anime], continuation: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.title2.bold()).padding(.horizontal)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 16) {
                    ForEach(anime) { anime in
                        NavigationLink(value: anime) {
                            AnimeCard(anime: anime, caption: continuation ? "Серия \(continueEpisode(anime: anime, watched: model.rate(for: anime.id)?.episodes ?? 0, progress: model.progress[anime.id]))" : nil).frame(width: 150)
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal)
            }.scrollIndicators(.hidden)
        }
    }
}

struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @State private var status: WatchStatus = .watching
    @State private var query = ""
    private var entries: [LibraryItem] {
        model.library.filter { $0.status == status.rawValue && (query.isEmpty || $0.anime.title.localizedCaseInsensitiveContains(query)) }.sorted { $0.anime.title < $1.anime.title }
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
                            Picker("Статус", selection: $status) { ForEach(WatchStatus.allCases) { Text($0.title).tag($0) } }.pickerStyle(.menu)
                            Spacer()
                            if model.syncing { ProgressView().controlSize(.small) }
                            else if !model.pending.isEmpty { Image(systemName: "icloud.and.arrow.up").accessibilityLabel("Ожидает синхронизации") }
                            Text("\(entries.count)").foregroundStyle(.secondary)
                        }
                        if entries.isEmpty {
                            ContentUnavailableView("Нет аниме", systemImage: "rectangle.stack", description: Text("В этом разделе пока ничего нет."))
                        } else {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140, maximum: 200), spacing: 18)], alignment: .leading, spacing: 24) {
                                ForEach(entries, id: \.anime.id) { item in
                                    NavigationLink(value: item.anime) { AnimeCard(anime: item.anime, caption: "\(item.episodes) из \(item.anime.episodes > 0 ? String(item.anime.episodes) : "?") серий") }.buttonStyle(.plain)
                                }
                            }
                        }
                    }.padding()
                }.searchable(text: $query, prompt: "В моём списке").refreshable { await model.reloadLibrary(); await model.flush() }
            }
        }
    }
}

struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var results: [Anime] = []
    @State private var loading = false
    @State private var failure: String?
    var body: some View {
        ScrollView {
            if loading { ProgressView().padding(.top, 50) }
            else if let failure { ContentUnavailableView("Поиск недоступен", systemImage: "wifi.exclamationmark", description: Text(failure)) }
            else if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                ContentUnavailableView("Найдите свою историю", systemImage: "magnifyingglass", description: Text("Введите название аниме на русском или английском."))
            } else if results.isEmpty { ContentUnavailableView.search(text: query) }
            else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140, maximum: 200), spacing: 18)], alignment: .leading, spacing: 24) {
                    ForEach(results) { anime in NavigationLink(value: anime) { AnimeCard(anime: anime) }.buttonStyle(.plain).accessibilityIdentifier("anime-\(anime.id)") }
                }.padding()
            }
        }
        .searchable(text: $query, prompt: "Название аниме")
        .task(id: query) {
            let value = query.trimmingCharacters(in: .whitespacesAndNewlines)
            failure = nil
            guard !value.isEmpty else { results = []; loading = false; return }
            loading = true
            do {
                try await Task.sleep(for: .milliseconds(350))
                let found = try await model.service.search(value)
                try Task.checkCancellation()
                results = found; loading = false
            } catch is CancellationError {} catch { if !Task.isCancelled { failure = error.localizedDescription; loading = false } }
        }
    }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmLogout = false
    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section("Shikimori") {
                    if let account = model.session?.account {
                        Label(account.nickname, systemImage: "person.crop.circle.fill")
                        if !model.pending.isEmpty { Text("Ожидает синхронизации: \(model.pending.count)").foregroundStyle(.secondary) }
                        Button("Синхронизировать") { Task { await model.flush(); await model.reloadLibrary() } }.disabled(model.syncing)
                        Button("Выйти", role: .destructive) { confirmLogout = true }
                    } else { Button("Войти в Shikimori") { Task { await model.signIn() } }.disabled(model.signingIn) }
                }
                Section("Просмотр") {
                    Picker("Качество", selection: $model.preferredQuality) { ForEach([360, 480, 720, 1080], id: \.self) { Text("\($0)p").tag($0) } }
                    Toggle("Следующая серия автоматически", isOn: $model.autoNext)
                }
                Section { Text("После просмотра 90% серии она отмечается просмотренной. Позиция сохраняется на этом устройстве.").foregroundStyle(.secondary) }
                Section("О приложении") { LabeledContent("Kaeru", value: "Первая итерация для iOS") }
            }
            .navigationTitle("Настройки").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
            .confirmationDialog("Выйти из Shikimori?", isPresented: $confirmLogout, titleVisibility: .visible) {
                Button("Выйти", role: .destructive) { model.signOut() }
            } message: { Text("Прогресс останется на устройстве. Несинхронизированные изменения отправятся при следующем входе в этот аккаунт.") }
        }
        .onDisappear { model.savePreferences() }
    }
}
