import Foundation

/// Best-effort community metadata. A failure never prevents video playback.
actor AniSkipClient {
    static let shared = AniSkipClient()
    private struct Row: Codable {
        var animeID: Int
        var episode: Int
        var length: Int
        var fetchedAt: Date
        var marks: SkipMarks
    }
    private var rows: [Row] = []
    private let session: URLSession
    private let cacheURL: URL?
    init(session: URLSession = .shared, cacheURL: URL? = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("aniskip-v2.json")) {
        self.session = session; self.cacheURL = cacheURL
        if let cacheURL, let data = try? Data(contentsOf: cacheURL), let saved = try? JSONDecoder().decode([Row].self, from: data) { rows = saved }
    }
    nonisolated static func request(animeID: Int, episode: Int, duration: Double) -> URLRequest? {
        guard animeID > 0, episode > 0, duration.isFinite, duration >= 0.5, duration < Double(Int32.max) else { return nil }
        var url = URLComponents(string: "https://api.aniskip.com/v2/skip-times/\(animeID)/\(episode)")!
        url.queryItems = ["op", "ed", "mixed-op", "mixed-ed"].map { URLQueryItem(name: "types[]", value: $0) }
        url.queryItems?.append(URLQueryItem(name: "episodeLength", value: String(Int(duration.rounded()))))
        return URLRequest(url: url.url!, timeoutInterval: 10)
    }
    nonisolated static func decode(_ data: Data, duration: Double) throws -> SkipMarks {
        struct Interval: Decodable { var startTime: Double?; var endTime: Double? }
        struct Result: Decodable { var skipType: String?; var interval: Interval }
        struct Response: Decodable { var found: Bool?; var results: [Result]? }
        let response = try JSONDecoder().decode(Response.self, from: data)
        guard response.found == true else { return SkipMarks() }
        var marks = SkipMarks()
        for result in response.results ?? [] {
            let interval = SkipInterval(start: result.interval.startTime ?? 0, end: result.interval.endTime ?? 0)
            switch result.skipType {
            case "op", "mixed-op":
                if marks.opening == nil { marks.opening = SkipMarks(opening: interval).accepted(duration: duration).opening }
            case "ed", "mixed-ed":
                if marks.ending == nil { marks.ending = SkipMarks(ending: interval).accepted(duration: duration).ending }
            default: break
            }
        }
        return marks
    }
    func marks(animeID: Int, episode: Int, duration: Double) async throws -> SkipMarks {
        guard let request = Self.request(animeID: animeID, episode: episode, duration: duration) else { return SkipMarks() }
        let length = Int(duration.rounded()), now = Date()
        let cached = rows.filter { $0.animeID == animeID && $0.episode == episode && abs($0.length - length) <= 2 }
            .min { abs($0.length - length) < abs($1.length - length) }
        if let cached, now.timeIntervalSince(cached.fetchedAt) < 7 * 86400 { return cached.marks.accepted(duration: duration) }
        let marks: SkipMarks
        do {
            let (data, response) = try await session.data(for: request)
            try Task.checkCancellation()
            guard let response = response as? HTTPURLResponse else { return cached?.marks.accepted(duration: duration) ?? SkipMarks() }
            if response.statusCode == 404 { marks = SkipMarks() }
            else if (200..<300).contains(response.statusCode) { marks = try Self.decode(data, duration: duration) }
            else { return cached?.marks.accepted(duration: duration) ?? SkipMarks() }
        } catch {
            if Task.isCancelled { throw CancellationError() }
            return cached?.marks.accepted(duration: duration) ?? SkipMarks()
        }
        rows.removeAll { $0.animeID == animeID && $0.episode == episode && abs($0.length - length) <= 2 }
        rows.append(Row(animeID: animeID, episode: episode, length: cached?.length ?? length, fetchedAt: now, marks: marks))
        if let cacheURL, let data = try? JSONEncoder().encode(rows) { try? data.write(to: cacheURL, options: .atomic) }
        return marks
    }
}
