import SwiftUI

/// Where the bar sits, for everything that has to keep clear of it or hold it up.
@MainActor enum PlayerBarLayout {
    /// From the bar to the edges of the picture.
    static let margin: CGFloat = 16
    static let height: CGFloat = 44
    /// Where each player window's bar is, in the window's content, top-left origin — for the
    /// pointer monitor in PlayerWindow, which hears every move before SwiftUI does and has to know
    /// that a pointer resting on the bar is about to press something there.
    private static var frames: [ObjectIdentifier: CGRect] = [:]
    static func setFrame(_ frame: CGRect?, in window: NSWindow) { frames[ObjectIdentifier(window)] = frame }
    /// The bar with a few points of slack around it, so a pointer on its edge still counts.
    static func contains(_ point: CGPoint, in window: NSWindow) -> Bool {
        frames[ObjectIdentifier(window)]?.insetBy(dx: -8, dy: -8).contains(point) ?? false
    }
}

/// The Mac player's controls: one dark, translucent strip along the bottom of the picture, as in
/// QuickTime — with the volume and picture in picture in it, where AVKit's inline bar would not
/// put them (see NativePlayer). Comes and goes with the window's toolbar (`chromeVisible`).
struct PlayerBar: View {
    let playback: PlaybackModel
    let surface: PlayerSurfaceModel
    /// Where a drag along the timeline has got to, from 0 to 1, until it is let go.
    @State private var scrub: Double?
    /// The right-hand clock counts down; a click on it shows the length instead.
    @State private var showsRemaining = true

    private var duration: Double { playback.duration }
    private var seekable: Bool { !playback.loading && duration > 0 }
    /// Where the playhead is drawn — where the drag is, while there is one.
    private var shownPosition: Double {
        if let scrub, let target = PlayerBarRules.seekTarget(fraction: scrub, duration: duration) { return target }
        return playback.position
    }

    var body: some View {
        let skip = playback.skipSeconds
        HStack(spacing: 4) {
            BarButton(symbol: playback.wantsPlayback ? "pause.fill" : "play.fill",
                      label: playback.wantsPlayback ? "Пауза" : "Воспроизвести", size: 17) {
                playback.setPlaying(!playback.wantsPlayback)
            }
            BarButton(symbol: skipSymbol("gobackward", skip), label: "Назад на \(skip) с") {
                playback.seek(by: -Double(skip))
            }
            .disabled(!seekable)
            BarButton(symbol: skipSymbol("goforward", skip), label: "Вперёд на \(skip) с") {
                playback.seek(by: Double(skip))
            }
            .disabled(!seekable)

            Text(PlayerBarRules.clock(shownPosition, reference: duration))
                .font(Self.clockFont).foregroundStyle(.white)
                .frame(minWidth: 40, alignment: .trailing)
                .padding(.leading, 6)
                .accessibilityHidden(true)
            timeline.padding(.horizontal, 8)
            Button {
                showsRemaining.toggle()
            } label: {
                Text(showsRemaining ? PlayerBarRules.remaining(position: shownPosition, duration: duration)
                                    : PlayerBarRules.clock(duration))
                    .font(Self.clockFont).foregroundStyle(.white.opacity(0.75))
                    .frame(minWidth: 46, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain).focusable(false)
            .help(showsRemaining ? "Показать длительность серии" : "Показать, сколько осталось")
            .accessibilityHidden(true)
            .padding(.trailing, 6)

            VolumeControl(playback: playback).padding(.trailing, 6)

            // Only while there is a next episode to go to: never on the last one aired, nor on the
            // latest of a title still airing (`PlaybackModel.hasNext`).
            if playback.hasNext {
                BarButton(symbol: "forward.end.fill", label: "Следующая серия") { playback.nextNow() }
                    .disabled(playback.loading)
            }
            BarButton(symbol: surface.pictureInPictureActive ? "pip.exit" : "pip.enter",
                      label: "Картинка в картинке") { surface.togglePictureInPicture() }
                .disabled(!surface.pictureInPictureActive && !surface.pictureInPicturePossible)
            BarButton(symbol: surface.fullScreen ? "arrow.down.right.and.arrow.up.left"
                                                 : "arrow.up.left.and.arrow.down.right",
                      label: surface.fullScreen ? "Выйти из полноэкранного режима" : "Во весь экран") {
                surface.toggleFullScreen()
            }
        }
        .padding(.horizontal, 10)
        .frame(height: PlayerBarLayout.height)
        .background {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(.black.opacity(0.45))
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(.white.opacity(0.12), lineWidth: Metrics.hairline)
                }
        }
        .environment(\.colorScheme, .dark)
        .frame(maxWidth: 980)
    }

    private static let clockFont = Font.kaeruCaption.weight(.medium).monospacedDigit()

    /// The timeline: a drag shows where it would go, and the release goes there.
    private var timeline: some View {
        BarSlider(value: scrub ?? PlayerBarRules.progress(position: playback.position, duration: duration),
                  onChange: { value in
                      if scrub == nil { playback.holdChrome() }
                      scrub = value
                  },
                  onEnd: { value in
                      if let target = PlayerBarRules.seekTarget(fraction: value, duration: duration) {
                          playback.seek(to: target)
                      }
                      scrub = nil
                      playback.showChrome()
                  })
            .disabled(!seekable)
            .opacity(seekable ? 1 : 0.5)
            .help("Перемотка")
            // A slider to VoiceOver — the system's own, standing in for the drawn one — moving
            // by the player's step.
            .accessibilityRepresentation {
                // The range at least one step long: a step past its end is a precondition failure.
                let step = Double(max(1, playback.skipSeconds)), upper = max(duration, step)
                Slider(value: Binding(get: { min(upper, max(0, playback.position)) }, set: { playback.seek(to: $0) }),
                       in: 0...upper, step: step) { Text("Перемотка") }
                    .disabled(!seekable)
            }
            .accessibilityValue(PlayerBarRules.clock(playback.position, reference: duration))
    }

    /// «gobackward.10» where the system has a numbered arrow for the step, the plain arrow otherwise.
    private func skipSymbol(_ base: String, _ seconds: Int) -> String {
        [5, 10, 15, 30, 45, 60, 75, 90].contains(seconds) ? "\(base).\(seconds)" : base
    }
}

/// The speaker, and beside it the volume slider — tucked away until the pointer comes to the
/// speaker, as in QuickTime, so the timeline keeps the room. VoiceOver reaches the slider either
/// way, and it opens while VoiceOver is on it.
private struct VolumeControl: View {
    let playback: PlaybackModel
    @State private var hovering = false
    @State private var dragging = false
    @AccessibilityFocusState private var focused: Bool
    private var level: Float { playback.muted ? 0 : playback.volume }
    var body: some View {
        let expanded = PlayerBarRules.volumeExpanded(hovering: hovering, dragging: dragging, focused: focused)
        HStack(spacing: 2) {
            BarButton(symbol: PlayerBarRules.volumeSymbol(volume: playback.volume, muted: playback.muted),
                      label: playback.muted ? "Включить звук" : "Выключить звук") {
                playback.setMuted(!playback.muted)
            }
            BarSlider(value: Double(level), onChange: { playback.setVolume(Float($0)) },
                      onDragging: { dragging = $0 })
                .frame(width: 76)
                .padding(.horizontal, 4)
                .frame(width: expanded ? 84 : 0, alignment: .leading)
                .clipped()
                .help("Громкость")
                .accessibilityRepresentation {
                    Slider(value: Binding(get: { Double(level) }, set: { playback.setVolume(Float($0)) }),
                           in: 0...1, step: 0.1) { Text("Громкость") }
                }
                .accessibilityValue("\(Int((level * 100).rounded())) %")
                .accessibilityFocused($focused)
        }
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .animation(.easeOut(duration: 0.18), value: expanded)
    }
}

/// A round button of the bar: a white symbol, a soft disc under the pointer, its name read aloud
/// and shown as a tooltip.
private struct BarButton: View {
    let symbol: String
    let label: String
    var size: CGFloat = 15
    let action: () -> Void
    @Environment(\.isEnabled) private var enabled
    @State private var hovering = false
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(.white.opacity(enabled ? 1 : 0.35))
                .frame(width: 32, height: 32)
                .background(Circle().fill(.white.opacity(hovering && enabled ? 0.14 : 0)))
                .contentShape(Circle())
        }
        // Pressed by the pointer only. Space and the arrows are the player's (PlayerWindow's key
        // monitor), and a focused button would take Space for itself.
        .buttonStyle(.plain).focusable(false)
        .onHover { hovering = $0 }
        .accessibilityLabel(label)
        .help(label)
    }
}

/// A thin track the pointer drags along — the timeline and the volume. Amber up to the value, as
/// every progress mark in this app is; a knob while the pointer is on it.
private struct BarSlider: View {
    let value: Double
    var onChange: (Double) -> Void
    var onEnd: (Double) -> Void = { _ in }
    var onDragging: (Bool) -> Void = { _ in }
    @Environment(\.isEnabled) private var enabled
    @State private var hovering = false
    @State private var dragging = false
    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let filled = width * min(1, max(0, value))
            let lifted = enabled && (hovering || dragging)
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.28)).frame(height: lifted ? 6 : 4)
                Capsule().fill(Palette.accent).frame(width: filled, height: lifted ? 6 : 4)
                Circle().fill(.white).frame(width: 12, height: 12)
                    .shadow(color: .black.opacity(0.4), radius: 2)
                    .offset(x: filled - 6)
                    .opacity(lifted ? 1 : 0)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { drag in
                    if !dragging { dragging = true; onDragging(true) }
                    onChange(PlayerBarRules.fraction(at: drag.location.x, width: width))
                }
                .onEnded { drag in
                    dragging = false
                    onDragging(false)
                    let fraction = PlayerBarRules.fraction(at: drag.location.x, width: width)
                    onChange(fraction)
                    onEnd(fraction)
                })
            .onHover { hovering = $0 }
            .animation(.easeOut(duration: 0.12), value: lifted)
        }
        .frame(height: 20)
    }
}
