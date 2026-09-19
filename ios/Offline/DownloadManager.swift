import AVFoundation
import Foundation
import Network
import Observation

/// Keep one instance for the app lifetime, including background URLSession launches.
@MainActor @Observable final class DownloadManager {
    private(set) var entries: [DownloadEntry] = []
    private(set) var policies = DownloadPolicies()
    private(set) var isConnected = false
    private(set) var isOnWiFi = false
    private(set) var isReady = false
    private(set) var errorMessage: String?
    /// The parent can use this to retry its durable library outbox.
    @ObservationIgnored var onConnectivityRestored: (() -> Void)?
    @ObservationIgnored private let service: any AnimeService
    @ObservationIgnored private let store: OfflineCatalogStore
    @ObservationIgnored private let transfers: BackgroundTransfers
    @ObservationIgnored private let monitor = NWPathMonitor()
    @ObservationIgnored private var catalog = OfflineCatalog()
    @ObservationIgnored private var resolving: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var playing: OfflineEpisode?
    @ObservationIgnored private var lastProgressSave = Date.distantPast
    @ObservationIgnored private var catalogReadable = true
    @ObservationIgnored private var reconciling = false

    init(service: any AnimeService) {
        self.service = service
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Offline", isDirectory: true)
        store = OfflineCatalogStore(directory: directory)
        transfers = BackgroundTransfers(mediaDirectory: directory.appendingPathComponent("Media", isDirectory: true))
        do { catalog = try store.load(); entries = catalog.entries; policies = catalog.policies }
        catch { catalogReadable = false; errorMessage = "Не удалось прочитать каталог загрузок: \(error.localizedDescription)" }
        transfers.owner = self
        monitor.pathUpdateHandler = { [weak self] path in
            let connected = path.status == .satisfied
            let wifi = connected && !path.isExpensive && (path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet))
            Task { @MainActor [weak self] in self?.connectivityChanged(connected: connected, wifi: wifi) }
        }
        monitor.start(queue: DispatchQueue(label: "app.kaeru.offline.connectivity"))
        Task { [weak self] in await self?.reconcile() }
    }

    var usedBytes: Int64 { entries.reduce(0) { $0 + $1.bytes } }
    var reservedBytes: Int64 { entries.reduce(0) { $0 + $1.reservedBytes } }
    var groupedEntries: [(anime: Anime, entries: [DownloadEntry])] {
        Dictionary(grouping: entries, by: { $0.anime.id }).values.compactMap { rows in
            guard let anime = rows.first?.anime else { return nil }
            return (anime, rows.sorted { $0.episode < $1.episode })
        }.sorted { $0.anime.title.localizedStandardCompare($1.anime.title) == .orderedAscending }
    }
    func clearError() { errorMessage = nil }

    /// Synchronous admission; resolution and background transfer continue independently of the view.
    func enqueue(anime: Anime, episodes: [Int], translation: Int, quality: Int) {
        guard catalogReadable, anime.id > 0, translation > 0 else { return }
        let sizes = entries.filter { $0.state == .completed && $0.bytes > 0 }.map(\.bytes)
        let estimate = sizes.isEmpty ? DownloadPolicies.fallbackEstimate : sizes.reduce(0, +) / Int64(sizes.count)
        for episode in Set(episodes).sorted() where episode > 0 && episode <= anime.availableEpisodes {
            let wanted = quality >= 0 ? quality : policies.quality
            let existing = entries.filter { $0.anime.id == anime.id && $0.episode == episode }
            if existing.contains(where: { $0.translation == translation && $0.quality == wanted && ($0.state == .completed || $0.state.isPending || $0.state == .paused) }) { continue }
            if playing == OfflineEpisode(animeID: anime.id, episode: episode), !existing.isEmpty {
                errorMessage = "Завершите воспроизведение эпизода перед заменой загрузки."; continue
            }
            let reclaim = existing.reduce(Int64(0)) { $0 + $1.reservedBytes }
            guard policies.fits(used: max(0, reservedBytes - reclaim), additional: estimate) else {
                errorMessage = DownloadFailure.storageLimit.message; break
            }
            guard hasDiskSpace(estimate) else { errorMessage = DownloadFailure.noSpace.message; break }
            for row in existing { remove(id: row.id) }
            guard !entries.contains(where: { $0.anime.id == anime.id && $0.episode == episode }) else { continue }
            var entry = DownloadEntry(anime: anime, episode: episode, translation: translation, quality: wanted)
            entry.estimatedBytes = estimate
            entries.append(entry)
        }
        if flush() { pump() }
    }

    func localAsset(animeID: Int, episode: Int, translation: Int? = nil) -> URL? {
        for row in entries where row.anime.id == animeID && row.episode == episode && row.state == .completed && (translation == nil || row.translation == translation) {
            if let url = assetURL(row), playable(row, at: url) { return url }
        }
        return nil
    }

    func pause(id: String) {
        guard let index = index(id), entries[index].state.isPending else { return }
        let token = entries[index].taskToken
        entries[index].state = .paused
        if let token { resolving.removeValue(forKey: token)?.cancel(); transfers.pause(token) }
        flush(); pump()
    }
    func resume(id: String) {
        guard let index = index(id), entries[index].state == .paused else { return }
        entries[index].state = .queued
        if flush() { pump() }
    }
    func retry(id: String) {
        guard let index = index(id), [.failed, .cancelled].contains(entries[index].state) else { return }
        let row = entries[index]
        guard policies.fits(used: reservedBytes - row.reservedBytes, additional: max(row.estimatedBytes, row.bytes)) else { errorMessage = DownloadFailure.storageLimit.message; return }
        entries[index].failure = nil
        entries[index].state = .queued
        if flush() { pump() }
    }
    func cancel(id: String) {
        guard let index = index(id), entries[index].state != .completed else { return }
        entries[index].state = .cancelled
        stopAttempt(index)
        if clearFile(index) { entries[index].bytes = 0; entries[index].progress = 0 }
        flush(); pump()
    }
    func remove(id: String) {
        guard let index = index(id) else { return }
        entries[index].state = .removing
        // Persist deletion before canceling: a crash cannot resurrect a removed transfer.
        guard flush() else { return }
        stopAttempt(index)
        guard entries[index].episodeKey != playing else { return }
        guard clearFile(index) else { return }
        entries.remove(at: index)
        flush(); pump()
    }
    func removeAll(animeID: Int? = nil) {
        for id in entries.filter({ animeID == nil || $0.anime.id == animeID }).map(\.id) { remove(id: id) }
    }

    func updatePolicies(_ newValue: DownloadPolicies) {
        let changedNetwork = policies.wifiOnly != newValue.wifiOnly
        policies = newValue
        if !policies.deleteWatched { for index in entries.indices { entries[index].deleteWhenReleased = false } }
        if changedNetwork {
            // Session network requirements are immutable. Recreate active attempts under the new policy.
            for index in entries.indices where entries[index].state.isPending || entries[index].state == .paused {
                let paused = entries[index].state == .paused
                stopAttempt(index)
                _ = clearFile(index)
                entries[index].state = paused ? .paused : .queued
            }
        }
        if flush() { sweepWatched(); pump() }
    }
    func nowPlaying(animeID: Int?, episode: Int?) {
        playing = animeID.flatMap { id in episode.map { OfflineEpisode(animeID: id, episode: $0) } }
        for row in entries where row.state == .removing && row.episodeKey != playing { remove(id: row.id) }
        sweepWatched()
    }
    func beginPlayback(animeID: Int, episode: Int) { nowPlaying(animeID: animeID, episode: episode) }
    func endPlayback() { nowPlaying(animeID: nil, episode: nil) }
    func onWatched(animeID: Int, episode: Int) { markWatched(animeID: animeID, episode: episode) }

    func markWatched(animeID: Int, episode: Int) {
        for index in entries.indices where entries[index].anime.id == animeID && entries[index].episode == episode {
            OfflineRules.markWatched(&entries[index], enabled: policies.deleteWatched)
        }
        if flush() { sweepWatched() }
    }
    func unmarkWatched(animeID: Int, episode: Int) {
        for index in entries.indices where entries[index].anime.id == animeID && entries[index].episode == episode { entries[index].deleteWhenReleased = false }
        flush()
    }
    private func sweepWatched() {
        guard policies.deleteWatched else { return }
        for id in entries.filter({ OfflineRules.canDeleteWatched($0, playing: playing) }).map(\.id) { remove(id: id) }
    }

    /// Call on scene activation as well as at launch; system-purged assets become retryable rows.
    func reconcile() async {
        guard catalogReadable, !reconciling else { return }
        reconciling = true; isReady = false
        let live = await transfers.restore(catalog.transfers)
        var playableIDs = Set<String>()
        for index in entries.indices {
            // Recover a progressive file moved just before the process died, before its callback was persisted.
            if entries[index].relativePath == nil, let token = entries[index].taskToken, !entries[index].isHLS {
                let destination = transfers.destination(token)
                if FileManager.default.fileExists(atPath: destination.path) { entries[index].relativePath = relativePath(destination) }
            }
            if let url = assetURL(entries[index]), playable(entries[index], at: url) { playableIDs.insert(entries[index].id) }
        }
        entries = OfflineRules.reconcile(entries, liveTokens: live, playableIDs: playableIDs)
        let wanted = Set(entries.compactMap(\.taskToken))
        for transfer in catalog.transfers where !wanted.contains(transfer.token) || !live.contains(transfer.token) {
            transfers.cancel(transfer.token); transfers.retire(transfer)
        }
        catalog.transfers.removeAll { !wanted.contains($0.token) || !live.contains($0.token) }
        if !policies.deleteWatched { for index in entries.indices { entries[index].deleteWhenReleased = false } }
        for row in entries where row.state == .paused { if let token = row.taskToken { transfers.pause(token) } }
        reconciling = false; isReady = true
        flush()
        for row in entries where row.state == .removing { remove(id: row.id) }
        sweepWatched(); pump()
    }

    @discardableResult func handleBackgroundEvents(identifier: String, completionHandler: @escaping () -> Void) -> Bool {
        transfers.handleBackgroundEvents(identifier: identifier, completion: completionHandler)
    }
    @discardableResult func flush() -> Bool {
        guard catalogReadable else { return false }
        catalog.entries = entries; catalog.policies = policies
        do { try store.save(catalog); return true }
        catch { errorMessage = "Не удалось сохранить загрузки: \(error.localizedDescription)"; return false }
    }

    private func connectivityChanged(connected: Bool, wifi: Bool) {
        let restored = connected && !isConnected
        isConnected = connected; isOnWiFi = wifi
        if restored { onConnectivityRestored?() }
        pump()
    }
    private func pump() {
        guard isReady, catalogReadable else { return }
        var active = 0
        for index in entries.indices where entries[index].state.isPending {
            if !isConnected || (policies.wifiOnly && !isOnWiFi) {
                if let token = entries[index].taskToken { transfers.pause(token) }
                entries[index].state = !isConnected ? .waitingForNetwork : .waitingForWiFi
                continue
            }
            if active >= 2 { continue }
            active += 1
            if let token = entries[index].taskToken, transfers.tasks[token] != nil {
                entries[index].state = .downloading; transfers.resume(token)
            } else if let token = entries[index].taskToken, resolving[token] != nil {
                entries[index].state = .resolving
            } else { resolve(index) }
        }
        flush()
    }
    private func resolve(_ index: Int) {
        let token = UUID().uuidString
        entries[index].taskToken = token; entries[index].state = .resolving
        let row = entries[index]
        guard flush() else { entries[index].state = .failed; entries[index].failure = .unknown; return }
        resolving[token] = Task { [weak self] in
            guard let self else { return }
            defer { self.resolving.removeValue(forKey: token) }
            do {
                let stream = try await self.service.resolve(row.anime.id, translation: row.translation, episode: row.episode)
                try Task.checkCancellation()
                guard let index = self.index(token: token), self.entries[index].state.isPending else { return }
                guard stream.translation.id == row.translation, stream.episode == row.episode else { throw OfflineTransferError.unavailable }
                // A refresh must retain the exact variant; initial admission follows Android's fallback ladder.
                let quality = row.refreshAttempts.isEmpty && row.bytes == 0
                    ? OfflineRules.quality(wanted: row.quality, available: stream.urls.map(\.quality)) : row.quality
                guard let quality, let raw = stream.urls.first(where: { $0.quality == quality })?.url,
                      let url = URL(string: raw), ["https", "http"].contains(url.scheme?.lowercased() ?? "") else { throw OfflineTransferError.unavailable }
                self.entries[index].quality = quality
                self.entries[index].isHLS = url.pathExtension.lowercased() == "m3u8" || url.path.lowercased().contains(".m3u8")
                guard self.hasDiskSpace(max(0, row.estimatedBytes - row.bytes)) else { throw OfflineTransferError.noSpace }
                let transfer = OfflineTransfer(token: token, isHLS: self.entries[index].isHLS, wifiOnly: self.policies.wifiOnly, headers: stream.headers)
                self.catalog.transfers.append(transfer)
                self.entries[index].state = .downloading; self.entries[index].failure = nil
                guard self.flush() else { throw OfflineTransferError.persistence }
                try self.transfers.start(transfer, url: url, title: "\(row.anime.title) · \(row.episode)")
                self.pump()
            } catch is CancellationError {
                if let index = self.index(token: token) { self.entries[index].taskToken = nil; self.flush() }
            } catch { self.transferFailed(token, error: error, status: nil) }
        }
    }

    func transferLocation(_ token: String, location: URL) {
        guard let index = index(token: token), entries[index].state != .removing && entries[index].state != .cancelled else {
            if relativePath(location) != nil { try? FileManager.default.removeItem(at: location) }
            return
        }
        entries[index].relativePath = relativePath(location)
        flush()
    }
    func transferProgress(_ token: String, bytes: Int64, expected: Int64, progress: Double) {
        guard let index = index(token: token) else { return }
        entries[index].bytes = max(entries[index].bytes, bytes)
        entries[index].expectedBytes = max(0, expected)
        entries[index].progress = progress.isFinite ? min(1, max(0, progress)) : 0
        if Date().timeIntervalSince(lastProgressSave) > 2 { lastProgressSave = Date(); flush() }
    }
    func transferCompleted(_ token: String) {
        guard let index = index(token: token), let url = assetURL(entries[index]), playable(entries[index], at: url) else {
            transferFailed(token, error: OfflineTransferError.missingFile, status: nil); return
        }
        entries[index].state = .completed; entries[index].progress = 1
        entries[index].bytes = diskSize(url); entries[index].failure = nil; entries[index].updatedAt = Date()
        finishAttempt(index)
        flush(); pump()
    }
    func transferFailed(_ token: String, error: Error?, status: Int?) {
        guard let index = index(token: token) else { return }
        let ns = error as NSError?
        let nested = ns?.userInfo[NSUnderlyingErrorKey] as? NSError
        let isNetwork = [ns, nested].compactMap { $0 }.contains { $0.domain == NSURLErrorDomain && [NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorTimedOut, NSURLErrorCannotConnectToHost, NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed].contains($0.code) }
        let noSpace = (error as? OfflineTransferError) == .noSpace || [ns, nested].compactMap { $0 }.contains { ($0.domain == NSPOSIXErrorDomain && $0.code == 28) || ($0.domain == NSCocoaErrorDomain && $0.code == NSFileWriteOutOfSpaceError) }
        let explicitFailure = error as? OfflineTransferError
        let expired = status == 403 || status == 410 || [ns, nested].compactMap { $0?.localizedDescription }.contains { $0.contains("403") || $0.contains("410") }
        let refresh = !noSpace && explicitFailure == nil && OfflineRules.shouldRefresh(status: expired ? 403 : status, bytes: entries[index].bytes) && OfflineRules.claimRefresh(&entries[index])
        finishAttempt(index)
        _ = clearFile(index)
        entries[index].progress = 0
        if refresh {
            entries[index].state = .queued; entries[index].failure = nil
        } else if isNetwork {
            entries[index].state = .waitingForNetwork; entries[index].failure = .network
        } else {
            entries[index].state = .failed
            entries[index].failure = noSpace ? .noSpace : expired ? .expiredLink : explicitFailure == .unavailable ? .unavailable : explicitFailure == .missingFile ? .missingFile : .unknown
        }
        flush()
        // Network failures wait for connectivity/foreground rather than busy-looping on a stale NWPath.
        if !isNetwork { pump() }
    }

    private func stopAttempt(_ index: Int) {
        if let token = entries[index].taskToken { resolving.removeValue(forKey: token)?.cancel(); transfers.cancel(token) }
        finishAttempt(index)
    }
    private func finishAttempt(_ index: Int) {
        guard let token = entries[index].taskToken else { return }
        if let transfer = catalog.transfers.first(where: { $0.token == token }) { transfers.retire(transfer) }
        catalog.transfers.removeAll { $0.token == token }
        entries[index].taskToken = nil
    }
    private func index(_ id: String) -> Int? { entries.firstIndex { $0.id == id } }
    private func index(token: String) -> Int? { entries.firstIndex { $0.taskToken == token } }
    private func relativePath(_ url: URL) -> String? {
        let home = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true).standardizedFileURL.path + "/"
        let path = url.standardizedFileURL.path
        return path.hasPrefix(home) ? String(path.dropFirst(home.count)) : nil
    }
    private func assetURL(_ row: DownloadEntry) -> URL? {
        guard let path = row.relativePath, !path.hasPrefix("/"), !path.split(separator: "/").contains("..") else { return nil }
        return URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true).appendingPathComponent(path)
    }
    private func playable(_ row: DownloadEntry, at url: URL) -> Bool {
        guard FileManager.default.fileExists(atPath: url.path) else { return false }
        return row.isHLS ? AVURLAsset(url: url).assetCache?.isPlayableOffline == true : diskSize(url) > 0
    }
    @discardableResult private func clearFile(_ index: Int) -> Bool {
        if let url = assetURL(entries[index]), FileManager.default.fileExists(atPath: url.path) {
            do { try FileManager.default.removeItem(at: url) }
            catch { errorMessage = "Не удалось удалить загрузку: \(error.localizedDescription)"; return false }
        }
        entries[index].relativePath = nil; entries[index].bytes = 0
        return true
    }
    private func diskSize(_ url: URL) -> Int64 {
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileSizeKey, .totalFileAllocatedSizeKey]
        if let value = try? url.resourceValues(forKeys: keys), value.isRegularFile == true { return Int64(value.totalFileAllocatedSize ?? value.fileSize ?? 0) }
        guard let files = FileManager.default.enumerator(at: url, includingPropertiesForKeys: Array(keys)) else { return 0 }
        var size: Int64 = 0
        for case let file as URL in files {
            if let value = try? file.resourceValues(forKeys: keys), value.isRegularFile == true { size += Int64(value.totalFileAllocatedSize ?? value.fileSize ?? 0) }
        }
        return size
    }
    private func hasDiskSpace(_ bytes: Int64) -> Bool {
        let values = try? store.directory.deletingLastPathComponent().resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage.map { $0 >= bytes + 64 * 1024 * 1024 } ?? true
    }
}

private enum OfflineTransferError: Error { case unavailable, noSpace, missingFile, persistence }
