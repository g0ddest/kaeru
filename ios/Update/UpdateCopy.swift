import Foundation

/// Every word the «Обновления» screen says, in one file.
///
/// Word for word Android's `UpdateCopy.kt` and `ErrorMessages.kt` wherever the two apps are saying
/// the same thing, which is nearly everywhere: one repository publishes both, and a viewer with a
/// phone in each hand should not be reading two accounts of one release. What differs is only what
/// the platform forces — iOS installs nothing itself, so the sentences about a download and a
/// system installer have no counterpart here.
///
/// The lines built out of a release are functions rather than strings assembled at the call site,
/// so what the screen says can be read in a test instead of on a device.
enum UpdateCopy {
    static let title = "Обновления"

    /// The section names, in the order the page is read.
    static let installed = "Установлено"
    static let latest = "Последний выпуск"

    static let checking = "Проверяем…"
    static let upToDate = "У вас последняя версия"
    static let unknown = "Пока ничего не известно о новых версиях"
    static let check = "Проверить"
    static let install = "Установить"
    static let openPage = "Открыть страницу выпуска"

    /// The one sentence that explains a system prompt before it arrives.
    ///
    /// Android's counterpart warns about the «unknown apps» permission. iOS asks something else —
    /// whether this site may install an app — and it asks it every time, so saying what it is for
    /// beforehand matters here for the same reason it does there.
    static let installNote = "iOS спросит, можно ли установить приложение, и поставит его поверх текущего"
    /// What is offered when the release has no manifest to install from.
    static let pageNote = "У этого выпуска нет файла для установки на iPhone — страница выпуска на GitHub"

    /// `Kaeru 0.5.1` — the same line the settings page shows, so the two agree on sight.
    static func installedLine(_ version: String) -> String { "Kaeru \(version)" }

    /// `Доступна версия 0.6.0` — the headline of this screen, and the whole of the home row.
    ///
    /// One function for both. A signpost worded differently from the page it leads to would read
    /// as two separate pieces of news about one release.
    static func available(_ version: String) -> String { "Доступна версия \(version)" }

    /// `16 сентября 2026, 30 МБ` — when it came out and what it costs to fetch.
    ///
    /// Joined with a comma rather than a middle dot, as every two-fact line in this app is. A
    /// release with no date prints only the size, and one with neither prints nothing at all
    /// rather than an empty phrase with punctuation in it.
    static func releaseLine(_ release: UpdateRelease) -> String? {
        var parts: [String] = []
        if let published = release.publishedAt { parts.append(date(published)) }
        if release.sizeBytes > 0 { parts.append(ByteCountFormatter.string(fromByteCount: release.sizeBytes, countStyle: .file)) }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }

    /// `Проверено 16 сентября 2026`, or nothing on a device that has never managed a check.
    static func checkedLine(_ at: Date?) -> String? { at.map { "Проверено \(date($0))" } }

    /// The same words for every failure, so «Нет связи» cannot come to be written two ways.
    static func failure(_ reason: UpdateFailure) -> String {
        switch reason {
        case .noNetwork: "Нет связи"
        case .rateLimited: "GitHub ограничил запросы, попробуйте через час"
        case .installerRefused: "iOS не открыла установку"
        case .unknown: "Не удалось проверить обновления"
        }
    }

    /// A failure of any kind as the sentence to print under the release.
    ///
    /// Anything that is not one of ours — a decoder, a store — says the general sentence rather
    /// than its own English `localizedDescription`: this screen is in Russian throughout.
    static func message(for error: Error) -> String {
        (error as? UpdateFailed).map { failure($0.reason) } ?? failure(.unknown)
    }

    private static func date(_ value: Date) -> String {
        value.formatted(.dateTime.day().month(.wide).year())
    }
}
