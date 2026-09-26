import SwiftUI

/// The cast control: one press that opens Google's own device picker, and a glyph that says
/// whether anything is on the other end of it.
///
/// Not `GCKUICastButton`. That is a `UIButton`, and SwiftUI's toolbar hosts what it is handed
/// through a view controller — on iOS 26 that path aborted the app the moment the player opened.
/// Wrapping it in a container stopped the crash and left the glyph undrawn: a bare `UIView` has no
/// intrinsic size a toolbar will place. So the control is SwiftUI throughout and the SDK keeps the
/// only two jobs that are genuinely its own — finding receivers and running the picker.
///
/// Everything else follows from being an ordinary `Button`: it draws at any size, rotates with the
/// screen, and comes back with the toolbar when AVKit leaves full screen, because there is no
/// hosted view controller whose lifetime could disagree with the view's.
struct CastButton: View {
    let manager: CastManager
    var body: some View {
        Button {
            manager.presentDevices()
        } label: {
            CastGlyph(connected: manager.isConnected)
                .frame(width: 24, height: 24)
                // Amber only when something is actually playing somewhere else. A control that is
                // coloured at rest is a control that looks pressed.
                .foregroundStyle(manager.isConnected ? Palette.accent : Color.primary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(manager.receiverName.map { "Трансляция: \($0)" } ?? "Chromecast")
        .accessibilityHint("Выбрать телевизор или управлять трансляцией")
        .accessibilityIdentifier("cast-button")
        .onAppear { manager.start() }
    }
}

/// The cast glyph itself: a screen with waves in the corner, filled in while a receiver is playing.
///
/// Drawn rather than borrowed. There is no cast symbol in SF Symbols, and the SDK will only hand
/// its own out inside the `UIButton` this control exists to avoid — so the outline is Material's
/// `cast` / `cast_connected`, point for point, which is the same mark the Android client puts in
/// its own toolbar. Two apps, one glyph.
struct CastGlyph: Shape {
    var connected: Bool

    func path(in rect: CGRect) -> Path {
        // Material's icons are authored on a 24-point grid; everything below is in that grid and
        // scaled once, here, so the numbers can be read against the original.
        let scale = min(rect.width, rect.height) / 24
        let origin = CGPoint(x: rect.midX - 12 * scale, y: rect.midY - 12 * scale)
        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: origin.x + x * scale, y: origin.y + y * scale)
        }
        var path = Path()

        // The dot, and the two waves coming out of it.
        path.move(to: point(1, 18))
        path.addLine(to: point(1, 21))
        path.addLine(to: point(4, 21))
        path.addCurve(to: point(1, 18), control1: point(4, 19.34), control2: point(2.66, 18))
        path.closeSubpath()

        path.move(to: point(1, 14))
        path.addLine(to: point(1, 16))
        path.addCurve(to: point(6, 21), control1: point(3.76, 16), control2: point(6, 18.24))
        path.addLine(to: point(8, 21))
        path.addCurve(to: point(1, 14), control1: point(8, 17.13), control2: point(4.87, 14))
        path.closeSubpath()

        path.move(to: point(1, 10))
        path.addLine(to: point(1, 12))
        path.addCurve(to: point(10, 21), control1: point(5.97, 12), control2: point(10, 16.03))
        path.addLine(to: point(12, 21))
        path.addCurve(to: point(1, 10), control1: point(12, 14.92), control2: point(7.07, 10))
        path.closeSubpath()

        // The screen: one closed outline traced round its own frame, which is how the mark keeps
        // its hairline weight without a stroke that would thicken as the control grows.
        path.move(to: point(21, 3))
        path.addLine(to: point(3, 3))
        path.addCurve(to: point(1, 5), control1: point(1.9, 3), control2: point(1, 3.9))
        path.addLine(to: point(1, 8))
        path.addLine(to: point(3, 8))
        path.addLine(to: point(3, 5))
        path.addLine(to: point(21, 5))
        path.addLine(to: point(21, 19))
        path.addLine(to: point(14, 19))
        path.addLine(to: point(14, 21))
        path.addLine(to: point(21, 21))
        path.addCurve(to: point(23, 19), control1: point(22.1, 21), control2: point(23, 20.1))
        path.addLine(to: point(23, 5))
        path.addCurve(to: point(21, 3), control1: point(23, 3.9), control2: point(22.1, 3))
        path.closeSubpath()

        // Connected: the screen fills in, which is the whole of how this mark says «идёт сюда».
        if connected {
            path.move(to: point(19, 7))
            path.addLine(to: point(5, 7))
            path.addLine(to: point(5, 8.63))
            path.addCurve(to: point(13.37, 17), control1: point(8.96, 9.91), control2: point(12.09, 13.04))
            path.addLine(to: point(19, 17))
            path.addLine(to: point(19, 7))
            path.closeSubpath()
        }
        return path
    }
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
                    Text(selection.anime.title).font(.kaeruHeadline)
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
                    }.font(.kaeruCaption.monospacedDigit()).foregroundStyle(.secondary)
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
                }.font(.kaeruTitle2).buttonStyle(.borderless).frame(minHeight: 44).disabled(busy)
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
                            Text(manager.receiverName ?? "").font(.kaeruCaption).foregroundStyle(.secondary)
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
