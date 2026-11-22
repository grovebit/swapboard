import Foundation

/// base64url without padding (RFC 4648 §5), as used everywhere in the protocol.
public enum Base64URL {
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decode(_ string: String) -> Data? {
        guard !string.contains("="), !string.contains("+"), !string.contains("/") else { return nil }
        var s = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        switch s.count % 4 {
        case 0: break
        case 2: s += "=="
        case 3: s += "="
        default: return nil
        }
        return Data(base64Encoded: s)
    }
}

public enum Hex {
    public static func encode(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    public static func decode(_ string: String) -> Data? {
        guard string.count % 2 == 0 else { return nil }
        var data = Data(capacity: string.count / 2)
        var index = string.startIndex
        while index < string.endIndex {
            let next = string.index(index, offsetBy: 2)
            guard let byte = UInt8(string[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data
    }
}

/// Percent-encodes everything except RFC 3986 unreserved characters (A-Z a-z 0-9 - . _ ~).
/// Matches what the Android side decodes (a space is %20, a plus sign is %2B).
public enum PercentEncoding {
    private static let unreserved: CharacterSet = {
        var set = CharacterSet()
        set.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return set
    }()

    public static func encode(_ string: String) -> String {
        string.addingPercentEncoding(withAllowedCharacters: unreserved) ?? string
    }
}

extension UInt64 {
    var bigEndianBytes: Data {
        withUnsafeBytes(of: self.bigEndian) { Data($0) }
    }

    init(bigEndianBytes data: Data) {
        precondition(data.count == 8)
        self = data.reduce(0) { ($0 << 8) | UInt64($1) }
    }
}
