import Foundation

/// What «Мой список» shows for one choice of filter, order and search text.
struct LibraryQuery: Hashable, Sendable {
    var recent: Bool
    var status: String
    var text: String
    var byTitle: Bool
}

/// A list ready to draw: the titles in order and how many sit under each status.
struct LibraryListing: Sendable {
    var items: [LibraryItem]
    var counts: [String: Int]

    /// Built away from the main thread. A list of several hundred titles sorted with dates and
    /// Russian collation inside the comparator held the window long enough to freeze it; here each
    /// date is parsed once and one collation locale serves the whole sort.
    static func build(_ library: [LibraryItem], _ query: LibraryQuery) -> LibraryListing {
        let russian = Locale(identifier: "ru")
        let chosen = library.filter {
            (query.recent || $0.status == query.status)
                && (query.text.isEmpty || $0.anime.title.localizedCaseInsensitiveContains(query.text)
                    || $0.anime.originalTitle.localizedCaseInsensitiveContains(query.text))
        }
        let byDate = query.recent || !query.byTitle
        let items = chosen.map { ($0, CatalogPresentation.date($0.updatedAt)) }.sorted { lhs, rhs in
            if byDate && lhs.1 != rhs.1 { return lhs.1 > rhs.1 }
            if lhs.0.anime.title == rhs.0.anime.title { return lhs.0.anime.id < rhs.0.anime.id }
            return lhs.0.anime.title.compare(rhs.0.anime.title, options: [.caseInsensitive, .numeric], locale: russian) == .orderedAscending
        }.map(\.0)
        return LibraryListing(items: items, counts: Dictionary(library.map { ($0.status, 1) }, uniquingKeysWith: +))
    }
}

/// The last lists built, so coming back to «Мой список» draws at once instead of behind a loader.
@MainActor enum LibraryListingCache {
    private static var stored: [Int: LibraryListing] = [:]
    static func key(_ library: [LibraryItem], _ query: LibraryQuery) -> Int {
        var hasher = Hasher()
        hasher.combine(query)
        hasher.combine(library)
        return hasher.finalize()
    }
    static subscript(key: Int) -> LibraryListing? {
        get { stored[key] }
        set {
            if stored.count >= 12 { stored.removeAll() }
            stored[key] = newValue
        }
    }
}
