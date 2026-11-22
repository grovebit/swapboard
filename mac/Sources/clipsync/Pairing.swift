import AppKit
import ClipSyncCore
import CoreImage
import Foundation

enum PairCommand {
    /// Creates a new pairing (replacing any unscanned one) and shows its QR code.
    static func run(show: Bool = true) throws -> String {
        try Paths.ensureDataDir()
        let psk = randomBytes(ProtocolConstants.pskLength)
        let pid = Base64URL.encode(randomBytes(12))
        let config = try Paths.configStore.update { config in
            config.pairings.removeAll { !$0.confirmed }
            config.pairings.append(Pairing(pid: pid, psk: psk))
        }
        let hosts = MacInfo.tailscaleIPv4s()
        guard !hosts.isEmpty else {
            throw CLIError("Tailscale isn't connected on this Mac. ClipSync only syncs over Tailscale; connect it and try again")
        }
        let info = PairingInfo(pid: pid, name: MacInfo.computerName, psk: psk, hosts: hosts,
                               port: config.settings.port)
        try writeQR(info.uri, to: Paths.qrURL)
        _ = try? ControlClient.request(ControlRequest(cmd: "reload"))
        if show { showQR() }
        return "Scan the QR code with the ClipSync app (valid for 10 minutes)"
    }

    /// The pairing link, for pasting into the app instead of scanning.
    static func link() throws -> String {
        guard let pairing = Paths.configStore.load().pairings.last(where: { !$0.confirmed }),
              let psk = pairing.pskData
        else { throw CLIError("No pairing in progress. Run `clipsync pair` first") }
        let config = Paths.configStore.load()
        return PairingInfo(pid: pairing.pid, name: MacInfo.computerName, psk: psk,
                           hosts: MacInfo.tailscaleIPv4s(), port: config.settings.port).uri
    }

    static func showQR() {
        guard FileManager.default.fileExists(atPath: Paths.qrURL.path) else { return }
        Shell.spawn("/usr/bin/qlmanage", ["-p", Paths.qrURL.path])
    }

    static func writeQR(_ text: String, to url: URL) throws {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { throw CLIError("QR generator unavailable") }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { throw CLIError("QR generation failed") }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let qr = CIContext().createCGImage(scaled, from: scaled.extent) else {
            throw CLIError("QR rendering failed")
        }
        let padding = 60
        let size = qr.width + padding * 2
        guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
                                            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let context = NSGraphicsContext(bitmapImageRep: bitmap)
        else { throw CLIError("QR rendering failed") }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        NSColor.white.setFill()
        NSRect(x: 0, y: 0, width: size, height: size).fill()
        context.cgContext.interpolationQuality = .none
        context.cgContext.draw(qr, in: CGRect(x: padding, y: padding, width: qr.width, height: qr.height))
        NSGraphicsContext.restoreGraphicsState()
        guard let png = bitmap.representation(using: .png, properties: [:]) else { throw CLIError("PNG encoding failed") }
        try? FileManager.default.removeItem(at: url)
        guard FileManager.default.createFile(atPath: url.path, contents: png,
                                             attributes: [.posixPermissions: 0o600])
        else { throw CLIError("cannot write \(url.path)") }
    }
}
