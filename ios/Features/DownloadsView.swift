import SwiftUI

struct DownloadsView: View {
    @Bindable var manager: DownloadManager
    /// The parent supplies playback/navigation using its existing routes.
    var onPlay: ((DownloadEntry) -> Void)?
    @State private var showsSettings = false
    @State private var deletingAll = false

    var body: some View {
        List {
            if !manager.isConnected {
                Label("Нет подключения к сети", systemImage: "wifi.slash")
                    .foregroundStyle(.secondary)
            }
            if let error = manager.errorMessage {
                Section {
                    Text(error).foregroundStyle(.red)
                    Button("Закрыть") { manager.clearError() }
                }
            }
            if manager.entries.isEmpty {
                ContentUnavailableView("Нет загрузок", systemImage: "arrow.down.circle", description: Text("Скачивайте эпизоды на странице аниме, чтобы смотреть без интернета."))
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    LabeledContent("На устройстве", value: ByteCountFormatter.string(fromByteCount: manager.usedBytes, countStyle: .file))
                    if let limit = manager.policies.storageLimitBytes {
                        LabeledContent("Лимит", value: ByteCountFormatter.string(fromByteCount: limit, countStyle: .file))
                    }
                }
                ForEach(manager.groupedEntries, id: \.anime.id) { group in
                    Section {
                        ForEach(group.entries) { entry in
                            downloadRow(entry)
                                .contextMenu { actions(entry) }
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    Button("Удалить", role: .destructive) { manager.remove(id: entry.id) }
                                }
                        }
                    } header: {
                        Text(group.anime.title).textCase(nil)
                    }
                }
            }
        }
        .navigationTitle("Загрузки")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Настройки загрузок", systemImage: "slider.horizontal.3") { showsSettings = true }
                    if !manager.entries.isEmpty {
                        Button("Удалить все загрузки", systemImage: "trash", role: .destructive) { deletingAll = true }
                    }
                } label: { Label("Действия", systemImage: "ellipsis.circle") }
            }
        }
        .confirmationDialog("Удалить все загруженные эпизоды?", isPresented: $deletingAll, titleVisibility: .visible) {
            Button("Удалить все", role: .destructive) { manager.removeAll() }
        }
        .sheet(isPresented: $showsSettings) { DownloadSettingsView(manager: manager) }
    }

    private func downloadRow(_ entry: DownloadEntry) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Серия \(entry.episode)").font(.headline)
                    Text("\(entry.quality > 0 ? "\(entry.quality)p" : "Лучшее качество") · Озвучка \(entry.translation)")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 12)
                if entry.state == .completed, let onPlay {
                    Button { onPlay(entry) } label: { Image(systemName: "play.circle.fill").font(.title2) }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Смотреть серию \(entry.episode)")
                } else {
                    Menu { actions(entry) } label: { Image(systemName: "ellipsis.circle").font(.title3) }
                        .accessibilityLabel("Действия с серией \(entry.episode)")
                }
            }
            if entry.state.isPending || entry.state == .paused {
                ProgressView(value: entry.progress)
                    .accessibilityLabel("Загрузка серии \(entry.episode)")
                    .accessibilityValue(entry.progress.formatted(.percent.precision(.fractionLength(0))))
            }
            HStack {
                Text(entry.failure?.message ?? entry.state.title)
                    .foregroundStyle(entry.state == .failed ? Color.red : Color.secondary)
                Spacer()
                if entry.bytes > 0 { Text(ByteCountFormatter.string(fromByteCount: entry.bytes, countStyle: .file)).foregroundStyle(.secondary) }
            }.font(.caption)
        }.padding(.vertical, 4)
    }

    @ViewBuilder private func actions(_ entry: DownloadEntry) -> some View {
        if entry.state == .completed, let onPlay {
            Button("Смотреть", systemImage: "play.fill") { onPlay(entry) }
        }
        if entry.state.isPending {
            Button("Приостановить", systemImage: "pause") { manager.pause(id: entry.id) }
            Button("Отменить загрузку", systemImage: "xmark.circle") { manager.cancel(id: entry.id) }
        }
        if entry.state == .paused {
            Button("Продолжить", systemImage: "arrow.down.circle") { manager.resume(id: entry.id) }
        }
        if entry.state == .failed || entry.state == .cancelled {
            Button("Повторить", systemImage: "arrow.clockwise") { manager.retry(id: entry.id) }
        }
        Button("Удалить", systemImage: "trash", role: .destructive) { manager.remove(id: entry.id) }
    }
}

private struct DownloadSettingsView: View {
    @Bindable var manager: DownloadManager
    @Environment(\.dismiss) private var dismiss
    private let gib: Int64 = 1024 * 1024 * 1024
    private func binding<Value>(_ keyPath: WritableKeyPath<DownloadPolicies, Value>) -> Binding<Value> {
        Binding(get: { manager.policies[keyPath: keyPath] }, set: { value in
            var policies = manager.policies; policies[keyPath: keyPath] = value; manager.updatePolicies(policies)
        })
    }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Только по Wi-Fi", isOn: binding(\.wifiOnly))
                    Picker("Качество", selection: binding(\.quality)) {
                        Text("Лучшее доступное").tag(0)
                        ForEach([360, 480, 720, 1080], id: \.self) { Text("\($0)p").tag($0) }
                    }
                } footer: {
                    Text("Изменение Wi-Fi перезапускает текущие загрузки с новой сетевой настройкой.")
                }
                Section {
                    Picker("Лимит хранения", selection: binding(\.storageLimitBytes)) {
                        Text("Без лимита").tag(Int64?.none)
                        ForEach([2, 5, 10, 20, 50], id: \.self) { size in
                            Text("\(size) ГБ").tag(Optional(Int64(size) * gib))
                        }
                    }
                    Toggle("Удалять просмотренные", isOn: binding(\.deleteWatched))
                } footer: {
                    Text("Лимит проверяется перед добавлением загрузок и не удаляет сохранённые эпизоды. Просмотренный эпизод удаляется после завершения воспроизведения или перехода к следующему.")
                }
            }
            .navigationTitle("Загрузки")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        }
    }
}
