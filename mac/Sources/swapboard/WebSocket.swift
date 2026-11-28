import SwapboardCore
import Foundation
import Network

func webSocketParameters() -> NWParameters {
    let options = NWProtocolWebSocket.Options()
    options.autoReplyPing = true
    options.maximumMessageSize = ProtocolConstants.maxPlaintext + ProtocolConstants.frameOverhead
    let parameters = NWParameters.tcp
    parameters.defaultProtocolStack.applicationProtocols.insert(options, at: 0)
    return parameters
}

/// Thin wrapper over an NWConnection speaking WebSocket. All callbacks arrive on the main queue.
final class WSConnection {
    enum Event {
        case ready
        case text(String)
        case binary(Data)
        case closed(code: UInt16?, reason: String)
    }

    let connection: NWConnection
    var onEvent: ((Event) -> Void)?
    private var finished = false

    init(_ connection: NWConnection) {
        self.connection = connection
    }

    /// Remote IPv4/IPv6 address without the IPv4-mapped prefix or interface scope.
    var remoteHost: String { Self.remoteHost(of: connection) }

    static func remoteHost(of connection: NWConnection) -> String {
        guard case let .hostPort(host, _) = connection.endpoint else { return "?" }
        var text: String
        switch host {
        case let .ipv4(address): text = "\(address)"
        case let .ipv6(address): text = "\(address)"
        case let .name(name, _): text = name
        @unknown default: text = "\(host)"
        }
        if let percent = text.firstIndex(of: "%") { text = String(text[..<percent]) }
        if text.hasPrefix("::ffff:") { text = String(text.dropFirst(7)) }
        return text
    }

    func start() {
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            switch state {
            case .ready: self?.onEvent?(.ready)
            case let .failed(error):
                // A failed NWConnection keeps its socket open until it's cancelled.
                connection?.cancel()
                self?.finish(code: nil, reason: "\(error)")
            case .cancelled: self?.finish(code: nil, reason: "cancelled")
            default: break
            }
        }
        connection.start(queue: .main)
        receive()
    }

    private func receive() {
        connection.receiveMessage { [weak self] data, context, _, error in
            guard let self, !self.finished else { return }
            if let error {
                self.finish(code: nil, reason: "\(error)")
                self.connection.cancel()
                return
            }
            if let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                as? NWProtocolWebSocket.Metadata {
                switch metadata.opcode {
                case .text: self.onEvent?(.text(String(decoding: data ?? Data(), as: UTF8.self)))
                case .binary: self.onEvent?(.binary(data ?? Data()))
                case .close:
                    let code: UInt16?
                    switch metadata.closeCode {
                    case let .privateCode(value), let .applicationCode(value): code = value
                    case let .protocolCode(defined): code = defined.rawValue
                    @unknown default: code = nil
                    }
                    self.finish(code: code, reason: "closed by peer")
                    self.connection.cancel()
                    return
                default: break
                }
            }
            self.receive()
        }
    }

    func sendText(_ text: String) { send(Data(text.utf8), opcode: .text) }
    func sendBinary(_ data: Data) { send(data, opcode: .binary) }

    private func send(_ data: Data, opcode: NWProtocolWebSocket.Opcode) {
        guard !finished else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: opcode)
        let context = NWConnection.ContentContext(identifier: "ws", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
    }

    func close(_ code: CloseCode) {
        guard !finished else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .close)
        metadata.closeCode = code == .normal ? .protocolCode(.normalClosure) : .privateCode(code.rawValue)
        let context = NWConnection.ContentContext(identifier: "close", metadata: [metadata])
        // Holds the connection itself, not self: `finish` below lets the server drop this wrapper
        // before the close frame is flushed, and a connection that's never cancelled keeps its socket.
        let connection = self.connection
        connection.send(content: nil, contentContext: context, isComplete: true,
                        completion: .contentProcessed { _ in
                            // Give the close frame a moment to flush before tearing down.
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { connection.cancel() }
                        })
        finish(code: code.rawValue, reason: "closed locally")
    }

    private func finish(code: UInt16?, reason: String) {
        guard !finished else { return }
        finished = true
        onEvent?(.closed(code: code, reason: reason))
    }
}

/// One phone connection on the server: transport + protocol session.
final class Peer {
    let ws: WSConnection
    let session: ServerSession
    let connectedAt = Date()
    var lastInbound = Date()
    var onEstablished: ((Peer) -> Void)?
    var onMessage: ((Peer, Message) -> Void)?
    var onClosed: ((Peer) -> Void)?

    init(connection: NWConnection, macName: String, lookupPSK: @escaping (String) -> Data?) {
        ws = WSConnection(connection)
        session = ServerSession(macName: macName, lookupPSK: lookupPSK)
    }

    var isEstablished: Bool { session.state == .established }
    var pid: String? { session.pid }
    var device: String { session.peerName ?? "phone" }
    var host: String { ws.remoteHost }
    var interfaceNames: String {
        (ws.connection.currentPath?.availableInterfaces ?? []).map(\.name).joined(separator: ",")
    }

    func start() {
        ws.onEvent = { [weak self] event in
            guard let self else { return }
            switch event {
            case .ready:
                // A spoofed 100.64.x.x packet could still arrive over Wi-Fi/Ethernet; refuse those.
                let interfaces = self.ws.connection.currentPath?.availableInterfaces ?? []
                if interfaces.contains(where: { [.wifi, .wiredEthernet, .cellular].contains($0.type) }) {
                    Log.info("rejected a connection from \(self.host) that arrived on \(interfaces.map(\.name))")
                    self.ws.close(.authFailed)
                }
            case let .text(text):
                self.lastInbound = Date()
                self.apply(self.session.receiveText(text))
            case let .binary(data):
                self.lastInbound = Date()
                self.apply(self.session.receiveBinary(data))
            case let .closed(code, reason):
                Log.info("connection from \(self.host) closed (\(code.map(String.init) ?? "-"), \(reason))")
                self.onClosed?(self)
            }
        }
        ws.start()
    }

    func send(_ message: Message) {
        guard isEstablished, let frame = try? session.seal(message) else { return }
        ws.sendBinary(frame)
    }

    func close(_ code: CloseCode) { ws.close(code) }

    private func apply(_ outputs: [SessionOutput]) {
        for output in outputs {
            switch output {
            case let .sendText(text): ws.sendText(text)
            case let .sendBinary(data): ws.sendBinary(data)
            case let .close(code):
                Log.info("closing connection from \(host) with \(code.rawValue)")
                ws.close(code)
            case .established: onEstablished?(self)
            case let .message(message): onMessage?(self, message)
            }
        }
    }
}

/// Listens for phones. Main-queue only.
final class SyncServer {
    private var listener: NWListener?
    private(set) var peers: [Peer] = []
    private(set) var listening = false
    private(set) var listenError: String?
    private(set) var port: UInt16 = 0

    var macName = MacInfo.computerName
    var lookupPSK: (String) -> Data? = { _ in nil }
    var onEstablished: ((Peer) -> Void)?
    var onMessage: ((Peer, Message) -> Void)?

    var established: [Peer] { peers.filter(\.isEstablished) }

    /// The Tailscale address the listener is bound to. Swapboard never listens on LAN addresses.
    private(set) var boundAddress: String?

    func start(port: UInt16) {
        stop()
        self.port = port
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            listenError = "invalid port \(port)"
            return
        }
        guard let address = MacInfo.tailscaleIPv4s().first, let ipv4 = IPv4Address(address) else {
            if listenError != Self.noTailscale { Log.info("Tailscale is not connected; waiting for it before listening") }
            listenError = Self.noTailscale
            return
        }
        let parameters = webSocketParameters()
        parameters.allowLocalEndpointReuse = true
        // Bind to the Tailscale address only: the port stays closed on Wi-Fi/Ethernet.
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(ipv4), port: nwPort)
        boundAddress = address
        do {
            let listener = try NWListener(using: parameters)
            listener.stateUpdateHandler = { [weak self, weak listener] state in
                guard let self, let listener, listener === self.listener else { return }
                switch state {
                case .ready:
                    self.listening = true
                    self.listenError = nil
                    Log.info("listening on \(address):\(port) (Tailscale only)")
                case let .failed(error):
                    self.listening = false
                    self.listenError = "\(error)"
                    Log.error("listener failed: \(error)")
                case .cancelled:
                    self.listening = false
                default: break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            self.listener = listener
            listener.start(queue: .main)
        } catch {
            listenError = "\(error)"
            Log.error("cannot listen on \(port): \(error)")
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        listening = false
        for peer in peers { peer.close(.normal) }
        peers.removeAll()
    }

    static let noTailscale = "Tailscale isn't connected on this Mac"

    /// Called periodically: retries a failed listener (e.g. Tailscale came up later) and follows
    /// the Tailscale address if it changes.
    func maintain() {
        if !listening, listenError != nil {
            start(port: port)
        } else if listening, MacInfo.tailscaleIPv4s().first != boundAddress {
            Log.info("Tailscale address changed; restarting the listener")
            start(port: port)
        }
    }

    private func accept(_ connection: NWConnection) {
        // Defense in depth on top of binding to the Tailscale address: only Tailscale peers.
        let remote = WSConnection.remoteHost(of: connection)
        guard Route.isTailscale(remote) else {
            Log.info("rejected a connection from \(remote): not a Tailscale address")
            connection.cancel()
            return
        }
        let peer = Peer(connection: connection, macName: macName, lookupPSK: { [weak self] in self?.lookupPSK($0) })
        peer.onEstablished = { [weak self] peer in self?.onEstablished?(peer) }
        peer.onMessage = { [weak self] peer, message in self?.onMessage?(peer, message) }
        peer.onClosed = { [weak self] peer in self?.peers.removeAll { $0 === peer } }
        peers.append(peer)
        peer.start()
    }
}
