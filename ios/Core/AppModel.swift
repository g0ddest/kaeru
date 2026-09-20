import Foundation
import Observation
import AuthenticationServices

@MainActor @Observable final class AppModel {
    let service: any AnimeService
    let store: any LocalStorage
    let configuration: AppConfiguration
    @ObservationIgnored private var downloadManager: DownloadManager?
    @ObservationIgnored private var notificationService: EpisodeNotificationService?
    @ObservationIgnored private var castManager: CastManager?
    @ObservationIgnored private var togetherManager: TogetherManager?
    @ObservationIgnored private var pairingCoordinator: PairingCoordinator?
    @ObservationIgnored private var updates: GitHubUpdateRepository?
    var notifications: EpisodeNotificationService {
        if let notificationService { return notificationService }
        let value = EpisodeNotificationService()
        value.setAccount(session == nil ? nil : accountKey)
        notificationService = value
        return value
    }
    var downloads: DownloadManager {
        if let downloadManager { return downloadManager }
        let manager = DownloadManager(service: service)
        manager.onConnectivityRestored = { [weak self] in Task { await self?.flush() } }
        downloadManager = manager
        return manager
    }
    var cast: CastManager {
        if let castManager { return castManager }
        let manager = CastManager(service: service)
        manager.onProgress = { [weak self] progress, anime in
            guard let self else { return }
            self.saveProgress(progress, anime: anime, account: self.accountKey)
        }
        castManager = manager
        return manager
    }
    /// Signing a television in, held here rather than in the screen so a `kaeru://pair` deep link
    /// and «Подключить Android TV» in Settings are the same hand-off rather than two.
    var pairing: PairingCoordinator {
        if let pairingCoordinator { return pairingCoordinator }
        let value = PairingCoordinator(authorize: { [weak self] in
            guard let self else { throw CancellationError() }
            return try await self.televisionAuthorization()
        })
        pairingCoordinator = value
        return value
    }
    /// What GitHub says about releases of this app, and what this device remembers of the answer.
    /// One instance, because the screen and the launch-time check share a throttle and a record.
    var updateRepository: GitHubUpdateRepository {
        if let updates { return updates }
        let value = GitHubUpdateRepository(source: GitHubReleaseSource(), store: store,
                                           installedVersion: installedAppVersion())
        updates = value
        return value
    }
    /// The newer release this device knows about, as the quiet row on the home screen says it.
    /// Nil until something is known, which on a fresh install is until the first check lands.
    private(set) var availableUpdate: UpdateRelease?
    func refreshAvailableUpdate() { availableUpdate = updateRepository.lastResult?.release }
    /// The quiet check: once per launch, and at most once a day.
    ///
    /// Deliberately the smallest thing it could be. Nothing waits for it, nothing is shown while
    /// it runs, and a failure is not reported anywhere — the result is written down and the home
    /// screen picks it up from there whenever it lands, which may well be after it is on screen.
    func checkForUpdates() async {
        refreshAvailableUpdate()
        _ = await updateRepository.check(force: false)
        refreshAvailableUpdate()
    }
    var together: TogetherManager {
        if let togetherManager { return togetherManager }
        let name = session?.account.nickname.isEmpty == false ? session!.account.nickname : "Kaeru"
        let manager = TogetherManager(relayURL: configuration.togetherRelayURL, displayName: name)
        togetherManager = manager
        return manager
    }
    private(set) var session: Session?
    private(set) var catalog: [Anime] = []
    private(set) var library: [LibraryItem] = []
    private(set) var progress: [Int: EpisodeProgress] = [:]
    private(set) var recentAnime: [Int: Anime] = [:]
    private(set) var pending: [PendingRate] = []
    private(set) var episodeHistory: [String: EpisodeProgress] = [:]
    private(set) var titleTranslations: [Int: Int] = [:]
    var preferences = PlaybackPreferences()
    var kodikToken = ""
    var completionSuggestion: Anime?
    private var suppressedMarks: [Int: Int] = [:]
    private var undoChange: EpisodeUndo?
    var canUndoEpisodeChange: Bool { undoChange?.account == accountKey }
    var error: String?
    var loading = false
    var signingIn = false
    var syncing = false
    var preferredQuality = 0
    var autoNext = true
    var preferredTranslation = 0
    private var generation = UUID()
    private var refreshTask: Task<String, Error>?
    private var refreshID = UUID()
    private var mutationRevision = 0
    /// What the continuation rule last said, per title. See `ContinueTargetCache`.
    @ObservationIgnored private var targets = ContinueTargetCache()
    private var loaded = false
    private let authentication = Authentication()
    private let saveSession: (Session?) throws -> Void
    var accountKey: String { session.map { "user-\($0.account.id)" } ?? "guest" }
    var accountID: Int64? { session?.account.id }
    var continueWatching: [Anime] {
        let local = progress.values.sorted { $0.updatedAt > $1.updatedAt }.compactMap { recentAnime[$0.animeID] }
        let watching = library.filter { ["watching", "rewatching"].contains($0.status) }.map(\.anime)
        var seen = Set<Int>()
        return (local + watching).filter { anime in
            seen.insert(anime.id).inserted && !(rate(for: anime.id)?.status == "completed" && progress[anime.id]?.watched != false)
        }
    }

    init(service: any AnimeService, store: any LocalStorage, configuration: AppConfiguration, session: Session? = nil,
         saveSession: @escaping (Session?) throws -> Void = { try KeychainSession.write($0) }) {
        self.service = service; self.store = store; self.configuration = configuration; self.session = session; self.saveSession = saveSession
        do {
            catalog = try store.read([Anime].self, key: "catalog") ?? []
            preferredQuality = try store.read(Int.self, key: "quality") ?? 0
            preferences = try store.read(PlaybackPreferences.self, key: "playbackPreferences") ?? PlaybackPreferences()
            preferences.normalize()
            kodikToken = try store.read(String.self, key: "kodikToken") ?? ""
            service.configureKodikToken(kodikToken)
            preferredTranslation = try store.read(Int.self, key: "translation") ?? 0
            autoNext = try store.read(Bool.self, key: "autoNext") ?? true
            try restoreAccount()
        } catch { self.error = error.localizedDescription }
    }
    func start() async {
        guard !loaded else { return }; loaded = true
        _ = downloads
        await notifications.refreshAuthorization()
        await notifications.registerActions()
        EpisodeBackgroundRefresh.schedule(enabled: session != nil && notifications.isEnabled)
        // Nothing here waits on GitHub: an app that blocked its first frame on a question about
        // whether a newer build exists would start slowly on the one network where it matters least.
        Task { await checkForUpdates() }
        await reload()
    }
    func reload() async {
        guard !loading else { return }; loading = true
        defer { loading = false }
        do {
            catalog = try await service.discover()
            try store.write(catalog, key: "catalog")
        } catch is CancellationError {} catch { self.error = error.localizedDescription }
        if session != nil { await reloadLibrary(); await flush() }
    }
    func reloadLibrary() async {
        guard let userID = accountID else { return }
        let fence = generation
        let revision = mutationRevision
        do {
            let rates = try await authorized { token in try await self.service.library(userID, token: token) }
            guard fence == generation, revision == mutationRevision else { return }
            targets.invalidate()
            library = rates
            for change in pending { apply(change, rateID: rate(for: change.id)?.id ?? 0) }
            try persistLibrary()
            if let notificationService, !Task.isCancelled {
                await notificationService.process(library: library, progress: Array(episodeHistory.values), account: accountKey)
            }
        } catch is CancellationError {} catch { if fence == generation { self.error = error.localizedDescription } }
    }
    func signIn() async {
        guard !signingIn else { return }
        guard configuration.canSignIn else { error = AppError.missingConfiguration.localizedDescription; return }
        signingIn = true
        let fence = generation
        defer { signingIn = false }
        do {
            let code = try await authentication.signIn(clientID: configuration.clientID)
            var tokens = try await service.exchange(code)
            if tokens.created_at == nil { tokens.created_at = Date().timeIntervalSince1970 }
            let account = try await service.account(tokens.access_token)
            guard fence == generation else { return }
            let newSession = Session(account: account, tokens: tokens)
            try saveSession(newSession)
            session = newSession; generation = UUID(); syncing = false
            try restoreAccount()
            notificationService?.setAccount(accountKey)
            EpisodeBackgroundRefresh.schedule(enabled: notifications.isEnabled)
            await reloadLibrary(); await flush()
        } catch let failure as ASWebAuthenticationSessionError where failure.code == .canceledLogin {} catch {
            if fence == generation { self.error = errorMessage(error) }
        }
    }
    func signOut() {
        do {
            try saveSession(nil)
            generation = UUID(); refreshTask?.cancel(); refreshTask = nil
            refreshID = UUID(); syncing = false
            session = nil; try restoreAccount()
            notificationService?.setAccount(nil)
            EpisodeBackgroundRefresh.schedule(enabled: false)
        } catch { self.error = error.localizedDescription }
    }
    /// One authorization code for a television, obtained by this phone and never exchanged here.
    /// The account this app is signed into — if any — is not involved: the code buys the
    /// television its own session, for whichever account the person signs in with.
    func televisionAuthorization() async throws -> String {
        guard configuration.canSignIn else { throw AppError.missingConfiguration }
        return try await authentication.authorizeForTelevision(clientID: configuration.clientID)
    }
    /// The title behind a deep link: whatever is already known, otherwise one fetch. Nil when the
    /// catalogue has nothing under that id, which is the honest answer to a link from anywhere.
    func anime(id: Int) async -> Anime? {
        if let known = recentAnime[id] ?? rate(for: id)?.anime ?? catalog.first(where: { $0.id == id }) { return known }
        return try? await service.details(id)
    }
    /// How many full-screen players are on screen. A link that arrives over one must not open a
    /// second; the counter is kept here because the player is presented from several screens.
    private(set) var playersOpen = 0
    func playerAppeared() { playersOpen += 1 }
    func playerDisappeared() { playersOpen = max(0, playersOpen - 1) }
    func rate(for id: Int) -> LibraryItem? { library.first { $0.anime.id == id } }
    func progressFor(animeID: Int, episode: Int) -> EpisodeProgress? { episodeHistory["\(animeID):\(episode)"] }
    func continueTarget(for anime: Anime) -> ContinueTarget {
        targets.target(for: anime, threshold: preferences.watchedThreshold) { anime in
            let rate = rate(for: anime.id)
            return ContinueTarget.resolve(anime: anime, counted: rate?.episodes ?? 0,
                                          rewatching: rate?.status == "rewatching",
                                          progress: episodeHistory.values.filter { $0.animeID == anime.id },
                                          threshold: preferences.watchedThreshold)
        }
    }
    func beginPlayback(anime: Anime) {
        notificationService?.clearForPlayback(animeID: anime.id)
        suppressedMarks[anime.id] = nil
        if session != nil, rate(for: anime.id) == nil { queueRate(anime: anime, status: "watching", episodes: 0) }
    }
    func setNotificationsEnabled(_ enabled: Bool) async {
        if enabled, !(await notifications.requestAuthorization()) { return }
        guard await notifications.setEnabled(enabled) else { return }
        EpisodeBackgroundRefresh.schedule(enabled: enabled && session != nil)
        if enabled { await reloadLibrary() }
    }
    func markEpisode(anime: Anime, episode: Int, watched: Bool) {
        guard session != nil, episode > 0, episode <= max(anime.availableEpisodes, rate(for: anime.id)?.episodes ?? 0) else { return }
        let previous = snapshot
        targets.invalidate()
        let rate = rate(for: anime.id)
        let previousSuppressed = suppressedMarks
        if watched {
            guard episode > (rate?.episodes ?? 0) else { return }
            stageRate(anime: anime, status: watchedStatus(rate?.status), episodes: episode)
            suppressedMarks[anime.id] = nil
        } else {
            guard let rate, rate.episodes >= episode else { return }
            stageRate(anime: anime, status: rate.status, episodes: episode - 1)
            episodeHistory = episodeHistory.filter { $0.value.animeID != anime.id || $0.value.episode < episode }
            progress[anime.id] = episodeHistory.values.filter { $0.animeID == anime.id }.max { $0.updatedAt < $1.updatedAt }
            suppressedMarks[anime.id] = episode
        }
        do {
            try persist(from: previous); mutationRevision += 1
            undoChange = EpisodeUndo(account: accountKey, anime: anime, rate: rate, history: previous.episodeHistory.filter { $0.value.animeID == anime.id }, progress: previous.progress[anime.id])
            if watched { downloadManager?.onWatched(animeID: anime.id, episode: episode) }
            else {
                for number in episode...max(episode, rate?.episodes ?? episode) { downloadManager?.unmarkWatched(animeID: anime.id, episode: number) }
            }
            if watched && anime.episodes > 0 && episode >= anime.episodes { completionSuggestion = anime }
            Task { await flush() }
        } catch { restore(previous); suppressedMarks = previousSuppressed; self.error = error.localizedDescription }
    }
    func setEpisodes(anime: Anime, count: Int) {
        guard let rate = rate(for: anime.id) else { return }
        if count < rate.episodes { markEpisode(anime: anime, episode: max(0, count) + 1, watched: false) }
        else { queueRate(anime: anime, status: rate.status, episodes: count) }
    }
    func undoEpisodeChange() {
        guard let undo = undoChange, undo.account == accountKey else { return }
        let previous = snapshot
        targets.invalidate()
        stageRate(anime: undo.anime, status: undo.rate?.status ?? "watching", episodes: undo.rate?.episodes ?? 0)
        episodeHistory = episodeHistory.filter { $0.value.animeID != undo.anime.id }.merging(undo.history) { _, old in old }
        progress[undo.anime.id] = undo.progress
        do {
            try persist(from: previous); mutationRevision += 1; undoChange = nil; suppressedMarks[undo.anime.id] = nil
            Task { await flush() }
        } catch { restore(previous); self.error = error.localizedDescription }
    }
    private func watchedStatus(_ status: String?) -> String {
        guard let status, !["planned", "on_hold"].contains(status) else { return "watching" }
        return status
    }
    func preferredTranslation(for animeID: Int, available: [Translation], episode: Int) -> Int {
        let usage = titleTranslations.values.reduce(into: [Int: Int]()) { $0[$1, default: 0] += 1 }
        return TranslationPreference.pick(available, episode: episode, remembered: titleTranslations[animeID], studios: preferences.studios, usage: usage)
    }
    func rememberTranslation(_ id: Int, for animeID: Int) {
        guard id > 0 else { return }
        let previous = titleTranslations
        titleTranslations[animeID] = id
        do { try persistLibrary() } catch { titleTranslations = previous; self.error = error.localizedDescription }
    }
    func queueRate(anime: Anime, status: String, episodes: Int) {
        guard session != nil else { return }
        let previous = snapshot
        stageRate(anime: anime, status: status, episodes: episodes)
        do { try persist(from: previous); mutationRevision += 1 } catch {
            restore(previous)
            self.error = error.localizedDescription; return
        }
        Task { await flush() }
    }
    func saveProgress(_ value: EpisodeProgress, anime: Anime, account: String) {
        // Closing a player from a previous session must never change the new account.
        guard account == accountKey, value.animeID == anime.id, value.episode > 0,
              value.position.isFinite, value.duration.isFinite, value.position >= 0, value.duration >= 0 else { return }
        guard !(suppressedMarks[anime.id].map { value.episode >= $0 } ?? false) else { return }
        let previous = snapshot
        targets.invalidate()
        progress[anime.id] = value; recentAnime[anime.id] = anime
        episodeHistory["\(anime.id):\(value.episode)"] = value
        let watched = value.duration > 0 && value.position >= value.duration * preferences.watchedThreshold
        let suppressed = suppressedMarks[anime.id].map { value.episode >= $0 } ?? false
        let shouldQueue = watched && !suppressed && session != nil && value.episode > (rate(for: anime.id)?.episodes ?? 0)
        if shouldQueue {
            stageRate(anime: anime, status: watchedStatus(rate(for: anime.id)?.status), episodes: value.episode)
        }
        do { try persist(from: previous) }
        catch {
            library = previous.library; pending = previous.pending; progress = previous.progress; recentAnime = previous.recent
            episodeHistory = previous.episodeHistory
            self.error = error.localizedDescription; return
        }
        if shouldQueue {
            mutationRevision += 1
            downloadManager?.onWatched(animeID: anime.id, episode: value.episode)
            if anime.episodes > 0 && value.episode >= anime.episodes { completionSuggestion = anime }
            Task { await flush() }
        }
    }
    func savePreferences() {
        preferences.normalize()
        do {
            try store.write(preferences, key: "playbackPreferences")
            try store.write(kodikToken, key: "kodikToken")
            service.configureKodikToken(kodikToken)
            try store.write(preferredQuality, key: "quality")
            try store.write(preferredTranslation, key: "translation")
            try store.write(autoNext, key: "autoNext")
        } catch { self.error = error.localizedDescription }
    }
    func flush() async {
        guard !syncing, let userID = accountID else { return }
        syncing = true
        let fence = generation
        defer { if fence == generation { syncing = false } }
        while let change = pending.first, fence == generation {
            do {
                let rateID = rate(for: change.id)?.id ?? 0
                let saved = try await authorized { token in try await self.service.setRate(change, userID: userID, rateID: rateID, token: token) }
                guard fence == generation else { return }
                // A newer local change wins, but retain the server ID for its update.
                if let index = library.firstIndex(where: { $0.anime.id == change.id }) { library[index].id = saved.id }
                pending.removeAll { $0.revision == change.revision }
                mutationRevision += 1
                try persistLibrary()
            } catch {
                // Durable outbox remains available for the next foreground refresh.
                if fence == generation { self.error = "Прогресс сохранён на устройстве. Синхронизация не удалась: \(error.localizedDescription)" }
                return
            }
        }
    }
    private func authorized<T>(_ operation: (String) async throws -> T) async throws -> T {
        guard let session else { throw AppError.signedOut }
        let fence = generation
        let token = session.tokens.expiresAt.timeIntervalSinceNow < 60 ? try await refreshedToken() : session.tokens.access_token
        guard fence == generation else { throw AppError.signedOut }
        do {
            let result = try await operation(token)
            guard fence == generation else { throw AppError.signedOut }
            return result
        } catch {
            guard fence == generation, ServiceFailure.of(error)?.status == 401 else { throw error }
            let refreshed = try await refreshedToken()
            guard fence == generation else { throw AppError.signedOut }
            let result = try await operation(refreshed)
            guard fence == generation else { throw AppError.signedOut }
            return result
        }
    }
    private func refreshedToken() async throws -> String {
        guard let session else { throw AppError.signedOut }
        let fence = generation
        if let refreshTask {
            let token = try await refreshTask.value
            guard fence == generation else { throw AppError.signedOut }
            return token
        }
        let identifier = UUID(); refreshID = identifier
        let task = Task { @MainActor in
            var tokens: Tokens
            do { tokens = try await self.service.refresh(session.tokens.refresh_token) }
            catch {
                if fence == self.generation, ServiceFailure.of(error)?.oauthError == "invalid_grant" {
                    self.signOut()
                    if self.session == nil { self.error = "Сессия Shikimori истекла. Войдите снова; ваш прогресс сохранён на устройстве." }
                }
                throw error
            }
            guard fence == self.generation else { throw AppError.signedOut }
            try Task.checkCancellation()
            if tokens.created_at == nil { tokens.created_at = Date().timeIntervalSince1970 }
            let updated = Session(account: session.account, tokens: tokens)
            try self.saveSession(updated)
            self.session = updated
            return tokens.access_token
        }
        refreshTask = task
        defer { if fence == generation && refreshID == identifier { refreshTask = nil } }
        let token = try await task.value
        guard fence == generation else { throw AppError.signedOut }
        return token
    }
    private func apply(_ change: PendingRate, rateID: Int64) {
        targets.invalidate()
        library.removeAll { $0.anime.id == change.id }
        library.append(LibraryItem(id: rateID, anime: change.anime, status: change.status, episodes: change.episodes))
    }
    private func stageRate(anime: Anime, status: String, episodes: Int) {
        let change = PendingRate(anime: anime, status: status, episodes: max(0, min(episodes, anime.episodes > 0 ? anime.episodes : Int.max)))
        pending.removeAll { $0.id == anime.id }; pending.append(change)
        apply(change, rateID: rate(for: anime.id)?.id ?? 0)
    }
    // MARK: - what is written, and how little of it

    private var snapshotKey: String { "\(accountKey).snapshot" }
    private var episodePrefix: String { "\(accountKey).episode." }
    private var animePrefix: String { "\(accountKey).anime." }
    private func episodeKey(_ id: String) -> String { episodePrefix + id }
    private func animeKey(_ id: Int) -> String { "\(animePrefix)\(id)" }

    /// The list, the outbox and the remembered dubs. Everything that changes when the viewer does
    /// something to their list, and nothing that changes while an episode simply plays.
    private func persistLibrary() throws { try store.write(snapshot, key: snapshotKey) }

    /// Writes what actually moved since `previous`.
    ///
    /// A position saved every five seconds touches one record of about a hundred bytes; the library
    /// record — which holds every title in it — is rewritten only when the library itself changed.
    /// Before this, a viewer with three hundred titles re-encoded all of them twelve times a minute
    /// for the whole of an episode.
    private func persist(from previous: AccountSnapshot) throws {
        for (id, value) in episodeHistory where previous.episodeHistory[id] != value {
            try store.write(value, key: episodeKey(id))
        }
        let gone = previous.episodeHistory.keys.filter { episodeHistory[$0] == nil }.map(episodeKey)
        if !gone.isEmpty { try store.remove(gone) }
        for (id, value) in recentAnime where previous.recent[id] != value {
            try store.write(value, key: animeKey(id))
        }
        if previous.library != library || previous.pending != pending || previous.translations != titleTranslations {
            try persistLibrary()
        }
    }
    private var snapshot: AccountSnapshot {
        AccountSnapshot(library: library, pending: pending, progress: progress, recent: recentAnime, episodeHistory: episodeHistory, translations: titleTranslations)
    }
    private func restore(_ snapshot: AccountSnapshot) {
        targets.invalidate()
        library = snapshot.library; pending = snapshot.pending; progress = snapshot.progress; recentAnime = snapshot.recent
        episodeHistory = snapshot.episodeHistory; titleTranslations = snapshot.translations
    }
    private func restoreAccount() throws {
        targets.invalidate()
        undoChange = nil; suppressedMarks = [:]; completionSuggestion = nil
        library = []; pending = []; progress = [:]; recentAnime = [:]; episodeHistory = [:]; titleTranslations = [:]
        let stored = try store.read(AccountSnapshot.self, key: snapshotKey) ?? AccountSnapshot()
        library = stored.library; pending = stored.pending; titleTranslations = stored.translations
        let prefix = episodePrefix, animes = animePrefix
        episodeHistory = try store.readAll(EpisodeProgress.self, prefix: prefix)
            .reduce(into: [:]) { $0[String($1.key.dropFirst(prefix.count))] = $1.value }
        recentAnime = try store.readAll(Anime.self, prefix: animes)
            .reduce(into: [:]) { result, row in
                guard let id = Int(row.key.dropFirst(animes.count)) else { return }
                result[id] = row.value
            }
        // A snapshot written before positions had records of their own carries all of them. It is
        // taken apart once, here, and rewritten without them: from then on this is an empty branch.
        if !stored.episodeHistory.isEmpty || !stored.recent.isEmpty {
            let legacy = AccountSnapshot(library: library, pending: pending, progress: [:],
                                         recent: recentAnime, episodeHistory: episodeHistory,
                                         translations: titleTranslations)
            episodeHistory.merge(stored.episodeHistory) { current, _ in current }
            recentAnime.merge(stored.recent) { current, _ in current }
            try persist(from: legacy)
            try persistLibrary()
        }
        progress = latestPerTitle()
        mutationRevision += 1
    }

    /// Where each title was left, which is the most recent of its episodes. Derived rather than
    /// stored: it was always a second copy of a row the history already held, and a copy that can
    /// disagree with its original is a copy worth not having.
    private func latestPerTitle() -> [Int: EpisodeProgress] {
        episodeHistory.values.reduce(into: [:]) { result, value in
            if let held = result[value.animeID], held.updatedAt >= value.updatedAt { return }
            result[value.animeID] = value
        }
    }
    private func errorMessage(_ error: Error) -> String { error.localizedDescription }
}

private struct EpisodeUndo {
    var account: String
    var anime: Anime
    var rate: LibraryItem?
    var history: [String: EpisodeProgress]
    var progress: EpisodeProgress?
}
