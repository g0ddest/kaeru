import Foundation
import SwiftData
import Security

@Model final class CachedValue {
    @Attribute(.unique) var key: String
    var data: Data
    init(key: String, data: Data) { self.key = key; self.data = data }
}

@MainActor protocol LocalStorage {
    func read<T: Decodable>(_ type: T.Type, key: String) throws -> T?
    func write<T: Encodable>(_ value: T, key: String) throws
    /// Every record whose key begins with `prefix`, by key. What makes a position its own record
    /// rather than a field of one large one: writing one costs a row, and reading them all back
    /// still costs a single query.
    func readAll<T: Decodable>(_ type: T.Type, prefix: String) throws -> [String: T]
    /// Takes records away. Missing keys are not an error — an episode unmarked twice is one story.
    func remove(_ keys: [String]) throws
}

@MainActor final class LocalStore: LocalStorage {
    let container: ModelContainer
    private let context: ModelContext
    init(inMemory: Bool = false) throws {
        container = try ModelContainer(for: CachedValue.self, configurations: ModelConfiguration(isStoredInMemoryOnly: inMemory))
        context = ModelContext(container)
    }

    /// Throws the cache away and opens a fresh one.
    ///
    /// Everything this store holds is a copy of something Shikimori and Kodik can answer again, so
    /// a file that will not open is worth less than an application that will. Sessions live in the
    /// Keychain and downloads on disk: neither is touched here, and the viewer stays signed in.
    static func discardingCache() throws -> LocalStore {
        let store = URL.applicationSupportDirectory.appending(path: "default.store")
        for suffix in ["", "-shm", "-wal"] {
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: store.path() + suffix))
        }
        return try LocalStore()
    }
    func read<T: Decodable>(_ type: T.Type, key: String) throws -> T? {
        let query = FetchDescriptor<CachedValue>(predicate: #Predicate { $0.key == key })
        guard let record = try context.fetch(query).first else { return nil }
        return try JSONDecoder().decode(type, from: record.data)
    }
    func write<T: Encodable>(_ value: T, key: String) throws {
        let data = try JSONEncoder().encode(value)
        let query = FetchDescriptor<CachedValue>(predicate: #Predicate { $0.key == key })
        if let record = try context.fetch(query).first { record.data = data }
        else { context.insert(CachedValue(key: key, data: data)) }
        do { try context.save() }
        catch { context.rollback(); throw error }
    }
    func readAll<T: Decodable>(_ type: T.Type, prefix: String) throws -> [String: T] {
        let query = FetchDescriptor<CachedValue>(predicate: #Predicate { $0.key.starts(with: prefix) })
        let decoder = JSONDecoder()
        return try context.fetch(query).reduce(into: [:]) { result, record in
            result[record.key] = try decoder.decode(type, from: record.data)
        }
    }
    func remove(_ keys: [String]) throws {
        guard !keys.isEmpty else { return }
        let wanted = Set(keys)
        let query = FetchDescriptor<CachedValue>(predicate: #Predicate { wanted.contains($0.key) })
        for record in try context.fetch(query) { context.delete(record) }
        do { try context.save() }
        catch { context.rollback(); throw error }
    }
}

enum KeychainSession {
    private static func query(service: String) -> [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "shikimori-session"] }
    static func read(service: String = "app.kaeru.ios") throws -> Session? {
        var query = query(service: service)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw failure(status) }
        return try JSONDecoder().decode(Session.self, from: data)
    }
    static func write(_ session: Session?, service: String = "app.kaeru.ios") throws {
        let query = query(service: service)
        guard let session else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw failure(status) }
            return
        }
        let data = try JSONEncoder().encode(session)
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw failure(status) }
    }
    private static func failure(_ status: OSStatus) -> AppError { .message("Не удалось сохранить сессию в Связке ключей (\(status)).") }
}
