import SwiftUI

struct TogetherView: View {
    @Bindable var manager: TogetherManager
    @Environment(\.dismiss) private var dismiss
    @State private var invitation = ""
    @State private var message = ""

    var body: some View {
        Form {
            Section {
                LabeledContent("Состояние", value: stateTitle)
                if let peer = manager.peerName { LabeledContent("Собеседник", value: peer) }
                if let error = manager.error { Text(error.localizedDescription).foregroundStyle(.secondary) }
            }
            Section("Комната") {
                if let link = manager.invitation?.shareURL.absoluteString {
                    Text(link).font(.footnote).textSelection(.enabled)
                    ShareLink(item: link) { Label("Поделиться приглашением", systemImage: "square.and.arrow.up") }
                    Button("Завершить комнату", role: .destructive) { Task { await manager.leave() } }
                } else {
                    Button("Создать комнату", systemImage: "plus.circle") { Task { await manager.create() } }
                        .disabled(manager.phase == .connecting)
                    TextField("Ссылка приглашения", text: $invitation, axis: .vertical)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Войти в комнату", systemImage: "arrow.right.circle") {
                        guard let url = URL(string: invitation) else { return }
                        do { let parsed = try TogetherInvitation.parse(url); Task { await manager.join(parsed) } }
                        catch { }
                    }.disabled(invitation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || manager.phase == .connecting)
                }
            }
            if manager.phase == .live {
                Section("Чат") {
                    ForEach(Array(manager.messages.filter { $0.t == .chat }.suffix(30)), id: \.seq) { item in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.name ?? "Собеседник").font(.caption).foregroundStyle(.secondary)
                            Text(item.text ?? "")
                        }
                    }
                    HStack {
                        TextField("Сообщение", text: $message)
                        Button("Отправить") { manager.sendChat(message); message = "" }.disabled(message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    ScrollView(.horizontal) {
                        HStack { ForEach(TogetherReaction.allCases, id: \.self) { reaction in Button(reaction.symbol) { manager.sendReaction(reaction) } } }
                    }.scrollIndicators(.hidden)
                }
            }
        }
        .navigationTitle("Watch Together")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
    }

    private var stateTitle: String {
        switch manager.phase { case .idle: "Не подключено"; case .connecting: "Подключение…"; case .live: "В эфире"; case .ended: "Завершено"; case .failed: "Ошибка" }
    }
}
