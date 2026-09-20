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
    private static let limit = 64 * 1024
    private static let queue = DispatchQueue(label: "app.kaeru.together.log")
    private static let formatter: DateFormatter = {
        let value = DateFormatter()
        value.dateFormat = "HH:mm:ss.SSS"
        return value
    }()

    static var url: URL? {
        try? FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: true)
            .appendingPathComponent("together.log")
    }

    static func write(_ line: String) {
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
