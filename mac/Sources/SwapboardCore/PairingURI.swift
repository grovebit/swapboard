import Foundation

/// `swapboard://pair?v=1&pid=…&name=…&k=…&h=…&p=…` (PROTOCOL.md §1).
public struct PairingInfo: Equatable {
    public var pid: String
    public var name: String
    public var psk: Data
    public var hosts: [String]
    public var port: UInt16

    public init(pid: String, name: String, psk: Data, hosts: [String], port: UInt16) {
        self.pid = pid
        self.name = name
        self.psk = psk
        self.hosts = hosts
        self.port = port
    }

    public var uri: String {
        let h = hosts.map(PercentEncoding.encode).joined(separator: ",")
        return "swapboard://pair?v=1&pid=\(PercentEncoding.encode(pid))&name=\(PercentEncoding.encode(name))"
            + "&k=\(Base64URL.encode(psk))&h=\(h)&p=\(port)"
    }

    public static func parse(_ string: String) throws -> PairingInfo {
        guard let components = URLComponents(string: string),
              components.scheme == "swapboard", components.host == "pair",
              let items = components.queryItems
        else { throw ProtocolError.malformedMessage("not a swapboard pairing link") }

        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }

        guard value("v") == "1" else { throw ProtocolError.unsupportedVersion(Int(value("v") ?? "") ?? -1) }
        guard let pid = value("pid"), !pid.isEmpty else { throw ProtocolError.malformedMessage("missing pid") }
        guard let k = value("k"), let psk = Base64URL.decode(k), psk.count == ProtocolConstants.pskLength else {
            throw ProtocolError.malformedMessage("bad key")
        }
        let hosts = (value("h") ?? "").split(separator: ",").map(String.init).filter { !$0.isEmpty }
        guard !hosts.isEmpty else { throw ProtocolError.malformedMessage("missing hosts") }
        guard let portString = value("p"), let port = UInt16(portString), port > 0 else {
            throw ProtocolError.malformedMessage("bad port")
        }
        return PairingInfo(pid: pid, name: value("name") ?? "Mac", psk: psk, hosts: hosts, port: port)
    }
}
