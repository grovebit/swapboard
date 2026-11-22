import ClipSyncCore
import Foundation
import Network

/// `clipsync debug-client`: pretends to be the phone so the Mac side can be tested without it.
///   --host H (default: this Mac's Tailscale address)  --port P  --pid PID (default: newest pairing)
///   --send TEXT (send one clip after the handshake)  --sensitive  --wrong-key  --exit-after SECONDS
enum DebugClient {
    static func run(_ args: [String]) -> Never {
        func option(_ name: String) -> String? {
            guard let index = args.firstIndex(of: name), index + 1 < args.count else { return nil }
            return args[index + 1]
        }
        let config = Paths.configStore.load()
        guard let pairing = option("--pid").flatMap({ pid in config.pairings.first { $0.pid == pid } })
            ?? config.pairings.last, var psk = pairing.pskData
        else {
            print("no pairing found; run `clipsync pair` first")
            exit(1)
        }
        if args.contains("--wrong-key") { psk = randomBytes(32) }
        let host = option("--host") ?? MacInfo.tailscaleIPv4s().first ?? "127.0.0.1"
        let port = option("--port") ?? String(config.settings.port)
        let toSend = option("--send")
        let sensitive = args.contains("--sensitive")
        if let seconds = option("--exit-after").flatMap(Double.init) {
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { print("done"); exit(0) }
        }

        let session = ClientSession(pid: pairing.pid, psk: psk, deviceName: "Debug Client")
        let connection = NWConnection(to: .url(URL(string: "ws://\(host):\(port)/")!), using: webSocketParameters())
        let ws = WSConnection(connection)

        func apply(_ outputs: [SessionOutput]) {
            for output in outputs {
                switch output {
                case let .sendText(text): ws.sendText(text)
                case let .sendBinary(data): ws.sendBinary(data)
                case let .close(code):
                    print("protocol error, closing with \(code.rawValue)")
                    ws.close(code)
                case let .established(name):
                    print("established with \(name)")
                    if let toSend, let frame = try? session.seal(.clip(Clip(text: toSend, sensitive: sensitive))) {
                        ws.sendBinary(frame)
                        print("sent clip (\(toSend.utf8.count) bytes)")
                    }
                case let .message(message):
                    switch message {
                    case let .clip(clip):
                        print("CLIP sensitive=\(clip.sensitive) text=\(clip.text.debugDescription)")
                        if let ack = try? session.seal(.ack(id: clip.id)) { ws.sendBinary(ack) }
                    case let .ack(id): print("ACK \(id)")
                    default: print("message: \(message)")
                    }
                }
            }
        }

        ws.onEvent = { event in
            switch event {
            case .ready:
                print("connected to \(host):\(port)")
                apply(session.start())
            case let .text(text): apply(session.receiveText(text))
            case let .binary(data): apply(session.receiveBinary(data))
            case let .closed(code, reason):
                print("closed code=\(code.map(String.init) ?? "-") (\(reason))")
                exit(code == 1000 ? 0 : 2)
            }
        }
        ws.start()
        dispatchMain()
    }
}
