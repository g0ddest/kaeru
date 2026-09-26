import Foundation
import Observation

/// Which of the four things the «Обновления» screen is saying at this moment.
///
/// Four rather than «loading, content, error», because three of them lead to a different control
/// under them and the fourth is the absence of one. What separates `upToDate` from `unknown` is
/// the difference between «мы спросили, и нового нет» and «мы не смогли спросить» — the same
/// distinction the home screen draws between an empty shelf and a shelf nobody has read yet, and
/// it matters here for the same reason: telling somebody they have the latest version when nothing
/// was ever checked is a sentence that is simply untrue.
///
/// Android has two more, for a file arriving and a file waiting. Neither exists here: this app
/// hands iOS a link and iOS does the rest.
enum UpdateStage: Equatable, Sendable {
    /// Asking GitHub. Nothing is known yet, or what is known is being replaced.
    case checking
    /// Nothing was ever learned: a first check that failed, on a device with no stored answer.
    case unknown
    /// A check finished and found nothing newer. `checkedAt` says when.
    case upToDate
    /// There is a newer release.
    case available
}

/// The «Обновления» screen: what is installed, what is published, and the press between them.
///
/// Opening the screen checks, and does so unforced — the app already asked at launch, and a second
/// request a minute later would spend the hourly budget on an answer it has in hand. «Проверить»
/// is the forced one: a press is a question put directly, and answering it out of a cache would be
/// a button that does nothing.
@MainActor @Observable final class UpdateModel {
    let installedVersion: String
    private(set) var stage: UpdateStage = .checking
    /// When the last completed check ran, or nil on a device that has never managed one.
    private(set) var checkedAt: Date?
    /// The newer release, when there is one. Nil in every other stage.
    private(set) var release: UpdateRelease?
    /// What went wrong, already in Russian, or nil.
    ///
    /// It sits alongside the stage rather than replacing it, and that is the point: a check that
    /// failed over a release already known leaves both the failure and the release on screen, so a
    /// viewer on a train reads «доступна версия 0.6.0» and «нет связи» at once instead of losing
    /// the first to the second.
    private(set) var message: String?

    @ObservationIgnored private let repository: any UpdateRepository
    /// Hands a link to the system. Answers whether anything took it — nothing else here can know.
    @ObservationIgnored private let open: (URL) async -> Bool
    @ObservationIgnored private var checking: Task<Void, Never>?

    init(repository: any UpdateRepository, installedVersion: String, open: @escaping (URL) async -> Bool) {
        self.repository = repository
        self.installedVersion = installedVersion
        self.open = open
    }

    /// Asks again. `force` is the viewer's own press; false is the screen opening.
    ///
    /// A check already running is left alone rather than restarted: two answers to one question
    /// would race to write the same three fields, and the second press buys nothing.
    func check(force: Bool = true) {
        guard checking == nil else { return }
        stage = .checking
        message = nil
        checking = Task { [weak self] in
            guard let self else { return }
            let outcome = await repository.check(force: force)
            checking = nil
            switch outcome {
            case .success(let result): apply(result)
            case .failure(let error): failed(error)
            }
        }
    }

    /// «Установить», or «Открыть страницу выпуска» when there is nothing to install from.
    ///
    /// One press either way: a viewer who asked for the newer version has said what they want, and
    /// iOS asks its own question after this — it always does, and nothing here can or should
    /// change that. On the Mac the press is a download, and the rest is the viewer's.
    func install() {
        guard let target = release?.install ?? release?.page else { return }
        Task { [weak self] in
            guard let self else { return }
            if await open(target) { message = nil } else { message = UpdateCopy.failure(.installerRefused) }
        }
    }

    /// Whether «Проверить» is worth offering. Not while one is already running: the app is
    /// already doing the thing the button would ask for.
    var canCheck: Bool { stage != .checking }

    /// One stored answer, as the three fields of the screen it decides.
    private func apply(_ result: UpdateResult?) {
        stage = result == nil ? .unknown : (result?.release != nil ? .available : .upToDate)
        checkedAt = result?.checkedAt
        release = result?.release
        message = nil
    }

    /// A check that failed still leaves the last real answer on screen.
    ///
    /// That is the whole of what «offline: show the last known result» means: the stored result is
    /// read back and shown under the failure, so a screen opened in a tunnel says «доступна версия
    /// 0.6.0» and «нет связи» together rather than losing the first.
    private func failed(_ error: Error) {
        guard !(error is CancellationError) else { return }
        apply(repository.lastResult)
        message = UpdateCopy.message(for: error)
    }
}
