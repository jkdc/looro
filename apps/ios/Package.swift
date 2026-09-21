// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "LooroCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "LooroCore", targets: ["LooroCore"])],
    targets: [
        .target(name: "LooroCore", path: "Core"),
        .testTarget(name: "LooroCoreTests", dependencies: ["LooroCore"], path: "Tests")
    ]
)
