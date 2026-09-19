import SwiftUI

/// «Войти на телевизоре»: the phone reads the television's QR code, signs in on its own, and hands
/// the television the authorization it was just issued.
///
/// Nothing is typed from the television's screen. The code that crosses the local network is this
/// phone's own — see `PairingCoordinator` for why that is the only code that can work.
struct DevicePairingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var typed = ""
    @State private var scanning = false
    @State private var failure: String?
    private var coordinator: PairingCoordinator { model.pairing }

    var body: some View {
        Form {
            if let invitation = coordinator.invitation { television(invitation) } else { search }
        }
        .navigationTitle("Телевизор")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        .sheet(isPresented: $scanning) {
            QRScannerView { code in
                scanning = false
                open(code)
            }
        }
        // A hand-off that finished belongs to the visit that made it; a fresh visit starts over.
        .task { if coordinator.stage == .done { coordinator.dismiss() } }
    }

    @ViewBuilder private func television(_ invitation: PairingInvitation) -> some View {
        Section {
            LabeledContent("Телевизор", value: invitation.name.isEmpty ? "Android TV" : invitation.name)
        } footer: {
            Text("Телефон откроет вход в Shikimori и передаст результат телевизору сам. Код с экрана телевизора вводить не нужно.")
        }
        Section {
            switch coordinator.stage {
            case .awaitingCode:
                ProgressView("Ждём входа в Shikimori…")
            case .sending:
                ProgressView("Передаём телевизору…")
            case .done:
                Label("Телевизор вошёл в Shikimori", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                Button("Подключить другой телевизор") { coordinator.dismiss() }
            default:
                Button("Войти на телевизоре", systemImage: "tv.and.arrow.forward") {
                    Task { await coordinator.confirm() }
                }
                Button("Отмена", role: .cancel) { coordinator.dismiss() }
            }
            if let message = coordinator.message {
                Label(message, systemImage: "exclamationmark.triangle").foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder private var search: some View {
        Section {
            Button("Сканировать QR-код", systemImage: "qrcode.viewfinder") { scanning = true }
        } header: {
            Text("Телевизор")
        } footer: {
            Text("Откройте на телевизоре «Войти в Shikimori» и наведите камеру на QR-код.")
        }
        Section {
            TextField("kaeru://pair…", text: $typed, axis: .vertical)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .accessibilityIdentifier("pairing-link")
            Button("Продолжить по ссылке") { open(typed) }
                .disabled(typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if let message = failure ?? (coordinator.stage == .failed ? coordinator.message : nil) {
                Label(message, systemImage: "exclamationmark.triangle").foregroundStyle(.secondary)
            }
        } header: {
            Text("Ссылка вручную")
        } footer: {
            Text("Если камера недоступна, перепишите ссылку, напечатанную под QR-кодом.")
        }
    }

    private func open(_ text: String) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: value) else {
            failure = TogetherError.invalidInvitation.errorDescription
            return
        }
        failure = nil
        coordinator.open(url)
    }
}
