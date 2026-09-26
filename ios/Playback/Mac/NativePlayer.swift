import SwiftUI
import AVKit

/// The player on the Mac: the picture, this app's own control bar under it, and everything else
/// this app draws on top.
///
/// Not the iPad's arrangement (../NativePlayer.swift), and not AVKit's controls either. AVKit's
/// inline bar on the Mac puts the volume in the top right corner and picture in picture in the top
/// left, over the title bar's buttons, and has no API to move them; so the picture is a bare
/// `AVPlayerLayer` and the bar is ours (PlayerBar.swift), with the volume and picture in picture
/// in it. What goes full screen is the window, and a plain SwiftUI overlay goes with it — the
/// bar, the conversation, the skip button and the next episode included.
///
/// The pointer is the Mac's finger: a click on the picture plays and pauses, a double click makes
/// the window full screen, and the pointer moving brings the controls back.
struct NativePlayer<Overlay: View>: View {
    let playback: PlaybackModel
    /// The iPad's double tap by thirds. A click on a Mac plays and pauses instead, and ← → say
    /// where they went through the player's window (Features/Mac/PlayerWindow.swift); the call
    /// reads the same on both.
    var onDoubleTap: (PlayerTapZone) -> Void = { _ in }
    @ViewBuilder var overlay: () -> Overlay
    /// The screen stays on while the episode plays.
    @State private var awake = DisplayAwake()
    /// The picture's window and its picture in picture, for the bar's buttons.
    @State private var surface = PlayerSurfaceModel()
    /// Where the bar is over the picture, in this view's space.
    @State private var barFrame = CGRect.zero
    @State private var barWindowFrame = CGRect.zero
    /// How far down from the top the window's toolbar reaches over the picture.
    private let toolbarBand: CGFloat = 56
    var body: some View {
        PlayerSurface(playback: playback, surface: surface)
            // Under the title bar too. The toolbar comes and goes with the pointer, and a picture
            // laid out below it would jump every time it did.
            .ignoresSafeArea()
            .overlay { overlay() }
            .overlay(alignment: .bottom) {
                PlayerBar(playback: playback, surface: surface)
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: { barFrame = $0 }
                    // The window's content, top left, is SwiftUI's global space on a Mac.
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
                        barWindowFrame = frame
                        if let window = surface.window { PlayerBarLayout.setFrame(frame, in: window) }
                    }
                    .padding(.horizontal, PlayerBarLayout.margin)
                    .padding(.bottom, PlayerBarLayout.margin)
                    .opacity(playback.chromeVisible ? 1 : 0)
                    .allowsHitTesting(playback.chromeVisible)
                    .animation(.easeInOut(duration: 0.25), value: playback.chromeVisible)
            }
            .coordinateSpace(.named(Self.space))
            .onContinuousHover { phase in
                switch phase {
                case .active(let point):
                    // A pointer resting on the toolbar or on the bar is on its way to a button there.
                    if point.y < toolbarBand || barFrame.insetBy(dx: -8, dy: -8).contains(point) { playback.holdChrome() }
                    else { playback.showChrome() }
                case .ended:
                    // Gone out of the window — through the toolbar, say, to the menu bar — and on
                    // its way to no button here: the controls go in their three seconds, rather
                    // than staying over the picture until the pointer comes back.
                    playback.showChrome()
                }
            }
            // The pointer goes with the controls, as in every player on this system.
            .pointerVisibility(playback.chromeVisible ? .automatic : .hidden)
            // Up when the episode starts or stops, gone three seconds into playing — without
            // anybody having to move the pointer first. The iPad waits for a tap instead.
            .onChange(of: playback.isPlaying, initial: true) { _, playing in
                playback.showChrome()
                awake.set(playing)
            }
            .onChange(of: surface.window, initial: true) { _, window in
                // The frame is measured before the view is in a window.
                if let window { PlayerBarLayout.setFrame(barWindowFrame, in: window) }
            }
            .onDisappear {
                awake.set(false)
                if let window = surface.window { PlayerBarLayout.setFrame(nil, in: window) }
            }
    }
    private static var space: String { "player" }
}

/// What the bar needs from the picture beneath it: its window, to fill the screen with, and its
/// picture in picture, which only the layer the picture is drawn in can start.
@MainActor @Observable final class PlayerSurfaceModel: NSObject, @preconcurrency AVPictureInPictureControllerDelegate {
    private(set) var fullScreen = false
    /// A picture in picture can start: the layer is in a window and the item has a picture.
    private(set) var pictureInPicturePossible = false
    private(set) var pictureInPictureActive = false
    private(set) weak var window: NSWindow?
    @ObservationIgnored private weak var playback: PlaybackModel?
    @ObservationIgnored private var controller: AVPictureInPictureController?
    @ObservationIgnored private var possible: NSKeyValueObservation?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    func attach(layer: AVPlayerLayer, playback: PlaybackModel) {
        guard controller == nil, AVPictureInPictureController.isPictureInPictureSupported() else { return }
        self.playback = playback
        let controller = AVPictureInPictureController(playerLayer: layer)
        controller?.delegate = self
        possible = controller?.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] controller, _ in
            let value = controller.isPictureInPicturePossible
            Task { @MainActor in self?.pictureInPicturePossible = value }
        }
        self.controller = controller
    }

    func windowChanged(_ window: NSWindow?) {
        guard window !== self.window else { return }
        self.window = window
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
        fullScreen = window?.styleMask.contains(.fullScreen) ?? false
        guard let window else { return }
        for (name, value) in [(NSWindow.didEnterFullScreenNotification, true), (NSWindow.didExitFullScreenNotification, false)] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: window, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.fullScreen = value }
            })
        }
    }

    func toggleFullScreen() { window?.toggleFullScreen(nil) }

    func togglePictureInPicture() {
        guard let controller else { return }
        if controller.isPictureInPictureActive { controller.stopPictureInPicture() }
        else if controller.isPictureInPicturePossible { controller.startPictureInPicture() }
    }

    func detach() {
        controller?.stopPictureInPicture()
        possible = nil
        controller = nil
        windowChanged(nil)
    }

    // The callbacks the iPad's player hears from AVKit (../NativePlayer.swift), from the controller
    // here. Their Swift names are the importer's; a near miss would compile as a plain method
    // nobody calls.
    func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        pictureInPictureActive = true
        playback?.setPictureInPicture(true)
        playback?.showChrome()
    }
    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    failedToStartPictureInPictureWithError error: Error) {
        pictureInPictureActive = false
        playback?.setPictureInPicture(false)
    }
    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        pictureInPictureActive = false
        playback?.setPictureInPicture(false)
        playback?.showChrome()
    }
    /// «Вернуть» on the floating picture: the window it came from is still here, the picture goes
    /// back into it.
    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        window?.makeKeyAndOrderFront(nil)
        completionHandler(window != nil)
    }
}

private struct PlayerSurface: NSViewRepresentable {
    let playback: PlaybackModel
    let surface: PlayerSurfaceModel
    func makeNSView(context: Context) -> PictureView {
        let view = PictureView()
        view.playerLayer.player = playback.player
        let playback = playback, surface = surface
        // One click plays or pauses, as in the web player and QuickTime. The second click of a
        // double click puts that back — the picture carries on as it was — and fills the screen.
        view.clicked = { count in
            switch count {
            case 1: playback.setPlaying(!playback.wantsPlayback)
            case 2:
                playback.setPlaying(!playback.wantsPlayback)
                surface.toggleFullScreen()
            default: break
            }
        }
        view.windowChanged = { surface.windowChanged($0) }
        surface.attach(layer: view.playerLayer, playback: playback)
        return view
    }
    func updateNSView(_ view: PictureView, context: Context) {
        if view.playerLayer.player !== playback.player { view.playerLayer.player = playback.player }
    }
    /// The surface's model is the coordinator, so the view going away can end its picture in picture.
    func makeCoordinator() -> PlayerSurfaceModel { surface }
    static func dismantleNSView(_ view: PictureView, coordinator: PlayerSurfaceModel) {
        coordinator.detach()
        view.playerLayer.player = nil
    }
}

/// The picture, drawn by an `AVPlayerLayer` as the view's own layer, and the clicks on it.
final class PictureView: NSView {
    let playerLayer = AVPlayerLayer()
    var clicked: (Int) -> Void = { _ in }
    var windowChanged: (NSWindow?) -> Void = { _ in }
    init() {
        super.init(frame: .zero)
        playerLayer.videoGravity = .resizeAspect
        playerLayer.backgroundColor = NSColor.black.cgColor
        wantsLayer = true
    }
    required init?(coder: NSCoder) { nil }
    override func makeBackingLayer() -> CALayer { playerLayer }
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        windowChanged(window)
    }
    /// A view that is not opaque offers its clicks to the window to be dragged by, and a press on
    /// the picture then moved the window instead of pausing the episode.
    override var mouseDownCanMoveWindow: Bool { false }
    override func mouseUp(with event: NSEvent) {}
    override func mouseDragged(with event: NSEvent) {}
    override func mouseDown(with event: NSEvent) { clicked(event.clickCount) }
}
