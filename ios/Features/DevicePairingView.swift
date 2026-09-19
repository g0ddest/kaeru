import SwiftUI

struct DevicePairingView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var invitation = ""
    @State private var code = ""
    @State private var pairing = false
    @State private var result: String?
    @State private var failure: String?

    var body: some View {
        Form {
            Section {
                Text("Откройте QR-код Kaeru на Android TV или вставьте ссылку приглашения вручную. Затем введите одноразовый код, который показывает телевизор.")
                    .foregroundStyle(.secondary)
                TextField("kaeru://pair…", text: $invitation, axis: .vertical)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Одноразовый код", text: $code)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .textContentType(.oneTimeCode)
            }
            Section {
                Button("Войти на телевизоре", systemImage: "tv.and.arrow.forward") { pair() }
                    .disabled(pairing || !canPair)
                if pairing { ProgressView("Подключаемся…") }
                if let result { Label(result, systemImage: "checkmark.circle.fill").foregroundStyle(.green) }
                if let failure { Label(failure, systemImage: "exclamationmark.triangle").foregroundStyle(.secondary) }
            }
        }
        .navigationTitle("Телевизор")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
    }

    private var canPair: Bool {
        !invitation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func pair() {
        guard let url = URL(string: invitation) else { failure = PairingError.malformedResponse.localizedDescription; return }
        do {
            let parsed = try PairingInvitation.parse(url)
            pairing = true; result = nil; failure = nil
            Task {
                do {
                    try await TVPairingClient.pair(invitation: parsed, code: code.trimmingCharacters(in: .whitespacesAndNewlines))
                    pairing = false; result = "Телевизор подключён"
                } catch {
                    pairing = false; failure = error.localizedDescription
                }
            }
        } catch { failure = error.localizedDescription }
    }
}
