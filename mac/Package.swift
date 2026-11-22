// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ClipSync",
    platforms: [.macOS(.v12)],
    products: [
        .executable(name: "clipsync", targets: ["clipsync"]),
    ],
    targets: [
        .target(name: "ClipSyncCore"),
        .executableTarget(name: "clipsync", dependencies: ["ClipSyncCore"]),
        .testTarget(
            name: "ClipSyncCoreTests",
            dependencies: ["ClipSyncCore"]
        ),
    ],
    swiftLanguageModes: [.v5]
)
