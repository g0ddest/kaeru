import SwiftUI
#if os(iOS)
import UIKit
#endif

/// Something to share, and an identity so a sheet can be raised by handing it one.
struct TogetherShare: Identifiable {
    let text: String
    var id: String { text }
}

#if os(iOS)
/// The system share sheet, raised by code rather than by a press.
///
/// `ShareLink` is the way to do this everywhere else in the app and is better in every respect —
/// except that it needs somebody to press it. A room that has just been created has to offer the
/// invitation without a second press, which is what this is for.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
#else
/// The same moment on a Mac, which has no share sheet to raise by code: AppKit's sharing picker
/// hangs off a button somebody pressed. So the invitation goes where a Mac user takes a link from
/// anyway — the pasteboard — the moment the room exists, and this says so, with the picker behind
/// a button for Messages or Mail.
struct ShareSheet: View {
    let items: [Any]
    @Environment(\.dismiss) private var dismiss
    private var text: String { items.compactMap { $0 as? String }.joined(separator: "\n") }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Приглашение скопировано", systemImage: "doc.on.clipboard").font(.headline)
            Text("Вставьте его в чат тому, с кем хотите смотреть.").foregroundStyle(.secondary)
            Text(text).font(.callout).textSelection(.enabled)
                .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
            HStack {
                ShareLink(item: text) { Label("Отправить…", systemImage: "square.and.arrow.up") }
                Spacer()
                Button("Готово") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 420)
        .onAppear { Pasteboard.copy(text) }
    }
}
#endif
