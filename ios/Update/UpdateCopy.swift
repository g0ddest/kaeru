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
    #if os(macOS)
    /// The Mac installs nothing from here: the press downloads the image, and the viewer does the
    /// rest. A button that said «Установить» would promise what the next minute does not keep.
    static let install = "Скачать"
    #else
    static let install = "Установить"
    #endif
    static let openPage = "Открыть страницу выпуска"

    #if os(macOS)
    /// What is left to do once the image has downloaded, said before the press rather than after.
    ///
    /// Nothing on the Mac asks a question here the way iOS does: the browser saves the image and
    /// the rest is the viewer's, so the sentence is the steps. Kaeru is closed first, so the copy
    /// being replaced is not the one that is running.
    static let installNote = "Браузер скачает образ диска — откройте его, закройте Kaeru и перетащите новую версию в «Программы» с заменой"
    /// What is offered when the release has no disk image to install from.
    static let pageNote = "У этого выпуска нет образа для Mac — страница выпуска на GitHub"
    #else
    /// The one sentence that explains a system prompt before it arrives.
    ///
    /// Android's counterpart warns about the «unknown apps» permission. iOS asks something else —
    /// whether this site may install an app — and it asks it every time, so saying what it is for
    /// beforehand matters here for the same reason it does there.
    static let installNote = "iOS спросит, можно ли установить приложение, и поставит его поверх текущего"
    /// What is offered when the release has no manifest to install from.
    static let pageNote = "У этого выпуска нет файла для установки на iPhone — страница выпуска на GitHub"
    #endif

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
        #if os(macOS)
        case .installerRefused: "Не удалось открыть загрузку"
        #else
        case .installerRefused: "iOS не открыла установку"
        #endif
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

    /// `16 сентября 2026` — the months spelled out, in the genitive, as Android's `shortDate` does.
    ///
    /// Written out here rather than left to `Date.FormatStyle`, which follows the device's locale:
    /// this line sits inside a Russian sentence, and a phone set to English was printing
    /// «19 September 2026» in the middle of it. The calendar and the time zone are still the
    /// viewer's — only the words are the app's.
    private static let months = ["января", "февраля", "марта", "апреля", "мая", "июня",
                                 "июля", "августа", "сентября", "октября", "ноября", "декабря"]

    static func date(_ value: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.day, .month, .year], from: value)
        guard let day = parts.day, let month = parts.month, let year = parts.year,
              months.indices.contains(month - 1) else { return "" }
        return "\(day) \(months[month - 1]) \(year)"
    }
}
