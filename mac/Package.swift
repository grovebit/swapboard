// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Swapboard",
    platforms: [.macOS(.v12)],
    products: [
        .executable(name: "swapboard", targets: ["swapboard"]),
    ],
    targets: [
        .target(name: "SwapboardCore"),
        .executableTarget(name: "swapboard", dependencies: ["SwapboardCore"]),
        .testTarget(
            name: "SwapboardCoreTests",
            dependencies: ["SwapboardCore"]
        ),
    ],
    swiftLanguageModes: [.v5]
)
