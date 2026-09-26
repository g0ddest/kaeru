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
#endif
