import AVFoundation
import SwiftUI
import UIKit

/// Reads the QR code a television shows on its login screen.
///
/// The alternative is reading an address, a port and a 43-character nonce off a screen across the
/// room and typing them into a phone, so this is the path the flow is built around — but it is
/// never the only one: a simulator has no camera, and neither does a phone whose owner said no.
struct QRScannerView: View {
    /// Called once, with whatever the code contained. Validation belongs to the caller.
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                switch access {
                case .authorized:
                    CameraPreview(onCode: onCode).ignoresSafeArea()
                    VStack {
                        Spacer()
                        Text("Наведите камеру на QR-код на экране телевизора")
                            .font(.callout).multilineTextAlignment(.center).foregroundStyle(.white)
                            .padding(16).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
                            .padding(24)
                    }
                case .notDetermined:
                    ContentUnavailableView {
                        Label("Нужен доступ к камере", systemImage: "camera")
                    } description: {
                        Text("Камера нужна только для чтения QR-кода с экрана телевизора.")
                    } actions: {
                        Button("Разрешить") {
                            AVCaptureDevice.requestAccess(for: .video) { granted in
                                Task { @MainActor in access = granted ? .authorized : .denied }
                            }
                        }.buttonStyle(.borderedProminent)
                    }
                default:
                    ContentUnavailableView {
                        Label("Камера недоступна", systemImage: "camera.badge.ellipsis")
                    } description: {
                        Text("Разрешите доступ к камере в настройках или введите ссылку приглашения вручную.")
                    } actions: {
                        Button("Открыть настройки") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                        }.buttonStyle(.bordered)
                    }
                }
            }
            .navigationTitle("QR-код телевизора")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } } }
        }
        .preferredColorScheme(.dark)
    }
}

private struct CameraPreview: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    func makeUIViewController(context: Context) -> QRCaptureController { QRCaptureController(onCode: onCode) }
    func updateUIViewController(_ controller: QRCaptureController, context: Context) { controller.onCode = onCode }
}

/// `AVCaptureSession` is driven from one serial queue, which is safe and which the SDK does not
/// yet say in its annotations. Boxing it states the invariant instead of silencing the module.
private final class CaptureBox: @unchecked Sendable {
    let session = AVCaptureSession()
}

/// One capture session, started when the view appears and stopped the moment a code is read.
@MainActor final class QRCaptureController: UIViewController {
    var onCode: (String) -> Void
    private let capture = CaptureBox()
    private let queue = DispatchQueue(label: "app.kaeru.qr")
    private var session: AVCaptureSession { capture.session }
    private var preview: AVCaptureVideoPreviewLayer?
    private var sink: QRCodeSink?
    private var delivered = false

    init(onCode: @escaping (String) -> Void) {
        self.onCode = onCode
        super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        let sink = QRCodeSink { [weak self] value in self?.read(value) }
        self.sink = sink
        output.setMetadataObjectsDelegate(sink, queue: queue)
        output.metadataObjectTypes = output.availableMetadataObjectTypes.contains(.qr) ? [.qr] : []
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        guard !session.isRunning, !delivered else { return }
        // `startRunning` blocks until the camera is configured; never on the thread drawing this.
        let capture = self.capture
        queue.async { capture.session.startRunning() }
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        stop()
    }
    private func read(_ value: String) {
        // A QR code is scanned many times a second; the first one that parses is the answer.
        guard !delivered, !value.isEmpty, value.utf8.count <= 2048 else { return }
        delivered = true
        stop()
        onCode(value)
    }
    private func stop() {
        guard session.isRunning else { return }
        let capture = self.capture
        queue.async { capture.session.stopRunning() }
    }
}

/// AVFoundation calls back on its own queue; everything the app does with the value happens on the
/// main actor, so the hop is made here rather than left to the caller.
private final class QRCodeSink: NSObject, AVCaptureMetadataOutputObjectsDelegate {
    private let onCode: @MainActor (String) -> Void
    init(onCode: @escaping @MainActor (String) -> Void) { self.onCode = onCode }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let object = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first(where: { $0.type == .qr }),
              let value = object.stringValue else { return }
        let handler = onCode
        Task { @MainActor in handler(value) }
    }
}
