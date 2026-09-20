import SwiftUI

enum AppSection: String, CaseIterable, Identifiable {
    case home, search, library, downloads, recent, television, together, more
    var id: Self { self }
    var title: String {
        switch self {
        case .home: "Главная"
        case .search: "Поиск"
        case .library: "Мой список"
        case .downloads: "Загрузки"
        case .recent: "Недавно добавленные"
        case .television: "Телевизор"
        case .together: "Совместный просмотр"
        case .more: "Ещё"
        }
    }
    var icon: String {
        switch self {
        case .home: "play.house"
        case .search: "magnifyingglass"
        case .library: "rectangle.stack"
        case .downloads: "arrow.down.circle"
        case .recent: "clock"
        case .television: "tv"
        case .together: "person.2.wave.2"
        case .more: "ellipsis.circle"
        }
    }
    /// What the phone carries along the bottom.
    static let tabs: [AppSection] = [.home, .search, .library, .downloads, .more]
    /// How the sidebar groups the same app: the two places you go first, then your own shelves,
    /// then the screens that involve another device.
    static let groups: [(String?, [AppSection])] = [
        (nil, [.home, .search]),
        ("Библиотека", [.library, .downloads, .recent]),
        ("Устройства", [.television, .together])
    ]
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.scenePhase) private var scenePhase
    @State private var selection: AppSection = .home
    @State private var settings = false
    /// One navigation path per section, so a deep link can put a title on screen without taking
    /// the other sections apart.
    @State private var paths: [AppSection: NavigationPath] = [:]
    @State private var deepLinkRoute: PlaybackRoute?
    @State private var pairingOpen = false
    @State private var togetherOpen = false
    /// An invitation that arrived with nobody signed in. Opened as soon as somebody is.
    @State private var heldLink: DeepLink?
    var body: some View {
        Group {
            if sizeClass == .regular {
                NavigationSplitView {
                    sidebar
                } detail: { stack(selection) }
            } else {
                TabView(selection: $selection) {
                    ForEach(AppSection.tabs) { section in
                        stack(section).tabItem { Label(section.title, systemImage: section.icon) }.tag(section)
                    }
                }
                .tint(Palette.accent)
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
        // A universal link is not a URL the app is opened with — it arrives as a browsing activity,
        // and `onOpenURL` never sees it. Without this line an invitation tapped in a messenger went
        // to Safari and the landing page, which is what a phone without the app is shown.
        .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
            if let url = activity.webpageURL { open(url) }
        }
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

    /// The sidebar: groups with their own headings, and the viewer pinned to the bottom of it.
    private var sidebar: some View {
        List(selection: Binding<AppSection?>(get: { selection }, set: { if let value = $0 { selection = value } })) {
            ForEach(Array(AppSection.groups.enumerated()), id: \.offset) { _, group in
                Section {
                    ForEach(group.1) { section in
                        Label(section.title, systemImage: section.icon).tag(section)
                    }
                } header: {
                    if let title = group.0 { Text(title).font(.footnote.weight(.semibold)).foregroundStyle(Palette.inkSoft) }
                }
            }
        }
        .listStyle(.sidebar)
        .tint(Palette.accent)
        .navigationTitle("Kaeru")
        .safeAreaInset(edge: .bottom, spacing: 0) { viewerRow }
    }

    private var viewerRow: some View {
        Button { settings = true } label: {
            HStack(spacing: 10) {
                AsyncImage(url: URL(string: model.session?.account.avatar ?? "")) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(Palette.inkSoft)
                }
                .frame(width: 30, height: 30).clipShape(Circle())
                Text(model.session?.account.nickname ?? "Гость")
                    .font(.subheadline.weight(.medium)).foregroundStyle(Palette.ink).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 18).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Аккаунт и настройки")
        .background(.bar)
    }

    private var scheme: ColorScheme? {
        switch AppAppearance(stored: model.preferences.appearance) {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
    private func path(_ section: AppSection) -> Binding<NavigationPath> {
        Binding(get: { paths[section] ?? NavigationPath() }, set: { paths[section] = $0 })
    }
    private func stack(_ section: AppSection) -> some View {
        NavigationStack(path: path(section)) {
            Group {
                switch section {
                case .home: HomeView()
                case .search: SearchView()
                case .library: LibraryView(onSearch: { selection = .search })
                case .recent: LibraryView(mode: .recent, onSearch: { selection = .search })
                case .downloads: DownloadsView(manager: model.downloads) { entry in
                    deepLinkRoute = PlaybackRoute(anime: entry.anime, episode: entry.episode)
                }
                case .television: DevicePairingView(embedded: true)
                case .together: TogetherView(manager: model.together, embedded: true)
                case .more: MoreView(openSettings: { settings = true })
                }
            }
            .navigationTitle(section.title)
            .navigationDestination(for: Anime.self) { DetailView(initial: $0) }
            .navigationDestination(for: ShelfRoute.self) { ShelfScreen(route: $0) }
            .navigationDestination(for: UpdatesRoute.self) { _ in UpdatesView() }
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
            // The room opens now, and the joining happens behind it. It used to be the other way
            // round, and `join` is a websocket handshake with up to half a minute of backoff
            // behind it — so a tapped invitation brought the app to the front and then did
            // nothing visible at all, for as long as the relay took to answer or to give up. The
            // screen has a state for every part of that; it could not show any of them.
            togetherOpen = true
            Task { await model.together.join(invitation) }
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
        paths[.home] = NavigationPath([anime])
        guard let episode else { return }
        // A link can name an episode the title no longer has; the player is given one it can open.
        let playable = max(anime.availableEpisodes, model.rate(for: id)?.episodes ?? 0)
        guard playable > 0 else { return }
        deepLinkRoute = PlaybackRoute(anime: anime, episode: min(episode, playable))
    }
}
