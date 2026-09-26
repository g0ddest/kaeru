import SwiftUI

struct TogetherView: View {
    @Bindable var manager: TogetherManager
    /// Pushed inside a stack rather than raised as a sheet: nothing to dismiss, so no «Готово».
    var embedded = false
    @Environment(\.dismiss) private var dismiss
    @State private var invitation = ""
    @State private var message = ""
    @State private var linkError: String?

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
                    #if os(macOS)
                    Button(TogetherCopy.copyInvitation, systemImage: "doc.on.doc") { Pasteboard.copy(link) }
                    #endif
                    ShareLink(item: link) { Label("Поделиться приглашением", systemImage: "square.and.arrow.up") }
                    Button("Завершить комнату", role: .destructive) { Task { await manager.leave() } }
                } else {
                    Button("Создать комнату", systemImage: "plus.circle") { Task { await manager.create() } }
                        .disabled(manager.phase == .connecting)
                    TextField("Ссылка приглашения", text: $invitation, axis: .vertical)
                        .kaeruPlainTextInput()
                    Button("Войти в комнату", systemImage: "arrow.right.circle") { join() }
                        .disabled(invitation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || manager.phase == .connecting)
                    // A swallowed error here was a button that did nothing at all: the one thing
                    // that can be wrong with a pasted invitation is the invitation, and saying so
                    // is the difference between a typo and a broken app.
                    if let linkError { Text(linkError).font(.footnote).foregroundStyle(.red) }
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
        .kaeruGroupedForm()
        .navigationTitle(TogetherCopy.watchTogether)
        .kaeruTitleDisplay(.inline)
        .toolbar { if !embedded { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } } }
    }

    private func join() {
        let typed = invitation.trimmingCharacters(in: .whitespacesAndNewlines)
        // The page an invitation lands on shows a session code, and a code is the one thing that
        // can be typed here and cannot possibly work: the key that opens the room is the part
        // after the `#`, which never leaves the phone that opened the link. Saying so beats a
        // button that appears to do nothing.
        if typed.range(of: "^[A-Za-z0-9_-]{6,32}$", options: .regularExpression) != nil {
            linkError = "Это только код комнаты. Нужна вся ссылка целиком — ключ в ней идёт после «#»."
            return
        }
        do {
            guard let url = URL(string: typed) else { throw TogetherError.invalidInvitation }
            let parsed = try TogetherInvitation.parse(url)
            linkError = nil
            Task { await manager.join(parsed) }
        } catch let error as TogetherError {
            linkError = error.errorDescription
        } catch {
            linkError = TogetherError.invalidInvitation.errorDescription
        }
    }

    private var stateTitle: String {
        switch manager.phase { case .idle: "Не подключено"; case .connecting: "Подключение…"; case .live: "В эфире"; case .reconnecting: "Восстанавливаем связь…"; case .ended: "Завершено"; case .failed: "Ошибка" }
    }
}
