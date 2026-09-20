import Foundation

/// A few lines about what a room actually did, kept on the device.
///
/// A shared viewing fails on somebody else's phone, on somebody else's network, against a relay
/// that is a stranger's machine. «Связь с другом потеряна» is all the screen can honestly say, and
/// it is not enough to fix anything by: the close code, the side that went away and the moment it
/// happened are the difference between a dead socket, a refused room and a friend who walked out.
///
/// Small on purpose. One file, capped, plain text, no personal data and no room keys — a room id
/// is the half that is safe to write down, which is why the link's key lives in the fragment.
enum TogetherLog {
    /// Beyond this the file is started again: this is for the last evening, not for all of them.
    private static let limit = 512 * 1024
    private static let queue = DispatchQueue(label: "app.kaeru.together.log")
    private static let formatter: DateFormatter = {
        let value = DateFormatter()
        value.dateFormat = "HH:mm:ss.SSS"
        return value
    }()

    /// Off unless the viewer turned it on in Settings. A journal is for the evening something goes
    /// wrong, not for every evening — and a file that grows on every viewing is a file nobody asked
    /// for. Read from the defaults on every line rather than cached, so the switch takes effect at
    /// once; a bool lookup is nothing next to the write behind it.
    static var enabled: Bool { UserDefaults.standard.bool(forKey: key) }
    static let key = "togetherLog"
    static func setEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: key)
        if !on { clear() }
    }

    static var url: URL? {
        try? FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: true)
            .appendingPathComponent("together.log")
    }

    static func write(_ line: String) {
        guard enabled else { return }
        let stamp = formatter.string(from: Date())
        queue.async {
            guard let url else { return }
            let text = "\(stamp) \(line)\n"
            guard let data = text.data(using: .utf8) else { return }
            let manager = FileManager.default
            if let size = (try? manager.attributesOfItem(atPath: url.path)[.size] as? Int) ?? nil, size > limit {
                try? manager.removeItem(at: url)
            }
            if let handle = try? FileHandle(forWritingTo: url) {
                defer { try? handle.close() }
                _ = try? handle.seekToEnd()
                try? handle.write(contentsOf: data)
            } else {
                try? data.write(to: url)
            }
        }
    }

    /// What the last evening said, newest last. For the screen that offers to share it.
    static func read() -> String {
        guard let url, let data = try? Data(contentsOf: url) else { return "Журнал пуст." }
        return String(data: data, encoding: .utf8) ?? "Журнал нечитаем."
    }

    static func clear() {
        guard let url else { return }
        queue.async { try? FileManager.default.removeItem(at: url) }
    }
}
