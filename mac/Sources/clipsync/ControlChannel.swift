import ClipSyncCore
import Foundation

// One JSON request line → one JSON response line over a 0600 Unix socket.

struct ControlRequest: Codable {
    var cmd: String
    var text: String?
    var sensitive: Bool?
}

struct ControlResponse: Codable {
    var ok: Bool
    var message: String?
    var status: AgentStatus?
    var text: String?
    var sensitive: Bool?

    static func error(_ message: String) -> ControlResponse { ControlResponse(ok: false, message: message) }
}

struct AgentStatus: Codable {
    struct Peer: Codable {
        var pid: String
        var device: String
        var host: String
        var route: String
        var since: Date
    }

    struct Event: Codable {
        var id: String
        var at: Date
        var device: String?
        var preview: String
        var sensitive: Bool
        var delivered: Bool
    }

    var paused: Bool
    var port: UInt16
    var listening: Bool
    var listenError: String?
    var peers: [Peer]
    var lastSent: Event?
    var lastReceived: Event?
    var pairingPending: Bool
}

enum ControlCoding {
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()
}

private func unixAddress(_ path: String) -> sockaddr_un? {
    var addr = sockaddr_un()
    addr.sun_family = sa_family_t(AF_UNIX)
    let bytes = Array(path.utf8)
    guard bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else { return nil }
    withUnsafeMutableBytes(of: &addr.sun_path) { raw in
        raw.copyBytes(from: bytes)
    }
    addr.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
    return addr
}

private func withSockaddr<T>(_ addr: inout sockaddr_un, _ body: (UnsafePointer<sockaddr>, socklen_t) -> T) -> T {
    withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { body($0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
    }
}

private func writeAll(_ fd: Int32, _ data: Data) -> Bool {
    data.withUnsafeBytes { raw -> Bool in
        var offset = 0
        while offset < raw.count {
            let n = write(fd, raw.baseAddress! + offset, raw.count - offset)
            if n < 0 {
                if errno == EINTR { continue }
                return false
            }
            offset += n
        }
        return true
    }
}

/// Agent side. Runs on the main queue.
final class ControlServer {
    private var listenFD: Int32 = -1
    private var acceptSource: DispatchSourceRead?
    private var clients: [Int32: (source: DispatchSourceRead, buffer: Data)] = [:]
    private var handler: ((ControlRequest) -> ControlResponse)?
    private var path = ""

    func start(path: String, handler: @escaping (ControlRequest) -> ControlResponse) throws {
        self.handler = handler
        self.path = path
        unlink(path)
        listenFD = socket(AF_UNIX, SOCK_STREAM, 0)
        guard listenFD >= 0, var addr = unixAddress(path) else { throw CLIError("cannot create control socket") }
        let bound = withSockaddr(&addr) { bind(listenFD, $0, $1) }
        guard bound == 0 else { throw CLIError("cannot bind control socket: \(String(cString: strerror(errno)))") }
        chmod(path, 0o600)
        guard listen(listenFD, 16) == 0 else { throw CLIError("cannot listen on control socket") }
        _ = fcntl(listenFD, F_SETFL, fcntl(listenFD, F_GETFL) | O_NONBLOCK)
        let source = DispatchSource.makeReadSource(fileDescriptor: listenFD, queue: .main)
        source.setEventHandler { [weak self] in self?.acceptClients() }
        source.resume()
        acceptSource = source
    }

    func stop() {
        acceptSource?.cancel()
        if listenFD >= 0 { close(listenFD) }
        unlink(path)
    }

    private func acceptClients() {
        while true {
            let fd = accept(listenFD, nil, nil)
            if fd < 0 { return }
            var on: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
            _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
            let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: .main)
            source.setEventHandler { [weak self] in self?.read(fd) }
            source.setCancelHandler { close(fd) }
            clients[fd] = (source, Data())
            source.resume()
        }
    }

    private func read(_ fd: Int32) {
        var chunk = [UInt8](repeating: 0, count: 64 * 1024)
        let n = Darwin.read(fd, &chunk, chunk.count)
        if n < 0, errno == EAGAIN || errno == EINTR { return }
        guard n > 0, var client = clients[fd] else { return finish(fd) }
        client.buffer.append(contentsOf: chunk[0..<n])
        if client.buffer.count > 16 * 1024 * 1024 { return finish(fd) }
        clients[fd] = client
        guard let newline = client.buffer.firstIndex(of: 0x0A) else { return }
        let line = client.buffer[..<newline]
        let response: ControlResponse
        if let request = try? ControlCoding.decoder.decode(ControlRequest.self, from: line) {
            response = handler?(request) ?? .error("agent not ready")
        } else {
            response = .error("bad request")
        }
        if var data = try? ControlCoding.encoder.encode(response) {
            data.append(0x0A)
            _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) & ~O_NONBLOCK)
            _ = writeAll(fd, data)
        }
        finish(fd)
    }

    private func finish(_ fd: Int32) {
        clients.removeValue(forKey: fd)?.source.cancel()
    }
}

/// CLI side.
enum ControlClient {
    struct NotRunning: Error {}

    static func request(_ request: ControlRequest, timeout: Int = 5) throws -> ControlResponse {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw NotRunning() }
        defer { close(fd) }
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: timeout, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        guard var addr = unixAddress(Paths.socketPath),
              withSockaddr(&addr, { connect(fd, $0, $1) }) == 0
        else { throw NotRunning() }

        var data = try ControlCoding.encoder.encode(request)
        data.append(0x0A)
        guard writeAll(fd, data) else { throw NotRunning() }

        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 64 * 1024)
        while !buffer.contains(0x0A) {
            let n = read(fd, &chunk, chunk.count)
            if n <= 0 { break }
            buffer.append(contentsOf: chunk[0..<n])
        }
        guard let newline = buffer.firstIndex(of: 0x0A) else { throw CLIError("no response from agent") }
        return try ControlCoding.decoder.decode(ControlResponse.self, from: buffer[..<newline])
    }

    static func isAgentRunning() -> Bool {
        (try? request(ControlRequest(cmd: "ping"), timeout: 1))?.ok ?? false
    }
}
