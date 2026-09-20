import SwiftUI
import UserNotifications

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var confirmLogout = false
    @State private var updatingNotifications = false
    private var version: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "—"
        let build = info["CFBundleVersion"] as? String ?? "—"
        return "\(version) (\(build))"
    }
    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section("Shikimori") {
                    if let account = model.session?.account {
                        HStack(spacing: 12) {
                            AsyncImage(url: URL(string: account.avatar)) { image in image.resizable().scaledToFill() } placeholder: {
                                Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(.secondary)
                            }.frame(width: 44, height: 44).clipShape(Circle()).accessibilityHidden(true)
                            Text(account.nickname).font(.headline)
                        }
                        if !model.pending.isEmpty { Text("Ожидает синхронизации: \(model.pending.count)").foregroundStyle(.secondary) }
                        Button("Синхронизировать") { Task { await model.flush(); await model.reloadLibrary() } }.disabled(model.syncing)
                        Button("Выйти", role: .destructive) { confirmLogout = true }
                    } else { Button("Войти в Shikimori") { Task { await model.signIn() } }.disabled(model.signingIn) }
                }
                Section {
                    Toggle("Следующая серия автоматически", isOn: $model.autoNext)
                    Toggle("Пропускать эндинг автоматически", isOn: $model.preferences.autoSkipEnding)
                    Toggle("Картинка в картинке при выходе", isOn: $model.preferences.pipOnLeave)
                    Picker("Качество по умолчанию", selection: $model.preferredQuality) {
                        Text("Авто").tag(0)
                        ForEach([360, 480, 720, 1080], id: \.self) { Text("\($0)p").tag($0) }
                    }.accessibilityIdentifier("default-quality")
                    Picker("Порог просмотра", selection: $model.preferences.watchedThreshold) {
                        ForEach(thresholds, id: \.self) { value in Text("\(Int((value * 100).rounded())) %").tag(value) }
                    }.accessibilityIdentifier("watched-threshold")
                } header: { Text("Просмотр") } footer: {
                    Text("Серия отмечается просмотренной после выбранного порога. Пропуск эндинга работает, когда для серии известны его границы. Авто выбирает лучшее доступное качество.")
                }
                Section {
                    NavigationLink { StudioPreferencesView() } label: { Label("Приоритет озвучек", systemImage: "list.number") }
                } header: { Text("Озвучки") } footer: { Text("Порядок применяется, когда для аниме ещё нет запомненной озвучки.") }
                Section {
                    Toggle("Новые серии", isOn: Binding(get: {
                        model.notifications.isEnabled && model.notifications.authorizationStatus != .denied
                    }, set: { enabled in
                        updatingNotifications = true
                        Task {
                            await model.setNotificationsEnabled(enabled)
                            updatingNotifications = false
                        }
                    })).disabled(updatingNotifications)
                    if model.notifications.authorizationStatus == .denied {
                        Text("Уведомления запрещены в настройках системы.").font(.footnote).foregroundStyle(.secondary)
                        Button("Открыть настройки") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                        }
                    }
                    if let message = model.notifications.errorMessage { Text(message).font(.footnote).foregroundStyle(.secondary) }
                } header: { Text("Уведомления") } footer: {
                    Text("Kaeru сообщит о новых сериях того, что вы смотрите. Время фоновой проверки определяет система.")
                }
                // Real downloads surface is supplied by the offline worker. Device/social entry points remain owned by parent integration.
                Section {
                    NavigationLink { DownloadsView(manager: model.downloads) } label: { Label("Загрузки", systemImage: "arrow.down.circle") }
                }
                Section("Трансляция") {
                    HStack {
                        Label(model.cast.receiverName ?? "Chromecast", systemImage: "tv")
                        Spacer()
                        CastButton(manager: model.cast)
                            .frame(width: 44, height: 44)
                    }
                    if model.cast.isConnected {
                        NavigationLink { CastControlsView(manager: model.cast) } label: {
                            Label("Управление трансляцией", systemImage: "slider.horizontal.3")
                        }
                    }
                }
                Section("Устройства") {
                    NavigationLink { DevicePairingView() } label: {
                        Label("Подключить Android TV", systemImage: "tv.and.arrow.forward")
                    }
                }
                Section(TogetherCopy.watchTogether) {
                    NavigationLink { TogetherView(manager: model.together) } label: {
                        Label(TogetherCopy.watchTogether, systemImage: "person.2.wave.2")
                    }
                    if model.together.phase == .live, let peer = model.together.peerName {
                        Text("Подключён: \(peer)").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section {
                    SecureField("Свой токен Kodik", text: $model.kodikToken)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("kodik-token")
                    if !model.kodikToken.isEmpty { Button("Очистить токен", role: .destructive) { model.kodikToken = "" } }
                } header: { Text("Источник видео") } footer: { Text("Свой токен нужен, только если публичный токен Kodik перестал работать. Оставьте поле пустым для автоматического выбора.") }
                Section {
                    Picker("Оформление", selection: Binding(
                        get: { AppAppearance(stored: model.preferences.appearance) },
                        set: { model.preferences.appearance = $0.rawValue })) {
                        ForEach(AppAppearance.allCases) { Text($0.title).tag($0) }
                    }.accessibilityIdentifier("appearance")
                } header: { Text("Оформление") } footer: {
                    Text("«Как в системе» следует настройке iOS, включая расписание автоматической тёмной темы.")
                }
                Section("О приложении") {
                    LabeledContent("Kaeru", value: version).textSelection(.enabled)
                    NavigationLink { UpdatesView() } label: {
                        LabeledContent {
                            // The same sentence the home row says, so the two read as one piece of
                            // news about one release rather than two.
                            Text(model.availableUpdate.map { UpdateCopy.available($0.version) } ?? "")
                        } label: {
                            Label(UpdateCopy.title, systemImage: "arrow.down.app")
                        }
                    }
                }
            }
            .navigationTitle("Настройки").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
            .confirmationDialog("Выйти из Shikimori?", isPresented: $confirmLogout, titleVisibility: .visible) {
                Button("Выйти", role: .destructive) { model.signOut() }
            } message: { Text("Прогресс останется на устройстве. Несинхронизированные изменения отправятся при следующем входе в этот аккаунт.") }
        }
        .onChange(of: model.preferences) { _, _ in model.savePreferences() }
        .onChange(of: model.preferredQuality) { _, _ in model.savePreferences() }
        .onChange(of: model.autoNext) { _, _ in model.savePreferences() }
        .onChange(of: model.kodikToken) { _, _ in model.savePreferences() }
        .task { await model.notifications.refreshAuthorization() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.notifications.refreshAuthorization() } }
        }
    }
    private var thresholds: [Double] { Array(Set([0.8, 0.85, 0.9, 0.95, model.preferences.watchedThreshold])).sorted() }
}

private struct StudioPreferencesView: View {
    @Environment(AppModel.self) private var model
    @State private var typed = ""
    @State private var editMode: EditMode = .inactive
    private var studios: [String] { model.preferences.studios.isEmpty ? PlaybackPreferences.defaultStudios : model.preferences.studios }
    private var candidate: String { typed.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canAdd: Bool { !candidate.isEmpty && !studios.contains { $0.caseInsensitiveCompare(candidate) == .orderedSame } }
    var body: some View {
        List {
            Section {
                ForEach(studios, id: \.self) { studio in
                    Text(studio).deleteDisabled(studios.count <= 1)
                        .contextMenu {
                            if let index = studios.firstIndex(of: studio) {
                                Button("Поднять", systemImage: "arrow.up") { move(index, by: -1) }.disabled(index == 0)
                                Button("Опустить", systemImage: "arrow.down") { move(index, by: 1) }.disabled(index == studios.count - 1)
                            }
                        }
                }
                .onMove { source, destination in var values = studios; values.move(fromOffsets: source, toOffset: destination); save(values) }
                .onDelete { offsets in
                    var values = studios; values.remove(atOffsets: offsets)
                    if !values.isEmpty { save(values) }
                }
            } footer: { Text("Выше — предпочтительнее. Нажмите «Править», чтобы менять порядок и удалять студии.") }
            Section {
                TextField("Добавить студию", text: $typed).autocorrectionDisabled().onSubmit(add)
                Button("Добавить", systemImage: "plus", action: add).disabled(!canAdd)
            }
            if !model.preferences.studios.isEmpty {
                Section { Button("Сбросить порядок") { save([]) } }
            }
        }
        .navigationTitle("Приоритет озвучек").navigationBarTitleDisplayMode(.inline)
        .toolbar { EditButton() }
        .environment(\.editMode, $editMode)
    }
    private func add() { guard canAdd else { return }; save(studios + [candidate]); typed = "" }
    private func save(_ values: [String]) { model.preferences.studios = values; model.savePreferences() }
    private func move(_ index: Int, by offset: Int) {
        var values = studios
        guard values.indices.contains(index + offset) else { return }
        values.swapAt(index, index + offset); save(values)
    }
}
