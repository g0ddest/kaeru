import Foundation
import Observation
import AuthenticationServices

@MainActor @Observable final class AppModel {
    let service: any AnimeService
    let store: any LocalStorage
    let configuration: AppConfiguration
    private(set) var session: Session?
    private(set) var catalog: [Anime] = []
    private(set) var library: [LibraryItem] = []
    private(set) var progress: [Int: EpisodeProgress] = [:]
    private(set) var recentAnime: [Int: Anime] = [:]
    private(set) var pending: [PendingRate] = []
    var error: String?
    var loading = false
    var signingIn = false
    var syncing = false
    var preferredQuality = 720
    var autoNext = true
    var preferredTranslation = 0
    private var generation = UUID()
    private var refreshTask: Task<String, Error>?
    private var refreshID = UUID()
    private var mutationRevision = 0
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
            preferredQuality = try store.read(Int.self, key: "quality") ?? 720
            preferredTranslation = try store.read(Int.self, key: "translation") ?? 0
            autoNext = try store.read(Bool.self, key: "autoNext") ?? true
            try restoreAccount()
        } catch { self.error = error.localizedDescription }
    }
    func start() async {
        guard !loaded else { return }; loaded = true
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
            library = rates
            for change in pending { apply(change, rateID: rate(for: change.id)?.id ?? 0) }
            try persistAccount()
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
        } catch { self.error = error.localizedDescription }
    }
    func rate(for id: Int) -> LibraryItem? { library.first { $0.anime.id == id } }
    func queueRate(anime: Anime, status: String, episodes: Int) {
        guard session != nil else { return }
        let previousPending = pending, previousLibrary = library
        stageRate(anime: anime, status: status, episodes: episodes)
        do { try persistAccount(); mutationRevision += 1 } catch {
            pending = previousPending; library = previousLibrary
            self.error = error.localizedDescription; return
        }
        Task { await flush() }
    }
    func saveProgress(_ value: EpisodeProgress, anime: Anime, account: String) {
        // Closing a player from a previous session must never change the new account.
        guard account == accountKey, value.position.isFinite, value.duration.isFinite else { return }
        let previous = AccountSnapshot(library: library, pending: pending, progress: progress, recent: recentAnime)
        progress[anime.id] = value; recentAnime[anime.id] = anime
        let shouldQueue = value.watched && session != nil && value.episode > (rate(for: anime.id)?.episodes ?? 0)
        if shouldQueue {
            let complete = anime.episodes > 0 && value.episode >= anime.episodes
            stageRate(anime: anime, status: complete ? "completed" : "watching", episodes: value.episode)
        }
        do { try persistAccount() }
        catch {
            library = previous.library; pending = previous.pending; progress = previous.progress; recentAnime = previous.recent
            self.error = error.localizedDescription; return
        }
        if shouldQueue { mutationRevision += 1; Task { await flush() } }
    }
    func savePreferences() {
        do {
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
                try persistAccount()
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
            guard fence == generation, error.localizedDescription.contains("401") else { throw error }
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
                if fence == self.generation && error.localizedDescription.contains("invalid_grant") {
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
        library.removeAll { $0.anime.id == change.id }
        library.append(LibraryItem(id: rateID, anime: change.anime, status: change.status, episodes: change.episodes))
    }
    private func stageRate(anime: Anime, status: String, episodes: Int) {
        let change = PendingRate(anime: anime, status: status, episodes: max(0, min(episodes, anime.episodes > 0 ? anime.episodes : Int.max)))
        pending.removeAll { $0.id == anime.id }; pending.append(change)
        apply(change, rateID: rate(for: anime.id)?.id ?? 0)
    }
    private func persistAccount() throws {
        try store.write(AccountSnapshot(library: library, pending: pending, progress: progress, recent: recentAnime), key: "\(accountKey).snapshot")
    }
    private func restoreAccount() throws {
        library = []; pending = []; progress = [:]; recentAnime = [:]
        let snapshot = try store.read(AccountSnapshot.self, key: "\(accountKey).snapshot") ?? AccountSnapshot()
        library = snapshot.library; pending = snapshot.pending; progress = snapshot.progress; recentAnime = snapshot.recent
        mutationRevision += 1
    }
    private func errorMessage(_ error: Error) -> String { error.localizedDescription }
}
