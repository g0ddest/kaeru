import AVFoundation
import Foundation

/// All delegate callbacks are delivered by OperationQueue.main. The preconcurrency
/// conformances bridge Apple's nonisolated Objective-C delegate requirements.
@MainActor final class BackgroundTransfers: NSObject, @preconcurrency AVAssetDownloadDelegate, @preconcurrency URLSessionDownloadDelegate {
    weak var owner: DownloadManager?
    private var sessions: [String: URLSession] = [:]
    private(set) var tasks: [String: URLSessionTask] = [:]
    private var completions: [String: [() -> Void]] = [:]
    private var finishedEvents = Set<String>()
    let mediaDirectory: URL

    init(mediaDirectory: URL) { self.mediaDirectory = mediaDirectory }

    private func session(_ transfer: OfflineTransfer) -> URLSession {
        if let session = sessions[transfer.identifier] { return session }
        let configuration = URLSessionConfiguration.background(withIdentifier: transfer.identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.waitsForConnectivity = true
        configuration.allowsCellularAccess = !transfer.wifiOnly
        configuration.allowsExpensiveNetworkAccess = !transfer.wifiOnly
        configuration.httpAdditionalHeaders = transfer.headers
        let session: URLSession
        if transfer.isHLS {
            session = AVAssetDownloadURLSession(configuration: configuration, assetDownloadDelegate: self, delegateQueue: .main)
        } else {
            session = URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
        }
        sessions[transfer.identifier] = session
        return session
    }

    func restore(_ transfers: [OfflineTransfer]) async -> Set<String> {
        var live = Set<String>()
        for transfer in transfers {
            let restored = await session(transfer).allTasks
            for task in restored {
                guard task.state != .completed, task.state != .canceling else { continue }
                guard task.taskDescription == transfer.token else { task.cancel(); continue }
                tasks[transfer.token] = task
                live.insert(transfer.token)
            }
        }
        return live
    }

    func start(_ transfer: OfflineTransfer, url: URL, title: String) throws {
        let session = session(transfer)
        let task: URLSessionTask
        if let hls = session as? AVAssetDownloadURLSession {
            var options: [String: Any] = [AVURLAssetAllowsCellularAccessKey: !transfer.wifiOnly]
            if let agent = transfer.headers.first(where: { $0.key.lowercased() == "user-agent" })?.value {
                options[AVURLAssetHTTPUserAgentKey] = agent
            }
            // The resolver returns a quality-specific playlist. Do not download auxiliary variants.
            let asset = AVURLAsset(url: url, options: options)
            let configuration = AVAssetDownloadConfiguration(asset: asset, title: title)
            configuration.auxiliaryContentConfigurations = []
            task = hls.makeAssetDownloadTask(downloadConfiguration: configuration)
        } else {
            var request = URLRequest(url: url)
            request.allHTTPHeaderFields = transfer.headers
            task = session.downloadTask(with: request)
        }
        task.taskDescription = transfer.token
        tasks[transfer.token] = task
        task.resume()
    }

    func pause(_ token: String) { tasks[token]?.suspend() }
    func resume(_ token: String) { if tasks[token]?.state == .suspended { tasks[token]?.resume() } }
    func cancel(_ token: String) { tasks.removeValue(forKey: token)?.cancel() }
    func retire(_ transfer: OfflineTransfer) {
        tasks.removeValue(forKey: transfer.token)
        // finishTasksAndInvalidate preserves any pending delegate callbacks.
        sessions[transfer.identifier]?.finishTasksAndInvalidate()
    }
    func destination(_ token: String) -> URL { mediaDirectory.appendingPathComponent(token + ".mp4") }

    @discardableResult
    func handleBackgroundEvents(identifier: String, completion: @escaping () -> Void) -> Bool {
        guard identifier.hasPrefix(OfflineTransfer.prefix) else { return false }
        completions[identifier, default: []].append(completion)
        if finishedEvents.remove(identifier) != nil { finish(identifier); return true }
        if sessions[identifier] == nil {
            let suffix = String(identifier.dropFirst(OfflineTransfer.prefix.count))
            let hls = suffix.hasPrefix("hls.")
            let token = String(suffix.dropFirst(hls ? 4 : 5))
            // Unknown sessions belong to attempts removed before a background relaunch.
            let transfer = OfflineTransfer(token: token, isHLS: hls, wifiOnly: true, headers: [:])
            let restored = session(transfer)
            restored.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
        }
        return true
    }

    private func finish(_ identifier: String) {
        owner?.flush()
        let callbacks = completions.removeValue(forKey: identifier) ?? []
        callbacks.forEach { $0() }
    }
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        guard let identifier = session.configuration.identifier else { return }
        if completions[identifier] == nil { finishedEvents.insert(identifier) }
        else { finish(identifier) }
    }
    func urlSession(_ session: URLSession, didBecomeInvalidWithError error: Error?) {
        if let identifier = session.configuration.identifier {
            sessions.removeValue(forKey: identifier)
            if completions[identifier] != nil { finish(identifier) }
        }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let token = downloadTask.taskDescription else { return }
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode
        guard let status, (200..<300).contains(status) else {
            owner?.transferFailed(token, error: nil, status: status)
            return
        }
        // URLSession deletes its temporary file immediately after this method returns.
        do {
            try FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
            let destination = destination(token)
            try FileManager.default.moveItem(at: location, to: destination)
            owner?.transferLocation(token, location: destination)
        } catch { owner?.transferFailed(token, error: error, status: status) }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let token = downloadTask.taskDescription else { return }
        let progress = totalBytesExpectedToWrite > 0 ? Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) : 0
        owner?.transferProgress(token, bytes: totalBytesWritten, expected: totalBytesExpectedToWrite, progress: progress)
    }
    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingTo location: URL) {
        if let token = assetDownloadTask.taskDescription { owner?.transferLocation(token, location: location) }
    }
    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, willDownloadTo location: URL) {
        if let token = assetDownloadTask.taskDescription { owner?.transferLocation(token, location: location) }
    }
    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didLoad timeRange: CMTimeRange, totalTimeRangesLoaded loadedTimeRanges: [NSValue], timeRangeExpectedToLoad: CMTimeRange) {
        guard let token = assetDownloadTask.taskDescription else { return }
        let total = timeRangeExpectedToLoad.duration.seconds
        let loaded = loadedTimeRanges.reduce(0) { $0 + $1.timeRangeValue.duration.seconds }
        owner?.transferProgress(token, bytes: max(0, assetDownloadTask.countOfBytesReceived), expected: 0, progress: total > 0 ? loaded / total : 0)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let token = task.taskDescription else { return }
        tasks.removeValue(forKey: token)
        if let error { owner?.transferFailed(token, error: error, status: nil) }
        else { owner?.transferCompleted(token) }
    }
}
