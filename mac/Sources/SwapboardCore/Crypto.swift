import CryptoKit
import Foundation
import Security

public enum ProtocolConstants {
    public static let version = 1
    public static let aad = Data("swapboard-v1".utf8)
    public static let infoC2S = Data("swapboard v1 c2s".utf8)
    public static let infoS2C = Data("swapboard v1 s2c".utf8)
    public static let pskLength = 32
    public static let nonceLength = 16
    public static let maxPlaintext = 8 * 1024 * 1024
    public static let maxClipBytes = 1024 * 1024
    public static let frameOverhead = 8 + 16
    public static let pendingTTL: TimeInterval = 5 * 60
    public static let serverIdleTimeout: TimeInterval = 180
    public static let defaultPort: UInt16 = 48613
    public static let pairingTTL: TimeInterval = 10 * 60
}

public enum CloseCode: UInt16 {
    case normal = 1000
    case authFailed = 4001
    case unsupportedVersion = 4002
    case tooLarge = 4003
    case replaced = 4004
}

public enum ProtocolError: Error, Equatable {
    case truncatedFrame
    case frameTooLarge
    case badCounter(expected: UInt64, got: UInt64)
    case authenticationFailed
    case malformedMessage(String)
    case unsupportedVersion(Int)
    case unknownPairing
    case unexpected(String)

    public var closeCode: CloseCode {
        switch self {
        case .frameTooLarge: return .tooLarge
        case .unsupportedVersion: return .unsupportedVersion
        default: return .authFailed
        }
    }
}

public func hkdfSHA256(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
    let key = HKDF<SHA256>.deriveKey(
        inputKeyMaterial: SymmetricKey(data: ikm),
        salt: salt,
        info: info,
        outputByteCount: length
    )
    return key.withUnsafeBytes { Data($0) }
}

public struct SessionKeys {
    public let c2s: Data
    public let s2c: Data

    public init(psk: Data, nc: Data, ns: Data) {
        let salt = nc + ns
        c2s = hkdfSHA256(ikm: psk, salt: salt, info: ProtocolConstants.infoC2S, length: 32)
        s2c = hkdfSHA256(ikm: psk, salt: salt, info: ProtocolConstants.infoS2C, length: 32)
    }
}

private func gcmNonce(_ counter: UInt64) throws -> AES.GCM.Nonce {
    try AES.GCM.Nonce(data: Data(count: 4) + counter.bigEndianBytes)
}

/// Encrypts outgoing frames for one direction: ctr(8, BE) ‖ ciphertext ‖ tag(16).
public struct FrameSealer {
    private let key: SymmetricKey
    public private(set) var nextCounter: UInt64

    public init(key: Data, startingAt counter: UInt64 = 0) {
        self.key = SymmetricKey(data: key)
        self.nextCounter = counter
    }

    public mutating func seal(_ plaintext: Data) throws -> Data {
        guard plaintext.count <= ProtocolConstants.maxPlaintext else { throw ProtocolError.frameTooLarge }
        let counter = nextCounter
        let box = try AES.GCM.seal(plaintext, using: key, nonce: try gcmNonce(counter),
                                   authenticating: ProtocolConstants.aad)
        nextCounter += 1
        return counter.bigEndianBytes + box.ciphertext + box.tag
    }
}

/// Decrypts incoming frames for one direction and enforces the exact counter sequence.
public struct FrameOpener {
    private let key: SymmetricKey
    public private(set) var expectedCounter: UInt64

    public init(key: Data, expecting counter: UInt64 = 0) {
        self.key = SymmetricKey(data: key)
        self.expectedCounter = counter
    }

    public mutating func open(_ frame: Data) throws -> Data {
        let frame = Data(frame) // rebase indices to 0
        guard frame.count >= ProtocolConstants.frameOverhead else { throw ProtocolError.truncatedFrame }
        guard frame.count <= ProtocolConstants.maxPlaintext + ProtocolConstants.frameOverhead else {
            throw ProtocolError.frameTooLarge
        }
        let counter = UInt64(bigEndianBytes: frame.subdata(in: 0..<8))
        guard counter == expectedCounter else {
            throw ProtocolError.badCounter(expected: expectedCounter, got: counter)
        }
        let ciphertext = frame.subdata(in: 8..<(frame.count - 16))
        let tag = frame.subdata(in: (frame.count - 16)..<frame.count)
        do {
            let box = try AES.GCM.SealedBox(nonce: try gcmNonce(counter), ciphertext: ciphertext, tag: tag)
            let plaintext = try AES.GCM.open(box, using: key, authenticating: ProtocolConstants.aad)
            expectedCounter += 1
            return plaintext
        } catch {
            throw ProtocolError.authenticationFailed
        }
    }
}

public func randomBytes(_ count: Int) -> Data {
    var data = Data(count: count)
    let status = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
    precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
    return data
}
