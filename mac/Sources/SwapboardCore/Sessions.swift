import Foundation

/// What a session wants the transport to do after it processes an input.
public enum SessionOutput: Equatable {
    case sendText(String)
    case sendBinary(Data)
    case close(CloseCode)
    /// Handshake finished; `peerName` is the device name (server side) or Mac name (client side).
    case established(peerName: String)
    case message(Message)
}

/// Server half of the protocol (the Mac). Socket-independent so it can be unit-tested.
public final class ServerSession {
    public enum State: Equatable { case awaitingHello, awaitingAuth, established, closed }

    public private(set) var state: State = .awaitingHello
    public private(set) var pid: String?
    public private(set) var peerName: String?

    private let macName: String
    private let lookupPSK: (String) -> Data?
    private let random: (Int) -> Data
    private var sealer: FrameSealer?
    private var opener: FrameOpener?

    public init(macName: String, lookupPSK: @escaping (String) -> Data?, random: @escaping (Int) -> Data = randomBytes) {
        self.macName = macName
        self.lookupPSK = lookupPSK
        self.random = random
    }

    public func receiveText(_ text: String) -> [SessionOutput] {
        guard state == .awaitingHello else { return fail(.authFailed) }
        guard let hello = try? JSONDecoder().decode(ClientHello.self, from: Data(text.utf8)) else {
            return fail(.authFailed)
        }
        guard hello.v == ProtocolConstants.version else { return fail(.unsupportedVersion) }
        guard let psk = lookupPSK(hello.pid),
              let nc = Base64URL.decode(hello.nc), nc.count == ProtocolConstants.nonceLength
        else { return fail(.authFailed) }

        let ns = random(ProtocolConstants.nonceLength)
        let keys = SessionKeys(psk: psk, nc: nc, ns: ns)
        sealer = FrameSealer(key: keys.s2c)
        opener = FrameOpener(key: keys.c2s)
        pid = hello.pid
        state = .awaitingAuth
        let reply = String(decoding: try! JSONEncoder().encode(ServerHello(ns: ns)), as: UTF8.self)
        return [.sendText(reply)]
    }

    public func receiveBinary(_ frame: Data) -> [SessionOutput] {
        guard state == .awaitingAuth || state == .established, var opener else { return fail(.authFailed) }
        let message: Message
        do {
            let plaintext = try opener.open(frame)
            self.opener = opener
            message = try Message.decode(plaintext)
        } catch let error as ProtocolError {
            return fail(error.closeCode)
        } catch {
            return fail(.authFailed)
        }

        if state == .awaitingAuth {
            guard case let .auth(device) = message else { return fail(.authFailed) }
            guard let welcome = try? seal(.welcome(mac: macName)) else { return fail(.authFailed) }
            peerName = device
            state = .established
            return [.sendBinary(welcome), .established(peerName: device)]
        }

        switch message {
        case .ping:
            guard let pong = try? seal(.pong) else { return fail(.authFailed) }
            return [.sendBinary(pong)]
        case .auth, .welcome:
            return fail(.authFailed)
        case .unknown:
            return []
        default:
            return [.message(message)]
        }
    }

    /// Encrypts an outgoing message. Only valid once established (or for the welcome).
    public func seal(_ message: Message) throws -> Data {
        guard var sealer else { throw ProtocolError.unexpected("session not ready") }
        let frame = try sealer.seal(try message.encoded())
        self.sealer = sealer
        return frame
    }

    private func fail(_ code: CloseCode) -> [SessionOutput] {
        state = .closed
        return [.close(code)]
    }
}

/// Client half of the protocol. The Android app has its own Kotlin port; this one powers
/// `swapboard debug-client` and the handshake unit tests.
public final class ClientSession {
    public enum State: Equatable { case idle, awaitingServerHello, awaitingWelcome, established, closed }

    public private(set) var state: State = .idle
    public private(set) var peerName: String?

    private let pid: String
    private let psk: Data
    private let deviceName: String
    private let random: (Int) -> Data
    private var nc = Data()
    private var sealer: FrameSealer?
    private var opener: FrameOpener?

    public init(pid: String, psk: Data, deviceName: String, random: @escaping (Int) -> Data = randomBytes) {
        self.pid = pid
        self.psk = psk
        self.deviceName = deviceName
        self.random = random
    }

    public func start() -> [SessionOutput] {
        nc = random(ProtocolConstants.nonceLength)
        state = .awaitingServerHello
        let hello = String(decoding: try! JSONEncoder().encode(ClientHello(pid: pid, nc: nc)), as: UTF8.self)
        return [.sendText(hello)]
    }

    public func receiveText(_ text: String) -> [SessionOutput] {
        guard state == .awaitingServerHello,
              let hello = try? JSONDecoder().decode(ServerHello.self, from: Data(text.utf8)),
              hello.v == ProtocolConstants.version,
              let ns = Base64URL.decode(hello.ns), ns.count == ProtocolConstants.nonceLength
        else { return fail(.authFailed) }
        let keys = SessionKeys(psk: psk, nc: nc, ns: ns)
        sealer = FrameSealer(key: keys.c2s)
        opener = FrameOpener(key: keys.s2c)
        state = .awaitingWelcome
        guard let auth = try? seal(.auth(device: deviceName)) else { return fail(.authFailed) }
        return [.sendBinary(auth)]
    }

    public func receiveBinary(_ frame: Data) -> [SessionOutput] {
        guard state == .awaitingWelcome || state == .established, var opener else { return fail(.authFailed) }
        let message: Message
        do {
            let plaintext = try opener.open(frame)
            self.opener = opener
            message = try Message.decode(plaintext)
        } catch let error as ProtocolError {
            return fail(error.closeCode)
        } catch {
            return fail(.authFailed)
        }
        if state == .awaitingWelcome {
            guard case let .welcome(mac) = message else { return fail(.authFailed) }
            peerName = mac
            state = .established
            return [.established(peerName: mac)]
        }
        switch message {
        case .ping:
            guard let pong = try? seal(.pong) else { return fail(.authFailed) }
            return [.sendBinary(pong)]
        case .auth, .welcome:
            return fail(.authFailed)
        case .unknown:
            return []
        default:
            return [.message(message)]
        }
    }

    public func seal(_ message: Message) throws -> Data {
        guard var sealer else { throw ProtocolError.unexpected("session not ready") }
        let frame = try sealer.seal(try message.encoded())
        self.sealer = sealer
        return frame
    }

    private func fail(_ code: CloseCode) -> [SessionOutput] {
        state = .closed
        return [.close(code)]
    }
}
