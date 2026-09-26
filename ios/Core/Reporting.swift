import Foundation
#if KAERU_FIREBASE
import FirebaseAnalytics
import FirebaseCore
import FirebaseCrashlytics
#endif

/// What the app tells its developer about itself: which screens are opened, when an episode starts
/// or fails, how a shared viewing goes — and every crash, with the stack that caused it.
///
/// Firebase underneath, and only in a build that has a project to report to: without
/// `GoogleService-Info.plist` the generator leaves `KAERU_FIREBASE` unset and every call here is
/// nothing at all. The same events, with the same names and parameters, as Android's
/// `data.report.Reporting` — one project, one set of numbers.
///
/// **What never leaves the phone.** No room id, no room key, no nickname, no chat line, no token,
/// nothing typed. An event carries a catalogue id, an episode number, a reason from a fixed list.
///
/// **Off means off from the first frame.** Info.plist starts both collectors disabled, and
/// `install()` switches them on only when the viewer has not said no in Settings.
///
/// No stored state of its own — the preference is in `UserDefaults` and Firebase keeps its own —
/// so it is callable from anywhere without an actor to hop to. Tests never install it.
enum Reporting {
    static let playStart = "play_start"
    static let playError = "play_error"
    static let together = "together"

    static let key = "reporting"

    /// On unless the viewer turned it off.
    static var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }

    /// Whether this build can report at all: it was built with a Firebase project, and started it.
    static var available: Bool {
        #if KAERU_FIREBASE
        return FirebaseApp.app() != nil
        #else
        return false
        #endif
    }

    static func install() {
        #if KAERU_FIREBASE
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return }
        if FirebaseApp.app() == nil { FirebaseApp.configure() }
        apply(enabled)
        // The same property Android sets: an iPad and a phone are one kind of use, a television
        // another, a Mac a third, and the numbers are no use mixed.
        #if os(iOS)
        Analytics.setUserProperty("ios", forName: "device")
        #else
        Analytics.setUserProperty("macos", forName: "device")
        #endif
        #endif
    }

    static func setEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: key)
        apply(on)
    }

    private static func apply(_ on: Bool) {
        #if KAERU_FIREBASE
        guard FirebaseApp.app() != nil else { return }
        Analytics.setAnalyticsCollectionEnabled(on)
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(on)
        #endif
    }

    /// A screen came up. A fixed name — `details`, never the title's id — so the list of screens
    /// stays a list of screens.
    static func screen(_ name: String) {
        #if KAERU_FIREBASE
        guard enabled, FirebaseApp.app() != nil else { return }
        Analytics.logEvent(AnalyticsEventScreenView, parameters: [
            AnalyticsParameterScreenName: name,
            AnalyticsParameterScreenClass: name,
        ])
        Crashlytics.crashlytics().log("screen \(name)")
        #endif
    }

    /// One thing that happened, with at most a handful of plain values. Nils are left out.
    static func event(_ name: String, _ parameters: [String: Any?] = [:]) {
        #if KAERU_FIREBASE
        guard enabled, FirebaseApp.app() != nil else { return }
        var values: [String: Any] = [:]
        for (key, value) in parameters {
            switch value {
            case nil: continue
            case let number as Int: values[key] = number
            case let number as Int64: values[key] = number
            case let flag as Bool: values[key] = flag ? "true" : "false"
            case let some?: values[key] = String(String(describing: some).prefix(100))
            }
        }
        Analytics.logEvent(name, parameters: values)
        Crashlytics.crashlytics().log("\(name) \(values.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: ", "))")
        #endif
    }

    /// Something that went wrong without taking the app down, and is worth a record beside the
    /// crashes: a stream that would not resolve for a reason nobody expected.
    static func problem(_ error: Error) {
        #if KAERU_FIREBASE
        guard enabled, FirebaseApp.app() != nil else { return }
        Crashlytics.crashlytics().record(error: error)
        #endif
    }
}
