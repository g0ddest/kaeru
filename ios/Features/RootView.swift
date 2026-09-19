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
        .preferredColorScheme(sizeClass == .regular ? .dark : nil)
        .background { if sizeClass == .regular { Color.black.ignoresSafeArea() } }
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
                case .library: LibraryView(onSearch: { selection = .search })
                case .search: SearchView()
                }
            }
            .navigationTitle(section.title)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { settingsButton } }
            .navigationDestination(for: Anime.self) { DetailView(initial: $0) }
        }
    }
}
