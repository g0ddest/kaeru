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
    /// One navigation path per section, so a deep link can put a title on screen without taking
    /// the other two tabs apart.
    @State private var paths: [AppSection: [Anime]] = [:]
    @State private var deepLinkRoute: PlaybackRoute?
    @State private var pairingOpen = false
    @State private var togetherOpen = false
    /// An invitation that arrived with nobody signed in. Opened as soon as somebody is.
    @State private var heldLink: DeepLink?
    var body: some View {
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
        // The viewer's choice, and by default the system's. What stood here forced dark on every
        // iPad: it overruled a phone deliberately set to light, and repainted the whole app on
        // screen whenever a Split View divider changed the size class.
        .preferredColorScheme(scheme)
        .sheet(isPresented: $settings) { SettingsView() }
        .alert("Kaeru", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK", role: .cancel) { model.error = nil }
        } message: { Text(model.error ?? "") }
        .sheet(isPresented: $pairingOpen) {
            NavigationStack { DevicePairingView() }
        }
        .sheet(isPresented: $togetherOpen) {
            NavigationStack { TogetherView(manager: model.together) }
        }
        .fullScreenCover(item: $deepLinkRoute) { PlayerScreen(anime: $0.anime, episode: $0.episode, model: model) }
        .onOpenURL { open($0) }
        // A notification tap hands its URL to the app delegate, which has no view to route from.
        .onChange(of: ApplicationRuntime.shared.pendingURL) { _, url in if url != nil { openPending() } }
        .onChange(of: model.session?.account.id) { _, id in
            guard id != nil, let held = heldLink else { return }
            heldLink = nil
            open(held)
        }
        .task { await model.start() }
        .task { openPending() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await model.flush() } } }
    }
    private var scheme: ColorScheme? {
        switch AppAppearance(stored: model.preferences.appearance) {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
    private var settingsButton: some View {
        Button { settings = true } label: { Image(systemName: "person.crop.circle") }.accessibilityLabel("Аккаунт и настройки")
    }
    private func path(_ section: AppSection) -> Binding<[Anime]> {
        Binding(get: { paths[section] ?? [] }, set: { paths[section] = $0 })
    }
    private func stack(_ section: AppSection) -> some View {
        NavigationStack(path: path(section)) {
            Group {
                switch section {
                case .home: HomeView()
                case .library: LibraryView(onSearch: { selection = .search })
                case .search: SearchView()
                }
            }
            .navigationTitle(section.title)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { settingsButton } }
            .navigationDestination(for: Anime.self) { DetailView(initial: $0) }
        }
    }

    /// Takes the URL a notification tap left behind, exactly once: reading it clears it, so a
    /// scene that comes back a second time does not navigate away from wherever the viewer got to.
    private func openPending() {
        guard let url = ApplicationRuntime.shared.pendingURL else { return }
        ApplicationRuntime.shared.pendingURL = nil
        open(url)
    }

    private func open(_ url: URL) {
        guard let link = DeepLink.parse(url) else { return }
        open(link)
    }

    private func open(_ link: DeepLink) {
        switch DeepLinkRouting.destination(for: link, signedIn: model.session != nil, playerOpen: model.playersOpen > 0) {
        case .title(let id, let episode): Task { await openTitle(id: id, episode: episode) }
        case .watch(let invitation):
            // Settings closes first: two sheets cannot be raised at once, and the invitation may
            // well have been honoured the moment somebody signed in from that very screen.
            settings = false
            Task { await model.together.join(invitation); togetherOpen = true }
        case .pair(let invitation):
            settings = false
            model.pairing.open(invitation)
            pairingOpen = true
        case .held(let value): heldLink = value
        }
    }

    private func openTitle(id: Int, episode: Int?) async {
        guard let anime = await model.anime(id: id) else { return }
        selection = .home
        paths[.home] = [anime]
        guard let episode else { return }
        // A link can name an episode the title no longer has; the player is given one it can open.
        let playable = max(anime.availableEpisodes, model.rate(for: id)?.episodes ?? 0)
        guard playable > 0 else { return }
        deepLinkRoute = PlaybackRoute(anime: anime, episode: min(episode, playable))
    }
}
