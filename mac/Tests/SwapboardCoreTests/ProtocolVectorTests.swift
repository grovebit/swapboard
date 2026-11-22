import Foundation
import XCTest
@testable import SwapboardCore

/// Runs the shared vectors in protocol/vectors.json; the Kotlin suite runs the same file.
final class ProtocolVectorTests: XCTestCase {
    struct Vectors: Decodable {
        struct HKDFCase: Decodable { let ikm, salt, info: String; let length: Int; let okm: String }
        struct Session: Decodable { let psk, nc, ns, salt_hex, k_c2s_hex, k_s2c_hex, aad: String }
        struct Frame: Decodable { let direction: String; let counter: UInt64; let plaintext, frame_hex: String }
        struct InvalidFrame: Decodable { let reason, direction: String; let expected_counter: UInt64; let frame_hex: String }
        struct Pairing: Decodable { let uri, pid, name, psk: String; let hosts: [String]; let port: UInt16 }
        let hkdf_rfc5869_case1: HKDFCase
        let session: Session
        let frames: [Frame]
        let invalid_frames: [InvalidFrame]
        let pairing_uri: Pairing
    }

    static let vectors: Vectors = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("protocol/vectors.json")
        let data = try! Data(contentsOf: url)
        return try! JSONDecoder().decode(Vectors.self, from: data)
    }()

    var v: Vectors { Self.vectors }
    func hex(_ s: String) -> Data { Hex.decode(s)! }
    var keys: SessionKeys {
        SessionKeys(psk: Base64URL.decode(v.session.psk)!, nc: Base64URL.decode(v.session.nc)!,
                    ns: Base64URL.decode(v.session.ns)!)
    }
    func key(_ direction: String) -> Data { direction == "c2s" ? keys.c2s : keys.s2c }

    func testHKDFMatchesRFC5869() {
        let c = v.hkdf_rfc5869_case1
        XCTAssertEqual(Hex.encode(hkdfSHA256(ikm: hex(c.ikm), salt: hex(c.salt), info: hex(c.info), length: c.length)), c.okm)
    }

    func testSessionKeys() {
        XCTAssertEqual(Base64URL.decode(v.session.nc)! + Base64URL.decode(v.session.ns)!, hex(v.session.salt_hex))
        XCTAssertEqual(Hex.encode(keys.c2s), v.session.k_c2s_hex)
        XCTAssertEqual(Hex.encode(keys.s2c), v.session.k_s2c_hex)
        XCTAssertEqual(String(decoding: ProtocolConstants.aad, as: UTF8.self), v.session.aad)
    }

    func testSealProducesExactFrames() throws {
        for frame in v.frames {
            var sealer = FrameSealer(key: key(frame.direction), startingAt: frame.counter)
            let sealed = try sealer.seal(Data(frame.plaintext.utf8))
            XCTAssertEqual(Hex.encode(sealed), frame.frame_hex, "\(frame.direction) #\(frame.counter)")
        }
    }

    func testOpenDecryptsFrames() throws {
        for frame in v.frames {
            var opener = FrameOpener(key: key(frame.direction), expecting: frame.counter)
            let plaintext = try opener.open(hex(frame.frame_hex))
            XCTAssertEqual(String(decoding: plaintext, as: UTF8.self), frame.plaintext)
            XCTAssertEqual(opener.expectedCounter, frame.counter + 1)
        }
    }

    func testRejectsInvalidFrames() {
        for bad in v.invalid_frames {
            var opener = FrameOpener(key: key(bad.direction), expecting: bad.expected_counter)
            XCTAssertThrowsError(try opener.open(hex(bad.frame_hex)), bad.reason)
            XCTAssertEqual(opener.expectedCounter, bad.expected_counter, "counter must not advance: \(bad.reason)")
        }
    }

    func testMessagesDecodeAndRoundTrip() throws {
        let decoded = try v.frames.map { try Message.decode(Data($0.plaintext.utf8)) }
        XCTAssertEqual(decoded[0], .auth(device: "Test Phone"))
        XCTAssertEqual(decoded[1], .welcome(mac: "Test Mac"))
        guard case let .clip(clip) = decoded[2] else { return XCTFail("expected clip") }
        XCTAssertEqual(clip.text, "Hello from Android 👋\nLine 2 — ünïcödé")
        XCTAssertFalse(clip.sensitive)
        XCTAssertEqual(clip.ts, 1_763_800_000_000)
        XCTAssertEqual(decoded[3], .ack(id: clip.id))
        guard case let .clip(secret) = decoded[4] else { return XCTFail("expected clip") }
        XCTAssertTrue(secret.sensitive)
        XCTAssertEqual(decoded[5], .ping)
        XCTAssertEqual(decoded[6], .pong)
        for message in decoded {
            XCTAssertEqual(try Message.decode(try message.encoded()), message)
        }
        XCTAssertEqual(try Message.decode(Data(#"{"type":"future","x":1}"#.utf8)), .unknown(type: "future"))
        XCTAssertThrowsError(try Message.decode(Data(#"{"type":"clip","text":"no id"}"#.utf8)))
    }

    func testPairingURI() throws {
        let p = v.pairing_uri
        let info = try PairingInfo.parse(p.uri)
        XCTAssertEqual(info.pid, p.pid)
        XCTAssertEqual(info.name, p.name)
        XCTAssertEqual(Base64URL.encode(info.psk), p.psk)
        XCTAssertEqual(info.hosts, p.hosts)
        XCTAssertEqual(info.port, p.port)
        XCTAssertEqual(info.uri, p.uri, "Swift must generate the exact same link")
        XCTAssertThrowsError(try PairingInfo.parse(p.uri.replacingOccurrences(of: "v=1", with: "v=2")))
        XCTAssertThrowsError(try PairingInfo.parse("swapboard://pair?v=1&pid=x&k=AAAA&h=a&p=1"))
    }
}

final class SessionTests: XCTestCase {
    let psk = randomBytes(32)

    /// Shuttles outputs between a client and a server session; returns everything delivered as messages.
    private func pump(_ outputs: [SessionOutput], to server: ServerSession, client: ClientSession,
                      fromClient: Bool) -> [SessionOutput] {
        var collected: [SessionOutput] = []
        for output in outputs {
            let next: [SessionOutput]
            switch output {
            case let .sendText(text): next = fromClient ? server.receiveText(text) : client.receiveText(text)
            case let .sendBinary(data): next = fromClient ? server.receiveBinary(data) : client.receiveBinary(data)
            default: collected.append(output); continue
            }
            collected += pump(next, to: server, client: client, fromClient: !fromClient)
        }
        return collected
    }

    func testHandshakeAndClipExchange() throws {
        let server = ServerSession(macName: "Mac") { [psk] pid in pid == "p1" ? psk : nil }
        let client = ClientSession(pid: "p1", psk: psk, deviceName: "Phone")
        let events = pump(client.start(), to: server, client: client, fromClient: true)
        XCTAssertEqual(server.state, .established)
        XCTAssertEqual(client.state, .established)
        XCTAssertEqual(server.peerName, "Phone")
        XCTAssertEqual(client.peerName, "Mac")
        XCTAssertTrue(events.contains(.established(peerName: "Phone")))
        XCTAssertTrue(events.contains(.established(peerName: "Mac")))

        let clip = Clip(text: "hi", sensitive: false)
        let frame = try client.seal(.clip(clip))
        XCTAssertEqual(server.receiveBinary(frame), [.message(.clip(clip))])

        // ping is answered by the session itself
        let ping = try client.seal(.ping)
        guard case let .sendBinary(pong)? = server.receiveBinary(ping).first else { return XCTFail("no pong") }
        XCTAssertEqual(client.receiveBinary(pong), [.message(.pong)])

        // replaying an old frame closes the session
        XCTAssertEqual(server.receiveBinary(frame), [.close(.authFailed)])
        XCTAssertEqual(server.state, .closed)
    }

    func testWrongKeyIsRejected() {
        let server = ServerSession(macName: "Mac") { [psk] _ in psk }
        let client = ClientSession(pid: "p1", psk: randomBytes(32), deviceName: "Phone")
        let events = pump(client.start(), to: server, client: client, fromClient: true)
        XCTAssertTrue(events.contains(.close(.authFailed)))
        XCTAssertNotEqual(server.state, .established)
    }

    func testUnknownPairingAndVersion() {
        let server = ServerSession(macName: "Mac") { _ in nil }
        XCTAssertEqual(server.receiveText(#"{"v":1,"pid":"nope","nc":"oKGio6SlpqeoqaqrrK2urw"}"#), [.close(.authFailed)])
        let server2 = ServerSession(macName: "Mac") { [psk] _ in psk }
        XCTAssertEqual(server2.receiveText(#"{"v":2,"pid":"p","nc":"oKGio6SlpqeoqaqrrK2urw"}"#), [.close(.unsupportedVersion)])
    }
}

final class OutboxAndConfigTests: XCTestCase {
    func testOutboxKeepsOnlyLatestFreshClip() {
        var outbox = Outbox(ttl: 300)
        let t0 = Date()
        let a = Clip(text: "a", sensitive: false), b = Clip(text: "b", sensitive: false)
        outbox.put(a, now: t0)
        outbox.put(b, now: t0)
        XCTAssertEqual(outbox.freshPending(now: t0.addingTimeInterval(10)), b)
        outbox.acknowledge(a.id)
        XCTAssertNotNil(outbox.pending, "acking an older clip keeps the newer one")
        XCTAssertNil(outbox.freshPending(now: t0.addingTimeInterval(301)), "expired clips are dropped")
        outbox.put(a, now: t0)
        outbox.acknowledge(a.id)
        XCTAssertNil(outbox.pending)
    }

    func testRecentIDs() {
        var ids = RecentIDs(capacity: 2)
        XCTAssertTrue(ids.insert("a"))
        XCTAssertFalse(ids.insert("a"))
        XCTAssertTrue(ids.insert("b"))
        XCTAssertTrue(ids.insert("c"))
        XCTAssertTrue(ids.insert("a"), "a fell out of the window")
    }

    func testConfigRoundTripAndPrune() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = ConfigStore(directory: dir)
        let now = Date()
        try store.update { config in
            config.pairings.append(Pairing(pid: "old", psk: randomBytes(32), created: now.addingTimeInterval(-3600)))
            var ok = Pairing(pid: "ok", psk: randomBytes(32), created: now)
            ok.confirmed = true
            config.pairings.append(ok)
            config.settings.port = 50000
        }
        var loaded = store.load()
        XCTAssertEqual(loaded.settings.port, 50000)
        XCTAssertTrue(loaded.pruneExpired(now: now))
        XCTAssertEqual(loaded.pairings.map(\.pid), ["ok"])
        let attrs = try FileManager.default.attributesOfItem(atPath: store.url.path)
        XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        // A config written by an older version (missing keys) still loads with defaults.
        try Data(#"{"pairings":[]}"#.utf8).write(to: store.url)
        XCTAssertEqual(store.load().settings, Settings())
    }
}
