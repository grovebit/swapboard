import Foundation

public struct Pairing: Codable, Equatable {
    public var pid: String
    /// base64url PSK
    public var psk: String
    public var device: String?
    public var created: Date
    /// False until the phone completes its first handshake.
    public var confirmed: Bool
    /// Unconfirmed pairings (and their QR code) expire after `ProtocolConstants.pairingTTL`.
    public var expires: Date?
    public var lastSeen: Date?

    public init(pid: String, psk: Data, created: Date = Date()) {
        self.pid = pid
        self.psk = Base64URL.encode(psk)
        self.created = created
        self.confirmed = false
        self.expires = created.addingTimeInterval(ProtocolConstants.pairingTTL)
    }

    public var pskData: Data? { Base64URL.decode(psk) }
    public var displayName: String { device ?? "phone (not yet connected)" }
}

public struct Settings: Codable, Equatable {
    public static let defaultIgnoredApps = [
        "com.1password.1password", "com.agilebits.onepassword7", "com.bitwarden.desktop",
        "org.keepassxc.keepassxc", "com.apple.Passwords", "com.apple.keychainaccess",
    ]

    public var port: UInt16 = ProtocolConstants.defaultPort
    public var paused = false
    public var notifyOnReceive = true
    public var maxClipKB = 1024
    public var ignoredApps: [String] = Settings.defaultIgnoredApps
    /// Start the agent when the user logs in (the menu-bar app's "Open at Login").
    public var openAtLogin = true

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        port = try c.decodeIfPresent(UInt16.self, forKey: .port) ?? ProtocolConstants.defaultPort
        paused = try c.decodeIfPresent(Bool.self, forKey: .paused) ?? false
        notifyOnReceive = try c.decodeIfPresent(Bool.self, forKey: .notifyOnReceive) ?? true
        maxClipKB = try c.decodeIfPresent(Int.self, forKey: .maxClipKB) ?? 1024
        ignoredApps = try c.decodeIfPresent([String].self, forKey: .ignoredApps) ?? Settings.defaultIgnoredApps
        openAtLogin = try c.decodeIfPresent(Bool.self, forKey: .openAtLogin) ?? true
    }

    /// Effective byte limit for outgoing text (never above the protocol maximum).
    public var maxClipBytes: Int { min(max(maxClipKB, 1) * 1024, ProtocolConstants.maxClipBytes) }
}

public struct Config: Codable, Equatable {
    public var pairings: [Pairing] = []
    public var settings = Settings()

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pairings = try c.decodeIfPresent([Pairing].self, forKey: .pairings) ?? []
        settings = try c.decodeIfPresent(Settings.self, forKey: .settings) ?? Settings()
    }

    public func psk(for pid: String) -> Data? {
        pairings.first { $0.pid == pid }?.pskData
    }

    /// Drops unconfirmed pairings whose QR code has expired. Returns true if anything changed.
    public mutating func pruneExpired(now: Date = Date()) -> Bool {
        let before = pairings.count
        pairings.removeAll { !$0.confirmed && ($0.expires ?? .distantPast) < now }
        return pairings.count != before
    }
}

/// Reads and writes `config.json` (mode 0600 inside a 0700 directory).
public final class ConfigStore {
    public let url: URL

    public init(directory: URL) {
        url = directory.appendingPathComponent("config.json")
    }

    public func load() -> Config {
        guard let data = try? Data(contentsOf: url) else { return Config() }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode(Config.self, from: data)) ?? Config()
    }

    public func save(_ config: Config) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(config)
        let fm = FileManager.default
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true,
                               attributes: [.posixPermissions: 0o700])
        let tmp = url.deletingLastPathComponent().appendingPathComponent(".config.json.\(getpid()).tmp")
        guard fm.createFile(atPath: tmp.path, contents: data, attributes: [.posixPermissions: 0o600]) else {
            throw CocoaError(.fileWriteUnknown)
        }
        guard rename(tmp.path, url.path) == 0 else {
            try? fm.removeItem(at: tmp)
            throw CocoaError(.fileWriteUnknown)
        }
    }

    @discardableResult
    public func update(_ body: (inout Config) -> Void) throws -> Config {
        var config = load()
        body(&config)
        try save(config)
        return config
    }
}

/// Holds only the latest clip that the other side hasn't acknowledged (PROTOCOL.md §6).
public struct Outbox {
    public struct Entry: Equatable {
        public let clip: Clip
        public let created: Date
    }

    public private(set) var pending: Entry?
    public let ttl: TimeInterval

    public init(ttl: TimeInterval = ProtocolConstants.pendingTTL) {
        self.ttl = ttl
    }

    public mutating func put(_ clip: Clip, now: Date = Date()) {
        pending = Entry(clip: clip, created: now)
    }

    /// The pending clip if it is still young enough to deliver; expired clips are dropped.
    public mutating func freshPending(now: Date = Date()) -> Clip? {
        guard let entry = pending else { return nil }
        if now.timeIntervalSince(entry.created) > ttl {
            pending = nil
            return nil
        }
        return entry.clip
    }

    public mutating func acknowledge(_ id: String) {
        if pending?.clip.id == id { pending = nil }
    }
}
