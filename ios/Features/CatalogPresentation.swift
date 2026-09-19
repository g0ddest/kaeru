import Foundation

/// Presentation-only ordering and calendar labels shared by the catalog screens.
struct CatalogSeason: Hashable, Identifiable {
    var year: Int
    var quarter: Int
    var id: String { "\(apiValue)_\(year)" }
    var apiValue: String { ["winter", "spring", "summer", "autumn"][quarter] }
    var title: String { "\(["Зима", "Весна", "Лето", "Осень"][quarter]) \(year)" }
    static func current(date: Date = Date(), calendar: Calendar = .current) -> Self {
        Self(year: calendar.component(.year, from: date), quarter: (calendar.component(.month, from: date) - 1) / 3)
    }
    func offset(_ amount: Int) -> Self {
        let index = year * 4 + quarter + amount
        return Self(year: index / 4, quarter: index % 4)
    }
}

enum CatalogPresentation {
    static func recentQueries(adding query: String, to queries: [String]) -> [String] {
        let value = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.count >= 2 else { return queries }
        return Array(([value] + queries.filter { $0.caseInsensitiveCompare(value) != .orderedSame }).prefix(5))
    }
    static func titlePrecedes(_ lhs: String, _ rhs: String) -> Bool {
        lhs.compare(rhs, options: [.caseInsensitive, .numeric], locale: Locale(identifier: "ru")) == .orderedAscending
    }
    static func date(_ value: String?) -> Date {
        guard let value else { return .distantPast }
        let formatter = ISO8601DateFormatter()
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions.insert(.withFractionalSeconds)
        return formatter.date(from: value) ?? .distantPast
    }
    static func timestamp(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let value = Int(min(seconds, Double(Int.max / 2)))
        return "\(value / 60):\(String(format: "%02d", value % 60))"
    }
}
