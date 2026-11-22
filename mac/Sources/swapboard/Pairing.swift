import AppKit
import SwapboardCore
import CoreImage
import Foundation

enum PairCommand {
    /// Creates a new pairing, replacing any that wasn't scanned yet. Needs Tailscale, because the
    /// link only ever carries this Mac's Tailscale addresses.
    static func createPairing() throws -> PairingInfo {
        let hosts = MacInfo.tailscaleIPv4s()
        guard !hosts.isEmpty else {
            throw CLIError("Tailscale isn't connected on this Mac. Swapboard only syncs over Tailscale; connect it and try again")
        }
        try Paths.ensureDataDir()
        let psk = randomBytes(ProtocolConstants.pskLength)
        let pid = Base64URL.encode(randomBytes(12))
        let config = try Paths.configStore.update { config in
            config.pairings.removeAll { !$0.confirmed }
            config.pairings.append(Pairing(pid: pid, psk: psk))
        }
        return PairingInfo(pid: pid, name: MacInfo.computerName, psk: psk, hosts: hosts, port: config.settings.port)
    }

    /// CLI and Alfred without the menu-bar app: creates a pairing and shows its QR code in Quick Look.
    static func run(show: Bool = true) throws -> String {
        let info = try createPairing()
        try writeQR(info.uri, to: Paths.qrURL)
        _ = try? ControlClient.request(ControlRequest(cmd: "reload"))
        if show { showQR() }
        return "Scan the QR code with the Swapboard app (valid for 10 minutes)"
    }

    /// The pairing link, for pasting into the app instead of scanning.
    static func link() throws -> String {
        guard let pairing = Paths.configStore.load().pairings.last(where: { !$0.confirmed }),
              let psk = pairing.pskData
        else { throw CLIError("No pairing in progress. Run `swapboard pair` first") }
        let config = Paths.configStore.load()
        return PairingInfo(pid: pairing.pid, name: MacInfo.computerName, psk: psk,
                           hosts: MacInfo.tailscaleIPv4s(), port: config.settings.port).uri
    }

    static func showQR() {
        guard FileManager.default.fileExists(atPath: Paths.qrURL.path) else { return }
        Shell.spawn("/usr/bin/qlmanage", ["-p", Paths.qrURL.path])
    }

    static func writeQR(_ text: String, to url: URL) throws {
        guard let png = QRCode.pngData(for: text) else { throw CLIError("QR code generation failed") }
        try? FileManager.default.removeItem(at: url)
        guard FileManager.default.createFile(atPath: url.path, contents: png,
                                             attributes: [.posixPermissions: 0o600])
        else { throw CLIError("cannot write \(url.path)") }
    }
}

enum QRCode {
    /// Black-on-white QR code with a quiet zone, scaled up without smoothing so it stays sharp.
    static func image(for text: String, moduleSize: Int = 10, quietZone: Int = 4) -> CGImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scale = CGFloat(moduleSize)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let qr = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        let padding = moduleSize * quietZone
        let size = qr.width + padding * 2
        guard let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: size, height: size))
        context.interpolationQuality = .none
        context.draw(qr, in: CGRect(x: padding, y: padding, width: qr.width, height: qr.height))
        return context.makeImage()
    }

    static func pngData(for text: String) -> Data? {
        guard let image = image(for: text, quietZone: 6) else { return nil }
        return NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])
    }
}
