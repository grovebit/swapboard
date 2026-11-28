import Foundation

public struct Clip: Codable, Equatable {
    public var id: String
    public var text: String
    public var sensitive: Bool
    public var ts: Int64

    public init(id: String = UUID().uuidString.lowercased(), text: String, sensitive: Bool,
                ts: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        self.id = id
        self.text = text
        self.sensitive = sensitive
        self.ts = ts
    }
}

/// Encrypted application messages (PROTOCOL.md §5).
public enum Message: Equatable {
    case auth(device: String)
    case welcome(mac: String)
    case clip(Clip)
    case ack(id: String)
    case ping
    case pong
    /// Client → server: the client stops sending `ping` until its next `ping` (PROTOCOL.md §6).
    case quiet
    case unknown(type: String)

    private struct Envelope: Codable {
        var type: String
        var device: String?
        var mac: String?
        var id: String?
        var text: String?
        var sensitive: Bool?
        var ts: Int64?
    }

    public func encoded() throws -> Data {
        var env: Envelope
        switch self {
        case let .auth(device): env = Envelope(type: "auth", device: device)
        case let .welcome(mac): env = Envelope(type: "welcome", mac: mac)
        case let .clip(clip):
            env = Envelope(type: "clip", id: clip.id, text: clip.text, sensitive: clip.sensitive, ts: clip.ts)
        case let .ack(id): env = Envelope(type: "ack", id: id)
        case .ping: env = Envelope(type: "ping")
        case .pong: env = Envelope(type: "pong")
        case .quiet: env = Envelope(type: "quiet")
        case let .unknown(type): env = Envelope(type: type)
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return try encoder.encode(env)
    }

    public static func decode(_ data: Data) throws -> Message {
        let env: Envelope
        do {
            env = try JSONDecoder().decode(Envelope.self, from: data)
        } catch {
            throw ProtocolError.malformedMessage("invalid JSON")
        }
        func require<T>(_ value: T?, _ field: String) throws -> T {
            guard let value else { throw ProtocolError.malformedMessage("\(env.type) missing \(field)") }
            return value
        }
        switch env.type {
        case "auth": return .auth(device: try require(env.device, "device"))
        case "welcome": return .welcome(mac: try require(env.mac, "mac"))
        case "clip":
            return .clip(Clip(id: try require(env.id, "id"),
                              text: try require(env.text, "text"),
                              sensitive: env.sensitive ?? false,
                              ts: env.ts ?? 0))
        case "ack": return .ack(id: try require(env.id, "id"))
        case "ping": return .ping
        case "pong": return .pong
        case "quiet": return .quiet
        default: return .unknown(type: env.type)
        }
    }
}

/// Plaintext handshake frames (PROTOCOL.md §3), sent as WebSocket text frames.
public struct ClientHello: Codable, Equatable {
    public var v: Int
    public var pid: String
    public var nc: String

    public init(v: Int = ProtocolConstants.version, pid: String, nc: Data) {
        self.v = v
        self.pid = pid
        self.nc = Base64URL.encode(nc)
    }
}

public struct ServerHello: Codable, Equatable {
    public var v: Int
    public var ns: String

    public init(v: Int = ProtocolConstants.version, ns: Data) {
        self.v = v
        self.ns = Base64URL.encode(ns)
    }
}

/// Remembers the most recent clip ids so duplicates can be dropped (PROTOCOL.md §6).
public struct RecentIDs {
    private var ids: [String] = []
    public let capacity: Int

    public init(capacity: Int = 50) { self.capacity = capacity }

    /// Returns true if the id was new (and records it).
    public mutating func insert(_ id: String) -> Bool {
        if ids.contains(id) { return false }
        ids.append(id)
        if ids.count > capacity { ids.removeFirst(ids.count - capacity) }
        return true
    }
}
