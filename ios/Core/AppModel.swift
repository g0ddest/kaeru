import Foundation
import Observation
import AuthenticationServices
import KaeruShared

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
        manager.onConnectivityRestored = { [weak self] in
            self?.viewingSync?.push(.reconnected)
            Task { await self?.flush() }
        }
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
    /// When each remembered dub was chosen and what it is called, for sync. See `DubStamp`.
    private(set) var dubStamps: [Int: DubStamp] = [:]
    /// Titles watched «украдкой», by anime id, including ones switched back (see `SecretTitle`).
    private(set) var secrets: [Int: SecretTitle] = [:]
    /// Positions and dubs shared with the viewer's other devices through the worker. Nil in a
    /// build without a relay, and inert without an account.
    @ObservationIgnored private(set) var viewingSync: SyncService?
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
        let watching = myList.filter { ["watching", "rewatching", WatchStatus.secret.rawValue].contains($0.status) }.map(\.anime)
        var seen = Set<Int>()
        return (local + watching).filter { anime in
            seen.insert(anime.id).inserted && !(isFinished(anime.id) && progress[anime.id]?.watched != false)
        }
    }
    /// «Мой список» as it is shown: the Shikimori list, with every title watched «украдкой» under
    /// «Украдкой» only — in place of its Shikimori status if it has one. A title whose card is not
    /// known yet (switched on another device, still being fetched) waits for it.
    var myList: [LibraryItem] {
        let hidden = secrets.filter { $0.value.on }
        guard !hidden.isEmpty else { return library }
        var items = library.filter { hidden[$0.anime.id] == nil }
        for id in hidden.keys.sorted() {
            if let item = secretItem(id), !item.anime.title.isEmpty { items.append(item) }
        }
        return items
    }
    func isSecret(_ animeID: Int) -> Bool { secrets[animeID]?.on == true }
    /// A title watched «украдкой» as a list row: its secret count under «Украдкой», and the id of its
    /// Shikimori record (if any), which switching back updates rather than duplicates.
    private func secretItem(_ id: Int) -> LibraryItem? {
        guard let secret = secrets[id], secret.on else { return nil }
        let real = shikimoriRate(for: id)
        let anime = secret.anime ?? real?.anime ?? recentAnime[id] ?? Anime(id: id, title: "")
        return LibraryItem(id: real?.id ?? 0, anime: anime, status: WatchStatus.secret.rawValue, episodes: secret.watched,
                           updatedAt: Self.stampFormat.string(from: Date(syncMilliseconds: secret.at)))
    }
    private static let stampFormat = ISO8601DateFormatter()

    /// A frame from the episodes for each title that asked, for wide artwork: a poster stretched
    /// across a hero is a blur of pixels. Asked once per title per launch; none is remembered as none.
    private(set) var stills: [Int: URL] = [:]
    @ObservationIgnored private var stillsAsked = Set<Int>()
    func loadStill(for id: Int) {
        guard stillsAsked.insert(id).inserted else { return }
        // Its own task, not the caller's: the hero's first page redraws and cancels whatever it
        // started, and a cancelled ask left that title on its stretched poster for good.
        Task {
            do {
                let found = try await service.screenshots(id)
                if let first = found.first, let url = URL(string: first) { stills[id] = url }
            } catch {
                stillsAsked.remove(id) // a failure is asked again the next time the title is shown
            }
        }
    }



    init(service: any AnimeService, store: any LocalStorage, configuration: AppConfiguration, session: Session? = nil,
         saveSession: @escaping (Session?) throws -> Void = { try KeychainSession.write($0) },
         sync: SyncEnvironment? = nil) {
        self.service = service; self.store = store; self.configuration = configuration; self.session = session; self.saveSession = saveSession
        if let environment = sync ?? (configuration.togetherRelayURL.isEmpty ? nil : .live),
           let client = SyncClient(relayURL: configuration.togetherRelayURL, transport: environment.transport) {
            viewingSync = SyncService(model: self, client: client, environment: environment)
        }
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
        viewingSync?.start()
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
            viewingSync?.start()
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
        if let known = recentAnime[id] ?? secrets[id]?.anime ?? rate(for: id)?.anime ?? catalog.first(where: { $0.id == id }) { return known }
        return try? await service.details(id)
    }
    /// How many full-screen players are on screen. A link that arrives over one must not open a
    /// second; the counter is kept here because the player is presented from several screens.
    private(set) var playersOpen = 0
    func playerAppeared() { playersOpen += 1 }
    func playerDisappeared() { playersOpen = max(0, playersOpen - 1) }
    /// The title's place in the list as the app shows it: for one watched «украдкой», status
    /// «secret» and the secret count. What Shikimori holds is `shikimoriRate(for:)`.
    func rate(for id: Int) -> LibraryItem? { secretItem(id) ?? shikimoriRate(for: id) }
    private func shikimoriRate(for id: Int) -> LibraryItem? { library.first { $0.anime.id == id } }
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
        if session != nil, !isSecret(anime.id), rate(for: anime.id) == nil { queueRate(anime: anime, status: "watching", episodes: 0) }
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
        let secret = secrets[anime.id].flatMap { $0.on ? $0 : nil }
        let previousSuppressed = suppressedMarks
        if watched {
            guard episode > (rate?.episodes ?? 0) else { return }
            if secret != nil { countSecretly(anime, episode) }
            else { stageRate(anime: anime, status: watchedStatus(rate?.status), episodes: episode) }
            suppressedMarks[anime.id] = nil
        } else {
            guard let rate, rate.episodes >= episode else { return }
            if secret != nil { countSecretly(anime, episode - 1) }
            else { stageRate(anime: anime, status: rate.status, episodes: episode - 1) }
            episodeHistory = episodeHistory.filter { $0.value.animeID != anime.id || $0.value.episode < episode }
            progress[anime.id] = episodeHistory.values.filter { $0.animeID == anime.id }.max { $0.updatedAt < $1.updatedAt }
            suppressedMarks[anime.id] = episode
        }
        do {
            try persist(from: previous); mutationRevision += 1
            undoChange = EpisodeUndo(account: accountKey, anime: anime, rate: rate, secret: secret, history: previous.episodeHistory.filter { $0.value.animeID == anime.id }, progress: previous.progress[anime.id])
            if watched { downloadManager?.onWatched(animeID: anime.id, episode: episode) }
            else {
                for number in episode...max(episode, rate?.episodes ?? episode) { downloadManager?.unmarkWatched(animeID: anime.id, episode: number) }
            }
            if secret != nil { secretSaved(anime.id, from: previous.secrets[anime.id]); return }
            if watched && anime.endsWith(episode) { completionSuggestion = anime }
            Task { await flush() }
        } catch { restore(previous); suppressedMarks = previousSuppressed; self.error = error.localizedDescription }
    }
    func setEpisodes(anime: Anime, count: Int) {
        guard let rate = rate(for: anime.id) else { return }
        if count < rate.episodes { markEpisode(anime: anime, episode: max(0, count) + 1, watched: false) }
        else if isSecret(anime.id) {
            guard session != nil, count > rate.episodes else { return }
            let previous = snapshot
            targets.invalidate()
            countSecretly(anime, count)
            do { try persist(from: previous) } catch { restore(previous); self.error = error.localizedDescription; return }
            secretSaved(anime.id, from: previous.secrets[anime.id])
        }
        else { queueRate(anime: anime, status: rate.status, episodes: count) }
    }
    func undoEpisodeChange() {
        guard let undo = undoChange, undo.account == accountKey else { return }
        let previous = snapshot
        targets.invalidate()
        if let before = undo.secret {
            // Switching in or out of «украдкой» drops the undo, so the title is still secret here.
            countSecretly(undo.anime, before.watched)
        } else {
            stageRate(anime: undo.anime, status: undo.rate?.status ?? "watching", episodes: undo.rate?.episodes ?? 0)
        }
        episodeHistory = episodeHistory.filter { $0.value.animeID != undo.anime.id }.merging(undo.history) { _, old in old }
        progress[undo.anime.id] = undo.progress
        do {
            try persist(from: previous); mutationRevision += 1; undoChange = nil; suppressedMarks[undo.anime.id] = nil
            if undo.secret != nil { secretSaved(undo.anime.id, from: previous.secrets[undo.anime.id]); return }
            Task { await flush() }
        } catch { restore(previous); self.error = error.localizedDescription }
    }
    private func watchedStatus(_ status: String?) -> String {
        guard let status, !["planned", "on_hold"].contains(status) else { return "watching" }
        return status
    }
    func preferredTranslation(for animeID: Int, available: [Translation], episode: Int) -> Int {
        TranslationPreference.pick(available, episode: episode, remembered: titleTranslations[animeID], studios: preferences.studios,
                                   usage: TranslationPreference.usage(titleTranslations))
    }
    /// The dubs in the order a chooser shows them: the shared ranking, as Android's sheet has it.
    func rankedTranslations(for animeID: Int, available: [Translation]) -> [Translation] {
        TranslationPreference.ranked(available, remembered: titleTranslations[animeID], studios: preferences.studios,
                                     usage: TranslationPreference.usage(titleTranslations))
    }
    func rememberTranslation(_ id: Int, title: String = "", for animeID: Int) {
        guard id > 0 else { return }
        let previous = titleTranslations, previousStamps = dubStamps
        let name = title.isEmpty && titleTranslations[animeID] == id ? dubStamps[animeID]?.title ?? "" : title
        let stamp = DubStamp(title: name, at: (viewingSync?.now() ?? Date()).syncMilliseconds)
        titleTranslations[animeID] = id
        dubStamps[animeID] = stamp
        do { try persistLibrary() } catch {
            titleTranslations = previous; dubStamps = previousStamps
            self.error = error.localizedDescription; return
        }
        viewingSync?.dubChosen(animeID, id: id, title: stamp.title, at: stamp.at)
    }
    /// A status chosen for the title. «Украдкой» is one of them here, though Shikimori never hears
    /// of it: switching to it leaves the Shikimori record as it is and only stops writing to it;
    /// switching from it sets the chosen status there, with the count watched meanwhile if that is
    /// more than Shikimori's (`SecretRules.episodesToSendWhenTurnedOff`) — the one write the time
    /// spent «украдкой» ever makes. Otherwise the record keeps the count it has.
    func queueRate(anime: Anime, status: String, episodes: Int) {
        guard session != nil else { return }
        if status == WatchStatus.secret.rawValue { watchSecretly(anime); return }
        let previous = snapshot
        if var secret = secrets[anime.id], secret.on {
            targets.invalidate()
            let counted = shikimoriRate(for: anime.id)?.episodes
            let count = SecretCount.toSendWhenTurnedOff(watched: secret.watched, counted: counted) ?? counted ?? 0
            secret.on = false; secret.at = stamp(); secret.anime = anime
            secrets[anime.id] = secret
            if undoChange?.anime.id == anime.id { undoChange = nil }
            stageRate(anime: anime, status: status, episodes: count)
            do { try persist(from: previous); mutationRevision += 1 } catch {
                restore(previous)
                self.error = error.localizedDescription; return
            }
            secretSaved(anime.id, from: previous.secrets[anime.id])
            Task { await flush() }
            return
        }
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
        let watched = value.isWatched(threshold: preferences.watchedThreshold)
        let suppressed = suppressedMarks[anime.id].map { value.episode >= $0 } ?? false
        let shouldQueue = watched && !suppressed && session != nil && value.episode > (rate(for: anime.id)?.episodes ?? 0)
        let secret = isSecret(anime.id)
        if shouldQueue {
            if secret { countSecretly(anime, value.episode) }
            else { stageRate(anime: anime, status: watchedStatus(rate(for: anime.id)?.status), episodes: value.episode) }
        }
        do { try persist(from: previous) }
        catch {
            library = previous.library; pending = previous.pending; progress = previous.progress; recentAnime = previous.recent
            episodeHistory = previous.episodeHistory; secrets = previous.secrets
            self.error = error.localizedDescription; return
        }
        viewingSync?.positionSaved(value)
        if shouldQueue && secret {
            mutationRevision += 1
            downloadManager?.onWatched(animeID: anime.id, episode: value.episode)
            secretSaved(anime.id, from: previous.secrets[anime.id])
        } else if shouldQueue {
            mutationRevision += 1
            downloadManager?.onWatched(animeID: anime.id, episode: value.episode)
            if anime.endsWith(value.episode) { completionSuggestion = anime }
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
    // MARK: - viewing sync

    /// The player paused, moved to another episode or went away: the batch goes now.
    func pushSync(_ reason: SyncReason) { viewingSync?.push(reason) }
    func setSyncEnabled(_ on: Bool) {
        preferences.syncOn = on
        savePreferences()
        viewingSync?.setEnabled(on)
        // As on Android: the list as it stands is where things stand, so a title completed from
        // here on leaves its tombstone rather than becoming the first list sync sees.
        if on { viewingSync?.libraryChanged(syncStatuses) }
    }
    func appWentToBackground() { viewingSync?.wentToBackground() }
    func appBecameActive() { viewingSync?.becameActive() }
    /// Done with: «Просмотрено» on Shikimori, or — for a title watched «украдкой», whatever its
    /// Shikimori status — every episode of a released title watched (`SecretRules.finished`).
    func isFinished(_ animeID: Int) -> Bool {
        if let secret = secrets[animeID], secret.on { return secret.finished(now: viewingSync?.now() ?? Date()) }
        return shikimoriRate(for: animeID)?.status == "completed"
    }
    /// Every title `isFinished` says is done, as sync's tombstones follow them.
    func finishedTitles() -> Set<Int> {
        Set(syncStatuses.filter { $0.status == WatchStatus.completed.rawValue }.map(\.anime.id))
    }
    /// What this device holds, as the shared sync rules read it. `titles` narrows the positions —
    /// the one part that grows — to those titles, where only they are compared.
    func syncState(of titles: Set<Int>? = nil) -> LocalSyncState {
        let positions = titles.map { wanted in episodeHistory.values.filter { wanted.contains($0.animeID) } } ?? Array(episodeHistory.values)
        return .of(positions: positions, translations: titleTranslations, stamps: dubStamps, secrets: secrets)
    }
    /// A request to the worker with this account's token, refreshed once on a 401 like any other.
    func syncAuthorized(_ operation: (String) async throws -> SyncTitles) async throws -> SyncTitles {
        try await authorized(operation)
    }
    /// What another device did, already found newer than this one's by the shared rules
    /// (`SyncRules.newer`): tombstones first, then positions, dubs and «украдкой». Written as they
    /// are, without passing through `saveProgress`, so nothing is marked on Shikimori and nothing
    /// goes back to the worker. False when it could not be written.
    @discardableResult
    func applySynced(_ newer: SyncNewer) -> Bool {
        let previous = snapshot
        for (animeID, gone) in newer.tombstoneMoments {
            episodeHistory = episodeHistory.filter { $0.value.animeID != animeID || $0.value.updatedAt.syncMilliseconds > gone }
        }
        var unknown = Set<Int>()
        for position in newer.positions {
            let value = EpisodeProgress(position)
            episodeHistory["\(value.animeID):\(value.episode)"] = value
            if recentAnime[value.animeID] == nil {
                if let known = rate(for: value.animeID)?.anime ?? catalog.first(where: { $0.id == value.animeID }) {
                    recentAnime[value.animeID] = known
                } else { unknown.insert(value.animeID) }
            }
        }
        // A dub chosen later elsewhere: its stamp is taken, and the dub itself where it differs.
        let dubs = newer.dubChanges
        for (animeID, at) in newer.dubMoments {
            if let dub = dubs[animeID] { titleTranslations[animeID] = Int(dub.id) }
            let name = dubs[animeID].map { $0.title ?? "" } ?? dubStamps[animeID]?.title ?? ""
            dubStamps[animeID] = DubStamp(title: name, at: at)
        }
        // «Украдкой» switched or counted on another device. Nothing of it goes to Shikimori: the
        // device where it was switched back already wrote there.
        var cardless = Set<Int>()
        for secret in newer.secrets {
            let animeID = Int(secret.animeId)
            let local = secrets[animeID]
            let card = local?.anime ?? shikimoriRate(for: animeID)?.anime ?? recentAnime[animeID] ?? catalog.first { $0.id == animeID }
            secrets[animeID] = SecretTitle(on: secret.on, watched: Int(secret.watched), at: secret.at, anime: card)
            if card == nil && secret.on { cardless.insert(animeID) }
            if undoChange?.anime.id == animeID { undoChange = nil }
        }
        guard previous.episodeHistory != episodeHistory || previous.translations != titleTranslations
                || previous.dubs != dubStamps || previous.recent != recentAnime || previous.secrets != secrets else { return true }
        targets.invalidate()
        progress = latestPerTitle()
        do { try persist(from: previous) } catch { restore(previous); return false }
        // «Продолжить» shows a title by its card; one started on another device may not be known here.
        let account = accountKey
        for animeID in unknown.union(cardless) {
            Task {
                guard let anime = try? await service.details(animeID), account == accountKey else { return }
                let before = snapshot
                if unknown.contains(animeID), recentAnime[animeID] == nil { recentAnime[animeID] = anime }
                if cardless.contains(animeID), secrets[animeID] != nil, secrets[animeID]?.anime == nil { secrets[animeID]?.anime = anime }
                do { try persist(from: before) } catch { restore(before) }
            }
        }
        return true
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
    // MARK: - «смотреть украдкой»

    /// A normal title — or one not in the list at all — switched to «украдкой»: counted from what
    /// Shikimori has (`SecretRules.watchedWhenTurnedOn`), and the Shikimori record left exactly as it is.
    private func watchSecretly(_ anime: Anime) {
        guard !isSecret(anime.id) else { return }
        let previous = snapshot
        targets.invalidate()
        secrets[anime.id] = SecretTitle(on: true, watched: SecretCount.whenTurnedOn(counted: shikimoriRate(for: anime.id)?.episodes),
                                        at: stamp(), anime: anime)
        if undoChange?.anime.id == anime.id { undoChange = nil }
        if completionSuggestion?.id == anime.id { completionSuggestion = nil }
        do { try persist(from: previous); mutationRevision += 1 } catch {
            restore(previous)
            self.error = error.localizedDescription; return
        }
        secretSaved(anime.id, from: previous.secrets[anime.id])
    }
    /// An episode marked or unmarked on a title watched «украдкой»: the count moves to it as a
    /// rate's would (`SecretRules.watchedAfterMark`), and the card is refreshed from whatever screen
    /// had the title. The same count changes nothing. Staged only; the caller persists.
    private func countSecretly(_ anime: Anime, _ episodes: Int) {
        guard var secret = secrets[anime.id], secret.on,
              let watched = SecretCount.afterMark(watched: secret.watched, episodes: episodes) else { return }
        secret.watched = watched
        secret.at = stamp()
        if !anime.title.isEmpty { secret.anime = anime }
        secrets[anime.id] = secret
    }
    /// Told to sync once it is on the device, and only when it moved since `previous`: a count
    /// that stayed the same is not news. Off, sync drops it; there is nothing else to tell.
    private func secretSaved(_ animeID: Int, from previous: SecretTitle?) {
        guard let secret = secrets[animeID]?.wire, secret != previous?.wire else { return }
        viewingSync?.secretChanged(animeID, secret)
    }
    private func stamp() -> Int64 { (viewingSync?.now() ?? Date()).syncMilliseconds }
    /// What sync's tombstones follow: the list, with a title watched «украдкой» as «secret», or as
    /// «completed» once all of it is watched — which leaves the tombstone a completed title does.
    private var syncStatuses: [LibraryItem] {
        let hidden = secrets.filter { $0.value.on }
        guard !hidden.isEmpty else { return library }
        let now = viewingSync?.now() ?? Date()
        return library.filter { hidden[$0.anime.id] == nil } + hidden.map { id, secret in
            LibraryItem(id: 0, anime: secret.anime ?? Anime(id: id, title: ""),
                        status: secret.finished(now: now) ? WatchStatus.completed.rawValue : WatchStatus.secret.rawValue,
                        episodes: secret.watched)
        }
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
    private func persistLibrary() throws {
        try store.write(snapshot, key: snapshotKey)
        viewingSync?.libraryChanged(syncStatuses)
    }

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
        if previous.library != library || previous.pending != pending || previous.translations != titleTranslations
            || previous.dubs != dubStamps || previous.secrets != secrets {
            try persistLibrary()
        }
    }
    private var snapshot: AccountSnapshot {
        AccountSnapshot(library: library, pending: pending, progress: progress, recent: recentAnime, episodeHistory: episodeHistory,
                        translations: titleTranslations, dubs: dubStamps, secrets: secrets)
    }
    private func restore(_ snapshot: AccountSnapshot) {
        targets.invalidate()
        library = snapshot.library; pending = snapshot.pending; progress = snapshot.progress; recentAnime = snapshot.recent
        episodeHistory = snapshot.episodeHistory; titleTranslations = snapshot.translations; dubStamps = snapshot.dubs
        secrets = snapshot.secrets
    }
    private func restoreAccount() throws {
        targets.invalidate()
        viewingSync?.accountChanged()
        undoChange = nil; suppressedMarks = [:]; completionSuggestion = nil
        library = []; pending = []; progress = [:]; recentAnime = [:]; episodeHistory = [:]; titleTranslations = [:]; dubStamps = [:]; secrets = [:]
        let stored = try store.read(AccountSnapshot.self, key: snapshotKey) ?? AccountSnapshot()
        library = stored.library; pending = stored.pending; titleTranslations = stored.translations; dubStamps = stored.dubs
        secrets = stored.secrets
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
                                         translations: titleTranslations, dubs: dubStamps, secrets: secrets)
            episodeHistory.merge(stored.episodeHistory) { current, _ in current }
            recentAnime.merge(stored.recent) { current, _ in current }
            try persist(from: legacy)
            try persistLibrary()
        }
        progress = latestPerTitle()
        mutationRevision += 1
        viewingSync?.libraryChanged(syncStatuses)
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
    /// The title's «украдкой» state before the change, when it was watched so.
    var secret: SecretTitle?
    var history: [String: EpisodeProgress]
    var progress: EpisodeProgress?
}
