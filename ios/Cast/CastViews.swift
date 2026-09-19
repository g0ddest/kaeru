import SwiftUI
import GoogleCast

/// Google owns discovery, permissions education, device selection and the connected-device dialog.
struct CastButton: UIViewRepresentable {
    let manager: CastManager
    func makeUIView(context: Context) -> GCKUICastButton {
        manager.start()
        let button = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        button.tintColor = .label
        button.accessibilityLabel = "Chromecast"
        button.accessibilityHint = "Выбрать телевизор или управлять трансляцией"
        return button
    }
    func updateUIView(_ button: GCKUICastButton, context: Context) { button.tintColor = .label }
}

/// Present in a sheet or the remote player area; episode policy remains with the parent player.
struct CastControlsView: View {
    let manager: CastManager
    var translations: [Translation] = []
    @State private var scrubPosition = 0.0
    @State private var scrubbing = false
    private var busy: Bool { manager.isResolving || manager.state.isBuffering || !manager.isConnected }

    var body: some View {
        Form {
            Section {
                Label(manager.receiverName ?? "Chromecast", systemImage: "tv")
                if let selection = manager.state.selection {
                    Text(selection.anime.title).font(.headline)
                    Text("Серия \(selection.episode)").foregroundStyle(.secondary)
                }
                if case .suspended = manager.connection {
                    Label("Восстанавливаем соединение…", systemImage: "wifi.exclamationmark")
                } else if manager.isResolving || manager.state.isBuffering {
                    ProgressView("Открываем видео на телевизоре…")
                }
            }
            if let error = manager.error {
                Section {
                    Text(error).foregroundStyle(.secondary)
                    Button("Повторить") { Task { await manager.retry() } }.disabled(!manager.isConnected)
                }
            }
            Section {
                if manager.state.duration > 0 {
                    Slider(value: Binding(get: { scrubbing ? scrubPosition : manager.state.position },
                                          set: { scrubPosition = $0 }),
                           in: 0...max(1, manager.state.duration),
                           onEditingChanged: { editing in
                               scrubbing = editing
                               if !editing { manager.seek(to: scrubPosition) }
                           })
                        .accessibilityLabel("Позиция воспроизведения")
                        .accessibilityValue(time(manager.state.position))
                        .disabled(busy)
                    HStack {
                        Text(time(scrubbing ? scrubPosition : manager.state.position))
                        Spacer()
                        Text(time(manager.state.duration))
                    }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                HStack(spacing: 32) {
                    Spacer()
                    Button { manager.seek(to: manager.state.position - 10) } label: { Image(systemName: "gobackward.10") }
                        .accessibilityLabel("Назад на 10 секунд")
                    Button {
                        if manager.state.isPlaying { manager.pause() } else { manager.play() }
                    } label: { Image(systemName: manager.state.isPlaying ? "pause.fill" : "play.fill") }
                        .accessibilityLabel(manager.state.isPlaying ? "Пауза" : "Воспроизвести")
                    Button { manager.seek(to: manager.state.position + 10) } label: { Image(systemName: "goforward.10") }
                        .accessibilityLabel("Вперёд на 10 секунд")
                    Spacer()
                }.font(.title2).buttonStyle(.borderless).frame(minHeight: 44).disabled(busy)
            }
            if manager.state.selection != nil {
                Section {
                    if !translations.isEmpty {
                        Picker("Озвучка", selection: Binding(get: { manager.state.selection?.translation ?? 0 },
                                                           set: { value in Task { await manager.selectTranslation(value) } })) {
                            ForEach(translations) { translation in
                                Text(translation.title).tag(translation.id)
                                    .disabled(translation.episodes > 0 && translation.episodes < (manager.state.selection?.episode ?? 1))
                            }
                        }
                    }
                    Picker("Качество", selection: Binding(get: { manager.state.selection?.quality ?? 0 },
                                                         set: { value in Task { await manager.selectQuality(value) } })) {
                        Text("Авто").tag(0)
                        ForEach(manager.qualities, id: \.self) { quality in Text("\(quality)p").tag(quality) }
                    }
                }.disabled(busy)
            }
            Section {
                Button("Устройства Chromecast") { manager.presentDevices() }
                Button("Пульт Chromecast") { manager.presentExpandedControls() }.disabled(!manager.isConnected)
                Button("Остановить трансляцию", role: .destructive) { manager.stopCasting() }
                    .disabled(manager.connection == .disconnected)
            }
        }.navigationTitle("Chromecast")
    }

    private func time(_ seconds: Double) -> String {
        let value = Int(min(CastLoadPayload.validTime(seconds), Double(Int32.max)))
        return value >= 3600 ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)
            : String(format: "%d:%02d", value / 60, value % 60)
    }
}

/// Place above the tab bar when the user leaves the player, keeping remote playback discoverable.
struct CastMiniControls: View {
    let manager: CastManager
    var body: some View {
        if manager.receiverName != nil {
            HStack {
                Button { manager.presentExpandedControls() } label: {
                    Label {
                        VStack(alignment: .leading) {
                            Text(manager.state.selection?.anime.title ?? "Chromecast").lineLimit(1)
                            Text(manager.receiverName ?? "").font(.caption).foregroundStyle(.secondary)
                        }
                    } icon: { Image(systemName: "tv") }
                }.buttonStyle(.plain)
                Spacer()
                Button {
                    if manager.state.isPlaying { manager.pause() } else { manager.play() }
                } label: { Image(systemName: manager.state.isPlaying ? "pause.fill" : "play.fill").frame(width: 44, height: 44) }
                    .accessibilityLabel(manager.state.isPlaying ? "Пауза на телевизоре" : "Продолжить на телевизоре")
                    .disabled(!manager.isConnected || manager.isResolving || manager.state.isBuffering)
                CastButton(manager: manager).frame(width: 44, height: 44)
            }.padding(.horizontal).background(.bar)
        }
    }
}
